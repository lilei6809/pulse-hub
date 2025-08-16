# Profile Cache Service 架构设计方案

## **服务概览**

### **业务背景**
PulseHub 作为企业级 CDP 平台，用户profile查询是高频场景。当前 `profile-service` 职责过重，缓存策略与业务逻辑耦合，难以独立优化。为提升缓存命中率和系统性能，设计独立的 `profile-cache-service`。

### **核心目标**
1. **智能预加载** - 基于用户行为预测，主动预热热点数据
2. **分层缓存** - 根据用户价值和活跃度，采用差异化缓存策略  
3. **性能优化** - 提升整体系统缓存命中率到90%+
4. **成本控制** - 减少Redis内存使用，优化资源配置
5. **服务解耦** - 让profile-service专注业务逻辑

---

## **服务职责与边界** ✅

### **profile-cache-service 职责**
- **智能预加载策略** - 用户热度评分、预加载决策、缓存预热
- **缓存策略管理** - 分层TTL策略、缓存键命名、过期清理
- **缓存代理服务** - 统一缓存访问接口、缓存穿透保护
- **性能监控** - 缓存命中率、预加载效果、热度分布统计

### **不负责的范围**
- ❌ 用户profile的业务逻辑和CRUD操作
- ❌ 数据同步和一致性保证
- ❌ 权限控制和业务规则验证
- ❌ 外部API的业务功能

### **与其他服务的协作**

```
┌─────────────────┐    查询请求    ┌──────────────────┐
│  profile-api    │ ────────────▶ │ profile-cache    │
│  -service       │               │ -service         │
└─────────────────┘               └──────────────────┘
                                           │
                                           ▼ 缓存未命中
                                  ┌──────────────────┐
                                  │ profile-service  │
                                  │ (数据源)         │
                                  └──────────────────┘
                                           │
                                           ▼ 数据变更通知
                                  ┌──────────────────┐
                                  │ profile-cache    │
                                  │ -service         │
                                  │ (缓存失效)       │
                                  └──────────────────┘
```

---

## **用户热度评分系统** ✅

### **评分维度设计**

#### **1. 基础价值分 (0-100分)**
基于现有 `UserProfileSnapshot.getValueScore()`:
- 注册基础分: 20分
- 活跃度权重: 最高40分
- 页面浏览: 最高20分  
- 设备多样性: 最高10分
- 信息完整度: 最高10分

#### **2. 时间衰减因子 (0-50分)**
```java
// 最近访问时间权重
if (lastActiveAt != null) {
    long hoursInactive = ChronoUnit.HOURS.between(lastActiveAt, Instant.now());
    if (hoursInactive <= 1) score += 30;      // 1小时内
    else if (hoursInactive <= 6) score += 20; // 6小时内  
    else if (hoursInactive <= 24) score += 10; // 24小时内
}
```

#### **3. 访问频率权重 (0-30分)**
```java
// 基于Redis访问统计
String accessKey = "user_access_freq:" + userId;
Long accessCount = redisTemplate.opsForValue().get(accessKey);
if (accessCount != null) {
    // 最近24小时访问次数
    score += Math.min(accessCount * 2, 30);
}
```

#### **4. 业务重要性加权 (0-20分)**
```java
// VIP用户、高价值用户加权
if (isVipUser(userId)) score += 20;
else if (isHighValueUser(userId)) score += 15; 
else if (isPremiumUser(userId)) score += 10;
```

### **热度等级划分**

```java
public enum HeatLevel {
    HOT(120, "热点用户"),      // ≥120分，全量缓存
    WARM(90, "温热用户"),     // 90-119分，关键字段缓存  
    COOL(60, "一般用户"),     // 60-89分，按需缓存
    COLD(0, "冷用户");        // <60分，不预加载
    
    private final int threshold;
    private final String description;
}
```

---

## **智能预加载策略** ✅

### **预加载决策算法**

#### **1. 候选用户筛选**
```java
@Scheduled(fixedRate = 300000) // 每5分钟执行
public void smartPreloadAnalysis() {
    // 1. 获取最近7天有活动的用户
    List<String> candidateUsers = getRecentActiveUsers(7);
    
    // 2. 计算热度评分
    List<UserHeatScore> heatScores = candidateUsers.parallelStream()
        .map(this::calculateHeatScore)
        .filter(score -> score.getHeatLevel() != HeatLevel.COLD)
        .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
        .limit(2000) // 限制预加载用户数量
        .collect(toList());
        
    // 3. 执行分层预加载
    executePreloadStrategy(heatScores);
}
```

