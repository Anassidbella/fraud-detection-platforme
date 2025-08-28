package org.example.definition;

import ai.onnxruntime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.handlers.DomainHandlerFactory;
import org.example.handlers.IDomainHandler;
import org.example.rules.FinalRuleOutput;
import org.example.rules.TransactionRuleContext;
import org.mlflow.api.proto.ModelRegistry;
import org.mlflow.tracking.MlflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FraudDetectionSDK {
    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionSDK.class);

    private final SdkConfig sdkConfig;
    private final MlflowClient mlflowClient;
    private final OrtEnvironment sharedOrtEnv;
    private final Map<String, IDomainHandler> domainHandlers = new HashMap<>();
    private final HttpClient httpClient;
    private final ObjectMapper jsonMapper;

    public FraudDetectionSDK(SdkConfig sdkConfig) throws Exception {
        this.sdkConfig = sdkConfig;
        this.mlflowClient = new MlflowClient(sdkConfig.getMlflowTrackingUri());
        this.sharedOrtEnv = OrtEnvironment.getEnvironment();
        this.httpClient = HttpClient.newHttpClient();
        this.jsonMapper = new ObjectMapper();

        logger.info("FraudDetectionSDK initializing with MLflow URI: {}", sdkConfig.getMlflowTrackingUri());
        initializeDomains();
    }

    private void initializeDomains() throws Exception {
        logger.info("Initializing domain handlers...");
        if (sdkConfig.getDomainConfigurations() == null || sdkConfig.getDomainConfigurations().isEmpty()) {
            logger.warn("No domains configured in SdkConfig. SDK will not be able to process any transactions.");
            return;
        }

        for (Map.Entry<String, DomainConfig> entry : sdkConfig.getDomainConfigurations().entrySet()) {
            String domainName = entry.getKey();
            DomainConfig domainConfig = entry.getValue();
            logger.info("Setting up domain: {}", domainName);

            try {
                File modelFile = downloadOnnxModelForDomain(domainConfig);
                IDomainHandler handler = DomainHandlerFactory.createHandler(domainName);
                handler.initialize(domainConfig, sharedOrtEnv, modelFile);

                // Get the rules for this domain from the SdkConfig object that was
                // populated by the "phone home" call from the server.
                List<RuleDefinition> rulesForDomain = sdkConfig.getRules().get(domainName);
                handler.initializeRuleEngine(rulesForDomain); // Pass the list directly.

                domainHandlers.put(domainName, handler);
                logger.info("Successfully initialized handler (ONNX & Rules) for domain: {}", domainName);
            } catch (Exception e) {
                logger.error("Failed to initialize handler for domain: {}. This domain will be unavailable.", domainName, e);
            }
        }
    }

    private File downloadOnnxModelForDomain(DomainConfig domainConfig) throws Exception {
        logger.info("Domain [{}]: Downloading ONNX model...", domainConfig.getDomainName());
        ModelRegistry.ModelVersion modelVersionDetails;
        try {
            List<ModelRegistry.ModelVersion> versions = mlflowClient.getLatestVersions(
                    domainConfig.getMlflowVectorizerModelName(),
                    Collections.singletonList(domainConfig.getModelStage())
            );
            if (versions.isEmpty()) {
                ModelRegistry.RegisteredModel specificModel = mlflowClient.getRegisteredModel(domainConfig.getMlflowVectorizerModelName());
                modelVersionDetails = specificModel.getLatestVersionsList().stream()
                        .map(mvSummary -> {
                            try {
                                return mlflowClient.getModelVersion(domainConfig.getMlflowVectorizerModelName(), mvSummary.getVersion());
                            } catch (Exception e) { logger.warn("Could not fetch details for model version {}", mvSummary.getVersion()); return null;}
                        })
                        .filter(Objects::nonNull)
                        .filter(mv -> mv.getAliasesList().contains(domainConfig.getModelStage()))
                        .findFirst()
                        .orElse(null);
            } else {
                modelVersionDetails = versions.get(0);
            }

            if (modelVersionDetails == null) {
                throw new RuntimeException(String.format("Domain [%s]: No model version found for '%s' with stage/alias '%s'.",
                        domainConfig.getDomainName(), domainConfig.getMlflowVectorizerModelName(), domainConfig.getModelStage()));
            }
            logger.info("Domain [{}]: Found Model Version: {}, Run ID: {}", domainConfig.getDomainName(),
                    modelVersionDetails.getVersion(), modelVersionDetails.getRunId());
        } catch (Exception e) {
            logger.error("Domain [{}]: Failed to get model version from MLflow.", domainConfig.getDomainName(), e);
            throw e;
        }

        String modelVersionArtifactRootS3Uri = modelVersionDetails.getSource();
        if (!modelVersionArtifactRootS3Uri.startsWith("s3://")) {
            throw new RuntimeException("Domain [" + domainConfig.getDomainName() + "]: Model source URI is not S3: " + modelVersionArtifactRootS3Uri);
        }
        String s3Path = modelVersionArtifactRootS3Uri.substring(5);
        int firstSlashIndex = s3Path.indexOf('/');
        String bucketName = s3Path.substring(0, firstSlashIndex);
        String keyPrefixFromSource = s3Path.substring(firstSlashIndex + 1);

        String fullS3ObjectKey = keyPrefixFromSource;
        if (!fullS3ObjectKey.endsWith("/") && !domainConfig.getOnnxFileSubPathWithinModelDir().startsWith("/")) {
            fullS3ObjectKey += "/";
        } else if (fullS3ObjectKey.endsWith("/") && domainConfig.getOnnxFileSubPathWithinModelDir().startsWith("/")) {
            fullS3ObjectKey = fullS3ObjectKey.substring(0, fullS3ObjectKey.length() -1);
        }
        fullS3ObjectKey += domainConfig.getOnnxFileSubPathWithinModelDir();
        if (fullS3ObjectKey.startsWith("/")) fullS3ObjectKey = fullS3ObjectKey.substring(1);

        logger.info("Domain [{}]: S3 Bucket: {}, Key: {}", domainConfig.getDomainName(), bucketName, fullS3ObjectKey);

        String endpoint = System.getProperty("aws.s3.endpointOverride", System.getenv("AWS_ENDPOINT_URL"));
        String accessKey = System.getProperty("aws.accessKeyId", System.getenv("AWS_ACCESS_KEY_ID"));
        String secretKey = System.getProperty("aws.secretAccessKey", System.getenv("AWS_SECRET_ACCESS_KEY"));
        String region = System.getProperty("aws.region", System.getenv("AWS_REGION"));
        boolean pathStyleAccess = Boolean.parseBoolean(System.getProperty("aws.s3.pathStyleAccessEnabled", System.getenv("AWS_S3_PATH_STYLE_ACCESS")));

        if (endpoint == null || accessKey == null || secretKey == null || region == null) {
            throw new RuntimeException("Missing S3 config (endpoint, credentials, region). Check config.properties or environment variables.");
        }

        S3Client s3Client = null;
        File tempModelFile;
        try {
            s3Client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .region(Region.of(region))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build())
                    .build();

            tempModelFile = Files.createTempFile(domainConfig.getDomainName() + "_model_", ".onnx").toFile();
            tempModelFile.deleteOnExit();

            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucketName).key(fullS3ObjectKey).build();
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
            Files.write(tempModelFile.toPath(), objectBytes.asByteArray(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            if (!tempModelFile.exists() || tempModelFile.length() == 0) {
                throw new IOException("Downloaded ONNX model file is empty: " + tempModelFile.getAbsolutePath());
            }
            logger.info("Domain [{}]: ONNX model downloaded to {}", domainConfig.getDomainName(), tempModelFile.getAbsolutePath());
            return tempModelFile;

        } catch (S3Exception s3e) {
            logger.error("Domain [{}]: S3Exception downloading artifact. Bucket={}, Key={}. Error: {}, AWS Request ID: {}",
                    domainConfig.getDomainName(), bucketName, fullS3ObjectKey,
                    s3e.awsErrorDetails() != null ? s3e.awsErrorDetails().errorMessage() : s3e.getMessage(),
                    s3e.requestId(), s3e);
            throw new RuntimeException("S3 download failed for domain " + domainConfig.getDomainName(), s3e);
        } catch (IOException ioe) {
            logger.error("Domain [{}]: IOException during model download or file handling: {}", domainConfig.getDomainName(), ioe.getMessage(), ioe);
            throw new RuntimeException("File handling failed during model download for domain " + domainConfig.getDomainName(), ioe);
        } finally {
            if (s3Client != null) s3Client.close();
        }
    }

    private static class VectorizationResult {
        final float[] vector;
        final Map<String, Object> features;
        VectorizationResult(float[] vector, Map<String, Object> features) {
            this.vector = vector;
            this.features = features;
        }
    }

    public FinalRuleOutput scoreAndEvaluateRules(String domainName,
                                                 DomainInput domainInput,
                                                 Map<String, Object> optionalClientSuppliedContext) throws Exception {
        IDomainHandler handler = domainHandlers.get(domainName);
        if (handler == null) {
            throw new IllegalArgumentException("No handler initialized for domain: " + domainName);
        }
        DomainConfig domainConfig = sdkConfig.getDomainConfigurations().get(domainName);
        if (domainConfig == null) {
            throw new IllegalStateException("DomainConfig missing for domain: " + domainName);
        }

        if (!handler.getExpectedInputPojoType().isInstance(domainInput)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid input type for domain '%s'. Expected: %s, Got: %s",
                    domainName, handler.getExpectedInputPojoType().getName(), domainInput.getClass().getName()
            ));
        }

        VectorizationResult vecResult = vectorizeInternal(handler, domainInput, domainConfig);

        if (vecResult.vector.length == 0) {
            logger.warn("Domain [{}]: Vectorization returned an empty vector. Cannot proceed with ML scoring.", domainName);
            FinalRuleOutput errorOutput = new FinalRuleOutput();
            errorOutput.setDecision("ERROR");
            errorOutput.addReasonCode("VECTORIZATION_FAILED");
            return errorOutput;
        }

        String scoreApiResponse = callScoringApi(domainConfig, vecResult.vector);
        double mlScore = parseMlScoreFromResponse(scoreApiResponse);

        TransactionRuleContext ruleContext = new TransactionRuleContext(
                domainInput,
                mlScore,
                optionalClientSuppliedContext,
                vecResult.features
        );

        FinalRuleOutput output = handler.executeRules(ruleContext);
        output.setOriginalMlScore(mlScore);
        if (output.getFinalAdjustedScore() == -1.0) {
            output.setFinalAdjustedScore(mlScore);
        }
        return output;
    }

    private VectorizationResult vectorizeInternal(IDomainHandler handler, DomainInput domainInput, DomainConfig domainConfig) throws Exception {
        Map<String, Object> preprocessedFeatures = handler.preprocess(domainInput, domainConfig);

        try (OrtSession.Result results = handler.getSession().run(handler.createOnnxTensors(preprocessedFeatures, sharedOrtEnv))) {
            String outputName = handler.getSession().getOutputNames().iterator().next();
            OnnxValue resultValue = results.get(outputName).get();
            if (!(resultValue instanceof OnnxTensor)) {
                throw new OrtException("Expected OnnxTensor output from model, but got " + resultValue.getClass());
            }
            OnnxTensor resultTensor = (OnnxTensor) resultValue;
            float[][] batchVectors = (float[][]) resultTensor.getValue();

            float[] vector = (batchVectors.length > 0) ? batchVectors[0] : new float[0];
            logger.info("Domain [{}]: Successfully vectorized. Vector length: {}", domainConfig.getDomainName(), vector.length);
            return new VectorizationResult(vector, preprocessedFeatures);
        }
    }

    private String callScoringApi(DomainConfig domainConfig, float[] vector) throws Exception {
        String apiUrl = sdkConfig.getKongGatewayBaseUrl() + domainConfig.getScorerApiEndpointPath();
        Map<String, Object> payloadMap = Map.of("vector", vector);
        String jsonPayload = jsonMapper.writeValueAsString(payloadMap);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json");

        if (sdkConfig.getApiToken() != null && !sdkConfig.getApiToken().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + sdkConfig.getApiToken());
        } else {
            logger.warn("API Token is missing. Making unauthenticated call to scoring service. This will likely fail.");
        }

        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();

        // --- NEW DEEP DEBUGGING BLOCK ---
        logger.info("--- KONG REQUEST DEBUGGING ---");
        logger.info("Request Method: {}", request.method());
        logger.info("Request URI: {}", request.uri());
        logger.info("--- Request Headers ---");
        request.headers().map().forEach((k, v) -> logger.info("  {}: {}", k, v));
        logger.info("----------------------------");
        // --- END OF DEBUGGING BLOCK ---

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logger.info("Domain [{}]: Scorer API Response Status: {}. Body: {}", domainConfig.getDomainName(), response.statusCode(), response.body());
        if (response.statusCode() >= 300) {
            throw new IOException("Scoring API call failed with status " + response.statusCode() + " and body: " + response.body());
        }
        return response.body();
    }

    private double parseMlScoreFromResponse(String apiResponseBody) throws IOException {
        try {
            JsonNode rootNode = jsonMapper.readTree(apiResponseBody);
            if (rootNode.has("anomaly_score")) {
                return rootNode.get("anomaly_score").asDouble();
            }
            if (rootNode.has("ml_score")) {
                logger.warn("Received 'ml_score' field; prefer 'anomaly_score' for consistency.");
                return rootNode.get("ml_score").asDouble();
            }
            if (rootNode.has("score")) {
                logger.warn("Received 'score' field; prefer 'anomaly_score' for consistency.");
                return rootNode.get("score").asDouble();
            }
            throw new IOException("ML Score field ('anomaly_score', 'ml_score', or 'score') not found in API response.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("Invalid JSON response from scoring API.", e);
        }
    }

    public void closeAll() {
        logger.info("Closing FraudDetectionSDK resources and all domain handlers...");
        domainHandlers.forEach((domainName, handler) -> {
            try {
                handler.close();
                logger.info("Closed handler for domain: {}", domainName);
            } catch (Exception e) {
                logger.error("Error closing handler for domain: {}", domainName, e);
            }
        });
        domainHandlers.clear();

        if (this.sharedOrtEnv != null) {
            try {
                this.sharedOrtEnv.close();
                logger.info("Shared ONNX Runtime environment closed.");
            } catch (Exception e) {
                logger.error("Error closing shared ONNX Runtime environment", e);
            }
        }
        logger.info("All domain handlers and SDK resources closed.");
    }

    /**
     * Helper method for the interactive tester to know which POJO to deserialize into.
     * @param domainName The name of the domain.
     * @return The Class of the expected input object for that domain.
     */
    public Class<?> getDomainInputClass(String domainName) {
        IDomainHandler handler = domainHandlers.get(domainName);
        if (handler == null) {
            throw new IllegalArgumentException("No handler initialized for domain: " + domainName);
        }
        return handler.getExpectedInputPojoType();
    }

    /**
     * Helper method for the interactive tester to know which domains are configured.
     * @return A map of the configured domains.
     */
    public Map<String, DomainConfig> getDomainConfigurations() {
        return this.sdkConfig.getDomainConfigurations();
    }
}