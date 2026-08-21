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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.util.StringUtils;

/**
 * Self-contained JMS configuration for the Self-Service Plugin.
 *
 * <p>Creates its own ActiveMQ {@link ConnectionFactory} so the durable topic consumer does not
 * depend on Fineract’s internal producer beans. This guarantees that a consumer always appears on
 * the topic {@code fineract.external.events}.
 */
@Configuration
@EnableJms
@ConditionalOnProperty(
    name = {
      "fineract.events.external.producer.jms.enabled",
      "fineract.external.events.producer.jms.enabled"
    },
    havingValue = "true",
    matchIfMissing = false)
public class SelfServiceJmsConfig {

  /** Broker URL used by Fineract’s external-event producer (same property). */
  @Value("${fineract.events.external.producer.jms.broker-url:tcp://localhost:61616}")
  private String brokerUrl;

  @Value("${fineract.events.external.producer.jms.broker-username:}")
  private String brokerUsername;

  @Value("${fineract.events.external.producer.jms.broker-password:}")
  private String brokerPassword;

  /**
   * Client-ID for the durable subscription. Must be unique per JVM instance.
   * Matches the env-var you already set in docker-compose.
   */
  @Value("${fineract.external.events.jms.client-id:selfservice-plugin-external-events}")
  private String clientId;

  @Value("${fineract.external.events.jms.concurrency:3-10}")
  private String concurrency;

  @Value("${fineract.external.events.jms.prefetch:1}")
  private int prefetch;

  @Value("${fineract.external.events.jms.recovery-interval:5000}")
  private long recoveryInterval;

  /**
   * Dedicated ConnectionFactory for the self-service durable topic consumer.
   */
  @Bean(name = "selfServiceJmsConnectionFactory")
  public ConnectionFactory selfServiceJmsConnectionFactory() {
    ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory();
    factory.setBrokerURL(brokerUrl);

    if (StringUtils.hasText(brokerUsername)) {
      factory.setUserName(brokerUsername);
      factory.setPassword(brokerPassword);
    }

    // Mandatory for durable subscriptions
    if (StringUtils.hasText(clientId)) {
      factory.setClientID(clientId);
    }

    ActiveMQPrefetchPolicy prefetchPolicy = new ActiveMQPrefetchPolicy();
    prefetchPolicy.setTopicPrefetch(prefetch);
    prefetchPolicy.setQueuePrefetch(prefetch);
    factory.setPrefetchPolicy(prefetchPolicy);
    factory.setOptimizeAcknowledge(true);

    // Fail fast if the broker is unreachable at startup
    factory.setConnectResponseTimeout(10_000);

    return factory;
  }

  /**
   * Topic-mode listener-container factory with durable-subscription support.
   * This is the bean referenced by {@code @JmsListener(containerFactory = "...")}.
   */
  @Bean(name = "topicJmsListenerContainerFactory")
  public JmsListenerContainerFactory<?> topicJmsListenerContainerFactory(
      ConnectionFactory selfServiceJmsConnectionFactory) {

    DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
    factory.setConnectionFactory(selfServiceJmsConnectionFactory);

    // Pub/Sub (topic)
    factory.setPubSubDomain(true);

    // Durable subscription – messages are retained until this consumer acknowledges them
    factory.setSubscriptionDurable(true);
    if (StringUtils.hasText(clientId)) {
      factory.setClientId(clientId);
    }

    factory.setConcurrency(concurrency);
    factory.setSessionTransacted(true);
    factory.setCacheLevelName("CACHE_SESSION");
    factory.setRecoveryInterval(recoveryInterval);

    // Make sure the container starts automatically
    factory.setAutoStartup(true);

    return factory;
  }
}