package com.goldentime.api.admin;

import com.goldentime.api.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/shops")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminShopController {
    @PutMapping("/{shopId}/location")
    public void updateLocation(@PathVariable long shopId) {
        throw new ApiException(HttpStatus.FORBIDDEN, "Admin không được phép thay đổi vị trí cửa hàng.");
    }
}
