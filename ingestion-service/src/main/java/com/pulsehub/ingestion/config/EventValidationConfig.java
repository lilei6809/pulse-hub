package com.pulsehub.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 事件验证配置类
 * 
 * 该配置类负责管理 PulseHub 事件摄取系统中的事件验证逻辑，主要功能包括：
 * 1. 定义合法的事件类型列表，用于验证传入事件的类型是否被系统支持
 * 2. 定义允许跟踪的事件列表，用于过滤和验证事件跟踪请求
 * 3. 管理事件分类映射关系，将具体事件归类到不同的业务类别中
 * 
 * 通过 Spring Boot 的 @ConfigurationProperties 注解，该类可以自动从
 * application.yml 或 application.properties 中加载以 "pulsehub.events" 
 * 为前缀的配置项，实现灵活的外部化配置管理。
 * 
 * 配置示例：
 * pulsehub:
 *   events:
 *     valid-types:
 *       - "page_view"
 *       - "click"
 *       - "purchase"
 *     valid-track-events:
 *       - "user_login"
 *       - "product_view" 
 *     event-categories:
 *       engagement:
 *         - "page_view"
 *         - "click"
 *       commerce:
 *         - "purchase"
 *         - "add_to_cart"
 * 
*    prefix = "pulsehub.events"  映射到 yaml 的 pulsehub.events 路径下的配置项
 */
@Configuration
@ConfigurationProperties(prefix = "pulsehub.events")
public class EventValidationConfig {
    
    /**
     * 合法的事件类型列表
     * 
     * 存储系统支持的所有事件类型，用于在事件摄取过程中验证传入事件的类型。
     * 只有在此列表中的事件类型才会被系统接受和处理，其他类型的事件将被拒绝。
     * valid-types -> validTypes
     * 典型的事件类型包括：
     * - "page_view": 页面浏览事件
     * - "click": 点击事件  
     * - "purchase": 购买事件
     * - "user_action": 用户行为事件
     * - "system_event": 系统事件
     */
    private List<String> validEventTypes;
    
    /**
     * 允许跟踪的事件列表
     * 
     * 定义哪些具体的事件可以被跟踪和记录。这个列表提供了更细粒度的控制，
     * 允许系统管理员精确控制哪些事件需要被持久化存储和分析。
     * valid-track-events -> validTrackEvents
     *
     * 与 validTypes 不同的是，validTrackEvents 关注的是具体的事件名称，
     * 而不是事件类型。例如：
     * - "user_login": 用户登录事件
     * - "product_view": 产品查看事件
     * - "search_query": 搜索查询事件
     */
    private List<String> validTrackEvents;
    
    /**
     * 事件分类映射配置
     * 
     * 将具体的事件映射到不同的业务类别中，便于事件的分类管理和统计分析。
     * Key 为类别名称，Value 为该类别下包含的事件列表。
     * 
     * 分类的好处：
     * 1. 便于业务分析：可以按类别统计事件数量和趋势
     * 2. 简化配置管理：可以基于类别设置不同的处理策略
     * 3. 提高查询效率：支持按类别快速检索相关事件
     * 
     * 示例分类：
     * - "engagement": 用户参与类事件（点击、浏览等）
     * - "commerce": 电商类事件（购买、加购物车等）
     * - "authentication": 认证类事件（登录、注册等）
     * - "system": 系统类事件（错误、警告等）
     */
    private Map<String, List<String>> eventCategories;
    
    /**
     * 获取合法事件类型列表
     * 
     * @return 合法的事件类型列表，如果未配置则返回 null
     */
    public List<String> getValidEventTypes() {
        return validEventTypes;
    }
    
    /**
     * 设置合法事件类型列表
     * 
     * @param validEventTypes 要设置的合法事件类型列表
     */
    public void setValidEventTypes(List<String> validEventTypes) {
        this.validEventTypes = validEventTypes;
    }
    
    /**
     * 获取允许跟踪的事件列表
     * 
     * @return 允许跟踪的事件列表，如果未配置则返回 null
     */
    public List<String> getValidTrackEvents() {
        return validTrackEvents;
    }
    
    /**
     * 设置允许跟踪的事件列表
     * 
     * @param validTrackEvents 要设置的允许跟踪事件列表
     */
    public void setValidTrackEvents(List<String> validTrackEvents) {
        this.validTrackEvents = validTrackEvents;
    }
    