#### **2. 分层预加载策略**

```java
private void executePreloadStrategy(List<UserHeatScore> heatScores) {
    // HOT用户: 全量数据预加载
    heatScores.stream()
        .filter(score -> score.getHeatLevel() == HeatLevel.HOT)
        .forEach(score -> preloadFullProfile(score.getUserId(), 3600)); // 1小时TTL
        
    // WARM用户: 关键字段预加载  
    heatScores.stream()
        .filter(score -> score.getHeatLevel() == HeatLevel.WARM)
        .forEach(score -> preloadCriticalFields(score.getUserId(), 1800)); // 30分钟TTL
        
    // COOL用户: 基础信息预加载
    heatScores.stream()
        .filter(score -> score.getHeatLevel() == HeatLevel.COOL)
        .forEach(score -> preloadBasicInfo(score.getUserId(), 900)); // 15分钟TTL
}
```

#### **3. 预加载内容定义**

```java
// 全量数据预加载 (HOT用户)
private void preloadFullProfile(String userId, int ttlSeconds) {
    UserProfileSnapshot profile = profileService.getFullProfile(userId);
    if (profile != null) {
        cacheFullProfile(userId, profile, ttlSeconds);
        log.debug("Preloaded FULL profile for HOT user: {}", userId);
    }
}

// 关键字段预加载 (WARM用户)  
private void preloadCriticalFields(String userId, int ttlSeconds) {
    Map<String, Object> criticalFields = profileService.getCriticalFields(userId);
    if (!criticalFields.isEmpty()) {
        cacheCriticalFields(userId, criticalFields, ttlSeconds);
        log.debug("Preloaded CRITICAL fields for WARM user: {}", userId);
    }
}

// 基础信息预加载 (COOL用户)
private void preloadBasicInfo(String userId, int ttlSeconds) {
    Map<String, Object> basicInfo = profileService.getBasicInfo(userId);
    if (!basicInfo.isEmpty()) {
        cacheBasicInfo(userId, basicInfo, ttlSeconds);
        log.debug("Preloaded BASIC info for COOL user: {}", userId);
    }
}
```

### **访问模式学习**

#### **1. 实时访问统计**
```java
@Component
public class AccessPatternTracker {
    
    // 记录用户访问
    public void recordAccess(String userId, AccessType accessType) {
        String key = "access_pattern:" + userId;
        String hourKey = "access_hour:" + getHourKey();
        
        // 更新访问计数
        redisTemplate.opsForHash().increment(key, "total_count", 1);
        redisTemplate.opsForHash().increment(key, accessType.name(), 1);
        redisTemplate.expire(key, Duration.ofDays(7));
        
        // 小时级别统计
        redisTemplate.opsForZSet().incrementScore(hourKey, userId, 1);
        redisTemplate.expire(hourKey, Duration.ofHours(25));
    }
    
    // 获取用户访问频率
    public AccessFrequency getAccessFrequency(String userId) {
        String key = "access_pattern:" + userId;
        Map<Object, Object> stats = redisTemplate.opsForHash().entries(key);
        return AccessFrequency.from(stats);
    }
}
```

#### **2. 预加载时机优化**
```java
// 基于访问模式调整预加载时机
@Scheduled(cron = "0 0 8,12,18,22 * * *") // 高峰前预加载
public void schedulePreloadByTime() {
    int hour = LocalTime.now().getHour();
    PreloadConfig config = getPreloadConfigByHour(hour);
    
    // 调整预加载用户数量和策略
    executeTimeBasedPreload(config);
}

private PreloadConfig getPreloadConfigByHour(int hour) {
    if (hour >= 8 && hour <= 10) {
        return PreloadConfig.MORNING_PEAK; // 早高峰，激进预加载
    } else if (hour >= 18 && hour <= 22) {
        return PreloadConfig.EVENING_PEAK; // 晚高峰，激进预加载
    } else {
        return PreloadConfig.OFF_PEAK; // 低峰期，保守预加载
    }
}
```

---

## **缓存架构设计** ✅

### **Redis键命名规范**

