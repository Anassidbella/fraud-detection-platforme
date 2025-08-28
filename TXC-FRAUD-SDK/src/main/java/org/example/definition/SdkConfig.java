package org.example.definition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

public class SdkConfig {
    private String mlflowTrackingUri;
    private String kongGatewayBaseUrl;
    private Map<String, DomainConfig> domainConfigurations;
    private Map<String, List<RuleDefinition>> rules;

    // This field will not be part of the JSON from the server,
    // but we will add it after fetching for use in API calls.
    @JsonIgnore
    private String apiToken;

    public SdkConfig() {
        this.domainConfigurations = new HashMap<>();
        this.rules = new HashMap<>();
    }

    // Getters and Setters
    public String getMlflowTrackingUri() { return mlflowTrackingUri; }
    public void setMlflowTrackingUri(String mlflowTrackingUri) { this.mlflowTrackingUri = mlflowTrackingUri; }
    public String getKongGatewayBaseUrl() { return kongGatewayBaseUrl; }
    public void setKongGatewayBaseUrl(String kongGatewayBaseUrl) { this.kongGatewayBaseUrl = kongGatewayBaseUrl; }
    public Map<String, DomainConfig> getDomainConfigurations() { return domainConfigurations; }
    public void setDomainConfigurations(Map<String, DomainConfig> domainConfigurations) { this.domainConfigurations = domainConfigurations; }
    public Map<String, List<RuleDefinition>> getRules() { return rules; }
    public void setRules(Map<String, List<RuleDefinition>> rules) { this.rules = rules; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
}