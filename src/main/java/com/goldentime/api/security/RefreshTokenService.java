package com.goldentime.api.security;

import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final long lifetimeSeconds;

    public RefreshTokenService(JdbcTemplate jdbc,
                               @Value("${app.jwt.refresh-token-days:14}") long lifetimeDays) {
        this.jdbc = jdbc;
        if (lifetimeDays < 1) throw new IllegalArgumentException("Refresh token lifetime must be at least one day.");
        this.lifetimeSeconds = Duration.ofDays(lifetimeDays).toSeconds();
    }

    public Session issue(long userId) {
        String rawToken = newToken();
        Instant expiresAt = Instant.now().plusSeconds(lifetimeSeconds);
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                userId, hash(rawToken), Timestamp.from(expiresAt));
        return new Session(rawToken, expiresAt);
    }

    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw ApiException.unauthorized("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        RefreshRow current;
        try {
            current = jdbc.queryForObject("""
                    SELECT user_id, expires_at, revoked_at
                    FROM refresh_tokens WHERE token_hash = ? FOR UPDATE
                    """, (rs, row) -> new RefreshRow(rs.getLong("user_id"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()), hash(rawToken));
        } catch (EmptyResultDataAccessException missing) {
            throw ApiException.unauthorized("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        }
        Instant now = Instant.now();
        if (current.revokedAt() != null || !current.expiresAt().isAfter(now)) {
            throw ApiException.unauthorized("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        }
        int revoked = jdbc.update("UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = ? AND revoked_at IS NULL", hash(rawToken));
        if (revoked != 1) throw ApiException.unauthorized("Phiên đăng nhập đã được sử dụng. Vui lòng đăng nhập lại.");
        return new Rotation(current.userId(), issue(current.userId()));
    }

    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        jdbc.update("UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = ? AND revoked_at IS NULL", hash(rawToken));
    }

    public long getLifetimeSeconds() {
        return lifetimeSeconds;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    public record Session(String value, Instant expiresAt) {}
    public record Rotation(long userId, Session session) {}
    private record RefreshRow(long userId, Instant expiresAt, Instant revokedAt) {}
}
