package com.pulsehub.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulsehub.common.proto.*;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@Service
public class EventTransformationService {
    
    private static final Logger logger = LoggerFactory.getLogger(EventTransformationService.class);
    
    public UserActivityEvent transformJsonToProto(JsonNode eventData) {
        try {
            UserActivityEvent.Builder builder = UserActivityEvent.newBuilder();
            
            // 基本字段
            builder.setEventId(getStringValue(eventData, "messageId", UUID.randomUUID().toString()));
            builder.setUserId(getStringValue(eventData, "userId", ""));
            builder.setAnonymousId(getStringValue(eventData, "anonymousId", ""));
//            builder.setGroupId(getStringValue(eventData, "groupId", ""));
            
            // 事件信息
            builder.setEvent(getStringValue(eventData, "event", ""));
            builder.setType(getStringValue(eventData, "type", ""));
            builder.setVersion(getIntValue(eventData, "version", 1));
            
            // 时间戳
            builder.setTimestamp(getStringValue(eventData, "timestamp", ""));
//            builder.setSentAt(getStringValue(eventData, "sentAt", ""));
            builder.setReceivedAt(getStringValue(eventData, "receivedAt", ""));
            
            // 上下文信息
            if (eventData.has("context")) {
                builder.setContext(transformContext(eventData.get("context")));
            }
            
            // 集成配置
            if (eventData.has("integrations")) {
                JsonNode integrations = eventData.get("integrations");
                integrations.fieldNames().forEachRemaining(fieldName -> {
                    builder.putIntegrations(fieldName, integrations.get(fieldName).asBoolean());
                });
            }
            
            // 事件属性（properties字段）
            if (eventData.has("properties")) {
                transformProperties(eventData.get("properties"), builder);
            }
            
            return builder.build();
        } catch (Exception e) {
            logger.error("Error transforming JSON to protobuf", e);
            throw new RuntimeException("Failed to transform event data", e);
        }
    }
    
    private Context transformContext(JsonNode contextNode) {
        Context.Builder builder = Context.newBuilder();
        
        builder.setActive(getBooleanValue(contextNode, "active", false));
        builder.setIp(getStringValue(contextNode, "ip", ""));
        builder.setLocale(getStringValue(contextNode, "locale", ""));
        builder.setTimezone(getStringValue(contextNode, "timezone", ""));
        builder.setUserAgent(getStringValue(contextNode, "userAgent", ""));
        
        // App信息
        if (contextNode.has("app")) {
            builder.setApp(transformApp(contextNode.get("app")));
        }
        
        // Device信息
        if (contextNode.has("device")) {
            builder.setDevice(transformDevice(contextNode.get("device")));
        }
        
        // Campaign信息
        if (contextNode.has("campaign")) {
            builder.setCampaign(transformCampaign(contextNode.get("campaign")));
        }
        
        // Library信息
        if (contextNode.has("library")) {
            builder.setLibrary(transformLibrary(contextNode.get("library")));
        }
        
        // Network信息
        if (contextNode.has("network")) {
            builder.setNetwork(transformNetwork(contextNode.get("network")));
        }
        
        // OS信息
        if (contextNode.has("os")) {
            builder.setOs(transformOs(contextNode.get("os")));
        }
        
        // Page信息
        if (contextNode.has("page")) {
            builder.setPage(transformPage(contextNode.get("page")));
        }
        
        // Referrer信息
        if (contextNode.has("referrer")) {
            builder.setReferrer(transformReferrer(contextNode.get("referrer")));
        }
        
        // Screen信息
        if (contextNode.has("screen")) {
            builder.setScreen(transformScreen(contextNode.get("screen")));
        }
        
        // UserAgentData信息
        if (contextNode.has("userAgentData")) {
            builder.setUserAgentData(transformUserAgentData(contextNode.get("userAgentData")));
        }
        
        return builder.build();
    }
    
    private App transformApp(JsonNode appNode) {
        return App.newBuilder()
                .setName(getStringValue(appNode, "name", ""))
                .setVersion(getStringValue(appNode, "version", ""))
                .setBuild(getStringValue(appNode, "build", ""))
                .setNamespace(getStringValue(appNode, "namespace", ""))
                .build();
    }
    
    private Device transformDevice(JsonNode deviceNode) {
        return Device.newBuilder()
                .setId(getStringValue(deviceNode, "id", ""))
                .setAdvertisingId(getStringValue(deviceNode, "advertisingId", ""))
                .setAdTrackingEnabled(getBooleanValue(deviceNode, "adTrackingEnabled", false))
                .setManufacturer(getStringValue(deviceNode, "manufacturer", ""))
                .setModel(getStringValue(deviceNode, "model", ""))
                .setName(getStringValue(deviceNode, "name", ""))
                .setType(getStringValue(deviceNode, "type", ""))
                .setToken(getStringValue(deviceNode, "token", ""))
                .build();
    }
    
