package com.ai.repo.config;

import com.ai.repo.jwt.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Resource
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> body = new HashMap<>();
                    body.put("code", 401);
                    body.put("message", authException.getMessage());
                    body.put("data", null);
                    response.getWriter().write(mapper.writeValueAsString(body));
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**",
                    "/swagger-resources/**", "/webjars/**"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health/readiness").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/avatars/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/playground/shares/{token}",
                    "/api/playground/shares/{token}/landing").permitAll()
                .requestMatchers(
                    "/api/users/login", "/api/users/login/email",
                    "/api/users", "/api/users/refresh-token", "/api/users/auth-login",
                    "/api/users/password/reset-request", "/api/users/password/validate",
                    "/api/oauth/**",
                    "/api/captcha/**",
                    "/api/auth/challenge", "/api/auth/challenge/verify", "/api/auth/challenge/status",
                    "/api/auth/temp-token/**",
                    "/api/agent-policy",
                    "/api/git/**",
                    "/api/skill-repos/uid/**",
                    "/api/skill-repos/shared/**",
                    "/api/repo/**",
                    "/api/agents/uid/**",
                    "/api/comments/uid/**",
                    "/api/memories/uid/**",
                    "/api/notifications/uid/**",
                    "/api/test/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
