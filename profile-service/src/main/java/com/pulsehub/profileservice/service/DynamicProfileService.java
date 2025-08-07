package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.domain.DynamicProfileSerializer;
import com.pulsehub.profileservice.domain.DeviceClass;
import com.pulsehub.profileservice.domain.event.CleanupCompletedEvent;
import com.pulsehub.profileservice.domain.event.CleanupFailedEvent;
import com.pulsehub.profileservice.repository.StaticUserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.data.redis.core.*;

import com.pulsehub.profileservice.repository.StaticUserProfileRepository;


import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 动态用户画像服务
 * 
 * 【设计目标】
 * - 管理高频更新的动态用户行为数据
 * - 基于Redis实现高性能读写操作
 * - 支持实时活跃状态跟踪和行为统计
 * - 提供设备分类和用户行为分析能力
 * 
 * 【与静态画像区别】
 * - 存储介质：Redis替代数据库，支持高频写入
 * - 更新频率：实时更新，支持秒级数据变更
 * - 数据特性：临时性强，支持TTL自动过期
 * - 一致性要求：最终一致性，性能优先
 * 
 * 【Redis存储策略】
 * - Key模式：dynamic_profile:{userId}
 * - TTL策略：默认7天，可配置
 * - 序列化：JSON格式，便于调试和扩展
 * - 批量操作：支持Pipeline提升性能
 * 
 * 【缓存层级】
 * - L1缓存：本地JVM缓存（5分钟TTL）
 * - L2缓存：Redis存储（7天TTL）
 * - 备份策略：可选的异步数据库持久化
 */
@Service
@Slf4j
public class DynamicProfileService {

    // Redis模板，用于操作动态画像数据（已优化：支持Java 8时间类型）
    private final RedisTemplate<String, Object> redisTemplate;

    private final StaticUserProfileRepository staticProfileRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final DynamicProfileSerializer dynamicProfileSerializer;

//    private final Executor cleanupTaskExecutor;
    
    // 构造方法初始化Redis脚本
    public DynamicProfileService(RedisTemplate<String, Object> redisTemplate,
                                 StaticUserProfileRepository staticProfileRepository,
                                 ApplicationEventPublisher eventPublisher, DynamicProfileSerializer dynamicProfileSerializer) {
        this.redisTemplate = redisTemplate;
        this.staticProfileRepository = staticProfileRepository;
        this.eventPublisher = eventPublisher;
        this.dynamicProfileSerializer = dynamicProfileSerializer;
        // 初始化原子清理脚本
        this.atomicCleanupScript = RedisScript.of(ATOMIC_CLEANUP_LUA_SCRIPT, List.class);
    }
    
    // Redis Key前缀
    private static final String PROFILE_KEY_PREFIX = "dynamic_profile:";
    
    // 活跃用户索引Key前缀（用于快速查询活跃用户）
    private static final String ACTIVE_USERS_KEY = "active_users:";
    
    // 设备分类索引Key前缀
    private static final String DEVICE_INDEX_KEY = "device_index:";
    
    // 页面浏览数索引Key（ZSet，用于高效查询高参与度用户）
    private static final String PAGEVIEW_INDEX_KEY = "pageview_index";
    
    // 用户总数计数器Key（用于高效统计总用户数）
    private static final String USER_COUNT_KEY = "dynamic_profile_count";
    
    // 用户过期时间索引Key（ZSet，用于TTL感知的用户管理）
    private static final String USER_EXPIRY_INDEX = "user_expiry_index";
    
