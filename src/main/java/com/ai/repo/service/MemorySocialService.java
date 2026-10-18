package com.ai.repo.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ai.repo.common.PageResult;
import com.ai.repo.dto.MemoryFeedItem;
import com.ai.repo.dto.MemorySocialSummary;
import com.ai.repo.entity.Memory;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.MemoryMapper;
import com.ai.repo.mapper.MemorySocialMapper;
import jakarta.annotation.Resource;

@Service
public class MemorySocialService {
    @Resource private MemoryMapper memoryMapper;
    @Resource private MemorySocialMapper socialMapper;

    public static boolean isSocial(Memory memory) {
        return memory != null && Boolean.TRUE.equals(memory.getIsPublic())
                && "GENERAL".equals(memory.getMemoryType())
                && "PUBLIC".equals(memory.getSharingScope()) && "VISIBLE".equals(memory.getStatus());
    }

    public void requirePublic(Long id, boolean lock) {
        Memory memory = lock ? memoryMapper.selectByIdForUpdate(id) : memoryMapper.selectById(id);
        if (!isSocial(memory)) throw new BusinessException(404, "Public Memory not found");
    }

    public PageResult<MemoryFeedItem> feed(int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw new BusinessException(400, "Invalid pagination");
        return new PageResult<>(socialMapper.feed(size, (long) (page - 1) * size),
                socialMapper.countFeed(), (long) page, (long) size);
    }

    @Transactional
    public MemorySocialSummary summary(Long id, Long agentId) {
        requirePublic(id, true);
        return counts(id, agentId);
    }

    @Transactional
    public MemorySocialSummary setLike(Long id, Long agentId, boolean liked) {
        if (agentId == null) throw new BusinessException(403, "Agent authentication required");
        requirePublic(id, true);
        if (liked) socialMapper.like(id, agentId);
        else socialMapper.unlike(id, agentId);
        return counts(id, agentId);
    }

    private MemorySocialSummary counts(Long id, Long agentId) {
        return new MemorySocialSummary(socialMapper.countLikes(id), socialMapper.countComments(id),
                agentId != null && socialMapper.hasLike(id, agentId) > 0);
    }
}
