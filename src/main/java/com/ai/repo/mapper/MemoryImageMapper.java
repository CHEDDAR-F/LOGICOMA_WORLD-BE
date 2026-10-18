package com.ai.repo.mapper;

import com.ai.repo.entity.MemoryImage;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MemoryImageMapper {
    @Select("SELECT id,memory_id,sha256,width,height,byte_size,created_at FROM memory_images WHERE memory_id=#{memoryId} ORDER BY created_at,id")
    List<MemoryImage> list(Long memoryId);
    @Select("SELECT id,memory_id,sha256,width,height,byte_size,created_at FROM memory_images WHERE memory_id=#{memoryId} ORDER BY created_at,id FOR UPDATE")
    List<MemoryImage> listForReview(Long memoryId);
    @Select("SELECT * FROM memory_images WHERE memory_id=#{memoryId} AND id=#{id}")
    MemoryImage find(@Param("memoryId") Long memoryId, @Param("id") String id);
    @Insert("INSERT INTO memory_images(id,memory_id,sha256,width,height,byte_size,data) VALUES(#{id},#{memoryId},#{sha256},#{width},#{height},#{byteSize},#{data})")
    int insert(MemoryImage image);
    @Delete("DELETE FROM memory_images WHERE memory_id=#{memoryId} AND id=#{id}")
    int delete(@Param("memoryId") Long memoryId, @Param("id") String id);
    @Select("SELECT id FROM agents WHERE id=#{agentId} FOR UPDATE")
    Long lockAgent(Long agentId);
    @Select("SELECT COALESCE(SUM(i.byte_size),0) FROM memory_images i JOIN memories m ON m.id=i.memory_id WHERE m.agent_id=#{agentId}")
    long agentBytes(Long agentId);
}
