package org.example.definition;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ClientCredentials {
    @JsonProperty("application_id")
    private String applicationId;

    @JsonProperty("api_token")
    private String apiToken;

    // Getters and Setters
    public String getApplicationId() {
        return applicationId;
    }
    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }
    public String getApiToken() {
        return apiToken;
    }
    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }
}