```java
public class CacheKeyBuilder {
    // 用户profile缓存键
    public static String userProfile(String userId) {
        return "profile:user:" + userId;
    }
    
    // 关键字段缓存键
    public static String criticalFields(String userId) {
        return "profile:critical:" + userId;
    }
    
    // 基础信息缓存键
    public static String basicInfo(String userId) {
        return "profile:basic:" + userId;
    }
    
    // 热度评分缓存键
    public static String heatScore(String userId) {
        return "heat:score:" + userId;
    }
    
    // 访问统计键
    public static String accessStats(String userId) {
        return "access:stats:" + userId;
    }
    
    // 预加载队列键
    public static String preloadQueue(HeatLevel level) {
        return "preload:queue:" + level.name().toLowerCase();
    }
}
```

### **分层缓存策略**

```java
@Service
public class LayeredCacheStrategy {
    
    // Level 1: 热点用户全量缓存
    public Optional<UserProfileSnapshot> getHotUserProfile(String userId) {
        String key = CacheKeyBuilder.userProfile(userId);
        return Optional.ofNullable(
            (UserProfileSnapshot) redisTemplate.opsForValue().get(key)
        );
    }
    
    // Level 2: 活跃用户关键字段缓存
    public Optional<Map<String, Object>> getCriticalFields(String userId) {
        String key = CacheKeyBuilder.criticalFields(userId);
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(key);
        return fields.isEmpty() ? Optional.empty() : 
               Optional.of(convertToStringMap(fields));
    }
    
    // Level 3: 一般用户基础信息缓存
    public Optional<Map<String, Object>> getBasicInfo(String userId) {
        String key = CacheKeyBuilder.basicInfo(userId);
        Map<Object, Object> info = redisTemplate.opsForHash().entries(key);
        return info.isEmpty() ? Optional.empty() : 
               Optional.of(convertToStringMap(info));
    }
    
    // 智能缓存写入
    public void cacheUserProfile(String userId, UserProfileSnapshot profile, HeatLevel heatLevel) {
        switch (heatLevel) {
            case HOT:
                cacheFullProfile(userId, profile, Duration.ofHours(1));
                break;
            case WARM:
                cacheCriticalFields(userId, extractCriticalFields(profile), Duration.ofMinutes(30));
                break;
            case COOL:
                cacheBasicInfo(userId, extractBasicInfo(profile), Duration.ofMinutes(15));
                break;
            default:
                // COLD用户不缓存
                break;
        }
    }
}
```

### **缓存一致性策略**

```java
@Component
public class CacheConsistencyManager {
    
    // 监听profile数据变更事件
    @EventListener
    public void handleProfileUpdateEvent(ProfileUpdateEvent event) {
        String userId = event.getUserId();
        Set<String> updatedFields = event.getUpdatedFields();
        
        // 立即失效相关缓存
        invalidateRelatedCache(userId, updatedFields);
        
        // 如果是热点用户，立即重新加载
        if (isHotUser(userId)) {
            asyncReloadCache(userId);
        }
    }
    
    private void invalidateRelatedCache(String userId, Set<String> updatedFields) {
        // 根据更新字段，选择性失效缓存
        if (containsCriticalFields(updatedFields)) {
            redisTemplate.delete(CacheKeyBuilder.criticalFields(userId));
        }
        
        if (containsBasicFields(updatedFields)) {
            redisTemplate.delete(CacheKeyBuilder.basicInfo(userId));
        }
        
        // 全量缓存总是失效
        redisTemplate.delete(CacheKeyBuilder.userProfile(userId));
    }
    
    // 异步重新加载缓存
    @Async
    public void asyncReloadCache(String userId) {
        try {
            UserProfileSnapshot profile = profileService.getFullProfile(userId);
            if (profile != null) {
                HeatLevel heatLevel = calculateHeatLevel(userId);
                layeredCacheStrategy.cacheUserProfile(userId, profile, heatLevel);
                log.debug("Async reloaded cache for user: {}", userId);
            }
        } catch (Exception e) {
            log.error("Failed to async reload cache for user: {}", userId, e);
        }
    }
}
```

---

## **服务接口设计** ✅

### **对外API接口**

