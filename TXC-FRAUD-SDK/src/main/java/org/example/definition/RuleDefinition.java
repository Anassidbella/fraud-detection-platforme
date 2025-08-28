package org.example.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true) // Important for flexibility
public class RuleDefinition {
    private String ruleId;
    private String description;
    private String domain; // Optional
    private boolean isEnabled = true; // Default to true
    private int priority = 0;      // Default priority
    private ConditionGroupDefinition conditions;
    private List<ActionDefinition> actions;

    // Getters and Setters for all fields
    // Ensure to add them for Jackson to work correctly during deserialization

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public ConditionGroupDefinition getConditions() {
        return conditions;
    }

    public void setConditions(ConditionGroupDefinition conditions) {
        this.conditions = conditions;
    }

    public List<ActionDefinition> getActions() {
        return actions;
    }

    public void setActions(List<ActionDefinition> actions) {
        this.actions = actions;
    }

    @Override
    public String toString() {
        return "RuleDefinition{" + "ruleId='" + ruleId + '\'' + ", description='" + description + '\'' + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleDefinition that = (RuleDefinition) o;
        return Objects.equals(ruleId, that.ruleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruleId);
    }
}