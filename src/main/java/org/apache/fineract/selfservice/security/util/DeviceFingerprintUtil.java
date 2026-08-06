package org.apache.fineract.selfservice.security.util;

import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.StringJoiner;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

/**
 * Builds a stable device fingerprint from HTTP headers and selected JSON body fields.
 * Does not include password or one-time tokens.
 */
@UtilityClass
public class DeviceFingerprintUtil {

  public static final String BODY_DEVICE_ID = "deviceId";
  public static final String BODY_DEVICE_FINGERPRINT = "deviceFingerprint";
  public static final String BODY_PLATFORM = "platform";
  public static final String BODY_APP_VERSION = "appVersion";

  public record DeviceSignals(
      String fingerprintHash,
      String userAgent,
      String ipAddress,
      String acceptLanguage,
      String deviceLabel) {}

  public static DeviceSignals from(HttpServletRequest request, JsonObject body) {
    String userAgent = header(request, "User-Agent");
    String acceptLanguage = header(request, "Accept-Language");
    String acceptEncoding = header(request, "Accept-Encoding");
    String secFetchMode = header(request, "Sec-Fetch-Mode");
    String contentType = header(request, "Content-Type");
    String ip = resolveClientIp(request);

    String bodyDeviceId = bodyString(body, BODY_DEVICE_ID);
    String bodyFp = bodyString(body, BODY_DEVICE_FINGERPRINT);
    String platform = bodyString(body, BODY_PLATFORM);
    String appVersion = bodyString(body, BODY_APP_VERSION);

    // Canonical material — order matters for stability
    StringJoiner material = new StringJoiner("|");
    material.add(norm(userAgent));
    material.add(norm(acceptLanguage));
    material.add(norm(acceptEncoding));
    material.add(norm(secFetchMode));
    material.add(norm(contentType));
    material.add(norm(ip));
    material.add(norm(bodyDeviceId));
    material.add(norm(bodyFp));
    material.add(norm(platform));
    material.add(norm(appVersion));

    String hash = sha256(material.toString());
    String label = buildLabel(userAgent, platform, ip);

    return new DeviceSignals(hash, truncate(userAgent, 512), ip, truncate(acceptLanguage, 128), label);
  }

  public static String resolveClientIp(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String xff = request.getHeader("X-Forwarded-For");
    if (StringUtils.isNotBlank(xff)) {
      String first = xff.split(",")[0].trim();
      if (StringUtils.isNotBlank(first)) {
        return first;
      }
    }
    String realIp = request.getHeader("X-Real-Ip");
    if (StringUtils.isNotBlank(realIp)) {
      return realIp.trim();
    }
    return request.getRemoteAddr();
  }

  private static String header(HttpServletRequest request, String name) {
    return request != null ? request.getHeader(name) : null;
  }

  private static String bodyString(JsonObject body, String key) {
    if (body == null || !body.has(key) || body.get(key).isJsonNull()) {
      return null;
    }
    try {
      return body.get(key).getAsString();
    } catch (Exception e) {
      return null;
    }
  }

  private static String norm(String v) {
    return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
  }

  private static String truncate(String v, int max) {
    if (v == null) {
      return null;
    }
    return v.length() <= max ? v : v.substring(0, max);
  }

  private static String buildLabel(String userAgent, String platform, String ip) {
    StringJoiner j = new StringJoiner(" / ");
    if (StringUtils.isNotBlank(platform)) {
      j.add(platform);
    }
    if (StringUtils.isNotBlank(userAgent)) {
      j.add(userAgent.length() > 80 ? userAgent.substring(0, 80) : userAgent);
    }
    if (StringUtils.isNotBlank(ip)) {
      j.add(ip);
    }
    String label = j.toString();
    return label.isEmpty() ? "unknown-device" : label;
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to compute device fingerprint", e);
    }
  }
}