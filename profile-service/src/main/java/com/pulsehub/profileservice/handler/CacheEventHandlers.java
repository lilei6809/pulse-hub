package com.pulsehub.profileservice.handler;

import com.pulsehub.profileservice.domain.event.CleanupCompletedEvent;
import com.pulsehub.profileservice.domain.event.CleanupFailedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
// 回调逻辑通过事件监听器实现
@Slf4j
@Component
public class CacheEventHandlers {

    @EventListener
    @Async("generalTaskExecutor")  // 使用不同的线程池
    public void handleCleanupCompleted(CleanupCompletedEvent event) {
        /**
         * 事件驱动, 监听 CleanupCompletedEvent
         *
         * 可以对该 event 执行类似回调的功能
         */
        log.info("清理完成回调：{}", event.getEventId());
//        log.info("📊 清理完成回调：{} 条记录", event.getCleanedCount());
//
//        // 这里就是你的"回调"逻辑
//        updateMetrics(event.getCleanedCount());
//        updateExternalSystems(event);
//        sendSuccessNotification(event);
    }

    @EventListener
    @Async("generalTaskExecutor")
    public void handleCleanupFailed(CleanupFailedEvent event) {

        log.error("📊 清理失败回调：{}", event.getErrorMessage());
//
//        // 失败处理逻辑
//        recordFailureMetrics(event);
//        sendFailureAlert(event);
    }


}
