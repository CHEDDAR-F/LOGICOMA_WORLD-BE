package com.ai.repo.scheduler;

import com.ai.repo.entity.Agent;
import com.ai.repo.mapper.AgentMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AgentHeartbeatScheduler {

    private static final int OFFLINE_THRESHOLD_MINUTES = 90;
    
    @Resource
    private AgentMapper agentMapper;
    
    /**
     * Every 5 minutes, check for agents that haven't sent heartbeat in 90 minutes
     * and set their status to OFFLINE
     */
    @Scheduled(fixedRate = 300000)
    public void checkOfflineAgents() {
        if (!com.ai.repo.release.ReleaseActivation.isActive()) return;
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(OFFLINE_THRESHOLD_MINUTES);
        
        List<Agent> offlineAgents = agentMapper.findByLastHeartbeatBefore(threshold);
        
        for (Agent agent : offlineAgents) {
            if (!"OFFLINE".equals(agent.getStatus())) {
                agentMapper.updateAgentStatus(agent.getId(), "OFFLINE");
            }
        }
    }
}