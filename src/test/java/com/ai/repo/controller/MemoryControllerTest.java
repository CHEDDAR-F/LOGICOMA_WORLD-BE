package com.ai.repo.controller;

import com.ai.repo.entity.Agent;
import com.ai.repo.entity.FileUploadLog;
import com.ai.repo.entity.Memory;
import com.ai.repo.entity.ProfileMemoryItem;
import com.ai.repo.dto.ProfileMemoryResponse;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.exception.GlobalExceptionHandler;
import com.ai.repo.service.AgentService;
import com.ai.repo.service.FileStorageService;
import com.ai.repo.service.MemoryService;
import com.ai.repo.service.ProfileMemoryService;
import com.ai.repo.service.PublicationGrantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {MemoryController.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration({
        WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class
})
class MemoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemoryService memoryService;

    @MockBean
    private com.ai.repo.service.MemorySocialService memorySocialService;

    @MockBean
    private FileStorageService fileStorageService;

    @MockBean
    private AgentService agentService;

    @MockBean
    private ProfileMemoryService profileMemoryService;

    @MockBean
    private PublicationGrantService publicationGrantService;

    private RequestPostProcessor withAgentId(Long agentId) {
        return request -> {
            request.setAttribute("agentId", agentId);
            request.setAttribute("userId", 1L);
            request.addHeader("X-Logicoma-Policy-Version", "2026-09-26");
            return request;
        };
    }

    private RequestPostProcessor withUserIdOnly(Long userId) {
        return request -> {
            request.setAttribute("userId", userId);
            return request;
        };
    }

    private Memory createMemory(Long id, Long agentId, boolean isPublic) {
        Memory m = new Memory();
        m.setId(id);
        m.setAgentId(agentId);
        m.setUserId(1L);
        m.setTitle("Test");
        m.setIsPublic(isPublic);
        m.setMemoryType("GENERAL"); m.setSharingScope(isPublic ? "PUBLIC" : "AGENT_PRIVATE"); m.setStatus("VISIBLE");
        return m;
    }

    private Agent createAgent(Long id, Long userId) {
        Agent a = new Agent();
        a.setId(id);
        a.setUserId(userId);
        return a;
    }

    // ==================== POST /api/memories (create) ====================

    @Test
    void createMemory_shouldInitializeCounts() throws Exception {
        Memory memory = new Memory();
        memory.setId(1L);
        memory.setAgentId(5L);
        memory.setTitle("Test Memory");
        memory.setDownloadCount(0);
        memory.setLikeCount(0);
        when(memoryService.upsert(any(Memory.class))).thenReturn(memory);

        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test Memory\",\"content\":\"Some content\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.downloadCount").value(0))
                .andExpect(jsonPath("$.data.likeCount").value(0));
    }

    @Test
    void createMemory_shouldRejectStaleUploadPolicy() throws Exception {
        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Some content\"}")
                        .with(request -> {
                            request.setAttribute("agentId", 5L);
                            request.setAttribute("userId", 1L);
                            return request;
                        }))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value(428));
    }

    @Test
    void createPublicMemory_shouldRequireApprovalLinkEvenWithLegacyGrant() throws Exception {
        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Logicoma-Publication-Grant", "pgr_public")
                        .content("""
                                {"content":"Public knowledge","isPublic":true,
                                 "sharingScope":"PUBLIC","clientMemoryKey":"public-knowledge-v1"}
                                """)
                        .with(withAgentId(5L)))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(publicationGrantService, memoryService);
    }

    // ==================== POST /api/memories/{id}/download ====================

    @Test
    void incrementDownloadCount_shouldSucceed() throws Exception {
        when(memoryService.incrementDownloadCount(1L)).thenReturn(true);

        mockMvc.perform(post("/api/memories/1/download")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void incrementDownloadCount_notFound_shouldReturnError() throws Exception {
        when(memoryService.incrementDownloadCount(999L))
                .thenThrow(new BusinessException("Memory not found"));

        mockMvc.perform(post("/api/memories/999/download")
                        .with(withAgentId(5L)))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(500));
    }

    // ==================== POST /api/memories/{id}/like ====================

    @Test
    void incrementLikeCount_shouldSucceed() throws Exception {
        when(memorySocialService.setLike(1L, 5L, true)).thenReturn(new com.ai.repo.dto.MemorySocialSummary(1, 0, true));

        mockMvc.perform(post("/api/memories/1/like").requestAttr("agentId", 5L).header("X-Logicoma-Policy-Version", "2026-09-26")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void incrementLikeCount_notFound_shouldReturnError() throws Exception {
        when(memorySocialService.setLike(999L, 5L, true))
                .thenThrow(new BusinessException("Memory not found"));

        mockMvc.perform(post("/api/memories/999/like").requestAttr("agentId", 5L).header("X-Logicoma-Policy-Version", "2026-09-26")
                        .with(withAgentId(5L)))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(500));
    }

    // ==================== POST /api/memories — agentId from body ====================

    @Test
    void createMemory_shouldRejectMismatchedAgentIdInBody() throws Exception {
        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":7,\"content\":\"body\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void createMemory_shouldUseDefaultTitleWhenBlank() throws Exception {
        Memory memory = new Memory();
        memory.setId(3L);
        memory.setAgentId(5L);
        memory.setTitle("Memory_<timestamp>");
        when(memoryService.upsert(any(Memory.class))).thenReturn(memory);

        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"no title\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(org.hamcrest.Matchers.startsWith("Memory_")));
    }

    @Test
    void createMemory_shouldReturn400_whenNoAgentId() throws Exception {
        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"orphan\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void createMemory_shouldJoinTagsWithComma() throws Exception {
        Memory memory = new Memory();
        memory.setId(4L);
        memory.setAgentId(5L);
        when(memoryService.upsert(any(Memory.class))).thenReturn(memory);

        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":5,\"content\":\"body\",\"tags\":[\"alpha\",\"beta\",\"gamma\"]}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk());

        verify(memoryService).upsert(org.mockito.ArgumentMatchers.argThat(
                m -> "alpha,beta,gamma".equals(m.getTags())));
    }

    @Test
    void createMemory_shouldStoreAgentAuthoredUserProfile() throws Exception {
        Memory memory = new Memory();
        memory.setId(8L);
        memory.setAgentId(5L);
        memory.setUserId(1L);
        memory.setMemoryType("USER_PROFILE");
        memory.setSharingScope("USER_AGENTS");
        when(profileMemoryService.upsert(any(Memory.class), any())).thenReturn(memory);

        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memoryType":"USER_PROFILE","clientMemoryKey":"codex-profile",
                                 "title":"User profile","content":"User prefers Chinese",
                                 "profile":{"schemaVersion":"1.0","mode":"PATCH","items":[
                                   {"itemKey":"language","operation":"UPSERT","namespace":"communication",
                                    "key":"language.primary","valueType":"string","value":"zh-CN",
                                    "recordType":"PREFERENCE"}]}}
                                """)
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memoryType").value("USER_PROFILE"))
                .andExpect(jsonPath("$.data.sharingScope").value("USER_AGENTS"));

        verify(profileMemoryService).upsert(
                org.mockito.ArgumentMatchers.argThat(m -> !Boolean.TRUE.equals(m.getIsPublic())
                        && "USER".equals(m.getOwnerType())), any());
    }

    @Test
    void createMemory_shouldRejectPublicUserProfile() throws Exception {
        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memoryType":"USER_PROFILE","clientMemoryKey":"codex-profile",
                                 "title":"User profile","content":"User prefers Chinese","isPublic":true,
                                 "profile":{"items":[{"itemKey":"language","operation":"RETRACT"}]}}
                                """)
                        .with(withAgentId(5L)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getMyProfileMemories_shouldUseAuthenticatedUser() throws Exception {
        when(profileMemoryService.findByUserIdVisibleToAgent(1L, 5L))
                .thenReturn(new ProfileMemoryResponse(List.of(), List.of()));

        mockMvc.perform(get("/api/memories/profile/me").with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memories").isArray())
                .andExpect(jsonPath("$.data.items").isArray());
        verify(profileMemoryService).findByUserIdVisibleToAgent(1L, 5L);
    }

    @Test
    void getMyProfileMemories_shouldReturnAllItemsToHumanOwner() throws Exception {
        when(profileMemoryService.findByUserId(1L))
                .thenReturn(new ProfileMemoryResponse(List.of(), List.of()));

        mockMvc.perform(get("/api/memories/profile/me").with(withUserIdOnly(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());

        verify(profileMemoryService).findByUserId(1L);
    }

    @Test
    void governProfileItem_shouldAllowHumanOwner() throws Exception {
        ProfileMemoryItem item = new ProfileMemoryItem();
        item.setId(7L);
        item.setStatus("CONFIRMED");
        when(profileMemoryService.governItem(eq(1L), eq(7L), any())).thenReturn(item);

        mockMvc.perform(patch("/api/memories/profile/items/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\"}")
                        .with(withUserIdOnly(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void governProfileItem_shouldRejectAgentCaller() throws Exception {
        mockMvc.perform(patch("/api/memories/profile/items/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void replaceProfileGrants_shouldRequireOwnedAgent() throws Exception {
        when(agentService.findById(5L)).thenReturn(createAgent(5L, 2L));

        mockMvc.perform(put("/api/memories/profile/grants/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespaces\":[\"communication\"]}")
                        .with(withUserIdOnly(1L)))
                .andExpect(status().isNotFound());
    }

    // ==================== PUT /api/memories/{id} ====================

    @Test
    void updateMemory_shouldPassIdAndBody() throws Exception {
        when(memoryService.findById(1L)).thenReturn(createMemory(1L, 5L, false));
        Memory memory = new Memory();
        memory.setId(1L);
        memory.setAgentId(5L);
        memory.setTitle("Updated");
        when(memoryService.update(any(Memory.class))).thenReturn(memory);

        mockMvc.perform(put("/api/memories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated\",\"content\":\"new content\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("Updated"));
    }

    // ==================== DELETE /api/memories/{id} ====================

    @Test
    void deleteMemory_shouldInvokeService() throws Exception {
        when(memoryService.findById(1L)).thenReturn(createMemory(1L, 5L, false));
        mockMvc.perform(delete("/api/memories/1")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(memoryService).delete(1L);
    }

    @Test
    void deleteMemory_notFound_shouldReturnError() throws Exception {
        when(memoryService.findById(999L)).thenReturn(null);
        mockMvc.perform(delete("/api/memories/999")
                        .with(withAgentId(5L)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ==================== GET /api/memories/{id} ====================

    @Test
    void getMemoryById_shouldReturnMemory() throws Exception {
        when(memoryService.findById(1L)).thenReturn(createMemory(1L, 5L, true));

        mockMvc.perform(get("/api/memories/1")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void getMemoryById_shouldHideAnotherAgentsPrivateGeneralMemory() throws Exception {
        Memory memory = createMemory(1L, 99L, false);
        memory.setMemoryType("GENERAL");
        when(memoryService.findById(1L)).thenReturn(memory);

        mockMvc.perform(get("/api/memories/1").with(withAgentId(5L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMemoryById_shouldRequireStructuredProfileEndpointForSiblingAgent() throws Exception {
        Memory memory = createMemory(1L, 99L, false);
        memory.setMemoryType("USER_PROFILE");
        memory.setSharingScope("USER_AGENTS");
        memory.setStatus("VISIBLE");
        when(memoryService.findById(1L)).thenReturn(memory);

        mockMvc.perform(get("/api/memories/1").with(withAgentId(5L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMemoryById_notFound_shouldReturnError() throws Exception {
        when(memoryService.findById(999L))
                .thenThrow(new BusinessException("Memory not found"));

        mockMvc.perform(get("/api/memories/999")
                        .with(withAgentId(5L)))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(500));
    }

    // ==================== GET /api/memories/user/{userId} ====================

    @Test
    void getMemoriesByUserId_shouldReturnList() throws Exception {
        when(memoryService.findByUserId(1L)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memories/user/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void getMemoriesByUserId_shouldUseAgentScopedVisibilityForAgentCaller() throws Exception {
        when(memoryService.findByUserIdVisibleToAgent(1L, 5L)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memories/user/1").with(withAgentId(5L)))
                .andExpect(status().isOk());

        verify(memoryService).findByUserIdVisibleToAgent(1L, 5L);
    }

    // ==================== GET /api/memories/agent/{agentId} ====================

    @Test
    void getMemoriesByAgentId_shouldReturnList() throws Exception {
        when(memoryService.findByAgentId(5L)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memories/agent/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== GET /api/memories/category/{category} ====================

    @Test
    void getMemoriesByCategory_shouldReturnList() throws Exception {
        when(memoryService.findByCategoryVisibleToUser("notes", 1L, null)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memories/category/notes").with(withUserIdOnly(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== GET /api/memories/public ====================

    @Test
    void getPublicMemories_shouldReturnList() throws Exception {
        when(memoryService.findByPublic(true)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memories/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== GET /api/memories/search ====================

    @Test
    void searchMemories_shouldInvokeSearch() throws Exception {
        when(memoryService.searchPublicByKeyword("kotlin")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memories/search").param("keyword", "kotlin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(memoryService).searchPublicByKeyword("kotlin");
    }

    // ==================== DELETE /api/memories/batch ====================

    @Test
    void batchDeleteMemories_shouldReturnCount() throws Exception {
        when(memoryService.batchDeleteOwned(org.mockito.ArgumentMatchers.anyList(), eq(1L), eq(5L))).thenReturn(3);

        mockMvc.perform(delete("/api/memories/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2,3]}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3));
    }

    // ==================== File upload/download ====================

    @Test
    void uploadMemoryFile_shouldReturnResponse() throws Exception {
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "memo.md", "text/markdown", "# hello".getBytes());

        com.ai.repo.dto.FileUploadResponse resp = new com.ai.repo.dto.FileUploadResponse();
        resp.setFileId(11L);
        resp.setFileName("memo.md");
        when(fileStorageService.saveFile(any(), eq(1L), eq(5L), eq("memory"), any())).thenReturn(resp);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/memories/5/upload").file(file)
                        .param("description", "test memo")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileId").value(11));
    }

    @Test
    void downloadMemoryFile_shouldReturnOctetStream() throws Exception {
        FileUploadLog log = new FileUploadLog();
        log.setOriginalFileName("memo.md");
        when(fileStorageService.loadFileAsResource(eq(11L), eq(1L)))
                .thenReturn(new ByteArrayResource("content".getBytes()));
        when(fileStorageService.getFileUploadLog(11L)).thenReturn(log);

        mockMvc.perform(get("/api/memories/file/11")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("memo.md")));
    }

    @Test
    void getMemoryFiles_shouldReturnList() throws Exception {
        when(fileStorageService.getFileList(eq(5L), eq("memory"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memories/5/files")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void deleteMemoryFile_shouldInvokeService() throws Exception {
        mockMvc.perform(delete("/api/memories/file/11")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(fileStorageService).deleteFile(eq(11L), eq(1L));
    }

    // ==================== Access control: visibility & ownership ====================

    @Test
    void getMemoryById_shouldReturn404_whenPrivateAndNotOwner() throws Exception {
        Memory m = createMemory(1L, 99L, false);
        m.setUserId(99L);
        when(memoryService.findById(1L)).thenReturn(m);
        mockMvc.perform(get("/api/memories/1").with(withAgentId(5L)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void getMemoryById_shouldReturn200_whenPrivateAndOwnedByAgent() throws Exception {
        when(memoryService.findById(1L)).thenReturn(createMemory(1L, 5L, false));
        mockMvc.perform(get("/api/memories/1").with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void updateMemory_shouldReturn403_whenNeitherAgentNorUserOwns() throws Exception {
        Memory m = createMemory(1L, 99L, false);
        m.setUserId(99L);
        when(memoryService.findById(1L)).thenReturn(m);
        mockMvc.perform(put("/api/memories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hacked\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void updateMemory_shouldSucceed_whenJwtUserOwnsMemory() throws Exception {
        Memory existing = createMemory(1L, 5L, false);
        when(memoryService.findById(1L)).thenReturn(existing);
        Memory updated = createMemory(1L, 5L, true);
        when(memoryService.update(any(Memory.class))).thenReturn(updated);

        mockMvc.perform(put("/api/memories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isPublic\":true}")
                        .with(withUserIdOnly(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isPublic").value(true));
    }

    @Test
    void updateMemory_shouldReturn403_whenJwtUserNotOwner() throws Exception {
        Memory m = createMemory(1L, 5L, false);
        m.setUserId(99L);
        when(memoryService.findById(1L)).thenReturn(m);
        mockMvc.perform(put("/api/memories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isPublic\":true}")
                        .with(withUserIdOnly(2L)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void deleteMemory_shouldReturn403_whenNotOwner() throws Exception {
        Memory m = createMemory(1L, 5L, false);
        m.setAgentId(99L);
        when(memoryService.findById(1L)).thenReturn(m);
        mockMvc.perform(delete("/api/memories/1").with(withAgentId(5L)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void getMemoriesByAgentId_shouldReturnAll_whenJwtUserOwnsAgent() throws Exception {
        when(agentService.findById(5L)).thenReturn(createAgent(5L, 1L));
        when(memoryService.findByAgentId(5L)).thenReturn(List.of(
                createMemory(1L, 5L, false), createMemory(2L, 5L, true)));
        mockMvc.perform(get("/api/memories/agent/5").with(withUserIdOnly(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void getMemoriesByAgentId_shouldReturnPublicOnly_whenNotOwner() throws Exception {
        when(memoryService.findByAgentIdAndPublic(5L, true)).thenReturn(List.of(createMemory(2L, 5L, true)));
        mockMvc.perform(get("/api/memories/agent/5").with(withUserIdOnly(99L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void getMemoriesByAgentId_shouldNotExposeSiblingAgentsPrivateGeneralMemories() throws Exception {
        when(memoryService.findByAgentIdAndPublic(99L, true)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memories/agent/99").with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(memoryService).findByAgentIdAndPublic(99L, true);
    }

    @Test void humanCannotReassignPrivateMemoryToAnotherAgent() throws Exception {
        Memory memory = new Memory(); memory.setId(1L); memory.setUserId(1L); memory.setAgentId(5L);
        when(memoryService.findById(1L)).thenReturn(memory);
        mockMvc.perform(put("/api/memories/1").requestAttr("userId",1L)
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":999,\"content\":\"private\"}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(memoryService, org.mockito.Mockito.never()).update(any());
    }
}
