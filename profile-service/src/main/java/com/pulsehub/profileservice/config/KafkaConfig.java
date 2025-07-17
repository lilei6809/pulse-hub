package com.pulsehub.profileservice.config;


import com.pulsehub.common.proto.DeadLetterEvent;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {


    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;


    /**
     * 为发送到 DLQ 的 Protobuf 消息配置生产者工厂。
     * key 是 string,  value 是 DeadLetterEvent
     *
     * 在本模块中的生产者使用这个配置
     */
    @Bean
    public ProducerFactory<String, DeadLetterEvent> dlqProducerFactory() {
        Map<String, Object> props = new HashMap<>();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 关键：使用 KafkaProtobufSerializer 来序列化我们的 Protobuf Value
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);

        // 开启幂等性，确保发送到 DLQ 的消息本身不会因为重试而重复
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        return new DefaultKafkaProducerFactory<>(props);
    }


    /**
     * 创建一个专门用于向 DLQ 发送消息的 KafkaTemplate。
     * 它使用了上面定义的 dlqProducerFactory。
     */
    @Bean
    public KafkaTemplate<String, DeadLetterEvent> dlqKafkaTemplate() {
        return new KafkaTemplate<>(dlqProducerFactory());
    }

    /**
     * 创建“次品处理专家”：DeadLetterPublishingRecoverer。
     *  当重试耗尽后，它会被调用。
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<String, DeadLetterEvent> dlqKafkaTemplate){
        // 它会把失败的消息发送到名为 "originalTopic.DLT" 的 Topic。
        //  DLT = Dead Letter Topic
        return new DeadLetterPublishingRecoverer(dlqKafkaTemplate);
    }

    /**
     * 创建并配置我们的“监工”：DefaultErrorHandler。
     */
    @Bean
    public DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer){
        // 定义重试策略：重试2次，每次间隔1秒。总共会尝试 1 + 2 = 3 次
        FixedBackOff fixedBackOff = new FixedBackOff(1000L, 2L);

        // 创建监工，并告诉他“次品”应该交给谁处理
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, fixedBackOff);

        //  我们还可以告诉监工，哪些异常不需要重试，直接送进DLQ。
        //  比如，反序列化失败，或者消息格式校验失败，这种错误重试多少次都没用。
        //  errorHandler.addNotRetryableExceptions(DeserializationException.class, MessageValidationException.class);
        errorHandler.addNotRetryableExceptions(DeserializationException.class);

        return errorHandler;
    }
}

