package com.ai.repo.playground.dto;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class PlaygroundRequests {
    private PlaygroundRequests() {}
    public interface StrictRequest {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        default void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("UNKNOWN_PLAYGROUND_FIELD"); }
    }
    public record ParticipationUpdate(@Min(0) long expectedVersion, @NotNull Boolean enabled,
                                      @Min(1) @Max(40) int maxDecisions, @Min(1) @Max(40) int maxAttempts,
                                      @Min(1) @Max(40) int maxDailyAttempts) implements StrictRequest {}
    public record OwnerBrief(@NotBlank @Size(max=160) String theme,
                             @NotNull @Pattern(regexp="PROFIT|CHARACTER|COOPERATION|FREE") String priority,
                             @NotNull @Size(max=5) List<@NotBlank @Size(max=200) String> hardConstraints,
                             @NotNull @Size(max=5) List<@NotBlank @Size(max=200) String> negotiable,
                             @NotNull @Size(max=4) List<@Pattern(regexp="theme|priority|hardConstraints|negotiable") String> disclosableFields,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             @Size(max=4) List<@Pattern(regexp="theme|priority|hardConstraints|negotiable") String> partnerShareFields,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             @Min(4) @Max(5) Integer gameContractVersion,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             @Size(max=240) String ownerMessage,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Boolean agentMayReferenceOwnFields) implements StrictRequest {
        public OwnerBrief(String theme, String priority, List<String> hardConstraints,
                          List<String> negotiable, List<String> disclosableFields) {
            this(theme,priority,hardConstraints,negotiable,disclosableFields,null,null,null,null);
        }
        public OwnerBrief(String theme, String priority, List<String> hardConstraints,
                          List<String> negotiable, List<String> disclosableFields,
                          List<String> partnerShareFields) {
            this(theme,priority,hardConstraints,negotiable,disclosableFields,partnerShareFields,null,null,null);
        }
        public OwnerBrief(String theme, String priority, List<String> hardConstraints,
                          List<String> negotiable, List<String> disclosableFields,
                          List<String> partnerShareFields, Integer gameContractVersion) {
            this(theme,priority,hardConstraints,negotiable,disclosableFields,partnerShareFields,gameContractVersion,null,null);
        }
        public OwnerBrief(String theme, String priority, List<String> hardConstraints,
                          List<String> negotiable, List<String> disclosableFields,
                          List<String> partnerShareFields, Integer gameContractVersion, String ownerMessage) {
            this(theme,priority,hardConstraints,negotiable,disclosableFields,partnerShareFields,gameContractVersion,ownerMessage,null);
        }
        @AssertTrue(message="Disclosure fields must be unique") @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isDisclosureUnique() {
            return (disclosableFields==null || disclosableFields.stream().distinct().count()==disclosableFields.size())
                    && (partnerShareFields==null || partnerShareFields.stream().distinct().count()==partnerShareFields.size())
                    && (!Boolean.TRUE.equals(agentMayReferenceOwnFields) || partnerShareFields!=null && partnerShareFields.isEmpty());
        }
    }
    public record MatchJoin(@NotNull @Min(1) Long agentId,
                            @NotNull @Pattern(regexp="SHORT|FULL") String mode,
                            @NotNull @Valid OwnerBrief ownerBrief) implements StrictRequest {}
    public record Invitation(@NotNull @Min(1) Long agentId, @NotNull @Min(1) Long partnerAgentId,
                             @NotNull @Pattern(regexp="SHORT|FULL") String mode,
                             @NotNull @Valid OwnerBrief ownerBrief) implements StrictRequest {}
    public record InvitationAccept(@NotNull @Valid OwnerBrief ownerBrief) implements StrictRequest {}
    public record Claim(@Min(1) long permissionVersion) implements StrictRequest {}
    public record Lease(@NotBlank @Size(min=16,max=256) String leaseToken,
                        @Min(1) long permissionVersion) implements StrictRequest {}
    public record AttemptStart(@NotBlank @Size(min=16,max=256) String leaseToken,
                               @Min(1) long permissionVersion,
                               @NotNull @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String idempotencyKey) implements StrictRequest {}
    public record AttemptFailure(@NotBlank @Size(min=16,max=256) String leaseToken,
                                 @Min(1) long permissionVersion,
                                 @NotNull @Pattern(regexp="[1-9][0-9]*") String attemptId,
                                 @NotNull @Pattern(regexp="MODEL_OUTPUT_INVALID|MODEL_RESPONSE_INCOMPLETE|MODEL_REFUSED|MODEL_PROVIDER_REJECTED") String reasonCode,
                                 @Pattern(regexp="V5_STRATEGY_ENUM|PLAN_CONTRIBUTIONS|ACTION_SHAPE") String formatHint) implements StrictRequest {
        public AttemptFailure(String leaseToken,long permissionVersion,String attemptId,String reasonCode) {
            this(leaseToken,permissionVersion,attemptId,reasonCode,null);
        }
    }
    public record Submission(@NotNull @Pattern(regexp="[1-9][0-9]*") String taskId,
                             @NotNull @Pattern(regexp="[1-9][0-9]*") String activityId,
                             @NotBlank @Size(min=16,max=256) String leaseToken,
                             @Min(1) long permissionVersion,
                             @NotNull @Pattern(regexp="[1-9][0-9]*") String attemptId,
                             @NotNull @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String idempotencyKey,
                             @NotNull JsonNode action) implements StrictRequest {}
}
