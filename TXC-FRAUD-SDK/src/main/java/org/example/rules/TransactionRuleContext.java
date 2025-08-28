package org.example.rules;

import org.example.definition.DomainInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Arrays;

/**
 * DEFINITIVE FINAL VERSION
 * This version corrects the path traversal logic for all fields, including those
 * prefixed with "rawInput.", which was the source of the "null object" error.
 */
public class TransactionRuleContext {
    private static final Logger logger = LoggerFactory.getLogger(TransactionRuleContext.class);

    private final DomainInput rawInput;
    private final double mlScore;
    private final Map<String, Object> preprocessedFeatures;

    public TransactionRuleContext(DomainInput rawInput,
                                  double mlScore,
                                  Map<String, Object> clientSuppliedContext,
                                  Map<String, Object> preprocessedFeatures) {
        this.rawInput = rawInput;
        this.mlScore = mlScore;
        this.preprocessedFeatures = (preprocessedFeatures != null) ? preprocessedFeatures : Collections.emptyMap();
    }

    public double getMlScore() {
        return mlScore;
    }

    /**
     * The core of the rule engine's data access. This version directly parses dot-notation paths.
     */
    public Object getValue(String fieldPath) {
        if (fieldPath == null || fieldPath.trim().isEmpty()) {
            return null;
        }

        if ("mlScore".equalsIgnoreCase(fieldPath)) {
            return this.mlScore;
        }

        if (fieldPath.startsWith("preprocessed.")) {
            String featureName = fieldPath.substring("preprocessed.".length());
            return preprocessedFeatures.get(featureName);
        }

        Object currentObject = this.rawInput;
        String[] pathParts = fieldPath.split("\\.");

        // THIS IS THE CRITICAL FIX:
        // If the path starts with "rawInput.", we skip that part and start the traversal
        // from the remaining parts of the path, as we are already on the rawInput object.
        String[] partsToProcess = pathParts;
        if (pathParts.length > 1 && "rawInput".equalsIgnoreCase(pathParts[0])) {
            partsToProcess = Arrays.copyOfRange(pathParts, 1, pathParts.length);
        }

        for (String part : partsToProcess) {
            if (currentObject == null) {
                logger.warn("Attempted to access field '{}' on a null object in path '{}'", part, fieldPath);
                return null;
            }
            currentObject = getReflectedValue(currentObject, part);
        }
        return currentObject;
    }

    private Object getReflectedValue(Object target, String fieldName) {
        if (target == null || fieldName == null) {
            return null;
        }

        String[] potentialMethodNames = {
                "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1),
                "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1),
                fieldName
        };

        for (String methodName : potentialMethodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (NoSuchMethodException e) {
                // This is normal
            } catch (Exception e) {
                logger.error("Reflection error calling method '{}' on {}: {}", methodName, target.getClass().getName(), e.getMessage());
                return null;
            }
        }

        logger.trace("Reflection failed: No suitable getter found for field '{}' on class {}", fieldName, target.getClass().getName());
        return null;
    }
}