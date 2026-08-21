package org.apache.fineract.selfservice.notification.starter;

import jakarta.jms.ConnectionFactory;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQPrefetchPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;

/**
 * Production-ready JMS Configuration for the Self-Service Plugin.
 * Configured for high-load environments with proper durable subscriptions and prefetch settings.
 */
@Configuration
@ConditionalOnProperty(
    name = "fineract.external.events.producer.jms.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SelfServiceJmsConfig {

  @Value("${fineract.external.events.jms.prefetch:1}")
  private int prefetch;

  @Value("${fineract.external.events.jms.recovery-interval:5000}")
  private long recoveryInterval;

  @Value("${fineract.external.events.jms.client-id:#{null}}")
  private String clientId;

  @Value("${fineract.external.events.jms.subscription-name:#{null}}")
  private String subscriptionName;

  @Bean
  public JmsListenerContainerFactory<?> topicJmsListenerContainerFactory(
      ConnectionFactory connectionFactory,
      DefaultJmsListenerContainerFactoryConfigurer configurer) {

    DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
    configurer.configure(factory, connectionFactory);

    // 1. CRITICAL: Enable Topic (Pub/Sub) mode
    factory.setPubSubDomain(true);

    // 2. CRITICAL: Enable Durable Topic Subscription to prevent message loss
    if (clientId != null && !clientId.trim().isEmpty() 
        && subscriptionName != null && !subscriptionName.trim().isEmpty()) {
      factory.setSubscriptionDurable(true);
      factory.setClientId(clientId);
      factory.setSubscriptionName(subscriptionName);
      // ActiveMQ 5.x requires concurrency=1 for durable topics with a single clientId
      factory.setConcurrency("1");
    } else {
      factory.setConcurrency("3-10");
    }

    // 3. RELIABILITY: Transacted sessions ensure messages are only acknowledged
    // upon successful processing. Failures trigger automatic redelivery.
    factory.setSessionTransacted(true);

    // 4. PERFORMANCE: CACHE_CONNECTION is preferred for durable topics with clientId
    factory.setCacheLevelName("CACHE_CONNECTION");

    // 5. RESILIENCE: Configure recovery interval for connection drops
    factory.setRecoveryInterval(recoveryInterval);

    // 6. ACTIVE-MQ SPECIFIC OPTIMIZATION
    if (connectionFactory instanceof ActiveMQConnectionFactory) {
      ActiveMQConnectionFactory amqFactory = (ActiveMQConnectionFactory) connectionFactory;
      ActiveMQPrefetchPolicy prefetchPolicy = new ActiveMQPrefetchPolicy();
      prefetchPolicy.setTopicPrefetch(prefetch);
      prefetchPolicy.setQueuePrefetch(prefetch);
      amqFactory.setPrefetchPolicy(prefetchPolicy);
      amqFactory.setOptimizeAcknowledge(true);
    }

    return factory;
  }
}