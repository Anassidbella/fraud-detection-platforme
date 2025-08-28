// src/main/java/org/example/handlers/ECommerceDomainHandler.java
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

public class ECommerceDomainHandler implements IDomainHandler {
    private static final Logger logger = LoggerFactory.getLogger(ECommerceDomainHandler.class);

    private OrtSession session;
    private DomainConfig domainConfig;
    private List<RuleDefinition> parsedRules;
    private final ObjectMapper ruleObjectMapper = new ObjectMapper();

    public ECommerceDomainHandler() {
        this.parsedRules = Collections.emptyList();
    }

    @Override
    public void initialize(DomainConfig domainConfig, OrtEnvironment env, File onnxModelFile) throws OrtException {
        this.domainConfig = domainConfig;
        this.session = env.createSession(onnxModelFile.getAbsolutePath(), new OrtSession.SessionOptions());
        logger.info("ECommerceDomainHandler: ONNX session created. Inputs: {}", this.session.getInputNames());
    }

    @Override
    public void initializeRuleEngine(List<RuleDefinition> rules) {
        this.parsedRules = (rules != null) ? rules : Collections.emptyList();
        logger.info("Domain [{}]: Successfully loaded {} rules.", this.domainConfig.getDomainName(), this.parsedRules.size());
    }

    @Override
    public Class<?> getExpectedInputPojoType() {
        return ECommerceTransactionInput.class;
    }

    @Override
    public Map<String, Object> preprocess(Object rawInputData, DomainConfig config) {
        // This pattern variable is a small modern Java improvement suggested by your IDE
        if (!(rawInputData instanceof ECommerceTransactionInput raw)) {
            throw new IllegalArgumentException("Input data must be of type ECommerceTransactionInput.");
        }

        ECommerceTransactionInput.ClientContext context = raw.getClientContext();
        if (context == null) {
            throw new IllegalArgumentException("ClientContext cannot be null. It must be provided with behavioral features.");
        }

        Map<String, Object> features = new HashMap<>();

        // --- STATELESS FEATURE ENGINEERING TO MATCH FINAL AIRFLOW DAG ---

        // 1. Pass-through raw features from the payload
        features.put("transactionamount", (float) raw.getTransactionAmount());
        features.put("itemcount", (float) raw.getItemCount());
        features.put("accountageindays", (float) raw.getAccountAgeInDays());
        features.put("currency", raw.getCurrency());
        features.put("productcategory", raw.getProductCategory());
        features.put("emaildomain", raw.getEmailDomain());
        features.put("shippingcountry", raw.getShippingCountry());
        features.put("billingcountry", raw.getBillingCountry());
        features.put("paymentmethod", raw.getPaymentMethod());
        features.put("ipaddresscountry", raw.getIpAddressCountry());

        // 2. Pass-through behavioral features from the client context
        // This mirrors the leak-proof logic: client sends -1 for the first transaction.
        features.put("time_since_last_txn_seconds", (float) context.getTimeSinceLastTxnSeconds());
        features.put("avg_txn_amt_for_user", (float) context.getAvgTxnAmtForUser());

        // 3. Calculated features based on the combination of raw and context data
        double userAvgAmount = context.getAvgTxnAmtForUser();
        // This calculation now perfectly matches the Python training script
        features.put("amount_vs_user_avg_ratio", (float) (raw.getTransactionAmount() / (userAvgAmount + 1e-6)));

        // 4. Boolean conversions for the vectorizer and rule engine
        features.put("isguestcheckout", raw.isGuestCheckout()); // Pass boolean for rules
        boolean billingEqualsShipping = raw.getBillingCountry() != null &&
                raw.getBillingCountry().equalsIgnoreCase(raw.getShippingCountry());
        features.put("billingequalsshipping", billingEqualsShipping); // Pass boolean for rules

        logger.debug("E-commerce preprocessed feature keys: {}", features.keySet());
        return features;
    }

    @Override
    public Map<String, OnnxTensor> createOnnxTensors(Map<String, Object> preprocessedFeatures, OrtEnvironment env) throws OrtException {
        Map<String, OnnxTensor> onnxInputs = new HashMap<>();
        // Use lowercase names to match the training DataFrame
        for (String inputName : this.session.getInputNames()) {
            Object featureValue = preprocessedFeatures.get(inputName.toLowerCase());
            if (featureValue == null) {
                logger.warn("Feature '{}' required by ONNX model not found in preprocessed data. Skipping.", inputName);
                continue;
            }

            // Convert booleans to floats specifically for the ONNX model input
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

    // --- The rest of the file (executeRules, etc.) is generic and requires no changes ---

    @Override
    public FinalRuleOutput executeRules(TransactionRuleContext context) {
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
                    output.setFinalAdjustedScore(Math.max(0.0, Math.min(1.0, currentScore + adjustment)));
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