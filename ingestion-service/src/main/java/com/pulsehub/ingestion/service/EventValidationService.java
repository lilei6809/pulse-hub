package com.pulsehub.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulsehub.ingestion.config.EventValidationConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class EventValidationService {
    
    @Autowired
    private EventValidationConfig eventConfig;
    
    public ValidationResult validateEvent(JsonNode eventData) {
        ValidationResult result = new ValidationResult();
        
        // 验证必需字段
        if (!hasRequiredFields(eventData)) {
            result.setValid(false);
            result.addError("Missing required fields: messageId, timestamp, event");
            return result;
        }
        
        // 验证事件类型
        String type = eventData.path("event").asText();
        // type 是否合法
//        if (!eventConfig.isValidEventType(type)) {
//            result.setValid(false);
//            result.addError("Invalid event type: " + type);
//        }


        
        // 对于 track 类型事件，验证事件名称
        //TODO: 目前先考虑匹配 valid-track-events
        if (type.isEmpty()) {
            result.setValid(false);
            result.addError("Track event missing event name");
        } else if (!eventConfig.isValidTrackEvent(type)) {
            // 当前的 event 不可追踪
            result.addWarning("untrackable event: " + type);
        }

        
        // 验证用户标识
        String userId = eventData.path("userId").asText();
        String anonymousId = eventData.path("anonymousId").asText();
        // 如果无 userId, 且无 anonymousId, 说明是全新的登录, 创建 anonymousId, 前端需要保存到 cookie 中
        if (userId.isEmpty() && anonymousId.isEmpty()) {
            if (eventData instanceof ObjectNode){
                String uuid = UUID.randomUUID().toString();
                ((ObjectNode) eventData).put(anonymousId, uuid);

                result.keepNewId("anonymousId", uuid);
            }
            result.setValid(true);
//            result.addError("AnonymousId is created");
        }

        String deviceId = eventData.path("deviceId").asText();
        if (deviceId.isEmpty()) {
            if (eventData instanceof ObjectNode){
                String uuid = UUID.randomUUID().toString();
                ((ObjectNode) eventData).put(deviceId, uuid);
                result.keepNewId("deviceId", uuid);
            }
        }


        return result;
    }
    
    private boolean hasRequiredFields(JsonNode eventData) {
        return eventData.has("eventId") &&
               eventData.has("event") &&
               eventData.has("timestamp");
    }
    
    public static class ValidationResult {
        private boolean valid = true;
        private java.util.List<String> errors = new java.util.ArrayList<>();
        private java.util.List<String> warnings = new java.util.ArrayList<>();
        private Map<String,String> newIdMap = new HashMap<>();

        public void keepNewId(String key, String id){
            newIdMap.put(key,id);
        }

        public Map<String, String> getNewIdMap() {
            return newIdMap;
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public void setValid(boolean valid) {
            this.valid = valid;
        }
        
        public java.util.List<String> getErrors() {
            return errors;
        }
        
        public java.util.List<String> getWarnings() {
            return warnings;
        }
        
        public void addError(String error) {
            this.errors.add(error);
            this.valid = false;
        }
        
        public void addWarning(String warning) {
            this.warnings.add(warning);
        }
    }
}