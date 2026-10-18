package com.ai.repo.playground.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ai.repo.entity.Agent;
import com.ai.repo.entity.User;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.mapper.AgentMapper;
import com.ai.repo.mapper.UserMapper;
import com.ai.repo.playground.dto.PlaygroundRequests.*;
import com.ai.repo.playground.framework.GameKey;
import com.ai.repo.playground.framework.GameRegistry;
import com.ai.repo.playground.games.OddShopModule;
import com.ai.repo.playground.entity.PlaygroundRoomState;
import com.ai.repo.playground.entity.PlaygroundRows.*;
import com.ai.repo.playground.mapper.PlaygroundMapper;
import com.ai.repo.playground.rules.MonthlyShopRules;
import com.ai.repo.playground.rules.ProposalAgreement;
import com.ai.repo.playground.rules.PartnerDisclosure;
import com.ai.repo.playground.rules.V5MonthlyWindow;
import com.ai.repo.playground.rules.V5FranchiseOffer;
import com.ai.repo.playground.rules.V5FranchiseWindow;
import com.ai.repo.playground.rules.V5ShopRules;
import com.ai.repo.playground.rules.V5StrategyContract;
import com.ai.repo.playground.rules.V6FoundingAgreement;
import com.ai.repo.playground.rules.V6FoundingContract;
import com.ai.repo.playground.rules.V6ConflictRules;
import com.ai.repo.playground.rules.V6MonthlyWindow;
import com.ai.repo.playground.rules.V6AnnualWindow;
import com.ai.repo.playground.rules.V6MonthlyPlan;
import com.ai.repo.playground.rules.V7StoryRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class PlaygroundService {
    private final PlaygroundMapper store;
    private final AgentMapper agents;
    private final UserMapper users;
    private final ObjectMapper json;
    private final boolean enabled;
    private final boolean v5Enabled;
    private final boolean franchiseEnabled;
    private final boolean v6Enabled;
    @Value("${playground.enriched-context:false}")
    private boolean enrichedContext;
    @Value("${playground.story-enabled:false}")
    private boolean storyEnabled;
    private final Clock clock;
    private final MonthlyShopRules rules = new MonthlyShopRules();
    private final GameRegistry gameModules = OddShopModule.registry();
    private final V6ConflictRules v6Rules = new V6ConflictRules();
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public PlaygroundService(PlaygroundMapper store, AgentMapper agents, UserMapper users,
                             ObjectMapper json, @Value("${playground.enabled:false}") boolean enabled,
                             @Value("${playground.v5-enabled:false}") boolean v5Enabled,
                             @Value("${playground.franchise-enabled:false}") boolean franchiseEnabled,
                             @Value("${playground.v6-enabled:false}") boolean v6Enabled) {
        this(store, agents, users, json, enabled, v5Enabled, franchiseEnabled, v6Enabled, Clock.systemUTC());
    }
    public PlaygroundService(PlaygroundMapper store, AgentMapper agents, UserMapper users,
                             ObjectMapper json, boolean enabled, Clock clock) {
        this(store,agents,users,json,enabled,false,clock);
    }
    public PlaygroundService(PlaygroundMapper store, AgentMapper agents, UserMapper users,
                             ObjectMapper json, boolean enabled, boolean v5Enabled, Clock clock) {
        this(store,agents,users,json,enabled,v5Enabled,false,clock);
    }
    public PlaygroundService(PlaygroundMapper store, AgentMapper agents, UserMapper users,
                             ObjectMapper json, boolean enabled, boolean v5Enabled,
                             boolean franchiseEnabled, Clock clock) {
        this(store,agents,users,json,enabled,v5Enabled,franchiseEnabled,false,clock);
    }
    public PlaygroundService(PlaygroundMapper store, AgentMapper agents, UserMapper users,
                             ObjectMapper json, boolean enabled, boolean v5Enabled,
                             boolean franchiseEnabled, boolean v6Enabled, Clock clock) {
        this.store=store; this.agents=agents; this.users=users; this.json=json;
        this.enabled=enabled; this.v5Enabled=v5Enabled;
        this.franchiseEnabled=franchiseEnabled; this.v6Enabled=v6Enabled; this.clock=clock;
    }
    public Participation participation(long userId, long agentId) {
        available(); owner(userId, agentId);
        Participation result = store.participation(agentId);
        if (result != null) return result;
        result = new Participation(); result.setAgentId(agentId); result.setUserId(userId);
        result.setVersion(0L); result.setEnabled(false); result.setMaxDecisions(4);
        result.setMaxAttempts(4); result.setMaxDailyAttempts(8);
        return result;
    }
    @Transactional
    public Participation updateParticipation(long userId, long agentId, ParticipationUpdate request) {
        available(); lockAgents(agentId); owner(userId, agentId);
        Participation row = participation(userId, agentId);
        require(row.getVersion() == request.expectedVersion(),409,"PERMISSION_VERSION_CONFLICT");
        require(request.maxDecisions() >= 1 && request.maxDecisions() <= 40 && request.maxAttempts() >= 1
                && request.maxAttempts() <= 40 && request.maxDailyAttempts() >= 1
                && request.maxDailyAttempts() <= 40,400,"INVALID_BUDGET");
        require(request.enabled()!=null,400,"INVALID_PARTICIPATION");
        row.setVersion(row.getVersion()+1); row.setEnabled(request.enabled());
        row.setMaxDecisions(request.maxDecisions()); row.setMaxAttempts(request.maxAttempts());
        row.setMaxDailyAttempts(request.maxDailyAttempts()); row.setUpdatedAt(now());
        store.saveParticipation(row);
        // Changing permission invalidates outstanding leases, but keeps consumed budget and deadlines.
        store.rebindSeat(agentId, row.getVersion()); store.invalidateLeases(agentId,row.getVersion());
        return row;
    }
    @Transactional
    public Map<String,Object> invite(long userId, Invitation request) {
        available(); require(!request.agentId().equals(request.partnerAgentId()),400,"TWO_DISTINCT_AGENTS_REQUIRED");
        require(Set.of("SHORT","FULL").contains(request.mode()),400,"INVALID_MODE");
        lockAgents(request.agentId(),request.partnerAgentId()); owner(userId,request.agentId());
        activeAgent(request.partnerAgentId()); permit(request.agentId());
        if (v6Enabled && request.mode().equals("FULL") && contractVersionFor(request.ownerBrief())==5)
            annualGrant(permit(request.agentId()));
        require(store.waitingMatchCount(request.agentId())==0 && store.waitingMatchCount(request.partnerAgentId())==0,409,"AGENT_IN_MATCH_QUEUE");
        require(store.seat(request.agentId()) == null,409,"AGENT_ALREADY_IN_ACTIVITY");
        require(store.pendingHostCount(request.agentId())==0,409,"AGENT_ALREADY_HAS_INTENTION");
        Activity activity = new Activity(); activity.setHostAgentId(request.agentId());
        activity.setGuestAgentId(request.partnerAgentId()); activity.setHorizonMonths(request.mode().equals("FULL")?12:2);
        activity.setStatus("INVITED"); activity.setNextSequence(1L); activity.setCreatedAt(now());
        activity.setUpdatedAt(now()); activity.setExpiresAt(now().plusHours(24));
        PlaygroundRoomState state = new PlaygroundRoomState(); state.setContractVersion(contractVersionFor(request.ownerBrief()));
        if (state.getContractVersion()==4) state.setRuleVersion("0.5");
        if (state.getContractVersion()==5) state.setRuleVersion(v6Enabled
                ?(activity.getHorizonMonths()==12?(storyEnabled?"1.1":"1.0"):"0.9"):"0.8");
        if (isV6(state)) state.setV6Story(V6ConflictRules.StoryState.initial());
        state.getOwnerBriefs().put(request.agentId(),request.ownerBrief());
        state.setGame(shopModule(state).initialize(new OddShopModule.Configuration(activity.getHorizonMonths(),
                new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE,1,12))));
        activity.setStateJson(write(state)); store.insertActivity(activity);
        event(activity,"INVITED","SYSTEM",null,"PROPOSED",Map.of("horizonMonths",activity.getHorizonMonths()));
        save(activity,state); return Map.of("activityId",activity.getId().toString(),"status",activity.getStatus());
    }
    @Transactional
    public Map<String,Object> acceptInvitation(long userId,long activityId, InvitationAccept request) {
        Activity activity = locked(activityId); lockRoomAgents(activity); owner(userId,activity.getGuestAgentId());
        live(activity); permit(activity.getGuestAgentId());
        require(activity.getStatus().equals("INVITED"),409,"INVITATION_NOT_PENDING");
        PlaygroundRoomState state = state(activity);
        require(state.getContractVersion()==contractVersionFor(request.ownerBrief()),400,"CONTRACT_VERSION_MISMATCH");
        if (isAnnual(state)) annualGrant(permit(activity.getGuestAgentId()));
        state.getOwnerBriefs().put(activity.getGuestAgentId(),request.ownerBrief());
        activity.setStatus("WAITING"); event(activity,"INVITATION_ACCEPTED","SYSTEM",null,"EXECUTED",Map.of());
        save(activity,state); return Map.of("activityId",activityId+"","status",activity.getStatus());
    }
    @Transactional
    public Map<String,Object> join(long agentId,long activityId) {
        Activity activity = locked(activityId); member(activity,agentId); lockRoomAgents(activity); live(activity);
        require(activity.getStatus().equals("WAITING") || activity.getStatus().equals("PLANNING"),409,"OWNERS_NOT_CONFIRMED");
        PlaygroundRoomState state = state(activity); require(state.getOwnerBriefs().containsKey(agentId),403,"OWNER_BRIEF_REQUIRED");
        Participation permission = permit(agentId);
        if (isAnnual(state)) annualGrant(permission);
        Seat seat = store.seat(agentId);
        require(seat == null || seat.getActivityId() == activityId,409,"AGENT_ALREADY_IN_ACTIVITY");
        if (!state.getReadyAgents().contains(agentId)) {
            seat = new Seat(); seat.setAgentId(agentId); seat.setActivityId(activityId);
            seat.setPermissionVersion(permission.getVersion()); if (store.seat(agentId)==null) store.insertSeat(seat);
            state.getReadyAgents().add(agentId);
            event(activity,"AGENT_READY","ADAPTER",agentId,"EXECUTED",Map.of());
        }
        if (state.getReadyAgents().size()==2 && activity.getStatus().equals("WAITING")) {
            permit(activity.getHostAgentId()); permit(activity.getGuestAgentId());
            for (long actor:new TreeSet<>(List.of(activity.getHostAgentId(),activity.getGuestAgentId()))) {
                store.ensureDaily(actor,now().toLocalDate());
                require(store.lockDailyGames(actor,now().toLocalDate())<2,429,"DAILY_GAME_LIMIT");
                store.incrementDailyGames(actor,now().toLocalDate());
            }
            activity.setExpiresAt(state.isRandomMatched() ? now().plusHours(2) : min(activity.getExpiresAt(),now().plusHours(2)));
            activity.setStatus("PLANNING");
            if (franchiseEnabled && state.getContractVersion()==5 && Set.of("0.8","0.9","1.0","1.1").contains(state.getRuleVersion())
                    && activity.getHorizonMonths()==12) {
                V5FranchiseOffer offer=V5FranchiseOffer.draw("franchise:"+activity.getId(),1,random);
                state.setFranchiseOffer(offer);
                state.setFranchiseWindow(V5FranchiseWindow.open(offer,activity.getHostAgentId(),
                        activity.getGuestAgentId(),min(now().plusMinutes(15),activity.getExpiresAt())
                                .toInstant(ZoneOffset.UTC)));
                eventAtMonth(activity,"FRANCHISE_PITCH","NPC",null,"PROPOSED",
                        Map.of("offer",offer.publicTerms(),"claimCode","GUARANTEED_CUSTOMERS_UNVERIFIED"),0);
            }
            issueTask(activity,state,activity.getHostAgentId());
        }
        save(activity,state); return Map.of("activityId",activityId+"","status",activity.getStatus());
    }
    public List<Map<String,Object>> mine(long userId,long beforeId) {
        available(); User user=users.selectById(userId); require(user!=null && "ACTIVE".equals(user.getStatus()),403,"PRINCIPAL_DISABLED");
        require(beforeId>0,400,"INVALID_CURSOR");
        return store.mine(userId,beforeId).stream().map(this::activityCard).toList();
    }
    public List<Map<String,Object>> opportunities(long agentId) {
        available(); permit(agentId);
        return store.opportunities(agentId,now()).stream().map(this::activityCard).toList();
    }
    int contractVersionFor(OwnerBrief brief) {
        require(brief!=null,400,"OWNER_BRIEF_REQUIRED");
        if (brief.gameContractVersion()!=null) {
            require(brief.partnerShareFields()!=null,400,"CONTRACT_VERSION_MISMATCH");
            if (brief.gameContractVersion()==5) {
                require(v5Enabled,404,"V5_NOT_AVAILABLE");
                return 5;
            }
            require(brief.gameContractVersion()==4,400,"CONTRACT_VERSION_MISMATCH");
            return 4;
        }
        return brief.partnerShareFields()==null?2:3;
    }
    private Map<String,Object> activityCard(Activity activity) {
        return Map.of("activityId",activity.getId().toString(),"status",activity.getStatus(),
                "horizonMonths",activity.getHorizonMonths(),"expiresAt",utc(activity.getExpiresAt()));
    }
    public List<Map<String,Object>> tasks(long agentId) {
        available(); activeAgent(agentId);
        List<Map<String,Object>> result = new ArrayList<>();
        for (Task task : store.tasks(agentId,now())) result.add(Map.of("taskId",task.getId().toString(),
                "activityId",task.getActivityId().toString(),"phase",task.getPhase(),"status",task.getStatus(),
                "permissionVersion",task.getPermissionVersion(),"expiresAt",utc(task.getExpiresAt())));
        return result; // No lease, attempt reservation, or model invocation on GET.
    }
    @Transactional
    public ObjectNode claim(long agentId,long taskId, Claim request) {
        Task task = taskFor(agentId,taskId); Activity activity = locked(task.getActivityId()); lockRoomAgents(activity);
        task=lockedTaskFor(agentId,taskId); PlaygroundRoomState state = state(activity);
        permissionForTask(activity,task,request.permissionVersion());
        require(task.getStatus().equals("PENDING") || task.getStatus().equals("LEASED"),409,"TASK_NOT_PENDING");
        require(task.getLeaseExpiresAt()==null || !now().isBefore(task.getLeaseExpiresAt()),409,"TASK_ALREADY_LEASED");
        require(remainingDecisions(activity,task.getAgentId())>0 && windowRemaining(state,agentId)>0,429,"BUDGET_EXHAUSTED");
        if (task.getAttemptId()!=null) {
            Attempt previous = store.attempt(task.getAttemptId());
            if (previous!=null && previous.getStatus().equals("STARTED")) { previous.setStatus("UNKNOWN"); store.saveAttempt(previous); }
        }
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        task.setLeaseHash(hash(token)); task.setLeaseExpiresAt(min(now().plusSeconds(90),task.getExpiresAt()));
        task.setStatus("LEASED"); task.setAttemptId(null); store.saveTask(task);
        return taskView(activity,state,task,token);
    }
    @Transactional
    public ObjectNode startAttempt(long agentId,long taskId, AttemptStart request) {
        Task task = taskFor(agentId,taskId); Activity activity = locked(task.getActivityId()); lockRoomAgents(activity);
        task=lockedTaskFor(agentId,taskId); permissionForTask(activity,task,request.permissionVersion());
        lease(task,request.leaseToken()); String key=uuid(request.idempotencyKey());
        Attempt existing=store.attemptByKey(agentId,key);
        if (existing!=null) {
            require(existing.getTaskId()==taskId && existing.getLeaseHash().equals(task.getLeaseHash())
                    && existing.getStatus().equals("STARTED") && existing.getId().equals(task.getAttemptId()),409,"IDEMPOTENCY_CONFLICT");
            return taskView(activity,state(activity),task,request.leaseToken());
        }
        require(task.getAttemptId()==null,409,"ATTEMPT_OUTCOME_UNKNOWN");
        Participation permission=permit(agentId); Seat seat=seat(activity,agentId);
        PlaygroundRoomState state=state(activity);
        require(seat.getAttemptsUsed()<attemptLimit(activity,permission) && remainingDecisions(activity,agentId)>0
                && windowRemaining(state,agentId)>0,429,"BUDGET_EXHAUSTED");
        store.ensureDaily(agentId,now().toLocalDate());
        require(store.lockDaily(agentId,now().toLocalDate())<permission.getMaxDailyAttempts(),429,"DAILY_BUDGET_EXHAUSTED");
        Attempt attempt=new Attempt(); attempt.setAgentId(agentId); attempt.setTaskId(taskId);
        attempt.setRequestKey(key); attempt.setLeaseHash(task.getLeaseHash()); attempt.setStatus("STARTED"); attempt.setCreatedAt(now());
        store.insertAttempt(attempt); task.setAttemptId(attempt.getId()); store.saveTask(task);
        seat.setAttemptsUsed(seat.getAttemptsUsed()+1); store.saveSeat(seat); store.incrementDaily(agentId,now().toLocalDate());
        return taskView(activity,state,task,request.leaseToken());
    }
    @Transactional
    public ObjectNode reportAttemptFailure(long agentId,long taskId,AttemptFailure request) {
        Task task=taskFor(agentId,taskId); Activity activity=locked(task.getActivityId()); lockRoomAgents(activity);
        task=lockedTaskFor(agentId,taskId);
        PlaygroundRoomState state=state(activity);
        require(Set.of("MODEL_OUTPUT_INVALID","MODEL_RESPONSE_INCOMPLETE","MODEL_REFUSED","MODEL_PROVIDER_REJECTED").contains(request.reasonCode()),400,"INVALID_FAILURE_REASON");
        if (request.formatHint()!=null)
            require(state.getContractVersion()==5 && request.reasonCode().equals("MODEL_OUTPUT_INVALID")
                    && Set.of("V5_STRATEGY_ENUM","PLAN_CONTRIBUTIONS","ACTION_SHAPE").contains(request.formatHint()),
                    400,"INVALID_FORMAT_HINT");
        require(task.getAttemptId()!=null && task.getAttemptId().toString().equals(request.attemptId())
                && task.getPermissionVersion()==request.permissionVersion(),409,"ATTEMPT_REQUIRED");
        Attempt attempt=store.attempt(task.getAttemptId());
        require(attempt!=null && attempt.getAgentId()==agentId && attempt.getTaskId()==taskId
                && attempt.getLeaseHash().equals(hash(request.leaseToken())),409,"ATTEMPT_REQUIRED");
        // A repeated report after an unknown HTTP response gets the original disposition.
        if (attempt.getStatus().equals("FAILED")) {
            require(request.reasonCode().equals(attempt.getFailureReason()),409,"IDEMPOTENCY_CONFLICT");
            require(Set.of("RETRIED","FAILED").contains(task.getStatus()),409,"ATTEMPT_REQUIRED");
            return json.createObjectNode().put("taskId",taskId+"").put("activityId",activity.getId()+"")
                    .put("status",task.getStatus().equals("RETRIED")?"RETRY_PENDING":activity.getStatus());
        }
        permissionForTask(activity,task,request.permissionVersion()); lease(task,request.leaseToken());
        require(attempt.getStatus().equals("STARTED"),409,"ATTEMPT_REQUIRED");
        attempt.setStatus("FAILED"); attempt.setFailureReason(request.reasonCode()); store.saveAttempt(attempt);
        int failures=state.getConsecutiveModelFailures().merge(agentId,1,Integer::sum);
        Participation permission=permit(agentId); Seat seat=seat(activity,agentId);
        store.ensureDaily(agentId,now().toLocalDate());
        boolean retry=state.getContractVersion()>=3 && failures==1
                && seat.getAttemptsUsed()<attemptLimit(activity,permission)
                && remainingDecisions(activity,agentId)>0 && windowRemaining(state,agentId)>0
                && store.lockDaily(agentId,now().toLocalDate())<permission.getMaxDailyAttempts()
                && now().plusSeconds(60).isBefore(activity.getExpiresAt());
        task.setStatus(retry?"RETRIED":"FAILED"); task.setLeaseHash(null); task.setLeaseExpiresAt(null); store.saveTask(task);
        boolean skipOpportunity=!retry && state.getContractVersion()==4 && state.isNpcWindowOpen();
        boolean skipClosing=!retry && state.getContractVersion()>=4 && state.isClosingReplyPending();
        boolean skipReview=!retry && state.getReviewPendingAgent()!=null;
        boolean settleV5=!retry && openV5Window(state);
        boolean settleV6=!retry && openV6Window(state);
        boolean settleAnnual=!retry && openAnnualWindow(state);
        boolean skipFranchise=!retry && openFranchiseWindow(state);
        Map<String,Object> failureFacts=new LinkedHashMap<>();
        failureFacts.put("reasonCode",request.reasonCode()); failureFacts.put("retryScheduled",retry);
        if (request.formatHint()!=null) failureFacts.put("formatHint",request.formatHint());
        event(activity,"AGENT_FAILURE","ADAPTER",agentId,retry?"RETRYABLE":skipOpportunity || skipReview || settleV5 || settleV6 || settleAnnual || skipFranchise?"EXECUTED":"INTERRUPTED",failureFacts);
        if (retry) issueTask(activity,state,agentId);
        else {
            if (skipReview) {
                skipPartnerReview(activity,state,agentId,"MODEL_FAILURE");
                state.setReviewPendingAgent(null);
            } else if (skipOpportunity) {
                state.setNpcWindowOpen(false);
                eventAtMonth(activity,"NPC_OPPORTUNITY_SKIPPED","SYSTEM",null,"EXECUTED",
                        Map.of("offerId",state.getNpcOrder().id(),"reason","MODEL_FAILURE"),1);
                finishMonths(activity,state,null);
            } else if (skipClosing) closeWithoutReply(activity,state,"MODEL_FAILURE");
            else if (skipFranchise) fallbackFranchise(activity,state,V5FranchiseWindow.Resolution.MODEL_FAILURE_FALLBACK);
            else if (settleAnnual) fallbackAnnualMonth(activity,state,V6AnnualWindow.Resolution.MODEL_FAILURE_FALLBACK);
            else if (settleV6) fallbackV6Month(activity,state,V6MonthlyWindow.Resolution.MODEL_FAILURE_FALLBACK);
            else if (settleV5) fallbackV5Month(activity,state,V5MonthlyWindow.Resolution.MODEL_FAILURE_FALLBACK);
            else interrupt(activity,"AGENT_DECISION_FAILED");
            store.cancelTasks(activity.getId());
            resumePreopenAfterFallback(activity,state);
            resumeV5AfterFallback(activity,state,V5MonthlyWindow.Resolution.MODEL_FAILURE_FALLBACK);
            resumeV6AfterFallback(activity,state,V6MonthlyWindow.Resolution.MODEL_FAILURE_FALLBACK);
            resumeAnnualAfterFallback(activity,state,V6AnnualWindow.Resolution.MODEL_FAILURE_FALLBACK);
            if (skipReview) advancePartnerReviews(activity,state);
            if (Set.of("SETTLED","INTERRUPTED").contains(activity.getStatus())) store.releaseSeats(activity.getId());
        }
        save(activity,state);
        return json.createObjectNode().put("taskId",taskId+"").put("activityId",activity.getId()+"")
                .put("status",retry?"RETRY_PENDING":activity.getStatus());
    }
    @Transactional
    public JsonNode submit(long agentId,long taskId, Submission request) {
        Task task=taskFor(agentId,taskId); Activity activity=locked(task.getActivityId()); lockRoomAgents(activity);
        task=lockedTaskFor(agentId,taskId); Participation permission=permit(agentId);
        require(task.getPermissionVersion()==permission.getVersion() && request.permissionVersion()==permission.getVersion(),409,"PERMISSION_VERSION_CONFLICT");
        require(Long.toString(taskId).equals(request.taskId()) && activity.getId().toString().equals(request.activityId()),400,"TASK_SCOPE_MISMATCH");
        String key=uuid(request.idempotencyKey()); String canonicalRequest=write(canonical(json.valueToTree(request)));
        require(canonicalRequest.length()<=8192,400,"ACTION_TOO_LARGE"); String fingerprint=hash(canonicalRequest);
        Receipt receipt=store.receipt(agentId,key);
        if (receipt!=null) {
            require(receipt.getTaskId()==taskId && receipt.getRequestHash().equals(fingerprint),409,"IDEMPOTENCY_CONFLICT");
            return read(receipt.getResponseJson(),JsonNode.class);
        }
        permissionForTask(activity,task,request.permissionVersion()); lease(task,request.leaseToken());
        require(task.getAttemptId()!=null && task.getAttemptId().toString().equals(request.attemptId()),409,"ATTEMPT_REQUIRED");
        Attempt attempt=store.attempt(task.getAttemptId());
        require(attempt!=null && attempt.getAgentId()==agentId && attempt.getTaskId()==taskId
                && attempt.getLeaseHash().equals(task.getLeaseHash()) && attempt.getStatus().equals("STARTED"),409,"ATTEMPT_REQUIRED");
        PlaygroundRoomState state=state(activity); Seat seat=seat(activity,agentId);
        require(remainingDecisions(activity,agentId)>0 && windowRemaining(state,agentId)>0,429,"BUDGET_EXHAUSTED");
        String countedWindow=decisionWindowKey(state);
        reduce(activity,state,agentId,request.action());
        state.getConsecutiveModelFailures().remove(agentId);
        seat.setDecisionsUsed(seat.getDecisionsUsed()+1); store.saveSeat(seat);
        if (countedWindow.equals(decisionWindowKey(state)))
            state.getWindowDecisions().merge(agentId,1,Integer::sum);
        task.setStatus("DONE"); store.saveTask(task); attempt.setStatus("SUBMITTED"); store.saveAttempt(attempt);
        if (task.getPhase().equals("FRANCHISE_DECISION") && state.getGame().operatedMonths()==0
                && !openFranchiseWindow(state)) state.getWindowDecisions().clear();
        if (activity.getStatus().equals("PLANNING") && state.getReviewPendingAgent()!=null) {
            // The review reducer has already issued the next independent review task.
        } else if (activity.getStatus().equals("PLANNING") && openFranchiseWindow(state)) {
            long next=franchiseWindowActor(state.getFranchiseWindow());
            if (canOfferTask(activity,state,next)) issueTask(activity,state,next);
            else {
                fallbackFranchise(activity,state,V5FranchiseWindow.Resolution.BUDGET_FALLBACK);
                resumePreopenAfterFallback(activity,state);
                resumeV5AfterFallback(activity,state,V5MonthlyWindow.Resolution.BUDGET_FALLBACK);
            }
        } else if (activity.getStatus().equals("PLANNING") && openAnnualWindow(state)) {
            long next=state.getV6AnnualWindow().currentActor();
            if (canOfferTask(activity,state,next)) issueTask(activity,state,next);
            else {
                fallbackAnnualMonth(activity,state,V6AnnualWindow.Resolution.BUDGET_FALLBACK);
                resumeAnnualAfterFallback(activity,state,V6AnnualWindow.Resolution.BUDGET_FALLBACK);
            }
        } else if (activity.getStatus().equals("PLANNING") && openV6Window(state)) {
            long next=state.getV6Window().currentActor();
            if (canOfferTask(activity,state,next)) issueTask(activity,state,next);
            else {
                fallbackV6Month(activity,state,V6MonthlyWindow.Resolution.BUDGET_FALLBACK);
                resumeV6AfterFallback(activity,state,V6MonthlyWindow.Resolution.BUDGET_FALLBACK);
            }
        } else if (activity.getStatus().equals("PLANNING") && openV5Window(state)) {
            long next=v5WindowActor(state.getV5Window());
            if (canOfferTask(activity,state,next)) issueTask(activity,state,next);
            else fallbackV5Month(activity,state,V5MonthlyWindow.Resolution.BUDGET_FALLBACK);
        } else if (activity.getStatus().equals("PLANNING") && state.isNpcWindowOpen()) {
            boolean ready=true;
            for (long participant:new TreeSet<>(List.of(activity.getHostAgentId(),activity.getGuestAgentId())))
                if (!state.getNpcAcceptedBy().contains(participant) && !canOfferTask(activity,state,participant)) ready=false;
            if (!ready) {
                state.setNpcWindowOpen(false);
                eventAtMonth(activity,"NPC_OPPORTUNITY_SKIPPED","SYSTEM",null,"EXECUTED",
                        Map.of("offerId",state.getNpcOrder().id(),"reason","BUDGET_OR_DEADLINE"),1);
                finishMonths(activity,state,null);
            } else {
                long next=agentId==activity.getHostAgentId()?activity.getGuestAgentId():activity.getHostAgentId();
                issueTask(activity,state,next);
            }
        } else if (activity.getStatus().equals("PLANNING")) {
            long next=agentId==activity.getHostAgentId()?activity.getGuestAgentId():activity.getHostAgentId();
            if (remainingDecisions(activity,next)==0 || windowRemaining(state,next)==0) {
                if (state.isClosingReplyPending()) closeWithoutReply(activity,state,"BUDGET_EXHAUSTED");
                else interrupt(activity,"WINDOW_BUDGET_EXHAUSTED");
            }
            else issueTask(activity,state,next);
        }
        if (Set.of("SETTLED","INTERRUPTED").contains(activity.getStatus())) {
            store.cancelTasks(activity.getId()); store.releaseSeats(activity.getId());
        }
        save(activity,state);
        ObjectNode response=json.createObjectNode().put("taskId",taskId+"").put("activityId",activity.getId()+"")
                .put("status",activity.getStatus()).put("lastSequence",activity.getNextSequence()-1);
        receipt=new Receipt(); receipt.setAgentId(agentId); receipt.setTaskId(taskId); receipt.setRequestKey(key);
        receipt.setRequestHash(fingerprint); receipt.setResponseJson(write(response)); receipt.setCreatedAt(now()); store.insertReceipt(receipt);
        return read(receipt.getResponseJson(),JsonNode.class);
    }
    public ObjectNode ownerActivity(long userId,long activityId) {
        available(); Activity activity=store.activity(activityId); require(activity!=null,404,"ACTIVITY_NOT_FOUND");
        long own=ownerMember(userId,activity);
        PlaygroundRoomState state=state(activity);
        ObjectNode result=json.createObjectNode().put("activityId",activityId+"").put("status",activity.getStatus())
                .put("horizonMonths",activity.getHorizonMonths()).put("ruleVersion",state.getRuleVersion()).put("contractVersion",state.getContractVersion())
                .put("hostAgentId",activity.getHostAgentId()+"").put("guestAgentId",activity.getGuestAgentId()+"")
                .put("viewerAgentId",own+"")
                .put("canAcceptInvitation",activity.getStatus().equals("INVITED") && agents.selectById(activity.getGuestAgentId()).getUserId().equals(userId));
        result.set("ownerBrief",json.valueToTree(state.getOwnerBriefs().get(own)));
        result.set("game",json.valueToTree(state.getGame()));
        if (activity.getStatus().equals("SETTLED")) {
            result.set("summary",json.valueToTree(summaryFor(state)));
            if (isAnnual(state)) result.set("partnerReviews",json.valueToTree(state.getPartnerReviews()));
        } else if (isAnnual(state) && state.getReviewPendingAgent()!=null)
            result.put("phase","PARTNER_REVIEW");
        return result;
    }
    public List<JsonNode> ownerEvents(long userId,long activityId,long after) {
        available(); Activity activity=store.activity(activityId); require(activity!=null,404,"ACTIVITY_NOT_FOUND");
        ownerMember(userId,activity); require(after>=0,400,"INVALID_CURSOR");
        return store.events(activityId,after).stream().map(value->read(value,JsonNode.class)).toList();
    }
    @Transactional
    public void leave(long userId,long activityId) {
        Activity activity=locked(activityId); lockRoomAgents(activity); ownerMember(userId,activity);
        require(!Set.of("SETTLED","INTERRUPTED").contains(activity.getStatus()),409,"ACTIVITY_ENDED");
        PlaygroundRoomState state=state(activity);
        if (state.getReviewPendingAgent()!=null) {
            for (long actor:List.of(activity.getHostAgentId(),activity.getGuestAgentId()))
                if (!state.getPartnerReviews().containsKey(actor)) skipPartnerReview(activity,state,actor,"OWNER_LEFT");
            state.setReviewPendingAgent(null);
            activity.setStatus("SETTLED");
            store.cancelTasks(activityId); store.releaseSeats(activityId);
            save(activity,state);
            return;
        }
        interrupt(activity,"OWNER_LEFT"); store.cancelTasks(activityId); store.releaseSeats(activityId);
        save(activity,state); // Withdrawal during preparation is interruption, not business bankruptcy.
    }
    public List<Long> expiredActivityIds() { available(); return store.expired(now()).stream().map(Activity::getId).toList(); }
    @Transactional
    public void expire(long activityId) {
        Activity activity=locked(activityId); lockRoomAgents(activity);
        if (!Set.of("INVITED","WAITING","PLANNING").contains(activity.getStatus())) return;
        boolean expired=!now().isBefore(activity.getExpiresAt()) || store.expiredTaskCount(activityId,now())>0;
        if (!expired) return;
        PlaygroundRoomState state=state(activity);
        if (state.getReviewPendingAgent()!=null) {
            for (long actor:List.of(activity.getHostAgentId(),activity.getGuestAgentId()))
                if (!state.getPartnerReviews().containsKey(actor)) skipPartnerReview(activity,state,actor,"DEADLINE_EXPIRED");
            state.setReviewPendingAgent(null);
            activity.setStatus("SETTLED");
        } else if (state.getContractVersion()==4 && state.isNpcWindowOpen()) {
            state.setNpcWindowOpen(false);
            eventAtMonth(activity,"NPC_OPPORTUNITY_SKIPPED","SYSTEM",null,"EXECUTED",
                    Map.of("offerId",state.getNpcOrder().id(),"reason","DEADLINE_EXPIRED"),1);
            finishMonths(activity,state,null);
        } else if (state.getContractVersion()>=4 && state.isClosingReplyPending()) {
            closeWithoutReply(activity,state,"DEADLINE_EXPIRED");
        } else if (openFranchiseWindow(state)) {
            fallbackFranchise(activity,state,V5FranchiseWindow.Resolution.DEADLINE_FALLBACK);
        } else if (openAnnualWindow(state)) {
            fallbackAnnualMonth(activity,state,V6AnnualWindow.Resolution.DEADLINE_FALLBACK);
        } else if (openV6Window(state)) {
            fallbackV6Month(activity,state,V6MonthlyWindow.Resolution.DEADLINE_FALLBACK);
        } else if (openV5Window(state)) {
            fallbackV5Month(activity,state,V5MonthlyWindow.Resolution.DEADLINE_FALLBACK);
        } else interrupt(activity,"DEADLINE_EXPIRED");
        store.cancelTasks(activityId);
        resumePreopenAfterFallback(activity,state);
        resumeV5AfterFallback(activity,state,V5MonthlyWindow.Resolution.DEADLINE_FALLBACK);
        resumeV6AfterFallback(activity,state,V6MonthlyWindow.Resolution.DEADLINE_FALLBACK);
        resumeAnnualAfterFallback(activity,state,V6AnnualWindow.Resolution.DEADLINE_FALLBACK);
        if (Set.of("SETTLED","INTERRUPTED").contains(activity.getStatus())) store.releaseSeats(activityId);
        save(activity,state);
    }
    private void reduce(Activity activity,PlaygroundRoomState state,long actor,JsonNode action) {
        fields(action,Set.of("actionType","payload","publicRationale"));
        String type=text(action,"actionType",30); text(action,"publicRationale",300);
        require(allowed(state).contains(type),400,"ACTION_NOT_ALLOWED"); JsonNode payload=action.get("payload");
        if (state.getReviewPendingAgent()!=null) {
            require(actor==state.getReviewPendingAgent() && type.equals("REVIEW_PARTNER"),400,"ACTION_NOT_ALLOWED");
            fields(payload,Set.of("partnerStrength","friction","futureCollaboration","evidenceEventId"));
            String strength=text(payload,"partnerStrength",160),friction=text(payload,"friction",160);
            require(!strength.isBlank() && !friction.isBlank(),400,"REVIEW_TEXT_REQUIRED");
            require(Set.of("YES","CONDITIONAL","NO").contains(text(payload,"futureCollaboration",20)),
                    400,"INVALID_REVIEW_CHOICE");
            String evidence=identifier(payload,"evidenceEventId");
            require(partnerReviewEvidence(activity,actor).stream().anyMatch(item ->
                    item.path("eventId").asText().equals(evidence)),400,"REVIEW_EVIDENCE_REQUIRED");
            require(!action.path("publicRationale").asText().isBlank(),400,"REVIEW_TEXT_REQUIRED");
            state.getPartnerReviews().put(actor,action.deepCopy());
            eventAtMonth(activity,"PARTNER_REVIEW","USER_AGENT",actor,"EXECUTED",
                    Map.of("action",action),state.getGame().operatedMonths());
            state.setReviewPendingAgent(null);
            advancePartnerReviews(activity,state);
            return;
        }
        if (state.isNpcWindowOpen()) {
            reduceNpcOrder(activity,state,actor,type,payload,action);
            return;
        }
        if (openFranchiseWindow(state)) {
            reduceFranchise(activity,state,actor,type,payload,action);
            return;
        }
        if (openAnnualWindow(state)) {
            reduceAnnualMonth(activity,state,actor,type,payload,action);
            return;
        }
        if (openV6Window(state)) {
            reduceV6Month(activity,state,actor,type,payload,action);
            return;
        }
        if (openV5Window(state)) {
            reduceV5Month(activity,state,actor,type,payload,action);
            return;
        }
        String endReason=null;
        JsonNode recordedAction=action;
        if (state.isClosingReplyPending()) {
            require(type.equals("FINAL_NOTE") && !Objects.equals(actor,state.getClosingInitiator()),400,"ACTION_NOT_ALLOWED");
            fields(payload,Set.of("proposalId"));
            require(state.getAgreement()!=null && state.getAgreement().proposalId().equals(identifier(payload,"proposalId")),409,"STALE_PROPOSAL");
            require(!action.path("publicRationale").asText().isBlank(),400,"FINAL_NOTE_REQUIRED");
            event(activity,"FINAL_NOTE","USER_AGENT",actor,"EXECUTED",Map.of("action",action));
            state.setClosingReplyPending(false); interrupt(activity,"PLAN_DECLINED");
            return;
        }
        if (type.equals("PROPOSE_PLAN") || type.equals("COUNTER_PLAN")) {
            if (isAnnual(state) && type.equals("COUNTER_PLAN"))
                require(windowRemaining(state,actor)>1,400,"FINAL_PLANNING_TURN_REQUIRES_DECISION");
            fields(payload,Set.of("proposal")); JsonNode proposal=payload.get("proposal");
            fields(proposal,Set.of("proposalId","parentProposalId","plan"));
            String id=identifier(proposal,"proposalId");
            require(state.getUsedProposalIds().add(id),409,"STALE_PROPOSAL");
            if (state.getProposal()==null) require(proposal.get("parentProposalId").isNull(),409,"STALE_PROPOSAL");
            else require(state.getAgreement().proposalId().equals(text(proposal,"parentProposalId",100)),409,"STALE_PROPOSAL");
            JsonNode plan=proposal.get("plan"); validatePlan(plan,state,activity,actor);
            if (type.equals("COUNTER_PLAN"))
                require(!canonical(semanticPlan(state.getProposal().get("plan"))).equals(canonical(semanticPlan(plan))),400,"UNCHANGED_PLAN");
            if (state.getContractVersion()>=3) {
                ObjectNode normalized=(ObjectNode)action.deepCopy();
                ArrayNode items=(ArrayNode)normalized.path("payload").path("proposal").path("plan").path("contributions");
                for (JsonNode item:items) if (item.path("sourceEventId").isNull())
                    ((ObjectNode)item).put("sourceEventId",activity.getId()+":"+activity.getNextSequence());
                recordedAction=normalized;
                proposal=normalized.path("payload").path("proposal"); plan=proposal.path("plan");
            }
            state.setProposal(proposal.deepCopy()); state.setAgreement(ProposalAgreement.propose(id,write(canonical(plan)),
                    Set.of(activity.getHostAgentId(),activity.getGuestAgentId()),actor));
        } else if (type.equals("ACCEPT_PLAN") || type.equals("DECLINE_PLAN")) {
            fields(payload,Set.of("proposalId")); require(state.getAgreement()!=null,409,"NO_PROPOSAL");
            require(state.getAgreement().proposalId().equals(identifier(payload,"proposalId")),409,"STALE_PROPOSAL");
            if (type.equals("DECLINE_PLAN")) {
                if (state.getContractVersion()>=4) {
                    state.setClosingReplyPending(true); state.setClosingInitiator(actor);
                } else endReason="PLAN_DECLINED";
            }
            else {
                if (state.getContractVersion()>=3)
                    require(state.getProposal().path("plan").path("contributions").size()==2,400,"INCOMPLETE_SHARED_PLAN");
                state.setAgreement(state.getAgreement().approve(actor,state.getAgreement().proposalId(),state.getAgreement().contentHash()));
            }
        } else {
            fields(payload,Set.of("reason")); text(payload,"reason",300); endReason="AGENT_LEFT";
        }
        event(activity,type.equals("PROPOSE_PLAN")||type.equals("COUNTER_PLAN")?"PROPOSAL":"DECISION",
                "USER_AGENT",actor,endReason!=null || type.equals("DECLINE_PLAN")?"INTERRUPTED":state.getAgreement()!=null && state.getAgreement().partnersApproved()?"PARTNERS_APPROVED":"PROPOSED",
                Map.of("action",recordedAction));
        if (endReason!=null) interrupt(activity,endReason);
        if (state.getAgreement()!=null && state.getAgreement().partnersApproved() && activity.getStatus().equals("PLANNING")
                && state.getGame().operatedMonths()==0) {
            // Draw after both signatures, then persist the shock with the deterministic ledger.
            // Existing v2 rooms retain their original NONE environment.
            if (state.getContractVersion()>=3)
                state.setGame(rules.initialize(activity.getHorizonMonths(),
                        state.getContractVersion()==5 ? drawV5Environment(activity.getHorizonMonths())
                                : rules.drawEnvironment(activity.getHorizonMonths(),random)));
            JsonNode plan=state.getProposal().get("plan"); JsonNode product=plan.get("products").get(0);
            MonthlyShopRules.Plan standing=new MonthlyShopRules.Plan(product.get("quantity").intValue(),
                    product.get("priceCoins").intValue(),plan.get("reserveMinor").longValue());
            if (isV6(state))
                eventAtMonth(activity,"FOUNDING_AGREEMENT","USER_AGENT",null,"EXECUTED",
                        plan.get("foundingAgreement"),0);
            event(activity,"OPENED","SYSTEM",null,"EXECUTED",Map.of("proposalId",state.getAgreement().proposalId()));
            if (state.getContractVersion()==5) {
                advanceV5AndRecord(activity,state,v5Signal(state.getGame(),1),V5ShopRules.Response.KEEP_IDENTITY);
                continueV5(activity,state,actor);
            } else if (state.getContractVersion()==4) {
                advanceAndRecord(activity,state,standing,null);
                if (state.getGame().ending()==MonthlyShopRules.Ending.RUNNING) {
                    state.setNpcOrder(new MonthlyShopRules.NpcOrder("night-market:"+activity.getId(),2,2,18,36));
                    state.setNpcWindowOpen(true); state.getWindowDecisions().clear();
                    eventAtMonth(activity,"NPC_OFFER","NPC",null,"PROPOSED",
                            Map.of("offer",offerView(state.getNpcOrder()),"scripted",true),1);
                } else finishMonths(activity,state,null);
            } else finishMonths(activity,state,null);
        }
    }
    private void reduceNpcOrder(Activity activity,PlaygroundRoomState state,long actor,String type,JsonNode payload,JsonNode action) {
        if (type.equals("LEAVE")) {
            fields(payload,Set.of("reason")); text(payload,"reason",300);
            eventAtMonth(activity,"NPC_ORDER_DECISION","USER_AGENT",actor,"INTERRUPTED",Map.of("action",action),1);
            interrupt(activity,"AGENT_LEFT"); return;
        }
        fields(payload,Set.of("offerId"));
        require(state.getNpcOrder().id().equals(identifier(payload,"offerId")),409,"STALE_ORDER_OFFER");
        require(!state.getNpcAcceptedBy().contains(actor),409,"ORDER_ALREADY_DECIDED");
        if (type.equals("ACCEPT_ORDER")) state.getNpcAcceptedBy().add(actor);
        boolean approved=state.getNpcAcceptedBy().size()==2;
        eventAtMonth(activity,"NPC_ORDER_DECISION","USER_AGENT",actor,
                approved?"PARTNERS_APPROVED":type.equals("DECLINE_ORDER")?"EXECUTED":"PROPOSED",
                Map.of("action",action),1);
        if (approved || type.equals("DECLINE_ORDER")) {
            state.setNpcWindowOpen(false);
            finishMonths(activity,state,approved?state.getNpcOrder():null);
        }
    }
    private void reduceFranchise(Activity activity,PlaygroundRoomState state,long actor,
                                 String type,JsonNode payload,JsonNode action) {
        V5FranchiseWindow window=state.getFranchiseWindow();
        if (type.equals("LEAVE")) {
            fields(payload,Set.of("reason"));text(payload,"reason",300);
            eventAtMonth(activity,"FRANCHISE_DECISION","USER_AGENT",actor,"INTERRUPTED",
                    Map.of("action",action),franchiseEventMonth(state));
            interrupt(activity,"AGENT_LEFT");return;
        }
        boolean proposal=type.equals("PROPOSE_FRANCHISE") || type.equals("COUNTER_FRANCHISE");
        fields(payload,proposal?Set.of("offerId","offerVersion","decision")
                :Set.of("offerId","offerVersion"));
        JsonNode versionNode=payload.get("offerVersion");
        require(versionNode!=null && versionNode.isIntegralNumber() && versionNode.asInt()==1,
                400,"INVALID_FRANCHISE_VERSION");
        String offerId=identifier(payload,"offerId");
        try {
            java.time.Instant instant=now().toInstant(ZoneOffset.UTC);
            V5FranchiseWindow next=switch (type) {
                case "CHECK_FRANCHISE_TERMS" -> window.investigate(actor,offerId,1,V5FranchiseOffer.Investigation.TERMS,instant);
                case "CHECK_FRANCHISE_STORES" -> window.investigate(actor,offerId,1,V5FranchiseOffer.Investigation.STORES,instant);
                case "CHECK_FRANCHISE_SUPPLY" -> window.investigate(actor,offerId,1,V5FranchiseOffer.Investigation.SUPPLY,instant);
                case "PROPOSE_FRANCHISE" -> window.propose(actor,offerId,1,
                        V5FranchiseWindow.Decision.valueOf(text(payload,"decision",10)),instant);
                case "COUNTER_FRANCHISE" -> window.counter(actor,offerId,1,
                        V5FranchiseWindow.Decision.valueOf(text(payload,"decision",10)),instant);
                case "ACCEPT_FRANCHISE" -> window.accept(actor,offerId,1,instant);
                case "DECLINE_FRANCHISE" -> window.decline(actor,offerId,1,instant);
                default -> throw new IllegalArgumentException("FRANCHISE_ACTION_NOT_ALLOWED");
            };
            state.setFranchiseWindow(next);
            if (type.startsWith("CHECK_"))
                eventAtMonth(activity,"FRANCHISE_INVESTIGATION","USER_AGENT",actor,"EXECUTED",
                        Map.of("action",action),
                        franchiseEventMonth(state));
            else eventAtMonth(activity,"FRANCHISE_DECISION","USER_AGENT",actor,
                    next.phase()==V5FranchiseWindow.Phase.CLOSED?"EXECUTED":"PROPOSED",
                    Map.of("action",action),franchiseEventMonth(state));
            if (next.phase()==V5FranchiseWindow.Phase.CLOSED) {
                eventAtMonth(activity,"FRANCHISE_RESOLUTION","SYSTEM",null,"EXECUTED",
                        Map.of("offerId",offerId,"resolution",next.resolution().name()),
                        franchiseEventMonth(state));
                if (state.getGame().operatedMonths()>0) continueV5(activity,state,actor);
            }
        } catch (IllegalArgumentException error) {
            throw new BusinessException(400,error.getMessage());
        }
    }
    private void fallbackFranchise(Activity activity,PlaygroundRoomState state,
                                   V5FranchiseWindow.Resolution why) {
        V5FranchiseWindow window=state.getFranchiseWindow();
        state.setFranchiseWindow(window.miss(why));
        eventAtMonth(activity,"FRANCHISE_RESOLUTION","SYSTEM",null,"EXECUTED",
                Map.of("offerId",window.offerId(),"resolution",why.name()),
                franchiseEventMonth(state));
        if (state.getGame().operatedMonths()>0) continueV5(activity,state,window.proposerAgentId());
    }
    private boolean openV5Window(PlaygroundRoomState state) {
        return state.getContractVersion()==5 && state.getV5Window()!=null
                && state.getV5Window().phase()!=V5MonthlyWindow.Phase.CLOSED;
    }
    private boolean openV6Window(PlaygroundRoomState state) {
        return state.getRuleVersion().equals("0.9") && state.getV6Window()!=null
                && state.getV6Window().phase()!=V6MonthlyWindow.Phase.CLOSED;
    }
    private boolean openAnnualWindow(PlaygroundRoomState state) {
        return isAnnual(state) && state.getV6AnnualWindow()!=null
                && state.getV6AnnualWindow().phase()!=V6AnnualWindow.Phase.CLOSED;
    }
    private int franchiseEventMonth(PlaygroundRoomState state) {
        return state.getGame().operatedMonths()==0 ? 0 : state.getFranchiseOffer().appearsMonth();
    }
    private void resumePreopenAfterFallback(Activity activity,PlaygroundRoomState state) {
        if (!activity.getStatus().equals("PLANNING") || state.getContractVersion()!=5
                || !Set.of("0.8","0.9","1.0","1.1").contains(state.getRuleVersion()) || state.getGame().operatedMonths()!=0
                || state.getFranchiseWindow()==null || openFranchiseWindow(state)
                || state.getProposal()!=null) return;
        state.getWindowDecisions().clear();
        long actor=activity.getHostAgentId();
        if (canOfferTask(activity,state,actor)) issueTask(activity,state,actor);
        else interrupt(activity,"WINDOW_BUDGET_EXHAUSTED");
    }
    private boolean openFranchiseWindow(PlaygroundRoomState state) {
        return state.getContractVersion()==5 && state.getFranchiseWindow()!=null
                && state.getFranchiseWindow().phase()!=V5FranchiseWindow.Phase.CLOSED;
    }
    private long franchiseWindowActor(V5FranchiseWindow window) {
        return window.phase()==V5FranchiseWindow.Phase.AWAIT_REPLY
                ?window.partnerAgentId():window.proposerAgentId();
    }
    private long v5WindowActor(V5MonthlyWindow window) {
        return window.phase()==V5MonthlyWindow.Phase.AWAIT_REPLY
                ?window.partnerAgentId():window.proposerAgentId();
    }
    private V5ShopRules.Strategy v5Strategy(PlaygroundRoomState state) {
        JsonNode plan=state.getProposal().path("plan"), product=plan.path("products").get(0);
        return V5StrategyContract.parse(plan.path("strategy"),product.path("quantity").intValue(),
                product.path("priceCoins").intValue(),plan.path("reserveMinor").longValue());
    }
    private MonthlyShopRules.Environment drawV5Environment(int horizonMonths) {
        MonthlyShopRules.Environment drawn=rules.drawEnvironment(horizonMonths,random);
        int demandSeed=random.nextInt(Integer.MAX_VALUE);
        if (horizonMonths!=12) return new MonthlyShopRules.Environment(
                drawn.shock(),drawn.fromMonth(),drawn.throughMonth(),demandSeed);
        // Persist the late shock at signing. Older rooms retain their already drawn months.
        int month=7+random.nextInt(3);
        return new MonthlyShopRules.Environment(drawn.shock(),month,month+1,demandSeed);
    }
    private V5ShopRules.Signal v5Signal(MonthlyShopRules.State game,int month) {
        MonthlyShopRules.Environment environment=game.environment();
        if (month<environment.fromMonth() || month>environment.throughMonth()) return V5ShopRules.Signal.NORMAL;
        return switch (environment.shock()) {
            case NONE -> V5ShopRules.Signal.NORMAL;
            case MARKET_SLOWDOWN -> V5ShopRules.Signal.MARKET_SHIFT;
            case MATERIAL_SURGE -> V5ShopRules.Signal.MATERIAL_SURGE;
            case RENT_RENEWAL -> V5ShopRules.Signal.RENT_RISE;
            case PACKAGING_RULE -> V5ShopRules.Signal.PACKAGING_CHANGE;
            case POWER_OUTAGE -> V5ShopRules.Signal.POWER_OUTAGE;
        };
    }
    private boolean v5DecisionMonth(MonthlyShopRules.State game,int month,String ruleVersion) {
        if (month==2) return true;
        if (game.horizonMonths()!=12) return false;
        if (Set.of("0.8","0.9").contains(ruleVersion) && month==5) return true;
        int shockMonth=game.environment().shock()==MonthlyShopRules.Shock.NONE?8:
                Math.max(3,game.environment().fromMonth());
        return month==(shockMonth==2?8:shockMonth);
    }
    private void continueV5(Activity activity,PlaygroundRoomState state,long previousActor) {
        if (isAnnual(state)) { continueAnnual(activity,state,previousActor); return; }
        while (state.getGame().ending()==MonthlyShopRules.Ending.RUNNING
                && state.getGame().operatedMonths()<activity.getHorizonMonths()) {
            int month=state.getGame().operatedMonths()+1;
            if (state.getRuleVersion().equals("0.7") && franchiseEnabled && activity.getHorizonMonths()==12 && month==3
                    && state.getFranchiseOffer()==null) {
                V5FranchiseOffer offer=V5FranchiseOffer.draw("franchise:"+activity.getId(),month,random);
                state.setFranchiseOffer(offer);
                long proposer=previousActor==activity.getHostAgentId()
                        ?activity.getGuestAgentId():activity.getHostAgentId();
                state.setFranchiseWindow(V5FranchiseWindow.open(offer,proposer,previousActor,
                        min(now().plusMinutes(15),activity.getExpiresAt()).toInstant(ZoneOffset.UTC)));
                state.getWindowDecisions().clear();
                eventAtMonth(activity,"FRANCHISE_PITCH","NPC",null,"PROPOSED",
                        Map.of("offer",offer.publicTerms(),"claimCode","GUARANTEED_CUSTOMERS_UNVERIFIED"),month);
                return;
            }
            V5ShopRules.Signal signal=v5Signal(state.getGame(),month);
            if (!v5DecisionMonth(state.getGame(),month,state.getRuleVersion())) {
                advanceV5AndRecord(activity,state,signal,V5ShopRules.Response.KEEP_IDENTITY);
                eventAtMonth(activity,"MONTHLY_CONTINUITY","SYSTEM",null,"EXECUTED",
                        Map.of("signal",signal.name(),"effectiveResponse","KEEP_IDENTITY","reason","SIGNED_STRATEGY"),month);
                continue;
            }
            // A competitor appears in a quiet decision month; environmental shocks stay server drawn.
            if (signal==V5ShopRules.Signal.NORMAL && !state.getRuleVersion().equals("0.9"))
                signal=Set.of("0.8","0.9").contains(state.getRuleVersion()) && month==5
                        ?V5ShopRules.Signal.SUPPLY_DELAY:V5ShopRules.Signal.COMPETITOR;
            state.setV5Signal(signal);
            long proposer=previousActor==activity.getHostAgentId()?activity.getGuestAgentId():activity.getHostAgentId();
            String triggerEventId=activity.getId()+":"+activity.getNextSequence();
            if (state.getRuleVersion().equals("0.9")) {
                V6ConflictRules.Kind conflict=v6Rules.draw(state.getGame().environment().demandSeed(),
                        month,state.getV6Story());
                state.setV6Conflict(conflict);
                eventAtMonth(activity,"V6_CONFLICT","SYSTEM",null,"PROPOSED",
                        Map.of("triggerEventId",triggerEventId,"kind",conflict.name(),
                                "family",conflict.family().name(),
                                "options",conflict.options().stream().map(V6ConflictRules.Option::code).toList()),month);
            }
            java.time.Instant deadline=min(now().plusMinutes(15),activity.getExpiresAt()).toInstant(ZoneOffset.UTC);
            if (state.getRuleVersion().equals("0.9"))
                state.setV6Window(V6MonthlyWindow.open(triggerEventId,state.getAgreement().proposalId(),
                        month,proposer,previousActor,deadline,state.getV6Conflict()));
            else state.setV5Window(V5MonthlyWindow.open(triggerEventId,state.getAgreement().proposalId(),month,
                    proposer,previousActor,deadline));
            state.getWindowDecisions().clear();
            eventAtMonth(activity,"MONTHLY_SIGNAL","SYSTEM",null,"PROPOSED",
                    Map.of("triggerEventId",triggerEventId,"planVersion",state.getAgreement().proposalId(),
                            "signal",signal.name(),"scriptedCompetitor",signal==V5ShopRules.Signal.COMPETITOR),month);
            if (now().isBefore(activity.getExpiresAt())) return;
            if (openV6Window(state)) {
                state.setV6Window(state.getV6Window().miss(V6MonthlyWindow.Resolution.DEADLINE_FALLBACK));
                settleV6Month(activity,state);
            } else {
                state.setV5Window(state.getV5Window().miss(V5MonthlyWindow.Resolution.DEADLINE_FALLBACK));
                settleV5Month(activity,state);
            }
            return;
        }
        settleV5(activity,state);
    }
    private void continueAnnual(Activity activity,PlaygroundRoomState state,long previousActor) {
        if (state.getGame().ending()!=MonthlyShopRules.Ending.RUNNING
                || state.getGame().operatedMonths()>=activity.getHorizonMonths()) {
            settleV5(activity,state); return;
        }
        int month=state.getGame().operatedMonths()+1;
        V5ShopRules.Signal signal=v5Signal(state.getGame(),month);
        state.setV5Signal(signal);
        V7StoryRules.Incident incident=shopModule(state).nextIncident(
                state.getGame().environment().demandSeed(),month,state.getV6Story(),
                state.getLastExecution(),
                month%3==2 || signal!=V5ShopRules.Signal.NORMAL);
        V6ConflictRules.Kind conflict=incident.kind();
        state.setIncidentCause(incident.cause());
        state.setV6Conflict(conflict);
        long first=previousActor==activity.getHostAgentId()
                ?activity.getGuestAgentId():activity.getHostAgentId();
        String trigger=activity.getId()+":"+activity.getNextSequence();
        java.time.Instant deadline=min(now().plusMinutes(15),activity.getExpiresAt()).toInstant(ZoneOffset.UTC);
        state.setV6AnnualWindow(V6AnnualWindow.open(trigger,month,first,previousActor,deadline,conflict));
        state.getWindowDecisions().clear();
        eventAtMonth(activity,"V6_MONTHLY_BRIEF","SYSTEM",null,"PROPOSED",
                Map.of("triggerEventId",trigger,"month",month,"signal",signal.name(),
                        "priorReport",state.getGame().reports().get(state.getGame().reports().size()-1)),month);
        if (conflict!=null) {
            Map<String,Object> facts=new LinkedHashMap<>();
            facts.put("triggerEventId",trigger); facts.put("kind",conflict.name());
            facts.put("family",conflict.family().name());
            facts.put("options",conflict.options().stream().map(V6ConflictRules.Option::code).toList());
            if (incident.cause()!=null) facts.put("cause",incident.cause());
            eventAtMonth(activity,"V6_CONFLICT","SYSTEM",null,"PROPOSED",facts,month);
        }
        if (now().isBefore(activity.getExpiresAt())) return;
        fallbackAnnualMonth(activity,state,V6AnnualWindow.Resolution.DEADLINE_FALLBACK);
    }
    private void reduceV5Month(Activity activity,PlaygroundRoomState state,long actor,
                               String type,JsonNode payload,JsonNode action) {
        V5MonthlyWindow window=state.getV5Window();
        if (type.equals("LEAVE")) {
            fields(payload,Set.of("reason")); text(payload,"reason",300);
            eventAtMonth(activity,"MONTHLY_DECISION","USER_AGENT",actor,"INTERRUPTED",Map.of("action",action),window.month());
            interrupt(activity,"AGENT_LEFT"); return;
        }
        boolean choiceAction=type.equals("PROPOSE_MONTHLY") || type.equals("COUNTER_MONTHLY");
        fields(payload,choiceAction?Set.of("triggerEventId","planVersion","choice")
                :Set.of("triggerEventId","planVersion"));
        require(window.triggerEventId().equals(identifier(payload,"triggerEventId")),409,"STALE_V5_SIGNAL");
        String version=identifier(payload,"planVersion");
        try {
            java.time.Instant instant=now().toInstant(ZoneOffset.UTC);
            V5MonthlyWindow next;
            if (choiceAction) {
                V5ShopRules.Response choice=V5ShopRules.Response.valueOf(text(payload,"choice",30));
                require(state.getV5Signal()!=V5ShopRules.Signal.NORMAL || state.getV6Conflict()!=null
                        || choice==V5ShopRules.Response.KEEP_IDENTITY,400,"DECISION_WITHOUT_SIGNAL");
                MonthlyShopRules.TradingAdjustment preview=state.getV6Conflict()==null
                        ?MonthlyShopRules.TradingAdjustment.NONE
                        :v6Rules.resolve(state.getV6Conflict(),v6Option(state.getV6Conflict(),choice),
                                state.getV6Story()).immediate();
                shopModule(state).advance(state.getGame(),v5Strategy(state),state.getV5Signal(),choice,null,
                        Set.of("0.7","0.8","0.9").contains(state.getRuleVersion()),preview);
                next=type.equals("PROPOSE_MONTHLY")?window.propose(actor,version,choice,instant)
                        :window.counter(actor,version,choice,instant);
            } else next=type.equals("ACCEPT_MONTHLY")?window.accept(actor,version,instant)
                    :window.decline(actor,version,instant);
            state.setV5Window(next);
        } catch (IllegalArgumentException error) {
            throw new BusinessException(400,error.getMessage());
        }
        eventAtMonth(activity,"MONTHLY_DECISION","USER_AGENT",actor,
                state.getV5Window().phase()==V5MonthlyWindow.Phase.CLOSED?"EXECUTED":"PROPOSED",
                Map.of("action",action),window.month());
        if (state.getV5Window().phase()==V5MonthlyWindow.Phase.CLOSED) settleV5Month(activity,state);
    }
    private V5ShopRules.Response v6Response(V6ConflictRules.Kind kind,String choice) {
        for (int i=0;i<kind.options().size();i++) if (kind.options().get(i).code().equals(choice))
            return V5ShopRules.Response.values()[i];
        throw new IllegalArgumentException("INVALID_V6_CHOICE");
    }
    private void reduceV6Month(Activity activity,PlaygroundRoomState state,long actor,
                               String type,JsonNode payload,JsonNode action) {
        V6MonthlyWindow window=state.getV6Window();
        if (type.equals("LEAVE")) {
            fields(payload,Set.of("reason")); text(payload,"reason",300);
            eventAtMonth(activity,"V6_MONTHLY_DECISION","USER_AGENT",actor,"INTERRUPTED",
                    Map.of("action",action),window.month());
            interrupt(activity,"AGENT_LEFT"); return;
        }
        require(!action.path("publicRationale").asText().isBlank(),400,"V6_REASON_REQUIRED");
        if (type.equals("COUNTER_MONTHLY")) require(action.path("publicRationale").asText().strip().length()>=12,
                400,"V6_COUNTER_REASON_REQUIRED");
        fields(payload,switch (type) {
            case "POSITION_MONTHLY" -> Set.of("triggerEventId","planVersion","tacticCode");
            case "COUNTER_MONTHLY" -> Set.of("triggerEventId","planVersion","tacticCode",
                    "keptFromPartner","concededOwnPoint");
            default -> Set.of("triggerEventId","planVersion");
        });
        require(window.triggerEventId().equals(identifier(payload,"triggerEventId")),409,"STALE_V6_SIGNAL");
        String version=identifier(payload,"planVersion");
        try {
            java.time.Instant instant=now().toInstant(ZoneOffset.UTC);
            V6MonthlyWindow next;
            if (type.equals("POSITION_MONTHLY") || type.equals("COUNTER_MONTHLY")) {
                String choice=text(payload,"tacticCode",40);
                MonthlyShopRules.TradingAdjustment preview=v6Rules.resolve(window.conflict(),choice,
                        state.getV6Story()).immediate();
                V5ShopRules.Response response=v6Response(window.conflict(),choice);
                shopModule(state).advance(state.getGame(),v5Strategy(state),state.getV5Signal(),response,
                        signedFranchise(state,window.month()),true,preview);
                next=type.equals("POSITION_MONTHLY")?window.position(actor,version,choice,instant)
                        :window.counter(actor,version,choice,
                                text(payload,"keptFromPartner",40),
                                text(payload,"concededOwnPoint",40),instant);
            } else next=type.equals("ACCEPT_MONTHLY")?window.accept(actor,version,instant)
                    :window.decline(actor,version,instant);
            state.setV6Window(next);
        } catch (IllegalArgumentException error) {
            throw new BusinessException(400,error.getMessage());
        }
        eventAtMonth(activity,type.equals("POSITION_MONTHLY")?"V6_POSITION":"V6_MONTHLY_DECISION",
                "USER_AGENT",actor,state.getV6Window().phase()==V6MonthlyWindow.Phase.CLOSED
                        ?"EXECUTED":"PROPOSED",Map.of("action",action),window.month());
        if (state.getV6Window().phase()==V6MonthlyWindow.Phase.CLOSED) settleV6Month(activity,state);
    }
    private V6MonthlyPlan annualPlan(JsonNode value,V6ConflictRules.Kind conflict) {
        fields(value,Set.of("productionBand","marketingAction","serviceFocus","incidentResponse"));
        try {
            JsonNode incident=value.get("incidentResponse");
            V6MonthlyPlan plan=new V6MonthlyPlan(
                    V6MonthlyPlan.ProductionBand.valueOf(text(value,"productionBand",30)),
                    V6MonthlyPlan.MarketingAction.valueOf(text(value,"marketingAction",30)),
                    V6MonthlyPlan.ServiceFocus.valueOf(text(value,"serviceFocus",30)),
                    incident==null || incident.isNull()?null:text(value,"incidentResponse",40));
            plan.validateFor(conflict);
            return plan;
        } catch (IllegalArgumentException error) {
            throw new BusinessException(400,error.getMessage());
        }
    }
    private void reduceAnnualMonth(Activity activity,PlaygroundRoomState state,long actor,
                                   String type,JsonNode payload,JsonNode action) {
        V6AnnualWindow window=state.getV6AnnualWindow();
        if (type.equals("LEAVE")) {
            fields(payload,Set.of("reason")); text(payload,"reason",300);
            eventAtMonth(activity,"V6_ANNUAL_DECISION","USER_AGENT",actor,"INTERRUPTED",
                    Map.of("action",action),window.month());
            interrupt(activity,"AGENT_LEFT"); return;
        }
        require(action.path("publicRationale").asText().strip().length()>=12,
                400,"V6_REASON_REQUIRED");
        boolean proposal=type.equals("POSITION_MONTHLY") || type.equals("REPLY_MONTHLY");
        fields(payload,proposal
                ?type.equals("REPLY_MONTHLY")
                    ?Set.of("triggerEventId","targetVersion","plan","claimsRevision","focusField","reasonFocus")
                    :Set.of("triggerEventId","targetVersion","plan","reasonFocus")
                :Set.of("triggerEventId","targetVersion","reasonFocus"));
        require(window.triggerEventId().equals(identifier(payload,"triggerEventId")),409,"STALE_V6_SIGNAL");
        String target=identifier(payload,"targetVersion");
        String focus=text(payload,"reasonFocus",24);
        require(Set.of("CASH","FULFILLMENT","SUPPLY","LEASE","CUSTOMERS").contains(focus),
                400,"INVALID_V6_REASON_FOCUS");
        try {
            V6AnnualWindow next;
            java.time.Instant instant=now().toInstant(ZoneOffset.UTC);
            if (proposal) {
                V6MonthlyPlan plan=annualPlan(payload.get("plan"),window.conflict());
                V5ShopRules.Response response=window.conflict()==null
                        ?V5ShopRules.Response.KEEP_IDENTITY
                        :v6Response(window.conflict(),plan.incidentResponse());
                MonthlyShopRules.TradingAdjustment preview=window.conflict()==null
                        ?v6Rules.advanceEcho(state.getV6Story()).adjustment()
                        :v6Rules.resolve(window.conflict(),plan.incidentResponse(),state.getV6Story()).immediate();
                shopModule(state).advance(state.getGame(),plan.strategy(v5Strategy(state)),state.getV5Signal(),
                        response,signedFranchise(state,window.month()),true,preview);
                if (type.equals("REPLY_MONTHLY")) {
                    String field=text(payload,"focusField",24);
                    require(Set.of("productionBand","marketingAction","serviceFocus","incidentResponse")
                            .contains(field),400,"INVALID_V6_FOCUS_FIELD");
                    require(!json.valueToTree(window.latestOffer()).path(field)
                                    .equals(json.valueToTree(window.ownLastPlan(actor)).path(field)),
                            400,"V6_FOCUS_NOT_DISPUTED");
                    JsonNode revision=payload.get("claimsRevision");
                    require(revision!=null && revision.isBoolean(),400,"INVALID_V6_REVISION");
                    next=window.reply(actor,target,plan,revision.booleanValue(),instant);
                } else next=window.position(actor,target,plan,instant);
            } else next=switch (type) {
                case "ACCEPT_MONTHLY" -> window.accept(actor,target,instant);
                case "DECLINE_MONTHLY" -> window.decline(actor,target,instant);
                case "RETRACT_MONTHLY" -> window.retract(actor,target,instant);
                default -> throw new IllegalArgumentException("V6_ACTION_NOT_ALLOWED");
            };
            state.setV6AnnualWindow(next);
        } catch (IllegalArgumentException error) {
            throw new BusinessException(400,error.getMessage());
        }
        eventAtMonth(activity,"V6_ANNUAL_DECISION","USER_AGENT",actor,
                state.getV6AnnualWindow().phase()==V6AnnualWindow.Phase.CLOSED?"EXECUTED":"PROPOSED",
                Map.of("action",action,"offerRevision",window.offerRevision()),window.month());
        if (state.getV6AnnualWindow().phase()==V6AnnualWindow.Phase.CLOSED)
            settleAnnualMonth(activity,state);
    }
    private void fallbackV5Month(Activity activity,PlaygroundRoomState state,V5MonthlyWindow.Resolution reason) {
        state.setV5Window(state.getV5Window().miss(reason));
        settleV5Month(activity,state);
    }
    private void resumeV5AfterFallback(Activity activity,PlaygroundRoomState state,V5MonthlyWindow.Resolution reason) {
        while (activity.getStatus().equals("PLANNING") && openV5Window(state)) {
            long next=v5WindowActor(state.getV5Window());
            if (canOfferTask(activity,state,next)) { issueTask(activity,state,next); return; }
            fallbackV5Month(activity,state,reason);
        }
    }
    private void settleV5Month(Activity activity,PlaygroundRoomState state) {
        V5MonthlyWindow window=state.getV5Window();
        V6ConflictRules.Kind conflict=state.getV6Conflict();
        boolean approved=window.resolution()==V5MonthlyWindow.Resolution.PARTNERS_APPROVED;
        String tactic=conflict==null?null:approved?v6Option(conflict,window.effectiveResponse()):"SIGNED_STRATEGY";
        V5ShopRules.MonthResult result=advanceV5AndRecord(activity,state,state.getV5Signal(),window.effectiveResponse());
        eventAtMonth(activity,"MONTHLY_RESOLUTION","SYSTEM",null,"EXECUTED",
                Map.of("triggerEventId",window.triggerEventId(),"planVersion",window.planVersion(),
                        "resolution",window.resolution().name(),"effectiveResponse",window.effectiveResponse().name(),
                        "marketingSpentMinor",result.marketingSpentMinor(),"addedBuyers",result.addedBuyers(),
                        "budgetGuardTriggered",result.budgetGuardTriggered()),window.month());
        if (conflict!=null) eventAtMonth(activity,"V6_CONFLICT_RESOLUTION","SYSTEM",null,"EXECUTED",
                Map.of("triggerEventId",window.triggerEventId(),"kind",conflict.name(),
                        "selectedOption",tactic,"resolution",window.resolution().name(),
                        "nextTrust",state.getV6Story().trust(),"nextSupply",state.getV6Story().supply(),
                        "nextRentSurchargeCoins",state.getV6Story().rentSurchargeCoins()),window.month());
        continueV5(activity,state,window.proposerAgentId());
    }
    private void settleV6Month(Activity activity,PlaygroundRoomState state) {
        V6MonthlyWindow window=state.getV6Window();
        String tactic=window.effectiveChoice()==null?"SIGNED_STRATEGY":window.effectiveChoice();
        V5ShopRules.Response response=window.effectiveChoice()==null
                ?V5ShopRules.Response.KEEP_IDENTITY:v6Response(window.conflict(),window.effectiveChoice());
        V5ShopRules.MonthResult result=advanceV5AndRecord(activity,state,state.getV5Signal(),response);
        eventAtMonth(activity,"V6_CONFLICT_RESOLUTION","SYSTEM",null,"EXECUTED",
                Map.ofEntries(Map.entry("triggerEventId",window.triggerEventId()),
                        Map.entry("kind",window.conflict().name()),Map.entry("selectedOption",tactic),
                        Map.entry("resolution",window.resolution().name()),
                        Map.entry("firstPosition",window.firstPosition()==null?"NONE":window.firstPosition()),
                        Map.entry("secondPosition",window.secondPosition()==null?"NONE":window.secondPosition()),
                        Map.entry("counter",window.counter()==null?"NONE":window.counter()),
                        Map.entry("replyCounter",window.replyCounter()==null?"NONE":window.replyCounter()),
                        Map.entry("counterRounds",window.counterRounds()),
                        Map.entry("nextTrust",state.getV6Story().trust()),
                        Map.entry("nextSupply",state.getV6Story().supply()),
                        Map.entry("nextRentSurchargeCoins",state.getV6Story().rentSurchargeCoins())),window.month());
        eventAtMonth(activity,"MONTHLY_RESOLUTION","SYSTEM",null,"EXECUTED",
                Map.of("triggerEventId",window.triggerEventId(),"planVersion",window.planVersion(),
                        "resolution",window.resolution().name(),"effectiveResponse",response.name(),
                        "marketingSpentMinor",result.marketingSpentMinor(),"addedBuyers",result.addedBuyers(),
                        "budgetGuardTriggered",result.budgetGuardTriggered()),window.month());
        continueV5(activity,state,window.firstAgentId());
    }
    private void fallbackV6Month(Activity activity,PlaygroundRoomState state,V6MonthlyWindow.Resolution reason) {
        state.setV6Window(state.getV6Window().miss(reason));
        settleV6Month(activity,state);
    }
    private void resumeV6AfterFallback(Activity activity,PlaygroundRoomState state,V6MonthlyWindow.Resolution reason) {
        while (activity.getStatus().equals("PLANNING") && openV6Window(state)) {
            long actor=state.getV6Window().currentActor();
            if (canOfferTask(activity,state,actor)) { issueTask(activity,state,actor); return; }
            fallbackV6Month(activity,state,reason);
        }
    }
    private void settleAnnualMonth(Activity activity,PlaygroundRoomState state) {
        V6AnnualWindow window=state.getV6AnnualWindow();
        V6MonthlyPlan plan=window.effectivePlan();
        V5ShopRules.Response response=plan==null || window.conflict()==null
                ?V5ShopRules.Response.KEEP_IDENTITY
                :v6Response(window.conflict(),plan.incidentResponse());
        V5ShopRules.MonthResult result=advanceV5AndRecord(activity,state,state.getV5Signal(),response);
        Map<String,Object> facts=new LinkedHashMap<>();
        facts.put("triggerEventId",window.triggerEventId()); facts.put("month",window.month());
        facts.put("resolution",window.resolution().name());
        facts.put("effectiveResponse",response.name());
        facts.put("effectivePlan",plan==null?"SIGNED_FALLBACK":plan);
        facts.put("firstPlan",window.firstPlan()); facts.put("secondPlan",window.secondPlan());
        facts.put("firstReply",window.firstReply()); facts.put("secondReply",window.secondReply());
        facts.put("marketingSpentMinor",result.marketingSpentMinor());
        eventAtMonth(activity,"V6_ANNUAL_RESOLUTION","SYSTEM",null,"EXECUTED",facts,window.month());
        continueAnnual(activity,state,window.firstAgentId());
    }
    private void fallbackAnnualMonth(Activity activity,PlaygroundRoomState state,V6AnnualWindow.Resolution why) {
        state.setV6AnnualWindow(state.getV6AnnualWindow().miss(why));
        settleAnnualMonth(activity,state);
    }
    private void resumeAnnualAfterFallback(Activity activity,PlaygroundRoomState state,V6AnnualWindow.Resolution why) {
        while (activity.getStatus().equals("PLANNING") && openAnnualWindow(state)) {
            long actor=state.getV6AnnualWindow().currentActor();
            if (canOfferTask(activity,state,actor)) { issueTask(activity,state,actor); return; }
            fallbackAnnualMonth(activity,state,why);
        }
    }
    private V5FranchiseOffer signedFranchise(PlaygroundRoomState state,int month) {
        V5FranchiseWindow window=state.getFranchiseWindow();
        return window!=null && window.resolution()==V5FranchiseWindow.Resolution.SIGNED
                && month>=state.getFranchiseOffer().appearsMonth()?state.getFranchiseOffer():null;
    }
    private String v6Option(V6ConflictRules.Kind conflict,V5ShopRules.Response response) {
        return conflict.options().get(switch (response) {
            case KEEP_IDENTITY -> 0;
            case PROMOTE -> 1;
            case TEMPORARY_PIVOT -> 2;
        }).code();
    }
    private V5ShopRules.MonthResult advanceV5AndRecord(Activity activity,PlaygroundRoomState state,
                                                        V5ShopRules.Signal signal,V5ShopRules.Response response) {
        MonthlyShopRules.State before=state.getGame();
        V5FranchiseOffer signed=signedFranchise(state,before.operatedMonths()+1);
        MonthlyShopRules.TradingAdjustment external=MonthlyShopRules.TradingAdjustment.NONE;
        V6ConflictRules.StoryState nextStory=null;
        boolean currentConflict=isV6(state) && state.getV6Conflict()!=null
                && ((state.getV6Window()!=null && state.getV6Window().month()==before.operatedMonths()+1)
                    || (state.getV5Window()!=null && state.getV5Window().month()==before.operatedMonths()+1)
                    || (state.getV6AnnualWindow()!=null && state.getV6AnnualWindow().month()==before.operatedMonths()+1));
        if (isV6(state)) {
            boolean approved=currentConflict && ((state.getV6Window()!=null
                    && Set.of(V6MonthlyWindow.Resolution.INDEPENDENT_CONSENSUS,
                            V6MonthlyWindow.Resolution.COUNTER_ACCEPTED).contains(state.getV6Window().resolution()))
                    || (state.getV6AnnualWindow()!=null
                            && state.getV6AnnualWindow().effectivePlan()!=null
                            && state.getV6AnnualWindow().month()==before.operatedMonths()+1)
                    || (state.getV5Window()!=null
                            && state.getV5Window().resolution()==V5MonthlyWindow.Resolution.PARTNERS_APPROVED));
            if (approved) {
                V6ConflictRules.Resolution resolved=v6Rules.resolve(state.getV6Conflict(),
                        state.getV6AnnualWindow()!=null && isAnnual(state)
                                ?state.getV6AnnualWindow().effectivePlan().incidentResponse()
                                :state.getV6Window()!=null?state.getV6Window().effectiveChoice()
                                    :v6Option(state.getV6Conflict(),response),state.getV6Story());
                external=resolved.immediate(); nextStory=resolved.next();
            } else {
                V6ConflictRules.Echo echo=v6Rules.advanceEcho(state.getV6Story());
                external=echo.adjustment(); nextStory=echo.next();
                if (currentConflict) nextStory=new V6ConflictRules.StoryState(nextStory.trust(),
                        nextStory.supply(),nextStory.rentSurchargeCoins(),nextStory.echoMonths(),
                        state.getV6Conflict());
            }
        }
        V5ShopRules.Strategy strategy=v5Strategy(state);
        if (isAnnual(state) && state.getV6AnnualWindow()!=null
                && state.getV6AnnualWindow().month()==before.operatedMonths()+1
                && state.getV6AnnualWindow().effectivePlan()!=null)
            strategy=state.getV6AnnualWindow().effectivePlan().strategy(strategy);
        V5ShopRules.MonthResult result=shopModule(state).advance(before,strategy,signal,response,signed,
                Set.of("0.7","0.8","0.9","1.0","1.1").contains(state.getRuleVersion()),external);
        if (state.getRuleVersion().equals("1.1"))
            state.setLastExecution(shopModule(state).executedPlan(before,result,
                    state.getV6AnnualWindow()==null?null:state.getV6AnnualWindow().effectivePlan(),
                    v5Strategy(state).produceUnits()));
        if (nextStory!=null) state.setV6Story(nextStory);
        if (currentConflict) state.setV6Conflict(null);
        state.setGame(result.game());
        MonthlyShopRules.MonthlyReport report=state.getGame().reports().get(state.getGame().reports().size()-1);
        if (state.getGame().reports().size()>before.reports().size())
            event(activity,"MONTH_REPORT","SYSTEM",null,"EXECUTED",report);
        else if (!before.reports().get(before.reports().size()-1).equals(report))
            event(activity,"MONTH_REPORT_ADJUSTMENT","SYSTEM",null,"EXECUTED",report);
        return result;
    }
    private void settleV5(Activity activity,PlaygroundRoomState state) {
        event(activity,"SETTLEMENT","SYSTEM",null,"EXECUTED",summaryFor(state));
        if (isAnnual(state)) advancePartnerReviews(activity,state);
        else activity.setStatus("SETTLED");
    }
    private void advancePartnerReviews(Activity activity,PlaygroundRoomState state) {
        for (long actor:List.of(activity.getHostAgentId(),activity.getGuestAgentId())) {
            if (state.getPartnerReviews().containsKey(actor)) continue;
            state.setReviewPendingAgent(actor);
            if (canOfferTask(activity,state,actor)) { issueTask(activity,state,actor); return; }
            skipPartnerReview(activity,state,actor,"BUDGET_OR_DEADLINE");
        }
        state.setReviewPendingAgent(null);
        activity.setStatus("SETTLED");
    }
    private void skipPartnerReview(Activity activity,PlaygroundRoomState state,long actor,String reason) {
        state.getPartnerReviews().put(actor,json.nullNode());
        eventAtMonth(activity,"PARTNER_REVIEW_SKIPPED","SYSTEM",actor,"EXECUTED",
                Map.of("reason",reason),state.getGame().operatedMonths());
    }
    private List<ObjectNode> partnerReviewEvidence(Activity activity,long reviewer) {
        long partner=reviewer==activity.getHostAgentId()?activity.getGuestAgentId():activity.getHostAgentId();
        List<ObjectNode> evidence=new ArrayList<>();
        long after=0;
        while (true) {
            List<String> page=store.events(activity.getId(),after);
            if (page.isEmpty()) break;
            for (String serialized:page) {
                JsonNode item=read(serialized,JsonNode.class);
                if (item.path("facts").path("actorId").asText().equals("agent:"+partner)) {
                    String move=item.path("facts").path("action").path("actionType").asText();
                    if (Set.of("PROPOSE_PLAN","COUNTER_PLAN","ACCEPT_PLAN","POSITION_MONTHLY",
                            "REPLY_MONTHLY","ACCEPT_MONTHLY","DECLINE_MONTHLY","RETRACT_MONTHLY")
                            .contains(move)) evidence.add(json.createObjectNode()
                                    .put("eventId",item.path("eventId").asText())
                                    .put("month",item.path("virtualMonth").asInt()).put("move",move));
                }
                after=item.path("sequence").asLong();
            }
            if (page.size()<50) break;
        }
        return evidence.subList(Math.max(0,evidence.size()-6),evidence.size());
    }
    private OddShopModule shopModule(PlaygroundRoomState state) {
        return gameModules.require(new GameKey("ODD_SHOP", state.getRuleVersion()), OddShopModule.class);
    }
    private MonthlyShopRules.Summary summaryFor(PlaygroundRoomState state) {
        if (!isV6(state)) return shopModule(state).summarize(state.getGame());
        V6FoundingAgreement agreement=V6FoundingContract.parse(
                state.getProposal().path("plan").path("foundingAgreement"));
        return shopModule(state).summarize(state.getGame(),agreement);
    }
    private boolean canOfferTask(Activity activity,PlaygroundRoomState state,long actor) {
        Participation permission=permit(actor); Seat seat=seat(activity,actor);
        store.ensureDaily(actor,now().toLocalDate());
        return remainingDecisions(activity,actor)>0 && windowRemaining(state,actor)>0
                && seat.getAttemptsUsed()<attemptLimit(activity,permission)
                && store.lockDaily(actor,now().toLocalDate())<permission.getMaxDailyAttempts()
                && now().plusMinutes(1).isBefore(activity.getExpiresAt());
    }
    private MonthlyShopRules.Plan standingPlan(PlaygroundRoomState state) {
        JsonNode plan=state.getProposal().path("plan"), product=plan.path("products").get(0);
        return new MonthlyShopRules.Plan(product.path("quantity").intValue(),
                product.path("priceCoins").intValue(),plan.path("reserveMinor").longValue());
    }
    private Map<String,Object> offerView(MonthlyShopRules.NpcOrder offer) {
        return Map.of("offerId",offer.id(),"npcRole","NIGHT_MARKET_BUYER","dueMonth",offer.dueMonth(),
                "quantity",offer.quantity(),"unitPriceCoins",offer.unitPriceCoins(),"npcBudgetCoins",offer.npcBudgetCoins());
    }
    private void advanceAndRecord(Activity activity,PlaygroundRoomState state,MonthlyShopRules.Plan plan,MonthlyShopRules.NpcOrder order) {
        MonthlyShopRules.State before=state.getGame();
        state.setGame(rules.advanceMonth(before,plan,order));
        MonthlyShopRules.MonthlyReport report=state.getGame().reports().get(state.getGame().reports().size()-1);
        // A failure to open the next month only adjusts the last real month's report.
        if (report.month()==state.getGame().operatedMonths() && state.getGame().failedOpeningMonth()==null)
            event(activity,"MONTH_REPORT","SYSTEM",null,"EXECUTED",report);
        else if (!before.reports().get(before.reports().size()-1).equals(report))
            event(activity,"MONTH_REPORT_ADJUSTMENT","SYSTEM",null,"EXECUTED",report);
    }
    private void finishMonths(Activity activity,PlaygroundRoomState state,MonthlyShopRules.NpcOrder order) {
        MonthlyShopRules.Plan plan=standingPlan(state);
        while (state.getGame().ending()==MonthlyShopRules.Ending.RUNNING) {
            int nextMonth=state.getGame().operatedMonths()+1;
            advanceAndRecord(activity,state,plan,nextMonth==2?order:null);
        }
        activity.setStatus("SETTLED"); event(activity,"SETTLEMENT","SYSTEM",null,"EXECUTED",summaryFor(state));
    }
    private void validatePlan(JsonNode plan,PlaygroundRoomState state,Activity activity,long actor) {
        fields(plan,isV6(state)
                ?Set.of("shopName","venture","strategy","products","reserveMinor","contributions","foundingAgreement")
                :state.getContractVersion()==5
                ?Set.of("shopName","venture","strategy","products","reserveMinor","contributions")
                :state.getContractVersion()==4 && plan.has("venture")
                    ?Set.of("shopName","venture","products","reserveMinor","contributions")
                    :state.getContractVersion()>=3?Set.of("shopName","products","reserveMinor","contributions")
                    :Set.of("shopName","products","reserveMinor"));
        require(!text(plan,"shopName",80).isBlank(),400,"INVALID_PLAN");
        if (plan.has("venture")) {
            JsonNode venture=plan.get("venture");
            fields(venture,Set.of("concept","audience","experience","marketing"));
            for (String key:List.of("concept","audience","experience","marketing"))
                require(!text(venture,key,160).isBlank(),400,"INVALID_PLAN");
        }
        JsonNode products=plan.get("products"); require(products.isArray() && products.size()==1,400,"UNSUPPORTED_PRODUCTS");
        JsonNode product=products.get(0); fields(product,Set.of("sku","quantity","priceCoins"));
        require(text(product,"sku",20).equals("STANDARD"),400,"UNSUPPORTED_PRODUCTS");
        require(product.get("quantity").isIntegralNumber() && product.get("quantity").canConvertToInt()
                && product.get("priceCoins").isIntegralNumber() && product.get("priceCoins").canConvertToInt()
                && plan.get("reserveMinor").isIntegralNumber() && plan.get("reserveMinor").canConvertToLong(),400,"INVALID_PLAN");
        try { new MonthlyShopRules.Plan(product.get("quantity").intValue(),product.get("priceCoins").intValue(),plan.get("reserveMinor").longValue()); }
        catch (IllegalArgumentException error) { throw new BusinessException(400,"INVALID_PLAN"); }
        if (state.getContractVersion()==5) try {
            V5StrategyContract.parse(plan.get("strategy"),product.get("quantity").intValue(),
                    product.get("priceCoins").intValue(),plan.get("reserveMinor").longValue());
        } catch (IllegalArgumentException error) { throw new BusinessException(400,"INVALID_V5_STRATEGY"); }
        if (isV6(state)) try {
            V6FoundingContract.parse(plan.get("foundingAgreement"));
        } catch (IllegalArgumentException error) { throw new BusinessException(400,"INVALID_FOUNDING_AGREEMENT"); }
        if (state.getContractVersion()>=3) validateContributions(plan.get("contributions"),state,activity,actor);
    }
    private void validateContributions(JsonNode entries,PlaygroundRoomState state,Activity activity,long actor) {
        require(entries!=null && entries.isArray() && entries.size()>=1 && entries.size()<=2,400,"INVALID_CONTRIBUTIONS");
        Set<Long> origins=new HashSet<>();
        JsonNode previous=state.getProposal()==null?null:state.getProposal().path("plan").path("contributions");
        for (JsonNode entry:entries) {
            fields(entry,Set.of("sourceAgentId","sourceFieldId","placement","label","sourceEventId"));
            JsonNode id=entry.get("sourceAgentId");
            require(id.isIntegralNumber() && id.canConvertToLong(),400,"INVALID_CONTRIBUTIONS");
            long source=id.longValue();
            require((source==activity.getHostAgentId() || source==activity.getGuestAgentId()) && origins.add(source),400,"INVALID_CONTRIBUTIONS");
            require(Set.of("PRODUCT","SPACE","SERVICE").contains(text(entry,"placement",20))
                    && !text(entry,"label",state.getContractVersion()>=4?160:80).isBlank(),400,"INVALID_CONTRIBUTIONS");
            JsonNode field=entry.get("sourceFieldId"), eventId=entry.get("sourceEventId");
            require(field!=null && (field.isNull() || field.isTextual()) && eventId!=null
                    && (eventId.isNull() || eventId.isTextual()),400,"INVALID_CONTRIBUTIONS");
            JsonNode old=null;
            if (previous!=null && previous.isArray()) for (JsonNode item:previous)
                if (item.path("sourceAgentId").asLong(-1)==source) old=item;
            if (source!=actor || !eventId.isNull()) {
                require(old!=null && old.equals(entry) && !eventId.isNull(),400,"FORGED_CONTRIBUTION_SOURCE");
            } else {
                OwnerBrief brief=state.getOwnerBriefs().get(source);
                Map<String,Object> shared=PartnerDisclosure.project(brief,brief.partnerShareFields());
                Set<String> allowedSourceFields=Boolean.TRUE.equals(brief.agentMayReferenceOwnFields())
                        ?Set.copyOf(brief.disclosableFields()):shared.keySet();
                try { PartnerDisclosure.requireNewContribution(actor,source,field.isNull()?null:field.textValue(),allowedSourceFields); }
                catch (IllegalArgumentException error) { throw new BusinessException(400,error.getMessage()); }
            }
        }
        if (previous==null) require(origins.equals(Set.of(actor)),400,"FORGED_CONTRIBUTION_SOURCE");
    }
    private JsonNode semanticPlan(JsonNode plan) {
        ObjectNode terms=(ObjectNode)plan.deepCopy();
        JsonNode entries=terms.path("contributions");
        if (entries.isArray()) for (JsonNode entry:entries) ((ObjectNode)entry).remove("sourceEventId");
        return terms;
    }
    private ObjectNode taskView(Activity activity,PlaygroundRoomState state,Task task,String token) {
        Seat seat=seat(activity,task.getAgentId()); Participation permission=permit(task.getAgentId());
        ObjectNode result=json.createObjectNode().put("taskId",task.getId()+"").put("activityId",activity.getId()+"")
                .put("actorId","agent:"+task.getAgentId()).put("activityType","ODD_SHOP")
                .put("mode",activity.getHorizonMonths()==12?"FULL":"SHORT").put("horizonMonths",activity.getHorizonMonths())
                .put("contractVersion",state.getContractVersion()).put("ruleVersion",state.getRuleVersion())
                .put("templateVersion",isV6(state)?4:state.getContractVersion()==5?3:state.getContractVersion()==4?2:1).put("phase",task.getPhase())
                .put("actorSource","USER_AGENT").put("permissionVersion",permission.getVersion())
                .put("expiresAt",utc(min(task.getExpiresAt(),task.getLeaseExpiresAt()))).put("leaseToken",token);
        if (task.getAttemptId()==null) result.putNull("attemptId"); else result.put("attemptId",task.getAttemptId()+"");
        List<String> taskActions=new ArrayList<>(allowed(state));
        if (isAnnual(state) && task.getPhase().equals("PLANNING") && state.getProposal()!=null
                && windowRemaining(state,task.getAgentId())==1)
            taskActions.remove("COUNTER_PLAN");
        result.set("allowedActions",json.valueToTree(taskActions));
        ObjectNode visible=json.createObjectNode().put("cashMinor",state.getGame().cashMinor()).put("virtualMonth",state.getGame().operatedMonths());
        visible.set("proposal",state.getProposal()==null?json.nullNode():state.getProposal());
        if (isV6(state) && state.getGame().operatedMonths()==0)
            visible.set("foundingChoices",json.valueToTree(Map.of(
                    "totalCapitalCoins",V6FoundingAgreement.TOTAL_CAPITAL_COINS,
                    "capitalChoices",List.of(0,50,80,100,120,150,200),
                    "hostProfitPercentChoices",List.of(30,40,50,60,70),
                    "duties",List.of("serviceLead","supplyLead","communityLead"),
                    "seats",List.of("HOST","GUEST"))));
        OwnerBrief ownBrief=state.getOwnerBriefs().get(task.getAgentId());
        if (state.getContractVersion()>=3) {
            LinkedHashSet<String> ownVisible=new LinkedHashSet<>(ownBrief.disclosableFields());
            ownVisible.addAll(ownBrief.partnerShareFields());
            ObjectNode ownProjection=json.valueToTree(PartnerDisclosure.project(ownBrief,List.copyOf(ownVisible)));
            ownProjection.set("partnerShareFields",json.valueToTree(ownBrief.partnerShareFields()));
            if (Boolean.TRUE.equals(ownBrief.agentMayReferenceOwnFields()))
                ownProjection.put("agentMayReferenceOwnFields",true);
            if (ownBrief.ownerMessage()!=null && !ownBrief.ownerMessage().isBlank())
                ownProjection.put("ownerMessage",ownBrief.ownerMessage());
            visible.set("ownerBrief",ownProjection);
        } else visible.set("ownerBrief",json.valueToTree(ownBrief));
        if (state.getContractVersion()>=3) {
            long partner=task.getAgentId()==activity.getHostAgentId()?activity.getGuestAgentId():activity.getHostAgentId();
            OwnerBrief partnerBrief=state.getOwnerBriefs().get(partner);
            ObjectNode shared=json.createObjectNode().put("sourceAgentId",partner);
            shared.set("fields",json.valueToTree(PartnerDisclosure.project(partnerBrief,partnerBrief.partnerShareFields())));
            visible.set("partnerBrief",shared);
        }
        ArrayNode context=json.createArrayNode();
        String retryHint=null;
        boolean awaitingV5Retry=state.getContractVersion()==5
                && state.getConsecutiveModelFailures().getOrDefault(task.getAgentId(),0)==1;
        // Keep recent actual negotiations inside the bounded model input.
        // The signed proposal, prior ledger and carryover state are projected separately.
        for (String serialized:store.events(activity.getId(),Math.max(0,activity.getNextSequence()-21))) {
            JsonNode source=read(serialized,JsonNode.class);
            if (state.getReviewPendingAgent()!=null && Set.of("PARTNER_REVIEW","PARTNER_REVIEW_SKIPPED")
                    .contains(source.path("kind").asText())) continue;
            JsonNode action=source.path("facts").path("action");
            if (openAnnualWindow(state) && state.getV6AnnualWindow().phase()==V6AnnualWindow.Phase.AWAIT_SECOND
                    && source.path("kind").asText().equals("V6_ANNUAL_DECISION")
                    && action.path("payload").path("triggerEventId").asText()
                            .equals(state.getV6AnnualWindow().triggerEventId())) continue;
            if (openV6Window(state) && state.getV6Window().phase()==V6MonthlyWindow.Phase.AWAIT_SECOND
                    && source.path("kind").asText().equals("V6_POSITION")
                    && action.path("payload").path("triggerEventId").asText()
                            .equals(state.getV6Window().triggerEventId())) continue;
            String summary=action.has("publicRationale")?action.get("publicRationale").asText():source.get("summary").asText();
            context.add(json.createObjectNode().put("eventId",source.get("eventId").asText())
                    .put("actorSource",source.get("actorSource").asText()).put("summary",summary));
            JsonNode facts=source.path("facts");
            if (awaitingV5Retry && source.path("kind").asText().equals("AGENT_FAILURE")
                    && facts.path("actorId").asText().equals("agent:"+task.getAgentId())
                    && facts.path("retryScheduled").asBoolean()
                    && Set.of("V5_STRATEGY_ENUM","PLAN_CONTRIBUTIONS","ACTION_SHAPE")
                            .contains(facts.path("formatHint").asText()))
                retryHint=facts.path("formatHint").asText();
        }
        visible.set("events",context);
        if (state.getContractVersion()==5) {
            if (retryHint==null) visible.putNull("retryHint"); else visible.put("retryHint",retryHint);
            V5FranchiseWindow franchise=state.getFranchiseWindow();
            if (franchise!=null && franchise.phase()==V5FranchiseWindow.Phase.CLOSED) {
                V5FranchiseOffer terms=state.getFranchiseOffer();
                visible.putObject("franchiseOutcome").put("resolution",franchise.resolution().name())
                        .put("entryFeeMinor",terms.entryFeeMinor())
                        .put("monthlyRoyaltyMinor",terms.monthlyRoyaltyMinor())
                        .put("unitPremiumMinor",terms.unitPremiumMinor());
            } else visible.putNull("franchiseOutcome");
        }
        if (state.isNpcWindowOpen()) visible.set("orderOffer",json.valueToTree(offerView(state.getNpcOrder())));
        else visible.putNull("orderOffer");
        if (state.getContractVersion()==5) {
            if (openAnnualWindow(state)) {
                V6AnnualWindow window=state.getV6AnnualWindow();
                ObjectNode monthly=json.createObjectNode()
                        .put("triggerEventId",window.triggerEventId())
                        .put("targetVersion",window.currentOfferVersion())
                        .put("month",window.month()).put("phase",window.phase().name())
                        .put("deadline",window.deadline().toString())
                        .put("signal",state.getV5Signal().name());
                if (enrichedContext || state.getRuleVersion().equals("1.1")) {
                    V6FoundingAgreement founding=V6FoundingContract.parse(
                            state.getProposal().path("plan").path("foundingAgreement"));
                    V6FoundingAgreement.Seat ownSeat=task.getAgentId().equals(activity.getHostAgentId())
                            ?V6FoundingAgreement.Seat.HOST:V6FoundingAgreement.Seat.GUEST;
                    List<String> duties=new ArrayList<>();
                    if (founding.serviceLead()==ownSeat) duties.add("FULFILLMENT");
                    if (founding.supplyLead()==ownSeat) duties.add("SUPPLY");
                    if (founding.communityLead()==ownSeat) duties.add("CUSTOMERS");
                    monthly.set("responsibilities",json.valueToTree(duties));
                    monthly.set("optionEffects",json.valueToTree(window.conflict()==null
                            ?List.of():window.conflict().options()));
                }
                if (state.getRuleVersion().equals("1.1"))
                    monthly.set("cause",json.valueToTree(state.getIncidentCause()));
                monthly.set("priorReport",json.valueToTree(state.getGame().reports()
                        .get(state.getGame().reports().size()-1)));
                List<MonthlyShopRules.MonthlyReport> reports=state.getGame().reports();
                monthly.set("recentTrend",json.valueToTree(reports.stream()
                        .skip(Math.max(0,reports.size()-3))
                        .map(report -> Map.of("month",report.month(),"soldUnits",report.soldUnits(),
                                "profitMinor",report.profitMinor(),"closingCashMinor",report.closingCashMinor(),
                                "closingInventoryMinor",report.closingInventoryMinor()))
                        .toList()));
                monthly.set("carryover",json.valueToTree(Map.of(
                        "trust",state.getV6Story().trust(),"supply",state.getV6Story().supply(),
                        "rentSurchargeCoins",state.getV6Story().rentSurchargeCoins(),
                        "echoMonths",state.getV6Story().echoMonths())));
                if (window.conflict()==null) monthly.putNull("conflictCode");
                else {
                    monthly.put("conflictCode",window.conflict().name());
                    monthly.set("optionCodes",json.valueToTree(window.conflict().options().stream()
                            .map(V6ConflictRules.Option::code).toList()));
                }
                boolean sealed=window.phase()==V6AnnualWindow.Phase.AWAIT_SECOND;
                monthly.set("firstPlan",sealed?json.nullNode():json.valueToTree(window.firstPlan()));
                monthly.set("secondPlan",json.valueToTree(window.secondPlan()));
                monthly.set("firstReply",json.valueToTree(window.firstReply()));
                monthly.set("secondReply",json.valueToTree(window.secondReply()));
                monthly.set("latestOffer",sealed?json.nullNode():json.valueToTree(window.latestOffer()));
                monthly.set("ownLastPlan",json.valueToTree(window.ownLastPlan(task.getAgentId())));
                monthly.put("maxReplyRounds",2);
                visible.set("monthlyWindow",monthly);
            } else if (openV6Window(state)) {
                V6MonthlyWindow window=state.getV6Window();
                ObjectNode monthly=json.createObjectNode().put("triggerEventId",window.triggerEventId())
                        .put("planVersion",window.planVersion()).put("month",window.month())
                        .put("signal",state.getV5Signal().name()).put("phase",window.phase().name())
                        .put("deadline",window.deadline().toString())
                        .put("conflictCode",window.conflict().name())
                        .put("conflictFamily",window.conflict().family().name());
                monthly.set("optionCodes",json.valueToTree(window.conflict().options().stream()
                        .map(V6ConflictRules.Option::code).toList()));
                boolean sealed=window.phase()==V6MonthlyWindow.Phase.AWAIT_SECOND;
                if (sealed) monthly.putNull("firstPosition");
                else if (window.firstPosition()==null) monthly.putNull("firstPosition");
                else monthly.put("firstPosition",window.firstPosition());
                if (window.secondPosition()==null) monthly.putNull("secondPosition");
                else monthly.put("secondPosition",window.secondPosition());
                monthly.put("maxCounterRounds",V6MonthlyWindow.MAX_COUNTER_ROUNDS)
                        .put("counterRounds",window.counterRounds());
                if (window.latestOffer()==null) monthly.putNull("latestOffer");
                else monthly.put("latestOffer",window.latestOffer());
                visible.set("monthlyWindow",monthly);
            } else if (openV5Window(state)) {
                V5MonthlyWindow window=state.getV5Window();
                ObjectNode monthly=json.createObjectNode().put("triggerEventId",window.triggerEventId())
                        .put("planVersion",window.planVersion()).put("month",window.month())
                        .put("signal",state.getV5Signal().name()).put("phase",window.phase().name())
                        .put("deadline",window.deadline().toString());
                monthly.set("proposal",json.valueToTree(window.proposal()));
                monthly.set("counterProposal",json.valueToTree(window.counterProposal()));
                if (isV6(state) && state.getV6Conflict()!=null) {
                    V6ConflictRules.Kind kind=state.getV6Conflict();
                    monthly.put("conflictCode",kind.name()).put("conflictFamily",kind.family().name());
                    monthly.set("choiceMap",json.valueToTree(Map.of(
                            "KEEP_IDENTITY",v6Option(kind,V5ShopRules.Response.KEEP_IDENTITY),
                            "PROMOTE",v6Option(kind,V5ShopRules.Response.PROMOTE),
                            "TEMPORARY_PIVOT",v6Option(kind,V5ShopRules.Response.TEMPORARY_PIVOT))));
                }
                visible.set("monthlyWindow",monthly);
            } else visible.putNull("monthlyWindow");
            if (state.getReviewPendingAgent()!=null) {
                ObjectNode review=visible.putObject("partnerReview");
                MonthlyShopRules.Summary ending=summaryFor(state);
                review.putObject("outcome").put("ending",ending.ending().name())
                        .put("operatedMonths",ending.operatedMonths())
                        .put("netProfitMinor",ending.netProfitMinor());
                review.set("partnerEvidence",json.valueToTree(partnerReviewEvidence(activity,task.getAgentId())));
            }
            if (openFranchiseWindow(state)) {
                V5FranchiseWindow window=state.getFranchiseWindow();
                ObjectNode pitch=json.createObjectNode().put("offerId",window.offerId())
                        .put("offerVersion",window.offerVersion())
                        .put("phase",window.phase().name()).put("deadline",window.deadline().toString());
                pitch.set("terms",json.valueToTree(state.getFranchiseOffer().publicTerms()));
                pitch.set("proposal",json.valueToTree(window.proposal()));
                pitch.set("counterProposal",json.valueToTree(window.counterProposal()));
                V5FranchiseOffer.Investigation own=window.investigations().get(task.getAgentId());
                if (own==null) pitch.putNull("ownInvestigation");
                else pitch.putObject("ownInvestigation").put("kind",own.name())
                        .put("clueCode",state.getFranchiseOffer().clue(own));
                visible.set("franchiseWindow",pitch);
            } else if (franchiseEnabled) visible.putNull("franchiseWindow");
        }
        visible.putNull("orderProposal"); visible.putNull("npcBudgetMinor"); result.set("visibleState",visible);
        ObjectNode limits=json.createObjectNode().put("remainingDecisions",remainingDecisions(activity,task.getAgentId()))
                .put("remainingAttempts",Math.max(0,attemptLimit(activity,permission)-seat.getAttemptsUsed()))
                .put("remainingWindowDecisions",windowRemaining(state,task.getAgentId())).put("maxOutputChars",300);
        result.set("limits",limits); return result;
    }
    private List<String> allowed(PlaygroundRoomState state) {
        if (state.getReviewPendingAgent()!=null) return List.of("REVIEW_PARTNER");
        if (state.isClosingReplyPending()) return List.of("FINAL_NOTE");
        if (state.isNpcWindowOpen()) return List.of("ACCEPT_ORDER","DECLINE_ORDER","LEAVE");
        if (openFranchiseWindow(state)) {
            V5FranchiseWindow window=state.getFranchiseWindow();
            long actor=franchiseWindowActor(window);
            List<String> actions=new ArrayList<>();
            if (!window.investigations().containsKey(actor)) actions.addAll(List.of(
                    "CHECK_FRANCHISE_TERMS","CHECK_FRANCHISE_STORES","CHECK_FRANCHISE_SUPPLY"));
            actions.addAll(switch (window.phase()) {
                case AWAIT_PROPOSAL -> List.of("PROPOSE_FRANCHISE","LEAVE");
                case AWAIT_REPLY -> List.of("ACCEPT_FRANCHISE","COUNTER_FRANCHISE","DECLINE_FRANCHISE","LEAVE");
                case AWAIT_COUNTER_REPLY -> List.of("ACCEPT_FRANCHISE","DECLINE_FRANCHISE","LEAVE");
                case CLOSED -> List.of();
            });
            return actions;
        }
        if (openAnnualWindow(state)) return switch (state.getV6AnnualWindow().phase()) {
            case AWAIT_FIRST, AWAIT_SECOND -> List.of("POSITION_MONTHLY","LEAVE");
            case AWAIT_CONFIRM -> List.of("ACCEPT_MONTHLY","LEAVE");
            case AWAIT_FIRST_REPLY -> List.of("REPLY_MONTHLY","LEAVE");
            case AWAIT_SECOND_REPLY -> List.of("ACCEPT_MONTHLY","DECLINE_MONTHLY",
                    "RETRACT_MONTHLY","REPLY_MONTHLY","LEAVE");
            case AWAIT_LAST_REPLY -> List.of("ACCEPT_MONTHLY","DECLINE_MONTHLY",
                    "RETRACT_MONTHLY","LEAVE");
            case CLOSED -> List.of();
        };
        if (openV6Window(state)) return switch (state.getV6Window().phase()) {
            case AWAIT_FIRST, AWAIT_SECOND -> List.of("POSITION_MONTHLY","LEAVE");
            case AWAIT_COUNTER -> List.of("COUNTER_MONTHLY","DECLINE_MONTHLY","LEAVE");
            case AWAIT_FINAL -> List.of("ACCEPT_MONTHLY","COUNTER_MONTHLY","DECLINE_MONTHLY","LEAVE");
            case AWAIT_LAST_REPLY -> List.of("ACCEPT_MONTHLY","DECLINE_MONTHLY","LEAVE");
            case CLOSED -> List.of();
        };
        if (openV5Window(state)) return switch (state.getV5Window().phase()) {
            case AWAIT_PROPOSAL -> List.of("PROPOSE_MONTHLY","LEAVE");
            case AWAIT_REPLY -> List.of("ACCEPT_MONTHLY","COUNTER_MONTHLY","DECLINE_MONTHLY","LEAVE");
            case AWAIT_COUNTER_REPLY -> List.of("ACCEPT_MONTHLY","DECLINE_MONTHLY","LEAVE");
            case CLOSED -> List.of();
        };
        return state.getProposal()==null?List.of("PROPOSE_PLAN","LEAVE"):List.of("COUNTER_PLAN","ACCEPT_PLAN","DECLINE_PLAN","LEAVE");
    }
    private void issueTask(Activity activity,PlaygroundRoomState state,long actor) {
        Task task=new Task(); task.setActivityId(activity.getId()); task.setAgentId(actor);
        task.setPermissionVersion(permit(actor).getVersion()); task.setStatus("PENDING");
        task.setPhase(state.getReviewPendingAgent()!=null?"PARTNER_REVIEW":
                state.isClosingReplyPending()?"CLOSING":state.isNpcWindowOpen()?"OFFER_NEGOTIATION":
                openFranchiseWindow(state)?"FRANCHISE_DECISION":
                openAnnualWindow(state)?"MONTHLY_DEBATE":openV6Window(state)?"MONTHLY_DEBATE":
                openV5Window(state)?"MONTHLY_DECISION":"PLANNING");
        task.setExpiresAt(min(now().plusMinutes(15),activity.getExpiresAt())); task.setCreatedAt(now()); store.insertTask(task);
    }
    private void permissionForTask(Activity activity,Task task,long expected) {
        live(activity); require(activity.getStatus().equals("PLANNING"),409,"ACTIVITY_NOT_PLANNING");
        require(now().isBefore(task.getExpiresAt()),410,"TASK_EXPIRED");
        for (long actor:List.of(activity.getHostAgentId(),activity.getGuestAgentId())) {
            Participation permission=permit(actor); Seat seat=seat(activity,actor);
            require(permission.getVersion().equals(seat.getPermissionVersion()),409,"PERMISSION_VERSION_CONFLICT");
        }
        require(task.getPermissionVersion()==expected && permit(task.getAgentId()).getVersion()==expected,409,"PERMISSION_VERSION_CONFLICT");
    }
    private void lease(Task task,String token) {
        require(task.getStatus().equals("LEASED") && task.getLeaseHash()!=null && token!=null
                && MessageDigest.isEqual(task.getLeaseHash().getBytes(StandardCharsets.US_ASCII),hash(token).getBytes(StandardCharsets.US_ASCII))
                && task.getLeaseExpiresAt()!=null && now().isBefore(task.getLeaseExpiresAt()),409,"LEASE_LOST");
    }
    private int remainingDecisions(Activity activity,long actor) {
        return Math.max(0,Math.min(activity.getHorizonMonths()==2?4:isAnnualActivity(activity)?40:6,
                permit(actor).getMaxDecisions())-seat(activity,actor).getDecisionsUsed());
    }
    private int attemptLimit(Activity activity,Participation permission) {
        return Math.min(activity.getHorizonMonths()==2?4:isAnnualActivity(activity)?40:6,
                permission.getMaxAttempts());
    }
    private boolean isAnnualActivity(Activity activity) {
        return Set.of("1.0","1.1").contains(read(activity.getStateJson(),PlaygroundRoomState.class).getRuleVersion());
    }
    private boolean isAnnual(PlaygroundRoomState state) { return Set.of("1.0","1.1").contains(state.getRuleVersion()); }
    private boolean isV6(PlaygroundRoomState state) {
        return Set.of("0.9","1.0","1.1").contains(state.getRuleVersion());
    }
    boolean requiresAnnualGrant(String mode,int contractVersion) {
        return v6Enabled && "FULL".equals(mode) && contractVersion==5;
    }
    void annualGrant(Participation permission) {
        require(permission.getMaxDecisions()>=40 && permission.getMaxAttempts()>=40
                && permission.getMaxDailyAttempts()>=40,403,"ANNUAL_PARTICIPATION_GRANT_REQUIRED");
    }
    private int windowRemaining(PlaygroundRoomState state,long actor) {
        if (state.getReviewPendingAgent()!=null)
            return state.getReviewPendingAgent()==actor && !state.getPartnerReviews().containsKey(actor)?1:0;
        if (state.isClosingReplyPending()) return Objects.equals(actor,state.getClosingInitiator())?0:1;
        if (openFranchiseWindow(state)) return Math.max(0,3-state.getWindowDecisions().getOrDefault(actor,0));
        if (openV6Window(state)) return Math.max(0,3-state.getWindowDecisions().getOrDefault(actor,0));
        if (openAnnualWindow(state)) return Math.max(0,3-state.getWindowDecisions().getOrDefault(actor,0));
        if (isAnnual(state)) return Math.max(0,3-state.getWindowDecisions().getOrDefault(actor,0));
        return Math.max(0,2-state.getWindowDecisions().getOrDefault(actor,0));
    }
    private String decisionWindowKey(PlaygroundRoomState state) {
        if (state.getReviewPendingAgent()!=null) return "REVIEW:"+state.getReviewPendingAgent();
        if (openAnnualWindow(state)) return "V6A:"+state.getV6AnnualWindow().triggerEventId();
        if (openV6Window(state)) return "V6:"+state.getV6Window().triggerEventId();
        if (openV5Window(state)) return "V5:"+state.getV5Window().triggerEventId();
        if (openFranchiseWindow(state)) return "FRANCHISE:"+state.getFranchiseWindow().offerId();
        if (state.isNpcWindowOpen()) return "NPC:"+state.getNpcOrder().id();
        if (state.isClosingReplyPending()) return "CLOSING";
        return "PLANNING";
    }
    private Seat seat(Activity activity,long actor) {
        Seat seat=store.seat(actor); require(seat!=null && seat.getActivityId().equals(activity.getId()),409,"SEAT_LOST"); return seat;
    }
    private Participation permit(long actor) {
        activeAgent(actor); Participation permission=store.lockParticipation(actor);
        require(permission!=null && permission.isEnabled(),403,"PARTICIPATION_DISABLED"); return permission;
    }
    private Agent activeAgent(long actor) {
        Agent agent=agents.selectById(actor); require(agent!=null,404,"AGENT_NOT_FOUND");
        User user=users.selectById(agent.getUserId());
        require(!"DISABLED".equals(agent.getStatus()) && user!=null && "ACTIVE".equals(user.getStatus()),403,"PRINCIPAL_DISABLED"); return agent;
    }
    private void owner(long userId,long agentId) { require(activeAgent(agentId).getUserId()==userId,403,"NOT_AGENT_OWNER"); }
    private long ownerMember(long userId,Activity activity) {
        for (long actor:List.of(activity.getHostAgentId(),activity.getGuestAgentId())) {
            Agent agent=agents.selectById(actor);
            if (agent!=null && agent.getUserId()==userId) { activeAgent(actor); return actor; }
        }
        throw new BusinessException(403,"NOT_ACTIVITY_OWNER");
    }
    private void lockAgents(long... ids) {
        Arrays.stream(ids).distinct().sorted().forEach(id->require(store.lockAgent(id)!=null,404,"AGENT_NOT_FOUND"));
    }
    private void lockRoomAgents(Activity activity) { lockAgents(activity.getHostAgentId(),activity.getGuestAgentId()); }
    private Activity locked(long id) { available(); Activity activity=store.lockActivity(id); require(activity!=null,404,"ACTIVITY_NOT_FOUND"); return activity; }
    private Task taskFor(long actor,long id) {
        available(); Task task=store.task(id); require(task!=null,404,"TASK_NOT_FOUND"); require(task.getAgentId()==actor,403,"NOT_TASK_ACTOR"); return task;
    }
    private Task lockedTaskFor(long actor,long id) {
        Task task=store.lockTask(id); require(task!=null,404,"TASK_NOT_FOUND");
        require(task.getAgentId()==actor,403,"NOT_TASK_ACTOR"); return task;
    }
    private void member(Activity activity,long actor) {
        require(activity.getHostAgentId()==actor || activity.getGuestAgentId()==actor,403,"NOT_ACTIVITY_ACTOR");
    }
    private void live(Activity activity) { require(now().isBefore(activity.getExpiresAt()),410,"ACTIVITY_EXPIRED"); }
    private PlaygroundRoomState state(Activity activity) {
        PlaygroundRoomState state=read(activity.getStateJson(),PlaygroundRoomState.class);
        require((Set.of(2,3).contains(state.getContractVersion()) && state.getRuleVersion().equals("0.4"))
                || (state.getContractVersion()==4 && state.getRuleVersion().equals("0.5"))
                || (state.getContractVersion()==5 && Set.of("0.6","0.7","0.8","0.9","1.0","1.1").contains(state.getRuleVersion())),409,"UNSUPPORTED_RULE_VERSION");
        if (state.getProposal()!=null && state.getProposal().isNull()) state.setProposal(null);
        return state;
    }
    private void save(Activity activity,PlaygroundRoomState state) { activity.setStateJson(write(state)); activity.setUpdatedAt(now()); store.saveActivity(activity); }
    private void interrupt(Activity activity,String reason) {
        activity.setStatus("INTERRUPTED"); event(activity,"INTERRUPTED","SYSTEM",null,"INTERRUPTED",Map.of("reason",reason));
    }
    private void closeWithoutReply(Activity activity,PlaygroundRoomState state,String reason) {
        state.setClosingReplyPending(false);
        event(activity,"CLOSING_MISSED","SYSTEM",null,"EXECUTED",Map.of("reason",reason));
        interrupt(activity,"PLAN_DECLINED");
    }
    private void event(Activity activity,String kind,String source,Long actor,String status,Object facts) {
        int month=facts instanceof MonthlyShopRules.MonthlyReport report?report.month():
                facts instanceof MonthlyShopRules.Summary summary?summary.operatedMonths():0;
        eventAtMonth(activity,kind,source,actor,status,facts,month);
    }
    private void eventAtMonth(Activity activity,String kind,String source,Long actor,String status,Object facts,int month) {
        long sequence=activity.getNextSequence(); ObjectNode event=json.createObjectNode().put("eventId",activity.getId()+":"+sequence)
                .put("sequence",sequence).put("activityId",activity.getId()+"").put("occurredAt",utc(now())).put("viewSchemaVersion",2)
                .put("ruleVersion",read(activity.getStateJson(),PlaygroundRoomState.class).getRuleVersion())
                .put("actorSource",source).put("kind",kind).put("semanticStatus",status)
                .put("summary",kind);
        event.put("virtualMonth",month);
        event.set("sourceEventIds",json.createArrayNode()); event.set("facts",json.valueToTree(facts));
        if (actor!=null) ((ObjectNode)event.get("facts")).put("actorId","agent:"+actor);
        store.insertEvent(activity.getId(),sequence,write(event)); activity.setNextSequence(sequence+1);
    }
    private void fields(JsonNode node,Set<String> expected) {
        require(node!=null && node.isObject() && node.size()==expected.size(),400,"INVALID_ACTION");
        node.fieldNames().forEachRemaining(name->require(expected.contains(name),400,"INVALID_ACTION"));
    }
    private String text(JsonNode node,String key,int max) {
        JsonNode value=node==null?null:node.get(key); require(value!=null && value.isTextual() && value.textValue().length()<=max,400,"INVALID_ACTION"); return value.textValue();
    }
    private String identifier(JsonNode node,String key) {
        String value=text(node,key,100); require(value.matches("[A-Za-z0-9:_-]+"),400,"INVALID_ACTION"); return value;
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result=json.createObjectNode(); List<String> names=new ArrayList<>(); node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names); names.forEach(name->result.set(name,canonical(node.get(name)))); return result;
        }
        if (node.isArray()) { ArrayNode result=json.createArrayNode(); node.forEach(value->result.add(canonical(value))); return result; }
        return node;
    }
    private String uuid(String value) {
        try { String normalized=UUID.fromString(value).toString(); require(normalized.equalsIgnoreCase(value),400,"INVALID_IDEMPOTENCY_KEY"); return normalized; }
        catch (IllegalArgumentException|NullPointerException error) { throw new BusinessException(400,"INVALID_IDEMPOTENCY_KEY"); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception error) { throw new BusinessException(500,"PLAYGROUND_STATE_ENCODING_FAILED"); }
    }
    private <T> T read(String value,Class<T> type) {
        try { return json.readValue(value,type); } catch (Exception error) { throw new BusinessException(500,"PLAYGROUND_STATE_DECODING_FAILED"); }
    }
    private void available() { require(enabled,404,"PLAYGROUND_DISABLED"); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    private String utc(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC).toString(); }
    private LocalDateTime min(LocalDateTime a,LocalDateTime b) { return a.isBefore(b)?a:b; }
    private void require(boolean valid,int code,String error) { if (!valid) throw new BusinessException(code,error); }
}
