package com.ai.repo.service.impl;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.ai.repo.entity.Memory;
import com.ai.repo.entity.MemoryPublicationRequest;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.MemoryMapper;
import com.ai.repo.mapper.MemoryPublicationRequestMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryPublicationRequestServiceImplTest {
    @Mock MemoryMapper memories;
    @Mock com.ai.repo.mapper.MemoryImageMapper images;
    @Mock MemoryPublicationRequestMapper requests;
    MemoryPublicationRequestServiceImpl service;
    Memory memory;
    MemoryPublicationRequest saved;

    @BeforeEach void setup() {
        service = new MemoryPublicationRequestServiceImpl();
        ReflectionTestUtils.setField(service, "memoryMapper", memories);
        ReflectionTestUtils.setField(service, "imageMapper", images);
        ReflectionTestUtils.setField(service, "requestMapper", requests);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "frontendUrl", "https://logicomanet.com");
        memory = new Memory();
        memory.setId(42L); memory.setUserId(1L); memory.setAgentId(5L);
        memory.setMemoryType("GENERAL"); memory.setIsPublic(false);
        memory.setStatus("VISIBLE"); memory.setSharingScope("AGENT_PRIVATE");
        memory.setTitle("Working notes"); memory.setContent("Reviewed content");
    }
    private String create() {
        lenient().when(memories.selectById(42L)).thenReturn(memory);
        when(memories.selectByIdForUpdate(42L)).thenReturn(memory);
        doAnswer(call -> { saved = call.getArgument(0); saved.setId(10L); return 1; }).when(requests).insert(any());
        var result = service.create(5L, 1L, 42L);
        when(requests.selectByTokenHash(anyString())).thenReturn(saved);
        assertTrue(result.approvalUrl().startsWith("https://logicomanet.com/approve/memory-publication/mpr_"));
        assertNotEquals(result.requestId(), saved.getTokenHash());
        return result.requestId();
    }
    @Test void createsScopedLinkWithoutPublishing() {
        String id = create();
        assertEquals("PENDING", service.status(id, 5L).status());
        assertEquals("Reviewed content", service.details(id, 1L).memory().getContent());
        verify(memories, never()).publishPrivateGeneral(any(), any(), any());
    }
    @Test void onlyOwnerCanReviewAndOnlyRequestingAgentCanPoll() {
        String id = create();
        assertEquals(403, assertThrows(BusinessException.class, () -> service.details(id, 2L)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.approve(id, 2L)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.status(id, 6L)).getCode());
        verify(memories, never()).publishPrivateGeneral(any(), any(), any());
    }
    @Test void publishesLockedExactMemoryOnce() {
        String id = create();
        when(memories.selectByIdForUpdate(42L)).thenReturn(memory);
        when(requests.decide(10L, 1L, "APPROVED")).thenReturn(1);
        when(memories.publishPrivateGeneral(42L, 1L, 5L)).thenReturn(1);
        service.approve(id, 1L);
        verify(memories, times(2)).selectByIdForUpdate(42L);
        verify(memories).publishPrivateGeneral(42L, 1L, 5L);
        saved.setStatus("APPROVED");
        assertEquals(409, assertThrows(BusinessException.class, () -> service.approve(id, 1L)).getCode());
        verify(memories, times(1)).publishPrivateGeneral(42L, 1L, 5L);
    }
    @Test void changedContentInvalidatesApprovalEvenWithSameTitle() {
        String id = create();
        memory.setContent("Not reviewed");
        when(memories.selectByIdForUpdate(42L)).thenReturn(memory);
        assertEquals("CHANGED", service.status(id, 5L).status());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.approve(id, 1L)).getCode());
        verify(requests, never()).decide(any(), any(), eq("APPROVED"));
    }
    @Test void expiryAndRejectionNeverPublish() {
        String id = create();
        saved.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(memories.selectByIdForUpdate(42L)).thenReturn(memory);
        assertEquals("EXPIRED", service.status(id, 5L).status());
        assertThrows(BusinessException.class, () -> service.approve(id, 1L));
        saved.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(requests.decide(10L, 1L, "REJECTED")).thenReturn(1);
        service.reject(id, 1L);
        verify(memories, never()).publishPrivateGeneral(any(), any(), any());
    }
    @Test void profileBannedPublicAndForeignMemoriesCannotGetLinks() {
        lenient().when(memories.selectById(42L)).thenReturn(memory);
        when(memories.selectByIdForUpdate(42L)).thenReturn(memory);
        memory.setMemoryType("USER_PROFILE");
        assertEquals(409, assertThrows(BusinessException.class, () -> service.create(5L, 1L, 42L)).getCode());
        memory.setMemoryType("GENERAL"); memory.setStatus("BANNED");
        assertThrows(BusinessException.class, () -> service.create(5L, 1L, 42L));
        memory.setStatus("VISIBLE"); memory.setIsPublic(true);
        assertThrows(BusinessException.class, () -> service.create(5L, 1L, 42L));
        memory.setIsPublic(false); memory.setAgentId(6L);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.create(5L, 1L, 42L)).getCode());
        verifyNoInteractions(requests);
    }
    @Test void changingAttachmentsInvalidatesReview() {
        String id = create();
        var image = new com.ai.repo.entity.MemoryImage();
        image.setId("immutable-image"); image.setSha256("new-content-hash");
        when(images.listForReview(42L)).thenReturn(java.util.List.of(image));
        assertEquals("CHANGED", service.status(id, 5L).status());
        assertThrows(BusinessException.class, () -> service.approve(id, 1L));
        verify(requests, never()).decide(any(), any(), eq("APPROVED"));
    }
}
