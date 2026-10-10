package com.ai.repo.playground.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ai.repo.exception.BusinessException;
import com.ai.repo.playground.entity.PlaygroundRows.Activity;
import com.ai.repo.playground.entity.PlaygroundRows.Share;
import com.ai.repo.playground.mapper.PlaygroundMapper;
import com.ai.repo.playground.rules.V6FoundingContract;
import com.ai.repo.playground.rules.V6ConflictRules;
import com.ai.repo.playground.rules.V6MonthlyWindow;
import com.ai.repo.playground.rules.V6MonthlyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Immutable public result snapshots. No owner brief or private activity state crosses this boundary. */
@Service
public class PlaygroundShareService {
    private final PlaygroundMapper store;
    private final PlaygroundService games;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Clock clock;
    private final PlaygroundPublicRateLimiter publicRateLimiter;
    private final SecureRandom random=new SecureRandom();

    @Autowired
    public PlaygroundShareService(PlaygroundMapper store,PlaygroundService games,ObjectMapper json,
            @Value("${playground.enabled:false}") boolean enabled, PlaygroundPublicRateLimiter publicRateLimiter) {
        this(store,games,json,enabled,Clock.systemUTC(),publicRateLimiter);
    }
    public PlaygroundShareService(PlaygroundMapper store,PlaygroundService games,ObjectMapper json,
            boolean enabled,Clock clock) {
        this(store,games,json,enabled,clock,null);
    }
    public PlaygroundShareService(PlaygroundMapper store,PlaygroundService games,ObjectMapper json,
            boolean enabled,Clock clock,PlaygroundPublicRateLimiter publicRateLimiter) {
        this.store=store;this.games=games;this.json=json;this.enabled=enabled;this.clock=clock;
        this.publicRateLimiter=publicRateLimiter;
    }

    @Transactional
    public ObjectNode ownerResultLink(long userId,long activityId) {
        available();
        Activity activity=store.lockActivity(activityId);
        require(activity!=null,404,"ACTIVITY_NOT_FOUND");
        ObjectNode view=games.ownerActivity(userId,activityId);
        require(Set.of("SETTLED","INTERRUPTED").contains(view.path("status").asText()),409,"GAME_NOT_FINISHED");
        Share row=store.share(activityId);
        require(row==null || row.getRemovedAt()==null,410,"SHARE_REMOVED");
        JsonNode payload=safeStored(row);
        if (payload==null) {
            payload=projection(userId,activityId,view,activity);
            boolean newRow=row==null;
            if (row==null) { row=new Share();row.setActivityId(activityId);row.setCreatedAt(now()); }
            row.setPublicToken(newToken());
            row.setPayloadJson(write(payload));
            if (newRow) store.insertShare(row);
            else store.saveShare(row);
        }
        return json.createObjectNode().put("sharePath","/api/playground/shares/"+row.getPublicToken()+"/landing")
                .set("result",payload);
    }

    public JsonNode publicResult(String token) {
        available();require(token!=null && token.matches("[A-Za-z0-9_-]{43}"),404,"SHARE_NOT_FOUND");
        if (publicRateLimiter!=null) publicRateLimiter.check(token);
        Share row=store.publishedShare(token);
        require(row!=null,404,"SHARE_NOT_FOUND");
        try {
            JsonNode payload=json.readTree(row.getPayloadJson());
            require(safeSnapshot(payload),404,"SHARE_NOT_FOUND");
            return payload;
        }
        catch (BusinessException error) { throw error; }
        catch (Exception error) { throw new BusinessException(500,"SHARE_STATE_INVALID"); }
    }

