package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 🎯 @Cacheable 注解参数详解示例
 * 
 * 本类详细演示了 @Cacheable 注解中各种参数的用法，
 * 包括 SpEL 表达式的语法和实际作用。
 * 
 * 【SpEL 表达式基础】
 * SpEL（Spring Expression Language）是Spring的表达式语言，
 * 可以在运行时访问方法参数、返回值、对象属性等。
 * 
 * 常用语法：
 * - #参数名：访问方法参数
 * - #result：访问方法返回值
 * - #root.methodName：访问方法名
 * - #root.args[0]：访问第一个参数
 * - #root.target：访问目标对象
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheableParametersExample {

    private final UserProfileRepository userProfileRepository;

    // ═══════════════════════════════════════════════════════════════
    // 🎯 1. value/cacheNames 参数 - 缓存名称
    // ═══════════════════════════════════════════════════════════════

    /**
     * 【参数】value 或 cacheNames
     * 【作用】指定缓存的名称，用于匹配 CacheConfig 中的配置
     * 【类型】String 或 String[]
     * 
     * 【匹配过程】
     * value="user-profiles" → CacheManager.getCache("user-profiles")
     * → 查找配置中的 withCacheConfiguration("user-profiles", ...)
     * → 应用对应的TTL、前缀、空值策略等配置
     */
    @Cacheable(value = "user-profiles")  // 单个缓存名称
    public Optional<UserProfile> basicExample(String userId) {
        log.info("📝 使用基础缓存配置查询: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 【多缓存名称】可以同时在多个缓存中存储相同数据
     * 适用场景：需要用不同策略缓存同一份数据
     */
    @Cacheable(cacheNames = {"user-profiles", "backup-profiles"})  // 多个缓存名称
    public Optional<UserProfile> multiCacheExample(String userId) {
        log.info("📝 存储到多个缓存中: {}", userId);
        return userProfileRepository.findById(userId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 🎯 2. key 参数 - 缓存键生成
    // ═══════════════════════════════════════════════════════════════

    /**
     * 【默认key生成】
     * 如果不指定key，Spring会使用默认的key生成器：
     * - 无参数：SimpleKey.EMPTY
     * - 单参数：参数值本身
     * - 多参数：SimpleKey包装的参数组合
     */
    @Cacheable(value = "user-profiles")  // 默认key = userId
    public Optional<UserProfile> defaultKeyExample(String userId) {
        log.info("📝 默认key生成: key={}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 【简单参数key】
     * 使用 #参数名 访问方法参数
     */
    @Cacheable(value = "user-profiles", key = "#userId")  // key = userId的值
    public Optional<UserProfile> simpleKeyExample(String userId) {
        log.info("📝 简单参数key: key={}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 【复合key】
     * 组合多个参数生成复合key，用于多参数方法
     */
    @Cacheable(value = "user-queries", key = "#userId + '_' + #includeEmail + '_' + #includePhone")
    public Optional<UserProfile> compositeKeyExample(String userId, boolean includeEmail, boolean includePhone) {
        log.info("📝 复合key: userId={}, includeEmail={}, includePhone={}", userId, includeEmail, includePhone);
        // 生成的key示例: "user123_true_false"
        return userProfileRepository.findById(userId);
    }

    /**
     * 【对象属性key】
     * 访问复杂对象的属性作为key
     */
    @Cacheable(value = "user-profiles", key = "#profile.userId")
    public UserProfile saveProfileExample(UserProfile profile) {
        log.info("📝 对象属性key: key={}", profile.getUserId());
        return userProfileRepository.save(profile);
    }

    /**
     * 【方法名 + 参数key】
     * 使用方法名和参数组合，避免不同方法间的key冲突
     */
    @Cacheable(value = "method-specific", key = "#root.methodName + '_' + #userId")
    public Optional<UserProfile> methodSpecificKeyExample(String userId) {
        log.info("📝 方法特定key: key=methodSpecificKeyExample_{}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 【复杂SpEL表达式】
     * 使用条件表达式和字符串操作
     */
    @Cacheable(value = "conditional-keys", 
               key = "#userId.length() > 5 ? #userId.substring(0,5) : #userId")
    public Optional<UserProfile> complexKeyExample(String userId) {
        log.info("📝 复杂key表达式");
        // 如果userId长度>5，取前5个字符；否则使用完整userId
        return userProfileRepository.findById(userId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 🎯 3. unless 参数 - 不缓存的条件
    // ═══════════════════════════════════════════════════════════════

    /**
     * 【unless基本用法】
     * unless = "#result.isEmpty()" 表示：如果返回结果为空，则不缓存
     * 
     * 【执行时机】方法执行完毕后评估
     * 【作用】防止缓存无效数据，避免缓存空结果
     */
    @Cacheable(value = "user-profiles", key = "#userId", unless = "#result.isEmpty()")
    public Optional<UserProfile> unlessEmptyExample(String userId) {
        log.info("📝 unless空值示例: {}", userId);
        Optional<UserProfile> result = userProfileRepository.findById(userId);
        
        if (result.isEmpty()) {
            log.info("   ⚠️ 返回空值，根据unless条件，不会缓存");
        } else {
            log.info("   ✅ 返回有效数据，会被缓存");
        }
        
        return result;
    }

    /**
     * 【unless多条件】
     * 多个条件用 or 或 and 连接
     */
    @Cacheable(value = "user-profiles", 
               key = "#userId", 
               unless = "#result.isEmpty() or #result.get().email == null")
    public Optional<UserProfile> unlessMultiConditionExample(String userId) {
        log.info("📝 unless多条件: 不缓存空值或邮箱为空的用户");
        return userProfileRepository.findById(userId);
    }

    /**
     * 【unless基于参数】
     * 基于输入参数决定是否缓存
     */
    @Cacheable(value = "temp-cache", 
               key = "#userId", 
               unless = "#skipCache == true")
    public Optional<UserProfile> unlessParameterExample(String userId, boolean skipCache) {
        log.info("📝 unless参数条件: skipCache={}", skipCache);
        if (skipCache) {
            log.info("   ⚠️ skipCache=true，不会缓存结果");
        }
        return userProfileRepository.findById(userId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 🎯 4. condition 参数 - 缓存的前置条件
    // ═══════════════════════════════════════════════════════════════

    /**
     * 【condition vs unless区别】
     * - condition：方法执行BEFORE评估，决定是否启用缓存
     * - unless：方法执行AFTER评估，决定是否存储结果
     * 
     * 【condition用法】
     * 只有满足条件才启用缓存机制
     */
    @Cacheable(value = "conditional-cache", 
               key = "#userId",
               condition = "#userId != null and #userId.length() > 0")
    public Optional<UserProfile> conditionExample(String userId) {
        log.info("📝 condition示例: 只对有效userId启用缓存");
        if (userId == null || userId.isEmpty()) {
            log.info("   ⚠️ userId无效，缓存机制不会启用");
        }
        return userProfileRepository.findById(userId);
    }

    /**
     * 【condition + unless组合】
     * 精确控制缓存行为
     */
    @Cacheable(value = "precise-cache", 
               key = "#userId",
               condition = "#userId.startsWith('user_')",  // 只对特定前缀的用户启用缓存
               unless = "#result.isEmpty()")               // 有效数据才缓存
    public Optional<UserProfile> conditionUnlessExample(String userId) {
        log.info("📝 condition+unless组合: userId={}", userId);
        return userProfileRepository.findById(userId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 🎯 5. sync 参数 - 同步缓存
    // ═══════════════════════════════════════════════════════════════

    /**
     * 【sync参数作用】
     * sync=true 确保同一个key的缓存加载是同步的，防止缓存击穿
     * 
     * 【使用场景】
     * - 高并发场景下的热点数据
     * - 数据库查询成本很高的场景
     * - 需要防止缓存击穿的场景
     */
    @Cacheable(value = "sync-cache", key = "#userId", sync = true)
    public Optional<UserProfile> syncExample(String userId) {
        log.info("📝 同步缓存示例: {}", userId);
        log.info("   🔒 同一key的并发请求会排队等待，避免重复查询数据库");
        
        // 模拟耗时操作
        try {
            Thread.sleep(1000);  // 模拟1秒的数据库查询时间
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return userProfileRepository.findById(userId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 🎯 6. 高级SpEL表达式示例
    // ═══════════════════════════════════════════════════════════════

    /**
     * 【访问返回值属性】
     * 使用 #result 访问方法返回值的属性
     */
    @Cacheable(value = "result-based", 
               key = "#userId",
               unless = "#result.isPresent() and #result.get().email.contains('test')")
    public Optional<UserProfile> resultBasedUnlessExample(String userId) {
        log.info("📝 基于返回值的unless: 不缓存测试邮箱用户");
        return userProfileRepository.findById(userId);
    }

    /**
     * 【访问方法元信息】
     * 使用 #root 访问方法的元信息
     */
    @Cacheable(value = "method-info", 
               key = "#root.methodName + '_' + #root.args[0] + '_' + #includeDetails")
    public Optional<UserProfile> methodInfoExample(String userId, boolean includeDetails) {
        log.info("📝 方法元信息key: method={}, args={}", 
                 "methodInfoExample", new Object[]{userId, includeDetails});
        return userProfileRepository.findById(userId);
    }

    /**
     * 【使用工具类方法】
     * 在SpEL中调用静态方法或Spring Bean的方法
     */
    @Cacheable(value = "time-based", 
               key = "#userId + '_' + T(java.time.LocalDateTime).now().getHour()",
               unless = "#result.isEmpty()")
    public Optional<UserProfile> timeBasedKeyExample(String userId) {
        log.info("📝 基于时间的key: 每小时一个key");
        return userProfileRepository.findById(userId);
    }

    /**
     * 【复杂条件表达式】
     * 组合多种条件和操作符
     */
    @Cacheable(value = "complex-conditions",
               key = "#query.userId",
               condition = "#query.userId != null and #query.userId.length() > 0",
               unless = "#result.isEmpty() or (#result.isPresent() and #result.get().email == null)")
    public Optional<UserProfile> complexConditionsExample(UserQuery query) {
        log.info("📝 复杂条件示例");
        return userProfileRepository.findById(query.getUserId());
    }

    /**
     * 辅助类：用于复杂参数示例
     */
    public static class UserQuery {
        private String userId;
        private List<String> fields;
        private boolean includeMetadata;
        
        // 构造函数和getter/setter
        public UserQuery(String userId) {
            this.userId = userId;
        }
        
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public List<String> getFields() { return fields; }
        public void setFields(List<String> fields) { this.fields = fields; }
        public boolean isIncludeMetadata() { return includeMetadata; }
        public void setIncludeMetadata(boolean includeMetadata) { this.includeMetadata = includeMetadata; }
    }

    // ═══════════════════════════════════════════════════════════════
    // 🎯 7. 常见错误和最佳实践
    // ═══════════════════════════════════════════════════════════════

    /**
     * ❌ 错误示例1：在unless中访问不存在的属性
     * 
     * @Cacheable(unless = "#result.nonExistentProperty == null")  // 会抛异常
     */
    
    /**
     * ✅ 正确示例1：安全的属性访问
     */
    @Cacheable(value = "safe-access",
               key = "#userId", 
               unless = "#result.isEmpty() or (#result.isPresent() and #result.get().email?.length() == 0)")
    public Optional<UserProfile> safePropertyAccessExample(String userId) {
        log.info("📝 安全属性访问: 使用?安全导航操作符");
        return userProfileRepository.findById(userId);
    }

    /**
     * ✅ 最佳实践：缓存key命名规范
     */
    @Cacheable(value = "user-profiles",
               key = "'profile:' + #userId + ':v1'")  // 包含版本号，便于缓存失效
    public Optional<UserProfile> bestPracticeKeyExample(String userId) {
        log.info("📝 最佳实践key: 包含前缀和版本号");
        return userProfileRepository.findById(userId);
    }

    /**
     * 🎯 演示方法：展示所有参数的实际效果
     */
    public void demonstrateAllParameters() {
        log.info("\n🎭 ===== @Cacheable 参数演示 =====");
        
        // 测试不同的key生成
        defaultKeyExample("demo-user");
        simpleKeyExample("demo-user");
        compositeKeyExample("demo-user", true, false);
        
        // 测试unless条件
        unlessEmptyExample("non-existent-user");  // 不会缓存
        unlessEmptyExample("existing-user");      // 会缓存
        
        // 测试condition条件
        conditionExample("");           // condition失败，不启用缓存
        conditionExample("valid-user"); // condition成功，启用缓存
        
        log.info("🎭 ===== 演示完成 =====\n");
    }
} 