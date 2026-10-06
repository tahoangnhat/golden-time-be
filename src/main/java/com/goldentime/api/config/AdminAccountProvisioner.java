package com.goldentime.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/** Creates an initial admin only when an operator explicitly supplies credentials. */
@Component
public class AdminAccountProvisioner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String fullName;
    private final boolean resetPassword;

    public AdminAccountProvisioner(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                   @Value("${app.bootstrap.admin-email:}") String email,
                                   @Value("${app.bootstrap.admin-password:}") String password,
                                   @Value("${app.bootstrap.admin-name:Administrator}") String fullName,
                                   @Value("${app.bootstrap.admin-reset-password:false}") boolean resetPassword) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.fullName = fullName == null || fullName.isBlank() ? "Administrator" : fullName.trim();
        this.resetPassword = resetPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isBlank()) return;
        if (email.isBlank() || password.length() < 12) {
            throw new IllegalStateException("Set both ADMIN_BOOTSTRAP_EMAIL and ADMIN_BOOTSTRAP_PASSWORD (at least 12 characters), or leave both unset.");
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class, email);
        if (existing != null && existing > 0) {
            String role = jdbc.queryForObject("SELECT role FROM users WHERE LOWER(email) = ?", String.class, email);
            if (!"ADMIN".equals(role)) throw new IllegalStateException("Bootstrap email already belongs to a non-admin account.");
            if (resetPassword) {
                jdbc.update("""
                        UPDATE users
                        SET password_hash = ?, full_name = ?, enabled = TRUE
                        WHERE LOWER(email) = ?
                        """, passwordEncoder.encode(password), fullName, email);
                jdbc.update("""
                        UPDATE refresh_tokens SET revoked_at = NOW()
                        WHERE user_id = (SELECT id FROM users WHERE LOWER(email) = ?)
                          AND revoked_at IS NULL
                        """, email);
            }
            return;
        }
        jdbc.update("""
                INSERT INTO users (email, password_hash, full_name, role, enabled)
                VALUES (?, ?, ?, 'ADMIN', TRUE)
                """, email, passwordEncoder.encode(password), fullName);
    }
}
