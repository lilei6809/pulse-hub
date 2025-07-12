package com.pulsehub.common.config.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 服务发现客户端服务类
 * 
 * 提供服务发现和负载均衡功能，可以动态查找其他服务的实例
 * 配置为有条件加载，可以通过pulsehub.discovery.enabled=false禁用
 */
@Component
@ConditionalOnProperty(name = "pulsehub.discovery.enabled", havingValue = "true", matchIfMissing = true)
public class DiscoveryClientService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryClientService.class);
    
    private final DiscoveryClient discoveryClient;

    @Autowired
    public DiscoveryClientService(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    /**
     * 获取指定服务的所有实例列表
     *
     * @param serviceId 服务ID（服务名称）
     * @return 服务实例列表
     */
    public List<ServiceInstance> getInstances(String serviceId) {
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
        logger.debug("Found {} instance(s) of service: {}", instances.size(), serviceId);
        return instances;
    }

    /**
     * 获取系统中所有已注册的服务ID列表
     *
     * @return 服务ID列表
     */
    public List<String> getServices() {
        List<String> services = discoveryClient.getServices();
        logger.debug("Discovered services: {}", services);
        return services;
    }

    /**
     * 使用简单的负载均衡算法获取一个服务实例
     *
     * @param serviceId 服务ID（服务名称）
     * @return 服务实例URI（如果找到）
     */
    public Optional<URI> getServiceUri(String serviceId) {
        List<ServiceInstance> instances = getInstances(serviceId);
        
        if (instances.isEmpty()) {
            logger.warn("No instances found for service: {}", serviceId);
            return Optional.empty();
        }
        
        // 简单的随机负载均衡
        ServiceInstance instance = instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
        logger.debug("Selected instance: {} for service: {}", instance.getUri(), serviceId);
        
        return Optional.of(instance.getUri());
    }
    
    /**
     * 检查服务是否可用
     *
     * @param serviceId 服务ID（服务名称）
     * @return 如果服务至少有一个实例可用，则返回true
     */
    public boolean isServiceAvailable(String serviceId) {
        boolean available = !getInstances(serviceId).isEmpty();
        logger.debug("Service {} available: {}", serviceId, available);
        return available;
    }
} 