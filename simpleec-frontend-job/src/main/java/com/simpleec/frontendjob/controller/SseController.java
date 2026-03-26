package com.simpleec.frontendjob.controller;

import com.simpleec.frontendjob.broadcaster.EventBroadcaster;
import com.simpleec.frontendjob.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class SseController {
    private final EventBroadcaster eventBroadcaster;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> stream(@AuthenticationPrincipal UserPrincipal principal,
                                    @RequestParam String merchantId,
                                    @RequestParam(defaultValue = "unknown") String clientId) {
        if (principal == null) {
            log.warn("SSE stream request rejected: missing or invalid JWT");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!principal.getMerchantId().equals(merchantId)) {
            log.warn("SSE stream request rejected: principal merchantId {} does not match requested {}",
                principal.getMerchantId(), merchantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (clientId == null || clientId.trim().isEmpty()) {
            clientId = "client-" + System.currentTimeMillis();
        }

        log.info("New SSE client connected: {} (merchant: {})", clientId, merchantId);
        SseEmitter emitter = eventBroadcaster.registerClient(merchantId, clientId);

        try {
            emitter.send(SseEmitter.event()
                .id(System.currentTimeMillis() + "")
                .name("CONNECTED")
                .data("{\"message\":\"Connected to event stream\",\"clientId\":\"" + clientId + "\"}")
                .build());
        } catch (Exception e) {
            log.warn("Failed to send connection confirmation", e);
        }

        return ResponseEntity.ok(emitter);
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ResponseEntity.ok(eventBroadcaster.getConnectionStats());
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok("{\"status\":\"UP\",\"service\":\"frontend-job\"}");
    }
}
