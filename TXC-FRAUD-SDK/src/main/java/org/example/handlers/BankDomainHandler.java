package org.example.handlers;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.definition.*;
import org.example.rules.FinalRuleOutput;
import org.example.rules.TransactionRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

public class BankDomainHandler implements IDomainHandler {
    private static final Logger logger = LoggerFactory.getLogger(BankDomainHandler.class);

    private OrtSession session;
    private DomainConfig domainConfig;
    private List<RuleDefinition> parsedRules;
    private final ObjectMapper ruleObjectMapper = new ObjectMapper();

    public BankDomainHandler() {
        this.parsedRules = Collections.emptyList();
    }

    @Override
    public void initialize(DomainConfig domainConfig, OrtEnvironment env, File onnxModelFile) throws OrtException {
        this.domainConfig = domainConfig;
        this.session = env.createSession(onnxModelFile.getAbsolutePath(), new OrtSession.SessionOptions());
        logger.info("BankDomainHandler: ONNX session created. Inputs: {}", this.session.getInputNames());
    }

    @Override
    public void initializeRuleEngine(List<RuleDefinition> rules) {
        this.parsedRules = (rules != null) ? rules : Collections.emptyList();
        logger.info("Domain [{}]: Successfully loaded {} rules.", this.domainConfig.getDomainName(), this.parsedRules.size());
    }

    @Override
    public Class<?> getExpectedInputPojoType() {
        return BankTransactionInput.class;
    }

    @Override
    public Map<String, Object> preprocess(Object rawInputData, DomainConfig config) {
        BankTransactionInput raw = (BankTransactionInput) rawInputData;
        BankTransactionInput.ClientContext context = raw.getClientContext();
        Map<String, Object> features = new HashMap<>();

        logger.info("--- Starting preprocessing for Bank Domain to match final training pipeline ---");

        features.put("transaction_amount", (float) raw.getTransactionAmount());
        features.put("balance_before", (float) context.getBalanceBefore());
        features.put("velocity_txn_count_1h", (float) context.getVelocityTxnCount1h());
        features.put("avg_txn_amt_for_user", (float) context.getAvgTxnAmtForUser());
        features.put("time_since_last_txn_seconds", (float) context.getTimeSinceLastTxnSeconds());
        features.put("amount_to_balance_ratio", (float) (raw.getTransactionAmount() / (context.getBalanceBefore() + 1e-6)));
        features.put("amount_vs_user_avg_ratio", (float) (raw.getTransactionAmount() / (context.getAvgTxnAmtForUser() + 1e-6)));
        features.put("is_new_device", context.isNewDevice());
        features.put("is_foreign_location", context.isForeignLocation());
        features.put("is_night", context.isNight());
        features.put("transaction_type", raw.getTransactionType());

        logger.debug("Bank domain preprocessed feature keys for ONNX model: {}", features.keySet());

        return features;
    }


    @Override
    public Map<String, OnnxTensor> createOnnxTensors(Map<String, Object> preprocessedFeatures, OrtEnvironment env) throws OrtException {
        Map<String, OnnxTensor> onnxInputs = new HashMap<>();
        for (String inputName : this.session.getInputNames()) {
            Object featureValue = preprocessedFeatures.get(inputName);
            if (featureValue == null) {
                logger.warn("Feature '{}' required by ONNX model not found in preprocessed data or is null.", inputName);
                continue;
            }
            if (featureValue instanceof Boolean) {
                featureValue = ((Boolean) featureValue) ? 1.0f : 0.0f;
            }

            if (featureValue instanceof Float) {
                onnxInputs.put(inputName, OnnxTensor.createTensor(env, new float[][]{{(Float) featureValue}}));
            } else if (featureValue instanceof String) {
                onnxInputs.put(inputName, OnnxTensor.createTensor(env, new String[][]{{(String) featureValue}}));
            } else {
                throw new OrtException("Unsupported feature type for ONNX tensor creation: " + featureValue.getClass().getName() + " for feature '" + inputName + "'");
            }
        }
        return onnxInputs;
    }

    @Override
    public FinalRuleOutput executeRules(TransactionRuleContext context) {
        FinalRuleOutput output = new FinalRuleOutput();
        output.setOriginalMlScore(context.getMlScore());

        if (this.parsedRules == null || this.parsedRules.isEmpty()) {
            output.setDecision("ALLOW");
            output.addReasonCode("DEFAULT_ALLOW_NO_RULES");
            return output;
        }

        List<RuleDefinition> sortedRules = new ArrayList<>(this.parsedRules);
        sortedRules.sort(Comparator.comparingInt(RuleDefinition::getPriority));

        for (RuleDefinition rule : sortedRules) {
            if (rule.isEnabled() && evaluateConditionGroup(rule.getConditions(), context, rule.getRuleId())) {
                output.addTriggeredRuleId(rule.getRuleId());
                if (rule.getActions() != null) {
                    rule.getActions().forEach(action -> applyAction(action, output, rule.getRuleId()));
                }
            }
        }

        if (output.getDecision() == null) {
            output.setDecision("ALLOW");
            output.addReasonCode("DEFAULT_ALLOW_NO_DECISIVE_RULES");
        }

        return output;
    }

