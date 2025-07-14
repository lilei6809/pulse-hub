package com.pulsehub.infrastructure.kafka.partitioner;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.utils.Utils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Topic-Aware Partitioner
 *
 * This partitioner implements different strategies based on the target topic name.
 * It acts as a central routing logic for all producer partitioning needs.
 *
 * Strategies:
 * 1.  dead-letter-queue: All messages go to partition 0 for strict chronological analysis.
 * 2.  user-activity-events, profile-updates: Messages are partitioned by key (e.g., userId) to ensure
 *     in-order processing for a specific user. If no key is present, it falls back to round-robin.
 * 3.  Default (for all other topics): Uses a round-robin strategy to ensure even distribution
 *     of messages across partitions, maximizing throughput.
 */
@Slf4j
public class TopicAwarePartitioner implements Partitioner {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
        Integer numPartitions = cluster.partitionCountForTopic(topic);
        if (numPartitions == null || numPartitions == 0) {
            // Fallback in case topic info is not available
            return 0;
        }

        // Strategy 1: Dead-Letter Queue
        if ("dead-letter-queue".equals(topic)) {
            log.debug("Topic is [{}]. Using fixed partition [0] for strict ordering.", topic);
            return 0;
        }

        // Strategy 2: Key-based topics
        if ("user-activity-events".equals(topic) || "profile-updates".equals(topic)) {
            if (keyBytes == null) {
                log.debug("Topic is [{}], but key is null. Falling back to round-robin.", topic);
                return partitionByRoundRobin(numPartitions);
            } else {
                log.debug("Topic is [{}]. Using key-based partitioning.", topic);
                return partitionByKey(keyBytes, numPartitions);
            }
        }


        // 将来需要增加 topic 以及对应的分区逻辑直接在这个地方加
//        if(.....){}

        // Strategy 3: Default (Round-Robin for all other topics)
        log.debug("Topic is [{}]. Using default round-robin partitioning.", topic);
        return partitionByRoundRobin(numPartitions);
    }

    /**
     * Partitions based on the hash of the key.
     * Ensures that messages with the same key go to the same partition.
     */
    private int partitionByKey(byte[] keyBytes, int numPartitions) {
        return Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
    }

    /**
     * Partitions in a round-robin fashion.
     * Distributes messages evenly across partitions.
     */
    private int partitionByRoundRobin(int numPartitions) {
        return counter.getAndIncrement() % numPartitions;
    }

    @Override
    public void close() {
        // No resources to close
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // No configuration needed
    }
}