    @Transactional
    public void removePublishedResult(long adminUserId,long activityId,String reasonCode) {
        // Moderation remains possible while the game and public routes are disabled.
        require(adminUserId>0,401,"AUTHENTICATION_REQUIRED");
        require(reasonCode!=null && Set.of("PRIVACY","ABUSE","INACCURATE","OTHER").contains(reasonCode),400,"INVALID_TAKEDOWN_REASON");
        Activity activity=store.lockActivity(activityId);
        require(activity!=null,404,"ACTIVITY_NOT_FOUND");
        Share row=store.share(activityId);
        if (row==null) {
            // A result link is lazy-created when an owner opens the ending. A
            // tombstone blocks that future publication as well as existing links.
            row=new Share();
            row.setActivityId(activityId);
            row.setCreatedAt(now());
            row.setPayloadJson("{}");
            row.setPublicToken(newToken());
            row.setRemovedAt(now());
            row.setRemovedByUserId(adminUserId);
            row.setRemovedReason(reasonCode);
            store.insertShare(row);
            return;
        }
        if (row.getRemovedAt()!=null) return; // Idempotent; preserve the original audit decision.
        row.setRemovedAt(now());
        row.setRemovedByUserId(adminUserId);
        row.setRemovedReason(reasonCode);
        require(store.removeShare(row)==1,409,"SHARE_STATE_CHANGED");
    }

