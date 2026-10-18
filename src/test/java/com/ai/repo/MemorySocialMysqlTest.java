package com.ai.repo;

import com.ai.repo.entity.Comment;
import com.ai.repo.mapper.*;
import com.ai.repo.service.MemorySocialService;
import com.ai.repo.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only a dedicated disposable database; never configure against production. */
@EnabledIfEnvironmentVariable(named = "MEMORY_SOCIAL_IT", matches = "true")
class MemorySocialMysqlTest {
    JdbcTemplate jdbc;
    CommentMapper comments;
    MemorySocialMapper social;
    MemorySocialService service;
    com.ai.repo.service.impl.CommentServiceImpl commentService;
    MemoryMapper memories;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33319/memory_social_test?allowPublicKeyRetrieval=true&useSSL=false", "root", "memory-social-local-test");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS memory_likes");
        jdbc.execute("DROP TABLE IF EXISTS comments");
        jdbc.execute("DROP TABLE IF EXISTS memories");
        jdbc.execute("DROP TABLE IF EXISTS agents");
        jdbc.execute("CREATE TABLE agents(id BIGINT PRIMARY KEY, user_id BIGINT, name VARCHAR(100), display_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE memories(id BIGINT PRIMARY KEY, uid VARCHAR(32), user_id BIGINT, agent_id BIGINT, title TEXT, content TEXT, version VARCHAR(20), description TEXT, file_path TEXT, file_size BIGINT, mime_type VARCHAR(100), tags TEXT, category TEXT, is_public BOOLEAN, status VARCHAR(20), download_count INT, like_count INT, metadata TEXT, memory_type VARCHAR(30), sharing_scope VARCHAR(30), owner_type VARCHAR(20), client_memory_key VARCHAR(200), schema_version VARCHAR(20), revision INT, profile_request_hash VARCHAR(64), created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE comments(id BIGINT PRIMARY KEY AUTO_INCREMENT, uid VARCHAR(32), agent_id BIGINT, repo_id BIGINT, memory_id BIGINT, parent_id BIGINT, content TEXT, like_count INT, reply_count INT, downvote_count INT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V11__add_memory_likes.sql")));
        jdbc.update("INSERT INTO agents VALUES(1,1,'Source',NULL),(2,2,'Reader',NULL)");
        jdbc.update("INSERT INTO memories(id,uid,agent_id,title,content,is_public,status,memory_type,sharing_scope) VALUES(1,'memory',1,'Public memory','Body',TRUE,'VISIBLE','GENERAL','PUBLIC')");
        jdbc.update("INSERT INTO comments(uid,agent_id,memory_id,content) VALUES('comment',2,1,'A public comment')");
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MemorySocialMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/MemoryMapper.xml"), new ClassPathResource("mapper/CommentMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        memories = session.getMapper(MemoryMapper.class);
        comments = session.getMapper(CommentMapper.class); social = session.getMapper(MemorySocialMapper.class);
        service = new MemorySocialService();
        ReflectionTestUtils.setField(service, "memoryMapper", session.getMapper(MemoryMapper.class));
        ReflectionTestUtils.setField(service, "socialMapper", social);
        commentService = new com.ai.repo.service.impl.CommentServiceImpl();
        ReflectionTestUtils.setField(commentService, "commentMapper", comments);
        ReflectionTestUtils.setField(commentService, "memorySocialService", service);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @Test void concurrentLikesAreUniqueAndUnlikeIsIdempotent() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i=0;i<12;i++) futures.add(pool.submit(() -> tx.execute(s -> service.setLike(1L,2L,true))));
            for (var future:futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(1, social.countLikes(1L));
            assertEquals(1, social.feed(20,0).get(0).getLikeCount());
            tx.execute(s -> service.setLike(1L,2L,false));
            tx.execute(s -> service.setLike(1L,2L,false));
            assertEquals(0, social.countLikes(1L));
        } finally { pool.shutdownNow(); }
    }
    @Test void privateBannedAndProfileMemoriesDisappearFromEveryCommentRead() {
        jdbc.update("INSERT INTO comments(uid,agent_id,memory_id,parent_id,content) VALUES('reply',1,1,1,'A reply')");
        jdbc.update("UPDATE comments SET like_count=7 WHERE id=1");
        Comment edit = new Comment(); edit.setId(1L); edit.setContent("Edited"); edit.setLikeCount(999);
        comments.updateContent(edit);
        assertEquals(7, comments.selectById(1L).getLikeCount());
        assertEquals(1, social.feed(20,0).size());
        assertNotNull(comments.selectByUid("comment"));
        for (String change : new String[]{"is_public=FALSE", "status='BANNED'", "memory_type='USER_PROFILE'", "sharing_scope='AGENT_PRIVATE'"}) {
            jdbc.update("UPDATE memories SET is_public=TRUE,status='VISIBLE',memory_type='GENERAL',sharing_scope='PUBLIC'");
            jdbc.update("UPDATE memories SET " + change);
            assertTrue(social.feed(20,0).isEmpty());
            assertEquals(0,social.countFeed());
            assertTrue(comments.selectAll().isEmpty());
            assertTrue(comments.selectByAgentId(2L).isEmpty());
            assertTrue(comments.selectByParentId(1L).isEmpty());
            assertTrue(comments.selectByMemoryId(1L).isEmpty());
            assertTrue(comments.selectRootComments(null,null).isEmpty());
            assertNull(comments.selectByUid("comment"));
            assertNull(comments.selectById(1L));
            assertThrows(BusinessException.class, () -> tx.execute(s -> service.setLike(1L,2L,true)));
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM comments", Integer.class));
    }

    @Test void authenticatedHttpRejectsHumanLikesSpoofingAndPrivateInteractions() throws Exception {
        var memoryController = new com.ai.repo.controller.MemoryController();
        ReflectionTestUtils.setField(memoryController,"memorySocialService", service);
        var commentController = new com.ai.repo.controller.CommentController();
        ReflectionTestUtils.setField(commentController,"commentService", commentService);
        var agents = org.mockito.Mockito.mock(com.ai.repo.service.AgentService.class);
        var agent = new com.ai.repo.entity.Agent(); agent.setId(2L); agent.setUserId(2L); agent.setChallengeVerified(true);
        org.mockito.Mockito.when(agents.findById(2L)).thenReturn(agent);
        var interceptor = new com.ai.repo.security.ApiKeyInterceptor();
        ReflectionTestUtils.setField(interceptor,"agentService",agents);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(memoryController,commentController)
                .setControllerAdvice(new com.ai.repo.exception.GlobalExceptionHandler()).addInterceptors(interceptor).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/memories/1/like")
                .requestAttr("userId",2L).header("X-Logicoma-Policy-Version","2026-09-26"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/memories/1/like"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/memories/1/like")
                .requestAttr("agentId",2L).requestAttr("userId",2L))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(428));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/comments")
                .requestAttr("agentId",2L).requestAttr("userId",2L).header("X-Logicoma-Policy-Version","2026-09-26")
                .contentType("application/json").content("{\"memoryId\":1,\"agentId\":1,\"likeCount\":99,\"content\":\"Hello\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.agentId").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.likeCount").value(0));
        jdbc.update("UPDATE memories SET is_public=FALSE,sharing_scope='AGENT_PRIVATE'");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/memories/1/like")
                .requestAttr("agentId",2L).requestAttr("userId",2L).header("X-Logicoma-Policy-Version","2026-09-26"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/comments")
                .requestAttr("agentId",2L).requestAttr("userId",2L).header("X-Logicoma-Policy-Version","2026-09-26")
                .contentType("application/json").content("{\"memoryId\":1,\"content\":\"Hello\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test void legacyRepliesCannotBypassPrivateTargetAndPublicListsFailClosed() {
        jdbc.update("INSERT INTO comments(uid,agent_id,repo_id,parent_id,content) VALUES('legacy',2,123,1,'Old mismatched reply')");
        assertNull(comments.selectByUid("legacy"));
        assertEquals(1, memories.selectByPublic(true).size());
        jdbc.update("UPDATE memories SET sharing_scope='AGENT_PRIVATE'");
        assertTrue(memories.selectByPublic(true).isEmpty());
        assertTrue(memories.selectByAgentIdAndPublic(1L,true).isEmpty());
        assertTrue(comments.selectAll().isEmpty());
    }
}
