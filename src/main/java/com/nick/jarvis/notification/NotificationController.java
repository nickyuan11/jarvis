package com.nick.jarvis.notification;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nick.jarvis.security.CurrentUser;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/unread")
    public List<NotificationService.NotificationView> unread() {
        return notificationService.unread(CurrentUser.id());
    }

    @PostMapping("/read")
    public ResponseEntity<Void> markRead(@RequestBody MarkReadRequest request) {
        notificationService.markRead(CurrentUser.id(), request.ids());
        return ResponseEntity.noContent().build();
    }

    public record MarkReadRequest(List<Long> ids) {
    }
}
