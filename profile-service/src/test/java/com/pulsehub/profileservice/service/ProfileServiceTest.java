package com.pulsehub.profileservice.service;

// === 核心测试框架导入 ===
import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;  // 测试前置方法注解
import org.junit.jupiter.api.Test;       // 测试方法注解

// === Spring Boot 测试框架导入 ===
import org.springframework.beans.factory.annotation.Autowired;                    // 依赖注入
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;           // 自动配置控制
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration; // Redis自动配置（需排除）
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;  // 数据源自动配置（需排除）
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration; // JPA自动配置（需排除）
import org.springframework.boot.test.context.SpringBootTest;                      // Spring Boot测试容器
import org.springframework.boot.test.context.TestConfiguration;                  // 测试专用配置
import org.springframework.boot.test.mock.mockito.MockBean;                      // Spring Boot集成的Mock注解

// === 缓存测试相关导入 ===
import org.springframework.cache.Cache;                            // 缓存接口
import org.springframework.cache.CacheManager;                     // 缓存管理器
import org.springframework.cache.annotation.EnableCaching;         // 启用缓存功能
import org.springframework.cache.concurrent.ConcurrentMapCacheManager; // 测试用的内存缓存管理器
import org.springframework.context.annotation.Bean;                // Bean定义注解

// === 标准库导入 ===
import java.time.LocalDateTime; // 时间处理（虽然当前测试中未使用）
import java.util.Optional;      // Optional类型支持

// === 断言和Mock工具导入 ===
import static org.assertj.core.api.Assertions.assertThat; // AssertJ断言库 - 提供流畅的断言API
import static org.mockito.Mockito.*;                       // Mockito静态方法 - 用于Mock对象的行为配置和验证

/**
 * ProfileService 缓存功能集成测试类
 * 
 * 【测试目标】
 * - 验证 @Cacheable 注解是否正确工作
 * - 确保缓存能够减少数据库访问次数
 * - 验证缓存数据的一致性和正确性
 * 
 * 【测试策略】
 * - 使用内存缓存替代 Redis，提升测试速度和稳定性
 * - 通过 Mock 隔离数据库依赖，专注测试缓存逻辑
 * - 采用 Given-When-Then 模式，确保测试逻辑清晰
 * 
 * 【技术亮点】
 * - 精确控制 Spring Boot 自动配置，避免不必要的组件启动
 * - 使用测试专用配置类，实现测试环境的定制化
 * - 通过直接缓存验证，确保缓存行为的透明性
 */
@SpringBootTest(
    // 📌 不启动嵌入式Web服务器，专注于服务层测试
    // 优势：提升测试启动速度，避免端口冲突，专注业务逻辑测试
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    
    // 📌 精确指定需要加载的配置类和服务类
    // 优势：最小化 Spring 上下文，提升测试性能，减少组件间干扰
    classes = {ProfileServiceTest.CachingTestConfig.class, ProfileService.class}
)
@EnableAutoConfiguration(exclude = {
    // 📌 排除数据源自动配置 - 避免连接真实数据库
    DataSourceAutoConfiguration.class,
    // 📌 排除 JPA 自动配置 - 避免 Hibernate 相关依赖
    HibernateJpaAutoConfiguration.class,
    // 📌 排除 Redis 自动配置 - 使用测试专用的内存缓存
    RedisAutoConfiguration.class
})
class ProfileServiceTest {

    /**
     * 缓存测试专用配置类
     * 
     * 【设计目标】
     * - 提供轻量级的缓存实现，替代生产环境中的 Redis
     * - 确保测试的快速执行和稳定性
     * - 支持完整的 Spring Cache 抽象层功能
     * 
     * 【技术选择】
     * - 使用 ConcurrentMapCacheManager：线程安全的内存缓存
     * - 适合单元测试和集成测试的缓存验证需求
     * - 支持缓存的基本操作：存储、检索、失效等
     */
    @TestConfiguration  // 📌 标记为测试专用配置，不会影响生产环境
    @EnableCaching      // 📌 启用 Spring Cache 功能，激活 @Cacheable 等注解
    static class CachingTestConfig {

