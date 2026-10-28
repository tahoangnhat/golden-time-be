package com.goldentime.api.commerce.payment;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
public class WalletController {
    private final JdbcTemplate jdbc;
    private final WalletService wallet;

    public WalletController(JdbcTemplate jdbc, WalletService wallet) {
        this.jdbc = jdbc;
        this.wallet = wallet;
    }

    @GetMapping("/shop/wallet")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public WalletService.WalletView shopWallet(Authentication authentication) {
        return wallet.shopWallet(shopId(CurrentUser.id(authentication)));
    }

    @GetMapping("/shop/wallet/transactions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public List<WalletService.WalletTransaction> shopTransactions(Authentication authentication) {
        return wallet.transactions(shopId(CurrentUser.id(authentication)));
    }

    @GetMapping("/shop/wallet/withdrawal-requests")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public List<WalletService.ShopWithdrawalRequestView> shopWithdrawalRequests(Authentication authentication) {
        return wallet.shopWithdrawalRequests(shopId(CurrentUser.id(authentication)));
    }

    @PostMapping("/shop/wallet/withdrawal-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public WalletService.ShopWithdrawalRequestView createWithdrawalRequest(
            @Valid @RequestBody WithdrawalRequest request, Authentication authentication) {
        return wallet.createWithdrawalRequest(shopId(CurrentUser.id(authentication)), CurrentUser.id(authentication),
                request.amount(), request.bankCode(), request.bankName(), request.accountNumber(), request.accountName());
    }

    @PostMapping("/shop/wallet/withdrawal-requests/{requestId}/cancel")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public WalletService.ShopWithdrawalRequestView cancelWithdrawalRequest(@PathVariable long requestId,
                                                                           Authentication authentication) {
        return wallet.cancelWithdrawalRequest(shopId(CurrentUser.id(authentication)), requestId);
    }

    @GetMapping("/admin/wallets")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public List<WalletService.WalletSummary> adminWallets() {
        return wallet.adminSummaries();
    }

    @GetMapping("/admin/wallets/{shopId}/transactions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public List<WalletService.WalletTransaction> adminTransactions(@PathVariable long shopId) {
        return wallet.transactions(shopId);
    }

    @PutMapping("/admin/wallets/{shopId}/commission")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public void updateCommission(@PathVariable long shopId, @Valid @RequestBody CommissionRequest request) {
        wallet.updateCommission(shopId, request.tier(), request.customRate());
    }

    @GetMapping("/admin/wallet-withdrawal-requests")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public List<WalletService.AdminWithdrawalRequestView> adminWithdrawalRequests(
            @RequestParam(required = false) String status) {
        return wallet.adminWithdrawalRequests(status);
    }

    @PostMapping("/admin/wallet-withdrawal-requests/{requestId}/approve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public WalletService.AdminWithdrawalRequestView approveWithdrawalRequest(@PathVariable long requestId,
                                                                               Authentication authentication) {
        return wallet.approveWithdrawalRequest(requestId, CurrentUser.id(authentication));
    }

    @PostMapping("/admin/wallet-withdrawal-requests/{requestId}/reject")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public WalletService.AdminWithdrawalRequestView rejectWithdrawalRequest(@PathVariable long requestId,
                                                                              @Valid @RequestBody WithdrawalRejection request,
                                                                              Authentication authentication) {
        return wallet.rejectWithdrawalRequest(requestId, CurrentUser.id(authentication), request.reason());
    }

    @PostMapping("/admin/wallet-withdrawal-requests/{requestId}/mark-paid")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public WalletService.AdminWithdrawalRequestView markWithdrawalPaid(@PathVariable long requestId,
                                                                         @Valid @RequestBody WithdrawalPaid request,
                                                                         Authentication authentication) {
        return wallet.markWithdrawalPaid(requestId, CurrentUser.id(authentication), request.reference(), request.note());
    }

    @PostMapping("/admin/wallets/orders/{orderId}/collect-cod")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public void collectCod(@PathVariable long orderId, @Valid @RequestBody CodCollectionRequest request,
                           Authentication authentication) {
        wallet.markCodCollected(orderId, request.reference(), CurrentUser.id(authentication));
    }

    private long shopId(long userId) {
        List<Long> ids = jdbc.query("""
                SELECT s.id FROM shops s LEFT JOIN users u ON u.id = ?
                WHERE s.owner_user_id = ? OR s.id = u.shop_id ORDER BY s.id LIMIT 1
                """, (rs, row) -> rs.getLong(1), userId, userId);
        if (ids.isEmpty()) throw ApiException.forbidden("Tài khoản chưa được liên kết với cửa hàng.");
        return ids.get(0);
    }

    public record CommissionRequest(@NotBlank @Size(max = 24) String tier, BigDecimal customRate) {}
    public record WithdrawalRequest(@NotNull @Positive Long amount,
                                    @NotBlank @Size(max = 32) String bankCode,
                                    @NotBlank @Size(max = 120) String bankName,
                                    @NotBlank @Size(max = 64) String accountNumber,
                                    @NotBlank @Size(max = 120) String accountName) {}
    public record WithdrawalRejection(@NotBlank @Size(max = 1000) String reason) {}
    public record WithdrawalPaid(@NotBlank @Size(max = 255) String reference, @Size(max = 1000) String note) {}
    public record CodCollectionRequest(@Size(max = 255) String reference) {}
}
