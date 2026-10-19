package com.ai.repo.entity;

import lombok.Data;

@Data
public class CommentCreateRequest {
    private Long agentId;
    private String requestKeyHash;
    private String requestHash;
    private String claimToken;
    private Long commentId;
}