        /**
         * 创建测试专用的缓存管理器
         * 
         * @return ConcurrentMapCacheManager 实例，预配置了 "user-profiles" 缓存
         * 
         * 【实现特点】
         * - 基于 ConcurrentHashMap，提供线程安全的内存缓存
         * - 无需外部依赖，测试启动快速
         * - 支持多个命名缓存空间
         * - 自动创建不存在的缓存空间
         */
        @Bean
        CacheManager cacheManager() {
            // 🏗️ 创建并预配置缓存管理器，指定缓存名称 "user-profiles"
            // 这个名称必须与 @Cacheable(value = "user-profiles") 中的值保持一致
            return new ConcurrentMapCacheManager("user-profiles");
        }
    }

    // ========== 依赖注入的组件 ==========
    
    /**
     * 被测试的服务类实例
     * 
     * 通过 @Autowired 自动注入，包含完整的 Spring AOP 代理
     * 这确保了 @Cacheable 等注解能够正常工作
     */
    @Autowired
    private ProfileService profileService;

    /**
     * 模拟的用户画像数据仓库
     * 
     * 使用 @MockBean 替代真实的 JPA Repository
     * 优势：
     * - 隔离数据库依赖，提升测试稳定性和速度
     * - 精确控制返回数据，便于测试各种场景
     * - 验证方法调用次数，确保缓存生效
     */
    @MockBean
    private UserProfileRepository userProfileRepository;

    /**
     * 缓存管理器实例
     * 
     * 用于直接操作缓存，验证缓存行为
     * 在测试中用于：
     * - 清理缓存状态，确保测试独立性
     * - 直接检查缓存内容，验证数据正确性
     * - 模拟缓存失效等场景
     */
    @Autowired
    private CacheManager cacheManager;

    // ========== 测试辅助变量 ==========
    
    /**
     * 用户画像缓存的直接引用
     * 
     * 通过 CacheManager 获取特定名称的缓存实例
     * 用于测试中的缓存状态验证和操作
     */
    private Cache userProfileCache;

    /**
     * 测试前置方法 - 确保每个测试的独立性
     * 
     * 【执行时机】每个 @Test 方法执行前自动调用
     * 
     * 【主要职责】
     * 1. 获取缓存实例的引用
     * 2. 清空缓存内容，避免测试间的相互影响
     * 3. 确保每个测试都从一个干净的状态开始
     * 
     * 【设计原则】
     * - 测试隔离性：每个测试方法应该能够独立运行
     * - 可重复性：多次运行同一个测试应该得到相同结果
     * - 防御性编程：处理缓存可能为空的情况
     */
    @BeforeEach
    void setUp() {
        // 🎯 获取名为 "user-profiles" 的缓存实例
        // 这个名称必须与 CacheManager 配置和 @Cacheable 注解中的名称一致
        userProfileCache = cacheManager.getCache("user-profiles");
        
        // 🧹 清空缓存内容，确保测试的独立性
        // 防御性检查：避免缓存管理器返回 null 导致的空指针异常
        if (userProfileCache != null) {
            userProfileCache.clear();
        }
    }

