package com.goldentime.api.trace;

import com.goldentime.api.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TraceabilityManagementService {
    private static final List<String> BATCH_STATUSES = List.of("IN_PROGRESS", "READY", "IN_TRANSIT", "DELIVERED", "RECALLED");

    private final JdbcTemplate jdbc;

    public TraceabilityManagementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Farm> farms(long userId) {
        long shopId = shopId(userId);
        return jdbc.query("""
                SELECT id, shop_id, name, address, manager_name, phone, fruit_types, area_hectares,
                       certifications, image_url, status, rejection_reason, created_at, updated_at, reviewed_at
                FROM farms WHERE shop_id = ? ORDER BY created_at DESC, id DESC
                """, TraceabilityManagementService::mapFarm, shopId);
    }

    @Transactional
    public Farm createFarm(long userId, FarmWrite request) {
        long shopId = shopId(userId);
        String imageUrl = validateImageUrl(request.imageUrl());
        Long id = jdbc.queryForObject("""
                INSERT INTO farms (shop_id, name, address, manager_name, phone, fruit_types,
                                   area_hectares, certifications, image_url)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, shopId, request.name().trim(), request.address().trim(),
                request.managerName().trim(), request.phone().trim(), request.fruitTypes().trim(),
                request.areaHectares(), optionalText(request.certifications()), imageUrl);
        return farm(shopId, id);
    }

    @Transactional
    public Farm updateFarm(long userId, long farmId, FarmWrite request) {
        long shopId = shopId(userId);
        Farm current = farm(shopId, farmId);
        if ("APPROVED".equals(current.status()) && hasBatches(farmId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Nông trại đã có lô truy xuất nên không thể sửa thông tin. Hãy tạo nông trại mới nếu thông tin nguồn thay đổi.");
        }
        String imageUrl = validateImageUrl(request.imageUrl());
        jdbc.update("""
                UPDATE farms SET name = ?, address = ?, manager_name = ?, phone = ?, fruit_types = ?,
                    area_hectares = ?, certifications = ?, image_url = ?, status = 'PENDING',
                    rejection_reason = NULL, reviewed_by = NULL, reviewed_at = NULL, updated_at = NOW()
                WHERE id = ? AND shop_id = ?
                """, request.name().trim(), request.address().trim(), request.managerName().trim(),
                request.phone().trim(), request.fruitTypes().trim(), request.areaHectares(),
                optionalText(request.certifications()), imageUrl, farmId, shopId);
        return farm(shopId, farmId);
    }

    @Transactional
    public void deleteFarm(long userId, long farmId) {
        long shopId = shopId(userId);
        farm(shopId, farmId);
        if (hasBatches(farmId)) throw new ApiException(HttpStatus.CONFLICT, "Nông trại đã được dùng trong lô truy xuất và không thể xóa.");
        jdbc.update("DELETE FROM farms WHERE id = ? AND shop_id = ?", farmId, shopId);
    }

    @Transactional(readOnly = true)
    public List<BatchSummary> batches(long userId) {
        long shopId = shopId(userId);
        return jdbc.query(batchSelect() + " WHERE b.shop_id = ? ORDER BY b.created_at DESC, b.batch_code DESC",
                TraceabilityManagementService::mapBatch, shopId);
    }

    @Transactional
    public BatchSummary createBatch(long userId, BatchWrite request) {
        long shopId = shopId(userId);
        validateBatch(request);
        Farm farm = approvedFarm(shopId, request.farmId());
        Product product = product(shopId, request.productId());
        String batchCode = createBatchCode();
        String certificates = optionalText(farm.certifications());
        jdbc.update("""
                INSERT INTO trace_batches
                    (batch_code, fruit_id, product_name, origin, supplier, certified, harvest_date, intake_date,
                     certificates, public_note, product_id, shop_id, farm_id, quantity_kg, packaging_date,
                     quality_checked_at, quality_result, quality_score, quality_note, transport_date,
                     transport_info, batch_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batchCode, product.fruitId(), product.name(), farm.address(), farm.name(),
                certificates != null, request.harvestDate(), request.packagingDate(), certificates,
                null, product.id(), shopId, farm.id(), request.quantityKg(),
                request.packagingDate(), request.qualityCheckedAt(), optionalText(request.qualityResult()),
                request.qualityScore(), optionalText(request.qualityNote()), request.transportDate(),
                optionalText(request.transportInfo()), request.batchStatus());
        jdbc.update("UPDATE shop_products SET batch_code = ? WHERE id = ? AND shop_id = ?", batchCode, product.id(), shopId);
        refreshTimeline(batchCode, farm, request, product.shopName(), product.shopAddress());
        return batch(shopId, batchCode);
    }

    @Transactional
    public BatchSummary updateBatch(long userId, String batchCode, BatchWrite request) {
        long shopId = shopId(userId);
        validateBatch(request);
        jdbc.query("SELECT batch_code FROM trace_batches WHERE shop_id = ? AND UPPER(batch_code) = UPPER(?) FOR UPDATE",
                (rs, row) -> rs.getString(1), shopId, batchCode);
        BatchSummary current = batch(shopId, batchCode);
        if (current.farmId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Lô cũ chưa có Farm đã xác minh nên không thể chỉnh sửa trong luồng Batch mới.");
        }
        Farm farm = approvedFarm(shopId, request.farmId());
        Product product = product(shopId, request.productId());
        String certificates = optionalText(farm.certifications());
        jdbc.update("""
                UPDATE trace_batches SET fruit_id = ?, product_name = ?, origin = ?, supplier = ?, certified = ?,
                    harvest_date = ?, intake_date = ?, certificates = ?, product_id = ?, farm_id = ?,
                    quantity_kg = ?, packaging_date = ?, quality_checked_at = ?, quality_result = ?, quality_score = ?,
                    quality_note = ?, transport_date = ?, transport_info = ?, batch_status = ?
                WHERE batch_code = ? AND shop_id = ?
                """, product.fruitId(), product.name(), farm.address(), farm.name(), certificates != null,
                request.harvestDate(), request.packagingDate(), certificates,
                product.id(), farm.id(), request.quantityKg(), request.packagingDate(), request.qualityCheckedAt(),
                optionalText(request.qualityResult()), request.qualityScore(), optionalText(request.qualityNote()),
                request.transportDate(), optionalText(request.transportInfo()), request.batchStatus(),
                current.batchCode(), shopId);
        if (current.productId() != null && current.productId().longValue() != product.id()) {
            jdbc.update("UPDATE shop_products SET batch_code = NULL WHERE id = ? AND shop_id = ? AND batch_code = ?",
                    current.productId(), shopId, current.batchCode());
        }
        jdbc.update("UPDATE shop_products SET batch_code = ? WHERE id = ? AND shop_id = ?", current.batchCode(), product.id(), shopId);
        refreshTimeline(current.batchCode(), farm, request, product.shopName(), product.shopAddress());
        return batch(shopId, current.batchCode());
    }

    @Transactional(readOnly = true)
    public List<Farm> adminFarms() {
        return jdbc.query("""
                SELECT f.id, f.shop_id, f.name, f.address, f.manager_name, f.phone, f.fruit_types, f.area_hectares,
                       f.certifications, f.image_url, f.status, f.rejection_reason, f.created_at, f.updated_at,
                       f.reviewed_at, s.name AS shop_name
                FROM farms f JOIN shops s ON s.id = f.shop_id
                ORDER BY CASE WHEN f.status = 'PENDING' THEN 0 ELSE 1 END, f.created_at DESC, f.id DESC
                """, (rs, row) -> new Farm(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("name"),
                rs.getString("address"), rs.getString("manager_name"), rs.getString("phone"), rs.getString("fruit_types"),
                rs.getBigDecimal("area_hectares"), rs.getString("certifications"), rs.getString("image_url"),
                rs.getString("status"), rs.getString("rejection_reason"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), nullableInstant(rs.getTimestamp("reviewed_at")), rs.getString("shop_name")));
    }

    @Transactional
    public Farm decideFarm(long farmId, boolean approved, String reason, long adminId) {
        Farm current = farmById(farmId);
        if (!"PENDING".equals(current.status())) throw new ApiException(HttpStatus.CONFLICT, "Nông trại này đã được xử lý.");
        String normalizedReason = reason == null ? "" : reason.trim();
        if (!approved && normalizedReason.isBlank()) throw ApiException.badRequest("Hãy ghi lý do từ chối.");
        int updated = jdbc.update("""
                UPDATE farms SET status = ?, rejection_reason = ?, reviewed_by = ?, reviewed_at = NOW(), updated_at = NOW()
                WHERE id = ? AND status = 'PENDING'
                """, approved ? "APPROVED" : "REJECTED", approved ? null : normalizedReason, adminId, farmId);
        if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "Nông trại này vừa được một quản trị viên xử lý.");
        return farmById(farmId);
    }

    @Transactional(readOnly = true)
    public List<BatchSummary> adminBatches() {
        return jdbc.query(batchSelect() + " ORDER BY b.created_at DESC, b.batch_code DESC",
                TraceabilityManagementService::mapBatch);
    }

    @Transactional
    public BatchSummary updateAdminBatchStatus(String batchCode, String batchStatus, String note) {
        if (!BATCH_STATUSES.contains(batchStatus)) throw ApiException.badRequest("Trạng thái lô không hợp lệ.");
        List<String> lockedCodes = jdbc.query("SELECT batch_code FROM trace_batches WHERE UPPER(batch_code) = UPPER(?) FOR UPDATE",
                (rs, row) -> rs.getString(1), batchCode);
        if (lockedCodes.isEmpty()) throw ApiException.notFound("Không tìm thấy lô hàng.");
        BatchSummary current = adminBatch(batchCode);
        if (current.batchStatus().equals(batchStatus)) return current;
        jdbc.update("UPDATE trace_batches SET batch_status = ? WHERE batch_code = ?", batchStatus, current.batchCode());
        Integer nextStep = jdbc.queryForObject("SELECT COALESCE(MAX(step_order), 0) + 1 FROM trace_events WHERE batch_code = ?",
                Integer.class, current.batchCode());
        String title = "Admin cập nhật trạng thái lô · " + batchStatus;
        insertEvent(current.batchCode(), nextStep == null ? 1 : nextStep, title, Instant.now().toString(),
                optionalText(note), "store", true);
        return adminBatch(current.batchCode());
    }

    private void refreshTimeline(String batchCode, Farm farm, BatchWrite request, String shopName, String shopAddress) {
        insertEvent(batchCode, 1, "Nông trại · " + farm.name(), null, farm.address(), "farm", true);
        insertEvent(batchCode, 2, "Thu hoạch", dateText(request.harvestDate()), farm.address(), "harvest", request.harvestDate() != null);
        String qualityTitle = request.qualityResult() == null || request.qualityResult().isBlank()
                ? "Kiểm định chất lượng" : "Kiểm định chất lượng · " + request.qualityResult().trim();
        insertEvent(batchCode, 3, qualityTitle, dateText(request.qualityCheckedAt()), request.qualityNote(), "quality",
                request.qualityCheckedAt() != null || request.qualityResult() != null || request.qualityScore() != null);
        insertEvent(batchCode, 4, "Đóng gói", dateText(request.packagingDate()), null, "package", request.packagingDate() != null);
        insertEvent(batchCode, 5, "Vận chuyển", dateText(request.transportDate()), request.transportInfo(), "transport",
                request.transportDate() != null || (request.transportInfo() != null && !request.transportInfo().isBlank()));
        insertEvent(batchCode, 6, "Business / Shop · " + shopName, null, shopAddress, "store", true);
    }

    private void insertEvent(String code, int order, String title, String date, String location, String icon, boolean completed) {
        int updated = jdbc.update("""
                UPDATE trace_events SET title = ?, event_date = ?, location = ?, icon_key = ?, completed = ?
                WHERE batch_code = ? AND step_order = ?
                """, title, date, location, icon, completed, code, order);
        if (updated == 1) return;
        jdbc.update("""
                INSERT INTO trace_events (batch_code, step_order, title, event_date, location, icon_key, completed)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, code, order, title, date, location, icon, completed);
    }

    private Farm approvedFarm(long shopId, long farmId) {
        List<Farm> matches = jdbc.query("""
                SELECT id, shop_id, name, address, manager_name, phone, fruit_types, area_hectares,
                       certifications, image_url, status, rejection_reason, created_at, updated_at, reviewed_at
                FROM farms WHERE id = ? AND shop_id = ? AND status = 'APPROVED' FOR UPDATE
                """, TraceabilityManagementService::mapFarm, farmId, shopId);
        if (matches.isEmpty()) throw ApiException.badRequest("Chỉ có thể chọn nông trại đã được Admin xác minh thuộc doanh nghiệp của bạn.");
        return matches.get(0);
    }

    private Product product(long shopId, long productId) {
        List<Product> matches = jdbc.query("""
                SELECT p.id, p.fruit_id, p.display_name, s.name AS shop_name, s.address AS shop_address
                FROM shop_products p JOIN shops s ON s.id = p.shop_id WHERE p.id = ? AND p.shop_id = ?
                """, (rs, row) -> new Product(rs.getLong("id"), (Long) rs.getObject("fruit_id"),
                rs.getString("display_name"), rs.getString("shop_name"), rs.getString("shop_address")), productId, shopId);
        if (matches.isEmpty()) throw ApiException.badRequest("Sản phẩm không thuộc doanh nghiệp của bạn.");
        return matches.get(0);
    }

    private Farm farm(long shopId, long farmId) {
        List<Farm> matches = jdbc.query("""
                SELECT id, shop_id, name, address, manager_name, phone, fruit_types, area_hectares,
                       certifications, image_url, status, rejection_reason, created_at, updated_at, reviewed_at
                FROM farms WHERE id = ? AND shop_id = ? FOR UPDATE
                """, TraceabilityManagementService::mapFarm, farmId, shopId);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy nông trại của doanh nghiệp.");
        return matches.get(0);
    }

    private Farm farmById(long farmId) {
        List<Farm> matches = jdbc.query("""
                SELECT id, shop_id, name, address, manager_name, phone, fruit_types, area_hectares,
                       certifications, image_url, status, rejection_reason, created_at, updated_at, reviewed_at
                FROM farms WHERE id = ?
                """, TraceabilityManagementService::mapFarm, farmId);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy nông trại.");
        return matches.get(0);
    }

    private BatchSummary batch(long shopId, String code) {
        List<BatchSummary> matches = jdbc.query(batchSelect() + " WHERE b.shop_id = ? AND UPPER(b.batch_code) = UPPER(?)",
                TraceabilityManagementService::mapBatch, shopId, code);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy lô hàng của doanh nghiệp.");
        return matches.get(0);
    }

    private BatchSummary adminBatch(String code) {
        List<BatchSummary> matches = jdbc.query(batchSelect() + " WHERE UPPER(b.batch_code) = UPPER(?)",
                TraceabilityManagementService::mapBatch, code);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy lô hàng.");
        return matches.get(0);
    }

    private boolean hasBatches(long farmId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM trace_batches WHERE farm_id = ?)", Boolean.class, farmId));
    }

    private long shopId(long userId) {
        List<Long> ids = jdbc.query("""
                SELECT COALESCE(u.shop_id, s.id) FROM users u
                LEFT JOIN shops s ON s.owner_user_id = u.id WHERE u.id = ?
                """, (rs, row) -> rs.getLong(1), userId);
        if (ids.isEmpty() || ids.get(0) == null || ids.get(0) <= 0) throw ApiException.forbidden("Tài khoản chưa được liên kết với cửa hàng.");
        return ids.get(0);
    }

    private static String batchSelect() {
        return """
                SELECT b.batch_code, b.product_name, b.product_id, b.shop_id, b.farm_id, b.quantity_kg,
                       b.harvest_date, b.packaging_date, b.quality_checked_at, b.quality_result, b.quality_score,
                       b.quality_note, b.transport_date, b.transport_info, b.batch_status, b.created_at,
                       s.name AS shop_name, f.name AS farm_name, f.status AS farm_status
                FROM trace_batches b LEFT JOIN shops s ON s.id = b.shop_id LEFT JOIN farms f ON f.id = b.farm_id
                """;
    }

    private static Farm mapFarm(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Farm(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("name"), rs.getString("address"),
                rs.getString("manager_name"), rs.getString("phone"), rs.getString("fruit_types"),
                rs.getBigDecimal("area_hectares"), rs.getString("certifications"), rs.getString("image_url"),
                rs.getString("status"), rs.getString("rejection_reason"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), nullableInstant(rs.getTimestamp("reviewed_at")), null);
    }

    private static BatchSummary mapBatch(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new BatchSummary(rs.getString("batch_code"), rs.getString("product_name"),
                (Long) rs.getObject("product_id"), (Long) rs.getObject("shop_id"), (Long) rs.getObject("farm_id"),
                rs.getBigDecimal("quantity_kg"), rs.getDate("harvest_date") == null ? null : rs.getDate("harvest_date").toLocalDate(),
                rs.getDate("packaging_date") == null ? null : rs.getDate("packaging_date").toLocalDate(),
                rs.getDate("quality_checked_at") == null ? null : rs.getDate("quality_checked_at").toLocalDate(),
                rs.getString("quality_result"), (Integer) rs.getObject("quality_score"), rs.getString("quality_note"),
                rs.getDate("transport_date") == null ? null : rs.getDate("transport_date").toLocalDate(),
                rs.getString("transport_info"), rs.getString("batch_status"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("farm_name"), rs.getString("farm_status"), rs.getString("shop_name"));
    }

    private static void validateBatch(BatchWrite request) {
        if (request.quantityKg() == null || request.quantityKg().signum() <= 0) throw ApiException.badRequest("Số lượng lô phải lớn hơn 0.");
        if (!BATCH_STATUSES.contains(request.batchStatus())) throw ApiException.badRequest("Trạng thái lô không hợp lệ.");
        if (request.qualityScore() != null && (request.qualityScore() < 0 || request.qualityScore() > 100)) {
            throw ApiException.badRequest("Điểm kiểm định cần nằm trong khoảng 0 đến 100.");
        }
        if (request.harvestDate() != null && request.packagingDate() != null && request.packagingDate().isBefore(request.harvestDate())) {
            throw ApiException.badRequest("Ngày đóng gói không thể trước ngày thu hoạch.");
        }
    }

    private static String createBatchCode() {
        return "GT-" + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "") + "-" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static String validateImageUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw ApiException.badRequest("Ảnh cần có địa chỉ HTTPS công khai.");
            return value.trim();
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("Địa chỉ ảnh không hợp lệ.");
        }
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String dateText(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static Instant nullableInstant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Farm(long id, long shopId, String name, String address, String managerName, String phone,
                       String fruitTypes, BigDecimal areaHectares, String certifications, String imageUrl,
                       String status, String rejectionReason, Instant createdAt, Instant updatedAt,
                       Instant reviewedAt, String shopName) {}

    public record FarmWrite(String name, String address, String managerName, String phone, String fruitTypes,
                            BigDecimal areaHectares, String certifications, String imageUrl) {}

    public record BatchWrite(long productId, long farmId, BigDecimal quantityKg, LocalDate harvestDate,
                             LocalDate packagingDate, LocalDate qualityCheckedAt, String qualityResult,
                             Integer qualityScore, String qualityNote, LocalDate transportDate,
                             String transportInfo, String batchStatus) {}

    public record BatchSummary(String batchCode, String product, Long productId, Long shopId, Long farmId,
                               BigDecimal quantityKg, LocalDate harvestDate, LocalDate packagingDate,
                               LocalDate qualityCheckedAt, String qualityResult, Integer qualityScore,
                               String qualityNote, LocalDate transportDate, String transportInfo,
                               String batchStatus, Instant createdAt, String farmName, String farmStatus,
                               String shopName) {}

    private record Product(long id, Long fruitId, String name, String shopName, String shopAddress) {}
}
