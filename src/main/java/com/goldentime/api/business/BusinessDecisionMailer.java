package com.goldentime.api.business;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class BusinessDecisionMailer {
    private static final Logger log = LoggerFactory.getLogger(BusinessDecisionMailer.class);
    private final JavaMailSender mailSender;
    private final String from;
    private final String portalUrl;

    public BusinessDecisionMailer(JavaMailSender mailSender,
                                  @Value("${app.mail.from:}") String from,
                                  @Value("${app.business.portal-url:}") String portalUrl) {
        this.mailSender = mailSender;
        this.from = from == null ? "" : from.trim();
        this.portalUrl = portalUrl == null ? "" : portalUrl.trim();
    }

    public boolean sendDecision(String email, String contactName, String businessName, boolean approved, String reason) {
        if (from.isBlank()) {
            log.warn("Skipping business decision email because MAIL_FROM is not configured.");
            return false;
        }
        String subject = approved
                ? "Golden Time đã duyệt đăng ký doanh nghiệp của bạn"
                : "Kết quả đăng ký doanh nghiệp Golden Time";
        String message = approved
                ? "Xin chào " + contactName + ",\n\n"
                    + "Đăng ký doanh nghiệp “" + businessName + "” đã được Golden Time chấp nhận. "
                    + "Bạn hiện có thể đăng nhập vào cổng doanh nghiệp bằng email và mật khẩu đã đăng ký."
                    + (portalUrl.isBlank() ? "\n\nCổng doanh nghiệp: /business/login" : "\n\nCổng doanh nghiệp: " + portalUrl)
                : "Xin chào " + contactName + ",\n\n"
                    + "Đăng ký doanh nghiệp “" + businessName + "” chưa được chấp nhận.\n"
                    + "Lý do: " + (reason == null || reason.isBlank() ? "Vui lòng liên hệ Golden Time để biết thêm thông tin." : reason.trim())
                    + "\n\nBạn có thể cập nhật hồ sơ và gửi đăng ký lại.";
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(message, false);
            mailSender.send(mime);
            return true;
        } catch (MessagingException | MailException exception) {
            log.warn("Business decision was saved, but the notification email could not be sent.", exception);
            return false;
        }
    }
}
