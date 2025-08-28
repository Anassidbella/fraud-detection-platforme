package org.example.definition;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FINAL CORRECTED VERSION: Represents the complete, stateless input for a Mobile Money transaction,
 * aligned with the final, robust training pipeline.
 */
public class MobileMoneyTransactionInput implements DomainInput {
    // --- Core Transaction Details ---
    private String userId;
    private String recipientId;
    private double transactionAmount;
    private String transactionType;
    private String timestamp; // Expecting "yyyy-MM-dd HH:mm:ss"
    private double balanceBefore;

    // --- Enriched Context (Provided by the client's system) ---
    private ClientContext clientContext;

    // --- Getters & Setters ---
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRecipientId() { return recipientId; }
    public void setRecipientId(String recipientId) { this.recipientId = recipientId; }
    public double getTransactionAmount() { return transactionAmount; }
    public void setTransactionAmount(double transactionAmount) { this.transactionAmount = transactionAmount; }
    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public double getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(double balanceBefore) { this.balanceBefore = balanceBefore; }
    public ClientContext getClientContext() { return clientContext; }
    public void setClientContext(ClientContext clientContext) { this.clientContext = clientContext; }

    /**
     * Inner class for all contextual data the client system is expected to provide.
     */
    public static class ClientContext {
        @JsonProperty("is_new_device")
        private boolean isNewDevice;

        @JsonProperty("is_foreign_location")
        private boolean isForeignLocation;

        @JsonProperty("velocity_txn_count_1h")
        private int velocityTxnCount1h;

        // --- ADDED: Historical features the client MUST provide ---
        @JsonProperty("avg_txn_amt_for_user")
        private double avgTxnAmtForUser;

        @JsonProperty("time_since_last_txn_seconds")
        private long timeSinceLastTxnSeconds;
        // --- END ADDED ---

        private String currency;
        private String country;

        // --- Getters & Setters for all fields ---
        public boolean isNewDevice() { return isNewDevice; }
        public void setNewDevice(boolean newDevice) { isNewDevice = newDevice; }
        public boolean isForeignLocation() { return isForeignLocation; }
        public void setForeignLocation(boolean foreignLocation) { this.isForeignLocation = foreignLocation; }
        public int getVelocityTxnCount1h() { return velocityTxnCount1h; }
        public void setVelocityTxnCount1h(int velocityTxnCount1h) { this.velocityTxnCount1h = velocityTxnCount1h; }
        public double getAvgTxnAmtForUser() { return avgTxnAmtForUser; }
        public void setAvgTxnAmtForUser(double avgTxnAmtForUser) { this.avgTxnAmtForUser = avgTxnAmtForUser; }
        public long getTimeSinceLastTxnSeconds() { return timeSinceLastTxnSeconds; }
        public void setTimeSinceLastTxnSeconds(long timeSinceLastTxnSeconds) { this.timeSinceLastTxnSeconds = timeSinceLastTxnSeconds; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
    }
}