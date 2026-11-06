package com.goldentime.api.commerce.voucher;

import com.goldentime.api.commerce.voucher.VoucherRecords.ApplicableVoucherItem;
import com.goldentime.api.commerce.voucher.VoucherRecords.BuyerVoucherView;
import com.goldentime.api.commerce.voucher.VoucherRecords.VoucherResponse;
import com.goldentime.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/buyer/vouchers")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class BuyerVoucherController {

    private final VoucherService voucherService;

    public BuyerVoucherController(VoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @GetMapping("/discover")
    public List<VoucherResponse> discover(
            @RequestParam(required = false) Long shopId,
            Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        return voucherService.listDiscoverableVouchers(userId, shopId);
    }

    @PostMapping("/{voucherId}/claim")
    @ResponseStatus(HttpStatus.CREATED)
    public BuyerVoucherView claim(
            @PathVariable long voucherId,
            Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        return voucherService.claimVoucher(voucherId, userId);
    }

    @GetMapping("/mine")
    public List<BuyerVoucherView> mine(
            @RequestParam(required = false) String status,
            Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        return voucherService.listBuyerVouchers(userId, status);
    }

    @GetMapping("/applicable")
    public List<ApplicableVoucherItem> applicable(
            @RequestParam long shopId,
            @RequestParam long subtotal,
            Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        return voucherService.listApplicableVouchersForCart(userId, shopId, subtotal);
    }
}
