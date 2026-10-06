package com.goldentime.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldentime.api.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter, ObjectMapper mapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(response.getOutputStream(), Map.of(
                            "timestamp", Instant.now().toString(),
                            "status", 401,
                            "message", "Vui lòng đăng nhập để tiếp tục."));
                }).accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(response.getOutputStream(), Map.of(
                            "timestamp", Instant.now().toString(),
                            "status", 403,
                            "message", "Tài khoản không có quyền thực hiện thao tác này."));
                }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/logout", "/api/business/applications", "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/uploads/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/fruits", "/api/fruits/**", "/api/products", "/api/shops", "/api/shops/*/reviews", "/api/market/prices", "/api/trace/**", "/api/articles", "/api/articles/**", "/api/updates").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/shop/**").hasRole("SHOP_OWNER")
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/scans", "/api/scans/**", "/api/orders", "/api/orders/**", "/api/reviews", "/api/reviews/**").hasRole("USER")
                        .anyRequest().denyAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/uploads/**", configuration);
        return source;
    }
}