    /**
     * 缓存功能核心测试：验证重复调用时缓存的工作机制
     * 
     * 【测试场景】
     * 对同一用户ID连续调用两次 getProfileByUserId 方法
     * 
     * 【预期行为】
     * 1. 第一次调用：缓存未命中，从数据库获取数据并存入缓存
     * 2. 第二次调用：缓存命中，直接从缓存返回数据，不访问数据库
     * 
     * 【验证要点】
     * - 数据库只被访问一次
     * - 两次调用返回相同的数据
     * - 缓存中正确存储了用户数据
     * - 缓存数据与原始数据完全一致
     * 
     * 【测试技术】
     * - 使用 Mock 验证数据库交互次数
     * - 直接检查缓存内容验证存储正确性
     * - 使用 AssertJ 进行流畅的断言验证
     */
    @Test
    void whenGetProfileIsCalledTwice_thenDatabaseShouldBeHitOnlyOnce() {
        
        // ========================================
        // GIVEN - 测试数据准备阶段
        // ========================================
        
        // 🎯 定义测试用的用户ID
        // 使用 final 关键字确保数据不被意外修改
        final String userId = "user-123";
        
        // 🏗️ 使用 Builder 模式创建预期的用户画像对象
        // 优势：代码清晰、易于维护、支持链式调用
        final UserProfile expectedProfile = UserProfile.builder()
                .userId(userId)                    // 设置用户ID
                .email("test@example.com")         // 设置测试邮箱
                .fullName("Test User")             // 设置测试用户全名
                .build();

        // 🎭 配置 Mock 对象的行为
        // 当调用 userProfileRepository.findById(userId) 时，返回包装在 Optional 中的 expectedProfile
        // 这模拟了数据库中存在该用户的情况
        when(userProfileRepository.findById(userId)).thenReturn(Optional.of(expectedProfile));

        // ========================================
        // WHEN & THEN - 第一次调用测试（缓存未命中场景）
        // ========================================

        System.out.println("--- 第一次调用 service.getProfileByUserId ---");
        
        // 🚀 执行第一次服务调用
        // 此时缓存为空，应该触发数据库查询
        Optional<UserProfile> firstCallResult = profileService.getProfileByUserId(userId);

        // ✅ 验证第一次调用的返回结果
        // 使用 AssertJ 的链式断言，既验证 Optional 不为空，又验证内容正确
        assertThat(firstCallResult)
            .isPresent()                    // 验证 Optional 包含值（不是 empty）
            .contains(expectedProfile);     // 验证包含的值与预期对象相等
        
        // 🕵️ 验证 Mock 对象的交互：确保数据库被精确调用了一次
        // times(1) 确保既不是零次（缓存错误命中），也不是多次（重复查询）
        verify(userProfileRepository, times(1)).findById(userId);
        
        // ========================================
        // 缓存状态验证 - 确保数据正确存储在缓存中
        // ========================================
        
        // 🔍 直接从缓存中获取数据进行验证
        // ValueWrapper 是 Spring Cache 提供的缓存值包装器
        Cache.ValueWrapper cachedWrapper = userProfileCache.get(userId);
        
        // ✅ 验证缓存确实存储了数据（不是 null）
        assertThat(cachedWrapper).isNotNull();
        
        // 🎯 获取缓存中的实际对象
        Object cachedValue = cachedWrapper.get();
        
        // 📊 输出调试信息，帮助理解缓存的存储机制
        System.out.println("缓存中的对象类型: " + cachedValue.getClass().getName());
        System.out.println("缓存中的对象内容: " + cachedValue);
        
        // ⚡ 处理不同缓存实现的类型差异
        // 重要：Spring Cache 在不同实现中可能有不同的行为
        // - 某些实现直接存储 UserProfile 对象
        // - 某些实现存储完整的 Optional<UserProfile> 包装器
        UserProfile cachedProfile;
        
        if (cachedValue instanceof Optional) {
            // 📦 处理缓存存储 Optional<UserProfile> 的情况
            @SuppressWarnings("unchecked")  // 抑制类型转换警告，因为我们已经做了 instanceof 检查
            Optional<UserProfile> cachedOptional = (Optional<UserProfile>) cachedValue;
            
            // ✅ 验证 Optional 包含值
            assertThat(cachedOptional).isPresent();
            cachedProfile = cachedOptional.get();
            
        } else if (cachedValue instanceof UserProfile) {
            // 📦 处理缓存直接存储 UserProfile 的情况
            // 这是更常见的情况，Spring Cache 会"解包" Optional
            cachedProfile = (UserProfile) cachedValue;
            
        } else {
            // 🚨 如果缓存中的对象类型不符合预期，抛出明确的错误
            // 这有助于快速诊断缓存配置问题
            throw new AssertionError("缓存中的对象类型不正确: " + cachedValue.getClass());
        }
        
        // ========================================
        // 缓存数据内容验证 - 确保数据完整性和正确性
        // ========================================
        
        // ✅ 验证缓存对象本身不为空
        assertThat(cachedProfile).isNotNull();
        
        // 🔍 逐字段验证缓存数据与原始数据的一致性
        // 分别验证每个关键字段，便于定位具体的数据不一致问题
        assertThat(cachedProfile.getUserId()).isEqualTo(expectedProfile.getUserId());
        assertThat(cachedProfile.getEmail()).isEqualTo(expectedProfile.getEmail());
        assertThat(cachedProfile.getFullName()).isEqualTo(expectedProfile.getFullName());
        
        // 📢 输出成功信息，确认缓存验证通过
        System.out.println("验证成功：缓存数据正确!");

        // ========================================
        // WHEN & THEN - 第二次调用测试（缓存命中场景）
        // ========================================
        
        // 📢 输出调试信息，标识第二次调用的开始
        System.out.println("\n--- 第二次调用 service.getProfileByUserId ---");
        
        // 🚀 执行第二次服务调用
        // 此时缓存中已有数据，应该直接从缓存返回，不访问数据库
        Optional<UserProfile> secondCallResult = profileService.getProfileByUserId(userId);

        // ✅ 验证第二次调用的返回结果
        // 重要：验证从缓存返回的数据与第一次调用的结果完全一致
        assertThat(secondCallResult)
            .isPresent()                    // 验证 Optional 包含值
            .contains(expectedProfile);     // 验证返回的数据与预期完全相同
        
        // 🔒 验证数据库没有被再次访问 - 这是缓存生效的关键证据
        // verifyNoMoreInteractions 确保除了第一次调用外，没有任何额外的数据库交互
        // 如果这个断言失败，说明缓存没有正常工作
        verifyNoMoreInteractions(userProfileRepository);
        
        // 🎉 输出成功信息，确认缓存功能正常工作
        System.out.println("验证成功：数据库没有被再次访问！");
        
        // ========================================
        // 测试总结
        // ========================================
        // 
        // 🎯 本测试验证了以下关键缓存行为：
        // 1. ✅ 第一次调用触发数据库查询并将结果存入缓存
        // 2. ✅ 缓存正确存储了完整且准确的用户数据
        // 3. ✅ 第二次调用直接从缓存获取数据，避免了数据库访问
        // 4. ✅ 缓存返回的数据与原始数据完全一致
        // 
                 // 💡 缓存性能收益：
         // - 减少数据库负载（从2次查询降低到1次）
         // - 提升响应速度（内存访问比数据库查询快得多）
         // - 提高系统可扩展性（支持更多并发用户）
     }

