package com.goldentime.api.notifications;

import com.goldentime.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationService.Inbox inbox(Authentication authentication,
                                           @RequestParam(defaultValue = "20") int limit) {
        return notifications.inbox(CurrentUser.id(authentication), limit);
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable long id, Authentication authentication) {
        notifications.markRead(CurrentUser.id(authentication), id);
    }

    @PostMapping("/read-all")
    public MarkAllReadResponse markAllRead(Authentication authentication) {
        return new MarkAllReadResponse(notifications.markAllRead(CurrentUser.id(authentication)));
    }

    public record MarkAllReadResponse(int updatedCount) {}
}
