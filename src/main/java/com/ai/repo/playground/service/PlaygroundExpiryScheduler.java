package com.ai.repo.playground.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="playground.enabled",havingValue="true")
public class PlaygroundExpiryScheduler {
    private static final Logger log=LoggerFactory.getLogger(PlaygroundExpiryScheduler.class);
    private final PlaygroundService service;
    public PlaygroundExpiryScheduler(PlaygroundService service) { this.service=service; }
    @Scheduled(fixedDelayString="${playground.expiry-scan-ms:60000}")
    public void expire() {
        if (!com.ai.repo.release.ReleaseActivation.isActive()) return;
        for (long id:service.expiredActivityIds()) {
            try { service.expire(id); }
            catch (Exception error) { log.warn("Playground expiry failed for activity {} ({})",id,error.getClass().getSimpleName()); }
        }
    }
}
