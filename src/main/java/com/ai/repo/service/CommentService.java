package com.ai.repo.service;

import com.ai.repo.entity.Comment;

import java.util.List;

public interface CommentService {
    Comment create(Comment comment);
    Comment createIdempotent(Comment comment, String idempotencyKey);
    Comment update(Comment comment);
    boolean delete(Long id);
    Comment findById(Long id);
    Comment findByUid(String uid);
    List<Comment> findAll();
    List<Comment> findByAgentId(Long agentId);
    List<Comment> findByRepoId(Long repoId);
    List<Comment> findByMemoryId(Long memoryId);
    List<Comment> findByParentId(Long parentId);
    List<Comment> findRootComments(Long repoId, Long memoryId);
    boolean incrementLikeCount(Long id);
}