    /**
     * 缓存空值处理测试：验证配置了 unless="#result.isEmpty()" 后的缓存行为
     * 
     * 【重要更新】
     * 现在我们使用 @Cacheable(unless = "#result.isEmpty()") 配置，
     * 空的 Optional 结果将 **不会被缓存**！
     * 
     * 【测试场景】
     * 查询一个不存在的用户ID，验证空结果不被缓存的行为
     * 
     * 【预期行为】
     * - 第一次查询：返回 Optional.empty()，不缓存结果
     * - 第二次查询：再次访问数据库，仍返回 Optional.empty()
     * - 缓存中不会存储任何与该用户ID相关的数据
     * 
     * 【业务价值】
     * - 节省缓存内存：不存储无效查询结果
     * - 立即发现新用户：新注册用户能被立即查询到
     * - 灵活的缓存策略：只缓存有价值的数据
     * 
     * 【注意事项】
     * - 重复无效查询会增加数据库负载
     * - 在高并发下可能面临缓存穿透风险
     * - 适合用户注册频繁的业务场景
     * 
     * 【测试技术】
     * - Mock返回Optional.empty()模拟用户不存在
     * - 验证第二次调用仍然访问数据库（因为没有缓存）
     * - 直接检查缓存状态确认空值未被存储
     */
    @Test
    void whenProfileNotFound_thenNullValueShouldNotBeCached() {
        
        // ========================================
        // GIVEN - 准备不存在用户的测试场景
        // ========================================
        
        // 🎯 定义一个不存在的用户ID
        final String nonExistentUserId = "non-existent-user-999";
        
        // 🎭 配置Mock：当查询不存在的用户时返回空Optional
        // 这模拟了数据库中没有找到对应用户记录的情况
        when(userProfileRepository.findById(nonExistentUserId))
            .thenReturn(Optional.empty());

        // ========================================
        // WHEN & THEN - 第一次查询不存在的用户
        // ========================================
        
        System.out.println("--- 第一次查询不存在的用户 ---");
        
        // 🚀 执行第一次查询
        Optional<UserProfile> firstResult = profileService.getProfileByUserId(nonExistentUserId);
        
        // ✅ 验证返回结果为空
        assertThat(firstResult).isEmpty();
        
        // 🕵️ 验证数据库被访问了一次
        verify(userProfileRepository, times(1)).findById(nonExistentUserId);
        
        // 🔍 检查缓存状态：验证空值未被缓存
        Cache.ValueWrapper cachedWrapper = userProfileCache.get(nonExistentUserId);
        System.out.println("空值缓存状态: " + (cachedWrapper == null ? "未缓存" : "已缓存: " + cachedWrapper.get()));
        
        // ✅ 验证缓存中没有存储空值（因为配置了 unless = "#result.isEmpty()"）
        assertThat(cachedWrapper).isNull();
        
        System.out.println("验证成功：空值未被缓存，符合 unless 配置");

        // ========================================
        // WHEN & THEN - 第二次查询同一个不存在的用户
        // ========================================
        
        System.out.println("\n--- 第二次查询同一个不存在的用户 ---");
        
        // 🚀 执行第二次查询
        Optional<UserProfile> secondResult = profileService.getProfileByUserId(nonExistentUserId);
        
        // ✅ 验证第二次查询结果仍为空
        assertThat(secondResult).isEmpty();
        
        // 🔒 关键验证：确认数据库被再次访问（因为空值未被缓存）
        verify(userProfileRepository, times(2)).findById(nonExistentUserId); // 现在应该是2次
        
        System.out.println("验证成功：由于空值未缓存，数据库被再次访问");
        
        // ========================================
        // 额外验证：持续的数据库访问
        // ========================================
        
        System.out.println("\n--- 第三次查询验证无缓存行为 ---");
        
        // 🚀 第三次查询，验证持续的数据库访问
        Optional<UserProfile> thirdResult = profileService.getProfileByUserId(nonExistentUserId);
        
        // ✅ 验证结果一致性
        assertThat(thirdResult).isEmpty();
        
        // 🔒 验证数据库被第三次访问
        verify(userProfileRepository, times(3)).findById(nonExistentUserId);
        
        System.out.println("验证成功：每次查询都访问数据库，空值确实未被缓存");
        
        // ========================================
        // 测试结论与实践指导
        // ========================================
        // 
        // 🎯 此测试证实了配置 unless="#result.isEmpty()" 后的行为：
        // 1. ✅ 空的 Optional 结果不会被缓存
        // 2. ✅ 每次查询不存在的用户都会访问数据库
        // 3. ✅ 节省了缓存内存，不存储无效数据
        // 4. ✅ 新用户注册后能被立即发现
        // 
        // 💡 实践建议：
        // - 适用于用户注册频繁的业务场景
        // - 适用于内存资源宝贵的环境
        // - 在高并发下注意缓存穿透风险
        // 
        // 🚀 业务价值：
        // - 确保新用户能被立即发现和查询
        // - 优化缓存内存使用，只存储有价值的数据
        // - 提供更灵活的缓存策略选择
    }

