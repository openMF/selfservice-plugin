/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.notification.starter;

import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
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
 * Production-ready JMS Configuration for the Self-Service Plugin. Optimized for high-load
 * environments with proper concurrency, caching, and prefetch settings.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
    name = {
      "fineract.events.external.producer.jms.enabled",
      "fineract.external.events.producer.jms.enabled"
    },
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
   * Creates a JMS Listener Container Factory configured for Publish/Subscribe (Topics). Optimized
   * for high throughput and reliability in production.
   */
  @Bean
  public JmsListenerContainerFactory<?> topicJmsListenerContainerFactory(
      ConnectionFactory connectionFactory,
      DefaultJmsListenerContainerFactoryConfigurer configurer) {

    log.info(
        "Initializing Self-Service JMS Topic Listener Container Factory "
            + "(concurrency={}, prefetch={}, recoveryInterval={}ms)",
        concurrency,
        prefetch,
        recoveryInterval);

    DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

    // Apply default Spring Boot properties
    configurer.configure(factory, connectionFactory);
    log.info("Applied default Spring Boot JMS configuration via DefaultJmsListenerContainerFactoryConfigurer");

    // 1. CRITICAL: Enable Topic (Pub/Sub) mode
    factory.setPubSubDomain(true);
    log.info("JMS domain set to TOPIC (pub/sub) – required for destination 'fineract.external.events'");

    // 2. HIGH LOAD OPTIMIZATION: Dynamic concurrency (min 3, max 10 consumers)
    factory.setConcurrency(concurrency);
    log.info("Listener concurrency configured to '{}'", concurrency);

    // 3. RELIABILITY: Transacted sessions ensure messages are only acknowledged
    // upon successful processing. Failures trigger automatic redelivery.
    factory.setSessionTransacted(true);
    log.info("Session transactions enabled – failed messages will be redelivered");

    // 4. PERFORMANCE: Cache the JMS Session to reduce overhead of creating
    // sessions for each message, while remaining safe for concurrent consumers.
    factory.setCacheLevelName("CACHE_SESSION");
    log.info("Session cache level set to CACHE_SESSION");

    // 5. RESILIENCE: Configure recovery interval for connection drops
    factory.setRecoveryInterval(recoveryInterval);
    log.info("Connection recovery interval set to {} ms", recoveryInterval);

    // 6. ACTIVE-MQ SPECIFIC OPTIMIZATION: Tune prefetch policy to prevent
    // a single consumer from hoarding messages, ensuring better load distribution.
    if (connectionFactory instanceof ActiveMQConnectionFactory amqFactory) {
      log.info(
          "Detected ActiveMQConnectionFactory (brokerURL={}, clientID={}) – applying ActiveMQ-specific tuning",
          amqFactory.getBrokerURL(),
          amqFactory.getClientID());

      ActiveMQPrefetchPolicy prefetchPolicy = new ActiveMQPrefetchPolicy();
      prefetchPolicy.setTopicPrefetch(prefetch);
      prefetchPolicy.setQueuePrefetch(prefetch);
      amqFactory.setPrefetchPolicy(prefetchPolicy);
      log.info("ActiveMQ prefetch policy applied (topicPrefetch={}, queuePrefetch={})", prefetch, prefetch);

      // Optional: Enable optimized message dispatch
      amqFactory.setOptimizeAcknowledge(true);
      log.debug("ActiveMQ optimizeAcknowledge enabled");
    } else {
      log.warn(
          "ConnectionFactory is of type {} – ActiveMQ-specific optimizations (prefetch, optimizeAcknowledge) were skipped. "
              + "Durable topic consumption may still work but performance tuning is limited.",
          connectionFactory.getClass().getName());
    }

    log.info(
        "Self-Service JMS Topic Listener Container Factory successfully created and ready. "
            + "Consumers will attach to the topic configured by the @JmsListener destination.");

    return factory;
  }
}