package com.ai.repo.controller;

import com.ai.repo.entity.Comment;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.exception.GlobalExceptionHandler;
import com.ai.repo.service.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {CommentController.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration({
        WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class
})
class CommentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommentService commentService;

    private RequestPostProcessor withAgentId(Long agentId) {
        return request -> {
            request.setAttribute("agentId", agentId);
            request.addHeader("X-Logicoma-Policy-Version", "2026-09-26");
            return request;
        };
    }

    private Comment buildComment(Long id, Long agentId, String content) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setAgentId(agentId);
        comment.setUserId(1L);
        comment.setUsername("TestAgent");
        comment.setContent(content);
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setDownvoteCount(0);
        comment.setCreatedAt(LocalDateTime.now());
        comment.setUpdatedAt(LocalDateTime.now());
        return comment;
    }

    // ==================== POST /api/comments ====================

    @Test
    void createComment_shouldSucceed() throws Exception {
        Comment comment = buildComment(1L, 5L, "Great skill!");
        when(commentService.create(any(Comment.class))).thenReturn(comment);

        mockMvc.perform(post("/api/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoId\":10,\"content\":\"Great skill!\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.content").value("Great skill!"));

        verify(commentService).create(any(Comment.class));
    }

    @Test
    void createComment_withParentId_shouldSucceed() throws Exception {
        Comment reply = buildComment(2L, 5L, "Thanks!");
        reply.setParentId(1L);
        reply.setRepoId(10L);
        when(commentService.create(any(Comment.class))).thenReturn(reply);

        mockMvc.perform(post("/api/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoId\":10,\"parentId\":1,\"content\":\"Thanks!\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.parentId").value(1));
    }

    @Test
    void createComment_onMemory_shouldSucceed() throws Exception {
        Comment comment = buildComment(1L, 5L, "Nice memory!");
        comment.setMemoryId(20L);
        when(commentService.create(any(Comment.class))).thenReturn(comment);

        mockMvc.perform(post("/api/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memoryId\":20,\"content\":\"Nice memory!\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.memoryId").value(20));
    }

    @Test
    void idempotencyHeaderUsesAuthenticatedAgentAndKeepsMemoryPolicyGate() throws Exception {
        when(commentService.createIdempotent(any(Comment.class),eq("stable-retry-key")))
            .thenReturn(buildComment(42L,5L,"body"));
        mockMvc.perform(post("/api/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memoryId\":1,\"agentId\":999,\"content\":\"body\"}")
                .header("Idempotency-Key","stable-retry-key").with(withAgentId(5L)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(42));
        verify(commentService).createIdempotent(argThat(c->c.getAgentId()==5L),eq("stable-retry-key"));
        mockMvc.perform(post("/api/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memoryId\":1,\"content\":\"body\"}")
                .header("Idempotency-Key","stable-retry-key").requestAttr("agentId",5L))
            .andExpect(status().is(428));
    }

    // ==================== PUT /api/comments/{id} ====================

    @Test
    void updateComment_shouldSucceed() throws Exception {
        Comment updated = buildComment(1L, 5L, "Updated content");
        when(commentService.update(any(Comment.class))).thenReturn(updated);

        mockMvc.perform(put("/api/comments/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Updated content\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").value("Updated content"));
    }

    @Test
    void updateComment_notFound_shouldReturnError() throws Exception {
        when(commentService.update(any(Comment.class)))
                .thenThrow(new BusinessException("Comment not found"));

        mockMvc.perform(put("/api/comments/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"test\"}")
                        .with(withAgentId(5L)))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Comment not found"));
    }

    // ==================== DELETE /api/comments/{id} ====================

    @Test
    void deleteComment_shouldSucceed() throws Exception {
        when(commentService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/comments/1")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== GET /api/comments/{id} ====================

    @Test
    void getCommentById_shouldReturnComment() throws Exception {
        Comment comment = buildComment(1L, 5L, "Test");
        when(commentService.findById(1L)).thenReturn(comment);

        mockMvc.perform(get("/api/comments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.agentId").value(5))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.username").value("TestAgent"))
                .andExpect(jsonPath("$.data.content").value("Test"));
    }

    @Test
    void getCommentById_withoutAuth_shouldSucceed() throws Exception {
        Comment comment = buildComment(1L, 5L, "Public comment");
        when(commentService.findById(1L)).thenReturn(comment);

        mockMvc.perform(get("/api/comments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").value("Public comment"));
    }

    // ==================== GET /api/comments ====================

    @Test
    void getAllComments_shouldReturnList() throws Exception {
        List<Comment> comments = Arrays.asList(
                buildComment(1L, 5L, "First"),
                buildComment(2L, 6L, "Second")
        );
        when(commentService.findAll()).thenReturn(comments);

        mockMvc.perform(get("/api/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ==================== GET /api/comments/agent/{agentId} ====================

    @Test
    void getCommentsByAgentId_shouldReturnList() throws Exception {
        List<Comment> comments = Arrays.asList(
                buildComment(1L, 5L, "Comment by agent 5")
        );
        when(commentService.findByAgentId(5L)).thenReturn(comments);

        mockMvc.perform(get("/api/comments/agent/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].agentId").value(5));
    }

    // ==================== GET /api/comments/repo/{repoId} ====================

    @Test
    void getCommentsByRepoId_shouldReturnList() throws Exception {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Repo comment"));
        when(commentService.findByRepoId(10L)).thenReturn(comments);

        mockMvc.perform(get("/api/comments/repo/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ==================== GET /api/comments/memory/{memoryId} ====================

    @Test
    void getCommentsByMemoryId_shouldReturnList() throws Exception {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Memory comment"));
        when(commentService.findByMemoryId(20L)).thenReturn(comments);

        mockMvc.perform(get("/api/comments/memory/20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ==================== GET /api/comments/parent/{parentId} ====================

    @Test
    void getCommentsByParentId_shouldReturnReplies() throws Exception {
        Comment reply = buildComment(2L, 6L, "Reply");
        reply.setParentId(1L);
        List<Comment> replies = Arrays.asList(reply);
        when(commentService.findByParentId(1L)).thenReturn(replies);

        mockMvc.perform(get("/api/comments/parent/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].parentId").value(1));
    }

    // ==================== GET /api/comments/root ====================

    @Test
    void getRootComments_byRepo_shouldReturnList() throws Exception {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Root"));
        when(commentService.findRootComments(10L, null)).thenReturn(comments);

        mockMvc.perform(get("/api/comments/root")
                        .param("repoId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void getRootComments_byMemory_shouldReturnList() throws Exception {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Root"));
        when(commentService.findRootComments(null, 20L)).thenReturn(comments);

        mockMvc.perform(get("/api/comments/root")
                        .param("memoryId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void getRootComments_noFilter_shouldReturnAllRootComments() throws Exception {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Root"));
        when(commentService.findRootComments(null, null)).thenReturn(comments);

        mockMvc.perform(get("/api/comments/root"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ==================== POST /api/comments/{id}/like ====================

    @Test
    void likeComment_shouldSucceed() throws Exception {
        when(commentService.incrementLikeCount(1L)).thenReturn(true);

        mockMvc.perform(post("/api/comments/1/like")
                        .with(withAgentId(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void likeComment_notFound_shouldReturnError() throws Exception {
        when(commentService.incrementLikeCount(999L))
                .thenThrow(new BusinessException("Comment not found"));

        mockMvc.perform(post("/api/comments/999/like")
                        .with(withAgentId(5L)))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Comment not found"));
    }

    // ==================== Edge cases ====================

    @Test
    void getAllComments_empty_shouldReturnEmptyList() throws Exception {
        when(commentService.findAll()).thenReturn(Arrays.asList());

        mockMvc.perform(get("/api/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getCommentById_notFound_shouldReturnNull() throws Exception {
        when(commentService.findById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/comments/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
