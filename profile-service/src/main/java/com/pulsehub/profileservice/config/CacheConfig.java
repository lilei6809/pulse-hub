package com.pulsehub.profileservice.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.Random;

/**
 * 🏗️ PulseHub CDP 企业级缓存架构配置
 * 
 * 【设计理念】
 * 本配置类实现了基于业务场景的分层缓存策略，旨在为PulseHub CDP平台
 * 的不同业务场景提供最优的缓存性能和数据一致性保证。
 * 
 * 【核心设计原则】
 * 1. 业务驱动：不同业务场景采用不同的缓存策略
 * 2. 性能优先：通过合理的TTL和空值策略优化响应时间
 * 3. 一致性保证：通过事件驱动机制确保缓存与数据库同步
 * 4. 可扩展性：模块化设计便于添加新的缓存场景
 * 5. 可观测性：通过命名规范和监控集成提供运维友好性
 * 
 * 【架构层次】
 * ┌─────────────────┬────────────┬─────────────┬───────────────┐
 * │    缓存层次     │    TTL     │  空值策略   │   适用场景    │
 * ├─────────────────┼────────────┼─────────────┼───────────────┤
 * │ CRM操作缓存     │ 10分钟     │ 不缓存空值  │ 销售/客服/营销│
 * │ Analytics缓存   │ 4小时      │ 缓存空值    │ 报表/BI分析   │
 * │ 用户行为缓存    │ 30分钟     │ 不缓存空值  │ 行为追踪      │
 * │ 系统配置缓存    │ 24小时     │ 缓存所有值  │ 配置管理      │
 * └─────────────────┴────────────┴─────────────┴───────────────┘
 * 
 * 【事件驱动集成】
 * 该配置与Kafka事件监听器配合，实现：
 * - 用户创建事件 → 清除CRM缓存空值 → 新用户立即可见
 * - 用户更新事件 → 清除所有相关缓存 → 确保数据一致性
 * - 批量更新事件 → 智能缓存预热 → 优化查询性能
 * 
 * @author PulseHub Team
 * @version 2.0
 * @since 2024-12-29
 */
@Configuration
public class CacheConfig {

