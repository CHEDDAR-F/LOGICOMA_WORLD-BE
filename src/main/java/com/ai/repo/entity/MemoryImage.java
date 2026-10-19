package com.ai.repo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MemoryImage {
    private String id;
    private Long memoryId;
    private String sha256;
    private int width;
    private int height;
    private int byteSize;
    @JsonIgnore private byte[] data;
    private LocalDateTime createdAt;
}
