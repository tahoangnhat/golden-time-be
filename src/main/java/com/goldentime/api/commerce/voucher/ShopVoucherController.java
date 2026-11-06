package com.goldentime.api.commerce.voucher;

import com.goldentime.api.commerce.voucher.VoucherRecords.CreateVoucherRequest;
import com.goldentime.api.commerce.voucher.VoucherRecords.UpdateVoucherRequest;
import com.goldentime.api.commerce.voucher.VoucherRecords.VoucherResponse;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shop/vouchers")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class ShopVoucherController {

    private final VoucherService voucherService;

    public ShopVoucherController(VoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VoucherResponse create(@Valid @RequestBody CreateVoucherRequest request, Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        long shopId = voucherService.getShopIdForUser(userId);
        return voucherService.createShopVoucher(request, shopId, userId);
    }

    @GetMapping
    public List<VoucherResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        long shopId = voucherService.getShopIdForUser(CurrentUser.id(authentication));
        return voucherService.listShopVouchers(shopId, q, status);
    }

    @GetMapping("/{id}")
    public VoucherResponse get(@PathVariable long id, Authentication authentication) {
        long shopId = voucherService.getShopIdForUser(CurrentUser.id(authentication));
        return voucherService.getShopVoucher(id, shopId);
    }

    @PutMapping("/{id}")
    public VoucherResponse update(
            @PathVariable long id,
            @Valid @RequestBody UpdateVoucherRequest request,
            Authentication authentication) {
        long shopId = voucherService.getShopIdForUser(CurrentUser.id(authentication));
        return voucherService.updateShopVoucher(id, shopId, request);
    }

    @PatchMapping("/{id}/toggle")
    public VoucherResponse toggle(
            @PathVariable long id,
            @RequestParam boolean enabled,
            Authentication authentication) {
        long shopId = voucherService.getShopIdForUser(CurrentUser.id(authentication));
        return voucherService.toggleShopVoucher(id, shopId, enabled);
    }
}
