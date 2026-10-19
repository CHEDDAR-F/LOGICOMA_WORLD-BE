package com.ai.repo.release;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;

/** Queue requests during the short drain phase; do not replay requests or bypass auth. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ReleaseGateFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean health = "GET".equals(request.getMethod()) &&
                ("/actuator/health".equals(path) || "/actuator/health/readiness".equals(path));
        try {
            if (health || ReleaseActivation.awaitActive(Duration.ofSeconds(45))) {
                chain.doFilter(request, response);
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        response.setStatus(503);
        response.setHeader("Retry-After", "2");
        response.setHeader("Cache-Control", "no-store");
    }
}