    /**
     * 获取事件分类映射配置
     * 
     * @return 事件分类映射，Key为类别名称，Value为该类别下的事件列表
     */
    public Map<String, List<String>> getEventCategories() {
        return eventCategories;
    }
    
    /**
     * 设置事件分类映射配置
     * 
     * @param eventCategories 要设置的事件分类映射
     */
    public void setEventCategories(Map<String, List<String>> eventCategories) {
        this.eventCategories = eventCategories;
    }
    
    /**
     * 验证给定的事件类型是否合法
     * 
     * 此方法用于在事件摄取过程中快速验证传入事件的类型是否被系统支持。
     * 
     * 验证逻辑：
     * 1. 首先检查 validTypes 列表是否已初始化（不为 null）
     * 2. 然后检查给定的 type 是否存在于 validTypes 列表中
     * 3. 只有两个条件都满足时才返回 true
     * 
     * @param type 要验证的事件类型
     * @return true 如果事件类型合法，false 否则
     * 
     * @example
     * <pre>
     * EventValidationConfig config = new EventValidationConfig();
     * // 假设配置中包含 "page_view" 类型
     * boolean isValid = config.isValidType("page_view"); // 返回 true
     * boolean isInvalid = config.isValidType("unknown_type"); // 返回 false
     * </pre>
     */
    public boolean isValidEventType(String type) {
        return validEventTypes != null && validEventTypes.contains(type);
    }
    
    /**
     * 验证给定的事件是否允许被跟踪
     * 
     * 此方法用于验证特定事件是否在系统的跟踪白名单中，确保只有被授权的事件
     * 才会被记录和分析。
     * 
     * 验证逻辑：
     * 1. 检查 validTrackEvents 列表是否已初始化
     * 2. 检查给定的 event 是否在跟踪白名单中
     * 3. 两个条件都满足时返回 true
     * 
     * 使用场景：
     * - 事件过滤：在摄取管道中过滤掉不需要跟踪的事件
     * - 隐私保护：确保敏感事件不会被意外记录
     * - 资源优化：减少不必要事件的处理和存储开销
     * 
     * @param event 要验证的事件名称
     * @return true 如果事件允许被跟踪，false 否则
     * 
     * @example
     * <pre>
     * boolean canTrack = config.isValidTrackEvent("user_login"); // 返回 true（如果配置中包含）
     * boolean cannotTrack = config.isValidTrackEvent("internal_debug"); // 返回 false
     * </pre>
     */
    public boolean isValidTrackEvent(String event) {
        return validTrackEvents != null && validTrackEvents.contains(event);
    }
    
    /**
     * 根据事件名称获取其所属的业务类别
     * 
     * 该方法通过遍历事件分类映射来查找给定事件属于哪个业务类别。
     * 这对于事件的分类统计、不同类别事件的差异化处理等场景非常有用。
     * 
     * 查找逻辑：
     * 1. 检查 eventCategories 映射是否已初始化
     * 2. 遍历所有类别及其包含的事件列表
     * 3. 找到包含目标事件的第一个类别并返回类别名称
     * 4. 如果未找到匹配的类别，返回 "unknown" 作为默认值
     * 
     * 注意事项：
     * - 如果一个事件出现在多个类别中，返回遍历过程中首次匹配的类别
     * - 遍历顺序取决于 Map 的实现（通常是插入顺序或自然排序）
     * - 返回 "unknown" 不代表事件无效，只是未被分类
     * 
     * @param event 要查询类别的事件名称
     * @return 事件所属的类别名称，如果未找到则返回 "unknown"
     * 
     * @example
     * <pre>
     * String category1 = config.getEventCategory("page_view"); // 可能返回 "engagement"
     * String category2 = config.getEventCategory("purchase"); // 可能返回 "commerce"  
     * String category3 = config.getEventCategory("undefined_event"); // 返回 "unknown"
     * </pre>
     */
    public String getEventCategory(String event) {
        if (eventCategories != null) {
            for (Map.Entry<String, List<String>> entry : eventCategories.entrySet()) {
                if (entry.getValue().contains(event)) {
                    return entry.getKey();
                }
            }
        }
        return "unknown";
    }
}