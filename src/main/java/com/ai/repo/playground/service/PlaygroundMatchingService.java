package com.ai.repo.playground.service;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import com.ai.repo.entity.Agent;
import com.ai.repo.mapper.AgentMapper;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.playground.mapper.PlaygroundMapper;
import com.ai.repo.playground.entity.PlaygroundRows.*;
import com.ai.repo.playground.entity.PlaygroundRoomState;
import com.ai.repo.playground.dto.PlaygroundRequests.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name="playground.enabled",havingValue="true")
public class PlaygroundMatchingService {
    private final PlaygroundMapper store;
    private final AgentMapper agents;
    private final PlaygroundService games;
    private final ObjectMapper json;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public PlaygroundMatchingService(PlaygroundMapper store,AgentMapper agents,PlaygroundService games,ObjectMapper json) {
        this(store,agents,games,json,Clock.systemUTC());
    }
    public PlaygroundMatchingService(PlaygroundMapper store,AgentMapper agents,PlaygroundService games,ObjectMapper json,Clock clock) {
        this.clock=clock; this.store=store; this.agents=agents; this.games=games; this.json=json;
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> enqueue(long user,MatchJoin request) {
        mutex(); games.participation(user,request.agentId());
        int contractVersion=games.contractVersionFor(request.ownerBrief());
        MatchEntry old=store.matchEntry(request.agentId());
        if (old!=null && Set.of("WAITING","MATCHED").contains(old.getStatus()) && now().isBefore(old.getExpiresAt())) return view(old);
        store.lockAgent(request.agentId());
        Participation permission=store.lockParticipation(request.agentId());
        require(permission!=null && permission.isEnabled(),403,"PARTICIPATION_DISABLED");
        if (games.requiresAnnualGrant(request.mode(),contractVersion)) games.annualGrant(permission);
        require(idle(request.agentId(),true),409,"AGENT_ALREADY_IN_ACTIVITY");
        require(online(request.agentId()),409,"AGENT_RUNTIME_NOT_RECENT");
        require(budget(request.agentId(),permission),429,"DAILY_BUDGET_EXHAUSTED");
        MatchEntry row=new MatchEntry(); row.setAgentId(request.agentId()); row.setUserId(user);
        row.setMode(request.mode()); row.setPermissionVersion(permission.getVersion()); row.setBriefJson(write(request.ownerBrief()));
        row.setStatus("WAITING"); row.setCreatedAt(now()); row.setExpiresAt(now().plusMinutes(30)); store.saveMatch(row);
        match(); return view(store.matchEntry(row.getAgentId()));
    }
    public Map<String,Object> status(long user,long actor) {
        games.participation(user,actor); MatchEntry row=store.matchEntry(actor);
        return row==null ? Map.of("status","NONE") : view(row);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cancel(long user,long actor) {
        mutex(); games.participation(user,actor); MatchEntry row=store.matchEntry(actor);
        if(row==null || Set.of("CANCELLED","EXPIRED").contains(row.getStatus())) return;
        require(row.getStatus().equals("WAITING"),409,"MATCH_ALREADY_PAIRED");
        row.setStatus("CANCELLED"); store.saveMatch(row);
    }
    @Scheduled(fixedDelayString="${playground.match-scan-ms:5000}")
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void tick() { mutex(); match(); }
    private void match() {
        List<MatchEntry> rows=store.matchEntries();
        for (MatchEntry row:rows) {
            if(row.getStatus().equals("MATCHED")) {
                Activity room=store.activity(row.getActivityId());
                if(room==null) { row.setStatus("EXPIRED"); store.saveMatch(row); continue; }
                PlaygroundRoomState state=read(room.getStateJson(),PlaygroundRoomState.class);
                if(!room.getStatus().equals("WAITING") && !room.getStatus().equals("INTERRUPTED")) { row.setStatus("STARTED"); store.saveMatch(row); continue; }
                if(room.getStatus().equals("WAITING") && !now().isBefore(room.getExpiresAt())) { games.expire(room.getId()); room=store.activity(room.getId()); }
                if(room.getStatus().equals("INTERRUPTED")) {
                    // Only a deadline during initial joining may retry; withdrawals never restart.
                    boolean timeout=state.getReadyAgents().size()<2 && store.events(room.getId(),0).stream().map(v->read(v,com.fasterxml.jackson.databind.JsonNode.class)).anyMatch(e->"INTERRUPTED".equals(e.path("kind").asText()) && "DEADLINE_EXPIRED".equals(e.path("facts").path("reason").asText()));
                    boolean ready=state.getReadyAgents().contains(row.getAgentId());
                    row.setStatus(timeout && ready && row.getRetries()<2 && now().isBefore(row.getExpiresAt()) ? "WAITING" : "EXPIRED");
                    row.setRetries(row.getRetries()+1); if(row.getStatus().equals("WAITING")) row.setActivityId(null); store.saveMatch(row);
                }
            }
            if(row.getStatus().equals("WAITING") && (!now().isBefore(row.getExpiresAt()) || !eligible(row,false))) { row.setStatus("EXPIRED"); store.saveMatch(row); }
        }
        List<MatchEntry> waiting=new ArrayList<>(rows.stream().filter(r->r.getStatus().equals("WAITING")).toList());
        for(MatchEntry one:waiting) {
            if(!one.getStatus().equals("WAITING")) continue;
            List<MatchEntry> candidates=new ArrayList<>(waiting.stream().filter(two->two.getStatus().equals("WAITING") && !two.getUserId().equals(one.getUserId()) && two.getMode().equals(one.getMode())
                    && games.contractVersionFor(read(one.getBriefJson(),OwnerBrief.class))==games.contractVersionFor(read(two.getBriefJson(),OwnerBrief.class))).toList());
            Collections.shuffle(candidates);
            candidates.sort(Comparator.comparingInt(two->store.recentPair(one.getAgentId(),two.getAgentId(),now().minusDays(1))));
            if(candidates.isEmpty()) continue;
            MatchEntry two=candidates.get(0);
            for(long actor:new TreeSet<>(List.of(one.getAgentId(),two.getAgentId()))) store.lockAgent(actor);
            if(!eligible(one,true) || !eligible(two,true)) continue;
            one.setStatus("PAIRING"); two.setStatus("PAIRING"); store.saveMatch(one); store.saveMatch(two);
            Map<String,Object> created=games.invite(one.getUserId(),new Invitation(one.getAgentId(),two.getAgentId(),one.getMode(),read(one.getBriefJson(),OwnerBrief.class)));
            long id=Long.parseLong(created.get("activityId").toString());
            games.acceptInvitation(two.getUserId(),id,new InvitationAccept(read(two.getBriefJson(),OwnerBrief.class)));
            Activity room=store.lockActivity(id); PlaygroundRoomState initial=read(room.getStateJson(),PlaygroundRoomState.class); initial.setRandomMatched(true); room.setStateJson(write(initial));
            room.setExpiresAt(now().plusMinutes(room.getHorizonMonths()==12 && initial.getContractVersion()==5?60:2)); store.saveActivity(room);
            for(MatchEntry entry:List.of(one,two)) {
                Seat seat=new Seat(); seat.setAgentId(entry.getAgentId()); seat.setActivityId(id); seat.setPermissionVersion(entry.getPermissionVersion()); store.insertSeat(seat);
                entry.setActivityId(id); entry.setStatus("MATCHED"); store.saveMatch(entry);
            }
            return; // Bounded transaction: at most one new pair per scan.
        }
    }
    private boolean eligible(MatchEntry row,boolean locked) {
        try { games.participation(row.getUserId(),row.getAgentId()); } catch (BusinessException disabled) { return false; }
        Participation p=locked ? store.lockParticipation(row.getAgentId()) : store.participation(row.getAgentId());
        if (p==null || !p.isEnabled()) return false;
        OwnerBrief brief=read(row.getBriefJson(),OwnerBrief.class);
        if (games.requiresAnnualGrant(row.getMode(),games.contractVersionFor(brief))
                && (p.getMaxDecisions()<40 || p.getMaxAttempts()<40 || p.getMaxDailyAttempts()<40)) return false;
        return p.getVersion().equals(row.getPermissionVersion()) && idle(row.getAgentId(),locked)
                && online(row.getAgentId()) && budget(row.getAgentId(),p);
    }
    private boolean idle(long actor,boolean locked) { return store.activeRoomCount(actor)==0 && (locked ? store.seat(actor)==null : store.seatCount(actor)==0); }
    private boolean online(long actor) { Agent a=agents.selectById(actor); return a!=null && !"DISABLED".equals(a.getStatus()) && a.getLastHeartbeatAt()!=null && a.getLastHeartbeatAt().isAfter(now().minusMinutes(10)); }
    private boolean budget(long actor,Participation p) { return store.dailyAttempts(actor,now().toLocalDate())<p.getMaxDailyAttempts() && store.dailyGames(actor,now().toLocalDate())<2; }
    private void mutex() { require(store.matchMutex()!=null,503,"MATCH_QUEUE_UNAVAILABLE"); }
    private Map<String,Object> view(MatchEntry row) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("status",row.getStatus()); result.put("agentId",row.getAgentId().toString());
        result.put("ownerBrief",read(row.getBriefJson(),OwnerBrief.class)); result.put("mode",row.getMode()); result.put("expiresAt",row.getExpiresAt().toInstant(ZoneOffset.UTC).toString());
        if(row.getActivityId()!=null) result.put("activityId",row.getActivityId().toString()); return result;
    }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);} }
    private <T>T read(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);} }
    private void require(boolean ok,int status,String code) {if(!ok)throw new BusinessException(status,code);}
}
