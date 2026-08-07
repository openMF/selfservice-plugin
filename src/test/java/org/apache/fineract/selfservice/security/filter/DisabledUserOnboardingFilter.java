package org.apache.fineract.selfservice.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Response;
import org.apache.fineract.onboarding.domain.OnboardingProgressData;
import org.apache.fineract.onboarding.service.SelfServiceOnboardingStepService;
import org.apache.fineract.selfservice.registration.exception.SelfServiceEnrollmentConflictException;
import org.apache.fineract.selfservice.registration.exceptionmapper.SelfServiceEnrollmentConflictExceptionMapper;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@Order(-200) // Runs before Spring Security's default filter chain (which is typically -100)
public class DisabledUserOnboardingFilter extends OncePerRequestFilter {

    private final AppSelfServiceUserRepository appSelfServiceUserRepository;
    private final SelfServiceOnboardingStepService onboardingStepService;
    private final SelfServiceEnrollmentConflictExceptionMapper exceptionMapper;
    private final ObjectMapper objectMapper;

    public DisabledUserOnboardingFilter(
            AppSelfServiceUserRepository appSelfServiceUserRepository,
            SelfServiceOnboardingStepService onboardingStepService,
            SelfServiceEnrollmentConflictExceptionMapper exceptionMapper,
            ObjectMapper objectMapper) {
        this.appSelfServiceUserRepository = appSelfServiceUserRepository;
        this.onboardingStepService = onboardingStepService;
        this.exceptionMapper = exceptionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only intercept the onboarding steps endpoint
        return !path.endsWith("/self/onboarding/steps");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                // Decode Basic Auth to extract the username
                String base64Credentials = authHeader.substring("Basic ".length()).trim();
                byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
                String credentials = new String(credDecoded, StandardCharsets.UTF_8);
                final String[] parts = credentials.split(":", 2);
                String username = parts[0];

                AppSelfServiceUser user = appSelfServiceUserRepository.findAppSelfServiceUserByName(username);
                if (user != null && !user.isEnabled()) {
                    // User exists but is disabled (pending confirmation)
                    SelfServiceEnrollmentConflictException exception = buildDuplicateUsernameConflict(user);
                    
                    // Generate the 409 JAX-RS Response using your existing mapper
                    Response jaxrsResponse = exceptionMapper.toResponse(exception);
                    
                    // Write the response directly to the Servlet response
                    response.setStatus(jaxrsResponse.getStatus());
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    
                    Object entity = jaxrsResponse.getEntity();
                    objectMapper.writeValue(response.getWriter(), entity);
                    
                    return; // Stop the filter chain, do not pass to Spring Security
                }
            } catch (Exception e) {
                // If decoding fails or user lookup fails, fall through to Spring Security 
                // (which will handle it and return a standard 401)
            }
        }

        // Continue to Spring Security and the JAX-RS endpoint
        filterChain.doFilter(request, response);
    }

    private SelfServiceEnrollmentConflictException buildDuplicateUsernameConflict(AppSelfServiceUser existing) {
        Long userId = existing.getId();
        Boolean pendingConfirmation = !existing.isEnabled();
        OnboardingProgressData onboarding = null;

        if (onboardingStepService != null) {
            try {
                onboarding = onboardingStepService.getOrInitProgress(userId);
            } catch (Exception e) {
                // Non-fatal: proceed without onboarding data if service fails
            }
        }

        return new SelfServiceEnrollmentConflictException(
                "error.msg.user.duplicate.username",
                "Username already exists",
                "username",
                userId,
                pendingConfirmation,
                onboarding);
    }
}