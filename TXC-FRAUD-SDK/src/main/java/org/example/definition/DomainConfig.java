package org.example.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DomainConfig {
    private String domainName;
    private String mlflowVectorizerModelName;
    private String modelStage;
    private String onnxFileSubPathWithinModelDir;
    private String scorerApiEndpointPath;

    public DomainConfig() {
    }

    // Getters
    public String getDomainName() { return domainName; }
    public String getMlflowVectorizerModelName() { return mlflowVectorizerModelName; }
    public String getModelStage() { return modelStage; }
    public String getOnnxFileSubPathWithinModelDir() { return onnxFileSubPathWithinModelDir; }
    public String getScorerApiEndpointPath() { return scorerApiEndpointPath; }

    // Setters
    public void setDomainName(String domainName) { this.domainName = domainName; }
    public void setMlflowVectorizerModelName(String mlflowVectorizerModelName) { this.mlflowVectorizerModelName = mlflowVectorizerModelName; }
    public void setModelStage(String modelStage) { this.modelStage = modelStage; }
    public void setOnnxFileSubPathWithinModelDir(String onnxFileSubPathWithinModelDir) { this.onnxFileSubPathWithinModelDir = onnxFileSubPathWithinModelDir; }
    public void setScorerApiEndpointPath(String scorerApiEndpointPath) { this.scorerApiEndpointPath = scorerApiEndpointPath; }
}