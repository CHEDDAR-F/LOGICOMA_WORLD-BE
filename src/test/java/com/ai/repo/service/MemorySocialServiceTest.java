package com.ai.repo.service;

import com.ai.repo.entity.Memory;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.MemoryMapper;
import com.ai.repo.mapper.MemorySocialMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemorySocialServiceTest {
    @Mock MemoryMapper memoryMapper;
    @Mock MemorySocialMapper socialMapper;
    MemorySocialService service;
    Memory memory;
    @BeforeEach void setup() {
        service = new MemorySocialService();
        ReflectionTestUtils.setField(service, "memoryMapper", memoryMapper);
        ReflectionTestUtils.setField(service, "socialMapper", socialMapper);
        memory = new Memory();
        memory.setId(1L); memory.setIsPublic(true); memory.setMemoryType("GENERAL");
        memory.setSharingScope("PUBLIC"); memory.setStatus("VISIBLE");
    }
    @Test void likeUsesAuthenticatedAgentAndLocksVisibility() {
        when(memoryMapper.selectByIdForUpdate(1L)).thenReturn(memory);
        when(socialMapper.countLikes(1L)).thenReturn(1L);
        when(socialMapper.hasLike(1L, 2L)).thenReturn(1);
        assertTrue(service.setLike(1L, 2L, true).liked());
        var order = inOrder(memoryMapper, socialMapper);
        order.verify(memoryMapper).selectByIdForUpdate(1L);
        order.verify(socialMapper).like(1L, 2L);
    }
    @Test void unlikeOnlyRemovesCaller() {
        when(memoryMapper.selectByIdForUpdate(1L)).thenReturn(memory);
        service.setLike(1L, 2L, false);
        verify(socialMapper).unlike(1L, 2L);
        verify(socialMapper, never()).like(anyLong(), anyLong());
    }
    @Test void humanCannotLike() {
        assertThrows(BusinessException.class, () -> service.setLike(1L, null, true));
        verifyNoInteractions(memoryMapper, socialMapper);
    }
    @Test void privateCannotBeLikedOrReadEvenByOwner() {
        memory.setIsPublic(false);
        when(memoryMapper.selectByIdForUpdate(1L)).thenReturn(memory);
        assertThrows(BusinessException.class, () -> service.setLike(1L, 2L, true));
        assertThrows(BusinessException.class, () -> service.summary(1L, 2L));
        verifyNoInteractions(socialMapper);
    }
    @Test void inconsistentScopeIsDenied() {
        memory.setSharingScope("AGENT_PRIVATE");
        assertFalse(MemorySocialService.isSocial(memory));
    }
    @Test void profileIsDeniedEvenIfPublicFlagCorrupt() {
        memory.setMemoryType("USER_PROFILE");
        assertFalse(MemorySocialService.isSocial(memory));
    }
    @Test void bannedIsDenied() {
        memory.setStatus("BANNED");
        assertFalse(MemorySocialService.isSocial(memory));
    }
    @Test void absentIsDenied() {
        assertThrows(BusinessException.class, () -> service.summary(1L, null));
        verifyNoInteractions(socialMapper);
    }
    @Test void feedIsBoundedAndUsesStablePageOffset() {
        service.feed(3, 20);
        verify(socialMapper).feed(20, 40L);
        assertThrows(BusinessException.class, () -> service.feed(0, 20));
        assertThrows(BusinessException.class, () -> service.feed(1, 51));
    }
}
