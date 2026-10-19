package com.ai.repo.playground.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.AgentMapper;
import com.ai.repo.mapper.UserMapper;
import com.ai.repo.playground.dto.PlaygroundRequests.*;
import com.ai.repo.playground.entity.PlaygroundRows.Activity;
import com.ai.repo.playground.mapper.PlaygroundMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, destructive ONLY to the dedicated localhost playground_test database.
 * No application.yml, production credentials, Redis, or network model calls are loaded.
 */
@EnabledIfEnvironmentVariable(named="PLAYGROUND_TEST_CONFIG",matches=".+")
class PlaygroundPersistenceTest {
    static JsonNode settings;
    static AnnotationConfigApplicationContext context;
    static ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static MutableClock clock=new MutableClock();
    PlaygroundService service;
    String orderMode="SMALL";
    List<JsonNode> capacityTasks=new ArrayList<>();
    PlaygroundMapper store;
    PlaygroundMatchingService matching;
    JdbcTemplate jdbc;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean(destroyMethod="close") DataSource dataSource() {
            // Reuse a small set of real connections; repeated annual suites otherwise
            // exhaust macOS ephemeral sockets with one connection per mapper call.
            HikariDataSource ds=new HikariDataSource();
            ds.setJdbcUrl(settings.get("url").asText()); ds.setUsername("root");
            ds.setPassword(settings.get("password").asText()); ds.setMaximumPoolSize(8);
            ds.setMinimumIdle(0); ds.setDriverClassName("com.mysql.cj.jdbc.Driver"); return ds;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(ds);
            org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true); factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/AgentMapper.xml"),new ClassPathResource("mapper/UserMapper.xml"));
            SqlSessionFactory result=factory.getObject(); result.getConfiguration().addMapper(PlaygroundMapper.class); return result;
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean PlaygroundMapper playgroundMapper(SqlSessionTemplate session) { return session.getMapper(PlaygroundMapper.class); }
        @Bean AgentMapper agentMapper(SqlSessionTemplate session) { return session.getMapper(AgentMapper.class); }
        @Bean UserMapper userMapper(SqlSessionTemplate session) { return session.getMapper(UserMapper.class); }
        @Bean PlaygroundMatchingService matchingService(PlaygroundMapper mapper,AgentMapper agents,PlaygroundService games) { return new PlaygroundMatchingService(mapper,agents,games,json,clock); }
        @Bean PlaygroundService playgroundService(PlaygroundMapper mapper,AgentMapper agents,UserMapper users) {
            return new PlaygroundService(mapper,agents,users,json,true,true,
                    Boolean.parseBoolean(System.getenv("PLAYGROUND_FRANCHISE_TEST")),
                    Boolean.parseBoolean(System.getenv("PLAYGROUND_MODEL_TEST_ANNUAL")),clock);
        }
        @Bean PlaygroundShareService playgroundShareService(PlaygroundMapper mapper,PlaygroundService games) {
            return new PlaygroundShareService(mapper,games,json,true,clock);
        }
    }
    static class MutableClock extends Clock {
        private Instant instant=Instant.parse("2026-09-27T00:00:00Z");
        private boolean live;
        void reset() { instant=Instant.parse("2026-09-27T00:00:00Z"); live=false; }
        void advance(long seconds) { instant=instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return live?Instant.now():instant; }
    }
    @BeforeAll static void schema() throws Exception {
        settings=json.readTree(Files.readString(Path.of(System.getenv("PLAYGROUND_TEST_CONFIG"))));
        assertTrue(settings.get("url").asText().matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/playground_test\\?.*"),"Dedicated test database required");
        context=new AnnotationConfigApplicationContext(Config.class);
        DataSource ds=context.getBean(DataSource.class); JdbcTemplate jdbc=new JdbcTemplate(ds);
        // Refuse any unexpected existing tables. It must be a newly created disposable DB.
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='playground_test'",Integer.class));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }
    @AfterAll static void shutdown() { if (context!=null) context.close(); }
    @BeforeEach void reset() {
        matching=context.getBean(PlaygroundMatchingService.class); service=context.getBean(PlaygroundService.class); store=context.getBean(PlaygroundMapper.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class)); clock.reset();
        for (String table:List.of("playground_match_queue","playground_shares","playground_actions","playground_attempts","playground_tasks","playground_events","playground_seats","playground_activities","playground_daily_budgets","playground_participations"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM agents"); jdbc.update("DELETE FROM users");
        for (long id:List.of(1L,2L,3L)) {
            jdbc.update("INSERT INTO users(id,uid,username,password,email,status) VALUES(?,?,?,?,?,'ACTIVE')",id,"test-user-"+id,"owner"+id,"fixture","owner"+id+"@test.invalid");
            jdbc.update("INSERT INTO agents(id,uid,user_id,name,code,status) VALUES(?,?,?,?,?, 'OFFLINE')",id,"test-agent-"+id,id,"agent"+id,"fixture"+id);
        }
    }
    void online(long id) { jdbc.update("UPDATE agents SET status='ONLINE',last_heartbeat_at=? WHERE id=?",LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),id); }
    @Test void playgroundTablesAndOwnerBriefsSupportUnicode() {
        Integer nonUnicode=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name LIKE 'playground_%' "
                + "AND table_collation NOT LIKE 'utf8mb4%'",Integer.class);
        assertEquals(0,nonUnicode);
        enable(1); enable(2);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"FULL",v5Brief("雨夜修伞与旧书")))
                .get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(v5Brief("社区早餐与雨具修补")));
        String state=jdbc.queryForObject("SELECT state_json FROM playground_activities WHERE id=?",String.class,id);
        assertNotNull(state);
        assertTrue(state.contains("雨夜修伞与旧书"));
        assertTrue(state.contains("社区早餐与雨具修补"));
    }
    @Test void randomQueuePairsWithoutPartnerInputAndReservesSeats() {
        enable(1); enable(2); online(1); online(2);
        assertEquals("WAITING",matching.enqueue(1,new MatchJoin(1L,"FULL",brief("cats"))).get("status"));
        assertEquals(0,count("playground_attempts")); assertEquals(0,count("playground_seats"));
        Map<String,Object> paired=matching.enqueue(2,new MatchJoin(2L,"FULL",brief("dogs")));
        assertEquals("dogs",((OwnerBrief) paired.get("ownerBrief")).theme());
        assertEquals("cats",((OwnerBrief) matching.status(1,1).get("ownerBrief")).theme());
        assertEquals("MATCHED",paired.get("status")); long id=Long.parseLong(paired.get("activityId").toString());
        assertEquals(2,count("playground_seats")); assertEquals(0,count("playground_tasks"));
        service.join(1,id); service.join(2,id);
        assertEquals("PLANNING",store.activity(id).getStatus()); assertEquals(2,count("playground_seats"));
        matching.tick(); businessError("MATCH_ALREADY_PAIRED",()->matching.cancel(1,1));
        assertTrue(store.activity(id).getExpiresAt().isAfter(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).plusMinutes(2)));
        businessError("NOT_AGENT_OWNER",()->matching.status(3,1));
    }
    @Test void queueCancellationAndModeIsolationNeverStartInference() {
        enable(1); enable(2); online(1); online(2);
        matching.enqueue(1,new MatchJoin(1L,"FULL",brief("cats")));
        matching.enqueue(2,new MatchJoin(2L,"SHORT",brief("dogs")));
        assertEquals(0,count("playground_activities")); matching.cancel(1,1);
        assertEquals("CANCELLED",matching.status(1,1).get("status")); assertEquals(0,count("playground_attempts"));
        clock.advance(1801); matching.tick(); assertEquals("EXPIRED",matching.status(2,2).get("status"));
    }
    @Test void unmatchedRuntimeMustBeRecentAndTimeoutRetriesOnlyReadyParty() {
        enable(1); enable(2);
        businessError("AGENT_RUNTIME_NOT_RECENT",()->matching.enqueue(1,new MatchJoin(1L,"FULL",brief("cats"))));
        online(1); online(2); matching.enqueue(1,new MatchJoin(1L,"FULL",brief("cats")));
        long id=Long.parseLong(matching.enqueue(2,new MatchJoin(2L,"FULL",brief("dogs"))).get("activityId").toString());
        service.join(1,id); clock.advance(121); matching.tick();
        assertEquals("INTERRUPTED",store.activity(id).getStatus()); assertEquals(0,count("playground_seats"));
        assertEquals("WAITING",matching.status(1,1).get("status")); assertEquals("EXPIRED",matching.status(2,2).get("status"));
        assertEquals(0,count("playground_attempts"));
    }
    @Test void concurrentQueueEntriesProduceOnlyOneReservedRoom() throws Exception {
        for(long id:List.of(1L,2L,3L)) { enable(id); online(id); }
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(long id:List.of(1L,2L,3L)) futures.add(pool.submit(()->matching.enqueue(id,new MatchJoin(id,"FULL",brief("theme"+id)))));
            for(Future<?> future:futures) future.get(10,TimeUnit.SECONDS);
            assertEquals(1,count("playground_activities")); assertEquals(2,count("playground_seats"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM playground_match_queue WHERE status='WAITING'",Integer.class));
            assertEquals(0,count("playground_attempts"));
        } finally { pool.shutdownNow(); }
    }
    @Test void matchingRequiresDifferentOwnersAndCurrentPermission() {
        enable(1); online(1);
        jdbc.update("UPDATE agents SET user_id=1 WHERE id=2"); online(2);
        service.updateParticipation(1,2,new ParticipationUpdate(0,true,6,6,12));
        matching.enqueue(1,new MatchJoin(1L,"FULL",brief("cats")));
        matching.enqueue(1,new MatchJoin(2L,"FULL",brief("dogs")));
        assertEquals(0,count("playground_activities"));
        service.updateParticipation(1,1,new ParticipationUpdate(1,false,6,6,12)); matching.tick();
        assertEquals("EXPIRED",matching.status(1,1).get("status"));
    }
    @Test void ownerWithdrawalNeverRestartsMatching() {
        enable(1); enable(2); online(1); online(2);
        matching.enqueue(1,new MatchJoin(1L,"FULL",brief("cats")));
        long id=Long.parseLong(matching.enqueue(2,new MatchJoin(2L,"FULL",brief("dogs"))).get("activityId").toString());
        service.join(1,id); service.leave(1,id); matching.tick();
        assertEquals("EXPIRED",matching.status(1,1).get("status")); assertEquals("EXPIRED",matching.status(2,2).get("status"));
    }
    OwnerBrief brief(String theme) { return new OwnerBrief(theme,"CHARACTER",List.of("private:"+theme),List.of("price"),List.of("theme")); }
    OwnerBrief v3Brief(String theme,List<String> partnerFields) {
        return new OwnerBrief(theme,"CHARACTER",List.of("private:"+theme),List.of("price"),List.of("theme"),partnerFields);
    }
    OwnerBrief v4Brief(String theme) {
        return new OwnerBrief(theme,"CHARACTER",List.of("private:"+theme),List.of("price"),List.of("theme"),List.of("theme"),4);
    }
    OwnerBrief v5Brief(String theme) {
        return new OwnerBrief(theme,"CHARACTER",List.of("private:"+theme),List.of("price"),List.of("theme"),List.of("theme"),5);
    }
    ObjectNode v5Strategy() {
        return json.createObjectNode().put("audienceSegment","NIGHT_READERS")
                .put("marketingChannel","FLYERS").put("servicePromise","QUIET")
                .put("monthlyMarketingBudgetMinor",300);
    }
    long openedV5Room() { return openedV5Room("SHORT"); }
    long joinedV5Room(String mode) {
        enable(1); enable(2);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,mode,v5Brief("cats"))).get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(v5Brief("dogs")));
        service.join(1,id); service.join(2,id);
        return id;
    }
    long openedV5Room(String mode) {
        long id=joinedV5Room(mode);
        signV5Plan();
        return id;
    }
    void signV5Plan() {
        ObjectNode first=ready(1), opening=proposal(4,16);
        ObjectNode plan=(ObjectNode)opening.path("payload").path("proposal").path("plan");
        plan.set("venture",venture()); plan.set("strategy",v5Strategy());
        plan.set("contributions",json.createArrayNode().add(json.createObjectNode().put("sourceAgentId",1)
                .put("sourceFieldId","theme").put("placement","SPACE").put("label","Cat wall").putNull("sourceEventId")));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));
        ObjectNode second=ready(2), counter=proposal(4,16);
        counter.put("actionType","COUNTER_PLAN");
        ObjectNode proposal=(ObjectNode)counter.path("payload").path("proposal");
        proposal.put("proposalId","plan-2").put("parentProposalId","plan-1");
        ObjectNode counterPlan=(ObjectNode)proposal.path("plan");
        counterPlan.put("shopName","Cat and dog shop"); counterPlan.set("venture",venture()); counterPlan.set("strategy",v5Strategy());
        counterPlan.set("contributions",json.createArrayNode()
                .add(second.path("visibleState").path("proposal").path("plan").path("contributions").get(0))
                .add(json.createObjectNode().put("sourceAgentId",2).put("sourceFieldId","theme")
                        .put("placement","SERVICE").put("label","Dog service").putNull("sourceEventId")));
        service.submit(2,second.path("taskId").asLong(),submission(second,counter,UUID.randomUUID().toString()));
        ObjectNode third=ready(1), accept=json.createObjectNode().put("actionType","ACCEPT_PLAN")
                .put("publicRationale","Both ideas remain");
        accept.set("payload",json.createObjectNode().put("proposalId","plan-2"));
        service.submit(1,third.path("taskId").asLong(),submission(third,accept,UUID.randomUUID().toString()));
    }
    @Test void v6FoundingTermsAreNegotiatedAndSurviveSettlement() {
        service=new PlaygroundService(store,context.getBean(AgentMapper.class),
                context.getBean(UserMapper.class),json,true,true,false,true,clock);
        long id=joinedV5Room("SHORT");
        assertEquals("0.9",service.ownerActivity(1,id).path("ruleVersion").asText());
        ObjectNode first=ready(1), opening=proposal(4,16);
        ObjectNode openingPlan=(ObjectNode)opening.path("payload").path("proposal").path("plan");
        openingPlan.set("venture",venture()); openingPlan.set("strategy",v5Strategy());
        openingPlan.set("contributions",json.createArrayNode().add(json.createObjectNode()
                .put("sourceAgentId",1).put("sourceFieldId","theme").put("placement","SPACE")
                .put("label","Host concept").putNull("sourceEventId")));
        businessError("INVALID_ACTION",()->service.submit(1,first.path("taskId").asLong(),
                submission(first,opening,UUID.randomUUID().toString())));
        openingPlan.set("foundingAgreement",founding(100,100,50));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));

        ObjectNode second=ready(2), counter=proposal(4,16);
        counter.put("actionType","COUNTER_PLAN");
        ObjectNode counterProposal=(ObjectNode)counter.path("payload").path("proposal");
        counterProposal.put("proposalId","plan-2").put("parentProposalId","plan-1");
        ObjectNode counterPlan=(ObjectNode)counterProposal.path("plan");
        counterPlan.set("venture",venture()); counterPlan.set("strategy",v5Strategy());
        counterPlan.set("foundingAgreement",founding(150,50,60));
        counterPlan.set("contributions",json.createArrayNode()
                .add(second.path("visibleState").path("proposal").path("plan").path("contributions").get(0))
                .add(json.createObjectNode().put("sourceAgentId",2).put("sourceFieldId","theme")
                        .put("placement","SERVICE").put("label","Guest service").putNull("sourceEventId")));
        service.submit(2,second.path("taskId").asLong(),submission(second,counter,UUID.randomUUID().toString()));
        ObjectNode third=ready(1);
        ObjectNode accept=json.createObjectNode().put("actionType","ACCEPT_PLAN")
                .put("publicRationale","I accept the revised funding, profit split, and roles.");
        accept.set("payload",json.createObjectNode().put("proposalId","plan-2"));
        service.submit(1,third.path("taskId").asLong(),submission(third,accept,UUID.randomUUID().toString()));
        assertTrue(service.ownerEvents(1,id,0).stream().anyMatch(event ->
                "FOUNDING_AGREEMENT".equals(event.path("kind").asText())
                && event.path("facts").path("hostCapitalCoins").asInt()==150));
        ObjectNode monthly=ready(2);
        JsonNode window=monthly.path("visibleState").path("monthlyWindow");
        assertFalse(window.path("conflictCode").asText().isBlank());
        String guestPosition=window.path("optionCodes").get(2).asText();
        String hostPosition=window.path("optionCodes").get(0).asText();
        String selected=window.path("optionCodes").get(1).asText();
        service.submit(2,monthly.path("taskId").asLong(),submission(monthly,
                v6Position(window,guestPosition,"Guest independent position"),UUID.randomUUID().toString()));
        ObjectNode reply=ready(1);
        assertTrue(reply.path("visibleState").path("monthlyWindow").path("firstPosition").isNull());
        assertFalse(reply.path("visibleState").path("events").toString().contains("Guest independent position"));
        service.submit(1,reply.path("taskId").asLong(),submission(reply,
                v6Position(reply.path("visibleState").path("monthlyWindow"),hostPosition,"Host independent position"),
                UUID.randomUUID().toString()));
        ObjectNode counterTask=ready(2);
        assertEquals(guestPosition,counterTask.path("visibleState").path("monthlyWindow")
                .path("firstPosition").asText());
        assertEquals(hostPosition,counterTask.path("visibleState").path("monthlyWindow")
                .path("secondPosition").asText());
        service.submit(2,counterTask.path("taskId").asLong(),submission(counterTask,
                v6Counter(counterTask.path("visibleState").path("monthlyWindow"),selected,
                        hostPosition,guestPosition),UUID.randomUUID().toString()));
        ObjectNode finalTask=ready(1);
        JsonNode finalWindow=finalTask.path("visibleState").path("monthlyWindow");
        assertEquals(selected,finalWindow.path("latestOffer").asText());
        assertEquals(1,finalWindow.path("counterRounds").asInt());
        assertTrue(finalTask.path("visibleState").path("events").toString()
                .contains("I keep your caution and concede my original speed."));
        assertTrue(finalTask.path("allowedActions").toString().contains("COUNTER_MONTHLY"));
        service.submit(1,finalTask.path("taskId").asLong(),submission(finalTask,
                v6Counter(finalWindow,hostPosition,selected,hostPosition),
                UUID.randomUUID().toString()));
        ObjectNode lastTask=ready(2);
        assertEquals(2,lastTask.path("visibleState").path("monthlyWindow").path("counterRounds").asInt());
        assertTrue(lastTask.path("visibleState").path("events").toString()
                .contains("I keep your caution and concede my original speed."));
        assertFalse(lastTask.path("allowedActions").toString().contains("COUNTER_MONTHLY"));
        service.submit(2,lastTask.path("taskId").asLong(),submission(lastTask,
                v6MonthlyDecision("ACCEPT_MONTHLY",lastTask.path("visibleState").path("monthlyWindow")),
                UUID.randomUUID().toString()));
        assertTrue(service.ownerEvents(1,id,0).stream().anyMatch(event ->
                "V6_CONFLICT_RESOLUTION".equals(event.path("kind").asText())
                && hostPosition.equals(event.path("facts").path("selectedOption").asText())
                && "COUNTER_ACCEPTED".equals(event.path("facts").path("resolution").asText())));
        JsonNode summary=service.ownerActivity(1,id).path("summary");
        assertEquals("SETTLED",service.ownerActivity(1,id).path("status").asText());
        assertEquals(summary.path("returnedCapitalMinor").asLong(),
                summary.path("ownerOneReturnedMinor").asLong()+summary.path("ownerTwoReturnedMinor").asLong());
        JsonNode published=context.getBean(PlaygroundShareService.class).ownerResultLink(1,id).path("result");
        assertEquals(4,published.path("shareSchemaVersion").asInt());
        assertEquals(150,published.path("foundingAgreement").path("hostCapitalCoins").asInt());
        assertEquals(summary.path("ownerOneReturnedMinor").asLong(),
                published.path("business").path("hostReturnedMinor").asLong());
        assertEquals(hostPosition,published.path("business").path("months").get(1).path("tactic").asText());
        assertEquals(guestPosition,published.path("business").path("months").get(1).path("firstPosition").asText());
        assertEquals(hostPosition,published.path("business").path("months").get(1).path("secondPosition").asText());
        assertEquals(selected,published.path("business").path("months").get(1).path("counter").asText());
        assertEquals(hostPosition,published.path("business").path("months").get(1).path("replyCounter").asText());
        assertEquals(2,published.path("business").path("months").get(1).path("counterRounds").asInt());
        assertFalse(published.toString().contains("private:cats"));
        assertFalse(published.toString().contains("private:dogs"));
    }
    @Test void v6AnnualRoomRequiresSeparateGrantAndNegotiatesEverySurvivingMonth() throws Exception {
        verifyAnnualRoom(false,false);
    }
    @Test void v6OwnerLeavingDuringReviewPreservesSettledGame() throws Exception {
        verifyAnnualRoom(true,false);
    }
    @Test void v6RepeatedReviewModelFailureKeepsSettlementAndLetsPartnerFinish() throws Exception {
        verifyAnnualRoom(false,true);
    }
    @Test void v7AnnualRoomPersistsVersionedContextAndCausalIncident() throws Exception {
        verifyAnnualRoom(false,false,true);
    }
    @Test void v9UnsignedOrderDeadlineFallsBackWithoutCreatingAnObligation() throws Exception {
        orderMode="TIMEOUT";long id=verifyAnnualRoom(false,false,true,true,true);
        assertTrue(service.ownerActivity(1,id).path("capacityOrder").isNull());
    }
    @Test void v9LargeOrderKeepsItsDistinctCapacityAcrossTheYear() throws Exception {
        orderMode="LARGE";long id=verifyAnnualRoom(false,false,true,true,true);
        JsonNode c=service.ownerActivity(1,id).path("capacityOrder");
        assertEquals("LARGE",c.path("choice").asText());
        assertEquals(3,c.path("deliveries").size());
        for(JsonNode d:c.path("deliveries")) { assertEquals(4,d.path("reservedUnits").asInt());assertTrue(d.path("regularCapacity").asInt()<=2); }
    }
    long verifyAnnualRoom(boolean leaveDuringReview,boolean failDuringReview) throws Exception {
        return verifyAnnualRoom(leaveDuringReview,failDuringReview,false);
    }
    @Test void v8StakesSurviveRestartAndPublishOnlyFiniteImpactAccounts() throws Exception {
        long id=verifyAnnualRoom(false,false,true,true);
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        var row=store.share(id);
        ObjectNode payload=(ObjectNode)json.readTree(row.getPayloadJson());
        assertEquals(6,payload.path("shareSchemaVersion").asInt());
        JsonNode month=payload.path("business").path("months").get(1);
        assertEquals("SAME_MONTH_SAME_MARKET",month.path("decisionImpact").path("basis").asText());
        assertEquals(month.path("profitMinor").asLong(),month.path("decisionImpact").path("actual").path("profitMinor").asLong());
        String original=row.getPayloadJson();
        ((ObjectNode)month.path("decisionImpact").path("firstPosition")).put("ownerPrivateCanary","must-never-be-public");
        row.setPayloadJson(json.writeValueAsString(payload));store.saveShare(row);
        businessError("SHARE_NOT_FOUND",()->shares.publicResult(row.getPublicToken()));
        row.setPayloadJson(original);store.saveShare(row);
        assertEquals(6,shares.publicResult(row.getPublicToken()).path("shareSchemaVersion").asInt());
        Files.writeString(Path.of("target/playground-v8-stakes.public.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                json.createObjectNode().put("source","LOCAL_MYSQL_DETERMINISTIC_DECISIONS_NOT_MODEL_EVIDENCE")
                    .set("result",shares.publicResult(row.getPublicToken()))));
    }
    long verifyAnnualRoom(boolean leaveDuringReview,boolean failDuringReview,boolean storyVersion) throws Exception {
        return verifyAnnualRoom(leaveDuringReview,failDuringReview,storyVersion,false);
    }
    @Test void v9OrderSurvivesRestartAndPublishesThreeActualDeliveries() throws Exception {
        long id=verifyAnnualRoom(false,false,true,true,true);
        JsonNode order=service.ownerActivity(1,id).path("capacityOrder");
        assertEquals("SMALL",order.path("choice").asText());
        assertEquals(3,order.path("deliveries").size());
        var link=context.getBean(PlaygroundShareService.class).ownerResultLink(1,id);
        assertEquals(json.readTree(order.toString()),link.path("result").path("business").path("capacityOrder"));
        var shareRow=store.share(id);String originalPayload=shareRow.getPayloadJson();
        ObjectNode unsafe=(ObjectNode)json.readTree(originalPayload);
        ((ObjectNode)unsafe.path("business").path("capacityOrder").path("deliveries").get(0)).put("privateNote","canary");
        shareRow.setPayloadJson(json.writeValueAsString(unsafe));store.saveShare(shareRow);
        businessError("SHARE_NOT_FOUND",()->context.getBean(PlaygroundShareService.class).publicResult(shareRow.getPublicToken()));
        shareRow.setPayloadJson(originalPayload);store.saveShare(shareRow);
        Files.writeString(Path.of("target/playground-v9-tasks.json"),json.writeValueAsString(capacityTasks));
        Files.writeString(Path.of("target/playground-v9-orders.public.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(link.path("result")));
    }
    long verifyAnnualRoom(boolean leaveDuringReview,boolean failDuringReview,boolean storyVersion,boolean stakesVersion) throws Exception {
        return verifyAnnualRoom(leaveDuringReview,failDuringReview,storyVersion,stakesVersion,false);
    }
    long verifyAnnualRoom(boolean leaveDuringReview,boolean failDuringReview,boolean storyVersion,boolean stakesVersion,boolean ordersVersion) throws Exception {
        return verifyAnnualRoom(leaveDuringReview,failDuringReview,storyVersion,stakesVersion,ordersVersion,false);
    }
    @Test void v10PressurePersistsAndPublicProjectionRejectsNestedNotes() throws Exception {
        orderMode="LARGE";long id=verifyAnnualRoom(false,false,true,true,true,true);
        var view=service.ownerActivity(1,id);assertEquals("1.4",view.path("ruleVersion").asText());
        assertEquals(3,view.path("capacityPressure").path("reports").size());
        var shares=context.getBean(PlaygroundShareService.class);var link=shares.ownerResultLink(1,id);
        assertEquals(8,link.path("result").path("shareSchemaVersion").asInt());
        assertEquals(json.readTree(view.path("capacityPressure").toString()),link.path("result").path("business").path("capacityPressure"));
        var row=store.share(id);String original=row.getPayloadJson();ObjectNode unsafe=(ObjectNode)json.readTree(original);
        ((ObjectNode)unsafe.path("business").path("capacityPressure").path("reports").get(0).path("evidence")).put("privateNote","canary");
        row.setPayloadJson(json.writeValueAsString(unsafe));store.saveShare(row);
        businessError("SHARE_NOT_FOUND",()->shares.publicResult(row.getPublicToken()));
        row.setPayloadJson(original);store.saveShare(row);
        Files.writeString(Path.of("target/playground-v10-tasks.json"),json.writeValueAsString(capacityTasks));
        Files.writeString(Path.of("target/playground-v10-pressure.public.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(link.path("result")));
    }
    long verifyAnnualRoom(boolean leaveDuringReview,boolean failDuringReview,boolean storyVersion,boolean stakesVersion,boolean ordersVersion,boolean pressureVersion) throws Exception {
        service=new PlaygroundService(store,context.getBean(AgentMapper.class),
                context.getBean(UserMapper.class),json,true,true,false,true,clock);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enrichedContext",true);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"storyEnabled",storyVersion);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"stakesEnabled",stakesVersion);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"ordersEnabled",ordersVersion);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"capacityPressureEnabled",pressureVersion);
        enable(1); enable(2);
        businessError("ANNUAL_PARTICIPATION_GRANT_REQUIRED",()->service.invite(1,
                new Invitation(1L,2L,"FULL",v5Brief("cats"))));
        for (long actor:List.of(1L,2L))
            service.updateParticipation(actor,actor,new ParticipationUpdate(1,true,40,40,40));
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"FULL",v5Brief("cats")))
                .get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(v5Brief("dogs")));
        service.join(1,id); service.join(2,id);
        assertEquals(pressureVersion?"1.4":ordersVersion?"1.3":stakesVersion?"1.2":storyVersion?"1.1":"1.0",service.ownerActivity(1,id).path("ruleVersion").asText());
        ObjectNode first=ready(1), opening=proposal(6,20);
        ObjectNode openingPlan=(ObjectNode)opening.path("payload").path("proposal").path("plan");
        openingPlan.set("venture",venture()); openingPlan.set("strategy",v5Strategy());
        openingPlan.set("foundingAgreement",founding(100,100,50));
        openingPlan.set("contributions",json.createArrayNode().add(json.createObjectNode()
                .put("sourceAgentId",1).put("sourceFieldId","theme").put("placement","SPACE")
                .put("label","Host concept").putNull("sourceEventId")));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));
        ObjectNode second=ready(2), counter=proposal(6,20);
        counter.put("actionType","COUNTER_PLAN");
        ObjectNode terms=(ObjectNode)counter.path("payload").path("proposal");
        terms.put("proposalId","plan-2").put("parentProposalId","plan-1");
        ObjectNode partnerPlan=(ObjectNode)terms.path("plan");
        partnerPlan.set("venture",venture()); partnerPlan.set("strategy",v5Strategy());
        partnerPlan.set("foundingAgreement",founding(150,50,60));
        partnerPlan.set("contributions",json.createArrayNode()
                .add(second.path("visibleState").path("proposal").path("plan").path("contributions").get(0))
                .add(json.createObjectNode().put("sourceAgentId",2).put("sourceFieldId","theme")
                        .put("placement","SERVICE").put("label","Guest service").putNull("sourceEventId")));
        service.submit(2,second.path("taskId").asLong(),submission(second,counter,UUID.randomUUID().toString()));
        ObjectNode third=ready(1), accept=json.createObjectNode().put("actionType","ACCEPT_PLAN")
                .put("publicRationale","I accept the revised funding, profit split, and roles.");
        accept.set("payload",json.createObjectNode().put("proposalId","plan-2"));
        service.submit(1,third.path("taskId").asLong(),submission(third,accept,UUID.randomUUID().toString()));
        if (storyVersion) {
            // A restart with the new-room switch off must preserve the saved rules and task context.
            service=new PlaygroundService(store,context.getBean(AgentMapper.class),
                    context.getBean(UserMapper.class),json,true,true,false,false,clock);
            assertEquals(pressureVersion?"1.4":ordersVersion?"1.3":stakesVersion?"1.2":"1.1",service.ownerActivity(1,id).path("ruleVersion").asText());
        }
        int meetings=0;
        while (service.ownerActivity(1,id).path("status").asText().equals("PLANNING")
                && !service.ownerActivity(1,id).path("phase").asText().equals("PARTNER_REVIEW")) {
            JsonNode saved=json.readTree(store.activity(id).getStateJson());
            if(ordersVersion && saved.path("orderWindow").isObject()
                    && !saved.path("orderWindow").path("phase").asText().equals("CLOSED")) {
                JsonNode ow=saved.path("orderWindow");
                if(orderMode.equals("TIMEOUT")) {
                    clock.advance(901);service.expire(id);continue;
                }
                long a=ow.path("firstAgentId").asLong(),b=ow.path("secondAgentId").asLong();
                ObjectNode t=ready(a); var window=t.path("visibleState").path("orderWindow");
                var initialAction=orderAction(window,"POSITION_CAPACITY_ORDER","LARGE");
                var initialSubmission=submission(t,initialAction,UUID.randomUUID().toString());
                var receipt=service.submit(a,t.path("taskId").asLong(),initialSubmission);
                assertEquals(receipt,service.submit(a,t.path("taskId").asLong(),initialSubmission));
                t=ready(b);window=t.path("visibleState").path("orderWindow");
                assertTrue(window.path("firstPlan").isNull());
                service.submit(b,t.path("taskId").asLong(),submission(t,orderAction(window,"POSITION_CAPACITY_ORDER",orderMode),UUID.randomUUID().toString()));
                t=ready(a);window=t.path("visibleState").path("orderWindow");
                if(orderMode.equals("LARGE")) {
                    service.submit(a,t.path("taskId").asLong(),submission(t,orderAction(window,"ACCEPT_CAPACITY_ORDER",null),UUID.randomUUID().toString()));
                    continue;
                }
                ObjectNode revised=orderAction(window,"REPLY_CAPACITY_ORDER",orderMode);
                ((ObjectNode)revised.path("payload")).put("claimsRevision",!orderMode.equals("LARGE"));
                service.submit(a,t.path("taskId").asLong(),submission(t,revised,UUID.randomUUID().toString()));
                // Reconstruct service with new-room flags off: accepted obligation is persisted.
                service=new PlaygroundService(store,context.getBean(AgentMapper.class),context.getBean(UserMapper.class),json,true,true,false,false,clock);
                t=ready(b);window=t.path("visibleState").path("orderWindow");
                service.submit(b,t.path("taskId").asLong(),submission(t,orderAction(window,"ACCEPT_CAPACITY_ORDER",null),UUID.randomUUID().toString()));
                assertEquals("SMALL",service.ownerActivity(1,id).path("capacityOrder").path("choice").asText());
                continue;
            }
            JsonNode persisted=saved.path("v6AnnualWindow");
            assertTrue(persisted.path("month").asInt()>=2 && persisted.path("month").asInt()<=12);
            long leader=persisted.path("firstAgentId").asLong();
            long partner=persisted.path("secondAgentId").asLong();
            ObjectNode leaderTask=ready(leader);
            JsonNode window=leaderTask.path("visibleState").path("monthlyWindow");
            assertEquals(meetings+2,window.path("month").asInt());
            assertTrue(window.path("responsibilities").isArray());
            assertTrue(window.path("responsibilities").size()>=1);
            assertEquals(json.valueToTree(leader==1?List.of("SUPPLY"):List.of("FULFILLMENT","CUSTOMERS")),
                    window.path("responsibilities"));
            assertTrue(window.path("optionEffects").isArray());
            assertEquals(window.path("conflictCode").isNull()?0:3,window.path("optionEffects").size());
            for (JsonNode effect:window.path("optionEffects")) {
                assertEquals(8,effect.size());
                assertTrue(effect.path("expenseCoins").isInt());
                assertTrue(effect.path("rentDeltaCoins").isInt());
            }
            JsonNode trend=window.path("recentTrend");
            assertEquals(Math.min(3,meetings+1),trend.size());
            assertEquals(meetings+1,trend.get(trend.size()-1).path("month").asInt());
            assertEquals(window.path("priorReport").path("closingCashMinor").asLong(),
                    trend.get(trend.size()-1).path("closingCashMinor").asLong());
            ObjectNode plan=json.createObjectNode().put("productionBand","STANDARD")
                    .put("marketingAction",storyVersion && meetings==0?"COMMUNITY_EVENT":"NONE")
                    .put("serviceFocus","FULFILLMENT");
            if (storyVersion) {
                assertTrue(window.has("cause"));
                if (!window.path("cause").isNull()) {
                    assertEquals("COMMUNITY_EVENT",window.path("cause").path("sourceValue").asText());
                    assertEquals(window.path("month").asInt()-1,window.path("cause").path("sourceMonth").asInt());
                    assertEquals("RUSH_BULK_ORDER",window.path("conflictCode").asText());
                }
            }
            if (window.path("conflictCode").isNull()) plan.putNull("incidentResponse");
            else plan.put("incidentResponse",window.path("optionCodes").get(0).asText());
            service.submit(leader,leaderTask.path("taskId").asLong(),submission(leaderTask,
                    annualPosition(window,plan),UUID.randomUUID().toString()));
            ObjectNode partnerTask=ready(partner);
            assertTrue(partnerTask.path("visibleState").path("monthlyWindow").path("firstPlan").isNull());
            String sealedEventId=store.events(id,Math.max(0,store.activity(id).getNextSequence()-51))
                    .stream().map(event->{
                        try { return json.readTree(event); }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    })
                    .filter(event->"V6_ANNUAL_DECISION".equals(event.path("kind").asText())
                            && window.path("triggerEventId").asText().equals(event.path("facts")
                                    .path("action").path("payload").path("triggerEventId").asText()))
                    .map(event->event.path("eventId").asText()).findFirst().orElseThrow();
            assertFalse(partnerTask.path("visibleState").path("events").toString().contains(sealedEventId));
            ObjectNode partnerChoice=plan.deepCopy();
            if (meetings==0) partnerChoice.put("productionBand","CONSERVATIVE");
            service.submit(partner,partnerTask.path("taskId").asLong(),submission(partnerTask,
                    annualPosition(partnerTask.path("visibleState").path("monthlyWindow"),partnerChoice),
                    UUID.randomUUID().toString()));
            ObjectNode confirmation=ready(leader);
            if (meetings==0) {
                JsonNode disputed=confirmation.path("visibleState").path("monthlyWindow");
                assertEquals("AWAIT_FIRST_REPLY",disputed.path("phase").asText());
                ObjectNode reply=json.createObjectNode().put("actionType","REPLY_MONTHLY")
                        .put("publicRationale","I defend standard capacity because demand was manageable.");
                reply.set("payload",json.createObjectNode()
                        .put("triggerEventId",disputed.path("triggerEventId").asText())
                        .put("targetVersion",disputed.path("targetVersion").asText())
                        .put("claimsRevision",false).put("focusField","productionBand")
                        .put("reasonFocus","CASH").set("plan",plan));
                service.submit(leader,confirmation.path("taskId").asLong(),submission(confirmation,
                        reply,UUID.randomUUID().toString()));
                confirmation=ready(partner);
            } else assertEquals("AWAIT_CONFIRM",confirmation.path("visibleState").path("monthlyWindow")
                    .path("phase").asText());
            ObjectNode confirmationAction=json.createObjectNode().put("actionType",stakesVersion && meetings==0?"DECLINE_MONTHLY":"ACCEPT_MONTHLY")
                    .put("publicRationale",stakesVersion && meetings==0
                            ?"I decline this plan because I prefer conserving production cash."
                            :"I read the same plan and confirm our operating choice.");
            confirmationAction.set("payload",json.createObjectNode()
                    .put("triggerEventId",window.path("triggerEventId").asText())
                    .put("targetVersion",confirmation.path("visibleState").path("monthlyWindow")
                            .path("targetVersion").asText()).put("reasonFocus","CASH"));
            service.submit(meetings==0?partner:leader,confirmation.path("taskId").asLong(),submission(confirmation,
                    confirmationAction,UUID.randomUUID().toString()));
            meetings++;
            assertTrue(meetings<=11);
        }
        assertTrue(meetings>0 && meetings<=11);
        JsonNode finalGame=service.ownerActivity(1,id).path("game");
        int operated=finalGame.path("operatedMonths").asInt();
        int failedOpening=finalGame.path("failedOpeningMonth").isNull()?0:
                finalGame.path("failedOpeningMonth").asInt();
        assertEquals(Math.min(11,operated+(failedOpening>0?1:0)-1),meetings);
        assertEquals(meetings,jdbc.queryForObject("SELECT COUNT(*) FROM playground_events "
                +"WHERE activity_id=? AND JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.kind'))='V6_ANNUAL_RESOLUTION'",
                Integer.class,id));
        assertEquals("PARTNER_REVIEW",service.ownerActivity(1,id).path("phase").asText());
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        businessError("GAME_NOT_FINISHED",()->shares.ownerResultLink(1,id));
        if (leaveDuringReview) {
            service.leave(1,id);
            assertEquals("SETTLED",service.ownerActivity(1,id).path("status").asText());
            JsonNode published=shares.ownerResultLink(1,id).path("result");
            assertEquals("UNAVAILABLE",published.path("partnerReviews").get(0).path("status").asText());
            assertEquals("UNAVAILABLE",published.path("partnerReviews").get(1).path("status").asText());
            return id;
        }
        if (failDuringReview) {
            ObjectNode firstReview=ready(1);
            assertEquals("PARTNER_REVIEW",firstReview.path("phase").asText());
            AttemptFailure failure=new AttemptFailure(firstReview.path("leaseToken").asText(),
                    firstReview.path("permissionVersion").asLong(),firstReview.path("attemptId").asText(),"MODEL_OUTPUT_INVALID");
            assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,firstReview.path("taskId").asLong(),failure)
                    .path("status").asText());
            ObjectNode retry=ready(1);
            AttemptFailure exhausted=new AttemptFailure(retry.path("leaseToken").asText(),
                    retry.path("permissionVersion").asLong(),retry.path("attemptId").asText(),"MODEL_OUTPUT_INVALID");
            assertEquals("PLANNING",service.reportAttemptFailure(1,retry.path("taskId").asLong(),exhausted)
                    .path("status").asText());
            assertEquals("PARTNER_REVIEW",service.ownerActivity(1,id).path("phase").asText());
        }
        for (long actor:failDuringReview?List.of(2L):List.of(1L,2L)) {
            ObjectNode reviewTask=ready(actor);
            assertEquals("PARTNER_REVIEW",reviewTask.path("phase").asText());
            if (actor==2) assertFalse(reviewTask.path("visibleState").path("events").toString()
                    .contains("My partner helped us make a specific operating choice together."));
            JsonNode evidence=reviewTask.path("visibleState").path("partnerReview").path("partnerEvidence");
            assertTrue(evidence.size()>0);
            ObjectNode review=json.createObjectNode().put("actionType","REVIEW_PARTNER")
                    .put("publicRationale","My partner helped us make a specific operating choice together.");
            review.set("payload",json.createObjectNode()
                    .put("partnerStrength","They responded to private:cats in a monthly proposal.")
                    .put("friction","We disagreed about production risk.")
                    .put("futureCollaboration","YES")
                    .put("evidenceEventId",evidence.get(evidence.size()-1).path("eventId").asText()));
            ObjectNode forged=review.deepCopy();
            ((ObjectNode)forged.path("payload")).put("evidenceEventId","999:999");
            businessError("REVIEW_EVIDENCE_REQUIRED",()->service.submit(actor,
                    reviewTask.path("taskId").asLong(),
                    submission(reviewTask,forged,UUID.randomUUID().toString())));
            service.submit(actor,reviewTask.path("taskId").asLong(),
                    submission(reviewTask,review,UUID.randomUUID().toString()));
        }
        assertEquals("SETTLED",service.ownerActivity(1,id).path("status").asText());
        ObjectNode link=shares.ownerResultLink(1,id);
        assertEquals(failDuringReview?1:2,jdbc.queryForObject("SELECT COUNT(*) FROM playground_events WHERE activity_id=? "
                +"AND JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.kind'))='PARTNER_REVIEW' "
                +"AND JSON_EXTRACT(event_json,'$.virtualMonth')=?",Integer.class,id,operated));
        JsonNode publicResult=link.path("result");
        String sharePath=link.path("sharePath").asText();
        String token=sharePath.substring(sharePath.indexOf("/shares/")+8,sharePath.indexOf("/landing"));
        assertEquals(pressureVersion?8:ordersVersion?7:stakesVersion?6:5,shares.publicResult(token).path("shareSchemaVersion").asInt());
        assertEquals(2,shares.publicResult(token).path("partnerReviews").size());
        assertEquals(pressureVersion?8:ordersVersion?7:stakesVersion?6:5,publicResult.path("shareSchemaVersion").asInt());
        assertEquals(2,publicResult.path("partnerReviews").size());
        assertEquals(failDuringReview?"UNAVAILABLE":"RECORDED",
                publicResult.path("partnerReviews").get(0).path("status").asText());
        assertEquals("RECORDED",publicResult.path("partnerReviews").get(1).path("status").asText());
        assertEquals(finalGame.path("operatedMonths").asInt(),
                publicResult.path("business").path("months").size());
        assertEquals(stakesVersion?"DECLINED":"COUNTER_ACCEPTED",publicResult.path("business").path("months").get(1)
                .path("resolution").asText());
        assertEquals("CONSERVATIVE",publicResult.path("business").path("months").get(1)
                .path("secondPlan").path("productionBand").asText());
        if (stakesVersion) {
            JsonNode firstMonth=publicResult.path("business").path("months").get(1);
            assertEquals("SIGNED_FALLBACK",firstMonth.path("effectivePlan").asText());
            assertTrue(firstMonth.path("incidentUnresolved").asBoolean());
            assertEquals(firstMonth.path("decisionImpact").path("actual"),firstMonth.path("decisionImpact").path("signedFallback"));
        } else assertEquals("STANDARD",publicResult.path("business").path("months").get(1)
                .path("effectivePlan").path("productionBand").asText());
        assertFalse(publicResult.toString().contains("private:cats"));
        assertFalse(publicResult.toString().contains("private:dogs"));
        return id;
    }
    ObjectNode orderAction(JsonNode window,String type,String choice) {
        ObjectNode action=json.createObjectNode().put("actionType",type).put("publicRationale","Reserve enough capacity for the coming three months.");
        ObjectNode payload=action.putObject("payload").put("triggerEventId",window.path("triggerEventId").asText())
                .put("targetVersion",window.path("currentOfferVersion").asText());
        if(choice!=null) payload.put("choice",choice);
        return action;
    }
    ObjectNode annualPosition(JsonNode window,ObjectNode plan) {
        ObjectNode action=json.createObjectNode().put("actionType","POSITION_MONTHLY")
                .put("publicRationale","First annual position based on the prior month report.");
        action.set("payload",json.createObjectNode()
                .put("triggerEventId",window.path("triggerEventId").asText())
                .put("targetVersion",window.path("targetVersion").asText())
                .put("reasonFocus","CASH").set("plan",plan));
        return action;
    }
    ObjectNode founding(int hostCapital,int guestCapital,int hostProfitPercent) {
        return json.createObjectNode().put("hostCapitalCoins",hostCapital)
                .put("guestCapitalCoins",guestCapital).put("hostProfitPercent",hostProfitPercent)
                .put("serviceLead","GUEST").put("supplyLead","HOST")
                .put("communityLead","GUEST");
    }
    ObjectNode v6Position(JsonNode window,String choice,String reason) {
        ObjectNode action=json.createObjectNode().put("actionType","POSITION_MONTHLY")
                .put("publicRationale",reason);
        action.set("payload",json.createObjectNode()
                .put("triggerEventId",window.path("triggerEventId").asText())
                .put("planVersion",window.path("planVersion").asText())
                .put("tacticCode",choice));
        return action;
    }
    ObjectNode v6Counter(JsonNode window,String choice,String kept,String conceded) {
        ObjectNode action=v6Position(window,choice,"I keep your caution and concede my original speed.");
        action.put("actionType","COUNTER_MONTHLY");
        ((ObjectNode)action.path("payload")).put("keptFromPartner",kept).put("concededOwnPoint",conceded);
        return action;
    }
    ObjectNode v6MonthlyDecision(String type,JsonNode window) {
        ObjectNode action=json.createObjectNode().put("actionType",type)
                .put("publicRationale","I accept the concrete compromise.");
        action.set("payload",json.createObjectNode()
                .put("triggerEventId",window.path("triggerEventId").asText())
                .put("planVersion",window.path("planVersion").asText()));
        return action;
    }
    ObjectNode v5Decision(String type,JsonNode window,String choice) {
        ObjectNode action=json.createObjectNode().put("actionType",type).put("publicRationale","Our shop responds to this month");
        ObjectNode payload=json.createObjectNode().put("triggerEventId",window.path("triggerEventId").asText())
                .put("planVersion",window.path("planVersion").asText());
        if (choice!=null) payload.put("choice",choice);
        action.set("payload",payload); return action;
    }
    @Test void v5SignedStrategyOpensMonthTwoAndBothAgentsSettleIt() {
        long id=openedV5Room();
        JsonNode before=service.ownerActivity(1,id);
        assertEquals("PLANNING",before.path("status").asText());
        assertEquals("0.8",before.path("ruleVersion").asText());
        assertEquals(1,before.path("game").path("operatedMonths").asInt());
        ObjectNode proposalTask=ready(2);
        assertEquals("MONTHLY_DECISION",proposalTask.path("phase").asText());
        JsonNode window=proposalTask.path("visibleState").path("monthlyWindow");
        assertEquals("plan-2",window.path("planVersion").asText());
        businessError("STALE_V5_SIGNAL",()->service.submit(2,proposalTask.path("taskId").asLong(),
                submission(proposalTask,v5Decision("PROPOSE_MONTHLY",json.createObjectNode()
                        .put("triggerEventId","old:1").put("planVersion","plan-2"),"PROMOTE"),UUID.randomUUID().toString())));
        service.submit(2,proposalTask.path("taskId").asLong(),
                submission(proposalTask,v5Decision("PROPOSE_MONTHLY",window,"PROMOTE"),UUID.randomUUID().toString()));
        ObjectNode replyTask=ready(1);
        assertEquals("PROMOTE",replyTask.path("visibleState").path("monthlyWindow").path("proposal").asText());
        JsonNode receipt=service.submit(1,replyTask.path("taskId").asLong(),
                submission(replyTask,v5Decision("ACCEPT_MONTHLY",window,null),UUID.randomUUID().toString()));
        assertEquals("SETTLED",receipt.path("status").asText());
        assertEquals(2,service.ownerActivity(1,id).path("game").path("operatedMonths").asInt());
        assertTrue(service.ownerEvents(1,id,0).stream().anyMatch(event ->
                "MONTHLY_RESOLUTION".equals(event.path("kind").asText())
                        && "PROMOTE".equals(event.path("facts").path("effectiveResponse").asText())
                        && "PARTNERS_APPROVED".equals(event.path("facts").path("resolution").asText())));
        assertEquals(0,count("playground_seats"));
    }
    @Test void v5MonthFallsBackToStandingStrategyWhenTaskExpires() {
        long id=openedV5Room();
        clock.advance(901); service.expire(id);
        assertEquals("SETTLED",service.ownerActivity(1,id).path("status").asText());
        assertTrue(service.ownerEvents(1,id,0).stream().anyMatch(event ->
                "MONTHLY_RESOLUTION".equals(event.path("kind").asText())
                        && "DEADLINE_FALLBACK".equals(event.path("facts").path("resolution").asText())));
    }
    @Test void v5PublicShareCarriesSignedShopAndMonthChoiceWithoutOwnerBrief() {
        long id=openedV5Room();
        ObjectNode proposalTask=ready(2);
        JsonNode window=proposalTask.path("visibleState").path("monthlyWindow");
        service.submit(2,proposalTask.path("taskId").asLong(),submission(proposalTask,
                v5Decision("PROPOSE_MONTHLY",window,"TEMPORARY_PIVOT"),UUID.randomUUID().toString()));
        ObjectNode reply=ready(1);
        service.submit(1,reply.path("taskId").asLong(),submission(reply,
                v5Decision("ACCEPT_MONTHLY",window,null),UUID.randomUUID().toString()));
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        ObjectNode link=shares.ownerResultLink(1,id);
        JsonNode result=link.path("result");
        assertEquals("NIGHT_READERS",result.path("shop").path("strategy").path("audienceSegment").asText());
        assertEquals("TEMPORARY_PIVOT",result.path("business").path("months").get(1).path("response").asText());
        assertEquals("PARTNERS_APPROVED",result.path("business").path("months").get(1).path("resolution").asText());
        assertFalse(result.toString().contains("ownerBrief"));
        assertFalse(result.toString().contains("private:cats"));
        String token=link.path("sharePath").asText().split("/")[4];
        assertEquals(result.toString(),shares.publicResult(token).toString());
    }
    @Test void v5FullYearNegotiatesCompetitorSupplyAndLateShock() {
        long id=openedV5Room("FULL");
        for (int decisionMonth=0;decisionMonth<3;decisionMonth++) {
            ObjectNode proposalTask=ready(decisionMonth%2==0?2:1);
            JsonNode window=proposalTask.path("visibleState").path("monthlyWindow");
            assertTrue(window.path("month").asInt()>=2);
            service.submit(proposalTask.path("actorId").asText().equals("agent:1")?1:2,
                    proposalTask.path("taskId").asLong(),submission(proposalTask,
                            v5Decision("PROPOSE_MONTHLY",window,"KEEP_IDENTITY"),UUID.randomUUID().toString()));
            long partner=proposalTask.path("actorId").asText().equals("agent:1")?2:1;
            ObjectNode reply=ready(partner);
            service.submit(partner,reply.path("taskId").asLong(),submission(reply,
                    v5Decision("ACCEPT_MONTHLY",window,null),UUID.randomUUID().toString()));
        }
        JsonNode owner=service.ownerActivity(1,id);
        assertEquals("SETTLED",owner.path("status").asText());
        assertEquals(12,owner.path("summary").path("operatedMonths").asInt());
        assertEquals("YEAR_COMPLETE",owner.path("summary").path("ending").asText());
        List<JsonNode> events=service.ownerEvents(1,id,0);
        assertEquals(12,events.stream().filter(e->"MONTH_REPORT".equals(e.path("kind").asText())).count());
        List<JsonNode> signals=events.stream().filter(e->"MONTHLY_SIGNAL".equals(e.path("kind").asText())).toList();
        assertEquals(3,signals.size());
        assertEquals(2,signals.get(0).path("virtualMonth").asInt());
        assertEquals(5,signals.get(1).path("virtualMonth").asInt());
        assertEquals("SUPPLY_DELAY",signals.get(1).path("facts").path("signal").asText());
        assertTrue(signals.get(2).path("virtualMonth").asInt()>=7);
        assertTrue(signals.get(2).path("virtualMonth").asInt()<=9);
        assertNotEquals("COMPETITOR",signals.get(2).path("facts").path("signal").asText());
        assertEquals(8,events.stream().filter(e->"MONTHLY_CONTINUITY".equals(e.path("kind").asText())).count());
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        ObjectNode link=shares.ownerResultLink(1,id);
        JsonNode share=link.path("result");
        assertEquals(12,share.path("business").path("months").size());
        assertEquals("SUPPLY_DELAY",share.path("business").path("months").get(4).path("signal").asText());
        assertEquals(share.toString(),shares.publicResult(link.path("sharePath").asText().split("/")[4]).toString());
        int lateMonth=signals.get(2).path("virtualMonth").asInt();
        assertEquals(signals.get(2).path("facts").path("signal").asText(),
                share.path("business").path("months").get(lateMonth-1).path("signal").asText());
        assertEquals("PARTNERS_APPROVED",share.path("business").path("months").get(lateMonth-1).path("resolution").asText());
        assertEquals("KEEP_IDENTITY",share.path("business").path("months").get(11).path("response").asText());
    }
    @Test void v5FullYearExpiredDecisionContinuesIntoNextWindowAndStillSettles() {
        long id=openedV5Room("FULL");
        clock.advance(901); service.expire(id);
        assertEquals("PLANNING",service.ownerActivity(1,id).path("status").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM playground_tasks WHERE status='PENDING'",Integer.class));
        clock.advance(901); service.expire(id);
        clock.advance(901); service.expire(id);
        JsonNode result=service.ownerActivity(1,id);
        assertEquals("SETTLED",result.path("status").asText());
        assertEquals(12,result.path("summary").path("operatedMonths").asInt());
        assertEquals(3,service.ownerEvents(1,id,0).stream().filter(event ->
                "MONTHLY_RESOLUTION".equals(event.path("kind").asText())
                        && "DEADLINE_FALLBACK".equals(event.path("facts").path("resolution").asText())).count());
        assertEquals(0,count("playground_seats"));
    }
    @Test void v5FranchisePitchRequiresBothAgentsAndPersistsTheSameOffer() throws Exception {
        service=new PlaygroundService(store,context.getBean(AgentMapper.class),
                context.getBean(UserMapper.class),json,true,true,true,clock);
        long id=joinedV5Room("FULL");
        ObjectNode pitch=ready(1);
        Files.writeString(Path.of("target/playground-franchise-task-contract.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(pitch));
        assertEquals("FRANCHISE_DECISION",pitch.path("phase").asText());
        JsonNode offer=pitch.path("visibleState").path("franchiseWindow");
        String offerId=offer.path("offerId").asText();
        assertEquals(0,pitch.path("visibleState").path("virtualMonth").asInt());
        assertFalse(offer.path("terms").has("support"));
        assertTrue(offer.path("ownInvestigation").isNull());
        ObjectNode check=franchiseAction("CHECK_FRANCHISE_STORES",offerId,null);
        service.submit(1,pitch.path("taskId").asLong(),submission(pitch,check,UUID.randomUUID().toString()));
        ObjectNode proposal=ready(1);
        assertEquals(offerId,proposal.path("visibleState").path("franchiseWindow").path("offerId").asText());
        assertFalse(proposal.path("visibleState").path("franchiseWindow")
                .path("ownInvestigation").path("clueCode").asText().isBlank());
        businessError("STALE_FRANCHISE_OFFER",()->service.submit(1,proposal.path("taskId").asLong(),
                submission(proposal,franchiseAction("PROPOSE_FRANCHISE","wrong-offer","SIGN"),UUID.randomUUID().toString())));
        service.submit(1,proposal.path("taskId").asLong(),submission(proposal,
                franchiseAction("PROPOSE_FRANCHISE",offerId,"SIGN"),UUID.randomUUID().toString()));
        ObjectNode partner=ready(2);
        assertTrue(partner.path("visibleState").path("franchiseWindow").path("ownInvestigation").isNull());
        assertFalse(partner.toString().contains(proposal.path("visibleState").path("franchiseWindow")
                .path("ownInvestigation").path("clueCode").asText()));
        service.submit(2,partner.path("taskId").asLong(),submission(partner,
                franchiseAction("ACCEPT_FRANCHISE",offerId,null),UUID.randomUUID().toString()));
        assertEquals(0,service.ownerActivity(1,id).path("game").path("operatedMonths").asInt());
        assertEquals("PLANNING",service.tasks(1).get(0).get("phase"));
        signV5Plan();
        JsonNode events=json.valueToTree(service.ownerEvents(1,id,0));
        assertEquals(1,java.util.stream.StreamSupport.stream(events.spliterator(),false)
                .filter(event->"FRANCHISE_PITCH".equals(event.path("kind").asText())).count());
        assertTrue(events.toString().contains("SIGNED"));
        assertTrue(service.ownerActivity(1,id).path("game").path("operatedMonths").asInt()>=1);
        assertFalse(service.ownerActivity(1,id).toString().contains("private:dogs"));
        while (service.ownerActivity(1,id).path("status").asText().equals("PLANNING")) {
            clock.advance(901); service.expire(id);
        }
        JsonNode published=context.getBean(PlaygroundShareService.class).ownerResultLink(1,id).path("result");
        assertEquals("SIGNED",published.path("business").path("franchise").path("resolution").asText());
        assertEquals(0,published.path("business").path("franchise").path("month").asInt());
        assertTrue(published.path("agentMoves").toString().contains("PROPOSE_FRANCHISE"));
        assertFalse(published.toString().contains("private:cats"));
        assertFalse(published.toString().contains("private:dogs"));
    }
    ObjectNode franchiseAction(String type,String offerId,String decision) {
        ObjectNode action=json.createObjectNode().put("actionType",type)
                .put("publicRationale","We checked the promised support against the cost.");
        ObjectNode payload=json.createObjectNode().put("offerId",offerId).put("offerVersion",1);
        if (decision!=null) payload.put("decision",decision);
        action.set("payload",payload); return action;
    }
    @Test void v5FranchiseDeadlineKeepsOriginalShopAndContinuesTheYear() {
        service=new PlaygroundService(store,context.getBean(AgentMapper.class),
                context.getBean(UserMapper.class),json,true,true,true,clock);
        long id=joinedV5Room("FULL");
        assertEquals("FRANCHISE_DECISION",service.tasks(1).get(0).get("phase"));
        clock.advance(901); service.expire(id);
        assertEquals("PLANNING",service.ownerActivity(1,id).path("status").asText());
        assertEquals(1,service.tasks(1).size()+service.tasks(2).size());
        signV5Plan();
        while (service.ownerActivity(1,id).path("status").asText().equals("PLANNING")) {
            clock.advance(901); service.expire(id);
        }
        JsonNode owner=service.ownerActivity(1,id);
        assertEquals("SETTLED",owner.path("status").asText());
        assertEquals(12,owner.path("summary").path("operatedMonths").asInt());
        assertFalse(owner.path("summary").toString().contains("FRANCHISE_SIGNED"));
        assertTrue(service.ownerEvents(1,id,0).stream().anyMatch(event ->
                "FRANCHISE_RESOLUTION".equals(event.path("kind").asText())
                        && "DEADLINE_FALLBACK".equals(event.path("facts").path("resolution").asText())));
    }
    @Test void v5FranchiseRepeatedModelFailureSkipsSigningAndContinuesTheYear() {
        service=new PlaygroundService(store,context.getBean(AgentMapper.class),
                context.getBean(UserMapper.class),json,true,true,true,clock);
        long id=joinedV5Room("FULL");
        ObjectNode pitch=ready(1);
        AttemptFailure failure=new AttemptFailure(pitch.path("leaseToken").asText(),
                pitch.path("permissionVersion").asLong(),pitch.path("attemptId").asText(),"MODEL_OUTPUT_INVALID");
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,pitch.path("taskId").asLong(),failure).path("status").asText());
        ObjectNode retry=ready(1);
        AttemptFailure secondFailure=new AttemptFailure(retry.path("leaseToken").asText(),
                retry.path("permissionVersion").asLong(),retry.path("attemptId").asText(),"MODEL_OUTPUT_INVALID");
        assertEquals("PLANNING",service.reportAttemptFailure(1,retry.path("taskId").asLong(),secondFailure).path("status").asText());
        assertEquals("PLANNING",service.ownerActivity(1,id).path("status").asText());
        assertEquals(1,service.tasks(1).size()+service.tasks(2).size());
        assertTrue(service.ownerEvents(1,id,0).stream().anyMatch(event ->
                "FRANCHISE_RESOLUTION".equals(event.path("kind").asText())
                        && "MODEL_FAILURE_FALLBACK".equals(event.path("facts").path("resolution").asText())));
        assertFalse(service.ownerActivity(1,id).path("game").toString().contains("FRANCHISE_SIGNED"));
    }
    @Test void v5RequiresExplicitFlagAndAcceptsFullModeInMatchingQueue() {
        PlaygroundService gated=new PlaygroundService(store,context.getBean(AgentMapper.class),
                context.getBean(UserMapper.class),json,true,false,clock);
        businessError("V5_NOT_AVAILABLE",()->gated.contractVersionFor(v5Brief("cats")));
        enable(1); online(1);
        assertEquals("WAITING",matching.enqueue(1,new MatchJoin(1L,"FULL",v5Brief("cats"))).get("status"));
        assertEquals(1,count("playground_match_queue"));
    }
    @Test void v5FullRandomMatchHasTimeForTheBoundedAnnualDecisions() {
        enable(1); enable(2); online(1); online(2);
        matching.enqueue(1,new MatchJoin(1L,"FULL",v5Brief("cats")));
        long id=Long.parseLong(matching.enqueue(2,new MatchJoin(2L,"FULL",v5Brief("dogs")))
                .get("activityId").toString());
        Activity room=store.activity(id);
        assertEquals(12,room.getHorizonMonths());
        assertEquals(5,service.ownerActivity(1,id).path("contractVersion").asInt());
        assertEquals(clock.instant().plusSeconds(3600),room.getExpiresAt().toInstant(ZoneOffset.UTC));
    }
    long v4Room() {
        enable(1); enable(2);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",v4Brief("cats"))).get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(v4Brief("dogs")));
        service.join(1,id); service.join(2,id); return id;
    }
    long openedV4Room() { return openedV4Room(false); }
    long openedV4Room(boolean exhaustGuestDailyBudget) {
        long id=v4Room(); openV4Room(id,exhaustGuestDailyBudget); return id;
    }
    void openV4Room(long id,boolean exhaustGuestDailyBudget) {
        ObjectNode first=ready(1);
        ObjectNode opening=proposal(4,16);
        ((ObjectNode)opening.path("payload").path("proposal").path("plan")).set("venture",venture());
        ObjectNode firstElement=json.createObjectNode().put("sourceAgentId",1)
                .put("placement","SPACE").put("label","Cat wall").putNull("sourceEventId");
        if (first.path("visibleState").path("ownerBrief").path("partnerShareFields").isEmpty()
                && !first.path("visibleState").path("ownerBrief").path("agentMayReferenceOwnFields").asBoolean()) firstElement.putNull("sourceFieldId");
        else firstElement.put("sourceFieldId","theme");
        ((ObjectNode)opening.path("payload").path("proposal").path("plan"))
                .set("contributions",json.createArrayNode().add(firstElement));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));
        ObjectNode second=ready(2);
        JsonNode carried=second.path("visibleState").path("proposal").path("plan").path("contributions").get(0);
        ObjectNode counter=proposal(4,16); counter.put("actionType","COUNTER_PLAN");
        ObjectNode terms=(ObjectNode)counter.path("payload").path("proposal");
        terms.put("proposalId","plan-2").put("parentProposalId","plan-1");
        ((ObjectNode)terms.path("plan")).put("shopName","Cat and dog shop");
        ((ObjectNode)terms.path("plan")).set("venture",venture());
        ObjectNode secondElement=json.createObjectNode().put("sourceAgentId",2)
                .put("placement","SERVICE").put("label","Dog service").putNull("sourceEventId");
        if (second.path("visibleState").path("ownerBrief").path("partnerShareFields").isEmpty()
                && !second.path("visibleState").path("ownerBrief").path("agentMayReferenceOwnFields").asBoolean()) secondElement.putNull("sourceFieldId");
        else secondElement.put("sourceFieldId","theme");
        ((ObjectNode)terms.path("plan")).set("contributions",json.createArrayNode().add(carried).add(secondElement));
        service.submit(2,second.path("taskId").asLong(),submission(second,counter,UUID.randomUUID().toString()));
        ObjectNode third=ready(1);
        ObjectNode decision=json.createObjectNode().put("actionType","ACCEPT_PLAN").put("publicRationale","Both ideas remain");
        decision.set("payload",json.createObjectNode().put("proposalId","plan-2"));
        if (exhaustGuestDailyBudget)
            jdbc.update("UPDATE playground_daily_budgets SET attempts_used=12 WHERE agent_id=2");
        service.submit(1,third.path("taskId").asLong(),submission(third,decision,UUID.randomUUID().toString()));
    }
    ObjectNode orderDecision(String type,String offerId) {
        ObjectNode decision=json.createObjectNode().put("actionType",type).put("publicRationale","My own choice");
        decision.set("payload",json.createObjectNode().put("offerId",offerId)); return decision;
    }
    @Test void v4OpportunityWaitsForBothAgentsAndSettlesOneRealOrder() {
        long id=openedV4Room();
        JsonNode waiting=service.ownerActivity(1,id);
        assertEquals("PLANNING",waiting.path("status").asText());
        assertEquals("0.5",waiting.path("ruleVersion").asText());
        assertEquals(1,waiting.path("game").path("operatedMonths").asInt());
        ObjectNode buyer=ready(2);
        assertEquals(4,buyer.path("contractVersion").asInt());
        assertEquals("OFFER_NEGOTIATION",buyer.path("phase").asText());
        String offerId=buyer.path("visibleState").path("orderOffer").path("offerId").asText();
        ObjectNode contractTask=buyer.deepCopy(); contractTask.put("leaseToken","fixture-replaced-lease-token");
        try { Files.writeString(Path.of("target/playground-server-v4-offer-task.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(contractTask)); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
        assertEquals(2,buyer.path("visibleState").path("orderOffer").path("dueMonth").asInt());
        businessError("STALE_ORDER_OFFER",()->service.submit(2,buyer.path("taskId").asLong(),
                submission(buyer,orderDecision("ACCEPT_ORDER","fake-offer"),UUID.randomUUID().toString())));
        Submission firstDecision=submission(buyer,orderDecision("ACCEPT_ORDER",offerId),UUID.randomUUID().toString());
        assertEquals("PLANNING",service.submit(2,buyer.path("taskId").asLong(),firstDecision).path("status").asText());
        ObjectNode partner=ready(1);
        assertEquals(offerId,partner.path("visibleState").path("orderOffer").path("offerId").asText());
        Submission secondDecision=submission(partner,orderDecision("ACCEPT_ORDER",offerId),UUID.randomUUID().toString());
        JsonNode receipt=service.submit(1,partner.path("taskId").asLong(),secondDecision);
        assertEquals("SETTLED",receipt.path("status").asText());
        assertEquals(receipt,service.submit(1,partner.path("taskId").asLong(),secondDecision));
        JsonNode summary=service.ownerActivity(2,id).path("summary");
        assertEquals(2,summary.path("operatedMonths").asInt());
        assertTrue(summary.path("reports").get(1).path("events").toString().contains("NPC_ORDER_FULFILLED"));
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->"NPC_OFFER".equals(e.path("kind").asText())).count());
        assertEquals(2,service.ownerEvents(1,id,0).stream().filter(e->"NPC_ORDER_DECISION".equals(e.path("kind").asText())).count());
        assertEquals(2,service.ownerEvents(1,id,0).stream().filter(e->"MONTH_REPORT".equals(e.path("kind").asText())).count());
        assertEquals(0,count("playground_seats"));
        try { Files.writeString(Path.of("target/playground-server-v4-events.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(service.ownerEvents(1,id,0))); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @Test void v4DeclineContinuesOrdinaryTradingWithoutInventingNpcIncome() {
        long id=openedV4Room(); ObjectNode task=ready(2);
        String offerId=task.path("visibleState").path("orderOffer").path("offerId").asText();
        assertEquals("SETTLED",service.submit(2,task.path("taskId").asLong(),
                submission(task,orderDecision("DECLINE_ORDER",offerId),UUID.randomUUID().toString())).path("status").asText());
        JsonNode summary=service.ownerActivity(1,id).path("summary");
        assertEquals(2,summary.path("operatedMonths").asInt());
        assertFalse(summary.path("reports").get(1).path("events").toString().contains("NPC_ORDER_FULFILLED"));
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->"NPC_ORDER_DECISION".equals(e.path("kind").asText())).count());
        assertEquals(0,count("playground_seats"));
    }
    @Test void v4ExhaustedBudgetSkipsOpportunityWithoutAcceptingForAgent() {
        long id=openedV4Room(true);
        JsonNode summary=service.ownerActivity(1,id).path("summary");
        assertEquals(2,summary.path("operatedMonths").asInt());
        assertFalse(summary.path("reports").get(1).path("events").toString().contains("NPC_ORDER_FULFILLED"));
        assertEquals(1,service.ownerEvents(1,id,0).stream()
                .filter(e->"NPC_OPPORTUNITY_SKIPPED".equals(e.path("kind").asText())).count());
        assertEquals(0,service.ownerEvents(1,id,0).stream()
                .filter(e->"NPC_ORDER_DECISION".equals(e.path("kind").asText())).count());
        assertEquals(0,count("playground_seats"));
    }
    @Test void v4RepeatedModelFailureSkipsOrderAndKeepsRealTrading() {
        long id=openedV4Room(); ObjectNode first=ready(2);
        assertFalse(first.path("visibleState").has("monthlyWindow"));
        AttemptFailure firstFailure=new AttemptFailure(first.path("leaseToken").asText(),
                first.path("permissionVersion").asLong(),first.path("attemptId").asText(),"MODEL_OUTPUT_INVALID");
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(2,first.path("taskId").asLong(),firstFailure).path("status").asText());
        ObjectNode second=ready(2);
        AttemptFailure secondFailure=new AttemptFailure(second.path("leaseToken").asText(),
                second.path("permissionVersion").asLong(),second.path("attemptId").asText(),"MODEL_OUTPUT_INVALID");
        assertEquals("SETTLED",service.reportAttemptFailure(2,second.path("taskId").asLong(),secondFailure).path("status").asText());
        assertEquals("SETTLED",service.reportAttemptFailure(2,second.path("taskId").asLong(),secondFailure).path("status").asText());
        JsonNode summary=service.ownerActivity(1,id).path("summary");
        assertEquals(2,summary.path("operatedMonths").asInt());
        assertFalse(summary.path("reports").get(1).path("events").toString().contains("NPC_ORDER_FULFILLED"));
        assertEquals(1,service.ownerEvents(1,id,0).stream()
                .filter(e->"NPC_OPPORTUNITY_SKIPPED".equals(e.path("kind").asText())).count());
        assertEquals(0,count("playground_seats"));
    }
    @Test void v4ExpiredOrderWindowSettlesOrdinaryMonthsOnce() {
        long id=openedV4Room(); ready(2);
        clock.advance(901); service.expire(id); service.expire(id);
        assertEquals("SETTLED",service.ownerActivity(1,id).path("status").asText());
        assertEquals(2,service.ownerActivity(1,id).path("summary").path("operatedMonths").asInt());
        assertEquals(1,service.ownerEvents(1,id,0).stream()
                .filter(e->"NPC_OPPORTUNITY_SKIPPED".equals(e.path("kind").asText())).count());
        assertEquals(2,service.ownerEvents(1,id,0).stream()
                .filter(e->"MONTH_REPORT".equals(e.path("kind").asText())).count());
        assertEquals(0,count("playground_seats"));
    }
    @Test void randomMatchingDoesNotPairV3AndV4Briefs() {
        enable(1); enable(2); online(1); online(2);
        assertEquals("WAITING",matching.enqueue(1,new MatchJoin(1L,"SHORT",v3Brief("cats",List.of("theme")))).get("status"));
        assertEquals("WAITING",matching.enqueue(2,new MatchJoin(2L,"SHORT",v4Brief("dogs"))).get("status"));
        assertEquals(0,count("playground_activities"));
    }
    long v3Room() {
        enable(1); enable(2);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",v3Brief("cats",List.of("theme")))).get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(v3Brief("dogs",List.of("theme"))));
        service.join(1,id); service.join(2,id); return id;
    }
    @Test void oneDefiniteV3ModelErrorSchedulesBoundedFreshTaskWithoutDoubleChargingOnReplay() {
        long id=v3Room(); ObjectNode first=ready(1);
        assertFalse(first.path("visibleState").has("monthlyWindow"));
        AttemptFailure failure=new AttemptFailure(first.path("leaseToken").asText(),1,
                first.path("attemptId").asText(),"MODEL_OUTPUT_INVALID");
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,first.path("taskId").asLong(),failure).path("status").asText());
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,first.path("taskId").asLong(),failure).path("status").asText());
        assertEquals("PLANNING",service.ownerActivity(1,id).path("status").asText());
        assertEquals("RETRIED",store.task(first.path("taskId").asLong()).getStatus());
        assertEquals(1,service.tasks(1).size());
        assertEquals(1,count("playground_attempts"));
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(event->"AGENT_FAILURE".equals(event.path("kind").asText())).count());
        ObjectNode retry=ready(1);
        assertNotEquals(first.path("taskId").asText(),retry.path("taskId").asText());
        AttemptFailure exhausted=new AttemptFailure(retry.path("leaseToken").asText(),1,
                retry.path("attemptId").asText(),"MODEL_RESPONSE_INCOMPLETE");
        assertEquals("INTERRUPTED",service.reportAttemptFailure(1,retry.path("taskId").asLong(),exhausted).path("status").asText());
        assertEquals(0,count("playground_seats"));
        assertEquals(2,count("playground_attempts"));
        assertEquals(0,count("playground_actions"));
        assertEquals(2,jdbc.queryForObject("SELECT attempts_used FROM playground_daily_budgets WHERE agent_id=1",Integer.class));
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,first.path("taskId").asLong(),failure).path("status").asText());
    }
    @Test void v5FormatHintIsPrivateBoundedAndClearedAfterValidAction() throws Exception {
        enable(1); enable(2);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",v5Brief("cats"))).get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(v5Brief("dogs")));
        service.join(1,id); service.join(2,id);
        ObjectNode first=ready(1);
        businessError("INVALID_FORMAT_HINT",()->service.reportAttemptFailure(1,first.path("taskId").asLong(),
                new AttemptFailure(first.path("leaseToken").asText(),1,first.path("attemptId").asText(),
                        "MODEL_PROVIDER_REJECTED","PLAN_CONTRIBUTIONS")));
        AttemptFailure invalid=new AttemptFailure(first.path("leaseToken").asText(),1,
                first.path("attemptId").asText(),"MODEL_OUTPUT_INVALID","PLAN_CONTRIBUTIONS");
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,first.path("taskId").asLong(),invalid).path("status").asText());
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,first.path("taskId").asLong(),invalid).path("status").asText());
        assertEquals(1,count("playground_attempts"));
        assertEquals(1,jdbc.queryForObject("SELECT attempts_used FROM playground_daily_budgets WHERE agent_id=1",Integer.class));
        ObjectNode retry=ready(1);
        assertEquals("PLAN_CONTRIBUTIONS",retry.path("visibleState").path("retryHint").asText());
        ObjectNode opening=proposal(4,16);
        ObjectNode plan=(ObjectNode)opening.path("payload").path("proposal").path("plan");
        plan.set("venture",venture()); plan.set("strategy",v5Strategy());
        plan.set("contributions",json.createArrayNode().add(json.createObjectNode().put("sourceAgentId",1)
                .put("sourceFieldId","theme").put("placement","SPACE").put("label","Cat wall").putNull("sourceEventId")));
        service.submit(1,retry.path("taskId").asLong(),submission(retry,opening,UUID.randomUUID().toString()));
        ObjectNode partner=ready(2);
        assertTrue(partner.path("visibleState").path("retryHint").isNull());
        assertEquals(0,json.readTree(store.activity(id).getStateJson()).path("consecutiveModelFailures").size());
    }
    @Test void v5FormatHintRequiresInvalidOutputAndCurrentContract() {
        long id=v3Room(); ObjectNode first=ready(1);
        businessError("INVALID_FORMAT_HINT",()->service.reportAttemptFailure(1,first.path("taskId").asLong(),
                new AttemptFailure(first.path("leaseToken").asText(),1,first.path("attemptId").asText(),
                        "MODEL_OUTPUT_INVALID","ACTION_SHAPE")));
        assertEquals(1,count("playground_attempts"));
        assertEquals("PLANNING",service.ownerActivity(1,id).path("status").asText());
    }
    @Test void definiteProviderRejectionUsesBoundedFailurePath() {
        long id=v3Room(); ObjectNode first=ready(1);
        AttemptFailure rejected=new AttemptFailure(first.path("leaseToken").asText(),1,
                first.path("attemptId").asText(),"MODEL_PROVIDER_REJECTED");
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,first.path("taskId").asLong(),rejected).path("status").asText());
        assertEquals("RETRY_PENDING",service.reportAttemptFailure(1,first.path("taskId").asLong(),rejected).path("status").asText());
        ObjectNode retry=ready(1);
        assertEquals("INTERRUPTED",service.reportAttemptFailure(1,retry.path("taskId").asLong(),
                new AttemptFailure(retry.path("leaseToken").asText(),1,retry.path("attemptId").asText(),
                        "MODEL_PROVIDER_REJECTED")).path("status").asText());
        assertEquals("INTERRUPTED",service.ownerActivity(1,id).path("status").asText());
        assertEquals(0,count("playground_seats"));
        assertEquals(2,count("playground_attempts"));
        assertEquals(0,count("playground_actions"));
    }
    @Test void v3ModelRepairResetsFailureStreakOnlyAfterValidAction() throws Exception {
        long id=v3Room(); ObjectNode first=ready(1);
        service.reportAttemptFailure(1,first.path("taskId").asLong(),new AttemptFailure(
                first.path("leaseToken").asText(),1,first.path("attemptId").asText(),"MODEL_OUTPUT_INVALID"));
        ObjectNode retry=ready(1);
        ObjectNode action=proposal(4,16);
        ObjectNode element=json.createObjectNode().put("sourceAgentId",1).put("sourceFieldId","theme")
                .put("placement","SPACE").put("label","Cat wall").putNull("sourceEventId");
        ((ObjectNode)action.path("payload").path("proposal").path("plan"))
                .set("contributions",json.createArrayNode().add(element));
        service.submit(1,retry.path("taskId").asLong(),submission(retry,action,UUID.randomUUID().toString()));
        assertEquals("PLANNING",service.ownerActivity(1,id).path("status").asText());
        assertEquals(1,service.tasks(2).size());
        assertEquals(0,json.readTree(store.activity(id).getStateJson()).path("consecutiveModelFailures").size());
    }
    @Test void v3DoesNotScheduleRepairBeyondOwnerAttemptBudget() {
        enable(1); enable(2);
        service.updateParticipation(1,1,new ParticipationUpdate(1,true,6,1,12));
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",v3Brief("cats",List.of("theme")))).get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(v3Brief("dogs",List.of("theme"))));
        service.join(1,id); service.join(2,id);
        ObjectNode task=ready(1);
        assertEquals("INTERRUPTED",service.reportAttemptFailure(1,task.path("taskId").asLong(),
                new AttemptFailure(task.path("leaseToken").asText(),2,task.path("attemptId").asText(),"MODEL_REFUSED"))
                .path("status").asText());
        assertTrue(service.tasks(1).isEmpty());
        assertEquals(1,count("playground_attempts"));
    }
    @Test void v3MatchingProjectsConsentAndRequiresTwoAttributedElements() {
        enable(1); enable(2); online(1); online(2);
        matching.enqueue(1,new MatchJoin(1L,"SHORT",v3Brief("cats",List.of("theme"))));
        long id=Long.parseLong(matching.enqueue(2,new MatchJoin(2L,"SHORT",v3Brief("dogs",List.of()))).get("activityId").toString());
        service.join(1,id); service.join(2,id);
        ObjectNode first=ready(1);
        assertEquals(3,first.get("contractVersion").asInt());
        assertEquals(0,first.path("visibleState").path("partnerBrief").path("fields").size());
        assertFalse(first.path("visibleState").path("ownerBrief").has("hardConstraints"));
        ObjectNode opening=proposal(4,16);
        ObjectNode firstElement=json.createObjectNode().put("sourceAgentId",1).put("sourceFieldId","theme")
                .put("placement","SPACE").put("label","Cat wall").putNull("sourceEventId");
        ((ObjectNode)opening.path("payload").path("proposal").path("plan"))
                .set("contributions",json.createArrayNode().add(firstElement));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));
        ObjectNode second=ready(2);
        JsonNode carried=second.path("visibleState").path("proposal").path("plan").path("contributions").get(0);
        String sourceEvent=carried.path("sourceEventId").asText();
        assertTrue(service.ownerEvents(1,id,0).stream().anyMatch(event->sourceEvent.equals(event.path("eventId").asText())
                && "PROPOSAL".equals(event.path("kind").asText())));
        assertEquals("cats",second.path("visibleState").path("partnerBrief").path("fields").path("theme").asText());
        assertFalse(second.path("visibleState").path("ownerBrief").has("hardConstraints"));
        ObjectNode premature=accept();
        businessError("INCOMPLETE_SHARED_PLAN",()->service.submit(2,second.path("taskId").asLong(),
                submission(second,premature,UUID.randomUUID().toString())));
        ObjectNode counter=proposal(4,16); counter.put("actionType","COUNTER_PLAN");
        ObjectNode proposal=(ObjectNode)counter.path("payload").path("proposal");
        proposal.put("proposalId","plan-2").put("parentProposalId","plan-1");
        ((ObjectNode)proposal.path("plan")).put("shopName","Cat and night shop");
        ObjectNode own=json.createObjectNode().put("sourceAgentId",2).putNull("sourceFieldId")
                .put("placement","SERVICE").put("label","Night service").putNull("sourceEventId");
        ((ObjectNode)proposal.path("plan")).set("contributions",json.createArrayNode().add(carried).add(own));
        ObjectNode forged=(ObjectNode)counter.deepCopy();
        ((ObjectNode)forged.path("payload").path("proposal").path("plan").path("contributions").get(0)).put("label","Forged");
        businessError("FORGED_CONTRIBUTION_SOURCE",()->service.submit(2,second.path("taskId").asLong(),
                submission(second,forged,UUID.randomUUID().toString())));
        service.submit(2,second.path("taskId").asLong(),submission(second,counter,UUID.randomUUID().toString()));
        ObjectNode third=ready(1);
        assertEquals(2,third.path("visibleState").path("proposal").path("plan").path("contributions").size());
        ObjectNode decision=json.createObjectNode().put("actionType","ACCEPT_PLAN").put("publicRationale","Both ideas remain");
        decision.set("payload",json.createObjectNode().put("proposalId","plan-2"));
        service.submit(1,third.path("taskId").asLong(),submission(third,decision,UUID.randomUUID().toString()));
        assertEquals("SETTLED",service.ownerActivity(1,id).path("status").asText());
        JsonNode game=service.ownerActivity(1,id).path("game");
        String shock=game.path("environment").path("shock").asText();
        assertNotEquals("NONE",shock);
        assertEquals(2,game.path("environment").path("fromMonth").asInt());
        assertEquals(2,game.path("reports").size());
        assertTrue(game.path("reports").get(1).path("events").toString().contains(shock));
        assertEquals(2,service.ownerEvents(1,id,0).stream().filter(event->"MONTH_REPORT".equals(event.path("kind").asText())).count());
    }
    @Test void ownerMessageReachesOnlyItsAgentAndOwnOwnerView() {
        enable(1); enable(2);
        List<String> ownFields=List.of("theme","priority","hardConstraints","negotiable");
        OwnerBrief host=new OwnerBrief("cats","CHARACTER",List.of("keep the welcome"),List.of("rain plan"),ownFields,
                List.of(),null,"Keep the small shop welcoming",true);
        OwnerBrief guest=new OwnerBrief("dogs","PROFIT",List.of("save cash"),List.of("price"),ownFields,
                List.of(),null,"Leave some cash for a rainy day",true);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",host)).get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(guest));
        assertEquals(host.ownerMessage(),service.ownerActivity(1,id).path("ownerBrief").path("ownerMessage").asText());
        assertFalse(service.ownerActivity(2,id).toString().contains(host.ownerMessage()));
        service.join(1,id); service.join(2,id);
        ObjectNode first=ready(1);
        assertEquals(host.ownerMessage(),first.path("visibleState").path("ownerBrief").path("ownerMessage").asText());
        assertEquals("keep the welcome",first.path("visibleState").path("ownerBrief").path("hardConstraints").get(0).asText());
        assertEquals(0,first.path("visibleState").path("partnerBrief").path("fields").size());
        assertEquals(0,first.path("visibleState").path("ownerBrief").path("partnerShareFields").size());
        assertTrue(first.path("visibleState").path("ownerBrief").path("agentMayReferenceOwnFields").asBoolean());
        assertFalse(first.toString().contains(guest.ownerMessage()));
        ObjectNode opening=proposal(4,16);
        ObjectNode element=json.createObjectNode().put("sourceAgentId",1).put("sourceFieldId","theme")
                .put("placement","SPACE").put("label","Cat wall").putNull("sourceEventId");
        ((ObjectNode)opening.path("payload").path("proposal").path("plan"))
                .set("contributions",json.createArrayNode().add(element));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));
        ObjectNode second=ready(2);
        assertEquals(guest.ownerMessage(),second.path("visibleState").path("ownerBrief").path("ownerMessage").asText());
        assertEquals("save cash",second.path("visibleState").path("ownerBrief").path("hardConstraints").get(0).asText());
        assertEquals(0,second.path("visibleState").path("partnerBrief").path("fields").size());
        assertEquals("Cat wall",second.path("visibleState").path("proposal").path("plan")
                .path("contributions").get(0).path("label").asText());
        assertEquals("theme",second.path("visibleState").path("proposal").path("plan")
                .path("contributions").get(0).path("sourceFieldId").asText());
        assertFalse(second.toString().contains("keep the welcome"));
        assertFalse(second.toString().contains("rain plan"));
        assertFalse(second.toString().contains(host.ownerMessage()));
    }
    @Test void v4RejectsForgedPartnerContributionBeforeRecordingProposal() {
        long id=v4Room();
        ObjectNode first=ready(1);
        ObjectNode opening=proposal(4,16);
        ObjectNode own=json.createObjectNode().put("sourceAgentId",1).put("sourceFieldId","theme")
                .put("placement","SPACE").put("label","Cat wall").putNull("sourceEventId");
        ObjectNode forged=json.createObjectNode().put("sourceAgentId",2).put("sourceFieldId","theme")
                .put("placement","SERVICE").put("label","Invented partner service").putNull("sourceEventId");
        ((ObjectNode)opening.path("payload").path("proposal").path("plan"))
                .set("contributions",json.createArrayNode().add(own).add(forged));
        businessError("FORGED_CONTRIBUTION_SOURCE",()->service.submit(1,first.path("taskId").asLong(),
                submission(first,opening,UUID.randomUUID().toString())));
        assertFalse(service.ownerEvents(1,id,0).stream().anyMatch(event->"PROPOSAL".equals(event.path("kind").asText())));
    }
    void enable(long id) { service.updateParticipation(id,id,new ParticipationUpdate(0,true,6,6,12)); }
    long room(String mode) {
        enable(1); enable(2);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,mode,brief("cats"))).get("activityId").toString());
        service.acceptInvitation(2,id,new InvitationAccept(brief("dogs"))); service.join(1,id); service.join(2,id); return id;
    }
    ObjectNode ready(long actor) {
        long id=Long.parseLong(service.tasks(actor).get(0).get("taskId").toString());
        long version=service.participation(actor,actor).getVersion();
        ObjectNode task=service.claim(actor,id,new Claim(version));
        ObjectNode result=service.startAttempt(actor,id,new AttemptStart(task.get("leaseToken").asText(),version,UUID.randomUUID().toString()));
        if(Set.of("1.3","1.4").contains(result.path("ruleVersion").asText())) {
            ObjectNode sanitized=result.deepCopy();sanitized.put("leaseToken","local-test-redacted");capacityTasks.add(sanitized);
        }
        return result;
    }
    Submission submission(ObjectNode task,JsonNode action,String key) {
        return new Submission(task.get("taskId").asText(),task.get("activityId").asText(),task.get("leaseToken").asText(),
                task.get("permissionVersion").asLong(),task.get("attemptId").asText(),key,action);
    }
    ObjectNode proposal(int quantity,int price) {
        ObjectNode action=json.createObjectNode().put("actionType","PROPOSE_PLAN").put("publicRationale","fixture real-action shape; no model evidence");
        ObjectNode proposal=json.createObjectNode().put("proposalId","plan-1").putNull("parentProposalId");
        ObjectNode plan=json.createObjectNode().put("shopName","Fixture gift shop").put("reserveMinor",0);
        plan.set("products",json.createArrayNode().add(json.createObjectNode().put("sku","STANDARD").put("quantity",quantity).put("priceCoins",price)));
        proposal.set("plan",plan); action.set("payload",json.createObjectNode().set("proposal",proposal)); return action;
    }
    ObjectNode venture() {
        return json.createObjectNode().put("concept","A reading room with handmade gifts")
                .put("audience","Readers and pet lovers")
                .put("experience","A quiet wall and hosted readings")
                .put("marketing","Neighborhood reading postcards");
    }
    void propose(ObjectNode task,int quantity,int price) {
        service.submit(1,task.get("taskId").asLong(),submission(task,proposal(quantity,price),UUID.randomUUID().toString()));
    }
    ObjectNode accept() {
        ObjectNode action=json.createObjectNode().put("actionType","ACCEPT_PLAN").put("publicRationale","accepted fixture terms");
        action.set("payload",json.createObjectNode().put("proposalId","plan-1")); return action;
    }
    void businessError(String expected,org.junit.jupiter.api.function.Executable work) {
        BusinessException error=assertThrows(BusinessException.class,work); assertEquals(expected,error.getMessage());
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    static final String testSigningSecret=UUID.randomUUID().toString()+UUID.randomUUID();
    static com.ai.repo.util.ApiKeyHashUtil keyHasher() {
        var hash=new com.ai.repo.util.ApiKeyHashUtil();
        org.springframework.test.util.ReflectionTestUtils.setField(hash,"secret",testSigningSecret);return hash;
    }
    static com.ai.repo.service.AgentService databaseIdentities() {
        // Delegate only authentication lookups to the actual implementation/SQL, without bootstrapping unrelated platform services.
        var real=new com.ai.repo.service.impl.AgentServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(real,"agentMapper",context.getBean(AgentMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(real,"userMapper",context.getBean(UserMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(real,"apiKeyHashUtil",keyHasher());
        return (com.ai.repo.service.AgentService)java.lang.reflect.Proxy.newProxyInstance(com.ai.repo.service.AgentService.class.getClassLoader(),new Class[]{com.ai.repo.service.AgentService.class},(proxy,method,args)->{
            if(method.getName().equals("findByApiKey") || method.getName().equals("findById")
                    || method.getName().equals("findByUserId")) return method.invoke(real,args);
            if(method.getName().equals("toString")) return "IsolatedDatabaseAuthentication";
            throw new UnsupportedOperationException("Only auth lookups are configured");
        });
    }
    void registerHttpKeys(List<String> keys) {
        for(int i=0;i<keys.size();i++) jdbc.update("UPDATE agents SET api_key_hash=?,challenge_verified=true,last_heartbeat_at=? WHERE id=?",keyHasher().hash(keys.get(i)),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),i+1);
    }
    @Configuration @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class HttpConfig implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Bean PlaygroundService httpService() { return context.getBean(PlaygroundService.class); }
        @Bean com.ai.repo.playground.controller.PlaygroundController controller(PlaygroundService service) {
            return new com.ai.repo.playground.controller.PlaygroundController(service);
        }
        @Bean PlaygroundShareService httpShares() { return context.getBean(PlaygroundShareService.class); }
        @Bean com.ai.repo.playground.controller.PlaygroundShareController shareController(PlaygroundShareService shares) {
            return new com.ai.repo.playground.controller.PlaygroundShareController(shares);
        }
        @Bean com.ai.repo.playground.controller.PlaygroundRequestAdvice requestAdvice() {
            return new com.ai.repo.playground.controller.PlaygroundRequestAdvice();
        }
        @Bean com.ai.repo.exception.GlobalExceptionHandler errors() { return new com.ai.repo.exception.GlobalExceptionHandler(); }
        @Bean ObjectMapper objectMapper() { return json; }
        @Bean com.ai.repo.service.AgentService agentService() { return databaseIdentities(); }
        @Bean com.ai.repo.playground.controller.PlaygroundMatchingController matchingController() {
            return new com.ai.repo.playground.controller.PlaygroundMatchingController(context.getBean(PlaygroundMatchingService.class));
        }
        @Bean com.ai.repo.security.ApiKeyInterceptor apiAuth() {
            com.ai.repo.security.ApiKeyInterceptor auth=new com.ai.repo.security.ApiKeyInterceptor();
            org.springframework.test.util.ReflectionTestUtils.setField(auth,"agentService",agentService());
            org.springframework.test.util.ReflectionTestUtils.setField(auth,"objectMapper",json);
            return auth;
        }
        @Override public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
            registry.addInterceptor(apiAuth());
        }
    }
    @Configuration @EnableAspectJAutoProxy(proxyTargetClass=true)
    static class AdminHttpConfig {
        @Bean com.ai.repo.service.UserService adminUserService() {
            UserMapper mapper=context.getBean(UserMapper.class);
            return (com.ai.repo.service.UserService)java.lang.reflect.Proxy.newProxyInstance(
                    com.ai.repo.service.UserService.class.getClassLoader(),new Class[]{com.ai.repo.service.UserService.class},
                    (proxy,method,args)->{
                        if(method.getName().equals("findById")) return mapper.selectById((Long)args[0]);
                        if(method.getName().equals("toString")) return "IsolatedDatabaseAdminLookup";
                        throw new UnsupportedOperationException("Only admin identity lookup is configured");
                    });
        }
        @Bean com.ai.repo.service.MemoryService adminMemoryService() { return org.mockito.Mockito.mock(com.ai.repo.service.MemoryService.class); }
        @Bean com.ai.repo.service.CommentService adminCommentService() { return org.mockito.Mockito.mock(com.ai.repo.service.CommentService.class); }
        @Bean com.ai.repo.jwt.JwtProvider adminJwtProvider() { return httpJwt; }
        @Bean com.ai.repo.jwt.JwtAuthenticationFilter adminJwtFilter(com.ai.repo.jwt.JwtProvider jwt,
                com.ai.repo.service.AgentService agents) {
            var filter=new com.ai.repo.jwt.JwtAuthenticationFilter();
            org.springframework.test.util.ReflectionTestUtils.setField(filter,"userMapper",context.getBean(UserMapper.class));
            org.springframework.test.util.ReflectionTestUtils.setField(filter,"jwtProvider",jwt);
            org.springframework.test.util.ReflectionTestUtils.setField(filter,"agentService",agents);
            return filter;
        }
        @Bean com.ai.repo.security.PermissionChecker adminPermissions() { return new com.ai.repo.security.PermissionChecker(); }
        @Bean com.ai.repo.playground.controller.PlaygroundAdminController adminController(PlaygroundShareService shares) {
            return new com.ai.repo.playground.controller.PlaygroundAdminController(shares);
        }
    }
    static com.ai.repo.jwt.JwtProvider browserJwt;
    static com.ai.repo.jwt.JwtProvider httpJwt;
    @Configuration
    static class BrowserAccountConfig {
        @Bean com.ai.repo.jwt.JwtProvider browserJwtProvider() { return browserJwt; }
        @Bean UserMapper browserUserMapper() { return context.getBean(UserMapper.class); }
        @Bean com.ai.repo.util.PasswordEncoderUtil browserPasswordEncoder() { return new com.ai.repo.util.PasswordEncoderUtil(); }
        @Bean com.ai.repo.service.TempTokenService browserTempTokens() { return org.mockito.Mockito.mock(com.ai.repo.service.TempTokenService.class); }
        @Bean com.ai.repo.util.ApiKeyUtil browserApiKeyUtil() { return new com.ai.repo.util.ApiKeyUtil(); }
        @Bean com.ai.repo.service.UserService browserUserService(UserMapper mapper,
                com.ai.repo.util.PasswordEncoderUtil passwords,com.ai.repo.jwt.JwtProvider jwt) {
            var service=new com.ai.repo.service.impl.UserServiceImpl();
            org.springframework.test.util.ReflectionTestUtils.setField(service,"userMapper",mapper);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"passwordEncoderUtil",passwords);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"jwtProvider",jwt);
            return service;
        }
        @Bean com.ai.repo.controller.UserController browserUsers() { return new com.ai.repo.controller.UserController(); }
        @Bean com.ai.repo.controller.AgentController browserAgents() { return new com.ai.repo.controller.AgentController(); }
    }
    @EnabledIfEnvironmentVariable(named="PLAYGROUND_OBSERVER_ROOT",matches=".+")
    @Test void pythonAdapterUsesRealHttpAndRecoversCommittedSubmissionWithoutRepeatedInference() throws Exception {
        String observer=System.getenv("PLAYGROUND_OBSERVER_ROOT");
        assertNotNull(observer,"Set PLAYGROUND_OBSERVER_ROOT for cross-repository HTTP acceptance");
        Path driver=Path.of(observer,"playground/adapter/verify_backend_http.py");
        assertTrue(Files.isRegularFile(driver));
        clock.instant=Instant.now();
        long room=room("FULL");
        registerHttpKeys(List.of("http-fixture-key-1","http-fixture-key-2"));
        Path directory=Files.createTempDirectory("playground-http-");
        Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        org.apache.catalina.startup.Tomcat tomcat=new org.apache.catalina.startup.Tomcat();
        tomcat.setBaseDir(directory.resolve("tomcat").toString()); tomcat.setPort(0);
        tomcat.getConnector().setProperty("address","127.0.0.1");
        org.apache.catalina.Context web=tomcat.addContext("",directory.toString());
        org.springframework.web.context.support.AnnotationConfigWebApplicationContext mvc=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        mvc.register(HttpConfig.class);
        org.apache.catalina.Wrapper servlet=org.apache.catalina.startup.Tomcat.addServlet(web,"dispatcher",new org.springframework.web.servlet.DispatcherServlet(mvc));
        servlet.setLoadOnStartup(1); web.addServletMappingDecoded("/","dispatcher");
        try {
            tomcat.start();
            String origin="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();
            java.net.http.HttpClient client=java.net.http.HttpClient.newHttpClient();
            var unauth=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/playground/agent/tasks")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(401,unauth.statusCode());
            var wrongOwner=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/playground/agents/1/participation")).header("agent-auth-api-key","http-fixture-key-1").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(403,wrongOwner.statusCode());
            Path config=directory.resolve("config.json"),output=directory.resolve("evidence.json");
            ObjectNode input=json.createObjectNode().put("origin",origin).put("checkpoints",directory.resolve("checkpoints").toString()).put("output",output.toString());
            input.set("keys",json.createArrayNode().add("http-fixture-key-1").add("http-fixture-key-2"));
            Files.writeString(config,json.writeValueAsString(input));
            Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            ProcessBuilder builder=new ProcessBuilder("python3",driver.toString(),config.toString());
            builder.redirectErrorStream(true); builder.redirectOutput(directory.resolve("driver.log").toFile());
            Process process=builder.start();
            boolean completed=process.waitFor(45,TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertTrue(completed,"HTTP driver timed out");
            assertEquals(0,process.exitValue(),"Python HTTP driver failed; inspect private driver.log");
            ObjectNode evidence=(ObjectNode)json.readTree(Files.readString(output));
            ObjectNode owner=service.ownerActivity(1,room);
            assertEquals("SETTLED",owner.get("status").asText());
            assertEquals(12,owner.get("summary").get("operatedMonths").asInt());
            assertEquals(2,count("playground_attempts")); assertEquals(2,count("playground_actions"));
            assertEquals(0,count("playground_seats"));
            evidence.set("ownerView",owner); evidence.set("events",json.valueToTree(service.ownerEvents(1,room,0)));
            Files.writeString(Path.of("target/playground-http-contract.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));

            // A second game exercises the actual failure route after the first game's seats are released.
            long failedRoom=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",brief("cats"))).get("activityId").toString());
            service.acceptInvitation(2,failedRoom,new InvitationAccept(brief("dogs")));
            service.join(1,failedRoom); service.join(2,failedRoom);
            Path failureOutput=directory.resolve("failure-evidence.json");
            input.put("entryMode","FAILURE").put("checkpoints",directory.resolve("failure-checkpoints").toString())
                    .put("output",failureOutput.toString());
            Files.writeString(config,json.writeValueAsString(input));
            ProcessBuilder failureBuilder=new ProcessBuilder("python3",driver.toString(),config.toString());
            failureBuilder.redirectErrorStream(true); failureBuilder.redirectOutput(directory.resolve("failure-driver.log").toFile());
            Process failureProcess=failureBuilder.start();
            boolean failureCompleted=failureProcess.waitFor(45,TimeUnit.SECONDS);
            if (!failureCompleted) failureProcess.destroyForcibly();
            assertTrue(failureCompleted,"failure HTTP driver timed out");
            assertEquals(0,failureProcess.exitValue(),"failure HTTP driver failed; inspect private failure-driver.log");
            ObjectNode failureEvidence=(ObjectNode)json.readTree(Files.readString(failureOutput));
            assertEquals(1,failureEvidence.get("hostCallbackCalls").asInt());
            assertTrue(failureEvidence.get("failureResponseLossRecovered").asBoolean());
            ObjectNode failedView=service.ownerActivity(1,failedRoom);
            assertEquals("INTERRUPTED",failedView.get("status").asText());
            assertFalse(failedView.has("summary"));
            assertEquals(3,count("playground_attempts")); assertEquals(2,count("playground_actions"));
            assertEquals("FAILED",jdbc.queryForObject("SELECT a.status FROM playground_attempts a JOIN playground_tasks t ON t.id=a.task_id WHERE t.activity_id=?",String.class,failedRoom));
            assertEquals(0,count("playground_seats"));
            List<JsonNode> failedEvents=service.ownerEvents(1,failedRoom,0);
            assertEquals(1,failedEvents.stream().filter(e->e.path("kind").asText().equals("AGENT_FAILURE")).count());
            assertEquals("MODEL_OUTPUT_INVALID",failedEvents.stream().filter(e->e.path("kind").asText().equals("AGENT_FAILURE"))
                    .findFirst().orElseThrow().path("facts").path("reasonCode").asText());
            failureEvidence.set("ownerView",failedView); failureEvidence.set("events",json.valueToTree(failedEvents));
            Files.writeString(Path.of("target/playground-http-failure-contract.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(failureEvidence));

            // The v5 adapter receives a private finite format hint on the fresh task,
            // and a lost failure receipt does not spend another model call or attempt.
            clock.advance(86400); // Existing daily admission limit permits two games per UTC day.
            long v5Room=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",v5Brief("cats"))).get("activityId").toString());
            service.acceptInvitation(2,v5Room,new InvitationAccept(v5Brief("dogs")));
            service.join(1,v5Room); service.join(2,v5Room);
            Path v5Output=directory.resolve("v5-format-retry-evidence.json");
            input.put("entryMode","V5_FORMAT_RETRY").put("checkpoints",directory.resolve("v5-format-checkpoints").toString())
                    .put("output",v5Output.toString());
            Files.writeString(config,json.writeValueAsString(input));
            ProcessBuilder v5Builder=new ProcessBuilder("python3",driver.toString(),config.toString());
            v5Builder.redirectErrorStream(true); v5Builder.redirectOutput(directory.resolve("v5-format-driver.log").toFile());
            Process v5Process=v5Builder.start();
            boolean v5Completed=v5Process.waitFor(45,TimeUnit.SECONDS);
            if (!v5Completed) v5Process.destroyForcibly();
            assertTrue(v5Completed,"v5 format retry HTTP driver timed out");
            assertEquals(0,v5Process.exitValue(),"v5 format retry HTTP driver failed; inspect private driver log");
            ObjectNode v5Evidence=(ObjectNode)json.readTree(Files.readString(v5Output));
            assertEquals(1,v5Evidence.path("invalidHostCallbackCalls").asInt());
            assertEquals(1,v5Evidence.path("correctedHostCallbackCalls").asInt());
            assertTrue(v5Evidence.path("partnerRetryHint").isNull());
            assertEquals("PLANNING",service.ownerActivity(1,v5Room).path("status").asText());
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM playground_attempts a JOIN playground_tasks t ON t.id=a.task_id WHERE t.activity_id=?",Integer.class,v5Room));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM playground_actions a JOIN playground_tasks t ON t.id=a.task_id WHERE t.activity_id=?",Integer.class,v5Room));
            JsonNode v5Failure=service.ownerEvents(1,v5Room,0).stream()
                    .filter(e->"AGENT_FAILURE".equals(e.path("kind").asText())).findFirst().orElseThrow();
            assertEquals("PLAN_CONTRIBUTIONS",v5Failure.path("facts").path("formatHint").asText());
            assertTrue(v5Failure.path("facts").path("retryScheduled").asBoolean());
            Files.writeString(Path.of("target/playground-http-v5-format-retry-contract.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(v5Evidence));
        } finally {
            tomcat.stop(); tomcat.destroy(); mvc.close();
            try (var files=Files.walk(directory)) {
                for (Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
    @EnabledIfEnvironmentVariable(named="PLAYGROUND_AUTH_TEST",matches="true")
    @Test void ownerJwtAndHashedAgentKeysMatchJoinAndSettleThroughRealHttp() throws Exception {
        assertTrue(settings.has("redisPort"),"Dedicated localhost Redis port required");
        clock.instant=Instant.now();
        List<String> keys=List.of(UUID.randomUUID().toString(),UUID.randomUUID().toString()); registerHttpKeys(keys);
        var redisFactory=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("127.0.0.1",settings.get("redisPort").asInt());
        redisFactory.afterPropertiesSet(); redisFactory.start();
        try(var connection=redisFactory.getConnection()){assertEquals(0L,connection.serverCommands().dbSize(),"Dedicated empty Redis required");}
        var redis=new org.springframework.data.redis.core.RedisTemplate<String,Object>(); redis.setConnectionFactory(redisFactory);redis.afterPropertiesSet();
        var jwt=new com.ai.repo.jwt.JwtProvider(redis);
        org.springframework.test.util.ReflectionTestUtils.setField(jwt,"secret",testSigningSecret);
        boolean browserObserve="true".equals(System.getenv("PLAYGROUND_BROWSER_OBSERVE_TEST"));
        boolean browserFixture="true".equals(System.getenv("PLAYGROUND_BROWSER_FIXTURE_TEST"));
        boolean v5Model="5".equals(System.getenv("PLAYGROUND_MODEL_TEST_CONTRACT_VERSION"));
        boolean v5FullModel=v5Model&&"FULL".equals(System.getenv("PLAYGROUND_MODEL_TEST_MODE"));
        boolean annualModel=v5FullModel&&"true".equals(System.getenv("PLAYGROUND_MODEL_TEST_ANNUAL"));
        clock.live=annualModel; // Real model calls must not outlive a frozen 90-second lease.
        org.springframework.test.util.ReflectionTestUtils.setField(jwt,"accessTokenExpiration",v5FullModel?1200000L:browserObserve||browserFixture||v5Model?600000L:60000L); jwt.validateSecret();
        httpJwt=jwt;
        List<String> browserPasswords=browserFixture
                ? List.of(UUID.randomUUID().toString(),UUID.randomUUID().toString()) : List.of();
        if (browserFixture) {
            var encoder=new com.ai.repo.util.PasswordEncoderUtil();
            for (int actor=1;actor<=2;actor++)
                jdbc.update("UPDATE users SET password=? WHERE id=?",encoder.encode(browserPasswords.get(actor-1)),actor);
        }
        List<String> tokens=List.of(jwt.generateAccessToken(1L,"owner1"),jwt.generateAccessToken(2L,"owner2"));
        Path directory=Files.createTempDirectory("playground-auth-http-");
        Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var tomcat=new org.apache.catalina.startup.Tomcat();tomcat.setBaseDir(directory.resolve("tomcat").toString());tomcat.setPort(0);
        tomcat.getConnector().setProperty("address","127.0.0.1");
        var web=tomcat.addContext("",directory.toString());
        boolean securityChain=!browserFixture && "true".equals(System.getenv("PLAYGROUND_SECURITY_CHAIN_TEST"));
        var mvc=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();mvc.register(HttpConfig.class);
        if (!browserFixture) {
            mvc.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "playgroundSecurityJwt",Map.of("jwt.secret",testSigningSecret,
                            "jwt.access-token-expiration",String.valueOf(v5FullModel?1200000L:v5Model?600000L:60000L))));
            mvc.register(AdminHttpConfig.class);
        }
        if (securityChain) mvc.register(com.ai.repo.config.SecurityConfig.class);
        if (browserFixture) {
            browserJwt=jwt;
            mvc.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "playgroundBrowserJwt",Map.of("jwt.secret",testSigningSecret,"jwt.access-token-expiration","600000")));
            mvc.register(BrowserAccountConfig.class);
        }
        var servlet=org.apache.catalina.startup.Tomcat.addServlet(web,"dispatcher",new org.springframework.web.servlet.DispatcherServlet(mvc));servlet.setLoadOnStartup(1);web.addServletMappingDecoded("/","dispatcher");
        var definition=new org.apache.tomcat.util.descriptor.web.FilterDef();
        if (securityChain) {
            definition.setFilterName("springSecurityFilterChain");
            var proxy=new org.springframework.web.filter.DelegatingFilterProxy("springSecurityFilterChain");
            proxy.setContextAttribute(org.springframework.web.servlet.FrameworkServlet.SERVLET_CONTEXT_PREFIX+"dispatcher");
            definition.setFilter(proxy);
        } else {
            var filter=new com.ai.repo.jwt.JwtAuthenticationFilter();
            org.springframework.test.util.ReflectionTestUtils.setField(filter,"userMapper",context.getBean(UserMapper.class));
            org.springframework.test.util.ReflectionTestUtils.setField(filter,"jwtProvider",jwt);
            org.springframework.test.util.ReflectionTestUtils.setField(filter,"agentService",databaseIdentities());
            definition.setFilterName("jwt");definition.setFilter(filter);
        }
        web.addFilterDef(definition);
        var mapping=new org.apache.tomcat.util.descriptor.web.FilterMap();mapping.setFilterName(definition.getFilterName());mapping.addURLPattern("/*");web.addFilterMap(mapping);
        try {
            tomcat.start();String origin="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();
            var client=java.net.http.HttpClient.newHttpClient();
            var forbidden=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/playground/matching/agents/1")).header("agent-auth-api-key",keys.get(0)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(403,forbidden.statusCode());
            var unsigned=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/playground/matching/agents/1")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(401,unsigned.statusCode());
            if (securityChain) {
                var futurePrivate=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        origin+"/api/playground/shares/example/private")).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(401,futurePrivate.statusCode()); // Would be 404 without the actual security chain.
            }
            Path config=directory.resolve("config.json"),output=directory.resolve("evidence.json");
            var input=json.createObjectNode().put("origin",origin).put("checkpoints",directory.resolve("checkpoints").toString()).put("output",output.toString()).put("entryMode","RANDOM_AUTH");
            input.set("keys",json.valueToTree(keys)); input.set("ownerTokens",json.valueToTree(tokens));
            if (browserFixture) input.set("ownerPasswords",json.valueToTree(browserPasswords));
            Files.writeString(config,json.writeValueAsString(input));Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            if (browserFixture && annualModel) {
                long annualId=verifyAnnualRoom(false,false,"true".equals(System.getenv("PLAYGROUND_STORY_TEST")));
                input.put("realAccountShell",true).put("decisionSource","DETERMINISTIC_FIXTURE");
                Files.writeString(config,json.writeValueAsString(input));
                runBrowserObservation(directory,config,annualId,false);
                return; // Deterministic decisions; no model call and no production login chain.
            }
            if (browserFixture) {
                long fixtureId=runBrowserEntry(directory,config);
                JsonNode browserTokens=json.readTree(Files.readString(directory.resolve("browser-login-tokens.json")));
                assertEquals(2,browserTokens.size());
                input.set("ownerTokens",browserTokens); input.remove("ownerPasswords"); input.put("realAccountShell",true);
                Files.writeString(config,json.writeValueAsString(input));
                assertEquals(4,service.ownerActivity(1,fixtureId).path("contractVersion").asInt());
                service.join(1,fixtureId); service.join(2,fixtureId);
                openV4Room(fixtureId,false);
                ObjectNode buyer=ready(2);
                String offerId=buyer.path("visibleState").path("orderOffer").path("offerId").asText();
                service.submit(2,buyer.path("taskId").asLong(),submission(buyer,orderDecision("ACCEPT_ORDER",offerId),UUID.randomUUID().toString()));
                ObjectNode partner=ready(1);
                service.submit(1,partner.path("taskId").asLong(),submission(partner,orderDecision("ACCEPT_ORDER",offerId),UUID.randomUUID().toString()));
                assertEquals("SETTLED",service.ownerActivity(1,fixtureId).path("status").asText());
                runBrowserObservation(directory,config,fixtureId,false);
                if ("true".equals(System.getenv("PLAYGROUND_BROWSER_CLOSING_FIXTURE_TEST"))) {
                    long closingId=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",v4Brief("quiet-books")))
                            .get("activityId").toString());
                    service.acceptInvitation(2,closingId,new InvitationAccept(v4Brief("fast-cafe")));
                    service.join(1,closingId); service.join(2,closingId);
                    ObjectNode firstClosing=ready(1), opening=proposal(4,16);
                    ((ObjectNode)opening.path("payload").path("proposal").path("plan"))
                            .set("contributions",json.createArrayNode().add(json.createObjectNode()
                                    .put("sourceAgentId",1).put("sourceFieldId","theme")
                                    .put("placement","SPACE").put("label","Quiet reading wall").putNull("sourceEventId")));
                    service.submit(1,firstClosing.path("taskId").asLong(),submission(firstClosing,opening,UUID.randomUUID().toString()));
                    ObjectNode secondClosing=ready(2);
                    ObjectNode decline=json.createObjectNode().put("actionType","DECLINE_PLAN")
                            .put("publicRationale","I need a fast cafe, not a quiet bookshop.");
                    decline.set("payload",json.createObjectNode().put("proposalId","plan-1"));
                    service.submit(2,secondClosing.path("taskId").asLong(),submission(secondClosing,decline,UUID.randomUUID().toString()));
                    ObjectNode closingTask=ready(1);
                    ObjectNode note=json.createObjectNode().put("actionType","FINAL_NOTE")
                            .put("publicRationale","I still wanted a quiet place to read and drink tea.");
                    note.set("payload",json.createObjectNode().put("proposalId","plan-1"));
                    service.submit(1,closingTask.path("taskId").asLong(),submission(closingTask,note,UUID.randomUUID().toString()));
                    assertEquals("INTERRUPTED",service.ownerActivity(1,closingId).path("status").asText());
                    runBrowserObservation(directory,config,closingId,true);
                }
                long directedId=runBrowserDirected(directory,config);
                JsonNode hostView=service.ownerActivity(1,directedId),guestView=service.ownerActivity(2,directedId);
                assertEquals("WAITING",hostView.path("status").asText());
                assertEquals("WAITING",guestView.path("status").asText());
                assertEquals(4,hostView.path("contractVersion").asInt());
                assertEquals("paper moon",hostView.path("ownerBrief").path("theme").asText());
                assertEquals("ink lanterns",guestView.path("ownerBrief").path("theme").asText());
                assertEquals("Keep a light on for late visitors",hostView.path("ownerBrief").path("ownerMessage").asText());
                assertEquals("Protect our cash on rainy days",guestView.path("ownerBrief").path("ownerMessage").asText());
                assertFalse(hostView.toString().contains("secret-owner-two"));
                assertFalse(guestView.toString().contains("secret-owner-one"));
                assertTrue(service.tasks(1).isEmpty());
                assertTrue(service.tasks(2).isEmpty());
                return;
            }
            boolean realModel="true".equals(System.getenv("PLAYGROUND_REAL_MODEL_TEST"));
            boolean recoveryMode="true".equals(System.getenv("PLAYGROUND_REAL_MODEL_RECOVERY_TEST"));
            boolean safetyMode=realModel && "true".equals(System.getenv("PLAYGROUND_REAL_MODEL_SAFETY_TEST"));
            boolean closingScenario=realModel && "true".equals(System.getenv("PLAYGROUND_MODEL_TEST_CLOSING_SCENARIO"));
            String driver=realModel ? "playground/adapter/verify_backend_real_model.py" : "playground/adapter/verify_backend_http.py";
            var builder=new ProcessBuilder("python3",Path.of(System.getenv("PLAYGROUND_OBSERVER_ROOT"),driver).toString(),config.toString());builder.redirectErrorStream(true);builder.redirectOutput(directory.resolve("driver.log").toFile());
            Process process=builder.start();boolean done=process.waitFor(realModel ? v5FullModel ? 900 : v5Model ? 420 : 120 : 45,TimeUnit.SECONDS);if(!done)process.destroyForcibly();
            if (realModel && Files.isRegularFile(output)) {
                ObjectNode snapshot=(ObjectNode)json.readTree(Files.readString(output));
                var rooms=service.mine(1,Long.MAX_VALUE);
                if (!rooms.isEmpty()) {
                    long snapshotId=Long.parseLong(rooms.get(0).get("activityId").toString());
                    snapshot.set("ownerView",service.ownerActivity(1,snapshotId));
                    snapshot.set("events",json.valueToTree(service.ownerEvents(1,snapshotId,0)));
                }
                Files.writeString(Path.of("target/playground-real-model-driver-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
            }
            assertTrue(done,"Authenticated HTTP driver timed out");
            if (!safetyMode) assertEquals(0,process.exitValue(),"Private HTTP driver failed");
            long id=service.mine(1,Long.MAX_VALUE).get(0).get("activityId") instanceof String text ? Long.parseLong(text) : 0;
            var owner=service.ownerActivity(1,id);
            if (!realModel) {
                assertEquals("SETTLED",owner.path("status").asText());assertEquals(2,owner.path("summary").path("operatedMonths").asInt());
                assertEquals(2,count("playground_attempts"));assertEquals(2,count("playground_actions"));assertEquals(0,count("playground_seats"));
                clock.advance(86400); // The dedicated share fixture starts on the next UTC admission day.
                long shareableId=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",v4Brief("cats")))
                        .get("activityId").toString());
                service.acceptInvitation(2,shareableId,new InvitationAccept(v4Brief("dogs")));
                service.join(1,shareableId);service.join(2,shareableId);
                openV4Room(shareableId,false);
                ObjectNode orderTask=ready(2);
                String offerId=orderTask.path("visibleState").path("orderOffer").path("offerId").asText();
                service.submit(2,orderTask.path("taskId").asLong(),submission(orderTask,
                        orderDecision("DECLINE_ORDER",offerId),UUID.randomUUID().toString()));
                verifyAdminShareTakedown(origin,client,jwt,tokens.get(0),keys.get(0),shareableId);
            } else {
                if (closingScenario) assertEquals("INTERRUPTED",owner.path("status").asText());
                if (recoveryMode) assertTrue(Set.of("PLANNING","SETTLED","INTERRUPTED").contains(owner.path("status").asText()));
                else if (safetyMode) assertTrue(Set.of("SETTLED","INTERRUPTED").contains(owner.path("status").asText()));
                else assertEquals("SETTLED",owner.path("status").asText());
                if (owner.path("status").asText().equals("SETTLED")) {
                    int months=owner.path("summary").path("operatedMonths").asInt();
                    if (annualModel) assertTrue(months>=1 && months<=12);
                    else assertEquals(v5FullModel?12:2,months);
                }
                if (safetyMode && !closingScenario && owner.path("status").asText().equals("INTERRUPTED")) {
                    assertFalse(owner.has("summary"));
                    long failures=service.ownerEvents(1,id,0).stream().filter(e->e.path("kind").asText().equals("AGENT_FAILURE")).count();
                    assertTrue(failures>=0 && failures<=maxModelCalls());
                    String reason=service.ownerEvents(1,id,0).stream().filter(e->e.path("kind").asText().equals("INTERRUPTED"))
                            .findFirst().orElseThrow().path("facts").path("reason").asText();
                    assertTrue(Set.of("AGENT_DECISION_FAILED","PLAN_DECLINED","AGENT_LEFT","WINDOW_BUDGET_EXHAUSTED").contains(reason));
                    assertEquals(count("playground_actions")+failures,count("playground_attempts"));
                    assertEquals(failures,jdbc.queryForObject("SELECT COUNT(*) FROM playground_attempts WHERE status='FAILED'",Integer.class).longValue());
                } else assertEquals(count("playground_attempts"),count("playground_actions")
                        +jdbc.queryForObject("SELECT COUNT(*) FROM playground_attempts WHERE status='FAILED'",Integer.class));
                assertEquals(owner.path("status").asText().equals("PLANNING") ? 2 : 0,count("playground_seats"));
                if (owner.path("status").asText().equals("SETTLED")) {
                    long returned=owner.path("summary").path("returnedCapitalMinor").asLong();
                    assertEquals(20000L+owner.path("summary").path("netProfitMinor").asLong(),returned);
                    assertEquals(returned,owner.path("summary").path("ownerOneReturnedMinor").asLong()
                            +owner.path("summary").path("ownerTwoReturnedMinor").asLong());
                }
            }
            var evidence=(ObjectNode)json.readTree(Files.readString(output));assertTrue(evidence.path("authenticatedRandomEntry").asBoolean());
            if (realModel) {
                if (v5FullModel) {
                    assertEquals("FULL",evidence.path("mode").asText());
                    assertEquals(12,evidence.path("horizonMonths").asInt());
                }
                if (recoveryMode) {assertTrue(evidence.path("submissionResponseLossRecovered").asBoolean());assertEquals(1,evidence.path("modelCalls").asInt());}
                else if (safetyMode && !closingScenario && owner.path("status").asText().equals("INTERRUPTED")) {
                    assertEquals(1,process.exitValue());
                    long failures=service.ownerEvents(1,id,0).stream().filter(e->e.path("kind").asText().equals("AGENT_FAILURE")).count();
                    String reason=service.ownerEvents(1,id,0).stream().filter(e->e.path("kind").asText().equals("INTERRUPTED"))
                            .findFirst().orElseThrow().path("facts").path("reason").asText();
                    if (reason.equals("AGENT_DECISION_FAILED"))
                        assertTrue(evidence.path("steps").findValuesAsText("status").contains("REPORTED_FAILURE"));
                    else assertEquals(evidence.path("modelCalls").asInt(),count("playground_actions")+failures);
                    int maxCalls=maxModelCalls();
                    assertTrue(evidence.path("modelCalls").asInt()>=1 && evidence.path("modelCalls").asInt()<=maxCalls);
                } else {assertEquals(0,process.exitValue());assertEquals(2,evidence.path("realAgentRuns").asInt());
                    int maxCalls=maxModelCalls();
                    assertTrue(evidence.path("modelCalls").asInt()>=2 && evidence.path("modelCalls").asInt()<=maxCalls);}
                if (closingScenario) {
                    assertTrue(evidence.path("closingVerified").asBoolean());
                    assertEquals("PLAN_DECLINED",service.ownerEvents(1,id,0).stream()
                            .filter(e->e.path("kind").asText().equals("INTERRUPTED"))
                            .findFirst().orElseThrow().path("facts").path("reason").asText());
                    assertEquals(1,service.ownerEvents(1,id,0).stream()
                            .filter(e->e.path("kind").asText().equals("FINAL_NOTE")).count());
                }
                if (owner.path("status").asText().equals("PLANNING")) assertTrue(evidence.path("postRecoveryAgentOneIdle").asBoolean());
                else assertEquals(2,evidence.path("postTerminalEmptyPolls").asInt());
                assertEquals(evidence.path("modelCalls").asInt(),count("playground_attempts"));
            }
            var anonymous=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/playground/matching/agents/1")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(401,anonymous.statusCode());
            String exported=json.writeValueAsString(evidence);
            for(String credential:keys) assertFalse(exported.contains(credential));
            for(String credential:tokens) assertFalse(exported.contains(credential));
            evidence.set("ownerView",owner);evidence.set("events",json.valueToTree(service.ownerEvents(1,id,0)));
            Files.writeString(Path.of(realModel ? (safetyMode ? "target/playground-real-model-safety-contract.json" : recoveryMode ? "target/playground-real-model-recovery-contract.json" : "target/playground-real-model-contract.json") : "target/playground-auth-http-contract.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            if (browserObserve && (owner.path("status").asText().equals("SETTLED") || closingScenario))
                runBrowserObservation(directory,config,id,closingScenario);
        } finally {
            tomcat.stop();tomcat.destroy();mvc.close();redisFactory.destroy();browserJwt=null;httpJwt=null;
            try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
    void verifyAdminShareTakedown(String origin,java.net.http.HttpClient client,
            com.ai.repo.jwt.JwtProvider jwt,String ordinaryToken,String agentKey,long activityId) throws Exception {
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        String token=shares.ownerResultLink(1,activityId).path("sharePath").asText().split("/")[4];
        String publicUrl=origin+"/api/playground/shares/"+token;
        var before=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(publicUrl)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,before.statusCode());
        assertEquals("no-store",before.headers().firstValue("Cache-Control").orElse(""));
        var beforeLanding=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(publicUrl+"/landing")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,beforeLanding.statusCode());
        assertEquals("no-store",beforeLanding.headers().firstValue("Cache-Control").orElse(""));
        String adminUrl=origin+"/api/admin/playground/shares/"+activityId+"/remove";
        String body="{\"reasonCode\":\"PRIVACY\"}";
        var unsigned=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(adminUrl))
                .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(401,unsigned.statusCode());
        var ordinary=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(adminUrl))
                .header("Authorization","Bearer "+ordinaryToken).header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(403,ordinary.statusCode());
        var agent=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(adminUrl))
                .header("agent-auth-api-key",agentKey).header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(403,agent.statusCode());
        jdbc.update("UPDATE users SET role='ADMIN',status='SUSPENDED' WHERE id=3");
        String adminToken=jwt.generateAccessToken(3L,"owner3");
        var suspended=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(adminUrl))
                .header("Authorization","Bearer "+adminToken).header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(403,suspended.statusCode());
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=3");
        var removed=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(adminUrl))
                .header("Authorization","Bearer "+adminToken).header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,removed.statusCode(),removed.body());
        assertEquals("PRIVACY",jdbc.queryForObject("SELECT removed_reason FROM playground_shares WHERE activity_id=?",String.class,activityId));
        assertEquals(3,jdbc.queryForObject("SELECT removed_by_user_id FROM playground_shares WHERE activity_id=?",Integer.class,activityId));
        var after=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(publicUrl)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(404,after.statusCode());
        var landing=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(publicUrl+"/landing")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(404,landing.statusCode());
        businessError("SHARE_REMOVED",()->shares.ownerResultLink(1,activityId));
    }
    int maxModelCalls() {
        return switch (System.getenv().getOrDefault("PLAYGROUND_MODEL_TEST_CONTRACT_VERSION","2")) {
            case "5" -> "true".equals(System.getenv("PLAYGROUND_MODEL_TEST_ANNUAL")) ? 100
                    : "FULL".equals(System.getenv("PLAYGROUND_MODEL_TEST_MODE")) ? 18 : 9;
            case "4" -> 8;
            default -> 4;
        };
    }
    long runBrowserEntry(Path directory,Path config) throws Exception {
        Path frontend=Path.of(System.getenv("PLAYGROUND_FE_ROOT"));
        Path script=frontend.resolve("tests/playground/authenticated-entry.mjs");
        assertTrue(Files.isRegularFile(script),"Browser entry script missing");
        var builder=new ProcessBuilder("node",script.toString(),config.toString());
        builder.directory(frontend.toFile());
        builder.environment().put("VITE_PLAYGROUND_ENABLED","true");
        builder.environment().put("VITE_PLAYGROUND_NPC_V4","true");
        builder.environment().put("VITE_TEST_MODE","false");
        builder.redirectErrorStream(true);
        builder.redirectOutput(directory.resolve("browser-entry.log").toFile());
        Process process=builder.start();
        boolean done=process.waitFor(60,TimeUnit.SECONDS);
        if (!done) process.destroyForcibly();
        copyRedactedBrowserLog(directory.resolve("browser-entry.log"),Path.of("target/playground-v4-browser-entry.log"));
        if (Files.isRegularFile(directory.resolve("browser-entry-evidence.json")))
            Files.copy(directory.resolve("browser-entry-evidence.json"),Path.of("target/playground-v4-browser-entry-evidence.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(done,"Authenticated browser entry timed out");
        assertEquals(0,process.exitValue(),"Authenticated browser entry failed; inspect private browser-entry.log");
        JsonNode evidence=json.readTree(Files.readString(directory.resolve("browser-entry-evidence.json")));
        assertEquals("WAITING",evidence.path("owners").get(0).path("matchingStatus").asText());
        assertEquals("MATCHED",evidence.path("owners").get(1).path("matchingStatus").asText());
        for (int actor=1;actor<=2;actor++) Files.copy(directory.resolve("browser-entry-owner-"+actor+".png"),
                Path.of("target/playground-v4-browser-entry-owner-"+actor+".png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return Long.parseLong(evidence.path("activityId").asText());
    }
    void runBrowserObservation(Path directory,Path config,long id,boolean closing) throws Exception {
        Path frontend=Path.of(System.getenv("PLAYGROUND_FE_ROOT"));
        Path script=frontend.resolve("tests/playground/authenticated-observe.mjs");
        assertTrue(Files.isRegularFile(script),"Browser observation script missing");
        boolean annual="5".equals(System.getenv("PLAYGROUND_MODEL_TEST_CONTRACT_VERSION"))
                && "true".equals(System.getenv("PLAYGROUND_MODEL_TEST_ANNUAL"));
        String browserVersion=annual ? "v6" : "5".equals(System.getenv("PLAYGROUND_MODEL_TEST_CONTRACT_VERSION")) ? "v5" : "v4";
        var browserBuilder=new ProcessBuilder("node",script.toString(),config.toString(),Long.toString(id));
        browserBuilder.directory(frontend.toFile());
        browserBuilder.environment().put("VITE_PLAYGROUND_ENABLED","true");
        browserBuilder.environment().put("VITE_PLAYGROUND_NPC_V4","true");
        if ("5".equals(System.getenv("PLAYGROUND_MODEL_TEST_CONTRACT_VERSION")))
            browserBuilder.environment().put("VITE_PLAYGROUND_V5","true");
        browserBuilder.environment().put("VITE_PLAYGROUND_V6",Boolean.toString(annual));
        browserBuilder.environment().put("VITE_TEST_MODE","false");
        browserBuilder.environment().put("PLAYGROUND_MODEL_TEST_CLOSING_SCENARIO",Boolean.toString(closing));
        browserBuilder.redirectErrorStream(true);
        browserBuilder.redirectOutput(directory.resolve("browser.log").toFile());
        Process browserProcess=browserBuilder.start();
        boolean browserDone=browserProcess.waitFor(120,TimeUnit.SECONDS);
        if (!browserDone) browserProcess.destroyForcibly();
        copyRedactedBrowserLog(directory.resolve("browser.log"),Path.of("target/playground-"+browserVersion+"-browser.log"));
        if (Files.isRegularFile(directory.resolve("browser-evidence.json")))
            Files.copy(directory.resolve("browser-evidence.json"),Path.of("target/playground-"+browserVersion+"-browser-evidence.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(browserDone,"Authenticated browser observation timed out");
        assertEquals(0,browserProcess.exitValue(),"Authenticated browser observation failed; inspect private browser.log");
        JsonNode browserEvidence=json.readTree(Files.readString(directory.resolve("browser-evidence.json")));
        assertEquals(2,browserEvidence.path("owners").size());
        if (annual) {
            for (JsonNode owner:browserEvidence.path("owners")) {
                assertEquals(11,owner.path("monthlyTurns").asInt());
                assertEquals(11,owner.path("resolvedMonthlyTurns").asInt());
                assertEquals(2,owner.path("recordedPartnerReviews").asInt());
                assertTrue(owner.path("publicEnding").path("anonymous").asBoolean());
                assertEquals(12,owner.path("publicEnding").path("months").asInt());
            }
        }
        if (closing) {
            var events=service.ownerEvents(1,id,0);
            String rejection=events.stream().filter(e->e.path("kind").asText().equals("DECISION"))
                    .findFirst().orElseThrow().path("facts").path("action").path("publicRationale").asText();
            String note=events.stream().filter(e->e.path("kind").asText().equals("FINAL_NOTE"))
                    .findFirst().orElseThrow().path("facts").path("action").path("publicRationale").asText();
            for (JsonNode ownerView:browserEvidence.path("owners")) {
                assertTrue(ownerView.path("partingText").asText().contains(rejection));
                assertTrue(ownerView.path("partingText").asText().contains(note));
                assertFalse(ownerView.path("hasSettlement").asBoolean());
            }
        } else {
            String netProfit=String.format(java.util.Locale.US,"%.2f",
                    service.ownerActivity(1,id).path("summary").path("netProfitMinor").asLong()/100.0);
            for (JsonNode ownerView:browserEvidence.path("owners"))
                assertTrue(ownerView.path("settlementText").asText().contains(netProfit));
        }
        Files.writeString(Path.of("target/playground-"+browserVersion+"-browser-evidence.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(browserEvidence));
        for (int actor=1;actor<=2;actor++) {
            Files.copy(directory.resolve("browser-owner-"+actor+".png"),
                    Path.of("target/playground-"+browserVersion+"-browser-owner-"+actor+".png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (annual) Files.copy(directory.resolve("browser-share-"+actor+".png"),
                    Path.of("target/playground-v6-browser-share-"+actor+".png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
    long runBrowserDirected(Path directory,Path config) throws Exception {
        Path frontend=Path.of(System.getenv("PLAYGROUND_FE_ROOT"));
        Path script=frontend.resolve("tests/playground/authenticated-directed.mjs");
        assertTrue(Files.isRegularFile(script),"Browser directed script missing");
        var builder=new ProcessBuilder("node",script.toString(),config.toString());
        builder.directory(frontend.toFile());
        builder.environment().put("VITE_PLAYGROUND_ENABLED","true");
        builder.environment().put("VITE_PLAYGROUND_NPC_V4","true");
        builder.environment().put("VITE_TEST_MODE","false");
        builder.redirectErrorStream(true);
        builder.redirectOutput(directory.resolve("browser-directed.log").toFile());
        Process process=builder.start();
        boolean done=process.waitFor(60,TimeUnit.SECONDS);
        if (!done) process.destroyForcibly();
        copyRedactedBrowserLog(directory.resolve("browser-directed.log"),Path.of("target/playground-v4-browser-directed.log"));
        if (Files.isRegularFile(directory.resolve("browser-directed-evidence.json")))
            Files.copy(directory.resolve("browser-directed-evidence.json"),Path.of("target/playground-v4-browser-directed-evidence.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(done,"Authenticated browser directed invitation timed out");
        assertEquals(0,process.exitValue(),"Authenticated browser directed invitation failed; inspect private browser-directed.log");
        JsonNode evidence=json.readTree(Files.readString(directory.resolve("browser-directed-evidence.json")));
        assertEquals(2,evidence.path("owners").size());
        for (int actor=1;actor<=2;actor++) {
            Files.copy(directory.resolve("browser-directed-owner-"+actor+".png"),
                    Path.of("target/playground-v4-browser-directed-owner-"+actor+".png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(directory.resolve("browser-directed-compose-owner-"+actor+".png"),
                    Path.of("target/playground-v4-browser-directed-compose-owner-"+actor+".png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return evidence.path("activityId").asLong();
    }
    void copyRedactedBrowserLog(Path source,Path target) throws Exception {
        Files.writeString(target,Files.readString(source)
                .replaceAll("(?i)Bearer [A-Za-z0-9._~+/-]+", "Bearer [REDACTED]"));
    }
    @Test void defaultsOffAndOwnershipAndOptimisticVersionAreEnforced() {
        assertFalse(service.participation(1,1).isEnabled());
        assertEquals(40,service.participation(1,1).getMaxDecisions());
        assertEquals(40,service.participation(1,1).getMaxAttempts());
        assertEquals(40,service.participation(1,1).getMaxDailyAttempts());
        assertEquals(0,service.participation(1,1).getVersion());
        businessError("NOT_AGENT_OWNER",()->service.updateParticipation(2,1,new ParticipationUpdate(0,true,4,4,8)));
        enable(1);
        businessError("PERMISSION_VERSION_CONFLICT",()->service.updateParticipation(1,1,new ParticipationUpdate(0,true,4,4,8)));
    }
    @Test void neitherRegistrationNorOwnerInvitationAutomaticallyStartsAgents() {
        enable(1); enable(2);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",brief("cats"))).get("activityId").toString());
        businessError("OWNERS_NOT_CONFIRMED",()->service.join(2,id));
        assertEquals(0,count("playground_tasks"));
        assertFalse(service.ownerActivity(1,id).get("canAcceptInvitation").asBoolean());
        assertTrue(service.ownerActivity(2,id).get("canAcceptInvitation").asBoolean());
        assertEquals("2",service.ownerActivity(2,id).get("guestAgentId").asText());
        assertEquals("2",service.ownerActivity(2,id).get("viewerAgentId").asText());
        businessError("NOT_AGENT_OWNER",()->service.acceptInvitation(1,id,new InvitationAccept(brief("dogs"))));
        service.acceptInvitation(2,id,new InvitationAccept(brief("dogs"))); service.join(1,id);
        assertEquals(0,count("playground_tasks")); service.join(2,id); assertEquals(1,count("playground_tasks"));
        service.join(2,id); assertEquals(1,count("playground_tasks"));
    }
    @Test void pollingIsReadOnlyAndClaimDoesNotReserveInference() {
        room("SHORT"); for(int i=0;i<10;i++) assertEquals(1,service.tasks(1).size());
        assertEquals(0,count("playground_attempts")); assertEquals(0,jdbc.queryForObject("SELECT COALESCE(SUM(attempts_used),0) FROM playground_daily_budgets",Integer.class));
        long taskId=Long.parseLong(service.tasks(1).get(0).get("taskId").toString());
        ObjectNode task=service.claim(1,taskId,new Claim(1)); assertTrue(task.get("attemptId").isNull());
        assertEquals(0,store.seat(1).getAttemptsUsed());
    }
    @Test void concurrentClaimsIssueExactlyOneLease() throws Exception {
        room("SHORT"); long taskId=Long.parseLong(service.tasks(1).get(0).get("taskId").toString());
        ExecutorService executor=Executors.newFixedThreadPool(2); CountDownLatch go=new CountDownLatch(1);
        try {
            Callable<String> claim=()->{ go.await(); try { service.claim(1,taskId,new Claim(1)); return "LEASED"; }
                catch(BusinessException e) { return e.getMessage(); } };
            Future<String> a=executor.submit(claim),b=executor.submit(claim); go.countDown();
            assertEquals(Set.of("LEASED","TASK_ALREADY_LEASED"),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));
        } finally { executor.shutdownNow(); }
    }
    @Test void attemptReservationIsIdempotentAndUnknownAttemptCannotBeRepeated() {
        room("SHORT"); long taskId=Long.parseLong(service.tasks(1).get(0).get("taskId").toString());
        ObjectNode claim=service.claim(1,taskId,new Claim(1)); String key=UUID.randomUUID().toString();
        AttemptStart start=new AttemptStart(claim.get("leaseToken").asText(),1,key);
        ObjectNode first=service.startAttempt(1,taskId,start), second=service.startAttempt(1,taskId,start);
        assertEquals(first.get("attemptId"),second.get("attemptId")); assertEquals(1,count("playground_attempts"));
        assertEquals(1,store.seat(1).getAttemptsUsed());
        businessError("ATTEMPT_OUTCOME_UNKNOWN",()->service.startAttempt(1,taskId,new AttemptStart(start.leaseToken(),1,UUID.randomUUID().toString())));
    }
    @Test void definiteAdapterFailureEndsGameOnceWithoutRefundingAttempt() {
        long roomId=room("SHORT"); ObjectNode task=ready(1); long taskId=task.get("taskId").asLong();
        AttemptFailure failure=new AttemptFailure(task.get("leaseToken").asText(),1,
                task.get("attemptId").asText(),"MODEL_OUTPUT_INVALID");
        businessError("NOT_TASK_ACTOR",()->service.reportAttemptFailure(2,taskId,failure));
        businessError("ATTEMPT_REQUIRED",()->service.reportAttemptFailure(1,taskId,
                new AttemptFailure("wrong-lease-token-123456",1,failure.attemptId(),failure.reasonCode())));
        assertEquals("INTERRUPTED",service.reportAttemptFailure(1,taskId,failure).get("status").asText());
        businessError("IDEMPOTENCY_CONFLICT",()->service.reportAttemptFailure(1,taskId,
                new AttemptFailure(failure.leaseToken(),1,failure.attemptId(),"MODEL_REFUSED")));
        assertEquals("FAILED",store.attempt(task.get("attemptId").asLong()).getStatus());
        assertEquals(1,count("playground_attempts")); assertEquals(0,count("playground_actions"));
        assertEquals(0,count("playground_seats")); assertTrue(service.tasks(1).isEmpty());
        long failures=service.ownerEvents(1,roomId,0).stream().filter(e->e.path("kind").asText().equals("AGENT_FAILURE")).count();
        assertEquals(1,failures);
        assertEquals("MODEL_OUTPUT_INVALID",service.ownerEvents(1,roomId,0).stream()
                .filter(e->e.path("kind").asText().equals("AGENT_FAILURE")).findFirst().orElseThrow()
                .path("facts").path("reasonCode").asText());
        assertEquals("INTERRUPTED",service.reportAttemptFailure(1,taskId,failure).get("status").asText());
        assertEquals(failures,service.ownerEvents(1,roomId,0).stream()
                .filter(e->e.path("kind").asText().equals("AGENT_FAILURE")).count());
        assertEquals(1,jdbc.queryForObject("SELECT attempts_used FROM playground_daily_budgets WHERE agent_id=1",Integer.class));
    }
    @Test void agentDeclineAppearsBeforeSystemInterruptionInOwnerStory() {
        long roomId=room("SHORT");
        propose(ready(1),4,16);
        ObjectNode task=ready(2);
        ObjectNode decline=json.createObjectNode().put("actionType","DECLINE_PLAN")
                .put("publicRationale","I do not agree with this plan");
        decline.set("payload",json.createObjectNode().put("proposalId","plan-1"));
        service.submit(2,task.get("taskId").asLong(),submission(task,decline,UUID.randomUUID().toString()));
        List<JsonNode> events=service.ownerEvents(1,roomId,0);
        assertEquals("DECISION",events.get(events.size()-2).path("kind").asText());
        assertEquals("USER_AGENT",events.get(events.size()-2).path("actorSource").asText());
        assertEquals("INTERRUPTED",events.get(events.size()-2).path("semanticStatus").asText());
        assertEquals("I do not agree with this plan",events.get(events.size()-2).path("facts").path("action").path("publicRationale").asText());
        assertEquals("INTERRUPTED",events.get(events.size()-1).path("kind").asText());
        assertEquals("PLAN_DECLINED",events.get(events.size()-1).path("facts").path("reason").asText());
        assertFalse(service.ownerActivity(1,roomId).has("summary"));
    }
    @Test void unchangedCounterCannotConsumeAStoryTurn() {
        long roomId=room("SHORT");
        propose(ready(1),4,16);
        ObjectNode task=ready(2);
        ObjectNode counter=proposal(4,16);
        counter.put("actionType","COUNTER_PLAN");
        ObjectNode body=(ObjectNode)counter.path("payload").path("proposal");
        body.put("proposalId","plan-2"); body.put("parentProposalId","plan-1");
        businessError("UNCHANGED_PLAN",()->service.submit(2,task.get("taskId").asLong(),
                submission(task,counter,UUID.randomUUID().toString())));
        assertEquals(1,count("playground_actions"));
        assertEquals("PLANNING",service.ownerActivity(1,roomId).path("status").asText());
        assertEquals(0,service.ownerEvents(1,roomId,0).stream()
                .filter(e->e.path("kind").asText().equals("AGENT_FAILURE")).count());
        ((ObjectNode)body.path("plan")).put("shopName","Different shop name");
        service.submit(2,task.get("taskId").asLong(),submission(task,counter,UUID.randomUUID().toString()));
        assertEquals(2,count("playground_actions"));
    }
    @Test void exactDuplicateSubmissionsSurviveConcurrentRequestsWithoutDoubleEvents() throws Exception {
        room("SHORT"); ObjectNode task=ready(1); Submission request=submission(task,proposal(4,16),UUID.randomUUID().toString());
        long taskId=task.get("taskId").asLong(); ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> a=executor.submit(()->service.submit(1,taskId,request)),b=executor.submit(()->service.submit(1,taskId,request));
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
        assertEquals(1,count("playground_actions")); assertEquals(1,store.seat(1).getDecisionsUsed());
        assertEquals(1,service.tasks(2).size());
        ObjectNode changed=proposal(4,20);
        businessError("IDEMPOTENCY_CONFLICT",()->service.submit(1,taskId,submission(task,changed,request.idempotencyKey())));
    }
    @Test void pauseRevokesLeaseAndResumeKeepsBudgetAndDeadline() {
        room("SHORT"); ObjectNode task=ready(1); long taskId=task.get("taskId").asLong();
        service.updateParticipation(1,1,new ParticipationUpdate(1,false,6,6,12));
        businessError("PARTICIPATION_DISABLED",()->service.submit(1,taskId,submission(task,proposal(4,16),UUID.randomUUID().toString())));
        assertTrue(service.tasks(1).isEmpty());
        service.updateParticipation(1,1,new ParticipationUpdate(2,true,6,6,12));
        assertEquals(1,store.seat(1).getAttemptsUsed()); assertEquals(1,count("playground_attempts"));
        businessError("PERMISSION_VERSION_CONFLICT",()->service.submit(1,taskId,submission(task,proposal(4,16),UUID.randomUUID().toString())));
        ObjectNode next=service.claim(1,taskId,new Claim(3)); assertNull(store.task(taskId).getAttemptId());
        assertNotEquals(task.get("leaseToken"),next.get("leaseToken"));
    }
    @Test void staleLeaseAndCrossAgentSubmissionsAreRejected() {
        room("SHORT"); ObjectNode task=ready(1); long taskId=task.get("taskId").asLong();
        businessError("NOT_TASK_ACTOR",()->service.submit(2,taskId,submission(task,proposal(4,16),UUID.randomUUID().toString())));
        clock.advance(91); service.claim(1,taskId,new Claim(1));
        businessError("LEASE_LOST",()->service.submit(1,taskId,submission(task,proposal(4,16),UUID.randomUUID().toString())));
        assertEquals("UNKNOWN",store.attempt(task.get("attemptId").asLong()).getStatus());
    }
    @Test void dailyBudgetStopsUnknownOutcomeRetryEvenWithRoomBudgetRemaining() {
        long id=room("SHORT");
        service.updateParticipation(1,1,new ParticipationUpdate(1,true,6,6,1));
        ObjectNode task=ready(1); clock.advance(91); long taskId=task.get("taskId").asLong();
        ObjectNode claim=service.claim(1,taskId,new Claim(2));
        businessError("DAILY_BUDGET_EXHAUSTED",()->service.startAttempt(1,taskId,new AttemptStart(claim.get("leaseToken").asText(),2,UUID.randomUUID().toString())));
        assertEquals(1,count("playground_attempts")); assertEquals("PLANNING",service.ownerActivity(1,id).get("status").asText());
    }
    @Test void completeYearAndPrivateBriefsAreNotLeakedToOtherAgentOrOwner() {
        long id=room("FULL"); ObjectNode host=ready(1);
        assertFalse(host.toString().contains("dogs")); assertFalse(service.ownerActivity(2,id).toString().contains("private:cats"));
        propose(host,4,16); ObjectNode guest=ready(2); assertFalse(guest.toString().contains("private:cats"));
        Submission request=submission(guest,accept(),UUID.randomUUID().toString());
        JsonNode response=service.submit(2,guest.get("taskId").asLong(),request);
        assertEquals("SETTLED",response.get("status").asText());
        JsonNode view=service.ownerActivity(1,id); assertEquals(12,view.get("summary").get("operatedMonths").asInt());
        assertEquals(40_000,view.get("summary").get("returnedCapitalMinor").asLong());
        assertEquals(12,view.get("game").get("reports").size()); assertEquals(0,count("playground_seats"));
        assertEquals(response,service.submit(2,guest.get("taskId").asLong(),request));
        List<JsonNode> events=service.ownerEvents(1,id,0);
        assertEquals(12,events.stream().filter(e->e.get("kind").asText().equals("MONTH_REPORT")).count());
        businessError("NOT_ACTIVITY_OWNER",()->service.ownerEvents(3,id,0));
        ObjectNode exported=json.createObjectNode().put("fixtureSource","SERVER_INTEGRATION_TEST_NOT_REAL_AGENT");
        exported.set("task",guest.deepCopy().put("leaseToken","fixture-expired-lease-for-contract-validation"));
        ObjectNode exportedSubmission=json.valueToTree(request);
        exportedSubmission.put("leaseToken","fixture-expired-lease-for-contract-validation");
        exported.set("submission",exportedSubmission); exported.set("events",json.valueToTree(events));
        exported.set("ownerView",view);
        try { Files.writeString(Path.of("target/playground-server-contract.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(exported)); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @Test void earlyFailureHasEightReportsAndNoFakeNinthMonth() {
        long id=room("FULL"); propose(ready(1),0,16); ObjectNode guest=ready(2);
        service.submit(2,guest.get("taskId").asLong(),submission(guest,accept(),UUID.randomUUID().toString()));
        JsonNode summary=service.ownerActivity(1,id).get("summary");
        assertEquals("BUSINESS_FAILURE",summary.get("ending").asText()); assertEquals(8,summary.get("operatedMonths").asInt());
        assertEquals(9,summary.get("failedOpeningMonth").asInt()); assertEquals(8,summary.get("reports").size());
        assertEquals(8,service.ownerEvents(1,id,0).stream().filter(e->e.get("kind").asText().equals("MONTH_REPORT")).count());
    }
    @Test void restartRestoresClaimedTaskAttemptAndIdempotencyReceipt() {
        room("SHORT"); ObjectNode task=ready(1); long taskId=task.get("taskId").asLong();
        Submission request=submission(task,proposal(4,16),UUID.randomUUID().toString());
        context.close(); context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        matching=context.getBean(PlaygroundMatchingService.class); service=context.getBean(PlaygroundService.class); store=context.getBean(PlaygroundMapper.class);
        JsonNode receipt=service.submit(1,taskId,request);
        context.close(); context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        matching=context.getBean(PlaygroundMatchingService.class); service=context.getBean(PlaygroundService.class); store=context.getBean(PlaygroundMapper.class);
        assertEquals(receipt,service.submit(1,taskId,request)); assertEquals(1,count("playground_actions"));
        assertEquals(1,store.seat(1).getAttemptsUsed());
    }
    @Test void v4OfferWindowSurvivesTwoServiceRestartsAndSettlesOnce() {
        long id=openedV4Room();
        ObjectNode first=ready(2);
        String offerId=first.path("visibleState").path("orderOffer").path("offerId").asText();
        assertFalse(offerId.isBlank());
        Submission firstDecision=submission(first,orderDecision("ACCEPT_ORDER",offerId),UUID.randomUUID().toString());

        // Restart after a leased and budgeted task, before its action is submitted.
        context.close(); context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        service=context.getBean(PlaygroundService.class); store=context.getBean(PlaygroundMapper.class);
        assertEquals(1,service.ownerActivity(1,id).path("game").path("operatedMonths").asInt());
        assertEquals("OFFER_NEGOTIATION",store.task(first.path("taskId").asLong()).getPhase());
        assertEquals("PLANNING",service.submit(2,first.path("taskId").asLong(),firstDecision).path("status").asText());
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->"NPC_OFFER".equals(e.path("kind").asText())).count());

        // Restart again with one decision persisted and the partner's task pending.
        context.close(); context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        service=context.getBean(PlaygroundService.class); store=context.getBean(PlaygroundMapper.class);
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->"NPC_ORDER_DECISION".equals(e.path("kind").asText())).count());
        ObjectNode second=ready(1);
        assertEquals("OFFER_NEGOTIATION",second.path("phase").asText());
        assertEquals(offerId,second.path("visibleState").path("orderOffer").path("offerId").asText());
        Submission secondDecision=submission(second,orderDecision("ACCEPT_ORDER",offerId),UUID.randomUUID().toString());
        JsonNode receipt=service.submit(1,second.path("taskId").asLong(),secondDecision);
        assertEquals("SETTLED",receipt.path("status").asText());
        assertEquals(receipt,service.submit(1,second.path("taskId").asLong(),secondDecision));
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->"NPC_OFFER".equals(e.path("kind").asText())).count());
        assertEquals(2,service.ownerEvents(1,id,0).stream().filter(e->"NPC_ORDER_DECISION".equals(e.path("kind").asText())).count());
        assertEquals(2,service.ownerEvents(1,id,0).stream().filter(e->"MONTH_REPORT".equals(e.path("kind").asText())).count());
        assertTrue(service.ownerActivity(2,id).path("summary").path("reports").get(1).path("events").toString().contains("NPC_ORDER_FULFILLED"));
    }
    @Test void v4DeclinedPlanWaitsForOneRealFinalNoteAndNeverOpens() {
        long id=v4Room(); ObjectNode first=ready(1);
        ObjectNode opening=proposal(4,16);
        ((ObjectNode)opening.path("payload").path("proposal").path("plan"))
                .set("contributions",json.createArrayNode().add(json.createObjectNode()
                        .put("sourceAgentId",1).put("sourceFieldId","theme").put("placement","SPACE")
                        .put("label","Quiet reading wall").putNull("sourceEventId")));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));
        ObjectNode second=ready(2);
        ObjectNode decline=json.createObjectNode().put("actionType","DECLINE_PLAN")
                .put("publicRationale","I cannot accept the cost of this plan.");
        decline.set("payload",json.createObjectNode().put("proposalId","plan-1"));
        assertEquals("PLANNING",service.submit(2,second.path("taskId").asLong(),
                submission(second,decline,UUID.randomUUID().toString())).path("status").asText());
        assertEquals(0,service.ownerActivity(1,id).path("game").path("operatedMonths").asInt());
        context.close(); context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        service=context.getBean(PlaygroundService.class); store=context.getBean(PlaygroundMapper.class);
        ObjectNode closing=ready(1); assertEquals("CLOSING",closing.path("phase").asText());
        assertEquals("FINAL_NOTE",closing.path("allowedActions").get(0).asText());
        ObjectNode note=json.createObjectNode().put("actionType","FINAL_NOTE")
                .put("publicRationale","I still wanted a quiet reading space.");
        note.set("payload",json.createObjectNode().put("proposalId","plan-1"));
        Submission request=submission(closing,note,UUID.randomUUID().toString());
        JsonNode receipt=service.submit(1,closing.path("taskId").asLong(),request);
        assertEquals("INTERRUPTED",receipt.path("status").asText());
        assertEquals(receipt,service.submit(1,closing.path("taskId").asLong(),request));
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->"FINAL_NOTE".equals(e.path("kind").asText())).count());
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->"INTERRUPTED".equals(e.path("kind").asText())
                && "PLAN_DECLINED".equals(e.path("facts").path("reason").asText())).count());
        assertEquals(0,count("playground_seats"));
        assertFalse(service.ownerActivity(1,id).has("summary"));
    }
    @Test void terminalEndingAutomaticallyHasOneAnonymousSafeLinkForBothOwners() throws Exception {
        long id=v4Room(); ObjectNode first=ready(1), opening=proposal(4,16);
        ObjectNode openingPlan=(ObjectNode)opening.path("payload").path("proposal").path("plan");
        openingPlan.put("shopName","private:cats test@example.com shop");
        openingPlan.set("venture",venture().put("concept","A store for private:dogs"));
        ((ObjectNode)opening.path("payload").path("proposal").path("plan"))
                .set("contributions",json.createArrayNode().add(json.createObjectNode()
                        .put("sourceAgentId",1).put("sourceFieldId","theme").put("placement","SPACE")
                        .put("label","Quiet reading wall").putNull("sourceEventId")));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));
        ObjectNode second=ready(2);
        ObjectNode decline=json.createObjectNode().put("actionType","DECLINE_PLAN")
                .put("publicRationale","I want a faster shop.");
        decline.set("payload",json.createObjectNode().put("proposalId","plan-1"));
        service.submit(2,second.path("taskId").asLong(),submission(second,decline,UUID.randomUUID().toString()));
        ObjectNode closing=ready(1);
        ObjectNode note=json.createObjectNode().put("actionType","FINAL_NOTE")
                .put("publicRationale","I wanted a reading room.");
        note.set("payload",json.createObjectNode().put("proposalId","plan-1"));
        service.submit(1,closing.path("taskId").asLong(),submission(closing,note,UUID.randomUUID().toString()));
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        ObjectNode published=shares.ownerResultLink(1,id);
        String publicText=published.path("result").toString();
        assertFalse(publicText.contains("ownerBrief"));
        assertFalse(publicText.contains("private:cats"));
        assertFalse(publicText.contains("private:dogs"));
        assertFalse(publicText.contains("test@example.com"));
        assertFalse(publicText.contains("I want a faster shop."));
        assertFalse(publicText.contains("I wanted a reading room."));
        assertEquals("DECLINE_PLAN",published.path("result").path("agentMoves").get(1).path("move").asText());
        businessError("NOT_ACTIVITY_OWNER",()->shares.ownerResultLink(3,id));
        assertEquals(published.path("sharePath").asText(),shares.ownerResultLink(2,id).path("sharePath").asText());
        assertEquals(1,count("playground_shares"));
        String firstToken=published.path("sharePath").asText().split("/")[4];
        JsonNode result=shares.publicResult(firstToken);
        assertEquals("UNOPENED",result.path("outcome").asText());
        assertEquals(3,result.path("shareSchemaVersion").asInt());
        assertFalse(result.has("words"));
        assertEquals("FINAL_NOTE",result.path("agentMoves").get(2).path("move").asText());
        var landing=new com.ai.repo.playground.controller.PlaygroundShareController(shares).landing(firstToken);
        assertTrue(landing.getBody().contains("<meta property=\"og:title\""));
        assertTrue(landing.getBody().contains("/playground/s/"+firstToken));
        assertFalse(landing.getBody().contains("private:cats"));
        assertTrue(landing.getHeaders().getCacheControl().contains("no-store"));
        var publicHttp=org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.ai.repo.playground.controller.PlaygroundShareController(shares)).build();
        var response=publicHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/playground/shares/"+firstToken)).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        assertEquals("no-store",response.getHeader("Cache-Control"));
        assertEquals("UNOPENED",json.readTree(response.getContentAsString()).path("data").path("outcome").asText());
        ObjectNode legacy=(ObjectNode)result.deepCopy();
        legacy.put("shareSchemaVersion",1);legacy.putArray("words").addObject().put("text","private:cats");
        jdbc.update("UPDATE playground_shares SET payload_json=? WHERE activity_id=?",legacy.toString(),id);
        businessError("SHARE_NOT_FOUND",()->shares.publicResult(firstToken));
        ObjectNode renewed=shares.ownerResultLink(1,id);
        assertNotEquals(firstToken,renewed.path("sharePath").asText().split("/")[4]);
        assertFalse(renewed.toString().contains("private:cats"));
        businessError("SHARE_NOT_FOUND",()->shares.publicResult(firstToken));
    }
    @Test void settledShareShowsAgreedVentureAndOutcomeWithoutPrivateBrief() {
        long id=openedV4Room(); ObjectNode task=ready(2);
        String offerId=task.path("visibleState").path("orderOffer").path("offerId").asText();
        service.submit(2,task.path("taskId").asLong(),submission(task,
                orderDecision("DECLINE_ORDER",offerId),UUID.randomUUID().toString()));
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        ObjectNode link=shares.ownerResultLink(1,id);
        JsonNode publicView=link.path("result");
        assertEquals("OPERATED",publicView.path("outcome").asText());
        assertEquals("Cat and dog shop",publicView.path("shop").path("name").asText());
        assertTrue(publicView.path("shop").has("audience"));
        assertTrue(publicView.path("shop").has("marketing"));
        assertEquals(2,publicView.path("business").path("months").size());
        assertFalse(publicView.toString().contains("ownerBrief"));
        assertFalse(publicView.toString().contains("private:cats"));
        assertFalse(publicView.toString().contains("private:dogs"));
        String token=link.path("sharePath").asText().split("/")[4];
        assertEquals(publicView.toString(),shares.publicResult(token).toString());
    }
    @Test void adminTakedownPermanentlySuppressesPublishedResultAndPreservesAudit() {
        long id=openedV4Room(); ObjectNode task=ready(2);
        String offerId=task.path("visibleState").path("orderOffer").path("offerId").asText();
        service.submit(2,task.path("taskId").asLong(),submission(task,
                orderDecision("DECLINE_ORDER",offerId),UUID.randomUUID().toString()));
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        String token=shares.ownerResultLink(1,id).path("sharePath").asText().split("/")[4];
        assertEquals("OPERATED",shares.publicResult(token).path("outcome").asText());
        businessError("INVALID_TAKEDOWN_REASON",()->shares.removePublishedResult(3,id,"FREE_TEXT"));
        businessError("AUTHENTICATION_REQUIRED",()->shares.removePublishedResult(0,id,"PRIVACY"));
        shares.removePublishedResult(3,id,"PRIVACY");
        shares.removePublishedResult(2,id,"OTHER"); // Repeat does not erase the first audit decision.
        assertEquals(1,count("playground_shares"));
        assertEquals("PRIVACY",jdbc.queryForObject("SELECT removed_reason FROM playground_shares WHERE activity_id=?",String.class,id));
        assertEquals(3,jdbc.queryForObject("SELECT removed_by_user_id FROM playground_shares WHERE activity_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM playground_shares WHERE activity_id=? AND removed_at IS NOT NULL",Integer.class,id));
        businessError("SHARE_NOT_FOUND",()->shares.publicResult(token));
        businessError("SHARE_REMOVED",()->shares.ownerResultLink(1,id));
        businessError("SHARE_REMOVED",()->shares.ownerResultLink(2,id));
        businessError("SHARE_NOT_FOUND",()->new com.ai.repo.playground.controller.PlaygroundShareController(shares).landing(token));
    }
    @Test void adminTakedownBeforeFirstViewPreventsLazyShareCreation() {
        long id=openedV4Room();
        PlaygroundShareService shares=context.getBean(PlaygroundShareService.class);
        assertEquals(0,count("playground_shares"));
        PlaygroundShareService disabled=new PlaygroundShareService(store,service,json,false,clock);
        disabled.removePublishedResult(3,id,"ABUSE");
        assertEquals(1,count("playground_shares"));
        assertEquals("ABUSE",jdbc.queryForObject("SELECT removed_reason FROM playground_shares WHERE activity_id=?",String.class,id));
        ObjectNode task=ready(2);
        String offerId=task.path("visibleState").path("orderOffer").path("offerId").asText();
        service.submit(2,task.path("taskId").asLong(),submission(task,
                orderDecision("DECLINE_ORDER",offerId),UUID.randomUUID().toString()));
        assertEquals("SETTLED",service.ownerActivity(1,id).path("status").asText());
        businessError("SHARE_REMOVED",()->shares.ownerResultLink(1,id));
        assertEquals(1,count("playground_shares"));
    }
    @Test void v4ClosingTimeoutKeepsPlanDeclinedWithoutInventingNote() {
        long id=v4Room(); ObjectNode first=ready(1); ObjectNode opening=proposal(4,16);
        ((ObjectNode)opening.path("payload").path("proposal").path("plan"))
                .set("contributions",json.createArrayNode().add(json.createObjectNode()
                        .put("sourceAgentId",1).put("sourceFieldId","theme").put("placement","SPACE")
                        .put("label","Quiet reading wall").putNull("sourceEventId")));
        service.submit(1,first.path("taskId").asLong(),submission(first,opening,UUID.randomUUID().toString()));
        ObjectNode second=ready(2); ObjectNode decline=json.createObjectNode().put("actionType","DECLINE_PLAN")
                .put("publicRationale","I want a smaller venture.");
        decline.set("payload",json.createObjectNode().put("proposalId","plan-1"));
        service.submit(2,second.path("taskId").asLong(),submission(second,decline,UUID.randomUUID().toString()));
        clock.advance(901); service.expire(id); service.expire(id);
        assertEquals("INTERRUPTED",service.ownerActivity(2,id).path("status").asText());
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->"CLOSING_MISSED".equals(e.path("kind").asText())).count());
        assertFalse(service.ownerEvents(1,id,0).stream().anyMatch(e->"FINAL_NOTE".equals(e.path("kind").asText())));
    }
    @Test void databaseFailureRollsBackAgreementEventsLedgerAndDecisionBudget() {
        long id=room("SHORT"); propose(ready(1),4,16); ObjectNode guest=ready(2);
        Submission request=submission(guest,accept(),UUID.randomUUID().toString()); int before=count("playground_events");
        jdbc.execute("CREATE TRIGGER playground_test_failure BEFORE INSERT ON playground_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='intentional fixture failure'");
        try { assertThrows(RuntimeException.class,()->service.submit(2,guest.get("taskId").asLong(),request)); }
        finally { jdbc.execute("DROP TRIGGER playground_test_failure"); }
        assertEquals(before,count("playground_events")); assertEquals(0,store.seat(2).getDecisionsUsed());
        assertEquals("PLANNING",service.ownerActivity(1,id).get("status").asText());
        assertEquals("LEASED",store.task(guest.get("taskId").asLong()).getStatus());
        assertEquals("SETTLED",service.submit(2,guest.get("taskId").asLong(),request).get("status").asText());
    }
    @Test void invalidModelOutputConsumesReservedAttemptButNoDecisionOrMoney() {
        long id=room("SHORT"); ObjectNode task=ready(1); ObjectNode invalid=proposal(7,16);
        businessError("INVALID_PLAN",()->service.submit(1,task.get("taskId").asLong(),submission(task,invalid,UUID.randomUUID().toString())));
        assertEquals(1,store.seat(1).getAttemptsUsed()); assertEquals(0,store.seat(1).getDecisionsUsed());
        assertEquals(20_000,service.ownerActivity(1,id).get("game").get("cashMinor").asLong());
    }
    @Test void pausedExpiredTaskIsInterruptedAndSeatReleasedWithoutBankruptcy() {
        long id=room("SHORT"); service.updateParticipation(1,1,new ParticipationUpdate(1,false,6,6,12));
        clock.advance(901); assertTrue(service.expiredActivityIds().contains(id)); service.expire(id); service.expire(id);
        JsonNode view=service.ownerActivity(1,id); assertEquals("INTERRUPTED",view.get("status").asText());
        assertEquals("RUNNING",view.get("game").get("ending").asText()); assertEquals(0,count("playground_seats"));
        assertEquals(1,service.ownerEvents(1,id,0).stream().filter(e->e.get("kind").asText().equals("INTERRUPTED")).count());
    }
    @Test void occupiedAgentCannotJoinSecondActivityAndWithdrawalFreesSeat() {
        long first=room("SHORT"); enable(3);
        long second=Long.parseLong(service.invite(3,new Invitation(3L,1L,"SHORT",brief("birds"))).get("activityId").toString());
        service.acceptInvitation(1,second,new InvitationAccept(brief("cats")));
        businessError("AGENT_ALREADY_IN_ACTIVITY",()->service.join(1,second));
        service.leave(1,first); service.join(1,second);
        assertEquals(second,store.seat(1).getActivityId());
    }
    @Test void revocationWinsAgainstClaimThatAlreadyReadAnOlderTaskSnapshot() throws Exception {
        room("SHORT"); long taskId=Long.parseLong(service.tasks(1).get(0).get("taskId").toString());
        ExecutorService executor=Executors.newSingleThreadExecutor();
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        java.util.concurrent.atomic.AtomicReference<Future<String>> result=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            tx.executeWithoutResult(status->{
                store.lockAgent(1);
                result.set(executor.submit(()->{ try { service.claim(1,taskId,new Claim(1)); return "WRONG_LEASE"; }
                    catch(BusinessException error) { return error.getMessage(); } }));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<until) {
                    if(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Integer.class)>0) { waiting=true; break; }
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                assertTrue(waiting,"Claim must reach a database lock after its initial task read");
                service.updateParticipation(1,1,new ParticipationUpdate(1,false,6,6,12));
            });
            assertEquals("PARTICIPATION_DISABLED",result.get().get(10,TimeUnit.SECONDS));
            assertNull(store.task(taskId).getLeaseHash()); assertEquals(0,count("playground_attempts"));
        } finally { executor.shutdownNow(); }
    }
    @Test void activityListsRespectOwnerAndConfirmedInvitationScope() {
        enable(1); enable(2);
        long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",brief("cats"))).get("activityId").toString());
        assertEquals(1,service.mine(1,Long.MAX_VALUE).size()); assertEquals(1,service.mine(2,Long.MAX_VALUE).size());
        assertTrue(service.mine(3,Long.MAX_VALUE).isEmpty()); assertTrue(service.opportunities(2).isEmpty());
        service.acceptInvitation(2,id,new InvitationAccept(brief("dogs")));
        assertEquals(1,service.opportunities(2).size());
        assertFalse(service.opportunities(2).toString().contains("private:cats"));
        assertTrue(service.mine(1,id).isEmpty());
    }
    @Test void abandonedGamesStillCountAgainstDailyTwoGameLimit() {
        long first=room("SHORT"); service.leave(1,first);
        for(int i=0;i<2;i++) {
            long id=Long.parseLong(service.invite(1,new Invitation(1L,2L,"SHORT",brief("cats"))).get("activityId").toString());
            service.acceptInvitation(2,id,new InvitationAccept(brief("dogs"))); service.join(1,id);
            if(i==0) {service.join(2,id); service.leave(1,id);}
            else {
                businessError("DAILY_GAME_LIMIT",()->service.join(2,id));
                assertNull(store.seat(2)); assertTrue(service.tasks(1).isEmpty());
                assertEquals("WAITING",service.ownerActivity(1,id).get("status").asText());
            }
        }
        assertEquals(0,count("playground_attempts"));
    }
    @Test void unresolvedInvitationCannotBeDuplicatedBySameHost() {
        enable(1); enable(2);
        service.invite(1,new Invitation(1L,2L,"SHORT",brief("cats")));
        businessError("AGENT_ALREADY_HAS_INTENTION",()->service.invite(1,new Invitation(1L,2L,"SHORT",brief("cats"))));
        assertEquals(1,count("playground_activities"));
    }
}
