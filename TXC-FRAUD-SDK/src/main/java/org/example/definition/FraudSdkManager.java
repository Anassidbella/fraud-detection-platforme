package org.example.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class FraudSdkManager {
    private static final Logger logger = LoggerFactory.getLogger(FraudSdkManager.class);

    // The single, cached instance of the SDK.
    private static volatile FraudDetectionSDK instance = null;

    // The URL to the configuration service, now loaded from config.properties.
    private static String CONFIG_SERVICE_URL;

    // Private constructor to prevent direct instantiation.
    private FraudSdkManager() {}

    /**
     * Initializes the FraudDetectionSDK using a credentials file from a specified path.
     * This is the new, primary entry point for any client application.
     * This method is thread-safe and ensures the SDK is only initialized once.
     *
     * @param credentialsFilePath The absolute or relative path to the client's credentials.json file.
     * @return The initialized, singleton instance of the FraudDetectionSDK.
     * @throws Exception if initialization fails.
     */
    public static FraudDetectionSDK initialize(String credentialsFilePath) throws Exception {
        if (instance == null) {
            synchronized (FraudSdkManager.class) {
                if (instance == null) {
                    logger.info("First-time initialization of FraudDetectionSDK initiated.");

                    // 1. Load internal properties first (like the config server URL)
                    loadInternalProperties();

                    // 2. Load client-specific credentials from the provided external path
                    ClientCredentials creds = loadClientCredentials(credentialsFilePath);

                    // 3. "Phone home" to the config service to get the real SdkConfig
                    SdkConfig sdkConfiguration = fetchRuntimeConfig(creds);

                    // 4. Add the API token to the fetched config for later use
                    sdkConfiguration.setApiToken(creds.getApiToken());

                    // 5. Initialize the SDK with the fetched configuration
                    instance = new FraudDetectionSDK(sdkConfiguration);

                    logger.info("FraudDetectionSDK has been successfully initialized and is now cached.");
                }
            }
        }
        return instance;
    }

    /**
     * Returns the already initialized instance of the SDK.
     * Throws an IllegalStateException if initialize() has not been called first.
     *
     * @return The singleton instance of the FraudDetectionSDK.
     */
    public static FraudDetectionSDK getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SDK has not been initialized. Please call FraudSdkManager.initialize(credentialsFilePath) first.");
        }
        return instance;
    }

    private static ClientCredentials loadClientCredentials(String filePath) throws IOException {
        logger.info("Loading client credentials from external file: '{}'", filePath);
        File credentialsFile = new File(filePath);
        if (!credentialsFile.exists() || !credentialsFile.canRead()) {
            throw new IOException("Credentials file not found or cannot be read at path: " + filePath);
        }

        try (InputStream input = new FileInputStream(credentialsFile)) {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(input, ClientCredentials.class);
        }
    }

    private static SdkConfig fetchRuntimeConfig(ClientCredentials creds) throws Exception {
        if (CONFIG_SERVICE_URL == null || CONFIG_SERVICE_URL.trim().isEmpty()) {
            throw new IllegalStateException("Configuration Service URL is not set. Check config.properties.");
        }

        logger.info("Phoning home to config server at: {}", CONFIG_SERVICE_URL);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CONFIG_SERVICE_URL))
                .header("Authorization", "Bearer " + creds.getApiToken())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch runtime config from server. Status: "
                    + response.statusCode() + ", Body: " + response.body());
        }

        logger.info("Successfully fetched runtime configuration from server.");
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(response.body(), SdkConfig.class);
    }

    private static void loadInternalProperties() throws IOException {
        // This now loads both AWS config AND the portal API URL from config.properties
        try (InputStream input = FraudSdkManager.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new IOException("Internal SDK configuration file 'config.properties' not found in classpath.");
            }
            Properties prop = new Properties();
            prop.load(input);
            prop.forEach((key, value) -> System.setProperty((String) key, (String) value));

            // Load the config service URL into our static variable
            CONFIG_SERVICE_URL = System.getProperty("portal.config.service.url");

            logger.info("Loaded internal SDK properties from config.properties.");
        }
    }

    public static void shutdown() {
        if (instance != null) {
            synchronized (FraudSdkManager.class) {
                if (instance != null) {
                    logger.info("Shutting down the cached FraudDetectionSDK instance.");
                    instance.closeAll();
                    instance = null;
                }
            }
        }
    }
}