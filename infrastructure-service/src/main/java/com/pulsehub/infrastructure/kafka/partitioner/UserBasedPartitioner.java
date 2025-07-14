package com.pulsehub.infrastructure.kafka.partitioner;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class UserBasedPartitioner implements Partitioner {

    // 1. 添加一个 Logger 实例
   private static final Logger logger = LoggerFactory.getLogger(UserBasedPartitioner.class);
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {

        logger.info("✅ UserBasedPartitioner is being used for topic: {}, key: {}" , topic, key);

        // 获取指定 topic 的分区总数
        Integer numPartitions = cluster.partitionCountForTopic(topic);

        if (keyBytes == null) {
            int nextPartition = counter.incrementAndGet() % numPartitions;
            logger.info("Key is null. Using round-robin. Partition: {}", nextPartition);

            // 如果没有 key，我们采用轮询策略 (Round-Robin)
            // 这对于那些不需要顺序保证的事件很有用
            return counter.getAndIncrement() % numPartitions;
        } else {
            // 如果有 key，我们使用 Kafka 内置的 murmur2 哈希算法
            // 这能保证同一个 key 的所有消息都进入同一个分区
            // 注意：这里我们没有重新发明哈希算法，而是复用了 Kafka 的工具
            int partition = Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
            logger.info("Key is present. Using key-based hashing. Partition: {}", partition);
            return Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
        }
    }

    @Override
    public void close() {
        // 在这个简单的实现中，我们没有需要关闭的资源
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // 在这个简单的实现中，我们没有需要配置的项
    }
}