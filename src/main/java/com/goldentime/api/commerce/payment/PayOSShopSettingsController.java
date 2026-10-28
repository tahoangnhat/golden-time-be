package com.goldentime.api.commerce.payment;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shop/payments/payos")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class PayOSShopSettingsController {
    private final JdbcTemplate jdbc;
    private final PayOSCredentialService credentials;

    public PayOSShopSettingsController(JdbcTemplate jdbc, PayOSCredentialService credentials) {
        this.jdbc = jdbc;
        this.credentials = credentials;
    }

    @GetMapping
    public PayOSCredentialService.Settings settings(Authentication authentication) {
        return credentials.settings(shopId(CurrentUser.id(authentication)));
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void connect(Authentication authentication, @Valid @RequestBody ConnectRequest request) {
        credentials.connect(shopId(CurrentUser.id(authentication)), request.clientId(), request.apiKey(), request.checksumKey());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(Authentication authentication) {
        credentials.disconnect(shopId(CurrentUser.id(authentication)));
    }

    private long shopId(long userId) {
        List<Long> ids = jdbc.query("""
                SELECT s.id FROM shops s LEFT JOIN users u ON u.id = ?
                WHERE s.owner_user_id = ? OR s.id = u.shop_id ORDER BY s.id LIMIT 1
                """, (rs, row) -> rs.getLong(1), userId, userId);
        if (ids.isEmpty()) throw ApiException.forbidden("Tài khoản chưa được liên kết với cửa hàng.");
        return ids.get(0);
    }

    public record ConnectRequest(@NotBlank @Size(max = 255) String clientId,
                                 @NotBlank @Size(max = 255) String apiKey,
                                 @NotBlank @Size(max = 255) String checksumKey) {}
}
