package com.ai.repo.controller;

import com.ai.repo.aspect.RateLimit;
import com.ai.repo.common.Result;
import com.ai.repo.entity.MemoryImage;
import com.ai.repo.security.AgentMutationPolicy;
import com.ai.repo.security.ApiKeyAuth;
import com.ai.repo.security.RequireAuth;
import com.ai.repo.service.MemoryImageService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/memories/{memoryId}/images")
@Validated
public class MemoryImageController {
    @Resource private MemoryImageService service;

    @ModelAttribute
    public void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Vary", "Authorization");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @PostMapping(consumes = "multipart/form-data")
    @ApiKeyAuth
    @RateLimit(value = 12, period = 60)
    @Operation(summary = "Attach a PNG/JPEG to an owned private Memory", description = "Up to 9 images per Memory; current policy acknowledgement required")
    public ResponseEntity<Result<MemoryImage>> upload(@PathVariable @Min(1) Long memoryId,
            @RequestParam("file") MultipartFile file, HttpServletRequest request) {
        AgentMutationPolicy.requireCurrent(request);
        return Result.ok(service.upload(memoryId, user(request), agent(request), file));
    }

    @GetMapping
    @RequireAuth
    @Operation(summary = "List images visible to the current Memory reader")
    public ResponseEntity<Result<List<MemoryImage>>> list(@PathVariable @Min(1) Long memoryId, HttpServletRequest request) {
        return Result.ok(service.list(memoryId,user(request),agent(request)));
    }

    @GetMapping("/{imageId}")
    @RequireAuth
    @Operation(summary = "Read image pixels after checking current Memory permissions")
    public ResponseEntity<byte[]> read(@PathVariable @Min(1) Long memoryId,
            @PathVariable @Pattern(regexp = "[0-9a-f]{32}") String imageId, HttpServletRequest request) {
        MemoryImage image = service.read(memoryId,imageId,user(request),agent(request));
        return ResponseEntity.ok().header("Content-Type","image/png")
                .header("Content-Disposition","inline; filename=memory-image.png")
                .contentLength(image.getByteSize()).body(image.getData());
    }

    @DeleteMapping("/{imageId}")
    @ApiKeyAuth
    @Operation(summary = "Delete an image from an owned private Memory")
    public ResponseEntity<Result<Void>> delete(@PathVariable @Min(1) Long memoryId,
            @PathVariable @Pattern(regexp = "[0-9a-f]{32}") String imageId, HttpServletRequest request) {
        AgentMutationPolicy.requireCurrent(request);
        service.delete(memoryId,imageId,user(request),agent(request));
        return Result.ok();
    }
    private Long user(HttpServletRequest request) { return (Long) request.getAttribute("userId"); }
    private Long agent(HttpServletRequest request) { return (Long) request.getAttribute("agentId"); }
}
