package com.goldentime.api.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void createForUser(long userId, String type, String title, String message, String targetPath) {
        jdbc.update("""
                INSERT INTO notifications (user_id, type, title, message, target_path)
                SELECT id, ?, ?, ?, ? FROM users WHERE id = ? AND enabled = TRUE
                """, type, title, message, targetPath, userId);
    }

    @Transactional
    public void createForRole(String role, String type, String title, String message, String targetPath) {
        jdbc.update("""
                INSERT INTO notifications (user_id, type, title, message, target_path)
                SELECT id, ?, ?, ?, ? FROM users WHERE role = ? AND enabled = TRUE
                """, type, title, message, targetPath, role);
    }

    @Transactional
    public void createForShopOwner(long shopId, String type, String title, String message, String targetPath) {
        jdbc.update("""
                INSERT INTO notifications (user_id, type, title, message, target_path)
                SELECT DISTINCT u.id, ?, ?, ?, ?
                FROM users u LEFT JOIN shops s ON s.id = ?
                WHERE u.role = 'SHOP_OWNER' AND u.enabled = TRUE
                  AND (u.shop_id = ? OR s.owner_user_id = u.id)
                """, type, title, message, targetPath, shopId, shopId);
    }

    @Transactional
    public void notifyOrderBuyer(long orderId, String type, String title, String message, String targetPath) {
        jdbc.update("""
                INSERT INTO notifications (user_id, type, title, message, target_path)
                SELECT o.user_id, ?, ?, ?, ? FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.id = ? AND u.enabled = TRUE
                """, type, title, message, targetPath, orderId);
    }

    public Inbox inbox(long userId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, userId);
        List<Item> items = jdbc.query("""
                SELECT id, type, title, message, target_path, created_at, read_at
                FROM notifications WHERE user_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, row) -> {
                    var readAt = rs.getTimestamp("read_at");
                    return new Item(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                            rs.getString("message"), rs.getString("target_path"), rs.getTimestamp("created_at").toInstant(),
                            readAt == null ? null : readAt.toInstant(), readAt != null);
                }, userId, limit);
        return new Inbox(items, count == null ? 0 : count);
    }

    @Transactional
    public void markRead(long userId, long notificationId) {
        jdbc.update("UPDATE notifications SET read_at = COALESCE(read_at, NOW()) WHERE id = ? AND user_id = ?",
                notificationId, userId);
    }

    @Transactional
    public int markAllRead(long userId) {
        return jdbc.update("UPDATE notifications SET read_at = NOW() WHERE user_id = ? AND read_at IS NULL", userId);
    }

    public record Item(long id, String type, String title, String message, String targetPath,
                       Instant createdAt, Instant readAt, boolean read) {}
    public record Inbox(List<Item> items, int unreadCount) {}
}
