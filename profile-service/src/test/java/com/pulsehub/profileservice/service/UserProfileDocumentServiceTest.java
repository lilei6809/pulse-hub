package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.document.UserProfileDocument;
import com.pulsehub.profileservice.domain.DeviceClass;
import com.pulsehub.profileservice.domain.UserProfileSnapshot;
import com.pulsehub.profileservice.domain.entity.StaticUserProfile.Gender;
import com.pulsehub.profileservice.domain.entity.StaticUserProfile.AgeGroup;
import com.pulsehub.profileservice.repository.UserProfileDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserProfileDocumentService 单元测试
 * 
 * 【测试目标】
 * 验证 MongoDB 用户画像文档服务的各项功能：
 * 1. 核心 CRUD 操作
 * 2. 批量操作
 * 3. 业务查询方法
 * 4. 标签管理
 * 5. 动态字段管理
 * 6. 统计分析方法
 * 
 * 【测试策略】
 * - 使用 Mockito 模拟依赖服务
 * - 验证方法调用和参数传递
 * - 测试异常场景处理
 * - 验证业务逻辑正确性
 */
@ExtendWith(MockitoExtension.class)
class UserProfileDocumentServiceTest {

    @Mock
    private UserProfileDocumentRepository documentRepository;
    
    @Mock
    private ProfileAggregationService profileAggregationService;
    
    private UserProfileDocumentService userProfileDocumentService;
    
    private static final String TEST_USER_ID = "test-user-123";
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String DELETED_STATUS = "DELETED";
    
    @BeforeEach
    void setUp() {
        userProfileDocumentService = new UserProfileDocumentService(
            documentRepository, 
            profileAggregationService
        );
    }

    // ===================================================================
    // 核心 CRUD 操作测试
    // ===================================================================

    @Test
    void createOrUpdateDocument_WithValidSnapshot_ShouldCreateNewDocument() {
        // GIVEN - 准备用户画像快照数据
        UserProfileSnapshot snapshot = UserProfileSnapshot.builder()
            .userId(TEST_USER_ID)
            .gender(Gender.MALE)
            .realName("张三")
            .email("zhangsan@example.com")
            .city("北京")
            .ageGroup(AgeGroup.YOUNG_ADULT)
            .pageViewCount(100L)
            .deviceClassification(DeviceClass.MOBILE)
            .recentDeviceTypes(Set.of(DeviceClass.MOBILE))
            .registrationDate(Instant.now().minus(Duration.ofDays(30)))
            .lastActiveAt(Instant.now().minus(Duration.ofHours(1)))
            .build();
        
        // Mock 聚合服务返回快照
        when(profileAggregationService.getRealtimeProfile(TEST_USER_ID))
            .thenReturn(Optional.of(snapshot));
        
        // Mock 仓储层没有找到现有文档（创建新文档）
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.empty());
        
        // Mock 保存操作
        UserProfileDocument savedDocument = UserProfileDocument.builder()
            .userId(TEST_USER_ID)
            .status(ACTIVE_STATUS)
            .dataVersion("1.0")
            .build();
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(savedDocument);

        // WHEN - 执行创建或更新操作
        Optional<UserProfileDocument> result = userProfileDocumentService.createOrUpdateDocument(TEST_USER_ID);

        // THEN - 验证结果
        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(TEST_USER_ID);
        
        // 验证调用了聚合服务
        verify(profileAggregationService).getRealtimeProfile(TEST_USER_ID);
        
