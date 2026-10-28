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

    @PostMapping("/admin/wallets/{shopId}/payouts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public void payout(@PathVariable long shopId, @Valid @RequestBody PayoutRequest request,
                       Authentication authentication) {
        wallet.recordPayout(shopId, request.amount(), request.reference(), request.note(), CurrentUser.id(authentication));
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
    public record PayoutRequest(@NotNull @Positive Long amount, @Size(max = 255) String reference,
                                @Size(max = 1000) String note) {}
    public record CodCollectionRequest(@Size(max = 255) String reference) {}
}