    /**
     * 🎯 默认缓存配置
     * 
     * 【技术说明】
     * 提供系统级的默认缓存配置，作为其他专用缓存配置的基础模板。
     * 这个配置会被所有没有显式配置的缓存使用。
     * 
     * 【配置决策】
     * 1. TTL=15分钟：平衡数据新鲜度和缓存效率的经验值
     * 2. 不缓存null值：防止缓存穿透，确保数据准确性
     * 3. JSON序列化：便于调试和跨语言兼容
     * 
     * 【使用场景】
     * - 临时缓存需求
     * - 开发测试环境
     * - 未分类的业务数据
     * 
     * @return RedisCacheConfiguration 默认缓存配置对象
     */
    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15)) // 1. 设置默认的缓存过期时间为 15 分钟
                .disableCachingNullValues()       // 2. 为了防止偶然错误导致的数据不一致, 不缓存 null 值
                .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())); // 3. 设置值的序列化器为 JSON
    }

    /**
     * 🏗️ 分层缓存管理器定制器
     * 
     * 【架构设计】
     * 这是整个缓存架构的核心配置方法，它定义了PulseHub CDP平台
     * 四个主要的缓存层次，每个层次都针对特定的业务场景优化。
     * 
     * 【业务价值】
     * 1. 提升用户体验：不同场景的差异化性能优化
     * 2. 降低系统负载：合理的TTL设计减少数据库压力
     * 3. 保证数据准确性：精细化的空值处理策略
     * 4. 支持业务增长：可扩展的缓存层次设计
     * 
     * 【监控集成】
     * 每个缓存层都使用不同的key前缀，便于：
     * - Redis监控工具按前缀统计使用情况
     * - 运维团队快速定位问题
     * - 性能分析和容量规划
     * 
     * @return RedisCacheManagerBuilderCustomizer 缓存管理器定制器
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return (builder) -> {
            
            // ═══════════════════════════════════════════════════════════════
            // 🎯 基础用户画像缓存 (向后兼容配置)
            // ═══════════════════════════════════════════════════════════════
            
            /**
             * 【兼容性说明】
             * 保留原有的user-profiles缓存配置，确保现有代码不受影响。
             * 新功能建议使用下面的分层缓存策略。
             */
            builder.withCacheConfiguration("user-profiles",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofHours(1)));

            // ═══════════════════════════════════════════════════════════════
            // 🎯 CRM/CDP 分层缓存策略配置
            // ═══════════════════════════════════════════════════════════════
            
            /**
             * 📋 CRM操作缓存：销售、营销、客服场景专用
             * 
             * 【业务需求分析】
             * - 销售场景：需要立即看到新注册的潜在客户，转化热度最高
             * - 客服场景：客户咨询时需要最新、最准确的信息
             * - 营销场景：实时营销活动需要基于最新的用户状态
             * 
             * 【技术设计决策】
             * 1. TTL=10分钟：短周期确保数据新鲜度，满足实时业务需求
             * 2. 不缓存空值：防止"新用户30分钟不可见"问题
             * 3. 专用前缀：pulsehub:crm:* 便于监控和运维
             * 
             * 【性能指标目标】
             * - 缓存命中率：>85%
             * - 响应时间：<5ms P95
             * - 新用户可见性：<100ms
             * 
             * 【事件驱动集成】
             * 配合ProfileUpdateListener实现：
             * - 用户创建事件 → 立即清除此缓存的空值条目
             * - 用户更新事件 → 清除相关用户的缓存条目
             */
            builder.withCacheConfiguration("crm-user-profiles",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofMinutes(10))  // 短TTL，确保数据新鲜度
                            .disableCachingNullValues()         // 不缓存空值，新用户立即可见
                            .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                            .computePrefixWith(cacheName -> "pulsehub:crm:" + cacheName + ":"));

            /**
             * 📊 数据分析缓存：报表、BI分析场景专用
             * 
             * 【业务需求分析】
             * - BI报表：对数据实时性要求不高，更关注查询性能
             * - 数据分析：大量的聚合查询，需要防止缓存穿透
             * - 管理驾驶舱：定期刷新，允许一定的数据延迟
             * 
             * 【技术设计决策】
             * 1. TTL=4小时：长周期减少数据库负载，适合分析类查询
             * 2. 缓存空值：防止恶意查询和大量无效分析任务穿透
             * 3. 专用前缀：pulsehub:analytics:* 区分业务场景
             * 
             * 【性能指标目标】
             * - 缓存命中率：>95%
             * - 数据库负载减少：>80%
             * - 查询响应时间：<50ms P95
             * 
             * 【缓存穿透防护】
             * 通过缓存空值防止：
             * - 恶意用户枚举攻击
             * - 错误配置的分析任务
             * - 第三方系统的无效查询
             */
            builder.withCacheConfiguration("analytics-user-profiles",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofHours(4))      // 长TTL，减少DB压力
                            // 注意：这里允许缓存null值，防止分析任务被无效查询影响
                            .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                            .computePrefixWith(cacheName -> "pulsehub:analytics:" + cacheName + ":"));

            // ═══════════════════════════════════════════════════════════════
            // 🎯 其他业务场景的缓存配置
            // ═══════════════════════════════════════════════════════════════
            
            /**
             * 🔍 用户行为缓存：行为追踪和实时推荐场景
             * 
             * 【业务价值】
             * - 实时推荐系统的基础数据
             * - 用户行为分析和模式识别
             * - A/B测试和个性化营销
             * 
             * 【技术特点】
             * 1. TTL=30分钟：平衡实时性和性能
             * 2. 不缓存空值：新行为数据需要立即可见
             * 3. 中等缓存周期：适合行为数据的变化频率
             */
            builder.withCacheConfiguration("user-behaviors",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofMinutes(30))   // 中等TTL
                            .disableCachingNullValues()         // 不缓存空值
                            .computePrefixWith(cacheName -> "pulsehub:behavior:" + cacheName + ":"));

            /**
             * ⚙️ 系统配置缓存：配置管理和元数据场景
             * 
             * 【业务价值】
             * - 系统配置参数的高性能访问
             * - 元数据和字典表的缓存
             * - 减少配置服务的访问压力
             * 
             * 【技术特点】
             * 1. TTL=24小时：配置数据变化频率低
             * 2. 缓存所有值：包括null值，减少无效查询
             * 3. 长期缓存：系统配置的变化通常通过配置推送
             */
            builder.withCacheConfiguration("system-configs",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofHours(24))     // 长TTL
                            .computePrefixWith(cacheName -> "pulsehub:config:" + cacheName + ":"));
        };
    }

    /**
     * 🔧 Redis模板配置
     * 
     * 【技术说明】
     * RedisTemplate是Spring Data Redis提供的核心操作类，用于直接操作Redis。
     * 相比于Spring Cache的注解式缓存，RedisTemplate提供了更细粒度的控制。
     * 
     * 【使用场景】
     * 1. 事件驱动缓存失效：通过Kafka事件手动清除缓存
     * 2. 缓存预热：批量加载热点数据到缓存
     * 3. 复杂缓存操作：非标准的缓存读写逻辑
     * 4. 缓存监控：获取缓存统计信息和健康状态
     * 
     * 【序列化策略】
     * 1. Key序列化：StringRedisSerializer
     *    - 优势：Redis中的key可读性好，便于调试
     *    - 用途：缓存key、Hash key的序列化
     * 
     * 2. Value序列化：GenericJackson2JsonRedisSerializer
     *    - 优势：支持复杂对象，跨语言兼容性好
     *    - 用途：缓存值、Hash value的序列化
     *    - 注意：包含类型信息，支持多态序列化
     * 
     * 【性能考虑】
     * 1. 连接池：依赖RedisConnectionFactory的连接池配置
     * 2. 序列化开销：JSON序列化相比二进制略慢，但可读性好
     * 3. 内存占用：JSON格式相比二进制占用空间稍大
     * 
     * @param connectionFactory Redis连接工厂，提供连接池管理
     * @return RedisTemplate<String, Object> 配置好的Redis操作模板
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // 使用String序列化器作为key的序列化器
        // 优势：Redis中的key具有良好的可读性，便于运维和调试
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // 使用JSON序列化器作为value的序列化器
        // 优势：支持复杂对象序列化，保持类型信息，跨语言兼容
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        // 初始化模板配置
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 🌟 改进版缓存配置示例（高级功能）
     * 
     * 【设计目标】
     * 解决缓存使用中的经典问题：
     * 1. 缓存雪崩：大量缓存同时失效导致数据库压力骤增
     * 2. 缓存击穿：热点数据失效时大量请求穿透到数据库
     * 3. 缓存穿透：恶意查询不存在的数据导致数据库负载
     * 
     * 【解决方案】
     * 1. 随机化TTL：在基准TTL基础上增加随机偏移
     * 2. 统计信息：启用缓存指标收集便于监控
     * 3. 前缀规范：统一的命名空间管理
     * 
     * 【使用建议】
     * 当前版本注释掉，在生产环境需要时可以启用。
     * 建议先在测试环境验证随机化TTL对业务的影响。
     */
    /*
    @Bean
    public RedisCacheConfiguration advancedCacheConfiguration() {
        // 基础配置
        Duration baseTtl = Duration.ofMinutes(15);
        // 随机化TTL，防止缓存雪崩（在基础TTL的基础上±20%随机）
        Duration randomizedTtl = Duration.ofMinutes(15 + new Random().nextInt(6) - 3);
        
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(randomizedTtl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                // 启用统计信息，便于监控
                .computePrefixWith(cacheName -> "pulsehub:cache:" + cacheName + ":");
    }

    @Bean 
    public RedisCacheManagerBuilderCustomizer advancedCacheManagerCustomizer() {
        return (builder) -> {
            // 用户画像缓存：长时间缓存+随机化
            Duration userProfileTtl = Duration.ofMinutes(60 + new Random().nextInt(20) - 10);
            builder.withCacheConfiguration("user-profiles-advanced",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(userProfileTtl)
                            .disableCachingNullValues()
                            .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
            );
            
            // 短时效缓存：适用于频繁变化的数据
            Duration shortTtl = Duration.ofMinutes(5 + new Random().nextInt(4) - 2);
            builder.withCacheConfiguration("short-lived-cache",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(shortTtl)
                            .disableCachingNullValues()
            );
        };
    }
    */

    /**
     * 📋 PulseHub CRM/CDP 缓存策略总结
     * 
     * ═══════════════════════════════════════════════════════════════════════════
     * 【业务驱动的缓存设计原则】
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * 1. 🎯 销售场景 (crm-user-profiles)
     *    原则：实时性 > 性能
     *    策略：不缓存空值，短TTL (10分钟)
     *    价值：新客户立即可见，提升转化率
     * 
     * 2. 📊 分析场景 (analytics-user-profiles)  
     *    原则：性能 > 实时性
     *    策略：缓存空值，长TTL (4小时)
     *    价值：减少DB负载，支持复杂分析
     * 
     * 3. 🔍 行为场景 (user-behaviors)
     *    原则：平衡实时性和性能
     *    策略：不缓存空值，中等TTL (30分钟)
     *    价值：支持实时推荐和个性化
     * 
     * 4. ⚙️ 配置场景 (system-configs)
     *    原则：稳定性 > 其他
     *    策略：缓存所有值，长TTL (24小时)
     *    价值：减少配置服务压力
     * 
     * ═══════════════════════════════════════════════════════════════════════════
     * 【缓存层次划分】
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * ┌─────────────────────┬────────────┬─────────────┬─────────────────────┐
     * │      缓存名称       │    TTL     │  空值策略   │      业务场景       │
     * ├─────────────────────┼────────────┼─────────────┼─────────────────────┤
     * │ crm-user-profiles   │ 10分钟     │ 不缓存空值  │ 销售/营销/客服      │
     * │ analytics-user-..   │ 4小时      │ 缓存空值    │ 报表/BI分析/管理    │
     * │ user-behaviors      │ 30分钟     │ 不缓存空值  │ 行为追踪/推荐/AB测试│
     * │ system-configs      │ 24小时     │ 缓存所有值  │ 配置管理/元数据     │
     * └─────────────────────┴────────────┴─────────────┴─────────────────────┘
     * 
     * ═══════════════════════════════════════════════════════════════════════════
     * 【事件驱动集成机制】
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * 1. 🔄 用户创建事件流程
     *    Kafka Event → ProfileUpdateListener → Cache.evict(userId) 
     *    → 下次查询重新加载 → 新用户立即可见
     * 
     * 2. 🔄 用户更新事件流程  
     *    Kafka Event → ProfileUpdateListener → Cache.evictAll(userId)
     *    → 所有相关缓存清除 → 确保数据一致性
     * 
     * 3. 🔄 批量更新事件流程
     *    Kafka Event → ProfileUpdateListener → CachePrewarmer.warmup()
     *    → 智能预热热点数据 → 优化查询性能
     * 
     * ═══════════════════════════════════════════════════════════════════════════
     * 【监控和运维支持】
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * 1. 📊 缓存前缀规范
     *    - pulsehub:crm:*       → CRM相关缓存
     *    - pulsehub:analytics:* → 分析相关缓存  
     *    - pulsehub:behavior:*  → 行为相关缓存
     *    - pulsehub:config:*    → 配置相关缓存
     * 
     * 2. 📈 监控指标
     *    - 缓存命中率：按业务场景统计
     *    - 响应时间：P95/P99延迟监控
     *    - 内存使用：按前缀统计占用情况
     *    - 失效频率：事件驱动失效的统计
     * 
     * 3. 🔧 运维工具
     *    - Redis CLI：使用前缀快速定位问题
     *    - 缓存预热：支持手动和自动预热
     *    - 故障恢复：缓存重建和数据修复
     * 
     * ═══════════════════════════════════════════════════════════════════════════
     * 【扩展性和未来规划】
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * 1. 🚀 功能扩展
     *    - 支持更多业务场景的缓存配置
     *    - 支持A/B测试不同的缓存策略  
     *    - 支持动态调整缓存参数
     *    - 支持多数据中心缓存同步
     * 
     * 2. 🎯 性能优化
     *    - 引入二级缓存（本地缓存+Redis）
     *    - 实现缓存压缩减少内存占用
     *    - 支持缓存分片和负载均衡
     *    - 智能缓存预热和淘汰策略
     * 
     * 3. 🛡️ 可靠性增强
     *    - 缓存数据备份和恢复
     *    - 缓存一致性自动检查
     *    - 缓存故障时的优雅降级
     *    - 分布式缓存锁和并发控制
     * 
     * ═══════════════════════════════════════════════════════════════════════════
     * 【最佳实践建议】
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * 1. 💡 开发建议
     *    - 根据业务场景选择合适的缓存层
     *    - 使用@Cacheable注解时指定正确的缓存名称
     *    - 重要操作后主动刷新相关缓存
     *    - 避免缓存大对象导致内存问题
     * 
     * 2. 🔍 监控建议  
     *    - 定期检查缓存命中率和响应时间
     *    - 监控Redis内存使用和慢查询
     *    - 设置合理的告警阈值
     *    - 定期分析缓存使用模式
     * 
     * 3. 🛠️ 运维建议
     *    - 定期清理过期和无用的缓存数据
     *    - 在业务低峰期进行缓存预热
     *    - 建立缓存故障应急预案
     *    - 定期备份重要的缓存配置
     * 
     * ═══════════════════════════════════════════════════════════════════════════
     */
}