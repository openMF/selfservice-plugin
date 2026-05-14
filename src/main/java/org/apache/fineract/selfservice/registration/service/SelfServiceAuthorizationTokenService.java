package org.apache.fineract.selfservice.registration.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;

/** Generates self-service authorization tokens and resolves their expiry configuration. */
public class SelfServiceAuthorizationTokenService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final String STRING_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  private static final int MIN_NUMERIC_LENGTH = 6;
  private static final int DEFAULT_NUMERIC_LENGTH = 8;
  private static final int DEFAULT_STRING_LENGTH = 32;
  private static final int MAX_TOKEN_LENGTH = 100;
  private static final String DEFAULT_TOKEN_TYPE = "uuidv7";
  private static final int DEFAULT_EXPIRY_SECONDS = 30;
  private final Environment env;

  /**
   * Creates the token service backed by Spring environment properties.
   *
   * @param env environment providing token type, length, and expiry overrides
   */
  public SelfServiceAuthorizationTokenService(Environment env) {
    this.env = env;
  }

  /**
   * Generates a token using the configured token type.
   *
   * <p>Supported types are {@code numeric}, {@code string}, and the default {@code uuidv7} mode.
   * The default produces high-entropy UUIDv7 tokens; short numeric tokens are opt-in only.
   *
   * @return generated token string
   */
  public String generateToken() {
    String tokenType = resolveTokenType();
    return switch (tokenType) {
      case "string" -> randomString(resolveTokenLength(tokenType));
      case "numeric" -> randomNumericToken(resolveTokenLength(tokenType));
      default -> uuidV7();
    };
  }

  /**
   * Calculates the absolute expiry time for a token created at the supplied timestamp.
   *
   * @param createdAt token creation timestamp
   * @return token expiry timestamp
   */
  public LocalDateTime calculateExpiry(LocalDateTime createdAt) {
    return createdAt.plusSeconds(resolveExpirySeconds());
  }

  /**
   * Resolves the configured token lifetime in seconds.
   *
   * <p>Defaults to {@value #DEFAULT_EXPIRY_SECONDS} seconds when not configured.
   *
   * @return token expiry duration in seconds
   */
  public int resolveExpirySeconds() {
    int expirySeconds =
        env.getProperty(
            "mifos.self.service.token.expiry.time", Integer.class, DEFAULT_EXPIRY_SECONDS);
    if (expirySeconds < 1) {
      throw new IllegalStateException(
          "mifos.self.service.token.expiry.time must be greater than 0");
    }
    return expirySeconds;
  }

  private String resolveTokenType() {
    return StringUtils.defaultIfBlank(
            env.getProperty("mifos.self.service.token.type"), DEFAULT_TOKEN_TYPE)
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  private int resolveTokenLength(String tokenType) {
    int defaultLength =
        "numeric".equals(tokenType) ? DEFAULT_NUMERIC_LENGTH : DEFAULT_STRING_LENGTH;
    int length = env.getProperty("mifos.self.service.token.length", Integer.class, defaultLength);
    length = Math.min(length, MAX_TOKEN_LENGTH);
    if ("numeric".equals(tokenType)) {
      return Math.max(length, MIN_NUMERIC_LENGTH);
    }
    return Math.max(length, 1);
  }

  private String randomNumericToken(int length) {
    StringBuilder token = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      token.append(SECURE_RANDOM.nextInt(10));
    }
    return token.toString();
  }

  private String randomString(int length) {
    StringBuilder token = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      token.append(STRING_ALPHABET.charAt(SECURE_RANDOM.nextInt(STRING_ALPHABET.length())));
    }
    return token.toString();
  }

  private String uuidV7() {
    long unixMillis = System.currentTimeMillis();
    long randA = SECURE_RANDOM.nextLong() & 0x0fffL;
    long randB = SECURE_RANDOM.nextLong();

    long msb = ((unixMillis & 0xffffffffffffL) << 16) | 0x7000L | randA;
    long lsb = (randB & 0x3fffffffffffffffL) | 0x8000000000000000L;

    return new java.util.UUID(msb, lsb).toString();
  }
}
