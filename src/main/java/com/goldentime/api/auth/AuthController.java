package com.goldentime.api.auth;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import com.goldentime.api.security.JwtService;
import com.goldentime.api.security.RefreshTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "GT_REFRESH_TOKEN";
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final boolean secureRefreshCookie;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService,
                          RefreshTokenService refreshTokens,
                          @Value("${app.jwt.refresh-token-secure:false}") boolean secureRefreshCookie) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.secureRefreshCookie = secureRefreshCookie;
    }

    @PostMapping("/login")
    @Transactional
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        var users = jdbc.query("""
                SELECT id, email, phone, password_hash, full_name, role
                FROM users
                WHERE enabled = TRUE AND (LOWER(email) = LOWER(?) OR phone = ?)
                """, (rs, row) -> new UserRow(
                rs.getLong("id"), rs.getString("email"), rs.getString("phone"),
                rs.getString("password_hash"), rs.getString("full_name"), rs.getString("role")),
                request.identifier().trim(), request.identifier().trim());
        UserRow user = users.stream().findFirst()
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.passwordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Email/số điện thoại hoặc mật khẩu không đúng."));
        return createSession(user, response);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class, email);
        if (exists != null && exists > 0) throw new ApiException(HttpStatus.CONFLICT, "Email đã được đăng ký.");
        Long id = jdbc.queryForObject("""
                INSERT INTO users (email, phone, password_hash, full_name, role, enabled)
                VALUES (?, ?, ?, ?, 'USER', TRUE)
                RETURNING id
                """, Long.class, email, blankToNull(request.phone()), passwordEncoder.encode(request.password()), request.fullName().trim());
        UserRow user = findUser(id);
        return createSession(user, response);
    }

    @PostMapping("/refresh")
    @Transactional
    public AuthResponse refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                HttpServletResponse response) {
        try {
            RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken);
            UserRow user = findUser(rotation.userId());
            setRefreshCookie(response, rotation.session());
            return accessResponse(user);
        } catch (RuntimeException failure) {
            clearRefreshCookie(response);
            throw failure;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                       HttpServletResponse response) {
        refreshTokens.revoke(refreshToken);
        clearRefreshCookie(response);
    }

    @GetMapping("/me")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public UserProfile me(Authentication authentication) {
        UserRow user = findUser(CurrentUser.id(authentication));
        return new UserProfile(user.id(), user.email(), user.phone(), user.fullName(), user.role());
    }

    private AuthResponse createSession(UserRow user, HttpServletResponse response) {
        RefreshTokenService.Session session = refreshTokens.issue(user.id());
        setRefreshCookie(response, session);
        return accessResponse(user);
    }

    private AuthResponse accessResponse(UserRow user) {
        return new AuthResponse(jwtService.issue(user.id(), user.role()), "Bearer", jwtService.getAccessTokenSeconds(),
                refreshTokens.getLifetimeSeconds(), new UserProfile(user.id(), user.email(), user.phone(), user.fullName(), user.role()));
    }

    private void setRefreshCookie(HttpServletResponse response, RefreshTokenService.Session session) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, session.value())
                .httpOnly(true)
                .secure(secureRefreshCookie)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.between(java.time.Instant.now(), session.expiresAt()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(secureRefreshCookie)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private UserRow findUser(long id) {
        UserRow user = jdbc.query("""
                SELECT id, email, phone, password_hash, full_name, role
                FROM users WHERE id = ? AND enabled = TRUE
                """, rs -> rs.next() ? new UserRow(rs.getLong("id"), rs.getString("email"), rs.getString("phone"),
                rs.getString("password_hash"), rs.getString("full_name"), rs.getString("role")) : null, id);
        if (user == null) throw ApiException.notFound("Không tìm thấy tài khoản.");
        return user;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record LoginRequest(@NotBlank @Size(max = 255) String identifier, @NotBlank @Size(max = 128) String password) {}
    public record RegisterRequest(@Email @NotBlank @Size(max = 255) String email,
                                  @Size(max = 32) String phone,
                                  @NotBlank @Size(min = 8, max = 128) String password,
                                  @NotBlank @Size(max = 255) String fullName) {}
    public record UserProfile(long id, String email, String phone, String fullName, String role) {}
    public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds,
                               long refreshExpiresInSeconds, UserProfile user) {}
    private record UserRow(long id, String email, String phone, String passwordHash, String fullName, String role) {}
}
