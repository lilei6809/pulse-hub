package com.pulsehub.profileservice.repository;

import com.pulsehub.profileservice.entity.StaticUserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 静态用户画像数据访问层
 * 
 * 【设计原则】
 * - 专注于静态用户数据的持久化操作
 * - 提供高效的查询方法
 * - 支持复杂的业务查询需求
 * - 优化数据库访问性能
 */
@Repository
public interface StaticUserProfileRepository extends JpaRepository<StaticUserProfile, String> {

    /**
     * 查找新用户（按注册时间排序）
     * 
     * @param limit 限制数量
     * @return 新用户列表
     */
    @Query("SELECT p FROM StaticUserProfile p " +
           "WHERE p.isDeleted = false " +
           "ORDER BY p.registrationDate DESC")
    List<StaticUserProfile> findNewUsers(@Param("limit") int limit);

    /**
     * 根据来源渠道查找用户
     * 
     * @param sourceChannel 来源渠道
     * @return 用户列表
     */
    List<StaticUserProfile> findBySourceChannelAndIsDeletedFalse(String sourceChannel);

    /**
     * 根据性别查找用户
     * 
     * @param gender 性别
     * @return 用户列表
     */
    List<StaticUserProfile> findByGenderAndIsDeletedFalse(StaticUserProfile.Gender gender);

    /**
     * 根据年龄段查找用户
     * 
     * @param ageGroup 年龄段
     * @return 用户列表
     */
    List<StaticUserProfile> findByAgeGroupAndIsDeletedFalse(StaticUserProfile.AgeGroup ageGroup);

    /**
     * 根据城市查找用户
     * 
     * @param city 城市
     * @return 用户列表
     */
    List<StaticUserProfile> findByCityAndIsDeletedFalse(String city);

    /**
     * 查找注册时间在指定范围内的用户
     * 
     * @param startDate 开始时间
     * @param endDate 结束时间
     * @return 用户列表
     */
    @Query("SELECT p FROM StaticUserProfile p " +
           "WHERE p.registrationDate BETWEEN :startDate AND :endDate " +
           "AND p.isDeleted = false " +
           "ORDER BY p.registrationDate DESC")
    List<StaticUserProfile> findByRegistrationDateBetween(
        @Param("startDate") Instant startDate, 
        @Param("endDate") Instant endDate
    );

    /**
     * 统计各来源渠道的用户数量
     * 
     * @return 渠道统计结果
     */
    @Query("SELECT p.sourceChannel, COUNT(p) FROM StaticUserProfile p " +
           "WHERE p.isDeleted = false " +
           "GROUP BY p.sourceChannel " +
           "ORDER BY COUNT(p) DESC")
    List<Object[]> countBySourceChannel();

    /**
     * 统计各性别的用户数量
     * 
     * @return 性别统计结果
     */
    @Query("SELECT p.gender, COUNT(p) FROM StaticUserProfile p " +
           "WHERE p.isDeleted = false " +
           "GROUP BY p.gender")
    List<Object[]> countByGender();

    /**
     * 根据邮箱查找用户
     * 
     * @param email 邮箱
     * @return 用户画像
     */
    Optional<StaticUserProfile> findByEmail(String email);

    /**
     * 根据手机号查找用户
     * 
     * @param phoneNumber 手机号
     * @return 用户画像
     */
    Optional<StaticUserProfile> findByPhoneNumber(String phoneNumber);

    /**
     * 查找注册时间晚于指定时间的用户
     * 
     * @param registrationDate 注册时间
     * @return 用户列表
     */
    List<StaticUserProfile> findByRegistrationDateAfter(Instant registrationDate);

    /**
     * 根据来源渠道查找用户（简化方法名）
     * 
     * @param sourceChannel 来源渠道
     * @return 用户列表
     */
    default List<StaticUserProfile> findBySourceChannel(String sourceChannel) {
        return findBySourceChannelAndIsDeletedFalse(sourceChannel);
    }

    /**
     * 根据城市查找用户（简化方法名）
     * 
     * @param city 城市
     * @return 用户列表
     */
    default List<StaticUserProfile> findByCity(String city) {
        return findByCityAndIsDeletedFalse(city);
    }

    /**
     * 查找信息完整的用户画像
     * 基于必要字段是否完整进行判断
     * 
     * @return 信息完整的用户列表
     */
    @Query("SELECT p FROM StaticUserProfile p " +
           "WHERE p.realName IS NOT NULL " +
           "AND p.email IS NOT NULL " +
           "AND p.phoneNumber IS NOT NULL " +
           "AND p.city IS NOT NULL " +
           "AND p.gender IS NOT NULL " +
           "AND p.ageGroup IS NOT NULL " +
           "AND p.sourceChannel IS NOT NULL")
    List<StaticUserProfile> findCompleteProfiles();

    /**
     * 检查邮箱是否已存在
     * 
     * @param email 邮箱
     * @return 是否存在
     */
    boolean existsByEmail(String email);

    /**
     * 检查手机号是否已存在
     * 
     * @param phoneNumber 手机号
     * @return 是否存在
     */
    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * 检查邮箱是否已存在（排除已删除的）
     * 
     * @param email 邮箱
     * @return 是否存在
     */
    boolean existsByEmailAndIsDeletedFalse(String email);

    /**
     * 检查手机号是否已存在（排除已删除的）
     * 
     * @param phoneNumber 手机号
     * @return 是否存在
     */
    boolean existsByPhoneNumberAndIsDeletedFalse(String phoneNumber);

    /**
     * 查找已软删除的用户画像
     * 
     * @return 已删除的用户列表
     */
    List<StaticUserProfile> findByIsDeletedTrue();

    /**
     * 查找未删除的用户画像（活跃用户）
     * 
     * @return 活跃用户列表
     */
    List<StaticUserProfile> findByIsDeletedFalse();
}