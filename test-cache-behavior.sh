#!/bin/bash

# 🎯 PulseHub CRM/CDP 分层缓存策略测试脚本
# 演示不同业务场景下的缓存行为差异

echo "🚀 PulseHub CRM/CDP 分层缓存策略学习实验"
echo "=============================================="
echo ""

# 配置
PROFILE_SERVICE_URL="http://localhost:8083"
TEST_USER="cache-test-user"
SALES_USER="sales-user-123"
ANALYTICS_USER="analytics-user-456"

# 1. 清理所有缓存，开始新的实验
echo "📝 步骤1: 清理缓存环境"
docker exec pulse-hub-redis-1 redis-cli FLUSHALL
echo "✅ 所有缓存已清空"
echo ""

# 2. 创建测试用户
echo "📝 步骤2: 创建测试用户"
echo "创建销售场景测试用户:"
curl -s -X POST "$PROFILE_SERVICE_URL/api/profiles" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"$SALES_USER\",\"name\":\"Sales Test User\",\"email\":\"sales@test.com\"}" | head -c 100
echo ""

echo "创建分析场景测试用户:"
curl -s -X POST "$PROFILE_SERVICE_URL/api/profiles" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"$ANALYTICS_USER\",\"name\":\"Analytics Test User\",\"email\":\"analytics@test.com\"}" | head -c 100
echo ""
echo ""

# 3. 测试CRM场景缓存行为（不缓存空值）
echo "📝 步骤3: 测试CRM场景缓存策略（销售/营销/客服）"
echo "=========================================="

echo "3.1 查询存在的用户 - 第一次（应该缓存）:"
curl -s -w "HTTP状态码: %{http_code}, 响应时间: %{time_total}s\n" \
  "$PROFILE_SERVICE_URL/api/profiles/crm/$SALES_USER" > /dev/null

echo "3.2 查询存在的用户 - 第二次（应该命中缓存）:"
curl -s -w "HTTP状态码: %{http_code}, 响应时间: %{time_total}s\n" \
  "$PROFILE_SERVICE_URL/api/profiles/crm/$SALES_USER" > /dev/null

echo ""
echo "3.3 查询不存在的用户 - 第一次（不应该缓存空值）:"
curl -s -w "HTTP状态码: %{http_code}, 响应时间: %{time_total}s\n" \
  "$PROFILE_SERVICE_URL/api/profiles/crm/fake-crm-user" > /dev/null

echo "3.4 查询不存在的用户 - 第二次（应该再次访问数据库）:"
curl -s -w "HTTP状态码: %{http_code}, 响应时间: %{time_total}s\n" \
  "$PROFILE_SERVICE_URL/api/profiles/crm/fake-crm-user" > /dev/null

echo ""

# 4. 测试分析场景缓存行为（缓存空值）
echo "📝 步骤4: 测试数据分析场景缓存策略"
echo "================================="

echo "4.1 查询存在的用户 - 第一次（应该缓存）:"
curl -s -w "HTTP状态码: %{http_code}, 响应时间: %{time_total}s\n" \
  "$PROFILE_SERVICE_URL/api/profiles/analytics/$ANALYTICS_USER" > /dev/null

echo "4.2 查询存在的用户 - 第二次（应该命中缓存）:"
curl -s -w "HTTP状态码: %{http_code}, 响应时间: %{time_total}s\n" \
  "$PROFILE_SERVICE_URL/api/profiles/analytics/$ANALYTICS_USER" > /dev/null

echo ""
echo "4.3 查询不存在的用户 - 第一次（应该缓存空值）:"
curl -s -w "HTTP状态码: %{http_code}, 响应时间: %{time_total}s\n" \
  "$PROFILE_SERVICE_URL/api/profiles/analytics/fake-analytics-user" > /dev/null

echo "4.4 查询不存在的用户 - 第二次（应该命中空值缓存）:"
curl -s -w "HTTP状态码: %{http_code}, 响应时间: %{time_total}s\n" \
  "$PROFILE_SERVICE_URL/api/profiles/analytics/fake-analytics-user" > /dev/null

echo ""

# 5. 检查Redis中的缓存数据结构
echo "📝 步骤5: 检查Redis中的分层缓存数据"
echo "=================================="

echo "所有缓存键:"
docker exec pulse-hub-redis-1 redis-cli KEYS "*"

echo ""
echo "CRM缓存键（短TTL，不缓存空值）:"
docker exec pulse-hub-redis-1 redis-cli KEYS "pulsehub:crm:*"

echo ""
echo "Analytics缓存键（长TTL，缓存空值）:"
docker exec pulse-hub-redis-1 redis-cli KEYS "pulsehub:analytics:*"

echo ""

# 6. 演示新用户可见性差异
echo "📝 步骤6: 演示新用户可见性差异"
echo "=========================="

# 先查询一个不存在的用户ID
NEW_USER="just-registered-user"

