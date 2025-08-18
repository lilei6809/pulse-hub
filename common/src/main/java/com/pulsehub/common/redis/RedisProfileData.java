package com.pulsehub.common.redis;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis用户Profile数据模型
 * 
 * 核心功能：
 * 2. 时间戳追踪 - 记录创建和最后更新时间
 * 3. 数据完整性 - 内置数据验证和一致性检查
 * 4. 高性能更新 - 线程安全的数据操作
 * 
 * 数据结构：
 * - profileData: 实际的用户profile数据(Map结构，支持动态字段)
 * - createdAt: 数据创建时间戳
 * - lastUpdated: 最后更新时间戳
 * - metadata: 元数据信息(如数据来源、更新原因等)
 * 
 * 使用场景：
 * - 用户profile的缓存存储
 * - 分布式环境下的数据同步
 * - Redis内部数据一致性保证
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Slf4j
public class RedisProfileData {

    /**
     * 用户profile的实际数据
     * 使用ConcurrentHashMap保证线程安全
     */
    @Builder.Default
    private Map<String, Object> profileData = new ConcurrentHashMap<>();


    /**
     * 数据创建时间戳
     */
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * 最后更新时间戳
     */
    @Builder.Default
    private Instant lastUpdated = Instant.now();

    /**
     * 仅用于 mongodb 乐观锁
     */
    @Builder.Default
    private Long version = 1L;

    /**
     * 元数据信息
     * 存储额外的上下文信息，如更新来源、操作类型等
     */
    @Builder.Default
    private Map<String, Object> metadata = new ConcurrentHashMap<>();

    /**
     * 数据校验和(可选)
     * 用于数据完整性验证
     */
    private String checksum;

    /**
     * 创建新的RedisProfileData实例
     * 
     * @param userId 用户ID
     * @param initialData 初始数据
     * @return 新的RedisProfileData实例
     */
    public static RedisProfileData create(String userId, Map<String, Object> initialData) {
        Map<String, Object> profileData = initialData != null ? 
            new ConcurrentHashMap<>(initialData) : new ConcurrentHashMap<>();
        
        Map<String, Object> metadata = new ConcurrentHashMap<>();
        metadata.put("userId", userId);
        metadata.put("source", "create");
        metadata.put("operation", "initial");
        
        return RedisProfileData.builder()
            .profileData(profileData)
            .createdAt(Instant.now())
            .lastUpdated(Instant.now())
            .metadata(metadata)
            .build();
    }

    /**
     * 创建空的RedisProfileData实例
     * 
     * @param userId 用户ID
     * @return 空的RedisProfileData实例
     */
    public static RedisProfileData createEmpty(String userId) {
        return create(userId, new HashMap<>());
    }

    /**
     * 简单更新数据
     * 直接更新数据并递增版本号
     * 
     * @param updates 要更新的数据
     * @param source 更新来源标识
     * @return 更新结果对象
     */
    public UpdateResult update(Map<String, Object> updates, String source) {
        if (updates == null || updates.isEmpty()) {
            log.warn("更新参数无效: updates={}", updates);
            return UpdateResult.failed("参数无效");
        }

        try {
            // 执行更新
            this.profileData.putAll(updates);
            this.lastUpdated = Instant.now();
            
            // 更新元数据
            this.metadata.put("lastSource", source);
            this.metadata.put("lastOperation", "update");
            this.metadata.put("updateCount", getUpdateCount() + 1);
            
            log.debug("成功更新profile数据: 更新字段数={}, 来源={}", 
                updates.size(), source);
            
            return UpdateResult.success("更新成功");
            
        } catch (Exception e) {
            log.error("更新profile数据失败: source={}", source, e);
            return UpdateResult.failed("更新异常: " + e.getMessage());
        }
    }

    /**
     * 强制更新数据（忽略版本检查）
     * 危险操作，仅在特殊情况下使用
     * 
     * @param updates 要更新的数据
     * @param source 更新来源
     * @param reason 强制更新的原因
     * @return 更新结果
     */
    public UpdateResult forceUpdate(Map<String, Object> updates, String source, String reason) {
        if (updates == null || updates.isEmpty()) {
            return UpdateResult.failed("更新数据为空");
        }

        try {
            this.profileData.putAll(updates);
            this.lastUpdated = Instant.now();
            
            // 记录强制更新的元数据
            this.metadata.put("forceUpdate", true);
            this.metadata.put("forceReason", reason);
            this.metadata.put("lastSource", source);
            this.metadata.put("lastOperation", "force_update");
            
            log.warn("执行强制更新: 原因: {}, 来源: {}", reason, source);
            
            return UpdateResult.success("强制更新成功");
            
        } catch (Exception e) {
            log.error("强制更新失败: source={}, reason={}", source, reason, e);
            return UpdateResult.failed("强制更新异常: " + e.getMessage());
        }
    }