    private boolean evaluateConditionGroup(ConditionGroupDefinition group, TransactionRuleContext context, String ruleIdForLogging) {
        if (group == null || group.getClauses() == null || group.getClauses().isEmpty()) { return true; }

        boolean isAnd = "AND".equalsIgnoreCase(group.getLogicalOperator());
        for (Object clauseObj : group.getClauses()) {
            Map<String, Object> clauseMap = (Map<String, Object>) clauseObj;
            boolean clauseMet;

            if (clauseMap.containsKey("logicalOperator")) {
                clauseMet = evaluateConditionGroup(ruleObjectMapper.convertValue(clauseMap, ConditionGroupDefinition.class), context, ruleIdForLogging);
            } else {
                clauseMet = evaluateClause(ruleObjectMapper.convertValue(clauseMap, ConditionClauseDefinition.class), context, ruleIdForLogging);
            }

            if (isAnd && !clauseMet) return false;
            if (!isAnd && clauseMet) return true;
        }
        return isAnd;
    }

    // THIS IS THE NEW, CORRECTED METHOD WITH THE 'BETWEEN' OPERATOR
    private boolean evaluateClause(ConditionClauseDefinition clause, TransactionRuleContext context, String ruleIdForLogging) {
        Object actualValue = context.getValue(clause.getField());
        Object ruleValue = clause.getValue();

        if (actualValue == null) {
            return "IS_NULL".equalsIgnoreCase(clause.getOperator());
        }

        try {
            switch (clause.getOperator().toUpperCase()) {
                case "EQUALS":
                    return Objects.equals(String.valueOf(actualValue), String.valueOf(ruleValue));

                case "NOT_EQUALS":
                    return !Objects.equals(String.valueOf(actualValue), String.valueOf(ruleValue));

                case "GREATER_THAN":
                case "LESS_THAN":
                    if (!(actualValue instanceof Number) || !(ruleValue instanceof Number)) return false;
                    double actualDouble = ((Number) actualValue).doubleValue();
                    double ruleDouble = ((Number) ruleValue).doubleValue();
                    return clause.getOperator().equalsIgnoreCase("GREATER_THAN") ? actualDouble > ruleDouble : actualDouble < ruleDouble;

                case "IN_LIST":
                    if (ruleValue instanceof String) {
                        List<String> valueList = Arrays.asList(((String) ruleValue).split(","));
                        return valueList.stream().anyMatch(item -> Objects.equals(item.trim(), String.valueOf(actualValue)));
                    }
                    if (!(ruleValue instanceof List)) return false;
                    return ((List<?>) ruleValue).stream().anyMatch(item -> Objects.equals(String.valueOf(item), String.valueOf(actualValue)));

                case "BETWEEN":
                    if (!(actualValue instanceof Number) || !(ruleValue instanceof List) || ((List<?>) ruleValue).size() != 2) {
                        return false;
                    }
                    // The JSON deserializer might create a list of Integers or Doubles, so we handle both.
                    List<?> rangeValues = (List<?>) ruleValue;
                    double val = ((Number) actualValue).doubleValue();
                    double start = ((Number) rangeValues.get(0)).doubleValue();
                    double end = ((Number) rangeValues.get(1)).doubleValue();

                    // Special logic for overnight time ranges (e.g., between 22 and 5)
                    if (start > end) {
                        return val >= start || val <= end;
                    } else {
                        return val >= start && val <= end;
                    }

                default:
                    logger.warn("Rule '{}': Unsupported operator '{}'.", ruleIdForLogging, clause.getOperator());
                    return false;
            }
        } catch (Exception e) {
            logger.error("Rule '{}': CRITICAL ERROR evaluating clause for field '{}'. Value was '{}' of type '{}'.",
                    ruleIdForLogging, clause.getField(), actualValue, actualValue.getClass().getName(), e);
            return false;
        }
    }

    private void applyAction(ActionDefinition action, FinalRuleOutput output, String ruleId) {
        try {
            switch (action.getType().toUpperCase()) {
                case "SET_DECISION":
                    output.setDecision(String.valueOf(action.getValue()));
                    if (action.getReasonCode() != null) { output.addReasonCode(action.getReasonCode()); }
                    break;
                case "ADD_FLAG":
                    output.addFlag(String.valueOf(action.getValue()));
                    break;
                case "ADJUST_ML_SCORE_RELATIVE_POINTS":
                    double currentScore = (output.getFinalAdjustedScore() == -1.0) ? output.getOriginalMlScore() : output.getFinalAdjustedScore();
                    double adjustment = ((Number) action.getValue()).doubleValue();
                    output.setFinalAdjustedScore(currentScore + adjustment);
                    break;
                default:
                    logger.warn("Rule '{}': Unsupported action type: {}", ruleId, action.getType());
            }
        } catch (Exception e) {
            logger.error("Rule '{}': Error applying action type '{}'.", ruleId, action.getType(), e);
        }
    }

    @Override
    public OrtSession getSession() { return this.session; }

    @Override
    public void close() throws OrtException {
        if (this.session != null) {
            this.session.close();
            this.session = null;
        }
    }
}