package org.apache.fineract.selfservice.notification.config;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQPrefetchPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;

import jakarta.jms.ConnectionFactory;

/**
 * Production-ready JMS Configuration for the Self-Service Plugin.
 * Optimized for high-load environments with proper concurrency, caching, and prefetch settings.
 */
@Configuration
@ConditionalOnProperty(
    name = "fineract.external.events.producer.jms.enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class SelfServiceJmsConfig {

    @Value("${fineract.external.events.jms.concurrency:3-10}")
    private String concurrency;

    @Value("${fineract.external.events.jms.prefetch:10}")
    private int prefetch;

    @Value("${fineract.external.events.jms.recovery-interval:5000}")
    private long recoveryInterval;

    /**
     * Creates a JMS Listener Container Factory configured for Publish/Subscribe (Topics).
     * Optimized for high throughput and reliability in production.
     */
    @Bean
    public JmsListenerContainerFactory<?> topicJmsListenerContainerFactory(
            ConnectionFactory connectionFactory,
            DefaultJmsListenerContainerFactoryConfigurer configurer) {
        
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        
        // Apply default Spring Boot properties
        configurer.configure(factory, connectionFactory);
        
        // 1. CRITICAL: Enable Topic (Pub/Sub) mode
        factory.setPubSubDomain(true);
        
        // 2. HIGH LOAD OPTIMIZATION: Dynamic concurrency (min 3, max 10 consumers)
        factory.setConcurrency(concurrency);
        
        // 3. RELIABILITY: Transacted sessions ensure messages are only acknowledged 
        // upon successful processing. Failures trigger automatic redelivery.
        factory.setSessionTransacted(true);
        
        // 4. PERFORMANCE: Cache the JMS Session to reduce overhead of creating 
        // sessions for each message, while remaining safe for concurrent consumers.
        factory.setCacheLevelName("CACHE_SESSION");
        
        // 5. RESILIENCE: Configure recovery interval for connection drops
        factory.setRecoveryInterval(recoveryInterval);

        // 6. ACTIVE-MQ SPECIFIC OPTIMIZATION: Tune prefetch policy to prevent 
        // a single consumer from hoarding messages, ensuring better load distribution.
        if (connectionFactory instanceof ActiveMQConnectionFactory) {
            ActiveMQConnectionFactory amqFactory = (ActiveMQConnectionFactory) connectionFactory;
            ActiveMQPrefetchPolicy prefetchPolicy = new ActiveMQPrefetchPolicy();
            prefetchPolicy.setTopicPrefetch(prefetch);
            prefetchPolicy.setQueuePrefetch(prefetch);
            amqFactory.setPrefetchPolicy(prefetchPolicy);
            
            // Optional: Enable optimized message dispatch
            amqFactory.setOptimizeAcknowledge(true);
        }
        
        return factory;
    }
}