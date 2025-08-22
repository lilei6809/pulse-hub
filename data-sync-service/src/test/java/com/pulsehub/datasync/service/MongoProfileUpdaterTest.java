package com.pulsehub.datasync.service;

import com.pulsehub.datasync.proto.SyncPriority;
import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Criteria;

import com.mongodb.client.result.UpdateResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MongoDB Profile Updater Test
 * 
 * 测试MongoDB更新器的核心功能:
 * - 版本控制和乐观锁更新
 * - 增量更新逻辑
 * - 立即同步失败处理
 */
@ExtendWith(MockitoExtension.class)
class MongoProfileUpdaterTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private UpdateResult updateResult;

    @InjectMocks
    private MongoProfileUpdater mongoUpdater;

    private UserProfileSyncEvent testEvent;

    @BeforeEach
    void setUp() {
        testEvent = UserProfileSyncEvent.newBuilder()
            .setUserId("test-user-123")
            .setPriority(SyncPriority.IMMEDIATE)
            .setVersion(5L)  // 期望更新到版本5
            .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .build())
            .setStatusUpdate("active")
            .build();
    }

    @Test
    void shouldUpdateProfileSuccessfully() {
        // Given: MongoDB更新成功
        when(updateResult.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("userProfiles")))
            .thenReturn(updateResult);

        // When: 执行更新操作
        boolean result = mongoUpdater.updateProfile(testEvent);

        // Then: 验证更新成功
        assertThat(result).isTrue();
        
        // 验证查询条件 - 版本控制
        verify(mongoTemplate).updateFirst(
            argThat(query -> {
                // 验证查询包含正确的userId和版本条件
                return query.toString().contains("test-user-123") && 
                       query.toString().contains("dataVersion") &&
                       query.toString().contains("4");  // 期望当前版本为4 (version - 1)
            }),
            any(Update.class),
            eq("userProfiles")
        );
        
        // 验证更新操作包含版本递增
        verify(mongoTemplate).updateFirst(
            any(Query.class),
            argThat(update -> {
                String updateStr = update.toString();
                return updateStr.contains("dataVersion") && updateStr.contains("5") &&
                       updateStr.contains("updatedAt") &&
                       updateStr.contains("active");  // status更新
            }),
            eq("userProfiles")
        );
    }

    @Test
    void shouldReturnFalseOnVersionConflict() {
        // Given: 版本冲突，没有文档被更新
        when(updateResult.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("userProfiles")))
            .thenReturn(updateResult);

        // When: 执行更新操作
        boolean result = mongoUpdater.updateProfile(testEvent);

        // Then: 验证更新失败
        assertThat(result).isFalse();
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq("userProfiles"));
    }

    @Test
    void shouldReturnFalseOnException() {
        // Given: MongoDB操作抛出异常
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("userProfiles")))
            .thenThrow(new RuntimeException("MongoDB connection error"));

        // When: 执行更新操作
        boolean result = mongoUpdater.updateProfile(testEvent);

        // Then: 验证异常处理
        assertThat(result).isFalse();
    }

    @Test
    void shouldHandleBatchSyncEvent() {
        // Given: 批量同步事件
        UserProfileSyncEvent batchEvent = testEvent.toBuilder()
            .setPriority(SyncPriority.BATCH)
            .build();

        when(updateResult.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("userProfiles")))
            .thenReturn(updateResult);

        // When: 执行批量同步更新
        boolean result = mongoUpdater.updateProfile(batchEvent);

        // Then: 验证批量同步更新成功
        assertThat(result).isTrue();
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq("userProfiles"));
    }

    @Test
    void shouldHandleTagUpdates() {
        // Given: 包含标签更新的事件
        UserProfileSyncEvent eventWithTags = testEvent.toBuilder()
            .addTagsToAdd("premium")
            .addTagsToAdd("verified")
            .addTagsToRemove("trial")
            .build();

        when(updateResult.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("userProfiles")))
            .thenReturn(updateResult);

        // When: 执行包含标签更新的操作
        boolean result = mongoUpdater.updateProfile(eventWithTags);

        // Then: 验证更新成功且包含标签操作
        assertThat(result).isTrue();
        verify(mongoTemplate).updateFirst(
            any(Query.class),
            argThat(update -> {
                String updateStr = update.toString();
                return updateStr.contains("premium") && 
                       updateStr.contains("verified") &&
                       updateStr.contains("trial");
            }),
            eq("userProfiles")
        );
    }
}