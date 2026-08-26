package ipl.pdms.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Filter that validates JWT tokens for each request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtTokenValidator jwtTokenValidator;
    private final SecurityEventService securityEventService;
    private final java.util.concurrent.ConcurrentMap<String, Integer> failedAttempts = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractTokenFromRequest(request);

        // If a token is provided, it must be valid
        if (token != null) {
            if (jwtTokenValidator.validateToken(token)) {
                var claims = jwtTokenValidator.extractClaims(token);
                if (claims != null) {
                    String subject = claims.getSubject();

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    subject,
                                    null,
                                    Collections.emptyList()
                            );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    failedAttempts.remove(getIpAddress(request));
                    log.trace("JWT authentication successful for subject: {}", subject);
                } else {
                    log.warn("Failed to extract claims from JWT token");
                    securityEventService.logFailedLogin("Claims extraction failed", null, request);
                    recordFailedAttempt(request);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Invalid token\"}");
                    return;
                }
            } else {
                var claims = jwtTokenValidator.extractClaims(token);
                String userId = claims != null ? claims.getSubject() : null;
                String reason = (claims != null && claims.getExpiration() != null && claims.getExpiration().before(new java.util.Date()))
                        ? "Expired token" : "Invalid token";
                securityEventService.logFailedLogin(reason, userId, request);
                recordFailedAttempt(request);
                log.warn("Invalid JWT token provided: {}", reason);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid token\"}");
                return;
            }
        }
        // If no token is provided, let Spring Security handle it (will return 403 or 401 based on configuration)

        filterChain.doFilter(request, response);
    }

    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private String getIpAddress(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private void recordFailedAttempt(HttpServletRequest request) {
        String ip = getIpAddress(request);
        int count = failedAttempts.merge(ip, 1, Integer::sum);
        if (count >= 5) {
            securityEventService.logSuspiciousAccess("Multiple failed attempts", null,
                    java.util.Map.of("ipAddress", ip, "failedAttempts", count), request);
        }
    }

}


