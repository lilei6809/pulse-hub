package com.pulsehub.datasync.proto;

import com.google.protobuf.Any;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced UserProfileSyncEvent 测试类
 * 验证新的双维度分类：优先级 + 更新类型
 */
@DisplayName("Enhanced UserProfileSyncEvent 测试")
class EnhancedUserProfileSyncEventTest {

    @Test
    @DisplayName("测试同步优先级枚举")
    void testSyncPriority() {
        UserProfileSyncEvent immediateEvent = UserProfileSyncEvent.newBuilder()
            .setUserId("user123")
            .setPriority(SyncPriority.IMMEDIATE)
            .setSyncType(SyncType.INCREMENTAL_SYNC)
            .setVersion(1)
            .build();

        UserProfileSyncEvent batchEvent = UserProfileSyncEvent.newBuilder()
            .setUserId("user456")
            .setPriority(SyncPriority.BATCH)
            .setSyncType(SyncType.INCREMENTAL_SYNC)
            .setVersion(2)
            .build();

        assertEquals(SyncPriority.IMMEDIATE, immediateEvent.getPriority());
        assertEquals(SyncPriority.BATCH, batchEvent.getPriority());
    }

    @Test
    @DisplayName("测试同步类型枚举")
    void testSyncType() {
        UserProfileSyncEvent fullSyncEvent = UserProfileSyncEvent.newBuilder()
            .setUserId("user123")
            .setPriority(SyncPriority.IMMEDIATE)
            .setSyncType(SyncType.FULL_SYNC)
            .setVersion(1)
            .build();

        UserProfileSyncEvent incrementalSyncEvent = UserProfileSyncEvent.newBuilder()
            .setUserId("user456")
            .setPriority(SyncPriority.BATCH)
            .setSyncType(SyncType.INCREMENTAL_SYNC)
            .setVersion(2)
            .build();

        assertEquals(SyncType.FULL_SYNC, fullSyncEvent.getSyncType());
        assertEquals(SyncType.INCREMENTAL_SYNC, incrementalSyncEvent.getSyncType());
    }

    @Test
    @DisplayName("测试版本控制字段")
    void testVersionControl() {
        UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
            .setUserId("version_test_user")
            .setPriority(SyncPriority.BATCH)
            .setSyncType(SyncType.INCREMENTAL_SYNC)
            .setVersion(42)
            .build();

        assertEquals(42, event.getVersion());
        assertEquals("version_test_user", event.getUserId());
    }

    @Test
    @DisplayName("测试标签操作字段")
    void testTagOperations() {
        UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
            .setUserId("tag_test_user")
            .setPriority(SyncPriority.BATCH)
            .setSyncType(SyncType.INCREMENTAL_SYNC)
            .setVersion(1)
            .addTagsToAdd("premium")
            .addTagsToAdd("mobile-user")
            .addTagsToRemove("trial")
            .addTagsToRemove("new-user")
            .build();

        assertEquals(2, event.getTagsToAddCount());
        assertEquals(2, event.getTagsToRemoveCount());
        assertTrue(event.getTagsToAddList().contains("premium"));
        assertTrue(event.getTagsToRemoveList().contains("trial"));
    }

    @Test
    @DisplayName("测试不同数据分区的更新字段")
    void testDifferentDataPartitions() {
        UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
            .setUserId("partition_test_user")
            .setPriority(SyncPriority.BATCH)
            .setSyncType(SyncType.INCREMENTAL_SYNC)
            .setVersion(1)
            // 各种分区更新
            .putStaticProfileUpdates("age", Any.pack(Int64Value.of(25)))
            .putDynamicProfileUpdates("session_count", Any.pack(Int64Value.of(10)))
            .putComputedMetricsUpdates("engagement_score", Any.pack(Int64Value.of(85)))
            .putBehavioralDataUpdates("last_action", Any.pack(StringValue.of("click")))
            .putSocialMediaUpdates("twitter_followers", Any.pack(Int64Value.of(1200)))
            .putExtendedPropertiesUpdates("custom_field", Any.pack(StringValue.of("custom_value")))
            .build();

        // 验证各个分区都有数据
        assertEquals(1, event.getStaticProfileUpdatesCount());
        assertEquals(1, event.getDynamicProfileUpdatesCount());
        assertEquals(1, event.getComputedMetricsUpdatesCount());
        assertEquals(1, event.getBehavioralDataUpdatesCount());
        assertEquals(1, event.getSocialMediaUpdatesCount());
        assertEquals(1, event.getExtendedPropertiesUpdatesCount());

        // 验证Map中包含对应的key
        assertTrue(event.getStaticProfileUpdatesMap().containsKey("age"));
        assertTrue(event.getDynamicProfileUpdatesMap().containsKey("session_count"));
        assertTrue(event.getComputedMetricsUpdatesMap().containsKey("engagement_score"));
        assertTrue(event.getBehavioralDataUpdatesMap().containsKey("last_action"));
        assertTrue(event.getSocialMediaUpdatesMap().containsKey("twitter_followers"));
        assertTrue(event.getExtendedPropertiesUpdatesMap().containsKey("custom_field"));
    }

