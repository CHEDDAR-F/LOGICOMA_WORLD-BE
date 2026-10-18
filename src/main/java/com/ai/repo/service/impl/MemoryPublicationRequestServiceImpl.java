package com.ai.repo.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ai.repo.entity.Memory;
import com.ai.repo.entity.MemoryPublicationRequest;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.MemoryMapper;
import com.ai.repo.mapper.MemoryPublicationRequestMapper;
import com.ai.repo.service.MemoryPublicationRequestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class MemoryPublicationRequestServiceImpl implements MemoryPublicationRequestService {
    private static final SecureRandom RANDOM = new SecureRandom();
    @Resource private MemoryMapper memoryMapper;
    @Resource private com.ai.repo.mapper.MemoryImageMapper imageMapper;
    @Resource private MemoryPublicationRequestMapper requestMapper;
    @Resource private ObjectMapper objectMapper;
    @Value("${app.frontend-url:http://localhost:3000}") private String frontendUrl;

    @Override
    @Transactional
    public Created create(Long agentId, Long userId, Long memoryId) {
        Memory memory = owned(memoryMapper.selectByIdForUpdate(memoryId), userId, agentId);
        requireEligible(memory);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String requestId = "mpr_" + HexFormat.of().formatHex(random);
        MemoryPublicationRequest request = new MemoryPublicationRequest();
        request.setTokenHash(hash(requestId));
        request.setUserId(userId);
        request.setAgentId(agentId);
        request.setMemoryId(memoryId);
        request.setContentHash(contentHash(memory));
        request.setStatus("PENDING");
        request.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        requestMapper.insert(request);
        return new Created(requestId, frontendUrl.replaceAll("/+$", "")
                + "/approve/memory-publication/" + requestId, memoryId, request.getExpiresAt());
    }

    @Override
    public Details details(String requestId, Long userId) {
        MemoryPublicationRequest request = request(requestId);
        requireOwner(request, userId);
        Memory memory = owned(memoryMapper.selectById(request.getMemoryId()), userId, request.getAgentId());
        return new Details(effectiveStatus(request, memory), memory, request.getExpiresAt());
    }

    @Override
    public Status status(String requestId, Long agentId) {
        MemoryPublicationRequest request = request(requestId);
        if (!request.getAgentId().equals(agentId)) throw new BusinessException(403, "Request belongs to another Agent");
        Memory memory = owned(memoryMapper.selectById(request.getMemoryId()), request.getUserId(), agentId);
        return new Status(effectiveStatus(request, memory), request.getMemoryId(), request.getExpiresAt());
    }

    @Override
    @Transactional
    public void approve(String requestId, Long userId) {
        MemoryPublicationRequest request = request(requestId);
        requireOwner(request, userId);
        // Lock the exact Memory until the decision and visibility change commit together.
        Memory memory = owned(memoryMapper.selectByIdForUpdate(request.getMemoryId()), userId, request.getAgentId());
        if (!"PENDING".equals(effectiveStatus(request, memory))) {
            throw new BusinessException(409, "Memory changed or approval is no longer pending; request a new link");
        }
        requireEligible(memory);
        if (requestMapper.decide(request.getId(), userId, "APPROVED") != 1
                || memoryMapper.publishPrivateGeneral(memory.getId(), userId, request.getAgentId()) != 1) {
            throw new BusinessException(409, "Memory publication could not be completed");
        }
    }

    @Override
    public void reject(String requestId, Long userId) {
        MemoryPublicationRequest request = request(requestId);
        requireOwner(request, userId);
        if (requestMapper.decide(request.getId(), userId, "REJECTED") != 1) {
            throw new BusinessException(409, "Approval is no longer pending");
        }
    }

    private MemoryPublicationRequest request(String requestId) {
        if (requestId == null || !requestId.matches("mpr_[0-9a-f]{64}")) throw new BusinessException(404, "Request not found");
        MemoryPublicationRequest request = requestMapper.selectByTokenHash(hash(requestId));
        if (request == null) throw new BusinessException(404, "Request not found");
        return request;
    }

    private Memory owned(Memory memory, Long userId, Long agentId) {
        if (memory == null) throw new BusinessException(404, "Memory not found");
        if (!userId.equals(memory.getUserId()) || !agentId.equals(memory.getAgentId())) {
            throw new BusinessException(403, "Memory belongs to another Agent or user");
        }
        return memory;
    }

    private void requireOwner(MemoryPublicationRequest request, Long userId) {
        if (!request.getUserId().equals(userId)) throw new BusinessException(403, "Request belongs to another user");
    }

    private void requireEligible(Memory memory) {
        if (!"GENERAL".equals(memory.getMemoryType()) || Boolean.TRUE.equals(memory.getIsPublic())
                || !"VISIBLE".equals(memory.getStatus())) {
            throw new BusinessException(409, "Only private visible GENERAL Memory can be published; USER_PROFILE stays non-public");
        }
    }

    private String effectiveStatus(MemoryPublicationRequest request, Memory memory) {
        if (!"PENDING".equals(request.getStatus())) return request.getStatus();
        if (!request.getExpiresAt().isAfter(LocalDateTime.now())) return "EXPIRED";
        if (Boolean.TRUE.equals(memory.getIsPublic()) || !"VISIBLE".equals(memory.getStatus())
                || !"GENERAL".equals(memory.getMemoryType())
                || !request.getContentHash().equals(contentHash(memory))) return "CHANGED";
        return "PENDING";
    }

    private String contentHash(Memory memory) {
        // Engagement counters and timestamps are not the disclosure being reviewed.
        try {
            return hash(objectMapper.writeValueAsString(Arrays.asList(memory.getTitle(), memory.getContent(),
                    memory.getDescription(), memory.getVersion(), memory.getTags(), memory.getCategory(),
                    memory.getMetadata(), memory.getFilePath(), memory.getFileSize(), memory.getMimeType(),
                    memory.getSharingScope(), memory.getClientMemoryKey(),
                    imageMapper.listForReview(memory.getId()).stream().map(i -> Arrays.asList(i.getId(), i.getSha256(), i.getWidth(), i.getHeight(), i.getByteSize())).toList())));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot hash Memory", e); }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
