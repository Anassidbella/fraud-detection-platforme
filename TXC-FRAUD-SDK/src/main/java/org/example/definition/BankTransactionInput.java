package org.example.definition;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single bank transaction for real-time inference.
 * FINAL ALIGNED VERSION: This class's structure is now perfectly aligned with the features
 * used to train the high-performance model in the Python pipeline AND the Rule Engine UI.
 */
public class BankTransactionInput implements DomainInput {
    // --- Core Transaction Details ---
    private String userId;
    private double transactionAmount;
    private String transactionType;
    private String timestamp; // e.g., "2025-03-26T07:46:59"

    // --- ADDED TO MATCH UI ---
    private String currency;

    // --- Enriched Context (Provided by the client's system) ---
    private ClientContext clientContext;

    // --- Getters & Setters ---
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public double getTransactionAmount() { return transactionAmount; }
    public void setTransactionAmount(double transactionAmount) { this.transactionAmount = transactionAmount; }
    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getCurrency() { return currency; } // Getter for new field
    public void setCurrency(String currency) { this.currency = currency; } // Setter for new field
    public ClientContext getClientContext() { return clientContext; }
    public void setClientContext(ClientContext clientContext) { this.clientContext = clientContext; }

    /**
     * Inner class structuring the required contextual data.
     */
    public static class ClientContext {
        @JsonProperty("balance_before")
        private double balanceBefore;

        @JsonProperty("is_new_device")
        private boolean isNewDevice;

        @JsonProperty("is_foreign_location")
        private boolean isForeignLocation;

        @JsonProperty("is_night")
        private boolean isNight;

        @JsonProperty("velocity_txn_count_1h")
        private int velocityTxnCount1h;

        @JsonProperty("avg_txn_amt_for_user")
        private double avgTxnAmtForUser;

        @JsonProperty("time_since_last_txn_seconds")
        private long timeSinceLastTxnSeconds;

        // --- ADDED TO MATCH UI ---
        @JsonProperty("account_age_days")
        private int accountAgeDays;

        @JsonProperty("is_new_beneficiary")
        private boolean isNewBeneficiary;

        @JsonProperty("is_using_vpn_proxy")
        private boolean isUsingVpnProxy;

        @JsonProperty("hour_of_day")
        private int hourOfDay;


        // --- Getters & Setters for all fields ---
        public double getBalanceBefore() { return balanceBefore; }
        public void setBalanceBefore(double balanceBefore) { this.balanceBefore = balanceBefore; }
        public boolean isNewDevice() { return isNewDevice; }
        public void setNewDevice(boolean newDevice) { this.isNewDevice = newDevice; }
        public boolean isForeignLocation() { return isForeignLocation; }
        public void setForeignLocation(boolean foreignLocation) { this.isForeignLocation = foreignLocation; }
        public boolean isNight() { return isNight; }
        public void setNight(boolean night) { this.isNight = night; }
        public int getVelocityTxnCount1h() { return velocityTxnCount1h; }
        public void setVelocityTxnCount1h(int velocityTxnCount1h) { this.velocityTxnCount1h = velocityTxnCount1h; }
        public double getAvgTxnAmtForUser() { return avgTxnAmtForUser; }
        public void setAvgTxnAmtForUser(double avgTxnAmtForUser) { this.avgTxnAmtForUser = avgTxnAmtForUser; }
        public long getTimeSinceLastTxnSeconds() { return timeSinceLastTxnSeconds; }
        public void setTimeSinceLastTxnSeconds(long timeSinceLastTxnSeconds) { this.timeSinceLastTxnSeconds = timeSinceLastTxnSeconds; }
        public int getAccountAgeDays() { return accountAgeDays; } // Getter for new field
        public void setAccountAgeDays(int accountAgeDays) { this.accountAgeDays = accountAgeDays; } // Setter for new field
        public boolean isNewBeneficiary() { return isNewBeneficiary; } // Getter for new field
        public void setNewBeneficiary(boolean newBeneficiary) { this.isNewBeneficiary = newBeneficiary; } // Setter for new field
        public boolean isUsingVpnProxy() { return isUsingVpnProxy; } // Getter for new field
        public void setUsingVpnProxy(boolean usingVpnProxy) { this.isUsingVpnProxy = usingVpnProxy; } // Setter for new field
        public int getHourOfDay() { return hourOfDay; } // Getter for new field
        public void setHourOfDay(int hourOfDay) { this.hourOfDay = hourOfDay; } // Setter for new field
    }
}