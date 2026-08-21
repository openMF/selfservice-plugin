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
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;

/**
 * Production-ready JMS Configuration for the Self-Service Plugin.
 * Listens to the Fineract external-events topic with a durable subscription
 * so messages are never lost when the consumer is temporarily down.
 */
@Configuration
@EnableJms
@ConditionalOnProperty(
    name = "fineract.events.external.producer.jms.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SelfServiceJmsConfig {

  @Value("${fineract.external.events.jms.concurrency:3-10}")
  private String concurrency;

  @Value("${fineract.external.events.jms.prefetch:1}")
  private int prefetch;

  @Value("${fineract.external.events.jms.recovery-interval:5000}")
  private long recoveryInterval;

  /**
   * Unique client identifier required by ActiveMQ for durable topic subscriptions.
   * Must be stable across restarts of the same logical consumer.
   */
  @Value("${fineract.external.events.jms.client-id:selfservice-plugin-external-events}")
  private String clientId;

  @Bean
  public JmsListenerContainerFactory<?> topicJmsListenerContainerFactory(
      ConnectionFactory connectionFactory,
      DefaultJmsListenerContainerFactoryConfigurer configurer) {

    DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
    configurer.configure(factory, connectionFactory);

    // Topic (pub/sub) mode
    factory.setPubSubDomain(true);

    // Durable subscription – messages published while the consumer is offline are retained.
    // The actual subscription name is supplied by the @JmsListener(subscription = "...") attribute.
    factory.setSubscriptionDurable(true);

    // Dynamic concurrency
    factory.setConcurrency(concurrency);

    // Transacted session → acknowledge only after successful processing
    factory.setSessionTransacted(true);

    // Session cache is safe with concurrent consumers
    factory.setCacheLevelName("CACHE_SESSION");

    factory.setRecoveryInterval(recoveryInterval);

    // ActiveMQ-specific tuning
    if (connectionFactory instanceof ActiveMQConnectionFactory amqFactory) {
      // Client ID is mandatory for durable subscriptions
      amqFactory.setClientID(clientId);

      ActiveMQPrefetchPolicy prefetchPolicy = new ActiveMQPrefetchPolicy();
      prefetchPolicy.setTopicPrefetch(prefetch);
      prefetchPolicy.setQueuePrefetch(prefetch);
      amqFactory.setPrefetchPolicy(prefetchPolicy);

      amqFactory.setOptimizeAcknowledge(true);
    }

    return factory;
  }
}