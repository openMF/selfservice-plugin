/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.infrastructure.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.apache.fineract.infrastructure.configuration.data.SMTPCredentialsData;
import org.apache.fineract.infrastructure.configuration.service.ExternalServicesPropertiesReadPlatformService;
import org.apache.fineract.infrastructure.core.domain.EmailDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.BadSqlGrammarException;

@ExtendWith(MockitoExtension.class)
class SelfServicePluginEmailServiceTest {

  @Mock private ExternalServicesPropertiesReadPlatformService externalServicesReadPlatformService;
  @Mock private Environment env;

  private SelfServicePluginEmailService service;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger logger;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    service = new SelfServicePluginEmailService(externalServicesReadPlatformService, env);

    // Attach Logback ListAppender to capture log output
    logger = (Logger) LoggerFactory.getLogger(SelfServicePluginEmailService.class);
    originalLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logAppender);
    logger.setLevel(originalLevel);
    logAppender.stop();
  }

  // ---- resolveSmtpCredentials tests ----

  @Test
  void resolveSmtpCredentials_returnsDbCredentials_whenTableExists() {
    SMTPCredentialsData expected =
        new SMTPCredentialsData()
            .setHost("smtp.example.com")
            .setPort("587")
            .setFromEmail("noreply@example.com");
    when(externalServicesReadPlatformService.getSMTPCredentials()).thenReturn(expected);

    SMTPCredentialsData result = service.resolveSmtpCredentials();

    assertEquals(expected, result);
    assertTrue(logAppender.list.isEmpty(), "No log output expected when DB table is available");
  }

  @Test
  void resolveSmtpCredentials_fallsBackToSpringProperties_whenDbThrowsDataAccessException() {
    when(externalServicesReadPlatformService.getSMTPCredentials())
        .thenThrow(
            new BadSqlGrammarException(
                "test", "SELECT ...", new java.sql.SQLException("table not found")));
    when(env.getProperty("fineract.selfservice.smtp.host")).thenReturn("smtp.fallback.com");
    when(env.getProperty("fineract.selfservice.smtp.from-email"))
        .thenReturn("fallback@example.com");
    when(env.getProperty("fineract.selfservice.smtp.port", "587")).thenReturn("465");
    when(env.getProperty("fineract.selfservice.smtp.username", "")).thenReturn("user");
    when(env.getProperty("fineract.selfservice.smtp.password", "")).thenReturn("pass");
    when(env.getProperty("fineract.selfservice.smtp.from-name", "")).thenReturn("Fallback App");
    when(env.getProperty("fineract.selfservice.smtp.use-tls", Boolean.class, true))
        .thenReturn(true);

    SMTPCredentialsData result = service.resolveSmtpCredentials();

    assertNotNull(result);
    assertEquals("smtp.fallback.com", result.getHost());
    assertEquals("465", result.getPort());
    assertEquals("fallback@example.com", result.getFromEmail());
    assertEquals("user", result.getUsername());
    assertEquals("pass", result.getPassword());
    assertEquals("Fallback App", result.getFromName());
    assertTrue(result.isUseTLS());
  }

  @Test
  void resolveSmtpCredentials_throwsSmtpConfigUnavailable_whenBothSourcesMissing() {
    when(externalServicesReadPlatformService.getSMTPCredentials())
        .thenThrow(
            new BadSqlGrammarException(
                "test", "SELECT ...", new java.sql.SQLException("table not found")));
    when(env.getProperty("fineract.selfservice.smtp.host")).thenReturn(null);
    when(env.getProperty("fineract.selfservice.smtp.from-email")).thenReturn(null);

    SmtpConfigurationUnavailableException ex =
        assertThrows(
            SmtpConfigurationUnavailableException.class, () -> service.resolveSmtpCredentials());

    assertTrue(ex.getMessage().contains("c_external_service_properties"));
    assertTrue(ex.getMessage().contains("fineract.selfservice.smtp.host"));
    assertNotNull(ex.getCause(), "Original DataAccessException should be preserved as cause");
  }

  @Test
  void resolveSmtpCredentials_throwsSmtpConfigUnavailable_whenHostMissingButFromEmailPresent() {
    when(externalServicesReadPlatformService.getSMTPCredentials())
        .thenThrow(
            new BadSqlGrammarException(
                "test", "SELECT ...", new java.sql.SQLException("table not found")));
    when(env.getProperty("fineract.selfservice.smtp.host")).thenReturn("  ");
    when(env.getProperty("fineract.selfservice.smtp.from-email")).thenReturn("ok@example.com");

    assertThrows(
        SmtpConfigurationUnavailableException.class, () -> service.resolveSmtpCredentials());
  }

  @Test
  void resolveSmtpCredentials_throwsSmtpConfigUnavailable_whenFromEmailMissingButHostPresent() {
    when(externalServicesReadPlatformService.getSMTPCredentials())
        .thenThrow(
            new BadSqlGrammarException(
                "test", "SELECT ...", new java.sql.SQLException("table not found")));
    when(env.getProperty("fineract.selfservice.smtp.host")).thenReturn("smtp.ok.com");
    when(env.getProperty("fineract.selfservice.smtp.from-email")).thenReturn("");

    assertThrows(
        SmtpConfigurationUnavailableException.class, () -> service.resolveSmtpCredentials());
  }

  // ---- Log-once fallback behavior ----

  @Test
  void resolveSmtpCredentials_logsWarnOnFirstFallback_thenDebugOnSubsequent() {
    when(externalServicesReadPlatformService.getSMTPCredentials())
        .thenThrow(
            new BadSqlGrammarException(
                "test", "SELECT ...", new java.sql.SQLException("table not found")));
    when(env.getProperty("fineract.selfservice.smtp.host")).thenReturn("smtp.fb.com");
    when(env.getProperty("fineract.selfservice.smtp.from-email")).thenReturn("fb@example.com");
    when(env.getProperty("fineract.selfservice.smtp.port", "587")).thenReturn("587");
    when(env.getProperty("fineract.selfservice.smtp.username", "")).thenReturn("");
    when(env.getProperty("fineract.selfservice.smtp.password", "")).thenReturn("");
    when(env.getProperty("fineract.selfservice.smtp.from-name", "")).thenReturn("");
    when(env.getProperty("fineract.selfservice.smtp.use-tls", Boolean.class, true))
        .thenReturn(true);

    // First call — should log WARN
    service.resolveSmtpCredentials();
    // Second call — should log DEBUG
    service.resolveSmtpCredentials();

    List<ILoggingEvent> logs = logAppender.list;
    assertEquals(2, logs.size(), "Expected exactly 2 log events");

    ILoggingEvent firstLog = logs.get(0);
    assertEquals(Level.WARN, firstLog.getLevel());
    assertTrue(firstLog.getFormattedMessage().contains("falling back to Spring properties"));
    assertTrue(firstLog.getFormattedMessage().contains("Further occurrences"));

    ILoggingEvent secondLog = logs.get(1);
    assertEquals(Level.DEBUG, secondLog.getLevel());
    assertTrue(secondLog.getFormattedMessage().contains("using Spring properties fallback"));
  }

  // ---- sendDefinedEmail integration with fallback ----

  @Test
  void sendDefinedEmail_throwsSmtpConfigUnavailable_whenNoSmtpSourceAvailable() {
    when(externalServicesReadPlatformService.getSMTPCredentials())
        .thenThrow(
            new BadSqlGrammarException(
                "test", "SELECT ...", new java.sql.SQLException("table not found")));
    when(env.getProperty("fineract.selfservice.smtp.host")).thenReturn(null);
    when(env.getProperty("fineract.selfservice.smtp.from-email")).thenReturn(null);

    EmailDetail emailDetail = new EmailDetail("Subject", "Body", "to@example.com", "Recipient");

    PlatformEmailSendException exception =
        assertThrows(PlatformEmailSendException.class, () -> service.sendDefinedEmail(emailDetail));
    assertTrue(exception.getCause() instanceof SmtpConfigurationUnavailableException);
  }

  @Test
  void sendFormattedEmail_throwsSmtpConfigUnavailable_whenNoSmtpSourceAvailable() {
    when(externalServicesReadPlatformService.getSMTPCredentials())
        .thenThrow(
            new BadSqlGrammarException(
                "test", "SELECT ...", new java.sql.SQLException("table not found")));
    when(env.getProperty("fineract.selfservice.smtp.host")).thenReturn(null);
    when(env.getProperty("fineract.selfservice.smtp.from-email")).thenReturn(null);

    EmailDetail emailDetail =
        new EmailDetail("Subject", "<html>Body</html>", "to@example.com", "Recipient");

    PlatformEmailSendException exception =
        assertThrows(
            PlatformEmailSendException.class, () -> service.sendFormattedEmail(emailDetail));
    assertTrue(exception.getCause() instanceof SmtpConfigurationUnavailableException);
  }
}