    @Test
    @DisplayName("测试业务场景组合")
    void testBusinessScenarios() {
        Timestamp now = Timestamp.newBuilder()
            .setSeconds(Instant.now().getEpochSecond())
            .build();

        // 场景1: 用户状态变更 (关键业务，立即增量同步)
        UserProfileSyncEvent statusChange = UserProfileSyncEvent.newBuilder()
            .setUserId("user_status_change")
            .setPriority(SyncPriority.IMMEDIATE)
            .setSyncType(SyncType.INCREMENTAL_SYNC)
            .setVersion(10)
            .setTimestamp(now)
            .setStatusUpdate("SUSPENDED")
            .putStaticProfileUpdates("account_status", Any.pack(StringValue.of("SUSPENDED")))
            .build();

        assertEquals(SyncPriority.IMMEDIATE, statusChange.getPriority());
        assertEquals(SyncType.INCREMENTAL_SYNC, statusChange.getSyncType());
        assertEquals("SUSPENDED", statusChange.getStatusUpdate());

        // 场景2: 页面浏览行为 (普通数据，批量增量同步)
        UserProfileSyncEvent pageView = UserProfileSyncEvent.newBuilder()
            .setUserId("user_page_view")
            .setPriority(SyncPriority.BATCH)
            .setSyncType(SyncType.INCREMENTAL_SYNC)
            .setVersion(25)
            .setTimestamp(now)
            .putDynamicProfileUpdates("page_view_count", Any.pack(Int64Value.of(156)))
            .putBehavioralDataUpdates("last_page_url", Any.pack(StringValue.of("/product/123")))
            .build();

        assertEquals(SyncPriority.BATCH, pageView.getPriority());
        assertEquals(SyncType.INCREMENTAL_SYNC, pageView.getSyncType());
        assertTrue(pageView.getDynamicProfileUpdatesMap().containsKey("page_view_count"));

        // 场景3: 新用户注册 (关键业务，立即全量同步)
        UserProfileSyncEvent newUser = UserProfileSyncEvent.newBuilder()
            .setUserId("new_user_registration")
            .setPriority(SyncPriority.IMMEDIATE)
            .setSyncType(SyncType.FULL_SYNC)
            .setVersion(1)
            .setTimestamp(now)
            .setMetadata(ProfileMetadata.newBuilder()
                .setRegistrationDate(now)
                .setLastActiveAt(now)
                .build())
            .putStaticProfileUpdates("email", Any.pack(StringValue.of("newuser@example.com")))
            .putDynamicProfileUpdates("registration_source", Any.pack(StringValue.of("web")))
            .addTagsToAdd("new-user")
            .setStatusUpdate("ACTIVE")
            .build();

        assertEquals(SyncPriority.IMMEDIATE, newUser.getPriority());
        assertEquals(SyncType.FULL_SYNC, newUser.getSyncType());
        assertEquals(1, newUser.getVersion());
        assertTrue(newUser.hasMetadata());
        assertTrue(newUser.getTagsToAddList().contains("new-user"));
        assertEquals("ACTIVE", newUser.getStatusUpdate());
    }

    @Test
    @DisplayName("测试ProfileMetadata字段")
    void testProfileMetadata() {
        Timestamp now = Timestamp.newBuilder()
            .setSeconds(Instant.now().getEpochSecond())
            .build();

        ProfileMetadata metadata = ProfileMetadata.newBuilder()
            .setRegistrationDate(now)
            .setLastActiveAt(now)
            .build();

        UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
            .setUserId("metadata_test_user")
            .setPriority(SyncPriority.IMMEDIATE)
            .setSyncType(SyncType.FULL_SYNC)
            .setVersion(1)
            .setMetadata(metadata)
            .build();

        assertTrue(event.hasMetadata());
        assertEquals(metadata, event.getMetadata());
        assertTrue(event.getMetadata().hasRegistrationDate());
        assertTrue(event.getMetadata().hasLastActiveAt());
    }
}