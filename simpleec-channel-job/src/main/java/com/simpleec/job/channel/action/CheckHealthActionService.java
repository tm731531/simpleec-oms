package com.simpleec.job.channel.action;

import com.simpleec.job.channel.model.Resource;
import com.simpleec.job.channel.service.SyncLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CheckHealthActionService implements ActionService {

    private final SyncLogService syncLogService;

    private Resource resource;

    @Override
    public String getAction() {
        return "CHECK_HEALTH";
    }

    @Override
    public void setting(Resource resource) {
        this.resource = resource;
    }

    @Override
    public void getPlatformTokens() {
        // Load platform credential + channel token from DB
        // Placeholder — real implementation in Phase 5
    }

    @Override
    public void verifyNeedData() {
        // Verify channel is active
    }

    @Override
    public void doAction() {
        String channelId = resource.getMsg().getOwnerId();
        String health;
        try {
            // Placeholder: will call adapter.validateConnection(tokens) in Phase 5
            health = "OK";
        } catch (Exception e) {
            health = "API_DOWN";
            log.warn("Health check failed for channel {}: {}", channelId, e.getMessage());
        }
        syncLogService.logHealth(resource.getMsg(), health);
        log.info("Health check: channel={}, health={}", channelId, health);
    }
}
