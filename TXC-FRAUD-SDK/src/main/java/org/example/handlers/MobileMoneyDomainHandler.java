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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MobileMoneyDomainHandler implements IDomainHandler {
    private static final Logger logger = LoggerFactory.getLogger(MobileMoneyDomainHandler.class);

    private OrtSession session;
    private DomainConfig domainConfig;
    private List<RuleDefinition> parsedRules;
    private final ObjectMapper ruleObjectMapper = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MobileMoneyDomainHandler() {
        this.parsedRules = Collections.emptyList();
    }

    // ... initialize, initializeRuleEngine, getExpectedInputPojoType methods remain the same ...
    @Override
    public void initialize(DomainConfig domainConfig, OrtEnvironment env, File onnxModelFile) throws OrtException {
        this.domainConfig = domainConfig;
        this.session = env.createSession(onnxModelFile.getAbsolutePath(), new OrtSession.SessionOptions());
        logger.info("MobileMoneyDomainHandler: ONNX session created. Inputs: {}", this.session.getInputNames());
    }

    @Override
    public void initializeRuleEngine(List<RuleDefinition> rules) {
        this.parsedRules = (rules != null) ? rules : Collections.emptyList();
        logger.info("Domain [{}]: Successfully loaded {} rules.", this.domainConfig.getDomainName(), this.parsedRules.size());
    }

    @Override
    public Class<?> getExpectedInputPojoType() {
        return MobileMoneyTransactionInput.class;
    }

    @Override
    public Map<String, Object> preprocess(Object rawInputData, DomainConfig config) {
        MobileMoneyTransactionInput raw = (MobileMoneyTransactionInput) rawInputData;
        MobileMoneyTransactionInput.ClientContext context = raw.getClientContext();
        Map<String, Object> features = new HashMap<>();

        // --- FINAL, STATELESS FEATURE ENGINEERING TO MATCH AIRFLOW PIPELINE ---

        // 1. Pass-through features directly from the payload
        features.put("transaction_amount", (float) raw.getTransactionAmount());
        features.put("balance_before", (float) raw.getBalanceBefore());
        features.put("velocity_txn_count_1h", (float) context.getVelocityTxnCount1h());
        features.put("time_since_last_txn_seconds", (float) context.getTimeSinceLastTxnSeconds());
        features.put("transaction_type", raw.getTransactionType());
        features.put("currency", context.getCurrency());
        features.put("country", context.getCountry());

        // --- THIS WAS THE MISSING FEATURE ---
        features.put("avg_txn_amt_for_user", (float) context.getAvgTxnAmtForUser());

        // 2. Calculated features based only on current transaction data
        List<String> inflowTypes = Arrays.asList("cash_in", "receive_money");
        float balanceAfter = inflowTypes.contains(raw.getTransactionType()) ?
                (float) (raw.getBalanceBefore() + raw.getTransactionAmount()) :
                (float) (raw.getBalanceBefore() - raw.getTransactionAmount());
        features.put("balance_after", balanceAfter);

        double amountToBalanceRatio = raw.getTransactionAmount() / (raw.getBalanceBefore() + 1e-6);
        features.put("amount_to_balance_ratio", (float) amountToBalanceRatio);
        features.put("is_account_drain", amountToBalanceRatio > 0.9);

        // 3. Calculated feature using the client-provided user average
        double userAvgAmount = context.getAvgTxnAmtForUser();
        features.put("amount_vs_user_avg_ratio", (float) (raw.getTransactionAmount() / (userAvgAmount + 1e-6)));

        // 4. Time-based feature
        boolean isNight = false;
        try {
            LocalDateTime dateTime = LocalDateTime.parse(raw.getTimestamp(), TIMESTAMP_FORMATTER);
            isNight = dateTime.getHour() < 6 || dateTime.getHour() > 22;
        } catch (Exception e) {
            logger.warn("Could not parse timestamp '{}'. Defaulting 'is_night' to false.", raw.getTimestamp());
        }

        // 5. Boolean fields for the model and rule engine
        features.put("is_night", isNight);
        features.put("is_new_device", context.isNewDevice());
        features.put("is_foreign_location", context.isForeignLocation());

        logger.debug("Mobile Money preprocessed feature keys: {}", features.keySet());
        return features;
    }

    // ... The rest of the file (createOnnxTensors, executeRules, etc.) remains exactly the same ...
    @Override
    public Map<String, OnnxTensor> createOnnxTensors(Map<String, Object> preprocessedFeatures, OrtEnvironment env) throws OrtException {
        // ... no changes needed here
        Map<String, OnnxTensor> onnxInputs = new HashMap<>();
        for (String inputName : this.session.getInputNames()) {
            Object featureValue = preprocessedFeatures.get(inputName);
            if (featureValue == null) {
                logger.warn("Feature '{}' required by ONNX model not found in preprocessed data. Skipping.", inputName);
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
                throw new OrtException("Unsupported feature type for ONNX tensor: " + featureValue.getClass().getName());
            }
        }
        return onnxInputs;
    }

    @Override
    public FinalRuleOutput executeRules(TransactionRuleContext context) {
        // ... no changes needed here
        FinalRuleOutput output = new FinalRuleOutput();
        output.setOriginalMlScore(context.getMlScore());
        if (this.parsedRules.isEmpty()) {
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
        // ... no changes needed here
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

    private boolean evaluateClause(ConditionClauseDefinition clause, TransactionRuleContext context, String ruleIdForLogging) {
        // ... no changes needed here
        Object actualValue = context.getValue(clause.getField());
        Object ruleValue = clause.getValue();
        if (actualValue == null) { return "IS_NULL".equalsIgnoreCase(clause.getOperator()); }
        try {
            switch (clause.getOperator().toUpperCase()) {
                case "EQUALS":
                    if (actualValue instanceof Boolean) return Objects.equals(actualValue, ruleValue);
                    return Objects.equals(String.valueOf(actualValue), String.valueOf(ruleValue));
                case "NOT_EQUALS":
                    if (actualValue instanceof Boolean) return !Objects.equals(actualValue, ruleValue);
                    return !Objects.equals(String.valueOf(actualValue), String.valueOf(ruleValue));
                case "GREATER_THAN":
                case "LESS_THAN":
                    if (!(actualValue instanceof Number) || !(ruleValue instanceof Number)) return false;
                    double actualDouble = ((Number) actualValue).doubleValue();
                    double ruleDouble = ((Number) ruleValue).doubleValue();
                    return clause.getOperator().equalsIgnoreCase("GREATER_THAN") ? actualDouble > ruleDouble : actualDouble < ruleDouble;
                case "IN_LIST":
                    if (!(ruleValue instanceof List)) return false;
                    return ((List<?>) ruleValue).stream().anyMatch(item -> Objects.equals(String.valueOf(item), String.valueOf(actualValue)));
                default:
                    logger.warn("Rule '{}': Unsupported operator '{}'.", ruleIdForLogging, clause.getOperator());
                    return false;
            }
        } catch (Exception e) {
            logger.error("Rule '{}': CRITICAL ERROR evaluating clause for field '{}'.", ruleIdForLogging, clause.getField(), e);
            return false;
        }
    }

    private void applyAction(ActionDefinition action, FinalRuleOutput output, String ruleId) {
        // ... no changes needed here
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