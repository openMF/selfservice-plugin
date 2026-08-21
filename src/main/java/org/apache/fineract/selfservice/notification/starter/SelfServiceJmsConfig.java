/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
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
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.SimpleMessageConverter;
import org.springframework.util.StringUtils;

/**
 * Production-ready JMS configuration for the Self-Service Plugin.
 * Supports durable topic subscriptions (required for reliable consumption of
 * fineract.external.events) and is fully multi-tenant aware via the listener.
 */
@Configuration
/*
@ConditionalOnProperty(
    name = {
      "fineract.events.external.producer.jms.enabled",
      "fineract.external.events.producer.jms.enabled"
    },
    havingValue = "true",
    matchIfMissing = false)*/
public class SelfServiceJmsConfig {

  @Value("${fineract.external.events.jms.concurrency:3-10}")
  private String concurrency;

  @Value("${fineract.external.events.jms.prefetch:1}")
  private int prefetch;

  @Value("${fineract.external.events.jms.recovery-interval:5000}")
  private long recoveryInterval;

  /**
   * Client ID used for durable subscriptions. Matches the env var supplied in docker-compose.
   * Must be unique per consumer instance.
   */
  @Value("${fineract.external.events.jms.client-id:selfservice-plugin-external-events}")
  private String clientId;

  /**
   * Creates a JMS Listener Container Factory configured for Publish/Subscribe (Topics)
   * with durable subscription support.
   */
  @Bean
  public JmsListenerContainerFactory<?> topicJmsListenerContainerFactory(
      ConnectionFactory connectionFactory,
      DefaultJmsListenerContainerFactoryConfigurer configurer) {

    DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
    configurer.configure(factory, connectionFactory);

    // Topic (pub/sub) mode – mandatory for fineract.external.events
    factory.setPubSubDomain(true);

    // Durable subscription so messages are retained when the consumer is offline
    factory.setSubscriptionDurable(true);
    if (StringUtils.hasText(clientId)) {
      factory.setClientId(clientId);
    }

    // Dynamic concurrency
    factory.setConcurrency(concurrency);

    // Transacted sessions → automatic redelivery on failure
    factory.setSessionTransacted(true);

    // Session caching (safe with concurrent consumers)
    factory.setCacheLevelName("CACHE_SESSION");

    // Recovery on broker disconnect
    factory.setRecoveryInterval(recoveryInterval);

    // ActiveMQ-specific tuning
    if (connectionFactory instanceof ActiveMQConnectionFactory amqFactory) {
      // Ensure the same clientId is also set on the underlying factory
      if (StringUtils.hasText(clientId) && !StringUtils.hasText(amqFactory.getClientID())) {
        amqFactory.setClientID(clientId);
      }

      ActiveMQPrefetchPolicy prefetchPolicy = new ActiveMQPrefetchPolicy();
      prefetchPolicy.setTopicPrefetch(prefetch);
      prefetchPolicy.setQueuePrefetch(prefetch);
      amqFactory.setPrefetchPolicy(prefetchPolicy);
      amqFactory.setOptimizeAcknowledge(true);
    }

    return factory;
  }

  /**
   * Simple converter – the listener works with raw BytesMessage / TextMessage
   * (Avro MessageV1). Override if a custom converter is required later.
   */
  @Bean
  public MessageConverter jmsMessageConverter() {
    return new SimpleMessageConverter();
  }
}