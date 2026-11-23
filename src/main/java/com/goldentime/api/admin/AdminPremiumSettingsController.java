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
        return new PremiumPriceSetting(readPrice("premium_price_vnd"), readPrice("premium_annual_price_vnd"));
    }

    @PutMapping
    public PremiumPriceSetting update(@RequestBody PremiumPriceUpdate request) {
        if (request == null || !validPrice(request.priceVnd())
                || (request.annualPriceVnd() != null && !validPrice(request.annualPriceVnd()))) {
            throw ApiException.badRequest("Giá mỗi gói Premium phải nằm trong khoảng 0 đến 999.999.999 VND.");
        }
        writePrice("premium_price_vnd", request.priceVnd());
        if (request.annualPriceVnd() != null) writePrice("premium_annual_price_vnd", request.annualPriceVnd());
        return get();
    }

    private long readPrice(String key) {
        return jdbc.query("SELECT setting_value FROM platform_settings WHERE setting_key = ?",
                rs -> rs.next() ? parse(rs.getString(1)) : 0L, key);
    }

    private void writePrice(String key, long amount) {
        jdbc.update("""
                INSERT INTO platform_settings (setting_key, setting_value, updated_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = NOW()
                """, key, Long.toString(amount));
    }

    private static boolean validPrice(Long value) {
        return value != null && value >= 0 && value <= 999_999_999L;
    }

    private static long parse(String value) {
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException exception) { return 0; }
    }

    public record PremiumPriceSetting(long priceVnd, long annualPriceVnd) {}
    public record PremiumPriceUpdate(Long priceVnd, Long annualPriceVnd) {}
}