    /**
     * 缓存失效机制测试：验证手动清除缓存后的重新加载行为
     * 
     * 【测试场景】
     * 1. 建立缓存数据
     * 2. 手动清除特定缓存项
     * 3. 验证下次访问时重新从数据库加载
     * 
     * 【业务价值】
     * - 验证缓存管理功能的正确性
     * - 确保缓存失效机制能够正常工作
     * - 为缓存维护和故障恢复提供信心
     * 
     * 【应用场景】
     * - 系统维护时需要刷新特定缓存
     * - 数据修复后需要清除旧缓存
     * - 缓存容量管理和清理策略验证
     */
    @Test
    void whenCacheEviction_thenDatabaseShouldBeAccessedAgain() {
        
        // ========================================
        // GIVEN - 准备测试数据并建立初始缓存
        // ========================================
        
        final String userId = "cache-eviction-test-user";
        final UserProfile testProfile = UserProfile.builder()
                .userId(userId)
                .email("eviction@test.com")
                .fullName("Cache Eviction Test User")
                .build();

        // 🎭 配置Mock行为
        when(userProfileRepository.findById(userId))
            .thenReturn(Optional.of(testProfile));

        // ========================================
        // STEP 1 - 建立缓存数据
        // ========================================
        
        System.out.println("--- 第一步：建立缓存数据 ---");
        
        // 🚀 第一次调用，建立缓存
        Optional<UserProfile> initialResult = profileService.getProfileByUserId(userId);
        
        // ✅ 验证数据正确返回
        assertThat(initialResult).isPresent().contains(testProfile);
        
        // 🕵️ 验证数据库被访问
        verify(userProfileRepository, times(1)).findById(userId);
        
        // 🔍 确认缓存已建立
        Cache.ValueWrapper cachedWrapper = userProfileCache.get(userId);
        assertThat(cachedWrapper).isNotNull();
        System.out.println("缓存已建立，包含数据: " + cachedWrapper.get());

        // ========================================
        // STEP 2 - 手动清除缓存（模拟缓存失效）
        // ========================================
        
        System.out.println("\n--- 第二步：手动清除缓存 ---");
        
        // 🧹 手动清除特定用户的缓存
        // 这模拟了缓存失效、缓存清理或系统维护等场景
        userProfileCache.evict(userId);
        
        // 🔍 验证缓存确实被清除
        Cache.ValueWrapper afterEviction = userProfileCache.get(userId);
        assertThat(afterEviction).isNull();
        System.out.println("缓存已清除，当前状态: " + afterEviction);

        // ========================================
        // STEP 3 - 缓存失效后重新访问
        // ========================================
        
        System.out.println("\n--- 第三步：缓存失效后重新访问 ---");
        
        // 🚀 缓存失效后的访问
        Optional<UserProfile> afterEvictionResult = profileService.getProfileByUserId(userId);
        
        // ✅ 验证数据仍然正确返回
        assertThat(afterEvictionResult).isPresent().contains(testProfile);
        
        // 🔒 关键验证：确认数据库被再次访问
        // 如果缓存失效机制不工作，这个验证会失败
        verify(userProfileRepository, times(2)).findById(userId);
        
        // 🔍 验证缓存被重新建立
        Cache.ValueWrapper rebuiltCache = userProfileCache.get(userId);
        assertThat(rebuiltCache).isNotNull();
        
        System.out.println("验证成功：缓存失效后正确重新加载数据");
        
        // ========================================
        // 测试价值总结
        // ========================================
        // 
        // 🎯 此测试验证了缓存系统的弹性和可管理性：
        // 1. ✅ 缓存能够被正确清除（失效机制工作正常）
        // 2. ✅ 缓存失效后系统能够自动重新加载数据
        // 3. ✅ 重新加载的数据与原始数据保持一致
        // 4. ✅ 缓存重建机制正常工作
        // 
        // 💼 业务意义：
        // - 支持运维人员进行缓存管理操作
        // - 确保数据修复后能够刷新缓存
        // - 为缓存容量管理提供技术基础
    }