```java
@RestController
@RequestMapping("/cache")
public class ProfileCacheController {
    
    // 获取用户profile (智能路由)
    @GetMapping("/profile/{userId}")
    public ResponseEntity<UserProfileSnapshot> getUserProfile(@PathVariable String userId) {
        Optional<UserProfileSnapshot> profile = profileCacheService.getProfile(userId);
        return profile.map(ResponseEntity::ok)
                     .orElse(ResponseEntity.notFound().build());
    }
    
    // 获取关键字段
    @GetMapping("/profile/{userId}/critical")
    public ResponseEntity<Map<String, Object>> getCriticalFields(@PathVariable String userId) {
        Map<String, Object> fields = profileCacheService.getCriticalFields(userId);
        return ResponseEntity.ok(fields);
    }
    
    // 预热指定用户缓存
    @PostMapping("/preload/{userId}")
    public ResponseEntity<Void> preloadUser(@PathVariable String userId) {
        profileCacheService.preloadUser(userId);
        return ResponseEntity.accepted().build();
    }
    
    // 批量预热
    @PostMapping("/preload/batch")
    public ResponseEntity<Void> preloadUsers(@RequestBody List<String> userIds) {
        profileCacheService.preloadUsers(userIds);
        return ResponseEntity.accepted().build();
    }
    
    // 缓存失效
    @DeleteMapping("/profile/{userId}")
    public ResponseEntity<Void> invalidateCache(@PathVariable String userId) {
        profileCacheService.invalidateUserCache(userId);
        return ResponseEntity.ok().build();
    }
    
    // 获取缓存统计
    @GetMapping("/stats")
    public ResponseEntity<CacheStats> getCacheStats() {
        CacheStats stats = profileCacheService.getCacheStatistics();
        return ResponseEntity.ok(stats);
    }
    
    // 获取用户热度评分
    @GetMapping("/heat/{userId}")
    public ResponseEntity<UserHeatScore> getUserHeatScore(@PathVariable String userId) {
        UserHeatScore score = heatScoreService.calculateHeatScore(userId);
        return ResponseEntity.ok(score);
    }
}
```

### **核心服务接口**

```java
@Service
public class ProfileCacheService {
    
    // 智能获取用户profile
    public Optional<UserProfileSnapshot> getProfile(String userId) {
        // 1. 尝试从缓存获取
        Optional<UserProfileSnapshot> cached = tryGetFromCache(userId);
        if (cached.isPresent()) {
            recordCacheHit(userId);
            return cached;
        }
        
        // 2. 缓存未命中，从数据源获取
        recordCacheMiss(userId);
        Optional<UserProfileSnapshot> profile = profileService.getFullProfile(userId);
        
        // 3. 异步缓存结果
        profile.ifPresent(p -> asyncCacheProfile(userId, p));
        
        return profile;
    }
    
    // 预加载用户缓存
    public void preloadUser(String userId) {
        HeatLevel heatLevel = calculateHeatLevel(userId);
        UserProfileSnapshot profile = profileService.getFullProfile(userId);
        
        if (profile != null) {
            layeredCacheStrategy.cacheUserProfile(userId, profile, heatLevel);
            log.info("Preloaded cache for user: {}, heat level: {}", userId, heatLevel);
        }
    }
    
    // 批量预加载
    @Async
    public void preloadUsers(List<String> userIds) {
        userIds.parallelStream()
               .forEach(this::preloadUser);
    }
    
    // 失效用户缓存
    public void invalidateUserCache(String userId) {
        String[] keys = {
            CacheKeyBuilder.userProfile(userId),
            CacheKeyBuilder.criticalFields(userId),
            CacheKeyBuilder.basicInfo(userId),
            CacheKeyBuilder.heatScore(userId)
        };
        
        redisTemplate.delete(Arrays.asList(keys));
        log.info("Invalidated cache for user: {}", userId);
    }
    
    // 获取缓存统计
    public CacheStats getCacheStatistics() {
        return CacheStats.builder()
            .totalRequests(metricsService.getTotalRequests())
            .cacheHits(metricsService.getCacheHits())
            .cacheMisses(metricsService.getCacheMisses())
            .hitRate(metricsService.getHitRate())
            .preloadedUsers(metricsService.getPreloadedUsers())
            .hotUsers(metricsService.getHotUsersCount())
            .warmUsers(metricsService.getWarmUsersCount())
            .build();
    }
}
```

---

## **技术实现方案** ✅

### **项目结构设计**

