package com.ai.repo.service;

import com.ai.repo.entity.Memory;
import com.ai.repo.entity.MemoryImage;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.MemoryImageMapper;
import com.ai.repo.mapper.MemoryMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class MemoryImageService {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    @Resource private MemoryMapper memoryMapper;
    @Resource private MemoryImageMapper imageMapper;

    public List<MemoryImage> list(Long memoryId, Long userId, Long agentId) {
        readable(memoryMapper.selectById(memoryId), userId, agentId);
        return imageMapper.list(memoryId);
    }

    // The authorization lookup and bytes are served afresh; no reusable public URL or disk alias.
    public MemoryImage read(Long memoryId, String id, Long userId, Long agentId) {
        readable(memoryMapper.selectById(memoryId), userId, agentId);
        MemoryImage image = imageMapper.find(memoryId, id);
        if (image == null) throw new BusinessException(404, "Image not found");
        return image;
    }

    @Transactional
    public MemoryImage upload(Long memoryId, Long userId, Long agentId, MultipartFile file) {
        if (agentId == null) throw new BusinessException(403, "Only an Agent can upload images");
        // Serialize the quota across this Agent's Memories, then lock the exact disclosure.
        imageMapper.lockAgent(agentId);
        editable(memoryMapper.selectByIdForUpdate(memoryId), userId, agentId);
        MemoryImage image = normalize(file);
        List<MemoryImage> existing = imageMapper.list(memoryId);
        for (MemoryImage previous : existing) {
            if (previous.getSha256().equals(image.getSha256())) return previous;
        }
        if (existing.size() >= 9) throw new BusinessException(409, "A Memory can have at most 9 images");
        if (imageMapper.agentBytes(agentId) + image.getByteSize() > 100L * 1024 * 1024) {
            throw new BusinessException(409, "Agent image quota is 100 MiB; delete unused private images first");
        }
        image.setMemoryId(memoryId);
        image.setId(UUID.randomUUID().toString().replace("-", ""));
        imageMapper.insert(image);
        return image;
    }

    @Transactional
    public void delete(Long memoryId, String id, Long userId, Long agentId) {
        editable(memoryMapper.selectByIdForUpdate(memoryId), userId, agentId);
        if (imageMapper.delete(memoryId, id) != 1) throw new BusinessException(404, "Image not found");
    }

    private void editable(Memory m, Long userId, Long agentId) {
        if (m == null || agentId == null || !agentId.equals(m.getAgentId()) || !java.util.Objects.equals(userId,m.getUserId())) {
            throw new BusinessException(404, "Memory not found");
        }
        if (!"GENERAL".equals(m.getMemoryType()) || !"VISIBLE".equals(m.getStatus())
                || !Boolean.FALSE.equals(m.getIsPublic()) || !"AGENT_PRIVATE".equals(m.getSharingScope())) {
            throw new BusinessException(409, "Images can only be changed on private visible GENERAL Memory; publish through a new owner approval afterward");
        }
    }

    private void readable(Memory m, Long userId, Long agentId) {
        boolean owner = m != null && (agentId != null ? agentId.equals(m.getAgentId()) : userId != null && userId.equals(m.getUserId()));
        boolean shared = m != null && Boolean.TRUE.equals(m.getIsPublic()) && "PUBLIC".equals(m.getSharingScope());
        if (m == null || userId == null || !"GENERAL".equals(m.getMemoryType()) || !"VISIBLE".equals(m.getStatus()) || (!owner && !shared)) {
            throw new BusinessException(404, "Memory not found");
        }
    }

    private MemoryImage normalize(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw new BusinessException(400, "Image must be at most 5 MiB");
        try (InputStream input = file.getInputStream(); var stream = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new BusinessException(400, "Only PNG and JPEG images are supported");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"png".equalsIgnoreCase(format) && !"jpeg".equalsIgnoreCase(format)) throw new BusinessException(400, "Only PNG and JPEG images are supported");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 4_000_000) {
                    throw new BusinessException(400, "Image dimensions exceed 4096 per side or 4 million pixels");
                }
                BufferedImage decoded = reader.read(0);
                // Re-encode pixels only: discard EXIF/location, names, trailing payloads and active formats.
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(decoded, "png", output);
                byte[] data = output.toByteArray();
                if (data.length > MAX_BYTES) throw new BusinessException(400, "Normalized PNG exceeds 5 MiB");
                MemoryImage image = new MemoryImage();
                image.setData(data); image.setWidth(width); image.setHeight(height); image.setByteSize(data.length);
                image.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
                return image;
            } finally { reader.dispose(); }
        } catch (IOException e) { throw new BusinessException(400, "Invalid image"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
