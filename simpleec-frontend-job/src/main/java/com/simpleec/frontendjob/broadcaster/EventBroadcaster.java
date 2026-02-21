package com.simpleec.frontendjob.broadcaster;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventBroadcaster {
    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, SseEmitter>> emittersByMerchant = new ConcurrentHashMap<>();
    private final Map<String, String> clientToMerchant = new ConcurrentHashMap<>();

    public SseEmitter registerClient(String merchantId, String clientId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        emittersByMerchant.computeIfAbsent(merchantId, k -> new ConcurrentHashMap<>()).put(clientId, emitter);
        clientToMerchant.put(clientId, merchantId);
        
        emitter.onCompletion(() -> handleClientDisconnect(clientId));
        emitter.onTimeout(() -> handleClientDisconnect(clientId));
        emitter.onError(throwable -> {
            log.warn("SSE error for client {}: {}", clientId, throwable.getMessage());
            handleClientDisconnect(clientId);
        });
        
        log.info("Client registered: {} (merchant: {})", clientId, merchantId);
        return emitter;
    }

    public void broadcastToMerchant(String merchantId, FrontendEvent event) {
        Map<String, SseEmitter> clients = emittersByMerchant.getOrDefault(merchantId, Collections.emptyMap());
        if (clients.isEmpty()) {
            log.debug("No active clients for merchant: {}", merchantId);
            return;
        }
        
        log.info("Broadcasting event to {} clients (merchant: {}): {}", clients.size(), merchantId, event.getTaskType());
        clients.entrySet().parallelStream().forEach(entry -> {
            try {
                entry.getValue().send(SseEmitter.event()
                    .id(event.getRequestId())
                    .name(event.getTaskType())
                    .data(objectMapper.writeValueAsString(event))
                    .build());
            } catch (IOException e) {
                log.warn("Failed to send event to client {}: {}", entry.getKey(), e.getMessage());
                handleClientDisconnect(entry.getKey());
            }
        });
    }

    public void broadcastToAll(FrontendEvent event) {
        emittersByMerchant.forEach((merchantId, clients) -> broadcastToMerchant(merchantId, event));
    }

    public Map<String, Integer> getConnectionStats() {
        return emittersByMerchant.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size()));
    }

    private void handleClientDisconnect(String clientId) {
        String merchantId = clientToMerchant.remove(clientId);
        if (merchantId != null) {
            Map<String, SseEmitter> clients = emittersByMerchant.get(merchantId);
            if (clients != null) {
                clients.remove(clientId);
                log.info("Client disconnected: {} (merchant: {})", clientId, merchantId);
                if (clients.isEmpty()) {
                    emittersByMerchant.remove(merchantId);
                }
            }
        }
    }
}
