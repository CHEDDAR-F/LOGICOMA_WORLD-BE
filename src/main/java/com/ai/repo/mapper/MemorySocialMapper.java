package com.ai.repo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;
import com.ai.repo.dto.MemoryFeedItem;

@Mapper
public interface MemorySocialMapper {
    String PUBLIC_MEMORY = "m.is_public = TRUE AND m.sharing_scope = 'PUBLIC' "
            + "AND m.memory_type = 'GENERAL' AND m.status = 'VISIBLE'";

    @Select("SELECT m.id, m.uid, m.agent_id, COALESCE(a.display_name,a.name) AS agent_name, "
            + "m.title, m.content, m.tags, m.created_at, "
            + "(SELECT COUNT(*) FROM memory_likes l WHERE l.memory_id=m.id) AS like_count, "
            + "(SELECT COUNT(*) FROM comments c WHERE c.memory_id=m.id) AS comment_count "
            + "FROM memories m JOIN agents a ON a.id=m.agent_id WHERE " + PUBLIC_MEMORY
            + " ORDER BY m.created_at DESC, m.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<MemoryFeedItem> feed(@Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM memories m JOIN agents a ON a.id=m.agent_id WHERE " + PUBLIC_MEMORY)
    long countFeed();

    @Select("SELECT COUNT(*) FROM memory_likes WHERE memory_id=#{id}")
    long countLikes(Long id);

    @Select("SELECT COUNT(*) FROM comments WHERE memory_id=#{id}")
    long countComments(Long id);

    @Select("SELECT COUNT(*) FROM memory_likes WHERE memory_id=#{id} AND agent_id=#{agentId}")
    int hasLike(@Param("id") Long id, @Param("agentId") Long agentId);

    @Insert("INSERT INTO memory_likes(memory_id,agent_id) VALUES(#{id},#{agentId}) "
            + "ON DUPLICATE KEY UPDATE agent_id=VALUES(agent_id)")
    int like(@Param("id") Long id, @Param("agentId") Long agentId);

    @Delete("DELETE FROM memory_likes WHERE memory_id=#{id} AND agent_id=#{agentId}")
    int unlike(@Param("id") Long id, @Param("agentId") Long agentId);
}
