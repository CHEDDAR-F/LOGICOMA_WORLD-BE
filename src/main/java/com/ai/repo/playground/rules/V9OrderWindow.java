package com.ai.repo.playground.rules;

import java.time.Instant;

/** Bounded dual-Agent negotiation of the same finite cross-month order. */
public record V9OrderWindow(String triggerEventId,int month,long firstAgentId,long secondAgentId,
        Instant deadline,Phase phase,
        V9CapacityOrder.Choice firstPlan,V9CapacityOrder.Choice secondPlan,V9CapacityOrder.Choice firstReply,
        V9CapacityOrder.Choice secondReply,V9CapacityOrder.Choice effectivePlan,int offerRevision,
        Resolution resolution) {
    public enum Phase { AWAIT_FIRST, AWAIT_SECOND, AWAIT_CONFIRM, AWAIT_FIRST_REPLY,
        AWAIT_SECOND_REPLY, AWAIT_LAST_REPLY, CLOSED }
    public enum Resolution { CONFIRMED_MATCH, COUNTER_ACCEPTED, DECLINED, RETRACTED,
        DEADLINE_FALLBACK, BUDGET_FALLBACK, MODEL_FAILURE_FALLBACK }

    public V9OrderWindow {
        require(triggerEventId!=null && triggerEventId.matches("[A-Za-z0-9:_-]{1,100}")
                && month==3 && firstAgentId>0 && secondAgentId>0
                && firstAgentId!=secondAgentId && deadline!=null && phase!=null
                && offerRevision>=0 && offerRevision<=4,"INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
        switch (phase) {
            case AWAIT_FIRST -> require(firstPlan==null && secondPlan==null && offerRevision==0,
                    "INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
            case AWAIT_SECOND -> require(firstPlan!=null && secondPlan==null && offerRevision==1,
                    "INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
            case AWAIT_CONFIRM -> require(firstPlan!=null && firstPlan.equals(secondPlan)
                    && firstReply==null && offerRevision==2,"INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
            case AWAIT_FIRST_REPLY -> require(firstPlan!=null && secondPlan!=null
                    && !firstPlan.equals(secondPlan) && firstReply==null && offerRevision==2,
                    "INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
            case AWAIT_SECOND_REPLY -> require(firstReply!=null && secondReply==null
                    && offerRevision==3,"INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
            case AWAIT_LAST_REPLY -> require(firstReply!=null && secondReply!=null
                    && offerRevision==4,
                    "INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
            case CLOSED -> require(resolution!=null && ((effectivePlan!=null)
                    == (resolution==Resolution.CONFIRMED_MATCH
                        || resolution==Resolution.COUNTER_ACCEPTED)),"INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
        }
        if (phase!=Phase.CLOSED) require(resolution==null && effectivePlan==null,
                "INVALID_CAPACITY_ORDER_ANNUAL_WINDOW");
    }

    public static V9OrderWindow open(String triggerEventId,int month,long firstAgentId,
            long secondAgentId,Instant deadline) {
        return new V9OrderWindow(triggerEventId,month,firstAgentId,secondAgentId,deadline,
                Phase.AWAIT_FIRST,null,null,null,null,null,0,null);
    }

    public long currentActor() {
        return switch (phase) {
            case AWAIT_FIRST, AWAIT_CONFIRM, AWAIT_FIRST_REPLY, AWAIT_LAST_REPLY -> firstAgentId;
            case AWAIT_SECOND, AWAIT_SECOND_REPLY -> secondAgentId;
            case CLOSED -> throw new IllegalStateException("CAPACITY_ORDER_ANNUAL_WINDOW_CLOSED");
        };
    }

    public String currentOfferVersion() { return triggerEventId+":"+offerRevision; }

    public V9CapacityOrder.Choice latestOffer() {
        return secondReply!=null?secondReply:firstReply!=null?firstReply:secondPlan!=null?secondPlan:firstPlan;
    }

    public V9CapacityOrder.Choice ownLastPlan(long actor) {
        require(actor==firstAgentId || actor==secondAgentId,"CAPACITY_ORDER_ACTION_NOT_ALLOWED");
        return actor==firstAgentId?(firstReply!=null?firstReply:firstPlan)
                :(secondReply!=null?secondReply:secondPlan);
    }

    public V9OrderWindow position(long actor,String targetVersion,V9CapacityOrder.Choice plan,Instant now) {
        require(phase==Phase.AWAIT_FIRST || phase==Phase.AWAIT_SECOND,"CAPACITY_ORDER_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now); require(plan!=null,"INVALID_ORDER_CHOICE");
        if (phase==Phase.AWAIT_FIRST)
            return copy(Phase.AWAIT_SECOND,plan,null,null,null,null,1,null);
        return copy(firstPlan.equals(plan)?Phase.AWAIT_CONFIRM:Phase.AWAIT_FIRST_REPLY,
                firstPlan,plan,null,null,null,2,null);
    }

    /** The action label is derived from the actual full-plan delta, not trusted model text. */
    public V9OrderWindow reply(long actor,String targetVersion,V9CapacityOrder.Choice plan,
            boolean claimsRevision,Instant now) {
        require(phase==Phase.AWAIT_FIRST_REPLY || phase==Phase.AWAIT_SECOND_REPLY,
                "CAPACITY_ORDER_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now); require(plan!=null,"INVALID_ORDER_CHOICE");
        boolean changed=!plan.equals(ownLastPlan(actor));
        require(changed==claimsRevision,"CAPACITY_ORDER_REPLY_PLAN_MISMATCH");
        if (phase==Phase.AWAIT_FIRST_REPLY)
            return copy(Phase.AWAIT_SECOND_REPLY,firstPlan,secondPlan,plan,null,null,3,null);
        return copy(Phase.AWAIT_LAST_REPLY,firstPlan,secondPlan,firstReply,plan,null,4,null);
    }

    public V9OrderWindow accept(long actor,String targetVersion,Instant now) {
        require(phase==Phase.AWAIT_CONFIRM || phase==Phase.AWAIT_SECOND_REPLY
                || phase==Phase.AWAIT_LAST_REPLY,"CAPACITY_ORDER_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now);
        return copy(Phase.CLOSED,firstPlan,secondPlan,firstReply,secondReply,latestOffer(),
                offerRevision,phase==Phase.AWAIT_CONFIRM?Resolution.CONFIRMED_MATCH
                        :Resolution.COUNTER_ACCEPTED);
    }

    public V9OrderWindow decline(long actor,String targetVersion,Instant now) {
        require(phase==Phase.AWAIT_SECOND_REPLY || phase==Phase.AWAIT_LAST_REPLY,
                "CAPACITY_ORDER_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now);
        require(!latestOffer().equals(ownLastPlan(actor)),"CAPACITY_ORDER_DECLINE_OWN_PLAN");
        return copy(Phase.CLOSED,firstPlan,secondPlan,firstReply,secondReply,null,
                offerRevision,Resolution.DECLINED);
    }

    public V9OrderWindow retract(long actor,String targetVersion,Instant now) {
        require(phase==Phase.AWAIT_SECOND_REPLY || phase==Phase.AWAIT_LAST_REPLY,
                "CAPACITY_ORDER_ACTION_NOT_ALLOWED");
        action(actor,targetVersion,now);
        require(latestOffer().equals(ownLastPlan(actor)),"CAPACITY_ORDER_RETRACT_NOT_OWN_PLAN");
        return copy(Phase.CLOSED,firstPlan,secondPlan,firstReply,secondReply,null,
                offerRevision,Resolution.RETRACTED);
    }

    public V9OrderWindow miss(Resolution why) {
        require(phase!=Phase.CLOSED && (why==Resolution.DEADLINE_FALLBACK
                || why==Resolution.BUDGET_FALLBACK || why==Resolution.MODEL_FAILURE_FALLBACK),
                "INVALID_CAPACITY_ORDER_ANNUAL_FALLBACK");
        return copy(Phase.CLOSED,firstPlan,secondPlan,firstReply,secondReply,null,offerRevision,why);
    }

    private V9OrderWindow copy(Phase next,V9CapacityOrder.Choice first,V9CapacityOrder.Choice second,
            V9CapacityOrder.Choice firstAnswer,V9CapacityOrder.Choice secondAnswer,V9CapacityOrder.Choice effective,
            int revision,Resolution why) {
        return new V9OrderWindow(triggerEventId,month,firstAgentId,secondAgentId,deadline,
                next,first,second,firstAnswer,secondAnswer,effective,revision,why);
    }

    private void action(long actor,String targetVersion,Instant now) {
        require(actor==currentActor(),"CAPACITY_ORDER_ACTION_NOT_ALLOWED");
        require(currentOfferVersion().equals(targetVersion),"STALE_CAPACITY_ORDER_OFFER");
        require(now!=null && now.isBefore(deadline),"CAPACITY_ORDER_ANNUAL_WINDOW_EXPIRED");
    }

    private static void require(boolean valid,String error) {
        if (!valid) throw new IllegalArgumentException(error);
    }
}
