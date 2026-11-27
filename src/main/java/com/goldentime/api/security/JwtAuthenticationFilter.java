package com.goldentime.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(authorization.substring(7));
                String tokenType = claims.get("token_type", String.class);
                if (tokenType != null && !"access".equals(tokenType)) {
                    log.warn("Rejected bearer token on {}: unexpected token type", request.getRequestURI());
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                long userId = Long.parseLong(claims.getSubject());
                Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND enabled = TRUE)", Boolean.class, userId);
                if (!Boolean.TRUE.equals(active)) {
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                String role = claims.get("role", String.class);
                var authentication = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException failure) {
                // Log only the failure category; never include the bearer token itself.
                log.warn("Rejected bearer token on {}: {}", request.getRequestURI(), failure.getClass().getSimpleName());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
