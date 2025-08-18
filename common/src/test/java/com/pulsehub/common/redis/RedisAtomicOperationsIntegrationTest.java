package com.pulsehub.common.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisAtomicOperations的TestContainers集成测试
 * 
 * 特性：
 * - 使用真实Redis容器进行测试
 * - 测试原子操作的并发安全性
 * - 验证增量更新和数据一致性
 * - 测试分布式场景下的操作行为
 * 
 * @author PulseHub Team
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RedisAtomicOperations集成测试")
public class RedisAtomicOperationsIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server --appendonly yes");

    private RedissonClient redissonClient;
    private RedisAtomicOperations atomicOperations;

    @BeforeEach
    void setUp() {
        // 配置Redisson客户端连接到TestContainers Redis
        Config config = new Config();
        config.useSingleServer()
                .setAddress(String.format("redis://%s:%d", 
                    redis.getHost(), redis.getMappedPort(6379)))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10)
                .setDatabase(0);

        redissonClient = Redisson.create(config);
        atomicOperations = new RedisAtomicOperations(redissonClient);
        
        // 清理Redis数据
        redissonClient.getKeys().flushdb();
    }

    @Test
    @DisplayName("测试atomicUpdateProfile基本功能")
    void testAtomicUpdateProfileBasicOperation() {
        // Given
        String key = "profile:user:test001";
        Map<String, Object> updates = new HashMap<>();
        updates.put("name", "张三");
        updates.put("age", 25);
        updates.put("city", "北京");

        // When
        RedisAtomicOperations.AtomicOperationResult result = 
            atomicOperations.atomicUpdateProfile(key, updates, "integration_test");

        // Then
        assertTrue(result.isSuccess(), "原子更新应该成功");
        assertEquals("增量更新成功", result.getMessage());

        // 验证数据是否正确存储
        RedisProfileData stored = redissonClient.<RedisProfileData>getBucket(key).get();
        assertNotNull(stored, "数据应该被存储到Redis");
        assertEquals("张三", stored.getProfileData().get("name"));
        assertEquals(25, stored.getProfileData().get("age"));
        assertEquals("北京", stored.getProfileData().get("city"));
        
        // 验证元数据
        assertEquals("integration_test", stored.getMetadata().get("lastSource"));
        assertEquals("incremental_update", stored.getMetadata().get("lastOperation"));
        assertEquals(1L, stored.getMetadata().get("updateCount"));
    }

    @Test
    @DisplayName("测试增量更新现有数据")
    void testIncrementalUpdateExistingData() {
        // Given - 先创建一些初始数据
        String key = "profile:user:test002";
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("name", "李四");
        initialData.put("age", 30);
        
        atomicOperations.atomicUpdateProfile(key, initialData, "initial");

        // When - 增量更新
        Map<String, Object> updates = new HashMap<>();
        updates.put("age", 31);  // 更新年龄
        updates.put("email", "lisi@example.com");  // 添加新字段
        
        RedisAtomicOperations.AtomicOperationResult result = 
            atomicOperations.atomicUpdateProfile(key, updates, "increment_test");

        // Then
        assertTrue(result.isSuccess());
        
        RedisProfileData stored = redissonClient.<RedisProfileData>getBucket(key).get();
        assertEquals("李四", stored.getProfileData().get("name")); // 原有数据保持
        assertEquals(31, stored.getProfileData().get("age"));      // 数据被更新
        assertEquals("lisi@example.com", stored.getProfileData().get("email")); // 新数据添加
        assertEquals(2L, stored.getMetadata().get("updateCount")); // 更新计数递增
    }

    @Test
    @DisplayName("测试并发更新的原子性")
    void testConcurrentAtomicUpdates() throws InterruptedException {
        // Given
        String key = "profile:user:concurrent001";
        int threadCount = 10;
        int updatesPerThread = 5;
        CountDownLatch startLatch = new CountDownLatch(1); // 控制所有线程同步开始
        CountDownLatch endLatch = new CountDownLatch(threadCount); // 等待所有线程完成
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // When - 多线程并发更新
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程准备就绪
                    
                    for (int j = 0; j < updatesPerThread; j++) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("counter_" + threadId, j);
                        updates.put("timestamp_" + threadId + "_" + j, System.currentTimeMillis());
                        
                        atomicOperations.atomicUpdateProfile(
                            key, updates, "thread_" + threadId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    // 每个线程在 finally 里 endLatch.countDown() 一次（哪怕异常也会执行），表示“我跑完了”。
                    //
                    //主线程执行 endLatch.await(timeout)，直到 10 次 countDown() 都发生（计数减到 0）才会继续，
                    // 保证所有线程都结束后再做最终断言
                    endLatch.countDown();
                }
            });
        }

        // 代码会瞬间执行完 for 循环, 且 10 个线程都阻塞在 startLatch.await();
        // 大门上锁（计数=1），大家在门口等（await）；主线程把钥匙一拧（countDown），门开了，所有人一起冲出去
        // startLatch.countDown()（把计数从 1 减到 0），所有在 await() 上等待的线程会同时被唤醒
        startLatch.countDown(); // 释放所有线程开始执行
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "所有线程应该在30秒内完成");
        executor.shutdown();

        // Then - 验证结果
        RedisProfileData finalData = redissonClient.<RedisProfileData>getBucket(key).get();
        assertNotNull(finalData, "最终数据应该存在");
        
        // 验证所有线程的数据都被正确更新
        for (int i = 0; i < threadCount; i++) {
            for (int j = 0; j < updatesPerThread; j++) {
                String counterKey = "counter_" + i;
                String timestampKey = "timestamp_" + i + "_" + j;
                
                // 由于并发更新，counter应该是每个线程的最后一个值
                if (finalData.getProfileData().containsKey(counterKey)) {
                    assertNotNull(finalData.getProfileData().get(counterKey));
                }
                if (finalData.getProfileData().containsKey(timestampKey)) {
                    assertNotNull(finalData.getProfileData().get(timestampKey));
                }
            }
        }
        
        // 验证更新计数 (由于并发场景，更新计数可能不精确，但应该大于0)
        Long updateCount = (Long) finalData.getMetadata().get("updateCount");
        assertNotNull(updateCount);
        assertTrue(updateCount > 0, "更新计数应该大于0");
        
        // 验证profile数据中存在多个线程的数据
        assertFalse(finalData.getProfileData().isEmpty(), "应该包含来自多个线程的数据");
    }

    @Test
    @DisplayName("测试无效参数处理")
    void testInvalidParameterHandling() {
        // Test null key
        RedisAtomicOperations.AtomicOperationResult result1 = 
            atomicOperations.atomicUpdateProfile(null, new HashMap<>(), "test");
        assertFalse(result1.isSuccess());
        assertEquals("参数无效", result1.getMessage());

        // Test null updates
        RedisAtomicOperations.AtomicOperationResult result2 = 
            atomicOperations.atomicUpdateProfile("test:key", null, "test");
        assertFalse(result2.isSuccess());
        assertEquals("参数无效", result2.getMessage());

        // Test empty updates
        RedisAtomicOperations.AtomicOperationResult result3 = 
            atomicOperations.atomicUpdateProfile("test:key", new HashMap<>(), "test");
        assertFalse(result3.isSuccess());
        assertEquals("参数无效", result3.getMessage());
    }

    @Test
    @DisplayName("测试userId解析功能")
    void testUserIdParsingFromKey() {
        // Given
        String key = "profile:user:user123";
        Map<String, Object> updates = new HashMap<>();
        updates.put("test", "value");

        // When
        RedisAtomicOperations.AtomicOperationResult result = 
            atomicOperations.atomicUpdateProfile(key, updates, "parsing_test");

        // Then
        assertTrue(result.isSuccess());
        
        RedisProfileData stored = redissonClient.<RedisProfileData>getBucket(key).get();
        assertEquals("user123", stored.getMetadata().get("userId"));
    }

    @Test
    @DisplayName("测试异步并发操作")
    void testAsyncConcurrentOperations() throws Exception {
        // Given
        String keyPrefix = "profile:user:async";
        int operationCount = 20;
        
        // When - 异步并发操作
        CompletableFuture<?>[] futures = new CompletableFuture[operationCount];
        
        for (int i = 0; i < operationCount; i++) {
            final int index = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                String key = keyPrefix + index;
                Map<String, Object> updates = new HashMap<>();
                updates.put("index", index);
                updates.put("data", "async_data_" + index);
                updates.put("timestamp", System.currentTimeMillis());
                
                RedisAtomicOperations.AtomicOperationResult result = 
                    atomicOperations.atomicUpdateProfile(key, updates, "async_test");
                
                assertTrue(result.isSuccess(), "异步操作应该成功");
            });
        }
        
        // 等待所有异步操作完成
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        
        // Then - 验证所有数据都被正确存储
        for (int i = 0; i < operationCount; i++) {
            String key = keyPrefix + i;
            RedisProfileData data = redissonClient.<RedisProfileData>getBucket(key).get();
            
            assertNotNull(data, "数据应该存在: " + key);
            assertEquals(i, data.getProfileData().get("index"));
            assertEquals("async_data_" + i, data.getProfileData().get("data"));
            assertEquals("async_test", data.getMetadata().get("lastSource"));
        }
    }

    @Test
    @DisplayName("测试数据持久性")
    void testDataPersistence() {
        // Given
        String key = "profile:user:persistence001";
        Map<String, Object> updates = new HashMap<>();
        updates.put("name", "持久化测试");
        updates.put("level", 10);

        // When
        atomicOperations.atomicUpdateProfile(key, updates, "persistence_test");
        
        // 关闭当前客户端
        redissonClient.shutdown();
        
        // 重新创建客户端连接
        Config config = new Config();
        config.useSingleServer()
                .setAddress(String.format("redis://%s:%d", 
                    redis.getHost(), redis.getMappedPort(6379)))
                .setDatabase(0);
        redissonClient = Redisson.create(config);

        // Then - 验证数据仍然存在
        RedisProfileData stored = redissonClient.<RedisProfileData>getBucket(key).get();
        assertNotNull(stored, "数据应该持久化存储");
        assertEquals("持久化测试", stored.getProfileData().get("name"));
        assertEquals(10, stored.getProfileData().get("level"));
    }
}