package org.example.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConditionGroupDefinition {
    private String logicalOperator; // "AND" or "OR"
    private List<Object> clauses; // Can contain ConditionClauseDefinition or nested ConditionGroupDefinition

    // Getters and Setters
    public String getLogicalOperator() {
        return logicalOperator;
    }

    public void setLogicalOperator(String logicalOperator) {
        this.logicalOperator = logicalOperator;
    }

    public List<Object> getClauses() {
        return clauses;
    }

    public void setClauses(List<Object> clauses) {
        this.clauses = clauses;
    }
}