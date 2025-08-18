package com.pulsehub.common.redis;

import lombok.Getter;

/**
 * Profile操作结果封装类
 * 
 * 统一封装Redis Profile操作的结果信息：
 * 1. 操作成功/失败状态
 * 2. 详细的结果消息
 * 3. 操作类型标识
 * 4. 返回的Profile数据
 * 
 * 支持的操作类型：
 * - SUCCESS: 操作成功
 * - FAILED: 操作失败
 * - LOCK_FAILED: 获取锁失败
 * - NOT_FOUND: 数据不存在
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Getter
public class ProfileOperationResult {

    /**
     * -- GETTER --
     *  操作是否成功
     *
     */
    private final boolean success;
    /**
     * -- GETTER --
     *  获取结果消息
     *
     */
    private final String message;
    /**
     * -- GETTER --
     *  获取操作类型
     *
     */
    private final String type;
    /**
     * -- GETTER --
     *  获取Profile数据
     *
     */
    private final RedisProfileData data;

    private ProfileOperationResult(boolean success, String message, String type, RedisProfileData data) {
        this.success = success;
        this.message = message;
        this.type = type;
        this.data = data;
    }

    /**
     * 创建成功结果
     * 
     * @param message 成功消息
     * @param data 返回的Profile数据
     * @return 成功结果
     */
    public static ProfileOperationResult success(String message, RedisProfileData data) {
        return new ProfileOperationResult(true, message, "SUCCESS", data);
    }

    /**
     * 创建失败结果
     * 
     * @param message 失败消息
     * @return 失败结果
     */
    public static ProfileOperationResult failed(String message) {
        return new ProfileOperationResult(false, message, "FAILED", null);
    }

    /**
     * 创建锁获取失败结果
     * 
     * @param message 失败消息
     * @return 锁失败结果
     */
    public static ProfileOperationResult lockFailed(String message) {
        return new ProfileOperationResult(false, message, "LOCK_FAILED", null);
    }

    /**
     * 创建数据未找到结果
     * 
     * @param message 未找到消息
     * @return 未找到结果
     */
    public static ProfileOperationResult notFound(String message) {
        return new ProfileOperationResult(false, message, "NOT_FOUND", null);
    }

    /**
     * 是否为锁获取失败
     * 
     * @return 是否为锁失败
     */
    public boolean isLockFailed() {
        return "LOCK_FAILED".equals(type);
    }

    /**
     * 是否为数据未找到
     * 
     * @return 是否为未找到
     */
    public boolean isNotFound() {
        return "NOT_FOUND".equals(type);
    }

    /**
     * 是否为普通失败（非锁失败或未找到）
     * 
     * @return 是否为普通失败
     */
    public boolean isGeneralFailure() {
        return "FAILED".equals(type);
    }

    @Override
    public String toString() {
        return String.format("ProfileOperationResult{success=%s, type=%s, message='%s', hasData=%s}", 
            success, type, message, data != null);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ProfileOperationResult that = (ProfileOperationResult) obj;
        return success == that.success &&
               type.equals(that.type) &&
               message.equals(that.message);
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(success);
        result = 31 * result + type.hashCode();
        result = 31 * result + message.hashCode();
        return result;
    }
}