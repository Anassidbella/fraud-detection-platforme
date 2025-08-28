package org.example.handlers;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import org.example.definition.DomainConfig;
import org.example.definition.RuleDefinition; // Import RuleDefinition
import org.example.rules.FinalRuleOutput;
import org.example.rules.TransactionRuleContext;

import java.io.File;
import java.util.List; // Import List
import java.util.Map;

public interface IDomainHandler {
    void initialize(DomainConfig domainConfig, OrtEnvironment env, File onnxModelFile) throws Exception;

    // UPDATED SIGNATURE: No longer uses DomainConfig
    void initializeRuleEngine(List<RuleDefinition> rules) throws Exception;

    Class<?> getExpectedInputPojoType();
    Map<String, Object> preprocess(Object rawInputData, DomainConfig domainConfig);
    Map<String, OnnxTensor> createOnnxTensors(Map<String, Object> preprocessedFeatures, OrtEnvironment env) throws OrtException;
    FinalRuleOutput executeRules(TransactionRuleContext context);
    OrtSession getSession();
    void close() throws Exception;
}