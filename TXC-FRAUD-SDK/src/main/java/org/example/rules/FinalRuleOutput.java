package org.example.rules;

import java.util.ArrayList;
import java.util.List;

public class FinalRuleOutput {
    private String decision; // e.g., "ALLOW", "DENY", "REVIEW"
    private double originalMlScore;
    private double finalAdjustedScore;
    private List<String> reasonCodes;
    private List<String> flags;
    private List<String> triggeredRuleIds; // Good for audit/logging

    public FinalRuleOutput() {
        this.reasonCodes = new ArrayList<>();
        this.flags = new ArrayList<>();
        this.triggeredRuleIds = new ArrayList<>();
        // Initialize scores to indicate they might not have been set/adjusted yet
        this.originalMlScore = -1.0; // Or NaN
        this.finalAdjustedScore = -1.0; // Or NaN
    }

    // Getters and Setters
    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public double getOriginalMlScore() {
        return originalMlScore;
    }

    public void setOriginalMlScore(double originalMlScore) {
        this.originalMlScore = originalMlScore;
        this.finalAdjustedScore = originalMlScore; /* Initialize adjusted with original */
    }

    public double getFinalAdjustedScore() {
        return finalAdjustedScore;
    }

    public void setFinalAdjustedScore(double finalAdjustedScore) {
        this.finalAdjustedScore = finalAdjustedScore;
    }

    public List<String> getReasonCodes() {
        return reasonCodes;
    }

    public void setReasonCodes(List<String> reasonCodes) {
        this.reasonCodes = reasonCodes;
    }

    public void addReasonCode(String reasonCode) {
        this.reasonCodes.add(reasonCode);
    }

    public List<String> getFlags() {
        return flags;
    }

    public void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public void addFlag(String flag) {
        this.flags.add(flag);
    }

    public List<String> getTriggeredRuleIds() {
        return triggeredRuleIds;
    }

    public void setTriggeredRuleIds(List<String> triggeredRuleIds) {
        this.triggeredRuleIds = triggeredRuleIds;
    }

    public void addTriggeredRuleId(String ruleId) {
        this.triggeredRuleIds.add(ruleId);
    }

    @Override
    public String toString() {
        return "FinalRuleOutput{" +
                "decision='" + decision + '\'' +
                ", originalMlScore=" + originalMlScore +
                ", finalAdjustedScore=" + finalAdjustedScore +
                ", reasonCodes=" + reasonCodes +
                ", flags=" + flags +
                ", triggeredRuleIds=" + triggeredRuleIds +
                '}';
    }
}