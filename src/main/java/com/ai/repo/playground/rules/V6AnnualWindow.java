package com.ai.repo.playground.rules;

import java.time.Instant;

/** Persisted negotiation over a complete next-month plan. No model prose can
 * advance this state machine or change the month, target revision or economics.
 */
public record V6AnnualWindow(String triggerEventId,int month,long firstAgentId,long secondAgentId,
        Instant deadline,V6ConflictRules.Kind conflict,Phase phase,
        V6MonthlyPlan firstPlan,V6MonthlyPlan secondPlan,V6MonthlyPlan firstReply,
        V6MonthlyPlan secondReply,V6MonthlyPlan effectivePlan,int offerRevision,
        Resolution resolution) {
    public enum Phase { AWAIT_FIRST, AWAIT_SECOND, AWAIT_CONFIRM, AWAIT_FIRST_REPLY,
        AWAIT_SECOND_REPLY, AWAIT_LAST_REPLY, CLOSED }
    public enum Resolution { CONFIRMED_MATCH, COUNTER_ACCEPTED, DECLINED, RETRACTED,
        DEADLINE_FALLBACK, BUDGET_FALLBACK, MODEL_FAILURE_FALLBACK }

    public V6AnnualWindow {
        require(triggerEventId!=null && triggerEventId.matches("[A-Za-z0-9:_-]{1,100}")
                && month>=2 && month<=12 && firstAgentId>0 && secondAgentId>0
                && firstAgentId!=secondAgentId && deadline!=null && phase!=null
                && offerRevision>=0 && offerRevision<=4,"INVALID_V6_ANNUAL_WINDOW");
        if (firstPlan!=null) firstPlan.validateFor(conflict);
        if (secondPlan!=null) secondPlan.validateFor(conflict);
        if (firstReply!=null) firstReply.validateFor(conflict);
        if (secondReply!=null) secondReply.validateFor(conflict);
        if (effectivePlan!=null) effectivePlan.validateFor(conflict);
        switch (phase) {
            case AWAIT_FIRST -> require(firstPlan==null && secondPlan==null && offerRevision==0,
                    "INVALID_V6_ANNUAL_WINDOW");
            case AWAIT_SECOND -> require(firstPlan!=null && secondPlan==null && offerRevision==1,
                    "INVALID_V6_ANNUAL_WINDOW");
            case AWAIT_CONFIRM -> require(firstPlan!=null && firstPlan.equals(secondPlan)
                    && firstReply==null && offerRevision==2,"INVALID_V6_ANNUAL_WINDOW");
            case AWAIT_FIRST_REPLY -> require(firstPlan!=null && secondPlan!=null
                    && !firstPlan.equals(secondPlan) && firstReply==null && offerRevision==2,
                    "INVALID_V6_ANNUAL_WINDOW");
            case AWAIT_SECOND_REPLY -> require(firstReply!=null && secondReply==null
                    && offerRevision==3,"INVALID_V6_ANNUAL_WINDOW");
            case AWAIT_LAST_REPLY -> require(firstReply!=null && secondReply!=null
                    && offerRevision==4,
                    "INVALID_V6_ANNUAL_WINDOW");
            case CLOSED -> require(resolution!=null && ((effectivePlan!=null)
                    == (resolution==Resolution.CONFIRMED_MATCH
                        || resolution==Resolution.COUNTER_ACCEPTED)),"INVALID_V6_ANNUAL_WINDOW");
        }
        if (phase!=Phase.CLOSED) require(resolution==null && effectivePlan==null,
                "INVALID_V6_ANNUAL_WINDOW");
    }

    public static V6AnnualWindow open(String triggerEventId,int month,long firstAgentId,
            long secondAgentId,Instant deadline,V6ConflictRules.Kind conflict) {
        return new V6AnnualWindow(triggerEventId,month,firstAgentId,secondAgentId,deadline,
                conflict,Phase.AWAIT_FIRST,null,null,null,null,null,0,null);
    }

    public long currentActor() {
        return switch (phase) {
            case AWAIT_FIRST, AWAIT_CONFIRM, AWAIT_FIRST_REPLY, AWAIT_LAST_REPLY -> firstAgentId;
            case AWAIT_SECOND, AWAIT_SECOND_REPLY -> secondAgentId;
            case CLOSED -> throw new IllegalStateException("V6_ANNUAL_WINDOW_CLOSED");
        };
    }

    public String currentOfferVersion() { return triggerEventId+":"+offerRevision; }

    public V6MonthlyPlan latestOffer() {
        return secondReply!=null?secondReply:firstReply!=null?firstReply:secondPlan!=null?secondPlan:firstPlan;
    }

    public V6MonthlyPlan ownLastPlan(long actor) {
        require(actor==firstAgentId || actor==secondAgentId,"V6_ACTION_NOT_ALLOWED");
        return actor==firstAgentId?(firstReply!=null?firstReply:firstPlan)
                :(secondReply!=null?secondReply:secondPlan);
    }

    public V6AnnualWindow position(long actor,String targetVersion,V6MonthlyPlan plan,Instant now) {
        require(phase==Phase.AWAIT_FIRST || phase==Phase.AWAIT_SECOND,"V6_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now); plan.validateFor(conflict);
        if (phase==Phase.AWAIT_FIRST)
            return copy(Phase.AWAIT_SECOND,plan,null,null,null,null,1,null);
        return copy(firstPlan.equals(plan)?Phase.AWAIT_CONFIRM:Phase.AWAIT_FIRST_REPLY,
                firstPlan,plan,null,null,null,2,null);
    }

    /** The action label is derived from the actual full-plan delta, not trusted model text. */
    public V6AnnualWindow reply(long actor,String targetVersion,V6MonthlyPlan plan,
            boolean claimsRevision,Instant now) {
        require(phase==Phase.AWAIT_FIRST_REPLY || phase==Phase.AWAIT_SECOND_REPLY,
                "V6_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now); plan.validateFor(conflict);
        boolean changed=!plan.equals(ownLastPlan(actor));
        require(changed==claimsRevision,"V6_REPLY_PLAN_MISMATCH");
        if (phase==Phase.AWAIT_FIRST_REPLY)
            return copy(Phase.AWAIT_SECOND_REPLY,firstPlan,secondPlan,plan,null,null,3,null);
        return copy(Phase.AWAIT_LAST_REPLY,firstPlan,secondPlan,firstReply,plan,null,4,null);
    }

    public V6AnnualWindow accept(long actor,String targetVersion,Instant now) {
        require(phase==Phase.AWAIT_CONFIRM || phase==Phase.AWAIT_SECOND_REPLY
                || phase==Phase.AWAIT_LAST_REPLY,"V6_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now);
        return copy(Phase.CLOSED,firstPlan,secondPlan,firstReply,secondReply,latestOffer(),
                offerRevision,phase==Phase.AWAIT_CONFIRM?Resolution.CONFIRMED_MATCH
                        :Resolution.COUNTER_ACCEPTED);
    }

    public V6AnnualWindow decline(long actor,String targetVersion,Instant now) {
        require(phase==Phase.AWAIT_SECOND_REPLY || phase==Phase.AWAIT_LAST_REPLY,
                "V6_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now);
        require(!latestOffer().equals(ownLastPlan(actor)),"V6_DECLINE_OWN_PLAN");
        return copy(Phase.CLOSED,firstPlan,secondPlan,firstReply,secondReply,null,
                offerRevision,Resolution.DECLINED);
    }

    public V6AnnualWindow retract(long actor,String targetVersion,Instant now) {
        require(phase==Phase.AWAIT_SECOND_REPLY || phase==Phase.AWAIT_LAST_REPLY,
                "V6_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now);
        require(latestOffer().equals(ownLastPlan(actor)),"V6_RETRACT_NOT_OWN_PLAN");
        return copy(Phase.CLOSED,firstPlan,secondPlan,firstReply,secondReply,null,
                offerRevision,Resolution.RETRACTED);
    }

    public V6AnnualWindow miss(Resolution why) {
        require(phase!=Phase.CLOSED && (why==Resolution.DEADLINE_FALLBACK
                || why==Resolution.BUDGET_FALLBACK || why==Resolution.MODEL_FAILURE_FALLBACK),
                "INVALID_V6_ANNUAL_FALLBACK");
        return copy(Phase.CLOSED,firstPlan,secondPlan,firstReply,secondReply,null,offerRevision,why);
    }

    private V6AnnualWindow copy(Phase next,V6MonthlyPlan first,V6MonthlyPlan second,
            V6MonthlyPlan firstAnswer,V6MonthlyPlan secondAnswer,V6MonthlyPlan effective,
            int revision,Resolution why) {
        return new V6AnnualWindow(triggerEventId,month,firstAgentId,secondAgentId,deadline,conflict,
                next,first,second,firstAnswer,secondAnswer,effective,revision,why);
    }

    private void action(long actor,String targetVersion,Instant now) {
        require(actor==currentActor(),"V6_ACTION_NOT_ALLOWED");
        require(currentOfferVersion().equals(targetVersion),"STALE_V6_ANNUAL_OFFER");
        require(now!=null && now.isBefore(deadline),"V6_ANNUAL_WINDOW_EXPIRED");
    }

    private static void require(boolean valid,String error) {
        if (!valid) throw new IllegalArgumentException(error);
    }
}
