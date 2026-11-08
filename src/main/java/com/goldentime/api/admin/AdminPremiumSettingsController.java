package com.goldentime.api.admin;

import com.goldentime.api.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/premium-settings")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminPremiumSettingsController {
    private final JdbcTemplate jdbc;

    public AdminPremiumSettingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public PremiumPriceSetting get() {
        return jdbc.query("SELECT setting_value FROM platform_settings WHERE setting_key = 'premium_price_vnd'",
                rs -> rs.next() ? new PremiumPriceSetting(parse(rs.getString(1))) : new PremiumPriceSetting(0));
    }

    @PutMapping
    public PremiumPriceSetting update(@RequestBody PremiumPriceUpdate request) {
        if (request == null || request.priceVnd() == null || request.priceVnd() < 0 || request.priceVnd() > 999_999_999L) {
            throw ApiException.badRequest("Giá Premium phải nằm trong khoảng 0 đến 999.999.999 VND.");
        }
        jdbc.update("""
                INSERT INTO platform_settings (setting_key, setting_value, updated_at)
                VALUES ('premium_price_vnd', ?, NOW())
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = NOW()
                """, Long.toString(request.priceVnd()));
        return new PremiumPriceSetting(request.priceVnd());
    }

    private static long parse(String value) {
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException exception) { return 0; }
    }

    public record PremiumPriceSetting(long priceVnd) {}
    public record PremiumPriceUpdate(Long priceVnd) {}
}