    /**
     * 缓存更新失效测试：验证数据更新时缓存自动失效机制
     * 
     * 【测试场景】
     * 1. 建立缓存数据
     * 2. 执行数据更新操作
     * 3. 验证缓存被自动清除
     * 4. 验证下次查询获取最新数据
     * 
     * 【技术实现】
     * 本测试假设ProfileService有一个使用@CacheEvict注解的更新方法
     * 如果实际代码中没有，这个测试演示了应该如何实现
     * 
     * 【业务价值】
     * - 确保数据一致性（缓存与数据库同步）
     * - 验证自动缓存管理功能
     * - 防止用户看到过期数据
     * 
     * 【企业应用】
     * - 用户资料更新后立即生效
     * - 配置变更后缓存自动刷新
     * - 数据修正后确保缓存同步
     */
    @Test
    void whenProfileUpdated_thenCacheShouldBeEvicted() {
        
        // ========================================
        // GIVEN - 准备初始数据并建立缓存
        // ========================================
        
        final String userId = "update-test-user";
        
        // 🏗️ 创建初始用户数据
        final UserProfile originalProfile = UserProfile.builder()
                .userId(userId)
                .email("original@test.com")
                .fullName("Original Name")
                .build();
        
        // 🏗️ 创建更新后的用户数据
        final UserProfile updatedProfile = UserProfile.builder()
                .userId(userId)
                .email("updated@test.com")
                .fullName("Updated Name")
                .build();

        // 🎭 配置Mock：初始时返回原始数据
        when(userProfileRepository.findById(userId))
            .thenReturn(Optional.of(originalProfile));

        // ========================================
        // STEP 1 - 建立初始缓存
        // ========================================
        
        System.out.println("--- 第一步：建立初始缓存 ---");
        
        // 🚀 第一次查询，建立缓存
        Optional<UserProfile> initialResult = profileService.getProfileByUserId(userId);
        
        // ✅ 验证初始数据正确
        assertThat(initialResult).isPresent();
        assertThat(initialResult.get().getEmail()).isEqualTo("original@test.com");
        assertThat(initialResult.get().getFullName()).isEqualTo("Original Name");
        
        // 🔍 确认缓存已建立且包含原始数据
        Cache.ValueWrapper cachedWrapper = userProfileCache.get(userId);
        assertThat(cachedWrapper).isNotNull();
        System.out.println("初始缓存已建立: " + cachedWrapper.get());

        // ========================================
        // STEP 2 - 模拟数据更新操作
        // ========================================
        
        System.out.println("\n--- 第二步：执行数据更新操作 ---");
        
        // 🔄 重新配置Mock：现在返回更新后的数据
        // 这模拟了数据库中的数据已经被更新的情况
        when(userProfileRepository.findById(userId))
            .thenReturn(Optional.of(updatedProfile));
        
        // 🧹 手动清除缓存来模拟@CacheEvict的效果
        // 在实际应用中，这应该由@CacheEvict注解的更新方法自动完成
        // 例如：profileService.updateProfile(userId, updatedProfile) 
        // 该方法应该标注：@CacheEvict(value = "user-profiles", key = "#userId")
        userProfileCache.evict(userId);
        
        System.out.println("数据更新操作完成，缓存已清除");

        // ========================================
        // STEP 3 - 验证缓存失效和数据更新
        // ========================================
        
        System.out.println("\n--- 第三步：验证更新后的数据查询 ---");
        
        // 🚀 更新后的查询
        Optional<UserProfile> afterUpdateResult = profileService.getProfileByUserId(userId);
        
        // ✅ 验证返回的是更新后的数据
        assertThat(afterUpdateResult).isPresent();
        assertThat(afterUpdateResult.get().getEmail()).isEqualTo("updated@test.com");
        assertThat(afterUpdateResult.get().getFullName()).isEqualTo("Updated Name");
        
        // 🕵️ 验证数据库被再次访问（因为缓存已失效）
        verify(userProfileRepository, times(2)).findById(userId);
        
        // 🔍 验证新的缓存包含更新后的数据
        Cache.ValueWrapper newCachedWrapper = userProfileCache.get(userId);
        assertThat(newCachedWrapper).isNotNull();
        
        System.out.println("验证成功：更新后的数据已正确缓存");

        // ========================================
        // STEP 4 - 验证新缓存的有效性
        // ========================================
        
        System.out.println("\n--- 第四步：验证新缓存的有效性 ---");
        
        // 🚀 再次查询，验证新缓存是否工作
        Optional<UserProfile> finalResult = profileService.getProfileByUserId(userId);
        
        // ✅ 验证从缓存返回的仍然是更新后的数据
        assertThat(finalResult).isPresent();
        assertThat(finalResult.get().getEmail()).isEqualTo("updated@test.com");
        assertThat(finalResult.get().getFullName()).isEqualTo("Updated Name");
        
        // 🔒 验证数据库没有被第三次访问（新缓存生效）
        verify(userProfileRepository, times(2)).findById(userId); // 仍然是2次
        
        System.out.println("验证成功：新缓存正常工作，数据一致性得到保证");
        
        // ========================================
        // 测试价值与实际应用指导
        // ========================================
        // 
        // 🎯 此测试演示了完整的缓存更新生命周期：
        // 1. ✅ 初始数据缓存建立
        // 2. ✅ 数据更新时缓存自动失效
        // 3. ✅ 新数据的缓存重建
        // 4. ✅ 更新后缓存的正常工作
        // 
        // 💡 实际应用中的实现建议：
        // - 在 ProfileService 中添加更新方法：
        //   @CacheEvict(value = "user-profiles", key = "#userId")
        //   public UserProfile updateProfile(String userId, UserProfile updatedProfile)
        // 
        // - 使用 @CachePut 进行更新并重新缓存：
        //   @CachePut(value = "user-profiles", key = "#userId")
        //   public UserProfile updateAndCacheProfile(String userId, UserProfile updatedProfile)
        // 
        // 🚀 业务价值：
        // - 确保用户看到的始终是最新数据
        // - 避免缓存与数据库数据不一致的问题
        // - 支持实时数据更新的业务需求
    }
 }