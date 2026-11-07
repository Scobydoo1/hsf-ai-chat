package com.hsf.aichat.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * 1. CORS: lets the front-end (another origin, e.g. https://your-app.vercel.app) call this API.
 * 2. App password: if APP_PASSWORD is set, every /api call must send the header X-App-Password.
 *    This stops strangers from spending your AI credits once the app is online.
 */
@Configuration
public class WebConfig {

    public static final String PASSWORD_HEADER = "X-App-Password";

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(@Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration cors = new CorsConfiguration();
        List<String> patterns = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        cors.setAllowedOriginPatterns(patterns);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(0); // before the password check, so even a 401 carries CORS headers
        return bean;
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> passwordFilter(@Value("${app.password:}") String password) {
        byte[] expected = password.getBytes(StandardCharsets.UTF_8);
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                boolean open = password.isBlank()
                        || "OPTIONS".equalsIgnoreCase(req.getMethod())
                        || !req.getRequestURI().startsWith("/api/")
                        || req.getRequestURI().equals("/api/status");
                String given = req.getHeader(PASSWORD_HEADER);
                if (open || (given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8)))) {
                    chain.doFilter(req, res);
                    return;
                }
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Wrong or missing app password\"}");
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(1);
        return bean;
    }
}
