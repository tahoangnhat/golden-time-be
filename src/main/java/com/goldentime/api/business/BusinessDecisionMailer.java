package com.goldentime.api.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Service
public class BusinessDecisionMailer {
    private static final Logger log = LoggerFactory.getLogger(BusinessDecisionMailer.class);
    private static final ObjectMapper ERROR_JSON = new ObjectMapper();
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GMAIL_SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

    private final JavaMailSender mailSender;
    private final RestClient restClient;
    private final String from;
    private final String portalUrl;
    private final String gmailClientId;
    private final String gmailClientSecret;
    private final String gmailRefreshToken;

    public BusinessDecisionMailer(JavaMailSender mailSender,
                                  @Value("${app.mail.from:}") String from,
                                  @Value("${app.business.portal-url:}") String portalUrl,
                                  @Value("${app.mail.gmail.client-id:}") String gmailClientId,
                                  @Value("${app.mail.gmail.client-secret:}") String gmailClientSecret,
                                  @Value("${app.mail.gmail.refresh-token:}") String gmailRefreshToken) {
        this.mailSender = mailSender;
        this.restClient = RestClient.create();
        this.from = clean(from);
        this.portalUrl = clean(portalUrl);
        this.gmailClientId = clean(gmailClientId);
        this.gmailClientSecret = clean(gmailClientSecret);
        this.gmailRefreshToken = clean(gmailRefreshToken);
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
            MimeMessage mime = createMessage(email, subject, message);
            if (gmailApiConfigured()) {
                sendWithGmailApi(mime);
            } else if (gmailApiPartiallyConfigured()) {
                log.warn("Gmail API email was not sent because one or more GMAIL_OAUTH_* variables are missing.");
                return false;
            } else {
                mailSender.send(mime);
            }
            return true;
        } catch (GoogleRequestException exception) {
            log.warn("Business decision was saved, but {} returned HTTP {} ({}).",
                    exception.operation, exception.response.getStatusCode().value(),
                    summarizeGoogleError(exception.response));
            return false;
        } catch (RestClientResponseException exception) {
            log.warn("Business decision was saved, but a Google request returned HTTP {} ({}).",
                    exception.getStatusCode().value(), summarizeGoogleError(exception));
            return false;
        } catch (MessagingException | RuntimeException exception) {
            log.warn("Business decision was saved, but the notification email could not be sent.", exception);
            return false;
        } catch (Exception exception) {
            log.warn("Business decision was saved, but the notification email could not be sent.", exception);
            return false;
        }
    }

    private MimeMessage createMessage(String email, String subject, String message) throws MessagingException {
        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(subject);
        helper.setText(message, false);
        return mime;
    }

    private void sendWithGmailApi(MimeMessage message) throws Exception {
        String accessToken = refreshAccessToken();
        ByteArrayOutputStream messageBytes = new ByteArrayOutputStream();
        message.writeTo(messageBytes);
        String rawMessage = Base64.getUrlEncoder().withoutPadding().encodeToString(messageBytes.toByteArray());

        try {
            restClient.post()
                    .uri(GMAIL_SEND_URL)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("raw", rawMessage))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new GoogleRequestException("Gmail message send", exception);
        }
    }

    private String refreshAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", gmailClientId);
        form.add("client_secret", gmailClientSecret);
        form.add("refresh_token", gmailRefreshToken);
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw new GoogleRequestException("OAuth token refresh", exception);
        }
        String accessToken = response == null ? "" : response.path("access_token").asText("");
        if (accessToken.isBlank()) throw new IllegalStateException("Google OAuth did not return an access token.");
        return accessToken;
    }

    private boolean gmailApiConfigured() {
        return !gmailClientId.isBlank() && !gmailClientSecret.isBlank() && !gmailRefreshToken.isBlank();
    }

    private boolean gmailApiPartiallyConfigured() {
        return !gmailClientId.isBlank() || !gmailClientSecret.isBlank() || !gmailRefreshToken.isBlank();
    }

    private static String summarizeGoogleError(RestClientResponseException exception) {
        try {
            JsonNode root = ERROR_JSON.readTree(exception.getResponseBodyAsString());
            JsonNode error = root.path("error");
            if (error.isTextual()) {
                String description = root.path("error_description").asText("");
                return logValue(error.asText()) + (description.isBlank() ? "" : ": " + logValue(description));
            }
            if (error.isObject()) {
                String status = error.path("status").asText("");
                String reason = error.path("errors").path(0).path("reason").asText("");
                String message = error.path("message").asText("");
                String summary = status.isBlank() ? "" : status;
                if (!reason.isBlank()) summary += (summary.isBlank() ? "" : ", ") + reason;
                if (!message.isBlank()) summary += (summary.isBlank() ? "" : ": ") + logValue(message);
                return summary.isBlank() ? "Google returned an unclassified error" : summary;
            }
        } catch (Exception ignored) {
            // Do not log raw response bodies: they may contain sensitive data.
        }
        return "Google returned no structured error details";
    }

    private static String logValue(String value) {
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160);
    }

    private static final class GoogleRequestException extends RuntimeException {
        private final String operation;
        private final RestClientResponseException response;

        private GoogleRequestException(String operation, RestClientResponseException response) {
            super(operation + " failed");
            this.operation = operation;
            this.response = response;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
