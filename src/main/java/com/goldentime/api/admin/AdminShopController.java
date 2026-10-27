package com.goldentime.api.admin;

import com.goldentime.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/shops")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminShopController {
    private final JdbcTemplate jdbc;

    public AdminShopController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PutMapping("/{shopId}/location")
    public ShopLocation updateLocation(@PathVariable long shopId, @Valid @RequestBody LocationWrite request) {
        if (!validCoordinates(request.latitude(), request.longitude())) {
            throw ApiException.badRequest("Tọa độ cửa hàng không hợp lệ.");
        }
        String address = request.address().trim();
        if (jdbc.update("UPDATE shops SET address = ?, latitude = ?, longitude = ?, google_place_id = NULL WHERE id = ?",
                address, request.latitude(), request.longitude(), shopId) == 0) {
            throw ApiException.notFound("Không tìm thấy cửa hàng.");
        }
        return new ShopLocation(shopId, address, request.latitude(), request.longitude());
    }

    private static boolean validCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    public record LocationWrite(@NotBlank @Size(min = 5, max = 512) String address,
                                @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
                                @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {}

    public record ShopLocation(long id, String address, double latitude, double longitude) {}
}
