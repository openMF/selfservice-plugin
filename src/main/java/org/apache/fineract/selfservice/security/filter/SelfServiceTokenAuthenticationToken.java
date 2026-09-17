package org.apache.fineract.selfservice.security.filter;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

public class SelfServiceTokenAuthenticationToken extends AbstractAuthenticationToken {

  private final String token;

  public SelfServiceTokenAuthenticationToken(String token) {
    // Cast so the Collection<? extends GrantedAuthority> ctor is chosen, not the Builder ctor
    super((java.util.Collection<? extends GrantedAuthority>) null);
    this.token = token;
    setAuthenticated(false);
  }

  @Override
  public Object getCredentials() {
    return token;
  }

  @Override
  public Object getPrincipal() {
    return token;
  }
}
