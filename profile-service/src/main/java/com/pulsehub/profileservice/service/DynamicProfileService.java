package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.domain.DeviceClass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
@RequiredArgsConstructor
@Slf4j
public class DynamicProfileService {

    // Redis模板，用于操作动态画像数据
    private final RedisTemplate<String, Object> redisTemplate;
    
    // Redis Key前缀
    private static final String PROFILE_KEY_PREFIX = "dynamic_profile:";
    
    // 活跃用户索引Key前缀（用于快速查询活跃用户）
    private static final String ACTIVE_USERS_KEY = "active_users:";
    
    // 设备分类索引Key前缀
    private static final String DEVICE_INDEX_KEY = "device_index:";
    
    // 页面浏览数索引Key（ZSet，用于高效查询高参与度用户）
    private static final String PAGEVIEW_INDEX_KEY = "pageview_index";
    
    // 默认TTL（7天）
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);
    
    // 活跃用户TTL（24小时）
    private static final Duration ACTIVE_USERS_TTL = Duration.ofHours(24);

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

        // 保存到Redis
        // key:    dynamic_profile:user123
        String key = buildProfileKey(dynamicProfile.getUserId());
        redisTemplate.opsForValue().set(key, dynamicProfile, DEFAULT_TTL);
        
        // 如果用户当前活跃，添加到活跃用户索引
        addToActiveUsersIndex(dynamicProfile.getUserId(), dynamicProfile.getLastActiveAt());
        
        // 添加到页面浏览数索引
        updatePageViewIndex(dynamicProfile.getUserId(), dynamicProfile.getPageViewCount());

        
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
        DynamicUserProfile profile = (DynamicUserProfile) redisTemplate.opsForValue().get(key);
        
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

        // 保存到Redis
        String key = buildProfileKey(dynamicProfile.getUserId());
        // 同时更新最新的 TTL
        redisTemplate.opsForValue().set(key, dynamicProfile, DEFAULT_TTL);
        
        // 更新活跃用户索引
        addToActiveUsersIndex(dynamicProfile.getUserId(), dynamicProfile.getLastActiveAt());
        
        // 更新页面浏览数索引
        updatePageViewIndex(dynamicProfile.getUserId(), dynamicProfile.getPageViewCount());

        
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
                // 为什么 profile 为空时需要创建新画像
                // 因为 动态 profile 是保存在 redis 中的, 过期时间为 7 天, 如果 7 天内没有发生任何的 event, 这个 profile 就会被自动被删除
                // 再次发生 event 时, 再重新创建
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
                .map(this::buildProfileKey) // 对于当前的 string, 输出一个新的 string
                .collect(Collectors.toList()); // 将所有的 新的 string 转为一个 list

        // 批量获取
        List<Object> profileObjects = redisTemplate.opsForValue().multiGet(keys);
        List<DynamicUserProfile> profiles = profileObjects.stream()
                .map(obj -> obj instanceof DynamicUserProfile ? (DynamicUserProfile) obj : null)
                .collect(Collectors.toList());
        
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
     * 
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

            // 保存到Redis
            String key = buildProfileKey(userId);
            redisTemplate.opsForValue().set(key, profile, DEFAULT_TTL);
            
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
            Object value = tuple.getValue();
            Double score = tuple.getScore();
            
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
        
        // 统计总用户数（近似值）
        Set<String> allKeys = redisTemplate.keys(PROFILE_KEY_PREFIX + "*");
        long totalUsers = allKeys != null ? allKeys.size() : 0;

        ActivityStatistics stats = new ActivityStatistics();
        stats.setTotalUsers(totalUsers);
        stats.setActiveUsers24h(activeUsers24h.size());
        stats.setActiveUsers1h(activeUsers1h.size());
        
        // 计算活跃率
        if (totalUsers > 0) {
            stats.setActivityRate24h((double) activeUsers24h.size() / totalUsers * 100);
        }

        log.info("📊 用户活跃统计 - 总数: {}, 24h活跃: {}, 1h活跃: {}, 24h活跃率: {:.1f}%", 
                totalUsers, activeUsers24h.size(), activeUsers1h.size(), stats.getActivityRate24h());

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

    // ===================================================================
    // 内部类：统计数据结构
    // ===================================================================

    /**
     * 活跃统计数据结构
     */
    public static class ActivityStatistics {
        private long totalUsers;
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
    }
}