        // 验证查找现有文档
        verify(documentRepository).findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS);
        
        // 验证保存文档
        ArgumentCaptor<UserProfileDocument> documentCaptor = ArgumentCaptor.forClass(UserProfileDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        UserProfileDocument capturedDocument = documentCaptor.getValue();
        assertThat(capturedDocument.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(capturedDocument.getStatus()).isEqualTo(ACTIVE_STATUS);
    }

    @Test
    void createOrUpdateDocument_WithExistingDocument_ShouldUpdateDocument() {
        // GIVEN - 准备快照数据
        UserProfileSnapshot snapshot = UserProfileSnapshot.builder()
            .userId(TEST_USER_ID)
            .pageViewCount(200L)
            .build();
        
        when(profileAggregationService.getRealtimeProfile(TEST_USER_ID))
            .thenReturn(Optional.of(snapshot));
        
        // Mock 已存在的文档
        UserProfileDocument existingDocument = UserProfileDocument.builder()
            .userId(TEST_USER_ID)
            .status(ACTIVE_STATUS)
            .dataVersion("1.0")
            .createdAt(Instant.now().minus(Duration.ofDays(1)))
            .build();
        
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.of(existingDocument));
        
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(existingDocument);

        // WHEN
        Optional<UserProfileDocument> result = userProfileDocumentService.createOrUpdateDocument(TEST_USER_ID);

        // THEN
        assertThat(result).isPresent();
        verify(documentRepository).save(existingDocument);
        
        // 验证文档被更新了时间戳
        assertThat(existingDocument.getUpdatedAt()).isNotNull();
    }

    @Test
    void createOrUpdateDocument_WithNoSnapshot_ShouldReturnEmpty() {
        // GIVEN - 聚合服务返回空
        when(profileAggregationService.getRealtimeProfile(TEST_USER_ID))
            .thenReturn(Optional.empty());

        // WHEN
        Optional<UserProfileDocument> result = userProfileDocumentService.createOrUpdateDocument(TEST_USER_ID);

        // THEN
        assertThat(result).isEmpty();
        
        // 验证没有进行保存操作
        verify(documentRepository, never()).save(any(UserProfileDocument.class));
    }

    @Test
    void createOrUpdateDocument_WithException_ShouldReturnEmpty() {
        // GIVEN - 模拟异常
        when(profileAggregationService.getRealtimeProfile(TEST_USER_ID))
            .thenThrow(new RuntimeException("Database error"));

        // WHEN
        Optional<UserProfileDocument> result = userProfileDocumentService.createOrUpdateDocument(TEST_USER_ID);

        // THEN
        assertThat(result).isEmpty();
        verify(documentRepository, never()).save(any(UserProfileDocument.class));
    }

    @Test
    void getActiveDocument_WithExistingDocument_ShouldReturnDocument() {
        // GIVEN
        UserProfileDocument document = UserProfileDocument.builder()
            .userId(TEST_USER_ID)
            .status(ACTIVE_STATUS)
            .build();
        
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.of(document));

        // WHEN
        Optional<UserProfileDocument> result = userProfileDocumentService.getActiveDocument(TEST_USER_ID);

        // THEN
        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(TEST_USER_ID);
        verify(documentRepository).findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS);
    }

    @Test
    void getActiveDocument_WithNonExistingDocument_ShouldReturnEmpty() {
        // GIVEN
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.empty());

        // WHEN
        Optional<UserProfileDocument> result = userProfileDocumentService.getActiveDocument(TEST_USER_ID);

        // THEN
        assertThat(result).isEmpty();
        verify(documentRepository).findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS);
    }

    @Test
    void deleteDocument_WithExistingDocument_ShouldMarkAsDeleted() {
        // GIVEN
        UserProfileDocument document = UserProfileDocument.builder()
            .userId(TEST_USER_ID)
            .status(ACTIVE_STATUS)
            .build();
        
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.of(document));
        
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(document);

        // WHEN
        boolean result = userProfileDocumentService.deleteDocument(TEST_USER_ID);

        // THEN
        assertThat(result).isTrue();
        
        // 验证文档被标记为删除
        ArgumentCaptor<UserProfileDocument> documentCaptor = ArgumentCaptor.forClass(UserProfileDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        UserProfileDocument savedDocument = documentCaptor.getValue();
        assertThat(savedDocument.getStatus()).isEqualTo(DELETED_STATUS);
    }

    @Test
    void deleteDocument_WithNonExistingDocument_ShouldReturnFalse() {
        // GIVEN
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.empty());

        // WHEN
        boolean result = userProfileDocumentService.deleteDocument(TEST_USER_ID);

        // THEN
        assertThat(result).isFalse();
        verify(documentRepository, never()).save(any(UserProfileDocument.class));
    }

    @Test
    void deleteDocument_WithException_ShouldReturnFalse() {
        // GIVEN - 模拟异常
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenThrow(new RuntimeException("Database error"));

        // WHEN
        boolean result = userProfileDocumentService.deleteDocument(TEST_USER_ID);

        // THEN
        assertThat(result).isFalse();
    }

    // ===================================================================
    // 批量操作测试
    // ===================================================================

    @Test
    void batchCreateOrUpdate_WithMultipleUsers_ShouldProcessAll() {
        // GIVEN
        List<String> userIds = Arrays.asList("user1", "user2", "user3");
        
        // Mock 每个用户的处理结果
        UserProfileSnapshot snapshot1 = UserProfileSnapshot.builder().userId("user1").build();
        UserProfileSnapshot snapshot2 = UserProfileSnapshot.builder().userId("user2").build();
        UserProfileSnapshot snapshot3 = UserProfileSnapshot.builder().userId("user3").build();
        
        when(profileAggregationService.getRealtimeProfile("user1"))
            .thenReturn(Optional.of(snapshot1));
        when(profileAggregationService.getRealtimeProfile("user2"))
            .thenReturn(Optional.of(snapshot2));
        when(profileAggregationService.getRealtimeProfile("user3"))
            .thenReturn(Optional.of(snapshot3));
        
        when(documentRepository.findByUserIdAndStatus("user1", ACTIVE_STATUS))
            .thenReturn(Optional.empty());
        when(documentRepository.findByUserIdAndStatus("user2", ACTIVE_STATUS))
            .thenReturn(Optional.empty());
        when(documentRepository.findByUserIdAndStatus("user3", ACTIVE_STATUS))
            .thenReturn(Optional.empty());
        
        UserProfileDocument doc1 = UserProfileDocument.builder().userId("user1").build();
        UserProfileDocument doc2 = UserProfileDocument.builder().userId("user2").build();
        UserProfileDocument doc3 = UserProfileDocument.builder().userId("user3").build();
        
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(doc1, doc2, doc3);

        // WHEN
        List<String> result = userProfileDocumentService.batchCreateOrUpdate(userIds);

        // THEN
        assertThat(result).hasSize(3);
        assertThat(result).containsExactlyInAnyOrder("user1", "user2", "user3");
        
        // 验证每个用户都被处理了
        for (String userId : userIds) {
            verify(profileAggregationService).getRealtimeProfile(userId);
        }
    }

    @Test
    void batchCreateOrUpdate_WithSomeFailures_ShouldReturnSuccessfulOnes() {
        // GIVEN
        List<String> userIds = Arrays.asList("user1", "user2", "user3");
        
        // user1 成功
        UserProfileSnapshot snapshot1 = UserProfileSnapshot.builder().userId("user1").build();
        when(profileAggregationService.getRealtimeProfile("user1"))
            .thenReturn(Optional.of(snapshot1));
        when(documentRepository.findByUserIdAndStatus("user1", ACTIVE_STATUS))
            .thenReturn(Optional.empty());
        UserProfileDocument doc1 = UserProfileDocument.builder().userId("user1").build();
        
        // user2 失败（无快照）
        when(profileAggregationService.getRealtimeProfile("user2"))
            .thenReturn(Optional.empty());
        
        // user3 成功
        UserProfileSnapshot snapshot3 = UserProfileSnapshot.builder().userId("user3").build();
        when(profileAggregationService.getRealtimeProfile("user3"))
            .thenReturn(Optional.of(snapshot3));
        when(documentRepository.findByUserIdAndStatus("user3", ACTIVE_STATUS))
            .thenReturn(Optional.empty());
        UserProfileDocument doc3 = UserProfileDocument.builder().userId("user3").build();
        
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(doc1, doc3);

        // WHEN
        List<String> result = userProfileDocumentService.batchCreateOrUpdate(userIds);

        // THEN
        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder("user1", "user3");
    }

    // ===================================================================
    // 业务查询方法测试
    // ===================================================================

    @Test
    void findUsersByCity_ShouldCallRepositoryWithCorrectParams() {
        // GIVEN
        String city = "北京";
        List<UserProfileDocument> expectedResults = Arrays.asList(
            UserProfileDocument.builder().userId("user1").build(),
            UserProfileDocument.builder().userId("user2").build()
        );
        
        when(documentRepository.findByCityAndStatus(city, ACTIVE_STATUS))
            .thenReturn(expectedResults);

        // WHEN
        List<UserProfileDocument> result = userProfileDocumentService.findUsersByCity(city);

        // THEN
        assertThat(result).hasSize(2);
        assertThat(result).isEqualTo(expectedResults);
        verify(documentRepository).findByCityAndStatus(city, ACTIVE_STATUS);
    }

    @Test
    void findUsersByDeviceClass_ShouldCallRepositoryWithCorrectParams() {
        // GIVEN
        String deviceClass = "MOBILE";
        List<UserProfileDocument> expectedResults = Arrays.asList(
            UserProfileDocument.builder().userId("user1").build()
        );
        
        when(documentRepository.findByDeviceClassificationAndStatus(deviceClass, ACTIVE_STATUS))
            .thenReturn(expectedResults);

        // WHEN
        List<UserProfileDocument> result = userProfileDocumentService.findUsersByDeviceClass(deviceClass);

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).isEqualTo(expectedResults);
        verify(documentRepository).findByDeviceClassificationAndStatus(deviceClass, ACTIVE_STATUS);
    }

    @Test
    void findHighValueActiveUsers_ShouldCallRepositoryWithCorrectParams() {
        // GIVEN
        Integer minValueScore = 80;
        Instant since = Instant.now().minus(Duration.ofDays(7));
        List<String> expectedActivityLevels = Arrays.asList("VERY_ACTIVE", "ACTIVE");
        
        List<UserProfileDocument> expectedResults = Arrays.asList(
            UserProfileDocument.builder().userId("user1").build()
        );
        
        when(documentRepository.findHighValueActiveUsers(
            minValueScore, expectedActivityLevels, since, ACTIVE_STATUS))
            .thenReturn(expectedResults);

        // WHEN
        List<UserProfileDocument> result = userProfileDocumentService
            .findHighValueActiveUsers(minValueScore, since);

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).isEqualTo(expectedResults);
        verify(documentRepository).findHighValueActiveUsers(
            minValueScore, expectedActivityLevels, since, ACTIVE_STATUS);
    }

    @Test
    void findUsersByInterest_ShouldCallRepositoryWithCorrectParams() {
        // GIVEN
        String interest = "technology";
        List<UserProfileDocument> expectedResults = Arrays.asList(
            UserProfileDocument.builder().userId("user1").build()
        );
        
        when(documentRepository.findByInterestAndStatus(interest, ACTIVE_STATUS))
            .thenReturn(expectedResults);

        // WHEN
        List<UserProfileDocument> result = userProfileDocumentService.findUsersByInterest(interest);

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).isEqualTo(expectedResults);
        verify(documentRepository).findByInterestAndStatus(interest, ACTIVE_STATUS);
    }

    @Test
    void findUsersByIndustry_ShouldCallRepositoryWithCorrectParams() {
        // GIVEN
        String industry = "Technology";
        List<UserProfileDocument> expectedResults = Arrays.asList(
            UserProfileDocument.builder().userId("user1").build()
        );
        
        when(documentRepository.findByIndustryAndStatus(industry, ACTIVE_STATUS))
            .thenReturn(expectedResults);

        // WHEN
        List<UserProfileDocument> result = userProfileDocumentService.findUsersByIndustry(industry);

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).isEqualTo(expectedResults);
        verify(documentRepository).findByIndustryAndStatus(industry, ACTIVE_STATUS);
    }

    // ===================================================================
    // 标签管理测试
    // ===================================================================

    @Test
    void addTagToUser_WithExistingUser_ShouldAddTagSuccessfully() {
        // GIVEN
        String tag = "vip_user";
        UserProfileDocument document = UserProfileDocument.builder()
            .userId(TEST_USER_ID)
            .status(ACTIVE_STATUS)
            .tags(new HashSet<>())
            .build();
        
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.of(document));
        
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(document);

        // WHEN
        boolean result = userProfileDocumentService.addTagToUser(TEST_USER_ID, tag);

        // THEN
        assertThat(result).isTrue();
        
        // 验证标签被添加
        ArgumentCaptor<UserProfileDocument> documentCaptor = ArgumentCaptor.forClass(UserProfileDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        UserProfileDocument savedDocument = documentCaptor.getValue();
        assertThat(savedDocument.getTags()).contains(tag);
    }

    @Test
    void addTagToUser_WithNonExistingUser_ShouldReturnFalse() {
        // GIVEN
        String tag = "vip_user";
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.empty());

        // WHEN
        boolean result = userProfileDocumentService.addTagToUser(TEST_USER_ID, tag);

        // THEN
        assertThat(result).isFalse();
        verify(documentRepository, never()).save(any(UserProfileDocument.class));
    }

    @Test
    void batchAddTag_WithMultipleUsers_ShouldAddTagToAll() {
        // GIVEN
        List<String> userIds = Arrays.asList("user1", "user2");
        String tag = "batch_tag";
        
        UserProfileDocument doc1 = UserProfileDocument.builder()
            .userId("user1")
            .status(ACTIVE_STATUS)
            .tags(new HashSet<>())
            .build();
        
        UserProfileDocument doc2 = UserProfileDocument.builder()
            .userId("user2")
            .status(ACTIVE_STATUS)
            .tags(new HashSet<>())
            .build();
        
        when(documentRepository.findByUserIdAndStatus("user1", ACTIVE_STATUS))
            .thenReturn(Optional.of(doc1));
        when(documentRepository.findByUserIdAndStatus("user2", ACTIVE_STATUS))
            .thenReturn(Optional.of(doc2));
        
        when(documentRepository.save(doc1)).thenReturn(doc1);
        when(documentRepository.save(doc2)).thenReturn(doc2);

        // WHEN
        long result = userProfileDocumentService.batchAddTag(userIds, tag);

        // THEN
        assertThat(result).isEqualTo(2);
        verify(documentRepository).save(doc1);
        verify(documentRepository).save(doc2);
    }

    @Test
    void findUsersByTag_ShouldCallRepositoryWithCorrectParams() {
        // GIVEN
        String tag = "vip_user";
        List<UserProfileDocument> expectedResults = Arrays.asList(
            UserProfileDocument.builder().userId("user1").build()
        );
        
        when(documentRepository.findByTagsContainingAndStatus(tag, ACTIVE_STATUS))
            .thenReturn(expectedResults);

        // WHEN
        List<UserProfileDocument> result = userProfileDocumentService.findUsersByTag(tag);

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).isEqualTo(expectedResults);
        verify(documentRepository).findByTagsContainingAndStatus(tag, ACTIVE_STATUS);
    }

    // ===================================================================
    // 动态字段管理测试
    // ===================================================================

    @Test
    void setSocialMediaData_WithExistingUser_ShouldSetDataSuccessfully() {
        // GIVEN
        String platform = "instagram";
        Map<String, Object> data = Map.of("followers", 1000, "posts", 50);
        
        UserProfileDocument document = UserProfileDocument.builder()
            .userId(TEST_USER_ID)
            .status(ACTIVE_STATUS)
            .socialMedia(new HashMap<>())
            .build();
        
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.of(document));
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(document);

        // WHEN
        boolean result = userProfileDocumentService.setSocialMediaData(TEST_USER_ID, platform, data);

        // THEN
        assertThat(result).isTrue();
        
        ArgumentCaptor<UserProfileDocument> documentCaptor = ArgumentCaptor.forClass(UserProfileDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        UserProfileDocument savedDocument = documentCaptor.getValue();
        assertThat(savedDocument.getSocialMedia()).containsKey(platform);
    }

    @Test
    void setSocialMediaData_WithNonExistingUser_ShouldReturnFalse() {
        // GIVEN
        String platform = "instagram";
        Map<String, Object> data = Map.of("followers", 1000);
        
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.empty());

        // WHEN
        boolean result = userProfileDocumentService.setSocialMediaData(TEST_USER_ID, platform, data);

        // THEN
        assertThat(result).isFalse();
        verify(documentRepository, never()).save(any(UserProfileDocument.class));
    }

    @Test
    void setComputedMetric_WithExistingUser_ShouldSetMetricSuccessfully() {
        // GIVEN
        String metricName = "engagement_score";
        Object value = 85.5;
        
        UserProfileDocument document = UserProfileDocument.builder()
            .userId(TEST_USER_ID)
            .status(ACTIVE_STATUS)
            .computedMetrics(new HashMap<>())
            .build();
        
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.of(document));
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(document);

        // WHEN
        boolean result = userProfileDocumentService.setComputedMetric(TEST_USER_ID, metricName, value);

        // THEN
        assertThat(result).isTrue();
        
        ArgumentCaptor<UserProfileDocument> documentCaptor = ArgumentCaptor.forClass(UserProfileDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        UserProfileDocument savedDocument = documentCaptor.getValue();
        assertThat(savedDocument.getComputedMetrics()).containsKey(metricName);
        assertThat(savedDocument.getComputedMetrics().get(metricName)).isEqualTo(value);
    }

    @Test
    void setExtendedProperty_WithExistingUser_ShouldSetPropertySuccessfully() {
        // GIVEN
        String key = "custom_field";
        Object value = "custom_value";
        
        UserProfileDocument document = UserProfileDocument.builder()
            .userId(TEST_USER_ID)
            .status(ACTIVE_STATUS)
            .extendedProperties(new HashMap<>())
            .build();
        
        when(documentRepository.findByUserIdAndStatus(TEST_USER_ID, ACTIVE_STATUS))
            .thenReturn(Optional.of(document));
        when(documentRepository.save(any(UserProfileDocument.class)))
            .thenReturn(document);

        // WHEN
        boolean result = userProfileDocumentService.setExtendedProperty(TEST_USER_ID, key, value);

        // THEN
        assertThat(result).isTrue();
        
        ArgumentCaptor<UserProfileDocument> documentCaptor = ArgumentCaptor.forClass(UserProfileDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        UserProfileDocument savedDocument = documentCaptor.getValue();
        assertThat(savedDocument.getExtendedProperties()).containsKey(key);
        assertThat(savedDocument.getExtendedProperties().get(key)).isEqualTo(value);
    }

    // ===================================================================
    // 统计分析方法测试
    // ===================================================================

    @Test
    void getActiveUserCount_ShouldReturnCorrectCount() {
        // GIVEN
        when(documentRepository.countByStatus(ACTIVE_STATUS)).thenReturn(150L);

        // WHEN
        long result = userProfileDocumentService.getActiveUserCount();

        // THEN
        assertThat(result).isEqualTo(150L);
        verify(documentRepository).countByStatus(ACTIVE_STATUS);
    }

    @Test
    void getActiveUserCountSince_ShouldReturnCorrectCount() {
        // GIVEN
        Instant since = Instant.now().minus(Duration.ofDays(7));
        when(documentRepository.countByLastActiveAtAfterAndStatus(since, ACTIVE_STATUS))
            .thenReturn(80L);

        // WHEN
        long result = userProfileDocumentService.getActiveUserCountSince(since);

        // THEN
        assertThat(result).isEqualTo(80L);
        verify(documentRepository).countByLastActiveAtAfterAndStatus(since, ACTIVE_STATUS);
    }

    @Test
    void getUserCountByCity_ShouldReturnCorrectCount() {
        // GIVEN
        String city = "北京";
        when(documentRepository.countByCityAndStatus(city, ACTIVE_STATUS)).thenReturn(25L);

        // WHEN
        long result = userProfileDocumentService.getUserCountByCity(city);

        // THEN
        assertThat(result).isEqualTo(25L);
        verify(documentRepository).countByCityAndStatus(city, ACTIVE_STATUS);
    }

    @Test
    void getServiceStatus_WithHealthyService_ShouldReturnHealthyStatus() {
        // GIVEN
        when(documentRepository.count()).thenReturn(1000L);
        when(documentRepository.countByStatus(ACTIVE_STATUS)).thenReturn(800L);
        
        List<UserProfileDocument> recentDocuments = Arrays.asList(
            UserProfileDocument.builder().userId("user1").build(),
            UserProfileDocument.builder().userId("user2").build()
        );
        
        when(documentRepository.findByLastActiveAtAfterAndStatusOrderByLastActiveAtDesc(
            any(Instant.class), eq(ACTIVE_STATUS)))
            .thenReturn(recentDocuments);

        // WHEN
        UserProfileDocumentService.ServiceStatus status = userProfileDocumentService.getServiceStatus();

        // THEN
        assertThat(status.isOverallHealthy()).isTrue();
        assertThat(status.isMongoDbHealthy()).isTrue();
        assertThat(status.getTotalDocumentCount()).isEqualTo(1000L);
        assertThat(status.getActiveDocumentCount()).isEqualTo(800L);
        assertThat(status.getRecentActivityCount()).isEqualTo(2L);
        assertThat(status.getErrors()).isEmpty();
    }

    @Test
    void getServiceStatus_WithException_ShouldReturnUnhealthyStatus() {
        // GIVEN - 模拟数据库异常
        when(documentRepository.count()).thenThrow(new RuntimeException("MongoDB connection failed"));

        // WHEN
        UserProfileDocumentService.ServiceStatus status = userProfileDocumentService.getServiceStatus();

        // THEN
        assertThat(status.isOverallHealthy()).isFalse();
        assertThat(status.isMongoDbHealthy()).isFalse();
        assertThat(status.getErrors()).hasSize(1);
        assertThat(status.getErrors().get(0)).contains("MongoDB 连接异常");
    }
}