    // 默认TTL（7天）
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);
    
    // 活跃用户TTL（24小时）
    private static final Duration ACTIVE_USERS_TTL = Duration.ofHours(24);
    
    // 原子清理配置
    private static final int DEFAULT_BATCH_SIZE = 1000;       // 单次处理最大用户数
    private static final int MAX_ITERATIONS = 100;           // 最大迭代次数
    private static final Duration LOCK_EXPIRE_TIME = Duration.ofMinutes(50);  // 锁过期时间
    private static final Duration MAX_EXECUTION_TIME = Duration.ofMinutes(45); // 最大执行时间
    
    // 分布式锁Key
    private static final String CLEANUP_LOCK_KEY = "ttl_cleanup_lock";
    
    /**
     * 原子清理Lua脚本
     * 保证计数器更新和索引清理的原子性，解决"部分成功"问题
     *
     */
    private static final String ATOMIC_CLEANUP_LUA_SCRIPT = """
        -- 参数说明:
        -- KEYS[1]: 过期时间索引 ZSet (user_expiry_index)
        -- KEYS[2]: 用户计数器 (dynamic_profile_count)
        -- KEYS[3]: 用户profile前缀 (dynamic_profile:)
        -- ARGV[1]: 当前时间戳
        -- ARGV[2]: 批处理大小
        
        local expiryIndexKey = KEYS[1]
        local counterKey = KEYS[2]
        local profilePrefix = KEYS[3]
        local currentTime = tonumber(ARGV[1])
        local batchSize = tonumber(ARGV[2])
        
        -- 第一步：获取过期用户列表（限制批次大小）
        local expiredUsers = redis.call('ZRANGEBYSCORE', expiryIndexKey, 0, currentTime, 'LIMIT', 0, batchSize)
        local candidateCount = #expiredUsers
        
        if candidateCount == 0 then
            return {0, 0, 0}  -- {实际过期数, 候选数, 剩余数}
        end
        
        -- 第二步：验证用户是否真的已过期
        local actualExpiredUsers = {}
        local actualExpiredCount = 0
        
        for i = 1, candidateCount do
            local userId = expiredUsers[i]
            local profileKey = profilePrefix .. userId
            
            -- 检查Redis中是否还存在用户数据
            local exists = redis.call('EXISTS', profileKey)
            if exists == 0 then
                -- 确实已过期
                actualExpiredUsers[actualExpiredCount + 1] = userId
                actualExpiredCount = actualExpiredCount + 1
            end
        end
        
        if actualExpiredCount == 0 then
            -- 所有候选用户都还存在，清理过期的索引记录
            redis.call('ZREMRANGEBYSCORE', expiryIndexKey, 0, currentTime)
            return {0, candidateCount, 0}
        end
        
        -- 第三步：原子执行清理操作
        -- 3.1 更新计数器
        redis.call('DECRBY', counterKey, actualExpiredCount)
        
        -- 3.2 从过期索引中移除已处理的用户
        for i = 1, actualExpiredCount do
            redis.call('ZREM', expiryIndexKey, actualExpiredUsers[i])
        end
        
        -- 3.3 清理剩余的过期索引记录
        redis.call('ZREMRANGEBYSCORE', expiryIndexKey, 0, currentTime)
        
        -- 第四步：检查是否还有更多过期用户需要处理
        local remainingCount = redis.call('ZCOUNT', expiryIndexKey, 0, currentTime)
        
        return {actualExpiredCount, candidateCount, remainingCount}
        """;
    
    // 编译后的Redis脚本
    private final RedisScript<List> atomicCleanupScript;

    // ===================================================================
    // 核心CRUD操作
    // ===================================================================

    /**
     * 创建或初始化动态用户画像
     * 
     * @param dynamicProfile 动态用户画像数据
     * @return 保存后的用户画像
     */
    public DynamicUserProfile createProfile(DynamicUserProfile dynamicProfile) {
        // 参数验证
        if (!dynamicProfile.isValid()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        // 设置初始值
        if (dynamicProfile.getPageViewCount() == null) {
            dynamicProfile.setPageViewCount(0L);
        }
        if (dynamicProfile.getVersion() == null) {
            dynamicProfile.setVersion(1L);
        }
        if (dynamicProfile.getUpdatedAt() == null) {
            dynamicProfile.setUpdatedAt(Instant.now());
        }
        if (dynamicProfile.getLastActiveAt() == null){
            dynamicProfile.setLastActiveAt(Instant.now());
        }
        if (dynamicProfile.getRecentDeviceTypes() == null) {
            dynamicProfile.setRecentDeviceTypes(new HashSet<>());
        }

        // 保存到Redis（RedisTemplate已优化支持Java 8时间类型）
        // key:    dynamic_profile:user123
        String key = buildProfileKey(dynamicProfile.getUserId());
        String profileJson = dynamicProfileSerializer.serialize(dynamicProfile);

        if (profileJson == null) {
            return null;
        }

        redisTemplate.opsForValue().set(key, profileJson, DEFAULT_TTL);
        
        // 如果用户当前活跃，添加到活跃用户索引
        addToActiveUsersIndex(dynamicProfile.getUserId(), dynamicProfile.getLastActiveAt());
        
        // 添加到页面浏览数索引
        updatePageViewIndex(dynamicProfile.getUserId(), dynamicProfile.getPageViewCount());
        
        // 📅 记录用户过期时间到索引（TTL感知管理）
        recordUserExpiryTime(dynamicProfile.getUserId());
        
        // 递增用户总数计数器
        incrementUserCount();

        
        log.info("✅ 创建动态用户画像: {} (页面浏览: {}, 设备: {})", 
                dynamicProfile.getUserId(), 
                dynamicProfile.getPageViewCount(),
                dynamicProfile.getDeviceClassification());
        
        return dynamicProfile;
    }

    /**
     * 获取动态用户画像
     * 
     * @param userId 用户ID
     * @return 用户画像Optional
     */
    public Optional<DynamicUserProfile> getProfile(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return Optional.empty();
        }

        String key = buildProfileKey(userId);
        String serializedProfile = (String) redisTemplate.opsForValue().get(key);
        
        DynamicUserProfile profile = null;
        if (serializedProfile != null) {
            profile = dynamicProfileSerializer.deserialize(serializedProfile);
        }
        
        if (profile != null) {
            log.debug("🔍 获取动态用户画像: {} (活跃等级: {})", 
                    userId, profile.getActivityLevel());
        } else {
            log.debug("❌ 动态用户画像不存在: {}", userId);
        }
        
        return Optional.ofNullable(profile);
    }

    /**
     * 更新动态用户画像
     * 
     * @param dynamicProfile 要更新的画像数据
     * @return 更新后的画像
     */
    public DynamicUserProfile updateProfile(DynamicUserProfile dynamicProfile) {
        if (!dynamicProfile.isValid()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        // 递增版本号和更新时间
        dynamicProfile.setUpdatedAt(Instant.now());
        if (dynamicProfile.getVersion() != null) {
            dynamicProfile.setVersion(dynamicProfile.getVersion() + 1);
        }

        // 更新 活跃时间
        dynamicProfile.updateLastActiveAt();

        // 保存到Redis，使用专用序列化器
        String key = buildProfileKey(dynamicProfile.getUserId());
        String serializedProfile = dynamicProfileSerializer.serialize(dynamicProfile);
        if (serializedProfile != null) {
            redisTemplate.opsForValue().set(key, serializedProfile, DEFAULT_TTL);
        } else {
            throw new RuntimeException("序列化用户画像失败: " + dynamicProfile.getUserId());
        }
        
        // 更新活跃用户索引
        addToActiveUsersIndex(dynamicProfile.getUserId(), dynamicProfile.getLastActiveAt());
        
        // 更新页面浏览数索引
        updatePageViewIndex(dynamicProfile.getUserId(), dynamicProfile.getPageViewCount());
        
        // 📅 更新用户过期时间（因为TTL被重置了）
        recordUserExpiryTime(dynamicProfile.getUserId());

        
        log.debug("🔄 更新动态用户画像: {} (版本: {}, 页面浏览: {})", 
                dynamicProfile.getUserId(), 
                dynamicProfile.getVersion(),
                dynamicProfile.getPageViewCount());
        
        return dynamicProfile;
    }

    // ===================================================================
    // 高频业务操作
    // ===================================================================

    /**
     * 记录页面浏览事件
     * 这是最高频的操作，需要高性能支持
     * 
     * @param userId 用户ID
     * @return 更新后的画像
     */
    public DynamicUserProfile recordPageView(String userId) {
        return recordPageViews(userId, 1L);
    }

    /**
     * 批量记录页面浏览事件
     * 
     * @param userId 用户ID
     * @param count 浏览次数
     * @return 更新后的画像
     */
    public DynamicUserProfile recordPageViews(String userId, long count) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("浏览次数必须大于0");
        }

        return getProfile(userId)
                .map(profile -> {
                    // 更新现有画像
                    profile.incrementPageViewCount(count);

                    return updateProfile(profile);
                })
                /*
                 为什么 profile 为空时需要创建新画像
                 因为 动态 profile 是保存在 redis 中的, 过期时间为 7 天, 如果 7 天内没有发生任何的 event, 这个 profile 就会被自动被删除
                 再次发生 event 时, 再重新创建
                */
                //TODO: 当使用 mongodb 后, 先查询 mongodb 中存不存在, 如果存在, 使用 mongodb 中保存的动态 profile
                .orElseGet(() -> {
                    // 创建新画像
                    DynamicUserProfile newProfile = DynamicUserProfile.builder()
                            .userId(userId)
                            .pageViewCount(count)
                            .lastActiveAt(Instant.now())
                            .recentDeviceTypes(new HashSet<>())
                            .version(1L)
                            .updatedAt(Instant.now())
                            .build();
                    return createProfile(newProfile);
                });
    }

    /**
     * 更新用户活跃状态
     * 
     * @param userId 用户ID
     * @param activeTime 活跃时间，如果为null则使用当前时间
     * @return 更新后的画像
     */
    public DynamicUserProfile updateLastActiveAt(String userId, Instant activeTime) {
        if (activeTime == null) {
            activeTime = Instant.now();
        }

        final Instant finalActiveTime = activeTime;
        
        return getProfile(userId)
                .map(profile -> updateProfile(profile))
                .orElseGet(() -> {
                    // 创建新画像，仅设置活跃时间
                    DynamicUserProfile newProfile = DynamicUserProfile.builder()
                            .userId(userId)
                            .lastActiveAt(finalActiveTime)
                            .pageViewCount(0L)
                            .recentDeviceTypes(new HashSet<>())
                            .version(1L)
                            .updatedAt(Instant.now())
                            .build();
                    return createProfile(newProfile);
                });
    }

    /**
     * 更新用户设备信息
     * 
     * @param userId 用户ID
     * @param deviceClass 设备分类
     * @return 更新后的画像
     */
    public DynamicUserProfile updateDeviceInfo(String userId, DeviceClass deviceClass) {
        if (deviceClass == null) {
            throw new IllegalArgumentException("设备分类不能为空");
        }

        return getProfile(userId)
                .map(profile -> {
                    profile.setMainDeviceClassification(deviceClass);
                    // 更新设备索引
                    updateDeviceIndex(userId, deviceClass);
                    return updateProfile(profile);
                })
                .orElseGet(() -> {
                    // 创建新画像，设置设备信息
                    Set<DeviceClass> deviceTypes = new HashSet<>();
                    deviceTypes.add(deviceClass);
                    
                    DynamicUserProfile newProfile = DynamicUserProfile.builder()
                            .userId(userId)
                            .deviceClassification(deviceClass)
                            .recentDeviceTypes(deviceTypes)
                            .pageViewCount(0L)
                            .lastActiveAt(Instant.now())
                            .version(1L)
                            .updatedAt(Instant.now())
                            .build();
                    
                    updateDeviceIndex(userId, deviceClass);
                    return createProfile(newProfile);
                });
    }

    // ===================================================================
    // 批量操作（性能优化）
    // ===================================================================

    /**
     * 批量获取动态用户画像
     * 
     * @param userIds 用户ID列表
     * @return 用户画像Map，key为用户ID
     */
    public Map<String, DynamicUserProfile> getProfiles(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new HashMap<>();
        }

        // 构建所有Key
        List<String> keys = userIds.stream()
                .filter(Objects::nonNull) // 因为  List<String> 可能包含了 null
                .map(this::buildProfileKey) // 对于当前的 string, 输出一个新的 string:  PROFILE_KEY_PREFIX:userId
                .toList(); // 将所有的 新的 string 转为一个 list

        // 批量获取, 一次性获取所有的 profile（序列化字符串）
        List<Object> profileObjects = redisTemplate.opsForValue().multiGet(keys);
        List<DynamicUserProfile> profiles = profileObjects.stream()
                .map(obj -> {
                    if (obj instanceof String) {
                        return dynamicProfileSerializer.deserialize((String) obj);
                    }
                    return null;
                })
                .toList();
        
        Map<String, DynamicUserProfile> result = new HashMap<>();

        for (int i = 0; i < userIds.size() && i < profiles.size(); i++) {
            String userId = userIds.get(i);
            DynamicUserProfile profile = profiles.get(i);
            if (profile != null) {
                result.put(userId, profile);
            }
        }

        log.debug("📦 批量获取动态画像: 请求{}个，返回{}个", userIds.size(), result.size());
        return result;
    }

    /**
     * 异步批量更新页面浏览数据
     * 用于高并发场景的性能优化
     * //TODO: 适用于使用 kafka stream 进行窗口更新的数据
     * @param userViewCounts 用户ID到浏览次数的映射
     * @return 异步任务Future
     */
    @Async
    public CompletableFuture<Integer> batchUpdatePageViews(Map<String, Long> userViewCounts) {
        if (userViewCounts == null || userViewCounts.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        log.info("📦 开始批量更新页面浏览数据: {} 个用户", userViewCounts.size());

        int updateCount = 0;
        Instant now = Instant.now();

        // 获取现有画像
        List<String> userIds = new ArrayList<>(userViewCounts.keySet());
        Map<String, DynamicUserProfile> existingProfiles = getProfiles(userIds);
        
        // 收集页面浏览数据用于批量索引更新
        Map<String, Long> finalPageViews = new HashMap<>();

        // 批量更新
        for (Map.Entry<String, Long> entry : userViewCounts.entrySet()) {
            String userId = entry.getKey();
            Long viewCount = entry.getValue();
            
            if (viewCount <= 0) continue;

            DynamicUserProfile profile = existingProfiles.get(userId);
            if (profile != null) {
                // 更新现有画像
                profile.incrementPageViewCount(viewCount);
                profile.updateLastActiveAt(now);
            } else {
                // 创建新画像
                profile = DynamicUserProfile.builder()
                        .userId(userId)
                        .pageViewCount(viewCount)
                        .lastActiveAt(now)
                        .recentDeviceTypes(new HashSet<>())
                        .version(1L)
                        .updatedAt(now)
                        .build();
            }

            // 保存到Redis，使用专用序列化器
            String key = buildProfileKey(userId);
            String serializedProfile = dynamicProfileSerializer.serialize(profile);
            if (serializedProfile != null) {
                redisTemplate.opsForValue().set(key, serializedProfile, DEFAULT_TTL);
            } else {
                throw new RuntimeException("序列化用户画像失败: " + userId);
            }
            
            // 更新活跃用户索引
            addToActiveUsersIndex(userId, now);
            
            // 收集页面浏览数用于批量索引更新
            finalPageViews.put(userId, profile.getPageViewCount());
            
            updateCount++;
        }
        
        // 🚀 批量更新页面浏览数索引（性能优化）
        batchUpdatePageViewIndex(finalPageViews);

        log.info("✅ 批量更新页面浏览数据完成: 成功更新 {} 个用户", updateCount);
        return CompletableFuture.completedFuture(updateCount);
    }

    // ===================================================================
    // 业务查询方法
    // ===================================================================

    /**
     * 获取活跃用户列表
     * 基于Redis Sorted Set实现高性能查询
     * 
     * @param withinSeconds 时间范围（秒）
     * @return 活跃用户画像列表
     */
    public List<DynamicUserProfile> getActiveUsers(long withinSeconds) {
        if (withinSeconds <= 0) {
            return new ArrayList<>();
        }

        // 计算时间范围
        Instant cutoffTime = Instant.now().minusSeconds(withinSeconds);
        long cutoffTimestamp = cutoffTime.toEpochMilli();

        // 从活跃用户索引中查询
        String activeUsersKey = ACTIVE_USERS_KEY + "recent";
        Set<Object> activeUserObjects = redisTemplate.opsForZSet()
                .rangeByScore(activeUsersKey, cutoffTimestamp, Double.MAX_VALUE);

        /*
        注意:   下面的代码的意思 如果 activeUserObjects != null 则执行 activeUserObjects.stream()
                否则执行 new HashSet<>();
         */
        Set<String> activeUserIds = activeUserObjects != null ?
                activeUserObjects.stream()
                        .map(obj -> obj instanceof String ? (String) obj : null)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet())
                : new HashSet<>();

        if (activeUserIds.isEmpty()) {
            log.debug("⚡ 查询活跃用户: 时间范围{}秒内无活跃用户", withinSeconds);
            return new ArrayList<>();
        }

        // 批量获取画像详情
        List<String> userIdsList = new ArrayList<>(activeUserIds);
        Map<String, DynamicUserProfile> profiles = getProfiles(userIdsList);

        List<DynamicUserProfile> result = profiles.values().stream()
                .filter(profile -> profile.isActiveWithin(withinSeconds))
                .sorted((p1, p2) -> p2.getLastActiveAt().compareTo(p1.getLastActiveAt()))
                .collect(Collectors.toList());

        log.debug("⚡ 查询活跃用户: 时间范围{}秒，找到{}个活跃用户", withinSeconds, result.size());
        return result;
    }

    /**
     * 根据设备类型获取用户
     * 
     * @param deviceClass 设备类型
     * @return 用户画像列表
     */
    public List<DynamicUserProfile> getUsersByDeviceClass(DeviceClass deviceClass) {
        if (deviceClass == null) {
            return new ArrayList<>();
        }

        // redis 中保存的 deviceIndexKey -> set
        String deviceIndexKey = DEVICE_INDEX_KEY + deviceClass.name().toLowerCase();

        // 获取 deviceIndexKey 映射的 Set(userId)
        Set<Object> userIdObjects = redisTemplate.opsForSet().members(deviceIndexKey);

        // 过滤 Redis 中保存的 有效 ID
        Set<String> userIds = userIdObjects != null ? 
                userIdObjects.stream()
                        .map(obj -> obj instanceof String ? (String) obj : null)
                        .filter(obj -> Objects.nonNull(obj))
                        .collect(Collectors.toSet())
                : new HashSet<>();

        if (userIds == null || userIds.isEmpty()) {
            log.debug("📱 根据设备类型查询用户: {} - 无相关用户", deviceClass);
            return new ArrayList<>();
        }

        List<String> userIdsList = new ArrayList<>(userIds);
        // 获取所有有效 ID 对应的 profile
        Map<String, DynamicUserProfile> profiles = getProfiles(userIdsList);


        List<DynamicUserProfile> result = profiles.values().stream()
                // 对 deviceClass 对应的 profiles 再做一遍 filter, 确保 profile 的主设备类型 == deviceClass
                .filter(profile -> deviceClass.equals(profile.getDeviceClassification()))
                .collect(Collectors.toList());

        log.debug("📱 根据设备类型查询用户: {} - 找到{}个用户", deviceClass, result.size());
        return result;
    }

    /**
     * 获取高参与度用户（页面浏览数超过阈值）
     * 使用ZSet索引实现高性能查询
     * 
     * @param minPageViews 最小页面浏览数
     * @return 高参与度用户列表（按页面浏览数降序排列）
     */
    public List<DynamicUserProfile> getHighEngagementUsers(long minPageViews) {
        log.debug("🎯 获取高参与度用户: 最小浏览数{} (使用ZSet索引优化)", minPageViews);
        
        // 🚀 从ZSet索引中直接获取符合条件的用户ID（已按页面浏览数降序排列）
        Set<Object> userIdObjects = redisTemplate.opsForZSet()
                .reverseRangeByScore(PAGEVIEW_INDEX_KEY, minPageViews, Double.MAX_VALUE);
        
        if (userIdObjects == null || userIdObjects.isEmpty()) {
            log.debug("🎯 高参与度用户查询结果: 0个用户符合条件 (最小浏览数: {})", minPageViews);
            return new ArrayList<>();
        }
        
        // 转换为用户ID列表
        List<String> userIds = userIdObjects.stream()
                .map(obj -> obj instanceof String ? (String) obj : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        // 🚀 批量获取完整画像数据（只获取符合条件的用户）
        Map<String, DynamicUserProfile> profiles = getProfiles(userIds);
        
        // 按ZSet的顺序返回结果（已经按pageView降序排列）
        List<DynamicUserProfile> result = userIds.stream()
                .map(profiles::get)
                .filter(Objects::nonNull)
                .filter(profile -> profile.getPageViewCount() != null && 
                                 profile.getPageViewCount() >= minPageViews) // 二次验证，确保数据一致性
                .collect(Collectors.toList());
        
        log.debug("🎯 高参与度用户查询完成: 找到{}个用户 (最小浏览数: {})", result.size(), minPageViews);
        return result;
    }

    /**
     * 获取高参与度用户（支持分页）
     * 使用ZSet索引实现高性能分页查询
     * 
     * @param minPageViews 最小页面浏览数
     * @param page 页码（从0开始）
     * @param size 每页大小
     * @return 高参与度用户列表（按页面浏览数降序排列）
     */
    public List<DynamicUserProfile> getHighEngagementUsers(long minPageViews, int page, int size) {
        if (page < 0 || size <= 0) {
            throw new IllegalArgumentException("页码必须>=0，每页大小必须>0");
        }
        
        log.debug("🎯 分页获取高参与度用户: 最小浏览数{}, 页码{}, 每页{}", minPageViews, page, size);
        
        long offset = (long) page * size;
        
        // 🚀 使用ZSet的分页查询功能
        Set<Object> userIdObjects = redisTemplate.opsForZSet()
                .reverseRangeByScore(PAGEVIEW_INDEX_KEY, minPageViews, Double.MAX_VALUE, offset, size);
        
        if (userIdObjects == null || userIdObjects.isEmpty()) {
            log.debug("🎯 分页查询结果: 第{}页无数据", page);
            return new ArrayList<>();
        }
        
        // 转换并获取完整画像
        List<String> userIds = userIdObjects.stream()
                .map(obj -> obj instanceof String ? (String) obj : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        Map<String, DynamicUserProfile> profiles = getProfiles(userIds);
        
        List<DynamicUserProfile> result = userIds.stream()
                .map(profiles::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        log.debug("🎯 分页查询完成: 第{}页返回{}个用户", page, result.size());
        return result;
    }

    /**
     * 获取高参与度用户（带分数信息）
     * 返回用户ID和对应的页面浏览数，避免二次查询
     * 
     * @param minPageViews 最小页面浏览数
     * @return 用户ID和页面浏览数的映射
     */
    public Map<String, Long> getHighEngagementUserScores(long minPageViews) {
        log.debug("🎯 获取高参与度用户分数: 最小浏览数{}", minPageViews);
        
        // 🚀 同时获取用户ID和分数，避免额外的数据查询
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<Object>> userWithScores = 
                redisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                        PAGEVIEW_INDEX_KEY, minPageViews, Double.MAX_VALUE);
        
        if (userWithScores == null || userWithScores.isEmpty()) {
            return new HashMap<>();
        }
        
        Map<String, Long> result = new HashMap<>();
        for (org.springframework.data.redis.core.ZSetOperations.TypedTuple<Object> tuple : userWithScores) {
            Object value = tuple.getValue(); // userId
            Double score = tuple.getScore(); // pageView
            
            if (value instanceof String && score != null) {
                result.put((String) value, score.longValue());
            }
        }
        
        log.debug("🎯 高参与度用户分数查询完成: {}个用户", result.size());
        return result;
    }

    // ===================================================================
    // 统计和分析方法
    // ===================================================================

    /**
     * 获取用户活跃统计信息
     * 
     * @return 活跃统计数据
     */
    public ActivityStatistics getActivityStatistics() {
        // 统计最近24小时活跃用户
        List<DynamicUserProfile> activeUsers24h = getActiveUsers(24 * 3600);
        
        // 统计最近1小时活跃用户
        List<DynamicUserProfile> activeUsers1h = getActiveUsers(3600);
        
        // 🚀 高效获取redis 中的用户数（使用计数器，O(1)时间复杂度）
        long redisUsersCount = getTotalRedisUsersCount();

        // 获取总的用户数
        long totalUsersCount = staticProfileRepository.count();

        ActivityStatistics stats = new ActivityStatistics();
        stats.setTotalUsers(totalUsersCount);
        stats.setRedisUsers(redisUsersCount);
        stats.setActiveUsers24h(activeUsers24h.size());
        stats.setActiveUsers1h(activeUsers1h.size());
        
        // 计算活跃率
        if (totalUsersCount > 0) {
            stats.setActivityRate24h((double) activeUsers24h.size() / totalUsersCount * 100);
        }

        log.info("📊 用户活跃统计 - 总数: {}, 24h活跃: {}, 1h活跃: {}, 24h活跃率: {:.1f}%",
                totalUsersCount, activeUsers24h.size(), activeUsers1h.size(), stats.getActivityRate24h());

        return stats;
    }

    /**
     * 获取设备分布统计
     * 
     * @return 设备分布统计数据
     */
    public Map<DeviceClass, Long> getDeviceDistribution() {
        Map<DeviceClass, Long> distribution = new HashMap<>();
        
        for (DeviceClass deviceClass : DeviceClass.values()) {
            String deviceIndexKey = DEVICE_INDEX_KEY + deviceClass.name().toLowerCase();
            Long count = redisTemplate.opsForSet().size(deviceIndexKey);
            distribution.put(deviceClass, count != null ? count : 0L);
        }
        
        log.debug("📊 设备分布统计: {}", distribution);
        return distribution;
    }

    // ===================================================================
    // 数据管理方法
    // ===================================================================

    /**
     * 检查用户画像是否存在
     * 
     * @param userId 用户ID
     * @return 是否存在
     */
    public boolean profileExists(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        
        String key = buildProfileKey(userId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 删除用户画像
     * 
     * @param userId 用户ID
     * @return 是否删除成功
     */
    public boolean deleteProfile(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }

        // 获取画像信息用于清理索引
        Optional<DynamicUserProfile> profileOpt = getProfile(userId);
        
        // 删除主数据
        String key = buildProfileKey(userId);
        Boolean deleted = redisTemplate.delete(key);
        
        if (Boolean.TRUE.equals(deleted) && profileOpt.isPresent()) {
            DynamicUserProfile profile = profileOpt.get();
            
            // 清理活跃用户索引
            removeFromActiveUsersIndex(userId);
            
            // 清理设备索引
            if (profile.getDeviceClassification() != null) {
                removeFromDeviceIndex(userId, profile.getDeviceClassification());
            }
            
            // 清理页面浏览数索引
            removeFromPageViewIndex(userId);
            
            // 🗑️ 从过期时间索引中移除
            removeFromExpiryIndex(userId);
            
            // 递减用户总数计数器
            decrementUserCount();
            
            log.info("🗑️ 删除动态用户画像: {}", userId);
            return true;
        }
        
        return false;
    }

    /**
     * 清理过期数据
     * 注意：Redis的TTL会自动处理过期，这个方法主要清理索引
     */
    public void cleanupExpiredData() {
        log.info("🧹 开始清理过期的动态画像索引数据");
        
        // 清理过期的活跃用户索引
        String activeUsersKey = ACTIVE_USERS_KEY + "recent";
        long expiredTimestamp = Instant.now().minus(ACTIVE_USERS_TTL).toEpochMilli();
        Long removedCount = redisTemplate.opsForZSet().removeRangeByScore(activeUsersKey, 0, expiredTimestamp);
        
        log.info("🧹 清理完成 - 移除{}个过期活跃用户索引", removedCount != null ? removedCount : 0);
    }

    /**
     * 增强版TTL感知的过期用户清理
     * 使用原子操作 + 智能重试 + 分布式锁的完整解决方案
     * 
     * 整点UTC触发，非阻塞锁，原子性保证数据一致性
     *
     * //TODO: 其实不用统一每个 instance 都使用 UTC 时区, 因为任何时区 整点的到来都是同步的
     */
    //TODO: 在 v0.3 版本, 需要将过期的 profile 写入 mongodb
    @Async("cleanupTaskExecutor")
    @Scheduled(cron = "0 0 * * * *", zone = "UTC") // 每小时整点UTC触发
    public void cleanupExpiredUsers() {
        Instant scheduleTime = Instant.now();
        log.info("🕐 开始增强版TTL感知清理 - UTC时间: {}", scheduleTime);
        
        // 尝试获取分布式锁 (非阻塞), 50分钟自动过期
        if (!tryAcquireDistributedLock(CLEANUP_LOCK_KEY, LOCK_EXPIRE_TIME)) {
            log.info("⏭️ 其他实例正在执行清理任务，本次跳过");
            return;
        }

        // 成功获取锁
        String taskId = UUID.randomUUID().toString();

        try {
            CleanupResult result = executeAtomicCleanupWithRetry();

            // 发布成功事件
            eventPublisher.publishEvent(CleanupCompletedEvent.builder()
                    .taskId(taskId)
                    .timestamp(Instant.now())
                    .totalExpiredCount(result.getTotalExpiredCount())
                    .totalCandidateCount(result.getTotalCandidateCount())
                    .build());

        }
        //TODO: 但是超时异常怎么处理呢?
        catch (Exception e) {
            // 发布失败事件
            eventPublisher.publishEvent(CleanupFailedEvent.builder()
                    .taskId(taskId)
                    .errorMessage(e.getMessage())
                    .timestamp(Instant.now())
                    .build());
        }
        finally {
            // 确保锁一定会被释放
            try {
                releaseDistributedLock(CLEANUP_LOCK_KEY);
                log.info("🔓 清理锁已释放");
            } catch (Exception e) {
                log.error("❌ 释放分布式锁失败", e);
            }
        }


        // 成功获取锁
//        try {
//            log.info("🔒 获得清理锁，开始执行原子清理...");
//
//            // 首先清理 expiry User index
//            // 使用超时保护的异步执行
//            //TODO: 什么情况下需要在方法上加 @Async
//            CompletableFuture<CleanupResult> cleanupFuture = CompletableFuture.supplyAsync(() -> {
//                try {
//                    return executeAtomicCleanupWithRetry();
//                } catch (Exception e) {
//                    log.error("异步清理执行失败", e);
//                    throw new RuntimeException("异步清理失败", e);
//                }
//            }); // 使用我们配置的自定义线程池
//
//            // 等待完成，但不超过最大执行时间
//            // ❌ 关键问题：调度线程可能在这里阻塞等待45分钟！ CompletableFuture 的 get() 方法是阻塞线程的
//            CleanupResult result = cleanupFuture.get(
//                MAX_EXECUTION_TIME.toMillis(),
//                TimeUnit.MILLISECONDS
//            );
//
//            log.info("✅ TTL感知清理成功完成: {}", result);
//
//        } catch (TimeoutException e) {
//            log.error("⏰ 清理任务超时，强制终止。任务可能存在性能问题", e);
//
//        } catch (Exception e) {
//            log.error("❌ TTL感知清理最终失败，等待下个整点重试", e);
//
//        } finally {
//            // 确保锁一定会被释放
//            try {
//                releaseDistributedLock(CLEANUP_LOCK_KEY);
//                log.info("🔓 清理锁已释放");
//            } catch (Exception e) {
//                log.error("❌ 释放分布式锁失败", e);
//            }
//        }
    }
    
    /**
     * 手动触发清理 (运维接口)
     * 用于紧急情况下的手动清理
     */
    public CleanupResult manualCleanup() {
        String manualLockKey = CLEANUP_LOCK_KEY + ":manual";
        
        if (!tryAcquireDistributedLock(manualLockKey, Duration.ofMinutes(30))) {
            throw new RuntimeException("另一个清理任务正在执行，无法手动触发");
        }
        
        try {
            log.info("🔧 手动触发清理任务");
            return executeAtomicCleanupWithRetry();
            
        } finally {
            releaseDistributedLock(manualLockKey);
        }
    }

    /**
     * 获取清理状态 (监控接口)
     * 用于监控和调试
     */
    public CleanupStatus getCleanupStatus() {
        try {
            // 检查是否有清理任务正在运行
            boolean isRunning = Boolean.TRUE.equals(redisTemplate.hasKey(CLEANUP_LOCK_KEY));
            
            // 检查过期索引堆积情况
            long currentTime = Instant.now().toEpochMilli();
            Long overdueCount = redisTemplate.opsForZSet().count(USER_EXPIRY_INDEX, 0, currentTime);
            
            // 获取当前用户计数
            String counterValue = (String) redisTemplate.opsForValue().get(USER_COUNT_KEY);
            long currentUserCount = counterValue != null ? Long.parseLong(counterValue) : 0;
            
            CleanupStatus status = new CleanupStatus();
            status.setCleanupRunning(isRunning);
            status.setOverdueTaskCount(overdueCount != null ? overdueCount : 0);
            status.setCurrentUserCount(currentUserCount);
            status.setNextScheduledTime(getNextScheduledTime());
            
            return status;
            
        } catch (Exception e) {
            log.error("获取清理状态失败", e);
            CleanupStatus errorStatus = new CleanupStatus();
            errorStatus.setErrorMessage(e.getMessage());
            return errorStatus;
        }
    }
    
    /**
     * 获取下次调度时间
     */
    private Instant getNextScheduledTime() {
        Instant now = Instant.now();
        Instant nextHour = now.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        return nextHour;
    }

    /**
     * 清理状态数据结构
     */
    public static class CleanupStatus {
        private boolean cleanupRunning;
        private long overdueTaskCount;
        private long currentUserCount;
        private Instant nextScheduledTime;
        private String errorMessage;
        
        // Getters and Setters
        public boolean isCleanupRunning() { return cleanupRunning; }
        public void setCleanupRunning(boolean cleanupRunning) { this.cleanupRunning = cleanupRunning; }
        public long getOverdueTaskCount() { return overdueTaskCount; }
        public void setOverdueTaskCount(long overdueTaskCount) { this.overdueTaskCount = overdueTaskCount; }
        public long getCurrentUserCount() { return currentUserCount; }
        public void setCurrentUserCount(long currentUserCount) { this.currentUserCount = currentUserCount; }
        public Instant getNextScheduledTime() { return nextScheduledTime; }
        public void setNextScheduledTime(Instant nextScheduledTime) { this.nextScheduledTime = nextScheduledTime; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        @Override
        public String toString() {
            return String.format("CleanupStatus{运行中=%s, 过期任务=%d, 当前用户=%d, 下次调度=%s}",
                    cleanupRunning, overdueTaskCount, currentUserCount, nextScheduledTime);
        }
    }

    // ===================================================================
    // 内部辅助方法
    // ===================================================================

    /**
     * 构建Redis Key
     * dynamic_profile:user123
     */
    private String buildProfileKey(String userId) {
        return PROFILE_KEY_PREFIX + userId;
    }

    /**
     * 添加到活跃用户索引
     */
    private void addToActiveUsersIndex(String userId, Instant activeTime) {
        String activeUsersKey = ACTIVE_USERS_KEY + "recent";
        double score = activeTime.toEpochMilli();
        /**
         * 这个 opsForZSet() 方法返回一个 ZSetOperations<K, V> 类型的对象，它提供了对 Redis Sorted Set 类型的所有操作
         * ZSet 是 Redis 中的一种有序集合，集合中的元素是唯一的（不能重复），但每个元素会关联一个 score 分数（可以重复）
         * add(key, value, score)	向有序集合添加元素及其分数, 此时我们的分数是 lastActiveTime
         */
        redisTemplate.opsForZSet().add(activeUsersKey, userId, score);

        // 只在TTL较短时才重新设置（减少Redis网络调用）
        //TODO: 这个地方是不是有必要 剩余时间少于2小时时才重设
        Long ttl = redisTemplate.getExpire(activeUsersKey);
        if (ttl == null || ttl < 7200) {  // 剩余时间少于2小时时才重设
            redisTemplate.expire(activeUsersKey, ACTIVE_USERS_TTL);
        }
    }

    /**
     * 从活跃用户索引中移除
     */
    private void removeFromActiveUsersIndex(String userId) {
        String activeUsersKey = ACTIVE_USERS_KEY + "recent";
        redisTemplate.opsForZSet().remove(activeUsersKey, userId);
    }

    /**
     * 更新设备索引
     */
    private void updateDeviceIndex(String userId, DeviceClass deviceClass) {
        String deviceIndexKey = DEVICE_INDEX_KEY + deviceClass.name().toLowerCase();
        redisTemplate.opsForSet().add(deviceIndexKey, userId);
        redisTemplate.expire(deviceIndexKey, DEFAULT_TTL);
    }

    /**
     * 从设备索引中移除
     * //TODO: 什么情况下需要这个操作
     */
    private void removeFromDeviceIndex(String userId, DeviceClass deviceClass) {
        String deviceIndexKey = DEVICE_INDEX_KEY + deviceClass.name().toLowerCase();
        redisTemplate.opsForSet().remove(deviceIndexKey, userId);
    }

    /**
     * 更新页面浏览数索引
     * 
     * @param userId 用户ID
     * @param pageViewCount 页面浏览数
     */
    private void updatePageViewIndex(String userId, Long pageViewCount) {
        if (userId == null || pageViewCount == null) {
            return;
        }
        
        // 添加或更新ZSet中的用户分数
        redisTemplate.opsForZSet().add(PAGEVIEW_INDEX_KEY, userId, pageViewCount.doubleValue());
        
        // 设置索引TTL，确保索引数据与主数据同步过期
        redisTemplate.expire(PAGEVIEW_INDEX_KEY, DEFAULT_TTL);
    }

    /**
     * 从页面浏览数索引中移除用户
     * 
     * @param userId 用户ID
     */
    private void removeFromPageViewIndex(String userId) {
        if (userId != null) {
            redisTemplate.opsForZSet().remove(PAGEVIEW_INDEX_KEY, userId);
        }
    }

    /**
     * 记录用户的过期时间到索引中
     * 用于TTL感知的用户生命周期管理
     * 
     * @param userId 用户ID
     */
    private void recordUserExpiryTime(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return;
        }
        
        // 计算过期时间戳（当前时间 + DEFAULT_TTL）
        long expiryTimestamp = Instant.now().plus(DEFAULT_TTL).toEpochMilli();
        
        // 添加到过期时间索引ZSet中，score为过期时间戳
        redisTemplate.opsForZSet().add(USER_EXPIRY_INDEX, userId, expiryTimestamp);
        
        // 设置索引本身的TTL，确保索引数据不会泄漏
        redisTemplate.expire(USER_EXPIRY_INDEX, DEFAULT_TTL.plus(Duration.ofDays(1)));
        
        log.debug("📅 记录用户过期时间: {} -> {}", userId, Instant.ofEpochMilli(expiryTimestamp));
    }

    /**
     * 从过期时间索引中移除用户
     * 
     * @param userId 用户ID
     */
    private void removeFromExpiryIndex(String userId) {
        if (userId != null) {
            redisTemplate.opsForZSet().remove(USER_EXPIRY_INDEX, userId);
            log.debug("🗑️ 从过期索引中移除用户: {}", userId);
        }
    }

    // ===================================================================
    // 分布式锁实现
    // ===================================================================

    /**
     * 尝试获取分布式锁（非阻塞）
     * 
     * @param lockKey 锁的key
     * @param expireTime 过期时间
     * @return 是否成功获取锁
     */
    private boolean tryAcquireDistributedLock(String lockKey, Duration expireTime) {

        // 生成唯一的锁值
        String lockValue = generateLockValue();

        // 如果成功设置锁, 就是 absent and set, 返回 true
        // 如果锁已经被设置了, 返回 false
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
            lockKey, 
            lockValue, 
            expireTime
        );
        
        if (Boolean.TRUE.equals(acquired)) {
            log.info("🔒 成功获取分布式锁: {}", lockKey);
            return true;
        } else {
            log.debug("🔒 获取分布式锁失败: {} (其他实例正在执行)", lockKey);
            return false;
        }
    }

    /**
     * 释放分布式锁
     * 
     * @param lockKey 锁的key
     */
    private void releaseDistributedLock(String lockKey) {
        try {
            Boolean deleted = redisTemplate.delete(lockKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 成功释放分布式锁: {}", lockKey);
            }
        } catch (Exception e) {
            log.error("❌ 释放分布式锁失败: {}", lockKey, e);
        }
    }

    /**
     * 生成唯一的锁值
     */
    private String generateLockValue() {
        return UUID.randomUUID().toString() + ":" + Thread.currentThread().threadId();
    }

    // ===================================================================
    // 原子清理执行器
    // ===================================================================

    /**
     * 执行原子清理操作: 同时清理 USER_EXPIRY_INDEX 中的过期 userId 与 计数器减少
     * 使用Lua脚本保证计数器和索引的一致性
     * 
     * @return 清理结果
     * //TODO: 这个方法还需要再好好看看
     */
    private CleanupResult executeAtomicCleanup() {
        long startTime = System.currentTimeMillis();
        long currentTimestamp = Instant.now().toEpochMilli();
        
        CleanupResult totalResult = new CleanupResult();
        int iteration = 0;
        
        try {
            do {
                iteration++;
                
                // 执行单批次原子清理
                @SuppressWarnings("unchecked")
                List<Long> batchResult = (List<Long>) redisTemplate.execute(
                        /**
                         * KEYS[1]: 过期时间索引 ZSet (user_expiry_index)
                         * KEYS[2]: 用户计数器 (dynamic_profile_count)
                         * KEYS[3]: 用户profile前缀 (dynamic_profile:)
                         * ARGV[1]: 当前时间戳
                         * ARGV[2]: 批处理大小
                         * List<Long> batchResult: {actualExpiredCount, candidateCount, remainingCount}
                         */
                    atomicCleanupScript,
                    Arrays.asList(USER_EXPIRY_INDEX, USER_COUNT_KEY, PROFILE_KEY_PREFIX), // KEYS[1, 2, 3]
                    String.valueOf(currentTimestamp), // args,  ARGV[1]
                    String.valueOf(DEFAULT_BATCH_SIZE) // args,  ARGV[2]
                );
                
                if (batchResult == null || batchResult.size() < 3) {
                    throw new RuntimeException("Lua脚本返回结果异常");
                }
                
                // 解析批次结果
                long actualExpired = batchResult.get(0);
                long candidateCount = batchResult.get(1);
                long remainingCount = batchResult.get(2);
                
                // 累计结果
                totalResult.addBatchResult(actualExpired, candidateCount);
                
                log.debug("批次{}完成 - 实际过期: {}, 候选: {}, 剩余: {}", 
                         iteration, actualExpired, candidateCount, remainingCount);
                
                // 检查是否还需要继续处理
                if (remainingCount == 0 || iteration >= MAX_ITERATIONS) {
                    if (remainingCount > 0) {
                        log.warn("达到最大迭代次数{}，仍有{}个过期用户待处理", 
                                MAX_ITERATIONS, remainingCount);
                    }
                    break;
                }
                
                // 批次间短暂休息，避免Redis压力过大
                Thread.sleep(10);
                
            } while (true);
            
            // 完成统计
            totalResult.setExecutionTime(System.currentTimeMillis() - startTime);
            totalResult.setIterations(iteration);
            totalResult.setSuccess(true);
            
            log.info("原子清理完成 - 总共处理{}个过期用户，耗时{}ms，迭代{}次",
                    totalResult.getTotalExpiredCount(), 
                    totalResult.getExecutionTime(),
                    totalResult.getIterations());
            
            return totalResult;
            
        } catch (Exception e) {
            totalResult.setSuccess(false);
            totalResult.setErrorMessage(e.getMessage());
            totalResult.setExecutionTime(System.currentTimeMillis() - startTime);
            throw new RuntimeException("原子清理执行失败", e);
        }
    }

    /**
     * 智能重试执行原子清理
     * 根据异常类型采用不同的重试策略
     */
    @Retryable(
        value = {RuntimeException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    private CleanupResult executeAtomicCleanupWithRetry() {
        try {
            return executeAtomicCleanup();
        } catch (Exception e) {
            log.warn("原子清理执行失败，准备重试: {}", e.getMessage());
            
            // 根据异常类型决定是否重试
            //TODO: 重新抛出 e 会被 @Retryable 捕获吗?
            // 为什么 else 中抛出 运行时异常就不会重试了
            if (isRetryableException(e)) {
                throw e; // 重新抛出，让@Retryable处理
            } else {
                log.error("不可重试的异常，放弃重试: {}", e.getMessage());
                throw new RuntimeException("清理失败，不可重试", e);
            }
        }
    }

    /**
     * 判断异常是否可重试
     */
    private boolean isRetryableException(Exception exception) {
        String message = exception.getMessage().toLowerCase();
        
        // 网络连接类异常 - 可重试
        if (message.contains("connection") || 
            message.contains("timeout") || 
            message.contains("socket")) {
            return true;
        }
        
        // Redis负载类异常 - 可重试
        if (message.contains("busy") || 
            message.contains("loading") || 
            message.contains("overload")) {
            return true;
        }
        
        // 数据异常、配置错误 - 不可重试
        return false;
    }

    // ===================================================================
    // 清理结果数据结构
    // ===================================================================

    /**
     * 清理结果统计
     */
    public static class CleanupResult {
        private long totalExpiredCount = 0;
        private long totalCandidateCount = 0;
        private int iterations = 0;
        private long executionTime = 0;
        private boolean success = false;
        private String errorMessage;
        
        public void addBatchResult(long expiredCount, long candidateCount) {
            this.totalExpiredCount += expiredCount;
            this.totalCandidateCount += candidateCount;
        }
        
        // Getters and Setters
        public long getTotalExpiredCount() { return totalExpiredCount; }
        public long getTotalCandidateCount() { return totalCandidateCount; }
        public int getIterations() { return iterations; }
        public void setIterations(int iterations) { this.iterations = iterations; }
        public long getExecutionTime() { return executionTime; }
        public void setExecutionTime(long executionTime) { this.executionTime = executionTime; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        @Override
        public String toString() {
            return String.format("CleanupResult{过期用户=%d, 候选用户=%d, 迭代=%d, 耗时=%dms, 成功=%s}",
                    totalExpiredCount, totalCandidateCount, iterations, executionTime, success);
        }
    }

    /**
     * 批量更新页面浏览数索引
     * 用于性能优化的批量操作
     * 
     * @param userPageViews 用户ID到页面浏览数的映射
     */
    private void batchUpdatePageViewIndex(Map<String, Long> userPageViews) {
        if (userPageViews == null || userPageViews.isEmpty()) {
            return;
        }
        
        // 批量添加到ZSet
        for (Map.Entry<String, Long> entry : userPageViews.entrySet()) {
            String userId = entry.getKey();
            Long pageViews = entry.getValue();
            
            if (userId != null && pageViews != null) {
                redisTemplate.opsForZSet().add(PAGEVIEW_INDEX_KEY, userId, pageViews.doubleValue());
            }
        }
        
        // 设置索引TTL
        redisTemplate.expire(PAGEVIEW_INDEX_KEY, DEFAULT_TTL);
        
        log.debug("📊 批量更新页面浏览数索引: {} 个用户", userPageViews.size());
    }

    /**
     * 获取 redis 中的总用户数
     * 使用Redis计数器实现O(1)时间复杂度的高效统计
     * 
     * @return redis 中的总用户数
     */
    private long getTotalRedisUsersCount() {
        String countStr = (String) redisTemplate.opsForValue().get(USER_COUNT_KEY);
        if (countStr == null) {
            // 首次使用时，初始化计数器
            return initializeUserCount();
        }
        
        try {
            return Long.parseLong(countStr);
        } catch (NumberFormatException e) {
            log.warn("⚠️ 用户计数器数据异常，重新初始化: {}", countStr);
            return initializeUserCount();
        }
    }

    /**
     * 递增用户计数器
     * 在创建新用户时调用
     */
    private void incrementUserCount() {
        try {
            Long newCount = redisTemplate.opsForValue().increment(USER_COUNT_KEY);
            // 设置计数器TTL，确保数据一致性
            redisTemplate.expire(USER_COUNT_KEY, DEFAULT_TTL);
            log.debug("📈 用户总数递增至: {}", newCount);
        } catch (Exception e) {
            log.error("❌ 递增用户计数器失败", e);
        }
    }

    /**
     * 递减用户计数器
     * 在删除用户时调用
     */
    private void decrementUserCount() {
        try {
            Long newCount = redisTemplate.opsForValue().decrement(USER_COUNT_KEY);
            // 确保计数器不会变成负数
            if (newCount != null && newCount < 0) {
                redisTemplate.opsForValue().set(USER_COUNT_KEY, "0");
                newCount = 0L;
                log.warn("⚠️ 用户计数器修正为0（之前为负数）");
            }
            log.debug("📉 用户总数递减至: {}", newCount);
        } catch (Exception e) {
            log.error("❌ 递减用户计数器失败", e);
        }
    }

    /**
     * 初始化用户计数器
     * 通过扫描现有数据进行初始化（仅在首次使用时）
     * 同时重建过期时间索引
     * 
     * @return 初始化后的用户数量
     */
    private long initializeUserCount() {
        log.info("🔧 初始化用户计数器和过期时间索引...");
        
        try {
            // 使用SCAN命令代替KEYS，避免阻塞Redis
            long count = scanAndCountKeysWithExpiryRebuild(PROFILE_KEY_PREFIX + "*");
            
            // 设置初始计数器值
            redisTemplate.opsForValue().set(USER_COUNT_KEY, String.valueOf(count));
            redisTemplate.expire(USER_COUNT_KEY, DEFAULT_TTL);
            
            log.info("✅ 用户计数器和过期索引初始化完成: {} 个用户", count);
            return count;
            
        } catch (Exception e) {
            log.error("❌ 初始化用户计数器失败", e);
            // 设置默认值0
            redisTemplate.opsForValue().set(USER_COUNT_KEY, "0");
            return 0L;
        }
    }

    /**
     * 使用SCAN命令安全地统计key数量
     * 避免KEYS命令的阻塞问题
     * 
     * @param pattern key的匹配模式
     * @return 匹配的key数量
     */
    private long scanAndCountKeys(String pattern) {
        log.debug("🔍 使用SCAN统计key数量: {}", pattern);
        
        long count = 0;
        
        try {
            // 使用RedisTemplate的scan方法，自动处理游标和分页
            ScanOptions options =
                    ScanOptions.scanOptions()
                            .match(pattern)
                            .count(1000)  // 每次扫描1000个key，避免阻塞
                            .build();
            
            // 使用try-with-resources确保资源正确关闭
            try (Cursor<String> cursor =
                    redisTemplate.scan(options)) {
                
                while (cursor.hasNext()) {
                    cursor.next();
                    count++;
                }
            }
            
        } catch (Exception e) {
            log.error("❌ SCAN统计key时发生异常", e);
            // 如果SCAN失败，降级使用近似值
            return 0L;
        }
        
        log.debug("🔍 SCAN统计完成: {} 个key", count);
        return count;
    }

    /**
     * 使用SCAN命令统计key数量并重建过期时间索引
     * 避免KEYS命令的阻塞问题，同时重建TTL感知索引
     * 
     * @param pattern key的匹配模式
     * @return 匹配的key数量
     */
    private long scanAndCountKeysWithExpiryRebuild(String pattern) {
        log.debug("🔍 使用SCAN统计key数量并重建过期索引: {}", pattern);
        
        long count = 0;
        
        try {
            // 清除现有的过期时间索引，重新构建
            redisTemplate.delete(USER_EXPIRY_INDEX);
            
            //
            /**
             * 使用RedisTemplate的scan方法，自动处理游标和分页
             */
            ScanOptions options =
                    ScanOptions.scanOptions()
                            .match(pattern)  // dynamic_profile:*
                            .count(1000)  // 每次扫描1000个key，避免阻塞
                            .build();
            
            // 使用try-with-resources确保资源正确关闭
            try (Cursor<String> cursor =
                    redisTemplate.scan(options)) {
                
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    count++;

                    // 从key中提取userId
                    // 删除 key 前缀 "dynamic_profile:", 只保留 userId
                    // userId 就是纯净的 id, 可以供其他系统使用,
                    //比如查询数据库, 就不能直接使用 key, 需要先删除前缀
                    String userId = key.replace(PROFILE_KEY_PREFIX, "");
                    
                    // 获取该key的TTL
                    Long ttl = redisTemplate.getExpire(key);
                    if (ttl != null && ttl > 0) {
                        // 计算 key 的过期时间戳
                        long expiryTimestamp = Instant.now().plusSeconds(ttl).toEpochMilli();

                        // 重建过期时间索引
                        // 此处就不能直接使用 key, 需要先删除前缀
                        redisTemplate.opsForZSet().add(USER_EXPIRY_INDEX, userId, expiryTimestamp);
                    }
                }
            }
            
            // 为过期时间索引设置TTL
            if (count > 0) {
                redisTemplate.expire(USER_EXPIRY_INDEX, DEFAULT_TTL.plus(Duration.ofDays(1)));
            }
            
        } catch (Exception e) {
            log.error("❌ SCAN统计和重建索引时发生异常", e);
            // 如果失败，降级使用原来的方法
            return scanAndCountKeys(pattern);
        }
        
        log.debug("🔍 SCAN统计和索引重建完成: {} 个key", count);
        return count;
    }

    /**
     * 重置用户计数器
     * 管理员维护功能，重新统计并设置正确的用户数量
     * 
     * @return 重置后的用户数量
     */
    public long resetUserCount() {
        log.info("🔧 管理员操作：重置用户计数器");
        
        long actualCount = scanAndCountKeys(PROFILE_KEY_PREFIX + "*");
        redisTemplate.opsForValue().set(USER_COUNT_KEY, String.valueOf(actualCount));
        redisTemplate.expire(USER_COUNT_KEY, DEFAULT_TTL);
        
        log.info("✅ 用户计数器重置完成: {} 个用户", actualCount);
        return actualCount;
    }

    // ===================================================================
    // 内部类：统计数据结构
    // ===================================================================

    /**
     * 活跃统计数据结构
     */
    public static class ActivityStatistics {
        private long totalUsers;
        private long redisUsers;
        private long activeUsers24h;
        private long activeUsers1h;
        private double activityRate24h;

        // Getters and Setters
        public long getTotalUsers() { return totalUsers; }
        public void setTotalUsers(long totalUsers) { this.totalUsers = totalUsers; }

        public long getActiveUsers24h() { return activeUsers24h; }
        public void setActiveUsers24h(long activeUsers24h) { this.activeUsers24h = activeUsers24h; }

        public long getActiveUsers1h() { return activeUsers1h; }
        public void setActiveUsers1h(long activeUsers1h) { this.activeUsers1h = activeUsers1h; }

        public double getActivityRate24h() { return activityRate24h; }
        public void setActivityRate24h(double activityRate24h) { this.activityRate24h = activityRate24h; }

        public long getRedisUsers() {
            return redisUsers;
        }

        public void setRedisUsers(long redisUsers) {
            this.redisUsers = redisUsers;
        }
    }
}