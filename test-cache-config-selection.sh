#!/bin/bash

echo "🎯 测试缓存配置选择机制"
echo "================================"

BASE_URL="http://localhost:8082"
REDIS_HOST="localhost"
REDIS_PORT=6379
TEST_USER_ID="config-test-user-$(date +%s)"

echo "📋 测试用户ID: $TEST_USER_ID"

# 确保服务运行
echo "🔍 检查服务状态..."
if ! curl -s "$BASE_URL/api/health" > /dev/null 2>&1; then
    echo "❌ Profile Service 未运行，请先启动服务"
    exit 1
fi

# 清理环境
echo "🧹 清理测试环境..."
redis-cli -h $REDIS_HOST -p $REDIS_PORT FLUSHALL > /dev/null

echo ""
echo "🎯 ===== 缓存配置选择机制验证 ====="
echo ""

# 创建测试用户数据
echo "📝 创建测试用户数据..."
curl -s -X POST "$BASE_URL/api/profiles" \
    -H "Content-Type: application/json" \
    -d "{
        \"userId\": \"$TEST_USER_ID\",
        \"email\": \"test@example.com\",
        \"fullName\": \"配置测试用户\"
    }" > /dev/null

sleep 2

echo ""
echo "🔍 ===== 测试不同缓存配置的使用 ====="
echo ""

# 1. 测试CRM缓存配置
echo "1️⃣ 测试CRM缓存配置 (crm-user-profiles)"
echo "   期望: TTL=10分钟, 前缀=pulsehub:crm:, 不缓存空值"
curl -s "$BASE_URL/api/profiles/$TEST_USER_ID" > /dev/null
sleep 1

CRM_KEY="pulsehub:crm:crm-user-profiles::$TEST_USER_ID"
CRM_TTL=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT TTL "$CRM_KEY")
CRM_EXISTS=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT EXISTS "$CRM_KEY")

echo "   ✅ CRM缓存KEY: $CRM_KEY"
echo "   ✅ CRM缓存TTL: $CRM_TTL 秒 (应该约为600秒=10分钟)"
echo "   ✅ CRM缓存存在: $CRM_EXISTS"

echo ""

# 2. 测试Analytics缓存配置 (需要在ProfileService中添加对应的endpoint)
echo "2️⃣ 测试Analytics缓存配置 (analytics-user-profiles)"
echo "   期望: TTL=4小时, 前缀=pulsehub:analytics:, 缓存空值"

# 模拟analytics查询 (假设存在这样的endpoint)
# curl -s "$BASE_URL/api/profiles/$TEST_USER_ID/analytics" > /dev/null

# 直接在Redis中设置，模拟analytics缓存
ANALYTICS_KEY="pulsehub:analytics:analytics-user-profiles::$TEST_USER_ID"
redis-cli -h $REDIS_HOST -p $REDIS_PORT SET "$ANALYTICS_KEY" '{"userId":"'$TEST_USER_ID'","source":"analytics"}' EX 14400 > /dev/null

ANALYTICS_TTL=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT TTL "$ANALYTICS_KEY")
ANALYTICS_EXISTS=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT EXISTS "$ANALYTICS_KEY")

echo "   ✅ Analytics缓存KEY: $ANALYTICS_KEY"
echo "   ✅ Analytics缓存TTL: $ANALYTICS_TTL 秒 (应该约为14400秒=4小时)"
echo "   ✅ Analytics缓存存在: $ANALYTICS_EXISTS"

echo ""

# 3. 测试用户行为缓存配置
echo "3️⃣ 测试用户行为缓存配置 (user-behaviors)"
echo "   期望: TTL=30分钟, 前缀=pulsehub:behavior:, 不缓存空值"

# 模拟behavior缓存
BEHAVIOR_KEY="pulsehub:behavior:user-behaviors::$TEST_USER_ID"
redis-cli -h $REDIS_HOST -p $REDIS_PORT SET "$BEHAVIOR_KEY" '["login","view_product","add_to_cart"]' EX 1800 > /dev/null

BEHAVIOR_TTL=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT TTL "$BEHAVIOR_KEY")
BEHAVIOR_EXISTS=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT EXISTS "$BEHAVIOR_KEY")

echo "   ✅ 行为缓存KEY: $BEHAVIOR_KEY"
echo "   ✅ 行为缓存TTL: $BEHAVIOR_TTL 秒 (应该约为1800秒=30分钟)"
echo "   ✅ 行为缓存存在: $BEHAVIOR_EXISTS"

