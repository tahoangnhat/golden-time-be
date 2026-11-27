package com.goldentime.api.auth;

import com.goldentime.api.business.BusinessDecisionMailer;
import com.goldentime.api.common.ApiException;
import com.goldentime.api.common.FrontendUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/api/account-deletion-requests")
public class AccountDeletionController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String GENERIC_RESPONSE = "Nếu email thuộc một tài khoản Golden Time, chúng tôi sẽ gửi hướng dẫn xác nhận xóa tài khoản. Vui lòng kiểm tra hộp thư và thư rác.";

    private final JdbcTemplate jdbc;
    private final BusinessDecisionMailer mailer;
    private final AccountDeletionService deletionService;
    private final String frontendUrl;

    public AccountDeletionController(JdbcTemplate jdbc, BusinessDecisionMailer mailer,
                                     AccountDeletionService deletionService,
                                     @Value("${app.payment.frontend-url:http://localhost:8080}") String frontendUrl) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.deletionService = deletionService;
        this.frontendUrl = FrontendUrl.canonicalBase(frontendUrl);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public RequestResponse request(@Valid @RequestBody RequestDeletionRequest request) {
        jdbc.update("DELETE FROM account_deletion_requests WHERE expires_at <= NOW() OR created_at < NOW() - INTERVAL '30 days'");
        List<Customer> customers = jdbc.query("""
                SELECT id, email, full_name FROM users
                WHERE LOWER(email) = LOWER(?) AND role = 'USER' AND enabled = TRUE
                """, (rs, row) -> new Customer(rs.getLong("id"), rs.getString("email"), rs.getString("full_name")),
                request.email().trim());
        if (customers.isEmpty()) return new RequestResponse(GENERIC_RESPONSE);

        Customer customer = customers.get(0);
        Integer recentRequest = jdbc.queryForObject("""
                SELECT COUNT(*) FROM account_deletion_requests
                WHERE user_id = ? AND created_at > NOW() - INTERVAL '15 minutes'
                """, Integer.class, customer.id());
        if (recentRequest != null && recentRequest > 0) return new RequestResponse(GENERIC_RESPONSE);

        jdbc.update("DELETE FROM account_deletion_requests WHERE user_id = ?", customer.id());
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                INSERT INTO account_deletion_requests (user_id, token_hash, expires_at)
                VALUES (?, ?, NOW() + INTERVAL '30 minutes')
                """, customer.id(), hash(token));

        String url = frontendUrl + "/delete-account/confirm?token=" + token;
        if (!mailer.sendAccountDeletionVerification(customer.email(), customer.fullName(), url)) {
            jdbc.update("DELETE FROM account_deletion_requests WHERE user_id = ?", customer.id());
        }
        return new RequestResponse(GENERIC_RESPONSE);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void confirm(@Valid @RequestBody ConfirmDeletionRequest request) {
        List<Long> userIds = jdbc.query("""
                SELECT user_id FROM account_deletion_requests
                WHERE token_hash = ? AND expires_at > NOW()
                FOR UPDATE
                """, (rs, row) -> rs.getLong(1), hash(request.token().trim()));
        if (userIds.isEmpty()) throw ApiException.badRequest("Liên kết xác nhận không hợp lệ hoặc đã hết hạn. Hãy gửi lại yêu cầu xóa tài khoản.");
        deletionService.deleteCustomerAccount(userIds.get(0));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể xác minh yêu cầu xóa tài khoản.", exception);
        }
    }

    public record RequestDeletionRequest(@NotBlank @Email @Size(max = 255) String email) {}
    public record ConfirmDeletionRequest(@NotBlank @Size(max = 128) String token) {}
    public record RequestResponse(String message) {}
    private record Customer(long id, String email, String fullName) {}
}