```
profile-cache-service/
├── src/main/java/com/pulsehub/profilecache/
│   ├── ProfileCacheServiceApplication.java
│   ├── config/
│   │   ├── RedisConfig.java
│   │   ├── CacheConfig.java
│   │   ├── AsyncConfig.java
│   │   └── SchedulingConfig.java
│   ├── controller/
│   │   └── ProfileCacheController.java
│   ├── service/
│   │   ├── ProfileCacheService.java
│   │   ├── HeatScoreService.java
│   │   ├── PreloadService.java
│   │   ├── AccessPatternTracker.java
│   │   └── CacheMetricsService.java
│   ├── strategy/
│   │   ├── LayeredCacheStrategy.java
│   │   ├── PreloadStrategy.java
│   │   └── CacheConsistencyManager.java
│   ├── domain/
│   │   ├── UserHeatScore.java
│   │   ├── HeatLevel.java
│   │   ├── AccessFrequency.java
│   │   ├── CacheStats.java
│   │   └── PreloadConfig.java
│   ├── client/
│   │   └── ProfileServiceClient.java
│   └── util/
│       ├── CacheKeyBuilder.java
│       └── HeatScoreCalculator.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-docker.yml
│   └── application-local.yml
└── pom.xml
```

### **关键配置文件**

```yaml
# application.yml
server:
  port: 8086
  
spring:
  application:
    name: profile-cache-service
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 2
  
# 缓存配置
cache:
  preload:
    enabled: true
    interval: 300000        # 预加载间隔5分钟
    batch-size: 100         # 批处理大小
    max-users: 2000         # 最大预加载用户数
    
  strategy:
    hot-user-ttl: 3600      # 热点用户TTL 1小时
    warm-user-ttl: 1800     # 温热用户TTL 30分钟  
    cool-user-ttl: 900      # 一般用户TTL 15分钟
    
  heat-score:
    recalculate-interval: 600000  # 重新计算间隔10分钟
    access-weight: 0.3      # 访问频率权重
    time-weight: 0.4        # 时间衰减权重
    value-weight: 0.3       # 基础价值权重

# 外部服务配置
external:
  profile-service:
    url: http://profile-service:8084
    timeout: 5000
    
# 监控配置
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,cache
  metrics:
    tags:
      service: profile-cache-service
      
# 日志配置
logging:
  level:
    com.pulsehub.profilecache: DEBUG
    org.springframework.cache: DEBUG
```

### **Maven依赖配置**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>com.pulsehub</groupId>
        <artifactId>pulse-hub-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    
    <artifactId>profile-cache-service</artifactId>
    <name>Profile Cache Service</name>
    <description>智能用户画像缓存服务</description>
    
    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        
        <!-- 服务发现 -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        
        <!-- Feign客户端 -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        
        <!-- 公共模块 -->
        <dependency>
            <groupId>com.pulsehub</groupId>
            <artifactId>common-config</artifactId>
        </dependency>
        
        <!-- 工具库 -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        
        <!-- 监控 -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        
        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>redis</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

---

## **MVP实现范围** ✅

### **Phase 1: 核心功能 (4周)**

#### **Week 1: 基础架构**
1. ✅ **项目结构搭建**
   - Maven模块创建和依赖配置
   - Spring Boot应用主类和基础配置
   - Docker集成和服务发现配置

2. ✅ **Redis缓存基础**
   - Redis连接和配置
   - 缓存键命名规范
   - 基础缓存CRUD操作

#### **Week 2: 热度评分系统**
3. ✅ **用户热度计算**
   - 基础价值分算法实现
   - 时间衰减因子计算
   - 访问频率统计
   - 热度等级划分

4. ✅ **访问模式追踪**
   - 实时访问统计
   - 访问频率分析
   - 模式学习基础框架

#### **Week 3: 分层缓存策略**
5. ✅ **分层缓存实现**
   - HOT/WARM/COOL用户差异化缓存
   - 智能TTL策略
   - 缓存一致性基础机制

6. ✅ **预加载机制**
   - 候选用户筛选算法
   - 分层预加载策略
   - 定时预加载任务

#### **Week 4: API和集成**
7. ✅ **服务接口**
   - 用户profile查询API
   - 缓存管理API
   - 预加载控制API

8. ✅ **外部集成**
   - Profile Service Feign客户端
   - 服务间调用和降级
   - 基础监控和日志

### **Phase 2: 增强功能 (2周)**

#### **Week 5-6: 优化和监控**
9. **性能优化**
   - 缓存命中率优化
   - 批量操作优化
   - 异步处理优化

10. **监控和告警**
    - 详细指标收集
    - Prometheus集成
    - 告警规则配置

