package com.nick.jarvis.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nick.jarvis.security.CurrentUser;

@RestController
@RequestMapping("/api/users/me/settings")
public class UserSettingsController {

    private final UserSettingsService settingsService;

    public UserSettingsController(UserSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public UserSettingsService.UserSettings get() {
        return settingsService.get(CurrentUser.id());
    }

    @PutMapping
    public UserSettingsService.UserSettings update(@RequestBody UpdateRequest request) {
        return settingsService.update(CurrentUser.id(), request.email(), request.emailReminder());
    }

    @PostMapping("/test-email")
    public ResponseEntity<Void> testEmail() {
        settingsService.sendTestEmail(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    public record UpdateRequest(String email, boolean emailReminder) {
    }
}
