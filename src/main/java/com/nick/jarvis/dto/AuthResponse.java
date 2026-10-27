package com.nick.jarvis.dto;

import java.time.Instant;

public record AuthResponse(String token, Instant expiresAt, UserView user) {
}
