// src/main/java/org/example/handlers/DomainHandlerFactory.java
package org.example.handlers;

public class DomainHandlerFactory {
    public static IDomainHandler createHandler(String domainName) {
        if (domainName == null || domainName.trim().isEmpty()) {
            throw new IllegalArgumentException("Domain name cannot be null or empty.");
        }
        switch (domainName.toLowerCase()) {
            case "mobile_money":
                return new MobileMoneyDomainHandler();
            case "ecommerce": // <-- ADDED THIS
                return new ECommerceDomainHandler();
            case "bank": // <--- ADD THIS NEW CASE
                return new BankDomainHandler();            //    return new InsuranceDomainHandler();
            default:
                throw new IllegalArgumentException("No handler registered for domain: " + domainName);
        }
    }
}