// src/main/java/org/example/definition/ECommerceTransactionInput.java
package org.example.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ECommerceTransactionInput implements DomainInput {
    // Transaction details
    private double transactionAmount;
    private String currency;
    private int itemCount;
    private String productCategory;
    private String paymentMethod;
    private String userId;
    private String ipAddressCountry;

    // User/Order context
    @JsonProperty("isGuestCheckout")
    private boolean isGuestCheckout;
    private int accountAgeInDays;
    private String emailDomain;
    private String shippingCountry;
    private String billingCountry;

    // --- DEFINITIVE FIX: Add the ClientContext object ---
    // This object will contain the pre-calculated behavioral features provided by the client.
    private ClientContext clientContext;

    // --- Nested static class for client-provided context ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClientContext {
        private double timeSinceLastTxnSeconds;
        private double avgTxnAmtForUser;

        // Getters and Setters for context fields
        public double getTimeSinceLastTxnSeconds() { return timeSinceLastTxnSeconds; }
        public void setTimeSinceLastTxnSeconds(double timeSinceLastTxnSeconds) { this.timeSinceLastTxnSeconds = timeSinceLastTxnSeconds; }
        public double getAvgTxnAmtForUser() { return avgTxnAmtForUser; }
        public void setAvgTxnAmtForUser(double avgTxnAmtForUser) { this.avgTxnAmtForUser = avgTxnAmtForUser; }
    }

    // Getters and Setters for all top-level fields...
    public double getTransactionAmount() { return transactionAmount; }
    public void setTransactionAmount(double transactionAmount) { this.transactionAmount = transactionAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public String getProductCategory() { return productCategory; }
    public void setProductCategory(String productCategory) { this.productCategory = productCategory; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getIpAddressCountry() { return ipAddressCountry; }
    public void setIpAddressCountry(String ipAddressCountry) { this.ipAddressCountry = ipAddressCountry; }
    public boolean isGuestCheckout() { return isGuestCheckout; }
    public void setGuestCheckout(boolean guestCheckout) { this.isGuestCheckout = guestCheckout; }
    public int getAccountAgeInDays() { return accountAgeInDays; }
    public void setAccountAgeInDays(int accountAgeInDays) { this.accountAgeInDays = accountAgeInDays; }
    public String getEmailDomain() { return emailDomain; }
    public void setEmailDomain(String emailDomain) { this.emailDomain = emailDomain; }
    public String getShippingCountry() { return shippingCountry; }
    public void setShippingCountry(String shippingCountry) { this.shippingCountry = shippingCountry; }
    public String getBillingCountry() { return billingCountry; }
    public void setBillingCountry(String billingCountry) { this.billingCountry = billingCountry; }

    // --- GETTER/SETTER FOR THE NEW ClientContext OBJECT ---
    public ClientContext getClientContext() { return clientContext; }
    public void setClientContext(ClientContext clientContext) { this.clientContext = clientContext; }
}