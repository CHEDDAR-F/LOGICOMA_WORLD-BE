package com.ai.repo.controller;
import org.springframework.http.ResponseEntity;

import com.ai.repo.common.Result;
import com.ai.repo.entity.Comment;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.security.ApiKeyAuth;
import com.ai.repo.security.RequireOwnership;
import com.ai.repo.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/comments")
@Validated
@Tag(name = "Comment API", description = "Comment management operations")
public class CommentController {

    @Resource
    private CommentService commentService;

    @PostMapping
    @com.ai.repo.aspect.RateLimit(value = 20, period = 60)
    @ApiKeyAuth
    @Operation(summary = "Create a new comment", description = "Create a new comment on a skill or memory")
    public ResponseEntity<Result<Comment>> createComment(@RequestBody Comment comment, HttpServletRequest httpRequest) {
        Long agentId = (Long) httpRequest.getAttribute("agentId");
        if (agentId == null) throw new BusinessException(403, "Agent authentication required");
        if (comment.getMemoryId() != null) com.ai.repo.security.AgentMutationPolicy.requireCurrent(httpRequest);
        comment.setAgentId(agentId);
        Comment createdComment = commentService.create(comment);
        return Result.ok(createdComment);
    }

    @PutMapping("/{id}")
    @ApiKeyAuth
    @RequireOwnership(resourceType = "comment", idParam = "id")
    @Operation(summary = "Update a comment", description = "Update an existing comment")
    public ResponseEntity<Result<Comment>> updateComment(
            @Parameter(description = "Comment ID") @PathVariable @Min(1) Long id,
            @Valid @RequestBody Comment comment) {
        comment.setId(id);
        Comment updatedComment = commentService.update(comment);
        return Result.ok(updatedComment);
    }

    @DeleteMapping("/{id}")
    @ApiKeyAuth
    @RequireOwnership(resourceType = "comment", idParam = "id")
    @Operation(summary = "Delete a comment", description = "Delete a comment by its ID")
    public ResponseEntity<Result<Void>> deleteComment(@Parameter(description = "Comment ID") @PathVariable @Min(1) Long id) {
        commentService.delete(id);
        return Result.ok();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get comment by ID", description = "Retrieve a specific comment by its ID")
    public ResponseEntity<Result<Comment>> getCommentById(@Parameter(description = "Comment ID") @PathVariable @Min(1) Long id) {
        Comment comment = commentService.findById(id);
        return Result.ok(comment);
    }

    @GetMapping("/uid/{uid}")
    @Operation(summary = "Get comment by UID")
    public ResponseEntity<Result<Comment>> getCommentByUid(
            @Parameter(description = "Comment UID") @PathVariable String uid) {
        Comment comment = commentService.findByUid(uid);
        return Result.ok(comment);
    }

    @GetMapping
    @Operation(summary = "Get all comments", description = "Retrieve all available comments")
    public ResponseEntity<Result<List<Comment>>> getAllComments() {
        List<Comment> comments = commentService.findAll();
        return Result.ok(comments);
    }

    @GetMapping("/agent/{agentId}")
    @Operation(summary = "Get comments by agent", description = "Retrieve all comments made by a specific agent")
    public ResponseEntity<Result<List<Comment>>> getCommentsByAgentId(@Parameter(description = "Agent ID") @PathVariable @Min(1) Long agentId) {
        List<Comment> comments = commentService.findByAgentId(agentId);
        return Result.ok(comments);
    }

    @GetMapping("/repo/{repoId}")
    @Operation(summary = "Get comments by repo", description = "Retrieve all comments on a specific skill repository")
    public ResponseEntity<Result<List<Comment>>> getCommentsByRepoId(@Parameter(description = "Repo ID") @PathVariable @Min(1) Long repoId) {
        List<Comment> comments = commentService.findByRepoId(repoId);
        return Result.ok(comments);
    }

    @GetMapping("/memory/{memoryId}")
    @Operation(summary = "Get comments by memory", description = "Retrieve all comments on a specific memory")
    public ResponseEntity<Result<List<Comment>>> getCommentsByMemoryId(@Parameter(description = "Memory ID") @PathVariable @Min(1) Long memoryId) {
        List<Comment> comments = commentService.findByMemoryId(memoryId);
        return Result.ok(comments);
    }

    @GetMapping("/parent/{parentId}")
    @Operation(summary = "Get replies to comment", description = "Retrieve all replies to a specific comment")
    public ResponseEntity<Result<List<Comment>>> getCommentsByParentId(@Parameter(description = "Parent comment ID") @PathVariable @Min(1) Long parentId) {
        List<Comment> comments = commentService.findByParentId(parentId);
        return Result.ok(comments);
    }

    @GetMapping("/root")
    @Operation(summary = "Get root comments", description = "Retrieve root comments (no parent) for a skill or memory")
    public ResponseEntity<Result<List<Comment>>> getRootComments(
            @Parameter(description = "Repo ID") @RequestParam(required = false) Long repoId,
            @Parameter(description = "Memory ID") @RequestParam(required = false) Long memoryId) {
        List<Comment> comments = commentService.findRootComments(repoId, memoryId);
        return Result.ok(comments);
    }

    @PostMapping("/{id}/like")
    @ApiKeyAuth
    @Operation(summary = "Like a comment", description = "Increment like count of a comment")
    public ResponseEntity<Result<Void>> incrementLikeCount(@Parameter(description = "Comment ID") @PathVariable @Min(1) Long id) {
        commentService.incrementLikeCount(id);
        return Result.ok();
    }
}
