package com.pulsehub.ingestion.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulsehub.common.proto.UserActivityEvent;
import com.pulsehub.ingestion.service.EventValidationService;
import com.pulsehub.ingestion.service.EventTransformationService;
import com.pulsehub.ingestion.service.KafkaProducerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
@CrossOrigin(origins = "*") // 允许跨域请求
public class EventIngestionController {
    
    private static final Logger logger = LoggerFactory.getLogger(EventIngestionController.class);
    
    @Autowired
    private EventValidationService validationService;
    
    @Autowired
    private EventTransformationService transformationService;
    
    @Autowired
    private KafkaProducerService kafkaProducerService;
    
    @PostMapping("/track")
    public ResponseEntity<Map<String, Object>> trackEvent(@RequestBody JsonNode eventData) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 添加接收时间戳
            /**
             * 为什么需要检查？
             *   - JsonNode 可能是数组, ArrayNode、字符串、数字等类型
             *   - 只有 ObjectNode 才支持 put(), remove() 等修改操作
             *   - 类型检查避免 ClassCastException
             */
            if (eventData instanceof ObjectNode) {
                ((ObjectNode) eventData)
                        .put("receivedAt", Instant.now().toString());
            }
            
            // 1. 验证数据
            EventValidationService.ValidationResult validation = validationService.validateEvent(eventData);
            
            if (!validation.isValid()) {
                logger.warn("Event validation failed: {}", validation.getErrors());
                response.put("success", false);
                response.put("errors", validation.getErrors());
                return ResponseEntity.badRequest().body(response);
            }
            
            // 记录警告（但不阻止处理）
            if (!validation.getWarnings().isEmpty()) {
                logger.info("Event validation warnings: {}", validation.getWarnings());
                response.put("warnings", validation.getWarnings());
            }
            
            // 2. 转换为 protobuf
            UserActivityEvent protoEvent = transformationService.transformJsonToProto(eventData);
            
            // 3. 发送到 Kafka
            kafkaProducerService.sendEvent(protoEvent);

            ResponseEntity.BodyBuilder builder =  ResponseEntity.ok();

            validation.getNewIdMap().forEach((idType, id) -> {
                ResponseCookie cookie = ResponseCookie.from(idType, id)
                        .httpOnly(true)        // 前端 JS 不能读，安全
                        .secure(true)          // 仅 HTTPS 传输
                        .sameSite("Lax")       // 防止 CSRF；跨域收集可用 None
                        .path("/")             // 整个域可用
                        .maxAge(Duration.ofDays(365)) // 一年
                        .build();
                builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
            });
            
            // 4. 返回成功响应
            response.put("success", true);
            response.put("messageId", protoEvent.getEventId());
            response.put("timestamp", Instant.now().toString());



            logger.info("Event processed successfully: messageId={}, type={}, event={}",
                    protoEvent.getEventId(), protoEvent.getEventId(), protoEvent.getEvent());
            
            return builder.body(response);
            
        } catch (Exception e) {
            logger.error("Error processing event", e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * batch Event 不是直接的用户活动, 而是租户通过 第三方导入的数据, 所以不用设置 cookies
     * @param batchData
     * @return
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> trackBatchEvents(@RequestBody JsonNode batchData) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            if (!batchData.has("events") || !batchData.get("events").isArray()) {
                response.put("success", false);
                response.put("error", "Missing or invalid events array");
                return ResponseEntity.badRequest().body(response);
            }
            
            JsonNode events = batchData.get("events");
            int successCount = 0;
            int failureCount = 0;
            
            for (JsonNode eventData : events) {
                try {
                    // 添加接收时间戳
                    if (eventData instanceof ObjectNode) {
                        ((ObjectNode) eventData)
                                .put("receivedAt", Instant.now().toString());
                    }
                    
                    // 验证并处理单个事件
                    EventValidationService.ValidationResult validation = validationService.validateEvent(eventData);
                    
                    if (validation.isValid()) {
                        UserActivityEvent protoEvent = transformationService.transformJsonToProto(eventData);
                        kafkaProducerService.sendEvent(protoEvent);
                        successCount++;
                    } else {
                        logger.warn("Batch event validation failed: {}", validation.getErrors());
                        failureCount++;
                    }
                } catch (Exception e) {
                    logger.error("Error processing batch event", e);
                    failureCount++;
                }
            }

            //TODO: 添加 id 的 cookie
            
            response.put("success", failureCount == 0);
            response.put("processedCount", successCount);
            response.put("failedCount", failureCount);
            response.put("timestamp", Instant.now().toString());
            
            logger.info("Batch events processed: success={}, failed={}", successCount, failureCount);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error processing batch events", e);
            response.put("success", false);
            response.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("timestamp", Instant.now().toString());
        response.put("service", "event-ingestion");
        return ResponseEntity.ok(response);
    }
}