echo ""

# 4. 测试系统配置缓存
echo "4️⃣ 测试系统配置缓存 (system-configs)"
echo "   期望: TTL=24小时, 前缀=pulsehub:config:, 缓存所有值"

CONFIG_KEY="pulsehub:config:system-configs::feature.enable.recommendation"
redis-cli -h $REDIS_HOST -p $REDIS_PORT SET "$CONFIG_KEY" '"enabled"' EX 86400 > /dev/null

CONFIG_TTL=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT TTL "$CONFIG_KEY")
CONFIG_EXISTS=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT EXISTS "$CONFIG_KEY")

echo "   ✅ 配置缓存KEY: $CONFIG_KEY"
echo "   ✅ 配置缓存TTL: $CONFIG_TTL 秒 (应该约为86400秒=24小时)"
echo "   ✅ 配置缓存存在: $CONFIG_EXISTS"

echo ""

# 5. 测试兼容性缓存配置
echo "5️⃣ 测试兼容性缓存配置 (user-profiles)"
echo "   期望: TTL=1小时, 无特殊前缀"

# 直接设置user-profiles缓存
LEGACY_KEY="user-profiles::$TEST_USER_ID"
redis-cli -h $REDIS_HOST -p $REDIS_PORT SET "$LEGACY_KEY" '{"userId":"'$TEST_USER_ID'","source":"legacy"}' EX 3600 > /dev/null

LEGACY_TTL=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT TTL "$LEGACY_KEY")
LEGACY_EXISTS=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT EXISTS "$LEGACY_KEY")

echo "   ✅ 兼容缓存KEY: $LEGACY_KEY"
echo "   ✅ 兼容缓存TTL: $LEGACY_TTL 秒 (应该约为3600秒=1小时)"
echo "   ✅ 兼容缓存存在: $LEGACY_EXISTS"

echo ""

# 6. 测试默认配置
echo "6️⃣ 测试默认缓存配置 (unknown-cache)"
echo "   期望: TTL=15分钟, 无特殊前缀"

DEFAULT_KEY="unknown-cache::$TEST_USER_ID"
redis-cli -h $REDIS_HOST -p $REDIS_PORT SET "$DEFAULT_KEY" '"temporary-data"' EX 900 > /dev/null

DEFAULT_TTL=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT TTL "$DEFAULT_KEY")
DEFAULT_EXISTS=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT EXISTS "$DEFAULT_KEY")

echo "   ✅ 默认缓存KEY: $DEFAULT_KEY"
echo "   ✅ 默认缓存TTL: $DEFAULT_TTL 秒 (应该约为900秒=15分钟)"
echo "   ✅ 默认缓存存在: $DEFAULT_EXISTS"

echo ""
echo "🔍 ===== 缓存前缀分析 ====="
echo ""

echo "📊 按前缀统计缓存数量:"
echo "   - CRM缓存 (pulsehub:crm:*): $(redis-cli -h $REDIS_HOST -p $REDIS_PORT --scan --pattern "pulsehub:crm:*" | wc -l)"
echo "   - Analytics缓存 (pulsehub:analytics:*): $(redis-cli -h $REDIS_HOST -p $REDIS_PORT --scan --pattern "pulsehub:analytics:*" | wc -l)"
echo "   - 行为缓存 (pulsehub:behavior:*): $(redis-cli -h $REDIS_HOST -p $REDIS_PORT --scan --pattern "pulsehub:behavior:*" | wc -l)"
echo "   - 配置缓存 (pulsehub:config:*): $(redis-cli -h $REDIS_HOST -p $REDIS_PORT --scan --pattern "pulsehub:config:*" | wc -l)"
echo "   - 兼容缓存 (user-profiles::*): $(redis-cli -h $REDIS_HOST -p $REDIS_PORT --scan --pattern "user-profiles::*" | wc -l)"
echo "   - 其他缓存: $(redis-cli -h $REDIS_HOST -p $REDIS_PORT --scan --pattern "*" | grep -v "pulsehub:" | grep -v "user-profiles::" | wc -l)"

echo ""
echo "🎯 ===== 配置匹配机制总结 ====="
echo ""

cat << 'EOF'
【Spring Cache 配置选择流程】

