package com.ai.repo.service.impl;

import com.ai.repo.entity.Comment;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.CommentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock private com.ai.repo.service.MemorySocialService memorySocialService;

    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() throws Exception {
        commentService = new CommentServiceImpl();
        java.lang.reflect.Field mapperField = CommentServiceImpl.class.getDeclaredField("commentMapper");
        mapperField.setAccessible(true);
        mapperField.set(commentService, commentMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(commentService, "memorySocialService", memorySocialService);
    }

    private Comment buildComment(Long id, Long agentId, String content) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setAgentId(agentId);
        comment.setContent(content);
        comment.setRepoId(1L);
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setDownvoteCount(0);
        return comment;
    }

    // ==================== create ====================

    @Test
    void create_shouldInsertAndReturnComment() {
        Comment comment = buildComment(null, 5L, "Hello");
        when(commentMapper.insert(any(Comment.class))).thenReturn(1);

        Comment result = commentService.create(comment);

        assertNotNull(result);
        verify(commentMapper).insert(comment);
    }

    @Test
    void create_withParentId_shouldIncrementParentReplyCount() {
        Comment reply = buildComment(null, 5L, "Reply");
        reply.setParentId(10L);
        reply.setRepoId(1L);

        Comment parent = buildComment(10L, 3L, "Parent");
        parent.setReplyCount(2);

        when(commentMapper.insert(any(Comment.class))).thenReturn(1);
        when(commentMapper.selectById(10L)).thenReturn(parent);

        Comment result = commentService.create(reply);

        assertNotNull(result);
        verify(commentMapper).insert(reply);
        verify(commentMapper).selectById(10L);
        verify(commentMapper).update(argThat(c -> c.getReplyCount() == 3));
    }

    @Test
    void create_withParentId_parentNotFound_shouldReject() {
        Comment reply = buildComment(null, 5L, "Reply");
        reply.setParentId(999L);

        when(commentMapper.selectById(999L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> commentService.create(reply));
        verify(commentMapper, never()).insert(any());
        verify(commentMapper).selectById(999L);
        verify(commentMapper, never()).update(any());
    }

    // ==================== update ====================

    @Test
    void update_shouldUpdateExistingComment() {
        Comment comment = buildComment(1L, 5L, "Updated");
        when(commentMapper.selectById(1L)).thenReturn(comment);
        when(commentMapper.updateContent(any(Comment.class))).thenReturn(1);

        Comment result = commentService.update(comment);

        assertEquals("Updated", result.getContent());
        verify(commentMapper).updateContent(comment);
    }

    @Test
    void update_notFound_shouldThrowException() {
        Comment comment = buildComment(999L, 5L, "Not found");
        when(commentMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> commentService.update(comment));
        verify(commentMapper, never()).update(any());
    }

    // ==================== delete ====================

    @Test
    void delete_shouldDeleteExistingComment() {
        Comment comment = buildComment(1L, 5L, "Delete me");
        when(commentMapper.selectById(1L)).thenReturn(comment);
        when(commentMapper.deleteById(1L)).thenReturn(1);

        boolean result = commentService.delete(1L);

        assertTrue(result);
        verify(commentMapper).deleteById(1L);
    }

    @Test
    void delete_notFound_shouldThrowException() {
        when(commentMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> commentService.delete(999L));
        verify(commentMapper, never()).deleteById(anyLong());
    }

    // ==================== findById ====================

    @Test
    void findById_shouldReturnComment() {
        Comment comment = buildComment(1L, 5L, "Hello");
        when(commentMapper.selectById(1L)).thenReturn(comment);

        Comment result = commentService.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Hello", result.getContent());
    }

    @Test
    void findById_notFound_shouldReturnNull() {
        when(commentMapper.selectById(999L)).thenReturn(null);

        Comment result = commentService.findById(999L);

        assertNull(result);
    }

    // ==================== findByAgentId ====================

    @Test
    void findByAgentId_shouldReturnComments() {
        List<Comment> comments = Arrays.asList(
                buildComment(1L, 5L, "Comment 1"),
                buildComment(2L, 5L, "Comment 2")
        );
        when(commentMapper.selectByAgentId(5L)).thenReturn(comments);

        List<Comment> result = commentService.findByAgentId(5L);

        assertEquals(2, result.size());
        assertEquals(5L, result.get(0).getAgentId());
    }

    // ==================== findByRepoId ====================

    @Test
    void findByRepoId_shouldReturnComments() {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Repo comment"));
        when(commentMapper.selectByRepoId(10L)).thenReturn(comments);

        List<Comment> result = commentService.findByRepoId(10L);

        assertEquals(1, result.size());
    }

    // ==================== findByMemoryId ====================

    @Test
    void findByMemoryId_shouldReturnComments() {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Memory comment"));
        when(commentMapper.selectByMemoryId(20L)).thenReturn(comments);

        List<Comment> result = commentService.findByMemoryId(20L);

        assertEquals(1, result.size());
    }

    // ==================== findByParentId ====================

    @Test
    void findByParentId_shouldReturnReplies() {
        Comment reply = buildComment(2L, 6L, "Reply");
        reply.setParentId(1L);
        when(commentMapper.selectByParentId(1L)).thenReturn(Arrays.asList(reply));

        List<Comment> result = commentService.findByParentId(1L);

        assertEquals(1, result.size());
    }

    // ==================== findRootComments ====================

    @Test
    void findRootComments_byRepo_shouldReturnRootComments() {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Root"));
        when(commentMapper.selectRootComments(10L, null)).thenReturn(comments);

        List<Comment> result = commentService.findRootComments(10L, null);

        assertEquals(1, result.size());
    }

    @Test
    void findRootComments_noFilter_shouldReturnAllRootComments() {
        List<Comment> comments = Arrays.asList(buildComment(1L, 5L, "Root"));
        when(commentMapper.selectRootComments(null, null)).thenReturn(comments);

        List<Comment> result = commentService.findRootComments(null, null);

        assertEquals(1, result.size());
    }

    // ==================== incrementLikeCount ====================

    @Test
    void incrementLikeCount_shouldCallMapper() {
        when(commentMapper.selectById(1L)).thenReturn(buildComment(1L, 5L, "Hello"));
        when(commentMapper.incrementLikeCount(1L)).thenReturn(1);

        boolean result = commentService.incrementLikeCount(1L);

        assertTrue(result);
        verify(commentMapper).incrementLikeCount(1L);
    }

    @Test
    void incrementLikeCount_notFound_shouldThrowException() {
        when(commentMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> commentService.incrementLikeCount(999L));
    }

    @Test void privateMemoryRejectsCommentBeforeInsert() {
        Comment c = buildComment(null, 5L, "Hello");
        c.setRepoId(null); c.setMemoryId(20L);
        doThrow(new BusinessException(404, "Not public")).when(memorySocialService).requirePublic(20L, true);
        assertThrows(BusinessException.class, () -> commentService.create(c));
        verify(commentMapper, never()).insert(any());
    }
    @Test void crossMemoryReplyIsRejected() {
        Comment c = buildComment(null, 5L, "Reply");
        c.setRepoId(null); c.setMemoryId(20L); c.setParentId(1L);
        Comment parent = buildComment(1L, 6L, "Parent");
        parent.setRepoId(null); parent.setMemoryId(21L);
        when(commentMapper.selectById(1L)).thenReturn(parent);
        assertThrows(BusinessException.class, () -> commentService.create(c));
        verify(commentMapper, never()).insert(any());
    }
    @Test void forgedIdentityCountersAndTargetCannotMoveOnEdit() {
        Comment original = buildComment(1L, 5L, "Original");
        when(commentMapper.selectById(1L)).thenReturn(original);
        Comment update = buildComment(1L, 999L, "Edited");
        update.setMemoryId(44L); update.setLikeCount(999);
        Comment result = commentService.update(update);
        assertEquals(5L, result.getAgentId());
        assertNull(result.getMemoryId());
        assertEquals(0, result.getLikeCount());
        assertEquals("Edited", result.getContent());
    }
    @Test void ambiguousTargetAndBlankContentAreRejected() {
        Comment c = buildComment(null, 5L, "Hello"); c.setMemoryId(1L);
        assertThrows(BusinessException.class, () -> commentService.create(c));
        c.setMemoryId(null); c.setContent("  ");
        assertThrows(BusinessException.class, () -> commentService.create(c));
        verify(commentMapper, never()).insert(any());
    }
}