11. **完整测试**
    - 单元测试覆盖
    - 集成测试
    - 性能测试

### **测试和验收标准**

#### **功能测试**
- ✅ 用户热度评分准确性 (误差<5%)
- ✅ 分层缓存策略生效 (HOT/WARM/COOL用户不同TTL)
- ✅ 预加载机制工作正常 (预加载用户比例>80%)
- ✅ 缓存一致性保证 (数据更新后缓存及时失效)

#### **性能指标**
- 🎯 **缓存命中率**: 达到90%+
- 🎯 **响应时间**: P99 < 50ms
- 🎯 **预加载准确率**: 预加载用户在1小时内被访问的比例>70%
- 🎯 **内存使用**: 相比全量缓存减少60%+

#### **可用性指标**  
- 🎯 **服务可用性**: 99.9%+
- 🎯 **降级机制**: Redis故障时自动降级到数据源
- 🎯 **故障恢复**: 服务重启后自动恢复预加载

---

## **部署和运维** ✅

### **Docker配置**

```dockerfile
# Dockerfile
FROM openjdk:21-jdk-slim

WORKDIR /app

COPY target/profile-cache-service-*.jar app.jar

EXPOSE 8086

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### **Docker Compose集成**

```yaml
# docker-compose.yml (新增)
  profile-cache-service:
    build:
      context: ./profile-cache-service
    ports:
      - "8086:8086"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE=http://discovery-service:8761/eureka/
      - SPRING_REDIS_HOST=redis
      - EXTERNAL_PROFILE_SERVICE_URL=http://profile-service:8084
    depends_on:
      - redis
      - discovery-service
      - profile-service
    networks:
      - pulsehub-network
```

### **监控指标**

```java
// 关键监控指标
@Component
public class CacheMetrics {
    
    @EventListener
    public void recordCacheHit(CacheHitEvent event) {
        Metrics.counter("cache.hits", 
            "user_heat_level", event.getHeatLevel().name().toLowerCase(),
            "cache_type", event.getCacheType()
        ).increment();
    }
    
    @EventListener
    public void recordCacheMiss(CacheMissEvent event) {
        Metrics.counter("cache.misses",
            "user_id", event.getUserId()
        ).increment();
    }
    
    @Scheduled(fixedRate = 60000)
    public void recordHeatDistribution() {
        Map<HeatLevel, Long> distribution = heatScoreService.getHeatDistribution();
        distribution.forEach((level, count) -> 
            Metrics.gauge("users.heat_distribution", 
                Tags.of("level", level.name().toLowerCase())
            ).set(count)
        );
    }
}
```

---

## **风险评估与应对** ✅

### **技术风险**

| 风险项 | 影响等级 | 应对策略 |
|--------|----------|----------|
| **Redis内存不足** | 高 | 实施LRU策略，优化TTL配置，添加内存监控告警 |
| **热度评分算法偏差** | 中 | A/B测试验证，可配置权重参数，支持算法热更新 |
| **预加载策略不准确** | 中 | 机器学习优化，用户反馈调整，定期策略评估 |
| **缓存雪崩** | 高 | 错峰过期，熔断机制，优雅降级到数据源 |

### **业务风险**

| 风险项 | 影响等级 | 应对策略 |
|--------|----------|----------|
| **缓存数据不一致** | 高 | 事件驱动缓存失效，版本控制，最终一致性保证 |
| **服务依赖故障** | 中 | 超时控制，熔断降级，本地缓存备份 |
| **预加载影响性能** | 中 | 异步处理，限流控制，可配置开关 |

---

## **总结**

`profile-cache-service` 作为独立的缓存服务，通过智能预加载和分层缓存策略，能够：

### **核心价值**
1. **性能提升** - 缓存命中率提升到90%+，响应时间减少70%
2. **成本优化** - Redis内存使用减少60%，资源利用率提升
3. **架构解耦** - profile-service专注业务逻辑，缓存策略独立演进
4. **智能决策** - 基于用户行为的预测性缓存，而非被动缓存

### **扩展潜力**
- **机器学习增强** - 集成ML模型预测用户访问模式
- **多级缓存** - 支持本地缓存+Redis分布式缓存
- **实时调优** - 基于监控数据自动调整缓存策略
- **跨服务支持** - 为其他业务服务提供智能缓存能力

通过MVP的分阶段实施，能够快速验证核心假设，并为后续的智能化升级奠定基础。