echo "6.1 CRM场景 - 查询即将注册的用户（空值不被缓存）:"
curl -s -w "HTTP状态码: %{http_code}\n" \
  "$PROFILE_SERVICE_URL/api/profiles/crm/$NEW_USER" > /dev/null

echo "6.2 Analytics场景 - 查询即将注册的用户（空值被缓存）:"
curl -s -w "HTTP状态码: %{http_code}\n" \
  "$PROFILE_SERVICE_URL/api/profiles/analytics/$NEW_USER" > /dev/null

echo ""
echo "现在创建这个新用户:"
curl -s -X POST "$PROFILE_SERVICE_URL/api/profiles" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"$NEW_USER\",\"name\":\"Just Registered\",\"email\":\"new@test.com\"}" | head -c 100
echo ""

echo ""
echo "6.3 CRM场景 - 新用户注册后立即查询（应该能找到）:"
curl -s -w "HTTP状态码: %{http_code}, 能找到用户: " \
  "$PROFILE_SERVICE_URL/api/profiles/crm/$NEW_USER"
echo ""

echo "6.4 Analytics场景 - 新用户注册后立即查询（可能找不到，因为缓存了空值）:"
curl -s -w "HTTP状态码: %{http_code}, 能找到用户: " \
  "$PROFILE_SERVICE_URL/api/profiles/analytics/$NEW_USER"
echo ""

# 7. 缓存统计信息
echo "📝 步骤7: Redis 统计信息分析"
echo "========================"

echo "Redis 统计信息:"
docker exec pulse-hub-redis-1 redis-cli INFO stats | grep -E "keyspace_hits|keyspace_misses|used_memory_human"

echo ""
echo "缓存命中率计算："
HITS=$(docker exec pulse-hub-redis-1 redis-cli INFO stats | grep "keyspace_hits" | cut -d: -f2 | tr -d '\r')
MISSES=$(docker exec pulse-hub-redis-1 redis-cli INFO stats | grep "keyspace_misses" | cut -d: -f2 | tr -d '\r')
if [ "$HITS" -gt 0 ] || [ "$MISSES" -gt 0 ]; then
    TOTAL=$((HITS + MISSES))
    if [ "$TOTAL" -gt 0 ]; then
        HIT_RATIO=$(echo "scale=2; $HITS * 100 / $TOTAL" | bc -l 2>/dev/null || echo "计算失败")
        echo "命中次数: $HITS, 未命中次数: $MISSES, 命中率: $HIT_RATIO%"
    fi
fi

echo ""
echo "🎓 PulseHub CRM/CDP 分层缓存策略学习总结"
echo "=========================================="
echo ""
echo "📚 关键学习要点："
echo ""
echo "1. 🎯 业务驱动的缓存策略设计"
echo "   - CRM场景：实时性优先，不缓存空值，确保新用户立即可见"
echo "   - Analytics场景：性能优先，缓存空值，减少重复计算"
echo ""
echo "2. 🔄 缓存策略对业务的影响"
echo "   - 销售人员能立即看到新录入的客户（提升转化率）"
echo "   - 分析师的报表生成更稳定（防止无效查询影响）"
echo ""
echo "3. ⚖️ 权衡取舍的艺术"
echo "   - 实时性 vs 性能"
echo "   - 内存使用 vs 数据库压力"
echo "   - 业务需求 vs 技术限制"
echo ""
echo "4. 🏗️ 分层架构的优势"
echo "   - 不同场景使用不同策略"
echo "   - 便于监控和调试"
echo "   - 支持独立优化"
echo ""
echo "🎯 实际业务应用建议："
echo ""
echo "📱 CRM/销售系统：使用 crm-user-profiles 缓存"
echo "   → 短TTL (10分钟)"
echo "   → 不缓存空值"
echo "   → 确保新客户立即可见"
echo ""
echo "📊 数据分析系统：使用 analytics-user-profiles 缓存"
echo "   → 长TTL (4小时)"
echo "   → 缓存空值"
echo "   → 防止无效查询影响分析任务"
echo ""
echo "⚡ 实时系统：根据具体需求选择策略"
echo "   → 用户行为跟踪：中TTL (30分钟)"
echo "   → 系统配置：长TTL (24小时)"
echo ""
echo "🔧 监控和调试命令："
echo "# 查看CRM缓存："
echo "docker exec pulse-hub-redis-1 redis-cli KEYS \"pulsehub:crm:*\""
echo ""
echo "# 查看Analytics缓存："
echo "docker exec pulse-hub-redis-1 redis-cli KEYS \"pulsehub:analytics:*\""
echo ""
echo "# 清除特定类型缓存："
echo "docker exec pulse-hub-redis-1 redis-cli EVAL \"return redis.call('del', unpack(redis.call('keys', ARGV[1])))\" 0 \"pulsehub:crm:*\""
echo ""
echo "🏆 恭喜！您已经掌握了企业级CRM/CDP缓存策略设计！" 