    private Campaign transformCampaign(JsonNode campaignNode) {
        return Campaign.newBuilder()
                .setName(getStringValue(campaignNode, "name", ""))
                .setSource(getStringValue(campaignNode, "source", ""))
                .setMedium(getStringValue(campaignNode, "medium", ""))
                .setTerm(getStringValue(campaignNode, "term", ""))
                .setContent(getStringValue(campaignNode, "content", ""))
                .build();
    }
    
    private Library transformLibrary(JsonNode libraryNode) {
        return Library.newBuilder()
                .setName(getStringValue(libraryNode, "name", ""))
                .setVersion(getStringValue(libraryNode, "version", ""))
                .build();
    }
    
    private Network transformNetwork(JsonNode networkNode) {
        return Network.newBuilder()
                .setBluetooth(getBooleanValue(networkNode, "bluetooth", false))
                .setCarrier(getStringValue(networkNode, "carrier", ""))
                .setCellular(getBooleanValue(networkNode, "cellular", false))
                .setWifi(getBooleanValue(networkNode, "wifi", false))
                .build();
    }
    
    private Os transformOs(JsonNode osNode) {
        return Os.newBuilder()
                .setName(getStringValue(osNode, "name", ""))
                .setVersion(getStringValue(osNode, "version", ""))
                .build();
    }
    
    private Page transformPage(JsonNode pageNode) {
        return Page.newBuilder()
                .setPath(getStringValue(pageNode, "path", ""))
                .setReferrer(getStringValue(pageNode, "referrer", ""))
                .setSearch(getStringValue(pageNode, "search", ""))
                .setTitle(getStringValue(pageNode, "title", ""))
                .setUrl(getStringValue(pageNode, "url", ""))
                .build();
    }
    
    private Referrer transformReferrer(JsonNode referrerNode) {
        return Referrer.newBuilder()
                .setId(getStringValue(referrerNode, "id", ""))
                .setType(getStringValue(referrerNode, "type", ""))
                .build();
    }
    
    private Screen transformScreen(JsonNode screenNode) {
        return Screen.newBuilder()
                .setWidth(getIntValue(screenNode, "width", 0))
                .setHeight(getIntValue(screenNode, "height", 0))
                .setDensity(getDoubleValue(screenNode, "density", 0.0))
                .build();
    }
    
    private UserAgentData transformUserAgentData(JsonNode userAgentNode) {
        UserAgentData.Builder builder = UserAgentData.newBuilder();
        builder.setMobile(getBooleanValue(userAgentNode, "mobile", false));
        builder.setPlatform(getStringValue(userAgentNode, "platform", ""));
        
        if (userAgentNode.has("brands")) {
            JsonNode brands = userAgentNode.get("brands");
            if (brands.isArray()) {
                for (JsonNode brandNode : brands) {
                    Brand brand = Brand.newBuilder()
                            .setBrand(getStringValue(brandNode, "brand", ""))
                            .setVersion(getStringValue(brandNode, "version", ""))
                            .build();
                    builder.addBrands(brand);
                }
            }
        }
        
        return builder.build();
    }
    
    private void transformProperties(JsonNode propertiesNode, UserActivityEvent.Builder builder) {
        propertiesNode.fieldNames().forEachRemaining(fieldName -> {
            JsonNode valueNode = propertiesNode.get(fieldName);
            
            if (valueNode.isTextual()) {
                builder.putProperties(fieldName, valueNode.asText());
            } else if (valueNode.isBoolean()) {
                builder.putBooleanProperties(fieldName, valueNode.asBoolean());
            } else if (valueNode.isNumber()) {
                builder.putNumericProperties(fieldName, valueNode.asDouble());
            } else {
                // 复杂类型转为字符串
                builder.putProperties(fieldName, valueNode.toString());
            }
        });
    }
    
    // 工具方法
    private String getStringValue(JsonNode node, String fieldName, String defaultValue) {
        return node.has(fieldName) ? node.get(fieldName).asText(defaultValue) : defaultValue;
    }
    
    private int getIntValue(JsonNode node, String fieldName, int defaultValue) {
        return node.has(fieldName) ? node.get(fieldName).asInt(defaultValue) : defaultValue;
    }
    
    private double getDoubleValue(JsonNode node, String fieldName, double defaultValue) {
        return node.has(fieldName) ? node.get(fieldName).asDouble(defaultValue) : defaultValue;
    }
    
    private boolean getBooleanValue(JsonNode node, String fieldName, boolean defaultValue) {
        return node.has(fieldName) ? node.get(fieldName).asBoolean(defaultValue) : defaultValue;
    }
}