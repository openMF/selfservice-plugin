package org.apache.fineract.selfservice.security.filter;

import org.springframework.security.authentication.AbstractAuthenticationToken;

public class SelfServiceTokenAuthenticationToken extends AbstractAuthenticationToken {

    private final String token;

    public SelfServiceTokenAuthenticationToken(String token) {
        super(null);
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