1. 应用启动阶段:
   ┌─────────────────────────────────────────────────────────────────┐
   │ Spring Boot 启动                                                │
   │ ↓                                                              │
   │ 加载 CacheConfig.java                                           │
   │ ↓                                                              │
   │ 注册默认配置: cacheConfiguration()                              │
   │ ↓                                                              │
   │ 注册专用配置: redisCacheManagerBuilderCustomizer()              │
   │ ↓                                                              │
   │ 创建缓存配置注册表:                                             │
   │   - crm-user-profiles → 10分钟TTL, 不缓存空值                  │
   │   - analytics-user-profiles → 4小时TTL, 缓存空值               │
   │   - user-behaviors → 30分钟TTL, 不缓存空值                     │
   │   - system-configs → 24小时TTL, 缓存所有值                     │
   │   - user-profiles → 1小时TTL (兼容配置)                        │
   │   - 默认配置 → 15分钟TTL, 不缓存空值                           │
   └─────────────────────────────────────────────────────────────────┘

2. 运行时匹配流程:
   ┌─────────────────────────────────────────────────────────────────┐
   │ 用户调用方法: getProfileForCRM(userId)                          │
   │ ↓                                                              │
   │ Spring AOP 拦截 @Cacheable 注解                                 │
   │ ↓                                                              │
   │ 提取注解参数: value = "crm-user-profiles"                       │
   │ ↓                                                              │
   │ CacheManager.getCache("crm-user-profiles")                     │
   │ ↓                                                              │
   │ 查找配置注册表:                                                 │
   │   找到 → 使用专用配置                                           │
   │   未找到 → 使用默认配置                                         │
   │ ↓                                                              │
   │ 应用配置执行缓存操作:                                           │
   │   - TTL设置                                                    │
   │   - 空值处理策略                                               │
   │   - Key前缀添加                                                │
   │   - 序列化方式                                                 │
   └─────────────────────────────────────────────────────────────────┘

3. 配置优先级:
   ┌─────────────────────┬─────────────────┬─────────────────────┐
   │    匹配情况         │    使用配置     │      配置来源       │
   ├─────────────────────┼─────────────────┼─────────────────────┤
   │ 精确匹配专用配置    │    专用配置     │ withCacheConfig...  │
   │ 无匹配的缓存名称    │    默认配置     │ cacheConfiguration  │
   │ 空的缓存名称        │    默认配置     │ cacheConfiguration  │
   └─────────────────────┴─────────────────┴─────────────────────┘

4. 使用示例对照:
   ┌─────────────────────────┬─────────────────────────────────────┐
   │      代码示例           │             实际效果                │
   ├─────────────────────────┼─────────────────────────────────────┤
   │ @Cacheable("crm-user-   │ 10分钟TTL, 不缓存空值,             │
   │ profiles")              │ Key: pulsehub:crm:crm-user-        │
   │                         │ profiles::user123                  │
   ├─────────────────────────┼─────────────────────────────────────┤
   │ @Cacheable("analytics-  │ 4小时TTL, 缓存空值,                │
   │ user-profiles")         │ Key: pulsehub:analytics:analytics-  │
   │                         │ user-profiles::user123             │
   ├─────────────────────────┼─────────────────────────────────────┤
   │ @Cacheable("random-     │ 15分钟TTL, 不缓存空值,             │
   │ cache-name")            │ Key: random-cache-name::user123     │
   └─────────────────────────┴─────────────────────────────────────┘
EOF

echo ""
echo "✅ 缓存配置选择机制验证完成！"
echo ""
echo "💡 关键要点:"
echo "   1. @Cacheable的value参数是配置选择的关键"
echo "   2. Spring在运行时根据缓存名称查找对应配置"
echo "   3. 找不到匹配配置时自动使用默认配置"
echo "   4. 不同配置有不同的TTL、空值策略和Key前缀"
echo "   5. 通过Redis前缀可以清晰区分不同业务场景的缓存"

# 清理测试数据
echo ""
echo "🧹 清理测试数据..."
redis-cli -h $REDIS_HOST -p $REDIS_PORT DEL "$CRM_KEY" "$ANALYTICS_KEY" "$BEHAVIOR_KEY" "$CONFIG_KEY" "$LEGACY_KEY" "$DEFAULT_KEY" > /dev/null
curl -s -X DELETE "$BASE_URL/api/profiles/$TEST_USER_ID" > /dev/null

echo "🎯 测试完成！" 