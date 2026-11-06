package com.goldentime.api.commerce.voucher;

import com.goldentime.api.commerce.voucher.VoucherRecords.ApplicableVoucherItem;
import com.goldentime.api.commerce.voucher.VoucherRecords.BuyerVoucherView;
import com.goldentime.api.commerce.voucher.VoucherRecords.CreateVoucherRequest;
import com.goldentime.api.commerce.voucher.VoucherRecords.UpdateVoucherRequest;
import com.goldentime.api.commerce.voucher.VoucherRecords.ValidatedVoucher;
import com.goldentime.api.commerce.voucher.VoucherRecords.VoucherResponse;
import com.goldentime.api.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class VoucherService {

    private static final String VOUCHER_SELECT = """
            SELECT v.id, v.code, v.name, v.description, v.discount_type, v.discount_value,
                   v.max_discount_amount, v.min_order_value, v.shop_id, s.name AS shop_name,
                   v.total_quantity, v.claimed_count, v.used_count, v.per_user_claim_limit, v.per_user_use_limit,
                   v.claim_start_at, v.claim_end_at, v.valid_from, v.valid_until, v.enabled, v.created_at
            FROM vouchers v
            JOIN shops s ON s.id = v.shop_id
            """;

    private static final RowMapper<VoucherResponse> VOUCHER_MAPPER = (rs, rowNum) -> {
        Timestamp claimStart = rs.getTimestamp("claim_start_at");
        Timestamp claimEnd = rs.getTimestamp("claim_end_at");
        Timestamp validFrom = rs.getTimestamp("valid_from");
        Timestamp validUntil = rs.getTimestamp("valid_until");
        Timestamp createdAt = rs.getTimestamp("created_at");

        return new VoucherResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("discount_type"),
                rs.getBigDecimal("discount_value"),
                (Long) rs.getObject("max_discount_amount"),
                rs.getLong("min_order_value"),
                rs.getLong("shop_id"),
                rs.getString("shop_name"),
                rs.getInt("total_quantity"),
                rs.getInt("claimed_count"),
                rs.getInt("used_count"),
                rs.getInt("per_user_claim_limit"),
                rs.getInt("per_user_use_limit"),
                claimStart != null ? claimStart.toInstant() : null,
                claimEnd != null ? claimEnd.toInstant() : null,
                validFrom != null ? validFrom.toInstant() : null,
                validUntil != null ? validUntil.toInstant() : null,
                rs.getBoolean("enabled"),
                createdAt != null ? createdAt.toInstant() : null
        );
    };

    private static final RowMapper<BuyerVoucherView> BUYER_VOUCHER_MAPPER = (rs, rowNum) -> {
        Timestamp validFrom = rs.getTimestamp("valid_from");
        Timestamp validUntil = rs.getTimestamp("valid_until");
        Timestamp claimedAt = rs.getTimestamp("claimed_at");
        Timestamp usedAt = rs.getTimestamp("used_at");

        return new BuyerVoucherView(
                rs.getLong("buyer_voucher_id"),
                rs.getLong("voucher_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("discount_type"),
                rs.getBigDecimal("discount_value"),
                (Long) rs.getObject("max_discount_amount"),
                rs.getLong("min_order_value"),
                rs.getLong("shop_id"),
                rs.getString("shop_name"),
                rs.getString("grant_type"),
                rs.getString("status"),
                validFrom != null ? validFrom.toInstant() : null,
                validUntil != null ? validUntil.toInstant() : null,
                claimedAt != null ? claimedAt.toInstant() : null,
                usedAt != null ? usedAt.toInstant() : null
        );
    };

    private final JdbcTemplate jdbc;

    public VoucherService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long getShopIdForUser(long userId) {
        List<Long> shopIds = jdbc.query("""
                SELECT s.id FROM shops s LEFT JOIN users u ON u.id = ?
                WHERE s.owner_user_id = ? OR s.id = u.shop_id
                ORDER BY s.id LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), userId, userId);
        if (shopIds.isEmpty()) {
            throw ApiException.forbidden("Tài khoản chưa được liên kết với cửa hàng.");
        }
        return shopIds.get(0);
    }

    // ==========================================
    // BUSINESS / SHOP OWNER VOUCHER OPERATIONS
    // ==========================================

    @Transactional
    public VoucherResponse createShopVoucher(CreateVoucherRequest request, long shopId, long userId) {
        Integer activeShop = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shops WHERE id = ? AND status = 'ACTIVE'", Integer.class, shopId);
        if (activeShop == null || activeShop == 0) {
            throw ApiException.badRequest("Cửa hàng không tồn tại hoặc chưa hoạt động.");
        }

        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (code.length() < 2 || code.length() > 50) {
            throw ApiException.badRequest("Mã voucher phải có độ dài từ 2 đến 50 ký tự.");
        }

        String discountType = request.discountType().trim().toUpperCase(Locale.ROOT);
        if (!List.of("PERCENT", "FIXED").contains(discountType)) {
            throw ApiException.badRequest("Loại giảm giá không hợp lệ (chỉ chấp nhận PERCENT hoặc FIXED).");
        }

        BigDecimal discountValue = request.discountValue();
        if (discountValue == null || discountValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("Giá trị giảm giá phải lớn hơn 0.");
        }
        if ("PERCENT".equals(discountType) && discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("Tỷ lệ giảm giá phần trăm không được vượt quá 100%.");
        }

        Long maxDiscountAmount = request.maxDiscountAmount();
        if (maxDiscountAmount != null && maxDiscountAmount <= 0) {
            throw ApiException.badRequest("Mức giảm tối đa phải lớn hơn 0.");
        }
        if ("FIXED".equals(discountType)) {
            maxDiscountAmount = null;
        }

        long minOrderValue = request.minOrderValue() == null ? 0L : request.minOrderValue();
        if (minOrderValue < 0) {
            throw ApiException.badRequest("Giá trị đơn tối thiểu không được âm.");
        }

        Instant now = Instant.now();
        Instant validFrom = request.validFrom() != null ? request.validFrom() : now;
        Instant validUntil = request.validUntil();
        if (validUntil == null || validUntil.isBefore(validFrom)) {
            throw ApiException.badRequest("Thời điểm hết hạn (validUntil) phải sau hoặc bằng thời điểm bắt đầu (validFrom).");
        }

        Instant claimStartAt = request.claimStartAt() != null ? request.claimStartAt() : now;
        Instant claimEndAt = request.claimEndAt();
        if (claimEndAt != null && claimEndAt.isBefore(claimStartAt)) {
            throw ApiException.badRequest("Thời điểm kết thúc nhận voucher (claimEndAt) phải sau hoặc bằng thời điểm bắt đầu.");
        }

        int totalQuantity = request.totalQuantity() == null ? 0 : request.totalQuantity();
        if (totalQuantity < 0) {
            throw ApiException.badRequest("Tổng số lượng phát hành không được âm.");
        }

        int perUserClaimLimit = request.perUserClaimLimit() == null ? 1 : request.perUserClaimLimit();
        int perUserUseLimit = request.perUserUseLimit() == null ? 1 : request.perUserUseLimit();
        if (perUserClaimLimit < 1) {
            throw ApiException.badRequest("Giới hạn nhận mỗi người tối thiểu là 1.");
        }
        if (perUserUseLimit < 1) {
            throw ApiException.badRequest("Giới hạn sử dụng mỗi người tối thiểu là 1.");
        }

        Integer existingCode = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vouchers WHERE code = ?", Integer.class, code);
        if (existingCode != null && existingCode > 0) {
            throw ApiException.badRequest("Mã voucher '" + code + "' đã tồn tại.");
        }

        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO vouchers (code, name, description, discount_type, discount_value, max_discount_amount,
                        min_order_value, shop_id, total_quantity, claimed_count, used_count,
                        per_user_claim_limit, per_user_use_limit, claim_start_at, claim_end_at, valid_from, valid_until,
                        enabled, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, TRUE, ?, NOW(), NOW())
                    RETURNING id
                    """, Long.class,
                    code, request.name().trim(), blankToNull(request.description()), discountType, discountValue,
                    maxDiscountAmount, minOrderValue, shopId, totalQuantity, perUserClaimLimit,
                    perUserUseLimit, Timestamp.from(claimStartAt),
                    claimEndAt != null ? Timestamp.from(claimEndAt) : null,
                    Timestamp.from(validFrom), Timestamp.from(validUntil), userId);

            if (id == null) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể tạo voucher.");
            }
            return getShopVoucher(id, shopId);
        } catch (DuplicateKeyException ex) {
            throw ApiException.badRequest("Mã voucher '" + code + "' đã tồn tại.");
        }
    }

    public List<VoucherResponse> listShopVouchers(long shopId, String q, String status) {
        String query = blankToNull(q);
        String pattern = query != null ? "%" + query.toLowerCase(Locale.ROOT) + "%" : null;

        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizedStatus.toUpperCase(Locale.ROOT);
            if (!List.of("ALL", "ACTIVE", "EXPIRED", "DISABLED").contains(normalizedStatus)) {
                throw ApiException.badRequest("Bộ lọc trạng thái không hợp lệ.");
            }
        }

        String sql = VOUCHER_SELECT + """
                WHERE v.shop_id = ?
                  AND (CAST(? AS VARCHAR) IS NULL OR LOWER(v.code) LIKE ? OR LOWER(v.name) LIKE ?)
                  AND (
                    CAST(? AS VARCHAR) IS NULL OR ? = 'ALL'
                    OR (? = 'ACTIVE' AND v.enabled = TRUE AND v.valid_from <= NOW() AND v.valid_until >= NOW())
                    OR (? = 'EXPIRED' AND v.valid_until < NOW())
                    OR (? = 'DISABLED' AND v.enabled = FALSE)
                  )
                ORDER BY v.created_at DESC, v.id DESC
                """;

        return jdbc.query(sql, VOUCHER_MAPPER,
                shopId,
                query, pattern, pattern,
                normalizedStatus, normalizedStatus,
                normalizedStatus, normalizedStatus, normalizedStatus);
    }

    public VoucherResponse getShopVoucher(long id, long shopId) {
        List<VoucherResponse> rows = jdbc.query(
                VOUCHER_SELECT + " WHERE v.id = ? AND v.shop_id = ?", VOUCHER_MAPPER, id, shopId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Không tìm thấy voucher của cửa hàng.");
        }
        return rows.get(0);
    }

    @Transactional
    public VoucherResponse updateShopVoucher(long id, long shopId, UpdateVoucherRequest request) {
        List<ExistingVoucherState> rows = jdbc.query("""
                SELECT id, code, total_quantity, claimed_count, used_count,
                       per_user_claim_limit, per_user_use_limit, claim_start_at, claim_end_at,
                       valid_from, valid_until, enabled
                FROM vouchers WHERE id = ? AND shop_id = ? FOR UPDATE
                """, (rs, rowNum) -> new ExistingVoucherState(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getInt("total_quantity"),
                rs.getInt("claimed_count"),
                rs.getInt("used_count"),
                rs.getInt("per_user_claim_limit"),
                rs.getInt("per_user_use_limit"),
                rs.getTimestamp("claim_start_at").toInstant(),
                rs.getTimestamp("claim_end_at") != null ? rs.getTimestamp("claim_end_at").toInstant() : null,
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getBoolean("enabled")
        ), id, shopId);

        if (rows.isEmpty()) {
            throw ApiException.notFound("Không tìm thấy voucher của cửa hàng.");
        }
        ExistingVoucherState current = rows.get(0);

        Integer newTotalQuantity = request.totalQuantity();
        if (newTotalQuantity != null) {
            if (newTotalQuantity < 0) {
                throw ApiException.badRequest("Tổng số lượng phát hành không được âm.");
            }
            if (newTotalQuantity < current.claimedCount()) {
                throw ApiException.badRequest("Tổng số lượng phát hành (" + newTotalQuantity
                        + ") không được nhỏ hơn số lượng đã nhận (" + current.claimedCount() + ").");
            }
        }

        Integer newClaimLimit = request.perUserClaimLimit();
        if (newClaimLimit != null) {
            if (newClaimLimit < 1) {
                throw ApiException.badRequest("Giới hạn nhận mỗi người tối thiểu là 1.");
            }
            Integer maxClaimedByAnyBuyer = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(cnt), 0) FROM (
                        SELECT COUNT(*) AS cnt FROM buyer_vouchers WHERE voucher_id = ? AND status <> 'REVOKED' GROUP BY user_id
                    ) t
                    """, Integer.class, id);
            if (maxClaimedByAnyBuyer != null && newClaimLimit < maxClaimedByAnyBuyer) {
                throw ApiException.badRequest("Giới hạn nhận mỗi người (" + newClaimLimit
                        + ") không được nhỏ hơn số vé một người dùng đã nhận (" + maxClaimedByAnyBuyer + ").");
            }
        }

        Integer newUseLimit = request.perUserUseLimit();
        if (newUseLimit != null) {
            if (newUseLimit < 1) {
                throw ApiException.badRequest("Giới hạn sử dụng mỗi người tối thiểu là 1.");
            }
            Integer maxUsedByAnyBuyer = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(cnt), 0) FROM (
                        SELECT COUNT(*) AS cnt FROM buyer_vouchers WHERE voucher_id = ? AND status = 'USED' GROUP BY user_id
                    ) t
                    """, Integer.class, id);
            if (maxUsedByAnyBuyer != null && newUseLimit < maxUsedByAnyBuyer) {
                throw ApiException.badRequest("Giới hạn sử dụng mỗi người (" + newUseLimit
                        + ") không được nhỏ hơn số lần một người dùng đã sử dụng (" + maxUsedByAnyBuyer + ").");
            }
        }

        Instant validFrom = request.validFrom() != null ? request.validFrom() : current.validFrom();
        Instant validUntil = request.validUntil() != null ? request.validUntil() : current.validUntil();
        if (validUntil.isBefore(validFrom)) {
            throw ApiException.badRequest("Thời điểm hết hạn (validUntil) phải sau hoặc bằng thời điểm bắt đầu (validFrom).");
        }

        Instant claimStartAt = request.claimStartAt() != null ? request.claimStartAt() : current.claimStartAt();
        Instant claimEndAt = request.claimEndAt();
        if (claimEndAt != null && claimEndAt.isBefore(claimStartAt)) {
            throw ApiException.badRequest("Thời điểm kết thúc nhận voucher phải sau hoặc bằng thời điểm bắt đầu nhận.");
        }

        Boolean enabled = request.enabled() != null ? request.enabled() : current.enabled();

        jdbc.update("""
                UPDATE vouchers SET
                    name = COALESCE(NULLIF(?, ''), name),
                    description = ?,
                    total_quantity = COALESCE(?, total_quantity),
                    per_user_claim_limit = COALESCE(?, per_user_claim_limit),
                    per_user_use_limit = COALESCE(?, per_user_use_limit),
                    claim_start_at = COALESCE(?, claim_start_at),
                    claim_end_at = ?,
                    valid_from = COALESCE(?, valid_from),
                    valid_until = COALESCE(?, valid_until),
                    enabled = ?,
                    updated_at = NOW()
                WHERE id = ? AND shop_id = ?
                """,
                request.name() != null ? request.name().trim() : null,
                blankToNull(request.description()),
                newTotalQuantity,
                newClaimLimit,
                newUseLimit,
                request.claimStartAt() != null ? Timestamp.from(request.claimStartAt()) : null,
                claimEndAt != null ? Timestamp.from(claimEndAt) : null,
                request.validFrom() != null ? Timestamp.from(request.validFrom()) : null,
                request.validUntil() != null ? Timestamp.from(request.validUntil()) : null,
                enabled,
                id,
                shopId);

        return getShopVoucher(id, shopId);
    }

    @Transactional
    public VoucherResponse toggleShopVoucher(long id, long shopId, boolean enabled) {
        List<Boolean> currentStatus = jdbc.query(
                "SELECT enabled FROM vouchers WHERE id = ? AND shop_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getBoolean(1), id, shopId);

        if (currentStatus.isEmpty()) {
            throw ApiException.notFound("Không tìm thấy voucher của cửa hàng.");
        }

        jdbc.update("UPDATE vouchers SET enabled = ?, updated_at = NOW() WHERE id = ? AND shop_id = ?", enabled, id, shopId);
        return getShopVoucher(id, shopId);
    }

    // ==========================================
    // ADMIN VOUCHER OPERATIONS (READ-ONLY)
    // ==========================================

    public List<VoucherResponse> listAdminVouchers(String q, Long shopId, String status) {
        String query = blankToNull(q);
        String pattern = query != null ? "%" + query.toLowerCase(Locale.ROOT) + "%" : null;

        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizedStatus.toUpperCase(Locale.ROOT);
            if (!List.of("ALL", "ACTIVE", "EXPIRED", "DISABLED").contains(normalizedStatus)) {
                throw ApiException.badRequest("Bộ lọc trạng thái không hợp lệ.");
            }
        }

        String sql = VOUCHER_SELECT + """
                WHERE (CAST(? AS BIGINT) IS NULL OR v.shop_id = ?)
                  AND (CAST(? AS VARCHAR) IS NULL OR LOWER(v.code) LIKE ? OR LOWER(v.name) LIKE ?)
                  AND (
                    CAST(? AS VARCHAR) IS NULL OR ? = 'ALL'
                    OR (? = 'ACTIVE' AND v.enabled = TRUE AND v.valid_from <= NOW() AND v.valid_until >= NOW())
                    OR (? = 'EXPIRED' AND v.valid_until < NOW())
                    OR (? = 'DISABLED' AND v.enabled = FALSE)
                  )
                ORDER BY v.created_at DESC, v.id DESC
                """;

        return jdbc.query(sql, VOUCHER_MAPPER,
                shopId, shopId,
                query, pattern, pattern,
                normalizedStatus, normalizedStatus,
                normalizedStatus, normalizedStatus, normalizedStatus);
    }

    public VoucherResponse getAdminVoucher(long id) {
        List<VoucherResponse> rows = jdbc.query(VOUCHER_SELECT + " WHERE v.id = ?", VOUCHER_MAPPER, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Không tìm thấy voucher.");
        }
        return rows.get(0);
    }

    // ==========================================
    // BUYER VOUCHER OPERATIONS
    // ==========================================

    public List<VoucherResponse> listDiscoverableVouchers(long userId, Long shopId) {
        if (shopId != null) {
            Integer activeShop = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM shops WHERE id = ? AND status = 'ACTIVE'", Integer.class, shopId);
            if (activeShop == null || activeShop == 0) {
                throw ApiException.badRequest("Cửa hàng không tồn tại hoặc chưa hoạt động.");
            }
        }

        String sql = """
                SELECT v.id, v.code, v.name, v.description, v.discount_type, v.discount_value,
                       v.max_discount_amount, v.min_order_value, v.shop_id, s.name AS shop_name,
                       v.total_quantity, v.claimed_count, v.used_count, v.per_user_claim_limit, v.per_user_use_limit,
                       v.claim_start_at, v.claim_end_at, v.valid_from, v.valid_until, v.enabled, v.created_at
                FROM vouchers v
                JOIN shops s ON s.id = v.shop_id
                LEFT JOIN (
                    SELECT voucher_id, COUNT(*) AS user_claim_count
                    FROM buyer_vouchers
                    WHERE user_id = ? AND status <> 'REVOKED'
                    GROUP BY voucher_id
                ) bv ON bv.voucher_id = v.id
                WHERE (CAST(? AS BIGINT) IS NULL OR v.shop_id = ?)
                  AND v.enabled = TRUE
                  AND s.status = 'ACTIVE'
                  AND v.claim_start_at <= NOW()
                  AND (v.claim_end_at IS NULL OR v.claim_end_at >= NOW())
                  AND v.valid_until >= NOW()
                  AND v.claimed_count < v.total_quantity
                  AND COALESCE(bv.user_claim_count, 0) < v.per_user_claim_limit
                ORDER BY v.created_at DESC, v.id DESC
                """;

        return jdbc.query(sql, VOUCHER_MAPPER, userId, shopId, shopId);
    }

    @Transactional
    public BuyerVoucherView claimVoucher(long voucherId, long userId) {
        List<ClaimableVoucherState> rows = jdbc.query("""
                SELECT id, total_quantity, claimed_count, per_user_claim_limit, enabled,
                       valid_from, valid_until, claim_start_at, claim_end_at
                FROM vouchers
                WHERE id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new ClaimableVoucherState(
                rs.getLong("id"),
                rs.getInt("total_quantity"),
                rs.getInt("claimed_count"),
                rs.getInt("per_user_claim_limit"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getTimestamp("claim_start_at").toInstant(),
                rs.getTimestamp("claim_end_at") != null ? rs.getTimestamp("claim_end_at").toInstant() : null
        ), voucherId);

        if (rows.isEmpty()) {
            throw ApiException.notFound("Không tìm thấy voucher.");
        }
        ClaimableVoucherState voucher = rows.get(0);

        if (!voucher.enabled()) {
            throw ApiException.badRequest("Voucher hiện đang tạm dừng hoặc không khả dụng.");
        }

        Instant now = Instant.now();
        if (now.isBefore(voucher.claimStartAt())) {
            throw ApiException.badRequest("Voucher chưa đến thời gian nhận.");
        }
        if (voucher.claimEndAt() != null && now.isAfter(voucher.claimEndAt())) {
            throw ApiException.badRequest("Voucher đã hết thời gian nhận.");
        }
        if (now.isAfter(voucher.validUntil())) {
            throw ApiException.badRequest("Voucher đã hết hạn sử dụng.");
        }

        if (voucher.claimedCount() >= voucher.totalQuantity()) {
            throw ApiException.badRequest("Voucher đã hết lượt nhận.");
        }

        Integer userClaimCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM buyer_vouchers
                WHERE voucher_id = ? AND user_id = ? AND status <> 'REVOKED'
                """, Integer.class, voucherId, userId);

        if (userClaimCount != null && userClaimCount >= voucher.perUserClaimLimit()) {
            throw ApiException.badRequest("Bạn đã đạt giới hạn nhận voucher này (tối đa "
                    + voucher.perUserClaimLimit() + " lượt).");
        }

        Long buyerVoucherId = jdbc.queryForObject("""
                INSERT INTO buyer_vouchers (user_id, voucher_id, grant_type, status, claimed_at)
                VALUES (?, ?, 'CLAIM', 'AVAILABLE', NOW())
                RETURNING id
                """, Long.class, userId, voucherId);

        if (buyerVoucherId == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể nhận voucher vào ví.");
        }

        jdbc.update("UPDATE vouchers SET claimed_count = claimed_count + 1, updated_at = NOW() WHERE id = ?", voucherId);

        return getBuyerVoucher(buyerVoucherId, userId);
    }

    public BuyerVoucherView getBuyerVoucher(long buyerVoucherId, long userId) {
        String sql = """
                SELECT bv.id AS buyer_voucher_id, bv.voucher_id, v.code, v.name, v.description,
                       v.discount_type, v.discount_value, v.max_discount_amount, v.min_order_value,
                       v.shop_id, s.name AS shop_name, bv.grant_type,
                       CASE
                           WHEN bv.status = 'AVAILABLE' AND v.valid_until < NOW() THEN 'EXPIRED'
                           ELSE bv.status
                       END AS status,
                       v.valid_from, v.valid_until, bv.claimed_at, bv.used_at
                FROM buyer_vouchers bv
                JOIN vouchers v ON v.id = bv.voucher_id
                JOIN shops s ON s.id = v.shop_id
                WHERE bv.id = ? AND bv.user_id = ?
                """;

        List<BuyerVoucherView> rows = jdbc.query(sql, BUYER_VOUCHER_MAPPER, buyerVoucherId, userId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Không tìm thấy voucher trong ví.");
        }
        return rows.get(0);
    }

    public List<BuyerVoucherView> listBuyerVouchers(long userId, String status) {
        String normalized = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "AVAILABLE", "USED", "EXPIRED").contains(normalized)) {
            throw ApiException.badRequest("Bộ lọc trạng thái không hợp lệ. Chỉ chấp nhận ALL, AVAILABLE, USED, EXPIRED.");
        }

        String sql = """
                SELECT bv.id AS buyer_voucher_id, bv.voucher_id, v.code, v.name, v.description,
                       v.discount_type, v.discount_value, v.max_discount_amount, v.min_order_value,
                       v.shop_id, s.name AS shop_name, bv.grant_type,
                       CASE
                           WHEN bv.status = 'AVAILABLE' AND v.valid_until < NOW() THEN 'EXPIRED'
                           ELSE bv.status
                       END AS status,
                       v.valid_from, v.valid_until, bv.claimed_at, bv.used_at
                FROM buyer_vouchers bv
                JOIN vouchers v ON v.id = bv.voucher_id
                JOIN shops s ON s.id = v.shop_id
                WHERE bv.user_id = ?
                  AND (
                    ? = 'ALL'
                    OR (? = 'AVAILABLE' AND bv.status = 'AVAILABLE' AND v.valid_until >= NOW())
                    OR (? = 'USED' AND bv.status = 'USED')
                    OR (? = 'EXPIRED' AND (bv.status = 'EXPIRED' OR (bv.status = 'AVAILABLE' AND v.valid_until < NOW())))
                  )
                ORDER BY bv.claimed_at DESC, bv.id DESC
                """;

        return jdbc.query(sql, BUYER_VOUCHER_MAPPER, userId, normalized, normalized, normalized, normalized);
    }

    // ==========================================
    // CHECKOUT, DISCOUNT & RESERVATION LIFECYCLE
    // ==========================================

    public static long calculateDiscount(long subtotal, String discountType, BigDecimal discountValue, Long maxDiscountAmount) {
        if (subtotal <= 0 || discountValue == null || discountValue.compareTo(BigDecimal.ZERO) <= 0) {
            return 0L;
        }
        long rawDiscount;
        if ("PERCENT".equalsIgnoreCase(discountType)) {
            rawDiscount = BigDecimal.valueOf(subtotal)
                    .multiply(discountValue)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .longValueExact();
            if (maxDiscountAmount != null && maxDiscountAmount > 0 && rawDiscount > maxDiscountAmount) {
                rawDiscount = maxDiscountAmount;
            }
        } else {
            rawDiscount = discountValue.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return Math.max(0L, Math.min(rawDiscount, subtotal));
    }

    public List<ApplicableVoucherItem> listApplicableVouchersForCart(long userId, long shopId, long subtotal) {
        String sql = """
                SELECT bv.id AS buyer_voucher_id, bv.voucher_id, v.code, v.name,
                       v.discount_type, v.discount_value, v.max_discount_amount, v.min_order_value,
                       v.shop_id, v.enabled, v.valid_from, v.valid_until, v.per_user_use_limit,
                       bv.status
                FROM buyer_vouchers bv
                JOIN vouchers v ON v.id = bv.voucher_id
                WHERE bv.user_id = ? AND bv.status = 'AVAILABLE'
                ORDER BY bv.claimed_at DESC, bv.id DESC
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            long buyerVoucherId = rs.getLong("buyer_voucher_id");
            long voucherId = rs.getLong("voucher_id");
            String code = rs.getString("code");
            String name = rs.getString("name");
            String discountType = rs.getString("discount_type");
            BigDecimal discountValue = rs.getBigDecimal("discount_value");
            Long maxDiscountAmount = (Long) rs.getObject("max_discount_amount");
            long minOrderValue = rs.getLong("min_order_value");
            long voucherShopId = rs.getLong("shop_id");
            boolean enabled = rs.getBoolean("enabled");
            Timestamp validFrom = rs.getTimestamp("valid_from");
            Timestamp validUntil = rs.getTimestamp("valid_until");
            int perUserUseLimit = rs.getInt("per_user_use_limit");

            Instant now = Instant.now();
            boolean applicable = true;
            String reason = null;

            if (!enabled) {
                applicable = false;
                reason = "Voucher hiện đang tạm dừng.";
            } else if (validFrom != null && now.isBefore(validFrom.toInstant())) {
                applicable = false;
                reason = "Voucher chưa đến thời gian có hiệu lực.";
            } else if (validUntil != null && now.isAfter(validUntil.toInstant())) {
                applicable = false;
                reason = "Voucher đã hết hạn sử dụng.";
            } else if (voucherShopId != shopId) {
                applicable = false;
                reason = "Voucher thuộc cửa hàng khác.";
            } else if (subtotal < minOrderValue) {
                applicable = false;
                reason = "Đơn hàng chưa đạt giá trị tối thiểu " + minOrderValue + "đ.";
            } else {
                Integer usedCount = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM buyer_vouchers
                        WHERE user_id = ? AND voucher_id = ? AND status IN ('RESERVED', 'USED')
                        """, Integer.class, userId, voucherId);
                if (usedCount != null && usedCount >= perUserUseLimit) {
                    applicable = false;
                    reason = "Bạn đã đạt giới hạn sử dụng voucher này.";
                }
            }

            long estimatedDiscount = applicable ? calculateDiscount(subtotal, discountType, discountValue, maxDiscountAmount) : 0L;

            return new ApplicableVoucherItem(
                    buyerVoucherId, voucherId, code, name, discountType, discountValue,
                    maxDiscountAmount, minOrderValue, voucherShopId, estimatedDiscount,
                    applicable, reason
            );
        }, userId);
    }

    public ValidatedVoucher validateForCheckout(long buyerVoucherId, long userId, long shopId, long subtotal) {
        List<BuyerVoucherCheckoutState> rows = jdbc.query("""
                SELECT bv.id AS buyer_voucher_id, bv.voucher_id, bv.status AS bv_status,
                       v.shop_id, v.discount_type, v.discount_value, v.max_discount_amount,
                       v.min_order_value, v.per_user_use_limit, v.enabled,
                       v.valid_from, v.valid_until
                FROM buyer_vouchers bv
                JOIN vouchers v ON v.id = bv.voucher_id
                WHERE bv.id = ? AND bv.user_id = ?
                FOR UPDATE OF bv
                """, (rs, rowNum) -> new BuyerVoucherCheckoutState(
                rs.getLong("buyer_voucher_id"),
                rs.getLong("voucher_id"),
                rs.getString("bv_status"),
                rs.getLong("shop_id"),
                rs.getString("discount_type"),
                rs.getBigDecimal("discount_value"),
                (Long) rs.getObject("max_discount_amount"),
                rs.getLong("min_order_value"),
                rs.getInt("per_user_use_limit"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_until").toInstant()
        ), buyerVoucherId, userId);

        if (rows.isEmpty()) {
            throw ApiException.notFound("Không tìm thấy voucher trong ví của bạn.");
        }
        BuyerVoucherCheckoutState state = rows.get(0);

        if (!"AVAILABLE".equals(state.bvStatus())) {
            throw ApiException.badRequest("Voucher không khả dụng hoặc đang được sử dụng ở đơn hàng khác.");
        }
        if (!state.enabled()) {
            throw ApiException.badRequest("Voucher hiện đang tạm dừng.");
        }
        Instant now = Instant.now();
        if (now.isBefore(state.validFrom())) {
            throw ApiException.badRequest("Voucher chưa đến thời gian có hiệu lực.");
        }
        if (now.isAfter(state.validUntil())) {
            throw ApiException.badRequest("Voucher đã hết hạn sử dụng.");
        }
        if (state.shopId() != shopId) {
            throw ApiException.badRequest("Voucher chỉ áp dụng cho sản phẩm của cửa hàng phát hành.");
        }
        if (subtotal < state.minOrderValue()) {
            throw ApiException.badRequest("Đơn hàng chưa đạt giá trị tối thiểu " + state.minOrderValue() + "đ để sử dụng voucher.");
        }

        Integer usedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM buyer_vouchers
                WHERE user_id = ? AND voucher_id = ? AND status IN ('RESERVED', 'USED')
                """, Integer.class, userId, state.voucherId());
        if (usedCount != null && usedCount >= state.perUserUseLimit()) {
            throw ApiException.badRequest("Bạn đã sử dụng hết số lượt cho phép của voucher này.");
        }

        long discount = calculateDiscount(subtotal, state.discountType(), state.discountValue(), state.maxDiscountAmount());
        return new ValidatedVoucher(buyerVoucherId, state.voucherId(), state.shopId(), discount);
    }

    public void reserveVoucher(long buyerVoucherId, long orderId) {
        int updated = jdbc.update("""
                UPDATE buyer_vouchers
                SET status = 'RESERVED', reserved_order_id = ?, reserved_at = NOW()
                WHERE id = ? AND status = 'AVAILABLE'
                """, orderId, buyerVoucherId);
        if (updated != 1) {
            throw ApiException.badRequest("Không thể giữ chỗ voucher. Voucher có thể đã được sử dụng.");
        }
    }

    public void releaseReservedVoucher(long orderId) {
        jdbc.update("""
                UPDATE buyer_vouchers bv
                SET status = CASE WHEN v.valid_until >= NOW() THEN 'AVAILABLE' ELSE 'EXPIRED' END,
                    reserved_order_id = NULL,
                    reserved_at = NULL
                FROM vouchers v
                WHERE bv.reserved_order_id = ? AND bv.voucher_id = v.id AND bv.status = 'RESERVED'
                """, orderId);
    }

    public void markVoucherUsed(long orderId) {
        List<Long> voucherIds = jdbc.query("""
                UPDATE buyer_vouchers
                SET status = 'USED', used_at = NOW()
                WHERE reserved_order_id = ? AND status = 'RESERVED'
                RETURNING voucher_id
                """, (rs, rowNum) -> rs.getLong(1), orderId);

        if (!voucherIds.isEmpty()) {
            for (Long vId : voucherIds) {
                jdbc.update("""
                        UPDATE vouchers
                        SET used_count = used_count + 1, updated_at = NOW()
                        WHERE id = ?
                        """, vId);
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ExistingVoucherState(
            long id, String code, int totalQuantity, int claimedCount, int usedCount,
            int perUserClaimLimit, int perUserUseLimit, Instant claimStartAt, Instant claimEndAt,
            Instant validFrom, Instant validUntil, boolean enabled) {}

    private record ClaimableVoucherState(
            long id, int totalQuantity, int claimedCount, int perUserClaimLimit, boolean enabled,
            Instant validFrom, Instant validUntil, Instant claimStartAt, Instant claimEndAt) {}

    private record BuyerVoucherCheckoutState(
            long buyerVoucherId, long voucherId, String bvStatus, long shopId,
            String discountType, BigDecimal discountValue, Long maxDiscountAmount,
            long minOrderValue, int perUserUseLimit, boolean enabled,
            Instant validFrom, Instant validUntil) {}
}
