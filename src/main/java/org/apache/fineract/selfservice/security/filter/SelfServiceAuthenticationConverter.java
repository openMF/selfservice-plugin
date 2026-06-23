package org.apache.fineract.selfservice.security.filter;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

public class SelfServiceAuthenticationConverter implements AuthenticationConverter {

  @Override
  public Authentication convert(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.toLowerCase().startsWith("basic ")) {
      return null;
    }

    String base64Token = header.substring(6).trim();
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }

    int delim = decoded.indexOf(":");
    if (delim == -1) {
      // No colon found; assume it's a self-service authentication token
      return new SelfServiceTokenAuthenticationToken(decoded);
    }

    // Standard username:password format
    return UsernamePasswordAuthenticationToken.unauthenticated(
        decoded.substring(0, delim), decoded.substring(delim + 1));
  }
}
