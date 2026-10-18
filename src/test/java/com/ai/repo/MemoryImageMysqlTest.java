package com.ai.repo;

import com.ai.repo.entity.*;
import com.ai.repo.mapper.*;
import com.ai.repo.service.MemoryImageService;
import com.ai.repo.service.impl.MemoryPublicationRequestServiceImpl;
import com.ai.repo.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="MEMORY_IMAGES_IT", matches="true")
class MemoryImageMysqlTest {
    JdbcTemplate jdbc;
    MemoryImageMapper images;
    MemoryImageService service;
    MemoryPublicationRequestServiceImpl publication;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33319/memory_images_test?allowPublicKeyRetrieval=true&useSSL=false", "root", "memory-social-local-test");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS memory_publication_requests");
        jdbc.execute("DROP TABLE IF EXISTS memory_images");
        jdbc.execute("DROP TABLE IF EXISTS memories");
        jdbc.execute("DROP TABLE IF EXISTS agents");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("DROP TABLE IF EXISTS skill_publication_requests");
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE agents(id BIGINT PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("CREATE TABLE memories(id BIGINT PRIMARY KEY, uid VARCHAR(32), user_id BIGINT, agent_id BIGINT, title TEXT, content TEXT, version VARCHAR(20), description TEXT, file_path TEXT, file_size BIGINT, mime_type VARCHAR(100), tags TEXT, category TEXT, is_public BOOLEAN, status VARCHAR(20), download_count INT, like_count INT, metadata TEXT, memory_type VARCHAR(30), sharing_scope VARCHAR(30), owner_type VARCHAR(20), client_memory_key VARCHAR(200), schema_version VARCHAR(20), revision INT, profile_request_hash VARCHAR(64), created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE skill_publication_requests(id BIGINT PRIMARY KEY)");
        var populator = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V9_4__add_memory_publication_requests.sql"),
            new ClassPathResource("db/migration/V12__add_memory_images.sql"));
        populator.execute(ds);
        jdbc.update("INSERT INTO users VALUES(1),(2)");
        jdbc.update("INSERT INTO agents VALUES(1,1),(2,1),(3,2)");
        jdbc.update("INSERT INTO memories(id,user_id,agent_id,title,content,is_public,status,memory_type,sharing_scope) VALUES(1,1,1,'Moment','Private body',FALSE,'VISIBLE','GENERAL','AGENT_PRIVATE'),(2,1,1,'Other','Other',FALSE,'VISIBLE','GENERAL','AGENT_PRIVATE')");
        var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MemoryImageMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/MemoryMapper.xml"),new ClassPathResource("mapper/MemoryPublicationRequestMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        images = session.getMapper(MemoryImageMapper.class);
        service = new MemoryImageService();
        ReflectionTestUtils.setField(service,"memoryMapper",session.getMapper(MemoryMapper.class));
        ReflectionTestUtils.setField(service,"imageMapper",images);
        publication = new MemoryPublicationRequestServiceImpl();
        ReflectionTestUtils.setField(publication,"memoryMapper",session.getMapper(MemoryMapper.class));
        ReflectionTestUtils.setField(publication,"imageMapper",images);
        ReflectionTestUtils.setField(publication,"requestMapper",session.getMapper(MemoryPublicationRequestMapper.class));
        ReflectionTestUtils.setField(publication,"objectMapper",new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(publication,"frontendUrl","http://localhost");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    MockMultipartFile png(int color) throws Exception {
        var image = new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB); image.setRGB(0,0,color);
        var out = new ByteArrayOutputStream(); ImageIO.write(image,"png",out);
        return new MockMultipartFile("file","../../unsafe.svg","text/html",out.toByteArray());
    }
    MemoryImage upload(int color) throws Exception {
        var file=png(color); return tx.execute(s -> service.upload(1L,1L,1L,file));
    }
    void publicMemory() { jdbc.update("UPDATE memories SET is_public=TRUE,sharing_scope='PUBLIC' WHERE id=1"); }
    void privateMemory() { jdbc.update("UPDATE memories SET is_public=FALSE,sharing_scope='AGENT_PRIVATE' WHERE id=1"); }
    @Test void permissionsFollowVisibilityAndDeletionWithoutReusablePublicUrl() throws Exception {
        var image=upload(10);
        assertNotNull(service.read(1L,image.getId(),1L,1L));
        assertNotNull(service.read(1L,image.getId(),1L,null));
        assertThrows(BusinessException.class, () -> service.read(1L,image.getId(),1L,2L));
        assertThrows(BusinessException.class, () -> service.list(1L,2L,3L));
        assertThrows(BusinessException.class, () -> service.read(1L,image.getId(),null,null));
        publicMemory();
        assertNotNull(service.read(1L,image.getId(),2L,3L));
        assertThrows(BusinessException.class, () -> service.read(2L,image.getId(),1L,1L));
        for(String change: List.of("is_public=FALSE", "sharing_scope='AGENT_PRIVATE'", "memory_type='USER_PROFILE'", "status='BANNED'")) {
            jdbc.update("UPDATE memories SET is_public=TRUE,sharing_scope='PUBLIC',memory_type='GENERAL',status='VISIBLE' WHERE id=1");
            jdbc.update("UPDATE memories SET "+change+" WHERE id=1");
            assertThrows(BusinessException.class, () -> service.read(1L,image.getId(),2L,3L));
        }
        jdbc.update("DELETE FROM memories WHERE id=1");
        assertEquals(0,images.list(1L).size());
        assertThrows(BusinessException.class, () -> service.read(1L,image.getId(),1L,1L));
    }
    @Test void publicationBindsImagesAndPublicMutationsAreDenied() throws Exception {
        upload(1);
        var first=tx.execute(s -> publication.create(1L,1L,1L));
        upload(2);
        assertEquals("CHANGED",publication.status(first.requestId(),1L).status());
        assertThrows(BusinessException.class, () -> tx.executeWithoutResult(s -> publication.approve(first.requestId(),1L)));
        var second=tx.execute(s -> publication.create(1L,1L,1L));
        tx.executeWithoutResult(s -> publication.approve(second.requestId(),1L));
        var file=png(3);
        assertThrows(BusinessException.class, () -> tx.execute(s -> service.upload(1L,1L,1L,file)));
        assertThrows(BusinessException.class, () -> tx.executeWithoutResult(s -> service.delete(1L,images.list(1L).get(0).getId(),1L,1L)));
        privateMemory();
        var third=tx.execute(s -> publication.create(1L,1L,1L));
        tx.executeWithoutResult(s -> service.delete(1L,images.list(1L).get(0).getId(),1L,1L));
        assertEquals("CHANGED",publication.status(third.requestId(),1L).status());
    }
    @Test void rejectsInvalidImagesAndBoundsCountAndQuota() throws Exception {
        for (byte[] data: List.of("<svg onload='alert(1)'/>".getBytes(),new byte[5*1024*1024+1],new byte[]{(byte)137,80,78,71})) {
            var file=new MockMultipartFile("file","bad.png","image/png",data);
            assertThrows(BusinessException.class, () -> tx.execute(s -> service.upload(1L,1L,1L,file)));
        }
        var huge=new BufferedImage(4097,1,BufferedImage.TYPE_INT_RGB); var out=new ByteArrayOutputStream(); ImageIO.write(huge,"png",out);
        var file=new MockMultipartFile("file","huge.png","image/png",out.toByteArray());
        assertThrows(BusinessException.class, () -> tx.execute(s -> service.upload(1L,1L,1L,file)));
        for(int i=0;i<9;i++) upload(i);
        assertThrows(BusinessException.class, () -> upload(99));
        assertEquals(9,images.list(1L).size());
        assertEquals(images.list(1L).size(),9);
        privateMemory();
        jdbc.update("UPDATE memory_images SET byte_size=104857600 LIMIT 1");
        var other=png(100);
        assertThrows(BusinessException.class, () -> tx.execute(s -> service.upload(2L,1L,1L,other)));
    }
    @Test void concurrentRetryDeduplicatesAndRollbackLeavesNoOrphan() throws Exception {
        var file=png(7); var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<MemoryImage>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> tx.execute(s -> service.upload(1L,1L,1L,file))));
            var ids=new HashSet<String>(); for(var f:futures) ids.add(f.get(10,TimeUnit.SECONDS).getId());
            assertEquals(1,ids.size()); assertEquals(1,images.list(1L).size());
        } finally { pool.shutdownNow(); }
        var second=png(8);
        tx.executeWithoutResult(s -> { service.upload(1L,1L,1L,second); s.setRollbackOnly(); });
        assertEquals(1,images.list(1L).size());
    }
    @Test void httpAuthenticationPolicyHeadersAndPayloadAreEnforced() throws Exception {
        var controller=new com.ai.repo.controller.MemoryImageController(); ReflectionTestUtils.setField(controller,"service",service);
        var agents=org.mockito.Mockito.mock(com.ai.repo.service.AgentService.class);
        for(long i=1;i<=3;i++) { var a=new Agent(); a.setId(i); a.setUserId(i==3?2L:1L); a.setChallengeVerified(true); org.mockito.Mockito.when(agents.findById(i)).thenReturn(a); }
        var interceptor=new com.ai.repo.security.ApiKeyInterceptor(); ReflectionTestUtils.setField(interceptor,"agentService",agents);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(interceptor).setControllerAdvice(new com.ai.repo.exception.GlobalExceptionHandler()).build();
        mvc.perform(get("/api/memories/1/images")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/memories/1/images").file(png(1)).requestAttr("userId",1L)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/memories/1/images").file(png(1)).requestAttr("userId",1L).requestAttr("agentId",1L)).andExpect(status().is(428));
        mvc.perform(multipart("/api/memories/1/images").file(png(1)).requestAttr("userId",1L).requestAttr("agentId",2L).header("X-Logicoma-Policy-Version","2026-09-26")).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/memories/1/images").file(png(1)).requestAttr("userId",1L).requestAttr("agentId",1L).header("X-Logicoma-Policy-Version","2026-09-26"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.data").doesNotExist());
        var image=images.list(1L).get(0);
        mvc.perform(get("/api/memories/1/images/"+image.getId()).requestAttr("userId",1L))
            .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control","private, no-store, max-age=0"))
            .andExpect(header().string("X-Content-Type-Options","nosniff"));
        publicMemory();
        mvc.perform(get("/api/memories/1/images/"+image.getId()).requestAttr("userId",2L)).andExpect(status().isOk());
        privateMemory();
        mvc.perform(get("/api/memories/1/images/"+image.getId()).requestAttr("userId",2L)).andExpect(status().isNotFound());
    }
    @Test void approvalUsesCurrentImagesAfterAnEarlierRepeatableReadSnapshot() throws Exception {
        upload(1);
        var request=tx.execute(s -> publication.create(1L,1L,1L));
        var pool=Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT COUNT(*) FROM memory_images",Integer.class);
                try { pool.submit(() -> { upload(2); return null; }).get(10,TimeUnit.SECONDS); }
                catch(Exception e) { throw new RuntimeException(e); }
                assertThrows(BusinessException.class, () -> publication.approve(request.requestId(),1L));
                s.setRollbackOnly();
            });
        } finally { pool.shutdownNow(); }
        assertEquals(false,jdbc.queryForObject("SELECT is_public FROM memories WHERE id=1",Boolean.class));
    }
}
