package com.ai.repo.mapper;

import com.ai.repo.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommentMapper {
    int insert(Comment comment);
    int update(Comment comment);
    @org.apache.ibatis.annotations.Update("UPDATE comments SET content=#{content} WHERE id=#{id}")
    int updateContent(Comment comment);
    int deleteById(Long id);
    int deleteByAgentId(Long agentId);
    Comment selectById(Long id);
    Comment selectByUid(@Param("uid") String uid);
    List<Comment> selectAll();
    List<Comment> selectByAgentId(Long agentId);
    List<Comment> selectByRepoId(Long repoId);
    List<Comment> selectByMemoryId(Long memoryId);
    List<Comment> selectByParentId(Long parentId);
    List<Comment> selectRootComments(@Param("repoId") Long repoId, @Param("memoryId") Long memoryId);
    int incrementLikeCount(Long id);
}
