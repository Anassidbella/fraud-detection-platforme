package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.example.definition.*;
import org.example.rules.FinalRuleOutput;

import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class InteractiveTester {

    public static void main(String[] args) {
        System.out.println("--- Fraud SDK Interactive Tester ---");

        FraudDetectionSDK sdk = null;
        String primaryDomain = null;
        Class<?> domainInputClass = null;

        try (Scanner consoleReader = new Scanner(System.in)) {
            // --- Step 1: Initialize the SDK ---
            while (sdk == null) {
                System.out.print("Enter the path to your credentials.json file: ");
                String credentialsPath = consoleReader.nextLine();
                if (credentialsPath.equalsIgnoreCase("exit")) return;

                try {
                    sdk = FraudSdkManager.initialize(credentialsPath);
                    System.out.println("✅ SDK Initialized successfully.");

                    // Determine the primary domain this SDK instance is configured for.
                    Set<String> configuredDomains = sdk.getDomainConfigurations().keySet();
                    if (configuredDomains.isEmpty()) {
                        System.out.println("❌ ERROR: The fetched configuration has no domains. Exiting.");
                        return;
                    }
                    // For this tester, we'll assume the client has one primary domain.
                    primaryDomain = configuredDomains.iterator().next();
                    domainInputClass = sdk.getDomainInputClass(primaryDomain);

                    System.out.println("✅ SDK is configured for domain: '" + primaryDomain + "'");
                    System.out.println("✅ Ready to accept transactions of type: " + domainInputClass.getSimpleName());

                } catch (Exception e) {
                    System.out.println("❌ ERROR: Failed to initialize SDK. Please check the path and try again.");
                    System.out.println("   Details: " + e.getMessage());
                }
            }

            // --- Step 2: Main processing loop ---
            System.out.println("\n=======================================================================");
            System.out.println("Ready to process transactions. Paste a single-line JSON and press Enter.");
            System.out.println("Type 'exit' to quit.");

            ObjectMapper mapper = new ObjectMapper();
            // This is important to not fail if the JSON has extra fields
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            while (true) {
                try {
                    System.out.print("\nTransaction JSON> ");
                    String jsonInput = consoleReader.nextLine();

                    if ("exit".equalsIgnoreCase(jsonInput)) break;
                    if (jsonInput.trim().isEmpty()) continue;

                    // Dynamically deserialize into the correct DomainInput class
                    DomainInput transaction = (DomainInput) mapper.readValue(jsonInput, domainInputClass);

                    // For this simple tester, we assume no extra client context is passed.
                    Map<String, Object> clientContext = null;

                    System.out.println("...Scoring transaction for domain '" + primaryDomain + "'...");
                    FinalRuleOutput assessment = sdk.scoreAndEvaluateRules(primaryDomain, transaction, clientContext);

                    System.out.println("\n--- ASSESSMENT RESULT ---");
                    System.out.println("  -> Final Decision:  " + assessment.getDecision());
                    System.out.println("  -> Original ML Score: " + assessment.getOriginalMlScore());
                    System.out.println("  -> Adjusted Score:    " + assessment.getFinalAdjustedScore());
                    System.out.println("  -> Reason Codes:    " + assessment.getReasonCodes());
                    System.out.println("  -> Triggered Rules: " + assessment.getTriggeredRuleIds());
                    System.out.println("-------------------------");

                } catch (Exception e) {
                    System.out.println("  -> ❌ ERROR: Could not process input. It might not match the expected format for the '" + primaryDomain + "' domain.");
                    System.out.println("     Details: " + e.getMessage());
                }
            }
        } finally {
            System.out.println("=======================================================================");
            System.out.println("Exiting... Shutting down SDK.");
            FraudSdkManager.shutdown();
        }
    }
}