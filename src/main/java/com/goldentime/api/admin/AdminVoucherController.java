package com.goldentime.api.admin;

import com.goldentime.api.commerce.voucher.VoucherRecords.VoucherResponse;
import com.goldentime.api.commerce.voucher.VoucherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/vouchers")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminVoucherController {

    private final VoucherService voucherService;

    public AdminVoucherController(VoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @GetMapping
    public List<VoucherResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String status) {
        return voucherService.listAdminVouchers(q, shopId, status);
    }

    @GetMapping("/{id}")
    public VoucherResponse get(@PathVariable long id) {
        return voucherService.getAdminVoucher(id);
    }
}
