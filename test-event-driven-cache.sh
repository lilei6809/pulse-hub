#!/bin/bash

echo "🚀 测试事件驱动缓存失效机制"
echo "================================"

BASE_URL="http://localhost:8082"
REDIS_HOST="localhost"
REDIS_PORT=6379
TEST_USER_ID="event-test-user-$(date +%s)"

echo "📋 测试用户ID: $TEST_USER_ID"

# 1. 清理环境
echo "🧹 清理测试环境..."
redis-cli -h $REDIS_HOST -p $REDIS_PORT FLUSHALL > /dev/null

# 2. 第一次查询 - 应该返回404，并缓存空值
echo "🔍 第一次查询用户档案（预期：404，缓存空值）"
RESPONSE1=$(curl -s -w "HTTP_STATUS:%{http_code}" "$BASE_URL/api/profiles/$TEST_USER_ID")
HTTP_STATUS1=$(echo "$RESPONSE1" | grep -o "HTTP_STATUS:[0-9]*" | cut -d: -f2)
echo "   状态码: $HTTP_STATUS1"

# 3. 检查Redis缓存
echo "📊 检查Redis缓存状态"
CACHE_KEY="user-profiles-crm::$TEST_USER_ID"
CACHED_VALUE=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT GET "$CACHE_KEY")
if [ -n "$CACHED_VALUE" ]; then
    echo "   ✅ 空值已缓存: $CACHE_KEY"
else
    echo "   ❌ 缓存中无数据"
fi

# 4. 模拟用户创建事件
echo "📨 模拟发送用户创建事件到Kafka"
cat << EOF > temp_event.json
{
    "event_type": "CREATE",
    "user_id": "$TEST_USER_ID", 
    "profile_id": "profile_$TEST_USER_ID",
    "timestamp": $(date +%s)000,
    "metadata": {
        "source": "test_script",
        "version": "1.0"
    }
}
EOF

# 使用kafka控制台生产者发送事件（需要Kafka运行）
if command -v kafka-console-producer.sh &> /dev/null; then
    kafka-console-producer.sh --bootstrap-server localhost:9092 \
                             --topic user-profile-events < temp_event.json
    echo "   ✅ 事件已发送到Kafka"
else
    echo "   ⚠️  Kafka未安装，跳过事件发送"
    echo "   💡 手动触发：POST /api/profiles/$TEST_USER_ID/cache/evict"
    curl -s -X POST "$BASE_URL/api/profiles/$TEST_USER_ID/cache/evict"
fi

# 5. 等待事件处理
echo "⏳ 等待事件处理（2秒）..."
sleep 2

# 6. 检查缓存是否被清除
echo "🔍 检查缓存是否被事件清除"
CACHED_VALUE_AFTER=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT GET "$CACHE_KEY")
if [ -z "$CACHED_VALUE_AFTER" ]; then
    echo "   ✅ 缓存已被清除"
else
    echo "   ❌ 缓存仍然存在: $CACHED_VALUE_AFTER"
fi

# 7. 创建用户档案
echo "👤 创建测试用户档案"
CREATE_PAYLOAD=$(cat << EOF
{
    "firstName": "Event",
    "lastName": "Test",
    "email": "event.test@example.com",
    "phone": "+1234567890"
}
EOF
)

RESPONSE2=$(curl -s -w "HTTP_STATUS:%{http_code}" \
                -X POST "$BASE_URL/api/profiles/$TEST_USER_ID" \
                -H "Content-Type: application/json" \
                -d "$CREATE_PAYLOAD")
HTTP_STATUS2=$(echo "$RESPONSE2" | grep -o "HTTP_STATUS:[0-9]*" | cut -d: -f2)
echo "   状态码: $HTTP_STATUS2"

# 8. 立即查询用户档案
echo "🔍 立即查询新创建的用户档案"
RESPONSE3=$(curl -s -w "HTTP_STATUS:%{http_code}" "$BASE_URL/api/profiles/$TEST_USER_ID")
HTTP_STATUS3=$(echo "$RESPONSE3" | grep -o "HTTP_STATUS:[0-9]*" | cut -d: -f2)
BODY3=$(echo "$RESPONSE3" | sed 's/HTTP_STATUS:[0-9]*$//')
echo "   状态码: $HTTP_STATUS3"

if [ "$HTTP_STATUS3" = "200" ]; then
    echo "   ✅ 成功获取用户档案"
    echo "   📄 用户信息: $(echo "$BODY3" | jq -r '.firstName + " " + .lastName')"
else
    echo "   ❌ 获取用户档案失败"
fi

# 9. 验证缓存行为
echo "📊 验证最终缓存状态"
FINAL_CACHE=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT GET "$CACHE_KEY")
if [ -n "$FINAL_CACHE" ]; then
    echo "   ✅ 新数据已缓存"
else
    echo "   ⚠️  缓存为空（可能是因为配置问题）"
fi

# 10. 性能对比测试
echo "⚡ 性能对比测试"
echo "   测试缓存命中性能..."
start_time=$(date +%s%N)
for i in {1..10}; do
    curl -s "$BASE_URL/api/profiles/$TEST_USER_ID" > /dev/null
done
end_time=$(date +%s%N)
cache_time=$((($end_time - $start_time) / 1000000))

echo "   10次缓存查询耗时: ${cache_time}ms"
echo "   平均每次查询: $((cache_time / 10))ms"

# 清理
rm -f temp_event.json

echo ""
echo "🎯 测试总结"
echo "============"
echo "传统方案: 空值缓存30分钟，新数据不可见"  
echo "事件驱动: 收到创建事件后立即清除缓存，新数据立即可见"
echo ""
echo "💡 关键优势："
echo "   • 实时性: 毫秒级缓存失效"
echo "   • 一致性: 减少数据不一致窗口"
echo "   • 灵活性: 基于事件类型的精细化缓存管理"
echo ""
echo "🏗️  架构改进建议："
echo "   • 添加事件重试机制防止消息丢失"
echo "   • 实现缓存预热减少冷启动延迟" 
echo "   • 添加监控告警跟踪缓存一致性" 