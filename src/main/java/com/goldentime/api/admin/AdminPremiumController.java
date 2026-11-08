package com.goldentime.api.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/premium")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminPremiumController {
    private final JdbcTemplate jdbc;

    public AdminPremiumController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public PremiumAdminOverview overview() {
        PremiumAdminSummary summary = jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE expires_at > NOW()) AS active_members,
                       COUNT(*) AS total_members
                FROM premium_memberships
                """, (rs, row) -> new PremiumAdminSummary(rs.getLong("active_members"), rs.getLong("total_members")));
        PaymentTotals payments = jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status = 'PAID') AS paid_count,
                       COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_count,
                       COALESCE(SUM(amount_vnd) FILTER (WHERE status = 'PAID'), 0) AS paid_revenue
                FROM premium_payments
                """, (rs, row) -> new PaymentTotals(rs.getLong("paid_count"), rs.getLong("pending_count"), rs.getLong("paid_revenue")));
        List<PremiumMember> members = jdbc.query("""
                SELECT u.id AS user_id, u.full_name, u.email, m.expires_at,
                       m.last_payment_order_code, m.updated_at
                FROM premium_memberships m JOIN users u ON u.id = m.user_id
                ORDER BY (m.expires_at > NOW()) DESC, m.expires_at DESC, u.id DESC
                LIMIT 500
                """, (rs, row) -> new PremiumMember(rs.getLong("user_id"), rs.getString("full_name"),
                rs.getString("email"), rs.getTimestamp("expires_at").toInstant(),
                (Long) rs.getObject("last_payment_order_code"), rs.getTimestamp("updated_at").toInstant()));
        List<PremiumPayment> paymentHistory = jdbc.query("""
                SELECT p.order_code, p.user_id, u.full_name, u.email, p.amount_vnd, p.status,
                       p.reference, p.created_at, p.paid_at, p.expires_at
                FROM premium_payments p JOIN users u ON u.id = p.user_id
                ORDER BY p.created_at DESC, p.order_code DESC
                LIMIT 500
                """, (rs, row) -> new PremiumPayment(rs.getLong("order_code"), rs.getLong("user_id"),
                rs.getString("full_name"), rs.getString("email"), rs.getLong("amount_vnd"), rs.getString("status"),
                rs.getString("reference"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()));
        return new PremiumAdminOverview(summary, payments, members, paymentHistory);
    }

    public record PremiumAdminSummary(long activeMembers, long totalMembers) {}
    public record PaymentTotals(long paidCount, long pendingCount, long paidRevenue) {}
    public record PremiumMember(long userId, String fullName, String email, Instant expiresAt,
                                Long lastPaymentOrderCode, Instant updatedAt) {}
    public record PremiumPayment(long orderCode, long userId, String fullName, String email, long amountVnd,
                                 String status, String reference, Instant createdAt, Instant paidAt, Instant expiresAt) {}
    public record PremiumAdminOverview(PremiumAdminSummary membership, PaymentTotals payments,
                                       List<PremiumMember> members, List<PremiumPayment> paymentHistory) {}
}
