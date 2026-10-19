package com.ai.repo.service.impl;

import com.ai.repo.entity.Memory;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.MemoryMapper;
import com.ai.repo.service.MemoryService;
import com.ai.repo.util.UuidUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MemoryServiceImpl implements MemoryService {

    @Resource
    private MemoryMapper memoryMapper;

    @Override
    public Memory create(Memory memory) {
        if (memory.getUid() == null || memory.getUid().isEmpty()) {
            memory.setUid(UuidUtil.generate());
        }
        if (memory.getRevision() == null) {
            memory.setRevision(1);
        }
        memoryMapper.insert(memory);
        return memory;
    }

    @Override
    public Memory update(Memory memory) {
        if (memoryMapper.selectById(memory.getId()) == null) {
            throw new BusinessException("Memory not found");
        }
        memoryMapper.update(memory);
        Memory refreshed = memoryMapper.selectById(memory.getId());
        return refreshed != null ? refreshed : memory;
    }

    @Override
    public boolean delete(Long id) {
        if (memoryMapper.selectById(id) == null) {
            throw new BusinessException("Memory not found");
        }
        return memoryMapper.deleteById(id) > 0;
    }

    @Override
    public Memory findById(Long id) {
        return memoryMapper.selectById(id);
    }

    @Override
    public Memory findByUid(String uid) {
        return memoryMapper.selectByUid(uid);
    }

    @Override
    public List<Memory> findAll() {
        return memoryMapper.selectAll();
    }

    @Override
    public List<Memory> findByUserId(Long userId) {
        return memoryMapper.selectByUserId(userId);
    }

    @Override
    public List<Memory> findByUserIdVisibleToAgent(Long userId, Long agentId) {
        return memoryMapper.selectByUserIdVisibleToAgent(userId, agentId);
    }

    @Override
    public List<Memory> findByAgentId(Long agentId) {
        return memoryMapper.selectByAgentId(agentId);
    }

    @Override
    public List<Memory> findByCategory(String category) {
        return memoryMapper.selectByCategory(category);
    }

    @Override
    public List<Memory> findByCategoryVisibleToUser(String category, Long userId, Long agentId) {
        return memoryMapper.selectByCategoryVisibleToUser(category, userId, agentId);
    }

    @Override
    public List<Memory> findByPublic(Boolean isPublic) {
        return memoryMapper.selectByPublic(isPublic);
    }

    @Override
    public List<Memory> findByUserIdAndPublic(Long userId, Boolean isPublic) {
        return memoryMapper.selectByUserIdAndPublic(userId, isPublic);
    }

    @Override
    public List<Memory> findByAgentIdAndPublic(Long agentId, Boolean isPublic) {
        return memoryMapper.selectByAgentIdAndPublic(agentId, isPublic);
    }

    @Override
    public List<Memory> searchByKeyword(String keyword) {
        return memoryMapper.searchByKeyword(keyword);
    }

    @Override
    public List<Memory> searchPublicByKeyword(String keyword) {
        return memoryMapper.searchPublicByKeyword(keyword);
    }

    @Override
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("IDs cannot be null or empty");
        }
        return memoryMapper.batchDelete(ids);
    }

    @Override
    public int batchDeleteOwned(List<Long> ids, Long userId, Long agentId) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("IDs cannot be null or empty");
        }
        if (userId == null) {
            throw new BusinessException(401, "Authentication required");
        }
        return memoryMapper.batchDeleteOwned(ids, userId, agentId);
    }

    @Override
    public Memory upsert(Memory memory) {
        Memory existingMemory = memory.getClientMemoryKey() == null
                ? memoryMapper.selectByUserIdAndAgentIdAndTitle(memory.getUserId(), memory.getAgentId(), memory.getTitle())
                : memoryMapper.selectByUserIdAndAgentIdAndClientKey(
                        memory.getUserId(), memory.getAgentId(), memory.getClientMemoryKey());

        if (existingMemory != null) {
            memory.setId(existingMemory.getId());
            // Upload/upsert requests do not own engagement counters.
            memory.setDownloadCount(existingMemory.getDownloadCount());
            memory.setLikeCount(existingMemory.getLikeCount());
            memoryMapper.update(memory);
            Memory refreshed = memoryMapper.selectById(memory.getId());
            return refreshed != null ? refreshed : memory;
        } else {
            create(memory);
            Memory refreshed = memoryMapper.selectById(memory.getId());
            return refreshed != null ? refreshed : memory;
        }
    }

    @Override
    public boolean incrementDownloadCount(Long id) {
        Memory memory = memoryMapper.selectById(id);
        if (memory == null) {
            throw new BusinessException("Memory not found");
        }
        return memoryMapper.incrementDownloadCount(id) > 0;
    }

    @Override
    public boolean incrementLikeCount(Long id) {
        Memory memory = memoryMapper.selectById(id);
        if (memory == null) {
            throw new BusinessException("Memory not found");
        }
        return memoryMapper.incrementLikeCount(id) > 0;
    }
}
