package com.goldentime.api.business;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.articles.ArticleImageStorage;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class BusinessApplicationService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final BusinessLicenseStorage licenseStorage;
    private final ArticleImageStorage imageStorage;
    private final BusinessDecisionMailer mailer;

    public BusinessApplicationService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                      BusinessLicenseStorage licenseStorage, ArticleImageStorage imageStorage,
                                      BusinessDecisionMailer mailer) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.licenseStorage = licenseStorage;
        this.imageStorage = imageStorage;
        this.mailer = mailer;
    }

    @Transactional
    public ApplicationReceipt submit(String businessName, String registrationNumber, String contactName,
                                     String emailValue, String phone, String address, Double latitude,
                                     Double longitude, String googlePlaceId, String password,
                                     String representativePhone, MultipartFile licenseFile, MultipartFile logoFile) {
        String email = emailValue.trim().toLowerCase(Locale.ROOT);
        String name = businessName.trim();
        String registration = registrationNumber == null ? "" : registrationNumber.trim();
        String contact = contactName.trim();
        String normalizedPhone = phone.trim();
        String normalizedRepresentativePhone = representativePhone == null || representativePhone.isBlank()
                ? normalizedPhone : representativePhone.trim();
        String normalizedAddress = address.trim();
        validate(name, registration, contact, email, normalizedPhone, normalizedRepresentativePhone, normalizedAddress, password);
        if ((latitude == null) != (longitude == null)
                || (latitude != null && !validCoordinates(latitude, longitude))) {
            throw ApiException.badRequest("Tọa độ cửa hàng không hợp lệ.");
        }
        String normalizedPlaceId = normalizePlaceId(googlePlaceId);

        Integer accountExists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class, email);
        if (accountExists != null && accountExists > 0) throw new ApiException(HttpStatus.CONFLICT, "Email này đã có tài khoản.");
        Integer applicationExists = jdbc.queryForObject("SELECT COUNT(*) FROM business_applications WHERE LOWER(email) = ? AND status = 'PENDING'", Integer.class, email);
        if (applicationExists != null && applicationExists > 0) throw new ApiException(HttpStatus.CONFLICT, "Email này đang có hồ sơ chờ duyệt.");

        if (logoFile == null || logoFile.isEmpty()) throw ApiException.badRequest("Vui lòng tải logo doanh nghiệp lên.");
        BusinessLicenseStorage.StoredLicense license = licenseFile == null || licenseFile.isEmpty()
                ? null : licenseStorage.save(licenseFile);
        try {
            String logoUrl = logoFile == null || logoFile.isEmpty() ? null : imageStorage.saveBusinessLogo(logoFile).url();
            Long id = jdbc.queryForObject("""
                    INSERT INTO business_applications
                        (business_name, registration_number, tax_code, contact_name, representative_phone, email, phone, address,
                         latitude, longitude, google_place_id,
                         password_hash, license_file, license_original_name, license_content_type, logo_url)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, name, registration.isBlank() ? null : registration,
                    registration.isBlank() ? null : registration, contact, normalizedRepresentativePhone,
                    email, normalizedPhone, normalizedAddress, latitude, longitude, normalizedPlaceId,
                    passwordEncoder.encode(password), license == null ? null : license.storedName(),
                    license == null ? null : license.originalName(), license == null ? null : license.contentType(), logoUrl);
            return new ApplicationReceipt(id, "PENDING", Instant.now());
        } catch (DuplicateKeyException exception) {
            if (license != null) licenseStorage.delete(license.storedName());
            throw new ApiException(HttpStatus.CONFLICT, "Email này đang có hồ sơ chờ duyệt.");
        } catch (RuntimeException exception) {
            if (license != null) licenseStorage.delete(license.storedName());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<ApplicationSummary> list() {
        return jdbc.query("""
                SELECT id, business_name, registration_number, tax_code, contact_name, representative_phone, email, phone, address,
                       license_original_name, license_content_type, status, rejection_reason, created_at,
                       reviewed_at, shop_id, notification_sent, latitude, longitude, google_place_id, logo_url
                FROM business_applications
                ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, created_at DESC, id DESC
                """, BusinessApplicationService::mapSummary);
    }

    @Transactional
    public ApplicationSummary decide(long id, boolean approved, String reason,
                                     Double latitude, Double longitude, String address,
                                     String googlePlaceId, long adminId) {
        List<LockedApplication> matches = jdbc.query("""
                SELECT id, business_name, registration_number, tax_code, contact_name, representative_phone,
                       email, phone, address, password_hash, license_original_name, license_content_type,
                       status, latitude, longitude, google_place_id, logo_url
                FROM business_applications WHERE id = ? FOR UPDATE
                """, (rs, row) -> new LockedApplication(rs.getLong("id"), rs.getString("business_name"),
                rs.getString("registration_number"), rs.getString("tax_code"), rs.getString("contact_name"),
                rs.getString("representative_phone"), rs.getString("email"), rs.getString("phone"),
                rs.getString("address"), rs.getString("password_hash"),
                rs.getString("license_original_name"), rs.getString("license_content_type"), rs.getString("status"),
                (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude"), rs.getString("google_place_id"),
                rs.getString("logo_url")), id);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy hồ sơ doanh nghiệp.");
        LockedApplication application = matches.get(0);
        if (!"PENDING".equals(application.status())) throw new ApiException(HttpStatus.CONFLICT, "Hồ sơ này đã được xử lý.");
        String normalizedReason = reason == null ? "" : reason.trim();
        if (!approved && normalizedReason.isBlank()) throw ApiException.badRequest("Hãy ghi lý do từ chối để gửi cho doanh nghiệp.");
        String normalizedAddress = address == null ? application.address() : address.trim();
        Double resolvedLatitude = latitude == null ? application.latitude() : latitude;
        Double resolvedLongitude = longitude == null ? application.longitude() : longitude;
        String resolvedPlaceId = normalizePlaceId(googlePlaceId);
        if (approved && (normalizedAddress.length() < 5 || normalizedAddress.length() > 512)) {
            throw ApiException.badRequest("Địa chỉ cửa hàng cần dài từ 5 đến 512 ký tự.");
        }
        if (approved && !validCoordinates(resolvedLatitude, resolvedLongitude)) {
            throw ApiException.badRequest("Không xác định được tọa độ hợp lệ từ địa chỉ doanh nghiệp đã đăng ký.");
        }

        Long shopId = null;
        if (approved) {
            Integer accountExists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class, application.email());
            if (accountExists != null && accountExists > 0) throw new ApiException(HttpStatus.CONFLICT, "Email này đã được dùng cho một tài khoản khác.");
            shopId = jdbc.queryForObject("""
                    INSERT INTO shops (name, address, latitude, longitude, google_place_id, status,
                                       business_email, business_phone, tax_code, representative_name,
                                       representative_phone, logo_url)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, application.businessName(), normalizedAddress, resolvedLatitude,
                    resolvedLongitude, resolvedPlaceId, application.email(), application.phone(), application.taxCode(),
                    application.contactName(), application.representativePhone(), application.logoUrl());
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
                SET status = ?, rejection_reason = ?, password_hash = NULL, shop_id = ?, reviewed_by = ?,
                    reviewed_at = NOW(), notification_sent = ?,
                    address = ?, latitude = ?, longitude = ?, google_place_id = ?
                WHERE id = ?
                """, status, approved ? null : normalizedReason, shopId, adminId, notificationSent,
                approved ? normalizedAddress : application.address(),
                approved ? resolvedLatitude : application.latitude(), approved ? resolvedLongitude : application.longitude(),
                approved ? resolvedPlaceId : application.googlePlaceId(), id);
        Instant reviewedAt = Instant.now();
        return new ApplicationSummary(id, application.businessName(), application.registrationNumber(), application.taxCode(),
                application.contactName(), application.representativePhone(), application.email(), application.phone(), approved ? normalizedAddress : application.address(),
                application.licenseOriginalName(), application.licenseContentType(), status,
                approved ? null : normalizedReason, reviewedAt, reviewedAt, shopId, notificationSent,
                approved ? resolvedLatitude : application.latitude(),
                approved ? resolvedLongitude : application.longitude(), approved ? resolvedPlaceId : application.googlePlaceId(),
                application.logoUrl());
    }

    @Transactional
    public ApplicationSummary resendNotification(long id) {
        List<ApplicationSummary> matches = jdbc.query("""
                SELECT id, business_name, registration_number, tax_code, contact_name, representative_phone, email, phone, address,
                       license_original_name, license_content_type, status, rejection_reason, created_at,
                       reviewed_at, shop_id, notification_sent, latitude, longitude, google_place_id, logo_url
                FROM business_applications WHERE id = ? FOR UPDATE
                """, BusinessApplicationService::mapSummary, id);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy hồ sơ doanh nghiệp.");
        ApplicationSummary application = matches.get(0);
        if ("PENDING".equals(application.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Chỉ có thể gửi lại email sau khi hồ sơ đã được xử lý.");
        }
        if (Boolean.TRUE.equals(application.notificationSent())) {
            throw new ApiException(HttpStatus.CONFLICT, "Email thông báo của hồ sơ này đã được gửi.");
        }

        boolean approved = "APPROVED".equals(application.status());
        boolean sent = mailer.sendDecision(application.email(), application.contactName(), application.businessName(),
                approved, application.rejectionReason());
        if (sent) jdbc.update("UPDATE business_applications SET notification_sent = TRUE WHERE id = ?", id);
        return new ApplicationSummary(application.id(), application.businessName(), application.registrationNumber(), application.taxCode(),
                application.contactName(), application.representativePhone(), application.email(), application.phone(), application.address(),
                application.licenseOriginalName(), application.licenseContentType(), application.status(),
                application.rejectionReason(), application.createdAt(), application.reviewedAt(), application.shopId(), sent,
                application.latitude(), application.longitude(), application.googlePlaceId(), application.logoUrl());
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
        if (metadata.storedName() == null) throw ApiException.notFound("Hồ sơ này không đính kèm giấy phép kinh doanh.");
        return new LicenseDocument(licenseStorage.load(metadata.storedName()), metadata.originalName(), metadata.contentType());
    }

    private static void validate(String name, String registration, String contact, String email,
                                 String phone, String representativePhone, String address, String password) {
        if (name.length() < 2 || name.length() > 255) throw ApiException.badRequest("Tên doanh nghiệp cần dài từ 2 đến 255 ký tự.");
        if (!registration.isBlank() && (registration.length() < 3 || registration.length() > 64)) throw ApiException.badRequest("Mã số thuế không hợp lệ.");
        if (contact.length() < 2 || contact.length() > 255) throw ApiException.badRequest("Tên người liên hệ không hợp lệ.");
        if (email.length() > 255 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw ApiException.badRequest("Email không hợp lệ.");
        if (phone.length() < 7 || phone.length() > 32) throw ApiException.badRequest("Số điện thoại không hợp lệ.");
        if (representativePhone.length() < 7 || representativePhone.length() > 32) throw ApiException.badRequest("Số điện thoại người đại diện không hợp lệ.");
        if (address.length() < 5 || address.length() > 512) throw ApiException.badRequest("Địa chỉ cần dài từ 5 đến 512 ký tự.");
        if (password == null || password.length() < 8 || password.length() > 128) throw ApiException.badRequest("Mật khẩu cần dài từ 8 đến 128 ký tự.");
    }

    private static boolean validCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    private static String normalizePlaceId(String placeId) {
        if (placeId == null || placeId.isBlank()) return null;
        String normalized = placeId.trim();
        if (normalized.length() > 255) throw ApiException.badRequest("Mã địa điểm Google Maps không hợp lệ.");
        return normalized;
    }

    public record ApplicationReceipt(long id, String status, Instant createdAt) {}
    public record ApplicationSummary(long id, String businessName, String registrationNumber, String taxCode,
                                    String contactName, String representativePhone, String email, String phone,
                                    String address, String licenseOriginalName,
                                    String licenseContentType, String status, String rejectionReason,
                                    Instant createdAt, Instant reviewedAt, Long shopId, Boolean notificationSent,
                                    Double latitude, Double longitude, String googlePlaceId, String logoUrl) {}
    public record LicenseDocument(Resource resource, String originalName, String contentType) {}
    private record LicenseMetadata(String storedName, String originalName, String contentType) {}
    private record LockedApplication(long id, String businessName, String registrationNumber, String taxCode,
                                     String contactName, String representativePhone, String email, String phone,
                                     String address, String passwordHash,
                                     String licenseOriginalName, String licenseContentType, String status,
                                     Double latitude, Double longitude, String googlePlaceId, String logoUrl) {}

    private static ApplicationSummary mapSummary(ResultSet rs, int row) throws SQLException {
        return new ApplicationSummary(rs.getLong("id"), rs.getString("business_name"),
                rs.getString("registration_number"), rs.getString("tax_code"), rs.getString("contact_name"),
                rs.getString("representative_phone"), rs.getString("email"), rs.getString("phone"),
                rs.getString("address"), rs.getString("license_original_name"),
                rs.getString("license_content_type"), rs.getString("status"), rs.getString("rejection_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
                (Long) rs.getObject("shop_id"), rs.getBoolean("notification_sent"),
                (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude"), rs.getString("google_place_id"),
                rs.getString("logo_url"));
    }
}