    private String newToken() {
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean safeSnapshot(JsonNode payload) {
        int schema=payload.path("shareSchemaVersion").asInt();
        if (!payload.isObject() || !Set.of(3,4,5).contains(schema) ||
                !"ODD_SHOP".equals(payload.path("gameKey").asText())) return false;
        String outcome=payload.path("outcome").asText();
        if (!Set.of("OPERATED","UNOPENED").contains(outcome) ||
                !onlyFields(payload,schema==5
                        ?Set.of("shareSchemaVersion","gameKey","outcome","horizonMonths","shop","agentMoves","business","foundingAgreement","partnerReviews")
                        :schema>=4
                        ?Set.of("shareSchemaVersion","gameKey","outcome","horizonMonths","shop","agentMoves","business","foundingAgreement")
                        :Set.of("shareSchemaVersion","gameKey","outcome","horizonMonths","shop","agentMoves","business"))) return false;
        if (payload.has("partnerReviews")) {
            JsonNode reviews=payload.path("partnerReviews");
            if (schema!=5 || !outcome.equals("OPERATED") || !reviews.isArray() || reviews.size()!=2
                    || !reviews.get(0).path("role").asText().equals("HOST")
                    || !reviews.get(1).path("role").asText().equals("GUEST")) return false;
            for (JsonNode review:reviews) {
                if (!review.isObject() || !onlyFields(review,Set.of("role","status","partnerStrength",
                        "friction","futureCollaboration","publicRationale","evidenceMonth","evidenceMove"))) return false;
                String status=review.path("status").asText();
                if (status.equals("UNAVAILABLE")) { if (review.size()!=2) return false; continue; }
                if (!status.equals("RECORDED") || review.size()!=8
                        || !Set.of("YES","CONDITIONAL","NO").contains(review.path("futureCollaboration").asText())
                        || review.path("evidenceMonth").asInt(-1)<0 || review.path("evidenceMonth").asInt()>12
                        || !Set.of("PROPOSE_PLAN","COUNTER_PLAN","ACCEPT_PLAN","POSITION_MONTHLY",
                                "REPLY_MONTHLY","ACCEPT_MONTHLY","DECLINE_MONTHLY","RETRACT_MONTHLY")
                                .contains(review.path("evidenceMove").asText())) return false;
                for (String field:List.of("partnerStrength","friction","publicRationale"))
                    if (!review.path(field).isTextual() || review.path(field).asText().isBlank()
                            || review.path(field).asText().length()>(field.equals("publicRationale")?300:160)) return false;
            }
        }
        if (schema>=4 && outcome.equals("OPERATED")) {
            try { V6FoundingContract.parse(payload.path("foundingAgreement")); }
            catch (IllegalArgumentException error) { return false; }
        }
        if (schema>=4 && outcome.equals("UNOPENED") && payload.has("foundingAgreement")) return false;
        JsonNode shop=payload.path("shop"),moves=payload.path("agentMoves");
        if (!shop.isObject() || !onlyFields(shop,Set.of("name","concept","audience","experience","marketing","strategy")) ||
                !shop.path("name").isTextual() || shop.path("name").asText().length()>80 || !moves.isArray()) return false;
        for (String key:List.of("concept","audience","experience","marketing"))
            if (shop.has(key) && (!shop.path(key).isTextual() || shop.path(key).asText().length()>160)) return false;
        if (shop.has("strategy")) {
            JsonNode strategy=shop.path("strategy");
            if (!strategy.isObject() || !onlyFields(strategy,Set.of("audienceSegment","marketingChannel","servicePromise"))
                    || !Set.of("NIGHT_READERS","COMMUTERS","STUDENTS","NEIGHBORS","FAMILIES",
                            "PET_OWNERS","HOBBYISTS").contains(strategy.path("audienceSegment").asText())
                    || !Set.of("NONE","FLYERS","LOCAL_EVENT").contains(strategy.path("marketingChannel").asText())
                    || !Set.of("QUIET","FAST","COMMUNITY").contains(strategy.path("servicePromise").asText())) return false;
        }
        for (JsonNode move:moves) if (!move.isObject() || !onlyFields(move,Set.of("role","move")) ||
                !Set.of("HOST","GUEST").contains(move.path("role").asText()) ||
                !(Set.of("PROPOSE_PLAN","COUNTER_PLAN","ACCEPT_PLAN","DECLINE_PLAN","FINAL_NOTE",
                        "PROPOSE_MONTHLY","POSITION_MONTHLY","COUNTER_MONTHLY",
                        "ACCEPT_MONTHLY","DECLINE_MONTHLY",
                        "CHECK_FRANCHISE_TERMS","CHECK_FRANCHISE_STORES","CHECK_FRANCHISE_SUPPLY",
                        "PROPOSE_FRANCHISE","COUNTER_FRANCHISE","ACCEPT_FRANCHISE","DECLINE_FRANCHISE")
                        .contains(move.path("move").asText())
                    || schema==5 && Set.of("REPLY_MONTHLY","RETRACT_MONTHLY")
                        .contains(move.path("move").asText()))) return false;
        if (outcome.equals("UNOPENED")) return !payload.has("business");
        JsonNode business=payload.path("business");
        if (!business.isObject() || !onlyFields(business,schema>=4
                ?Set.of("ending","operatedMonths","netProfitMinor","months","franchise","returnedCapitalMinor","hostReturnedMinor","guestReturnedMinor")
                :Set.of("ending","operatedMonths","netProfitMinor","months","franchise")) ||
                !business.path("months").isArray()) return false;
        if (schema>=4 && (business.path("returnedCapitalMinor").asLong(-1)<0
                || business.path("hostReturnedMinor").asLong(-1)<0
                || business.path("guestReturnedMinor").asLong(-1)<0
                || business.path("hostReturnedMinor").asLong()+business.path("guestReturnedMinor").asLong()
                    !=business.path("returnedCapitalMinor").asLong())) return false;
        if (business.has("franchise")) {
            JsonNode franchise=business.path("franchise");
            if (!franchise.isObject() || !onlyFields(franchise,Set.of("month","resolution","supportOutcome"))
                    || !Set.of(0,3).contains(franchise.path("month").asInt(-1))
                    || !Set.of("SIGNED","REJECTED","DEADLINE_FALLBACK","BUDGET_FALLBACK",
                    "MODEL_FAILURE_FALLBACK").contains(franchise.path("resolution").asText())
                    || !Set.of("PENDING","DELIVERED","WEAK","ABSENT","NOT_SIGNED")
                    .contains(franchise.path("supportOutcome").asText())) return false;
        }
        for (JsonNode month:business.path("months")) {
            if (!month.isObject() || !onlyFields(month,schema==5
                    ?Set.of("month","profitMinor","events","signal","response","resolution","conflict",
                            "effectivePlan","firstPlan","secondPlan","firstReply","secondReply")
                    :schema==4?Set.of("month","profitMinor","events","signal","response","resolution","conflict","tactic",
                            "firstPosition","secondPosition","counter","replyCounter","counterRounds")
                    :Set.of("month","profitMinor","events","signal","response","resolution")) || !month.path("events").isArray()) return false;
            for (JsonNode event:month.path("events")) if (!event.isTextual() || !event.asText().matches("[A-Z0-9_]{1,48}")) return false;
            if (month.has("signal") && !Set.of("NORMAL","MARKET_SHIFT","MATERIAL_SURGE","RENT_RISE",
                    "PACKAGING_CHANGE","POWER_OUTAGE","COMPETITOR","SUPPLY_DELAY").contains(month.path("signal").asText())) return false;
            if (month.has("response") && !Set.of("KEEP_IDENTITY","PROMOTE","TEMPORARY_PIVOT").contains(month.path("response").asText())) return false;
            if (month.has("resolution") && !(Set.of("PARTNERS_APPROVED","DECLINED","DEADLINE_FALLBACK",
                    "BUDGET_FALLBACK","MODEL_FAILURE_FALLBACK","INDEPENDENT_CONSENSUS",
                    "COUNTER_ACCEPTED").contains(month.path("resolution").asText())
                    || schema==5 && Set.of("CONFIRMED_MATCH","RETRACTED")
                            .contains(month.path("resolution").asText()))) return false;
            if (schema==5) {
                if (month.has("conflict")) try { V6ConflictRules.Kind.valueOf(month.path("conflict").asText()); }
                    catch (IllegalArgumentException error) { return false; }
                for (String field:List.of("effectivePlan","firstPlan","secondPlan","firstReply","secondReply")) {
                    if (!month.has(field)) continue;
                    JsonNode value=month.path(field);
                    if (field.equals("effectivePlan") && value.isTextual()
                            && value.asText().equals("SIGNED_FALLBACK")) continue;
                    if (!safeAnnualPlan(value,month.path("conflict").asText(null))) return false;
                }
                if (month.has("effectivePlan") && month.path("effectivePlan").isObject()
                        !=Set.of("CONFIRMED_MATCH","COUNTER_ACCEPTED")
                                .contains(month.path("resolution").asText())) return false;
                continue;
            }
            if (month.has("conflict")) {
                if (schema!=4 || !month.has("tactic")) return false;
                try {
                    V6ConflictRules.Kind kind=V6ConflictRules.Kind.valueOf(month.path("conflict").asText());
                    String tactic=month.path("tactic").asText();
                    boolean approved=Set.of("PARTNERS_APPROVED","INDEPENDENT_CONSENSUS",
                            "COUNTER_ACCEPTED").contains(month.path("resolution").asText());
                    if (!month.has("resolution") || tactic.equals("SIGNED_STRATEGY")==approved) return false;
                    if (!tactic.equals("SIGNED_STRATEGY") && kind.options().stream().noneMatch(
                            option->option.code().equals(tactic))) return false;
                    for (String field:List.of("firstPosition","secondPosition","counter","replyCounter")) {
                        String position=month.path(field).asText();
                        if (month.has(field) && !position.equals("NONE") && kind.options().stream()
                                .noneMatch(option->option.code().equals(position))) return false;
                    }
                    if (month.path("resolution").asText().equals("INDEPENDENT_CONSENSUS")
                            && (!month.path("firstPosition").asText().equals(tactic)
                                || !month.path("secondPosition").asText().equals(tactic))) return false;
                    if (month.has("counterRounds") && (month.path("counterRounds").asInt(-1)<0
                            || month.path("counterRounds").asInt()>V6MonthlyWindow.MAX_COUNTER_ROUNDS)) return false;
                    if (month.has("replyCounter") && !month.path("replyCounter").asText().equals("NONE")
                            && (!month.has("counter") || month.path("counter").asText().equals("NONE")
                                || month.path("counterRounds").asInt()!=2)) return false;
                    if (month.path("resolution").asText().equals("COUNTER_ACCEPTED")
                            && !(month.path("replyCounter").asText("NONE").equals("NONE")
                                    ?month.path("counter").asText().equals(tactic)
                                    :month.path("replyCounter").asText().equals(tactic))) return false;
                } catch (IllegalArgumentException error) { return false; }
            } else if (month.has("tactic")) return false;
        }
        return true;
    }
    private boolean safeAnnualPlan(JsonNode value,String conflictCode) {
        if (!value.isObject() || value.size()!=4 || !onlyFields(value,
                Set.of("productionBand","marketingAction","serviceFocus","incidentResponse"))) return false;
        try {
            JsonNode incident=value.path("incidentResponse");
            V6MonthlyPlan plan=new V6MonthlyPlan(
                    V6MonthlyPlan.ProductionBand.valueOf(value.path("productionBand").asText()),
                    V6MonthlyPlan.MarketingAction.valueOf(value.path("marketingAction").asText()),
                    V6MonthlyPlan.ServiceFocus.valueOf(value.path("serviceFocus").asText()),
                    incident.isNull()?null:incident.asText());
            plan.validateFor(conflictCode==null?null:V6ConflictRules.Kind.valueOf(conflictCode));
            return true;
        } catch (IllegalArgumentException error) { return false; }
    }
    private boolean onlyFields(JsonNode value,Set<String> allowed) {
        var names=value.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    private JsonNode safeStored(Share row) {
        if (row==null || row.getPublicToken()==null) return null;
        try {
            JsonNode payload=json.readTree(row.getPayloadJson());
            return safeSnapshot(payload)?payload:null;
        } catch (Exception ignored) { return null; }
    }

    private ObjectNode projection(long userId,long activityId,ObjectNode view,Activity activity) {
        available();
        require(Set.of(4,5).contains(view.path("contractVersion").asInt()),409,"SHARE_VERSION_UNSUPPORTED");
        String status=view.path("status").asText();
        require(status.equals("SETTLED") || status.equals("INTERRUPTED"),409,"GAME_NOT_FINISHED");
        List<JsonNode> events=new ArrayList<>();
        long after=0;
        while (true) {
            List<JsonNode> page=games.ownerEvents(userId,activityId,after);
            if (page.isEmpty()) break;
            events.addAll(page);
            long next=page.get(page.size()-1).path("sequence").asLong();
            require(next>after,500,"SHARE_EVENTS_INVALID");
            after=next;
            if (page.size()<50) break;
        }
        JsonNode proposal=null,decline=null,interruption=null;
        String openedId=null;
        for (JsonNode event:events) {
            String kind=event.path("kind").asText();
            JsonNode action=event.path("facts").path("action");
            if (kind.equals("PROPOSAL")) proposal=action.path("payload").path("proposal");
            if (kind.equals("DECISION") && action.path("actionType").asText().equals("DECLINE_PLAN")) decline=event;
            if (kind.equals("INTERRUPTED")) interruption=event;
            if (kind.equals("OPENED")) openedId=event.path("facts").path("proposalId").asText();
        }
        if (status.equals("INTERRUPTED")) require(interruption!=null &&
                interruption.path("facts").path("reason").asText().equals("PLAN_DECLINED") && decline!=null,
                409,"ENDING_NOT_SHAREABLE");
        if (status.equals("SETTLED")) require(openedId!=null,409,"ENDING_NOT_SHAREABLE");
        require(proposal!=null && proposal.isObject(),409,"ENDING_NOT_SHAREABLE");
        if (openedId!=null && !proposal.path("proposalId").asText().equals(openedId)) {
            for (JsonNode event:events) if (event.path("kind").asText().equals("PROPOSAL")) {
                JsonNode candidate=event.path("facts").path("action").path("payload").path("proposal");
                if (candidate.path("proposalId").asText().equals(openedId)) proposal=candidate;
            }
        }
        if (status.equals("SETTLED")) require(openedId.equals(proposal.path("proposalId").asText()),
                409,"ENDING_NOT_SHAREABLE");
        JsonNode plan=proposal.path("plan");
        List<String> privateText=privateTerms(activity);
        boolean annual=Set.of("1.0","1.1").contains(view.path("ruleVersion").asText());
        boolean v6=annual || view.path("ruleVersion").asText().equals("0.9");
        ObjectNode result=json.createObjectNode().put("shareSchemaVersion",annual?5:v6?4:3).put("gameKey","ODD_SHOP")
                .put("outcome",status.equals("SETTLED")?"OPERATED":"UNOPENED")
                .put("horizonMonths",view.path("horizonMonths").asInt());
        if (v6 && status.equals("SETTLED")) {
            try { V6FoundingContract.parse(plan.path("foundingAgreement")); }
            catch (IllegalArgumentException error) { throw new BusinessException(409,"ENDING_NOT_SHAREABLE"); }
            result.set("foundingAgreement",plan.path("foundingAgreement").deepCopy());
        }
        ObjectNode shop=result.putObject("shop").put("name",sanitize(plan.path("shopName").asText(""),privateText,80));
        JsonNode venture=plan.path("venture");
        for (String field:List.of("concept","audience","experience","marketing"))
            if (venture.path(field).isTextual()) shop.put(field,sanitize(venture.path(field).asText(),privateText,160));
        if (view.path("contractVersion").asInt()==5) {
            JsonNode strategy=plan.path("strategy");
            shop.putObject("strategy").put("audienceSegment",strategy.path("audienceSegment").asText())
                    .put("marketingChannel",strategy.path("marketingChannel").asText())
                    .put("servicePromise",strategy.path("servicePromise").asText());
        }
        ArrayNode moves=result.putArray("agentMoves");
        for (JsonNode event:events) {
            String kind=event.path("kind").asText();
            JsonNode action=event.path("facts").path("action");
            String move=kind.equals("PROPOSAL")?action.path("actionType").asText():
                    kind.equals("DECISION")?action.path("actionType").asText():
                    kind.equals("FINAL_NOTE")?"FINAL_NOTE":
                    kind.equals("MONTHLY_DECISION") || kind.equals("V6_POSITION")
                            || kind.equals("V6_MONTHLY_DECISION") || kind.equals("V6_ANNUAL_DECISION")
                            || kind.equals("FRANCHISE_DECISION")
                            || kind.equals("FRANCHISE_INVESTIGATION")?action.path("actionType").asText():"";
            if (!List.of("PROPOSE_PLAN","COUNTER_PLAN","ACCEPT_PLAN","DECLINE_PLAN","FINAL_NOTE",
                    "PROPOSE_MONTHLY","POSITION_MONTHLY","COUNTER_MONTHLY","REPLY_MONTHLY",
                    "ACCEPT_MONTHLY","DECLINE_MONTHLY","RETRACT_MONTHLY",
                    "CHECK_FRANCHISE_TERMS","CHECK_FRANCHISE_STORES","CHECK_FRANCHISE_SUPPLY",
                    "PROPOSE_FRANCHISE","COUNTER_FRANCHISE","ACCEPT_FRANCHISE","DECLINE_FRANCHISE").contains(move)) continue;
            String actor=event.path("facts").path("actorId").asText().replace("agent:","");
            moves.addObject().put("role",actor.equals(view.path("hostAgentId").asText())?"HOST":"GUEST")
                    .put("move",move);
        }
        if (annual && status.equals("SETTLED")) {
            ArrayNode reviews=result.putArray("partnerReviews");
            for (String role:List.of("HOST","GUEST")) {
                String actor=view.path(role.equals("HOST")?"hostAgentId":"guestAgentId").asText();
                JsonNode review=view.path("partnerReviews").path(actor);
                ObjectNode publicReview=reviews.addObject().put("role",role);
                if (!review.isObject()) { publicReview.put("status","UNAVAILABLE"); continue; }
                JsonNode action=review.path("payload");
                String cited=action.path("evidenceEventId").asText();
                JsonNode source=events.stream().filter(item -> item.path("eventId").asText().equals(cited))
                        .findFirst().orElse(null);
                require(source!=null,409,"ENDING_NOT_SHAREABLE");
                publicReview.put("status","RECORDED")
                        .put("partnerStrength",sanitize(action.path("partnerStrength").asText(),privateText,160))
                        .put("friction",sanitize(action.path("friction").asText(),privateText,160))
                        .put("futureCollaboration",action.path("futureCollaboration").asText())
                        .put("publicRationale",sanitize(review.path("publicRationale").asText(),privateText,300))
                        .put("evidenceMonth",source.path("virtualMonth").asInt())
                        .put("evidenceMove",source.path("facts").path("action").path("actionType").asText());
            }
        }
        if (status.equals("SETTLED")) {
            JsonNode summary=view.path("summary");
            require(summary.isObject(),409,"ENDING_NOT_SHAREABLE");
            ObjectNode business=result.putObject("business");
            business.put("ending",summary.path("ending").asText());
            business.put("operatedMonths",summary.path("operatedMonths").asInt());
            business.put("netProfitMinor",summary.path("netProfitMinor").asLong());
            if (v6) business.put("returnedCapitalMinor",summary.path("returnedCapitalMinor").asLong())
                    .put("hostReturnedMinor",summary.path("ownerOneReturnedMinor").asLong())
                    .put("guestReturnedMinor",summary.path("ownerTwoReturnedMinor").asLong());
            JsonNode franchiseResolution=events.stream().filter(event ->
                    event.path("kind").asText().equals("FRANCHISE_RESOLUTION")).findFirst().orElse(null);
            if (franchiseResolution!=null) {
                String resolution=franchiseResolution.path("facts").path("resolution").asText();
                String support="NOT_SIGNED";
                if (resolution.equals("SIGNED")) {
                    support="PENDING";
                    for (JsonNode report:summary.path("reports")) for (JsonNode marker:report.path("events")) {
                        String code=marker.asText();
                        if (code.equals("FRANCHISE_SUPPORT_DELIVERED")) support="DELIVERED";
                        if (code.equals("FRANCHISE_SUPPORT_WEAK")) support="WEAK";
                        if (code.equals("FRANCHISE_SUPPORT_ABSENT")) support="ABSENT";
                    }
                }
                business.putObject("franchise")
                        .put("month",franchiseResolution.path("virtualMonth").asInt(3))
                        .put("resolution",resolution)
                        .put("supportOutcome",support);
            }
            ArrayNode months=business.putArray("months");
            for (JsonNode report:summary.path("reports")) {
                ObjectNode month=months.addObject().put("month",report.path("month").asInt())
                        .put("profitMinor",report.path("profitMinor").asLong());
                ArrayNode markers=month.putArray("events");
                for (JsonNode marker:report.path("events")) if (marker.isTextual()) markers.add(marker.asText());
                if (view.path("contractVersion").asInt()==5) {
                    for (JsonNode event:events) {
                        if (event.path("virtualMonth").asInt()!=report.path("month").asInt()) continue;
                        if (event.path("kind").asText().equals("MONTHLY_SIGNAL")
                                || event.path("kind").asText().equals("MONTHLY_CONTINUITY")
                                || event.path("kind").asText().equals("V6_MONTHLY_BRIEF"))
                            month.put("signal",event.path("facts").path("signal").asText());
                        if (event.path("kind").asText().equals("MONTHLY_RESOLUTION")) {
                            month.put("response",event.path("facts").path("effectiveResponse").asText());
                            month.put("resolution",event.path("facts").path("resolution").asText());
                        }
                        if (event.path("kind").asText().equals("MONTHLY_CONTINUITY"))
                            month.put("response","KEEP_IDENTITY");
                        if (annual && event.path("kind").asText().equals("V6_ANNUAL_RESOLUTION")) {
                            JsonNode facts=event.path("facts");
                            month.put("resolution",facts.path("resolution").asText());
                            month.put("response",facts.path("effectiveResponse").asText());
                            if (facts.path("effectivePlan").isObject())
                                month.set("effectivePlan",facts.path("effectivePlan").deepCopy());
                            else month.put("effectivePlan","SIGNED_FALLBACK");
                            for (String field:List.of("firstPlan","secondPlan","firstReply","secondReply"))
                                if (facts.path(field).isObject()) month.set(field,facts.path(field).deepCopy());
                            if (events.stream().anyMatch(e->e.path("kind").asText().equals("V6_CONFLICT")
                                    && e.path("facts").path("triggerEventId").asText()
                                            .equals(facts.path("triggerEventId").asText())))
                                month.put("conflict",events.stream()
                                        .filter(e->e.path("kind").asText().equals("V6_CONFLICT")
                                            && e.path("facts").path("triggerEventId").asText()
                                                .equals(facts.path("triggerEventId").asText()))
                                        .findFirst().orElseThrow().path("facts").path("kind").asText());
                        }
                        if (v6 && event.path("kind").asText().equals("V6_CONFLICT_RESOLUTION"))
                            month.put("conflict",event.path("facts").path("kind").asText())
                                    .put("tactic",event.path("facts").path("selectedOption").asText())
                                    .put("firstPosition",event.path("facts").path("firstPosition").asText("NONE"))
                                    .put("secondPosition",event.path("facts").path("secondPosition").asText("NONE"))
                                    .put("counter",event.path("facts").path("counter").asText("NONE"))
                                    .put("replyCounter",event.path("facts").path("replyCounter").asText("NONE"))
                                    .put("counterRounds",event.path("facts").path("counterRounds").asInt());
                    }
                }
            }
        }
        return result;
    }

    private List<String> privateTerms(Activity activity) {
        try {
            List<String> terms=new ArrayList<>();
            JsonNode briefs=json.readTree(activity.getStateJson()).path("ownerBriefs");
            for (JsonNode brief:briefs) {
                JsonNode message=brief.path("ownerMessage");
                if (message.isTextual() && message.asText().length()>=8) terms.add(message.asText());
                for (String field:List.of("hardConstraints","negotiable"))
                    for (JsonNode item:brief.path(field)) if (item.isTextual() && item.asText().length()>=8)
                        terms.add(item.asText());
            }
            return terms;
        } catch (Exception error) { throw new BusinessException(500,"SHARE_STATE_INVALID"); }
    }
    private String sanitize(String value,List<String> privateText,int maxLength) {
        String result=value;
        for (String term:privateText) result=Pattern.compile(Pattern.quote(term),Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(result).replaceAll("[已隐藏]");
        result=result.replaceAll("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b","[已隐藏]")
                .replaceAll("(?i)https?://\\S+","[已隐藏]")
                .replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])","[已隐藏]");
        return result.length()>maxLength?result.substring(0,maxLength):result;
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new BusinessException(500,"SHARE_STATE_INVALID"); }
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    private void available() { require(enabled,404,"PLAYGROUND_DISABLED"); }
    private void require(boolean condition,int status,String message) {
        if (!condition) throw new BusinessException(status,message);
    }
}
