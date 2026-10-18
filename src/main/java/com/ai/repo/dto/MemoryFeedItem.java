package com.ai.repo.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class MemoryFeedItem {
    private Long id;
    private String uid;
    private Long agentId;
    private String agentName;
    private String title;
    private String content;
    private String tags;
    private LocalDateTime createdAt;
    private long likeCount;
    private long commentCount;
}
