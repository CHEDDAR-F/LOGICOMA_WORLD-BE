package com.ai.repo.service.impl;

import com.ai.repo.entity.Comment;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.CommentMapper;
import com.ai.repo.service.CommentService;
import com.ai.repo.util.UuidUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import com.ai.repo.service.MemorySocialService;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentServiceImpl implements CommentService {

    @Resource
    private CommentMapper commentMapper;

    @Resource
    private MemorySocialService memorySocialService;

    @Resource
    private com.ai.repo.mapper.CommentCreateRequestMapper commentCreateRequestMapper;

    private void validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > 10000)
            throw new BusinessException(400, "Comment must contain 1 to 10000 characters");
    }

    @Override
    @Transactional
    public Comment create(Comment comment) {
        return createInternal(comment, null);
    }

    @Override
    @Transactional
    public Comment createIdempotent(Comment comment, String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new BusinessException(400, "Idempotency-Key must contain 1 to 128 ASCII letters, digits, dot, underscore, colon or hyphen");
        return createInternal(comment, idempotencyKey);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Comment createInternal(Comment comment, String key) {
        validateContent(comment.getContent());
        if ((comment.getMemoryId() == null) == (comment.getRepoId() == null))
            throw new BusinessException(400, "Specify exactly one comment target");
        if (comment.getAgentId() == null) throw new BusinessException(403, "Agent authentication required");
        if (comment.getMemoryId() != null) memorySocialService.requirePublic(comment.getMemoryId(), true);
        com.ai.repo.entity.CommentCreateRequest receipt = null;
        if (key != null) {
            receipt = new com.ai.repo.entity.CommentCreateRequest();
            receipt.setAgentId(comment.getAgentId());
            receipt.setRequestKeyHash(sha256(key));
            receipt.setRequestHash(sha256("comment-create:v1\n" + comment.getRepoId() + "\n" + comment.getMemoryId()
                + "\n" + comment.getParentId() + "\n" + comment.getContent()));
            receipt.setClaimToken(UuidUtil.generate());
            commentCreateRequestMapper.claim(receipt);
            var stored = commentCreateRequestMapper.locked(receipt);
            if (!Objects.equals(stored.getRequestHash(), receipt.getRequestHash()))
                throw new BusinessException(409, "Idempotency-Key was already used for a different comment request");
            if (!Objects.equals(stored.getClaimToken(), receipt.getClaimToken())) {
                // Check current visibility above, then read current authorized data. Never cache a public response.
                Comment existing = stored.getCommentId() == null ? null : commentMapper.selectById(stored.getCommentId());
                if (existing == null) throw new BusinessException(410, "Comment for this Idempotency-Key is no longer available");
                return existing;
            }
        }
        Comment parent = null;
        if (comment.getParentId() != null) {
            parent = commentMapper.selectById(comment.getParentId());
            if (parent == null || !Objects.equals(parent.getMemoryId(), comment.getMemoryId())
                    || !Objects.equals(parent.getRepoId(), comment.getRepoId()))
                throw new BusinessException(400, "Reply must belong to the same resource");
        }
        comment.setId(null);
        comment.setUid(UuidUtil.generate());
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setDownvoteCount(0);
        commentMapper.insert(comment);
        if (parent != null) {
            commentMapper.incrementReplyCount(parent.getId());
        }

        if (receipt != null) {
            receipt.setCommentId(comment.getId());
            if (commentCreateRequestMapper.complete(receipt) != 1)
                throw new IllegalStateException("Comment creation receipt was not completed");
        }
        return comment;
    }

    @Override
    @Transactional
    public Comment update(Comment comment) {
        validateContent(comment.getContent());
        Comment existing = commentMapper.selectById(comment.getId());
        if (existing == null) throw new BusinessException(404, "Comment not found");
        if (existing.getMemoryId() != null) memorySocialService.requirePublic(existing.getMemoryId(), true);
        // Resource, author, nesting and counters are immutable through an edit.
        existing.setContent(comment.getContent());
        commentMapper.updateContent(existing);
        return existing;
    }

    @Override
    public boolean delete(Long id) {
        if (commentMapper.selectById(id) == null) {
            throw new BusinessException("Comment not found");
        }
        return commentMapper.deleteById(id) > 0;
    }

    @Override
    public Comment findById(Long id) {
        return commentMapper.selectById(id);
    }

    @Override
    public Comment findByUid(String uid) {
        return commentMapper.selectByUid(uid);
    }

    @Override
    public List<Comment> findAll() {
        return commentMapper.selectAll();
    }

    @Override
    public List<Comment> findByAgentId(Long agentId) {
        return commentMapper.selectByAgentId(agentId);
    }

    @Override
    public List<Comment> findByRepoId(Long repoId) {
        return commentMapper.selectByRepoId(repoId);
    }

    @Override
    public List<Comment> findByMemoryId(Long memoryId) {
        memorySocialService.requirePublic(memoryId, false);
        return commentMapper.selectByMemoryId(memoryId);
    }

    @Override
    public List<Comment> findByParentId(Long parentId) {
        return commentMapper.selectByParentId(parentId);
    }

    @Override
    public List<Comment> findRootComments(Long repoId, Long memoryId) {
        if (memoryId != null) memorySocialService.requirePublic(memoryId, false);
        return commentMapper.selectRootComments(repoId, memoryId);
    }

    @Override
    @Transactional
    public boolean incrementLikeCount(Long id) {
        Comment existing = commentMapper.selectById(id);
        if (existing == null) throw new BusinessException(404, "Comment not found");
        if (existing.getMemoryId() != null) memorySocialService.requirePublic(existing.getMemoryId(), true);
        int rows = commentMapper.incrementLikeCount(id);
        if (rows == 0) {
            throw new BusinessException("Comment not found");
        }
        return true;
    }
}
