package com.goldentime.api.business;

import com.goldentime.api.common.ApiException;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class BusinessApplicationService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final BusinessLicenseStorage licenseStorage;
    private final BusinessDecisionMailer mailer;

    public BusinessApplicationService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                      BusinessLicenseStorage licenseStorage, BusinessDecisionMailer mailer) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.licenseStorage = licenseStorage;
        this.mailer = mailer;
    }

    @Transactional
    public ApplicationReceipt submit(String businessName, String registrationNumber, String contactName,
                                     String emailValue, String phone, String address, String password,
                                     MultipartFile licenseFile) {
        String email = emailValue.trim().toLowerCase(Locale.ROOT);
        String name = businessName.trim();
        String registration = registrationNumber.trim();
        String contact = contactName.trim();
        String normalizedPhone = phone.trim();
        String normalizedAddress = address.trim();
        validate(name, registration, contact, email, normalizedPhone, normalizedAddress, password);

        Integer accountExists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class, email);
        if (accountExists != null && accountExists > 0) throw new ApiException(HttpStatus.CONFLICT, "Email này đã có tài khoản.");
        Integer applicationExists = jdbc.queryForObject("SELECT COUNT(*) FROM business_applications WHERE LOWER(email) = ? AND status = 'PENDING'", Integer.class, email);
        if (applicationExists != null && applicationExists > 0) throw new ApiException(HttpStatus.CONFLICT, "Email này đang có hồ sơ chờ duyệt.");

        BusinessLicenseStorage.StoredLicense license = licenseStorage.save(licenseFile);
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO business_applications
                        (business_name, registration_number, contact_name, email, phone, address,
                         password_hash, license_file, license_original_name, license_content_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, name, registration, contact, email, normalizedPhone, normalizedAddress,
                    passwordEncoder.encode(password), license.storedName(), license.originalName(), license.contentType());
            return new ApplicationReceipt(id, "PENDING", Instant.now());
        } catch (DuplicateKeyException exception) {
            licenseStorage.delete(license.storedName());
            throw new ApiException(HttpStatus.CONFLICT, "Email này đang có hồ sơ chờ duyệt.");
        } catch (RuntimeException exception) {
            licenseStorage.delete(license.storedName());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<ApplicationSummary> list() {
        return jdbc.query("""
                SELECT id, business_name, registration_number, contact_name, email, phone, address,
                       license_original_name, license_content_type, status, rejection_reason, created_at,
                       reviewed_at, shop_id
                FROM business_applications
                ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, created_at DESC, id DESC
                """, (rs, row) -> new ApplicationSummary(
                rs.getLong("id"), rs.getString("business_name"), rs.getString("registration_number"),
                rs.getString("contact_name"), rs.getString("email"), rs.getString("phone"), rs.getString("address"),
                rs.getString("license_original_name"), rs.getString("license_content_type"), rs.getString("status"),
                rs.getString("rejection_reason"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
                (Long) rs.getObject("shop_id"), null));
    }

    @Transactional
    public ApplicationSummary decide(long id, boolean approved, String reason, long adminId) {
        List<LockedApplication> matches = jdbc.query("""
                SELECT id, business_name, registration_number, contact_name, email, phone, address, password_hash,
                       license_original_name, license_content_type, status
                FROM business_applications WHERE id = ? FOR UPDATE
                """, (rs, row) -> new LockedApplication(rs.getLong("id"), rs.getString("business_name"),
                rs.getString("registration_number"), rs.getString("contact_name"), rs.getString("email"),
                rs.getString("phone"), rs.getString("address"), rs.getString("password_hash"),
                rs.getString("license_original_name"), rs.getString("license_content_type"), rs.getString("status")), id);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy hồ sơ doanh nghiệp.");
        LockedApplication application = matches.get(0);
        if (!"PENDING".equals(application.status())) throw new ApiException(HttpStatus.CONFLICT, "Hồ sơ này đã được xử lý.");
        String normalizedReason = reason == null ? "" : reason.trim();
        if (!approved && normalizedReason.isBlank()) throw ApiException.badRequest("Hãy ghi lý do từ chối để gửi cho doanh nghiệp.");

        Long shopId = null;
        if (approved) {
            Integer accountExists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class, application.email());
            if (accountExists != null && accountExists > 0) throw new ApiException(HttpStatus.CONFLICT, "Email này đã được dùng cho một tài khoản khác.");
            shopId = jdbc.queryForObject("""
                    INSERT INTO shops (name, address, latitude, longitude, status)
                    VALUES (?, ?, 0, 0, 'ACTIVE') RETURNING id
                    """, Long.class, application.businessName(), application.address());
            Long userId = jdbc.queryForObject("""
                    INSERT INTO users (email, phone, password_hash, full_name, role, shop_id, enabled)
                    VALUES (?, ?, ?, ?, 'SHOP_OWNER', ?, TRUE) RETURNING id
                    """, Long.class, application.email(), application.phone(), application.passwordHash(),
                    application.contactName(), shopId);
            jdbc.update("UPDATE shops SET owner_user_id = ? WHERE id = ?", userId, shopId);
        }

        boolean notificationSent = mailer.sendDecision(application.email(), application.contactName(), application.businessName(), approved, normalizedReason);
        String status = approved ? "APPROVED" : "REJECTED";
        jdbc.update("""
                UPDATE business_applications
                SET status = ?, rejection_reason = ?, password_hash = NULL, shop_id = ?, reviewed_by = ?, reviewed_at = NOW()
                WHERE id = ?
                """, status, approved ? null : normalizedReason, shopId, adminId, id);
        Instant reviewedAt = Instant.now();
        return new ApplicationSummary(id, application.businessName(), application.registrationNumber(),
                application.contactName(), application.email(), application.phone(), application.address(),
                application.licenseOriginalName(), application.licenseContentType(), status,
                approved ? null : normalizedReason, reviewedAt, reviewedAt, shopId, notificationSent);
    }

    @Transactional(readOnly = true)
    public LicenseDocument license(long id) {
        List<LicenseMetadata> matches = jdbc.query("""
                SELECT license_file, license_original_name, license_content_type
                FROM business_applications WHERE id = ?
                """, (rs, row) -> new LicenseMetadata(rs.getString("license_file"),
                rs.getString("license_original_name"), rs.getString("license_content_type")), id);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy hồ sơ doanh nghiệp.");
        LicenseMetadata metadata = matches.get(0);
        return new LicenseDocument(licenseStorage.load(metadata.storedName()), metadata.originalName(), metadata.contentType());
    }

    private static void validate(String name, String registration, String contact, String email,
                                 String phone, String address, String password) {
        if (name.length() < 2 || name.length() > 255) throw ApiException.badRequest("Tên doanh nghiệp cần dài từ 2 đến 255 ký tự.");
        if (registration.length() < 3 || registration.length() > 64) throw ApiException.badRequest("Mã số đăng ký kinh doanh không hợp lệ.");
        if (contact.length() < 2 || contact.length() > 255) throw ApiException.badRequest("Tên người liên hệ không hợp lệ.");
        if (email.length() > 255 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw ApiException.badRequest("Email không hợp lệ.");
        if (phone.length() < 7 || phone.length() > 32) throw ApiException.badRequest("Số điện thoại không hợp lệ.");
        if (address.length() < 5 || address.length() > 512) throw ApiException.badRequest("Địa chỉ cần dài từ 5 đến 512 ký tự.");
        if (password == null || password.length() < 8 || password.length() > 128) throw ApiException.badRequest("Mật khẩu cần dài từ 8 đến 128 ký tự.");
    }

    public record ApplicationReceipt(long id, String status, Instant createdAt) {}
    public record ApplicationSummary(long id, String businessName, String registrationNumber, String contactName,
                                    String email, String phone, String address, String licenseOriginalName,
                                    String licenseContentType, String status, String rejectionReason,
                                    Instant createdAt, Instant reviewedAt, Long shopId, Boolean notificationSent) {}
    public record LicenseDocument(Resource resource, String originalName, String contentType) {}
    private record LicenseMetadata(String storedName, String originalName, String contentType) {}
    private record LockedApplication(long id, String businessName, String registrationNumber, String contactName,
                                     String email, String phone, String address, String passwordHash,
                                     String licenseOriginalName, String licenseContentType, String status) {}
}
