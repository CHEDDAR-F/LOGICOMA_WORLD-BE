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
@EnabledIfEnvironmentVariable(named = "COMMENT_IDEMPOTENCY_IT", matches = "true")
class CommentIdempotencyMysqlTest {
    JdbcTemplate jdbc;
    CommentMapper comments;
    MemorySocialMapper social;
    MemorySocialService service;
    com.ai.repo.service.impl.CommentServiceImpl commentService;
    MemoryMapper memories;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33320/comment_idempotency_test?allowPublicKeyRetrieval=true&useSSL=false", "root", "comment-local-test");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS comment_create_requests");
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
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V13__add_comment_create_requests.sql")));
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MemorySocialMapper.class);
        config.addMapper(CommentCreateRequestMapper.class);
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
        ReflectionTestUtils.setField(commentService, "commentCreateRequestMapper", session.getMapper(CommentCreateRequestMapper.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    Comment input(long agent, String body, Long parent) {
        Comment c=new Comment();c.setAgentId(agent);c.setMemoryId(1L);c.setContent(body);c.setParentId(parent);return c;
    }
    Comment post(long agent,String key,String body,Long parent) {
        return tx.execute(s -> commentService.createIdempotent(input(agent,body,parent),key));
    }
    void status(int code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code,assertThrows(BusinessException.class,call).getCode());
    }
    @Test void twelveConcurrentRetriesCreateOneReplyAndIncrementParentOnce() throws Exception {
        var pool=Executors.newFixedThreadPool(6);
        try {
            var futures=new ArrayList<Future<Comment>>();
            for(int i=0;i<12;i++) futures.add(pool.submit(() -> post(2,"retry-concurrent","same reply",1L)));
            var ids=new java.util.HashSet<Long>();for(var f:futures)ids.add(f.get(10,TimeUnit.SECONDS).getId());
            assertEquals(1,ids.size());
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM comments",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT reply_count FROM comments WHERE id=1",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void keyIsScopedToAgentAndDifferentPayloadOrTargetConflicts() {
        Comment first=post(2,"scoped-key","body",null);
        assertEquals(first.getId(),post(2,"scoped-key","body",null).getId());
        status(409,()->post(2,"scoped-key","changed",null));
        status(409,()->post(2,"scoped-key","body",1L));
        jdbc.update("INSERT INTO memories(id,agent_id,is_public,status,memory_type,sharing_scope) VALUES(2,1,TRUE,'VISIBLE','GENERAL','PUBLIC')");
        Comment other=input(2,"body",null);other.setMemoryId(2L);
        status(409,()->tx.execute(s->commentService.createIdempotent(other,"scoped-key")));
        assertNotEquals(first.getId(),post(1,"scoped-key","other Agent",null).getId());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests",Integer.class));
    }
    @Test void retriesRecheckVisibilityBeforeReceiptLookupAndNeverRevealConflict() {
        post(2,"visibility-key","body",null);
        for(String update:new String[]{"is_public=FALSE","sharing_scope='AGENT_PRIVATE'","status='HIDDEN'","memory_type='USER_PROFILE'"}) {
            jdbc.update("UPDATE memories SET is_public=TRUE,sharing_scope='PUBLIC',status='VISIBLE',memory_type='GENERAL' WHERE id=1");
            jdbc.update("UPDATE memories SET "+update+" WHERE id=1");
            status(404,()->post(2,"visibility-key","body",null));
            status(404,()->post(2,"visibility-key","different",null));
            status(404,()->post(2,"fresh-key","new",null));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM comments",Integer.class));
    }
    @Test void failedCreationRollsBackReceiptAndCanRetryWithoutChangingKey() {
        status(400,()->post(2,"retry-after-reject","body",999L));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests",Integer.class));
        assertNotNull(post(2,"retry-after-reject","body",null).getId());
    }
    @Test void deletedCommentLeavesTombstoneAndEditedCommentKeepsOriginalRequestFingerprint() {
        Comment first=post(2,"edit-key","original",null);
        jdbc.update("UPDATE comments SET content='edited' WHERE id=?",first.getId());
        Comment replay=post(2,"edit-key","original",null);
        assertEquals(first.getId(),replay.getId());assertEquals("edited",replay.getContent());
        status(409,()->post(2,"edit-key","edited",null));
        jdbc.update("DELETE FROM comments WHERE id=?",first.getId());
        status(410,()->post(2,"edit-key","original",null));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM comments",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests WHERE comment_id IS NULL",Integer.class));
    }
    @Test void validationDoesNotClaimKeysAndLegacyCreatesRemainCompatible() {
        for(String key:new String[]{""," has-space","x".repeat(129),"中文"})status(400,()->post(2,key,"body",null));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests",Integer.class));
        Comment a=tx.execute(s->commentService.create(input(2,"repeat intentionally",null)));
        Comment b=tx.execute(s->commentService.create(input(2,"repeat intentionally",null)));
        assertNotEquals(a.getId(),b.getId());
    }
    @Test void receiptSurvivesNewServiceInstance() {
        Comment first=post(2,"restart-key","body",null);
        var replacement=new com.ai.repo.service.impl.CommentServiceImpl();
        ReflectionTestUtils.setField(replacement,"commentMapper",comments);
        ReflectionTestUtils.setField(replacement,"memorySocialService",service);
        ReflectionTestUtils.setField(replacement,"commentCreateRequestMapper",org.springframework.test.util.ReflectionTestUtils.getField(commentService,"commentCreateRequestMapper"));
        assertEquals(first.getId(),tx.execute(s->replacement.createIdempotent(input(2,"body",null),"restart-key")).getId());
    }
    @Test void visibilityChangeWinsBeforeQueuedRetryAndDoesNotClaimReceipt() throws Exception {
        var pool=Executors.newSingleThreadExecutor();
        var started=new CountDownLatch(1);
        try {
            Future<?>[] pending=new Future<?>[1];
            tx.execute(s->{
                jdbc.queryForObject("SELECT id FROM memories WHERE id=1 FOR UPDATE",Long.class);
                pending[0]=pool.submit(()->{started.countDown();return post(2,"private-race","body",null);});
                try {assertTrue(started.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.update("UPDATE memories SET is_public=FALSE,sharing_scope='AGENT_PRIVATE' WHERE id=1");return null;
            });
            var error=assertThrows(ExecutionException.class,()->pending[0].get(5,TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class,error.getCause());
            assertEquals(404,((BusinessException)error.getCause()).getCode());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM comments",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void transactionFailureAfterInsertRollsBackCommentReceiptAndParentCounter() {
        var real=(CommentCreateRequestMapper)ReflectionTestUtils.getField(commentService,"commentCreateRequestMapper");
        var broken=org.mockito.Mockito.mock(CommentCreateRequestMapper.class, org.mockito.AdditionalAnswers.delegatesTo(real));
        org.mockito.Mockito.doThrow(new IllegalStateException("injected commit preparation failure")).when(broken).complete(org.mockito.ArgumentMatchers.any());
        ReflectionTestUtils.setField(commentService,"commentCreateRequestMapper",broken);
        assertThrows(IllegalStateException.class,()->post(2,"transaction-rollback","reply",1L));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM comments",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COALESCE(reply_count,0) FROM comments WHERE id=1",Integer.class));
        ReflectionTestUtils.setField(commentService,"commentCreateRequestMapper",real);
        assertNotNull(post(2,"transaction-rollback","reply",1L).getId());
    }

    @Test void concurrentDifferentTargetsSharingKeyCommitOnlyOnePayload() throws Exception {
        jdbc.update("INSERT INTO memories(id,agent_id,is_public,status,memory_type,sharing_scope) VALUES(2,1,TRUE,'VISIBLE','GENERAL','PUBLIC')");
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Integer>>();
            for(long target=1;target<=2;target++){
                long id=target;
                futures.add(pool.submit(()->{
                    ready.countDown();assertTrue(go.await(2,TimeUnit.SECONDS));
                    try {
                        tx.execute(s->{Comment c=input(2,"target-"+id,null);c.setMemoryId(id);return commentService.createIdempotent(c,"shared-target-key");});return 200;
                    }catch(BusinessException e){return e.getCode();}
                }));
            }
            assertTrue(ready.await(2,TimeUnit.SECONDS));go.countDown();
            var codes=new java.util.ArrayList<Integer>();for(var f:futures)codes.add(f.get(10,TimeUnit.SECONDS));
            codes.sort(Integer::compareTo);assertEquals(java.util.List.of(200,409),codes);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM comment_create_requests",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM comments",Integer.class));
        }finally{pool.shutdownNow();}
    }

}