    /**
     * 增量更新特定字段
     * 只更新指定的字段，不影响其他数据
     * 
     * @param fieldName 字段名
     * @param newValue 新值
     * @param source 更新来源
     * @return 更新结果
     */
    public UpdateResult updateField(String fieldName, Object newValue, String source) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(fieldName, newValue);
        return update(updates, source);
    }

    /**
     * 安全地获取profile数据的副本
     * 防止外部修改影响内部数据
     * 
     * @return profile数据的深拷贝
     */
    @JsonIgnore
    public Map<String, Object> getProfileDataCopy() {
        return new HashMap<>(this.profileData);
    }

    /**
     * 获取特定字段的值
     * 
     * @param fieldName 字段名
     * @param defaultValue 默认值
     * @param <T> 值类型
     * @return 字段值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getFieldValue(String fieldName, T defaultValue) {
        Object value = this.profileData.get(fieldName);
        if (value == null) {
            return defaultValue;
        }
        
        try {
            return (T) value;
        } catch (ClassCastException e) {
            log.warn("字段类型转换失败: field={}, expectedType={}, actualType={}", 
                fieldName, defaultValue.getClass().getSimpleName(), value.getClass().getSimpleName());
            return defaultValue;
        }
    }

    /**
     * 检查数据是否在指定时间内被更新过
     * 
     * @param withinSeconds 时间范围(秒)
     * @return 是否在指定时间内更新过
     */
    @JsonIgnore
    public boolean isUpdatedWithin(long withinSeconds) {
        return lastUpdated.isAfter(Instant.now().minusSeconds(withinSeconds));
    }

    /**
     * 获取数据年龄（秒）
     * 
     * @return 从最后更新到现在的秒数
     */
    @JsonIgnore
    public long getAgeInSeconds() {
        return Instant.now().getEpochSecond() - lastUpdated.getEpochSecond();
    }

    /**
     * 获取更新次数
     * 
     * @return 更新次数
     */
    @JsonIgnore
    public long getUpdateCount() {
        Object count = metadata.get("updateCount");
        return count instanceof Number ? ((Number) count).longValue() : 0L;
    }

    /**
     * 验证数据完整性
     * 
     * @return 验证结果
     */
    public ValidationResult validate() {
        try {
            // 基本字段检查
            if (createdAt == null || lastUpdated == null) {
                return ValidationResult.invalid("时间戳缺失");
            }
            
            if (lastUpdated.isBefore(createdAt)) {
                return ValidationResult.invalid("最后更新时间早于创建时间");
            }
            
            if (profileData == null) {
                return ValidationResult.invalid("profileData不能为null");
            }
            
            // 元数据检查
            if (metadata == null) {
                return ValidationResult.invalid("metadata不能为null");
            }
            
            return ValidationResult.valid("数据验证通过");
            
        } catch (Exception e) {
            log.error("数据验证异常", e);
            return ValidationResult.invalid("验证异常: " + e.getMessage());
        }
    }

    /**
     * 创建当前数据的快照
     * 
     * @return 数据快照
     */
    public RedisProfileData createSnapshot() {
        return RedisProfileData.builder()
            .profileData(new HashMap<>(this.profileData))
            .createdAt(this.createdAt)
            .lastUpdated(this.lastUpdated)
            .metadata(new HashMap<>(this.metadata))
            .checksum(this.checksum)
            .build();
    }

    /**
     * 比较两个RedisProfileData实例
     * 
     * @param other 另一个实例
     * @return 比较结果
     */
    public ComparisonResult compareTo(RedisProfileData other) {
        if (other == null) {
            return ComparisonResult.of(false, "比较对象为null");
        }
        
        if (!this.profileData.equals(other.profileData)) {
            return ComparisonResult.of(false, "profile数据不同");
        }
        
        return ComparisonResult.of(true, "数据一致");
    }

    @Override
    public String toString() {
        return String.format("RedisProfileData{fieldsCount=%d, age=%ds, lastUpdated=%s}", 
            profileData.size(), getAgeInSeconds(), lastUpdated);
    }

    /**
     * 更新结果封装类
     */
    public static class UpdateResult {
        private final boolean success;
        private final String message;
        private final String type;

        private UpdateResult(boolean success, String message, String type) {
            this.success = success;
            this.message = message;
            this.type = type;
        }

        public static UpdateResult success(String message) {
            return new UpdateResult(true, message, "SUCCESS");
        }

        public static UpdateResult failed(String message) {
            return new UpdateResult(false, message, "FAILED");
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getType() { return type; }

        @Override
        public String toString() {
            return String.format("UpdateResult{success=%s, type=%s, message='%s'}", 
                success, type, message);
        }
    }

    /**
     * 验证结果封装类
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult valid(String message) {
            return new ValidationResult(true, message);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() { return valid; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return String.format("ValidationResult{valid=%s, message='%s'}", valid, message);
        }
    }

    /**
     * 比较结果封装类
     */
    public static class ComparisonResult {
        private final boolean equals;
        private final String message;

        private ComparisonResult(boolean equals, String message) {
            this.equals = equals;
            this.message = message;
        }

        public static ComparisonResult of(boolean equals, String message) {
            return new ComparisonResult(equals, message);
        }

        public boolean isEquals() { return equals; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return String.format("ComparisonResult{equals=%s, message='%s'}", equals, message);
        }
    }
}