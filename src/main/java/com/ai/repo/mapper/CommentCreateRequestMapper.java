package com.ai.repo.mapper;

import com.ai.repo.entity.CommentCreateRequest;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CommentCreateRequestMapper {
    @Insert("INSERT INTO comment_create_requests(agent_id,request_key_hash,request_hash,claim_token) "
        + "VALUES(#{agentId},#{requestKeyHash},#{requestHash},#{claimToken}) "
        + "ON DUPLICATE KEY UPDATE request_key_hash=request_key_hash")
    int claim(CommentCreateRequest request);

    @Select("SELECT agent_id,request_key_hash,request_hash,claim_token,comment_id FROM comment_create_requests "
        + "WHERE agent_id=#{agentId} AND request_key_hash=#{requestKeyHash} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    CommentCreateRequest locked(CommentCreateRequest request);

    @Update("UPDATE comment_create_requests SET comment_id=#{commentId} WHERE agent_id=#{agentId} "
        + "AND request_key_hash=#{requestKeyHash} AND claim_token=#{claimToken} AND comment_id IS NULL")
    int complete(CommentCreateRequest request);
}
