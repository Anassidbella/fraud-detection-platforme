from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional
import mlflow.xgboost  # <--- IMPORT 1: Use the correct MLflow flavor for XGBoost
import numpy as np
import os
import logging
import pathlib
import uvicorn
from dotenv import load_dotenv

# --- Load Environment Variables ---
env_path = pathlib.Path(__file__).parent / ".env"
load_dotenv(dotenv_path=env_path)

# --- Configuration (Read from environment variables, with defaults) ---
DOMAIN_NAME = "ecommerce"
MLFLOW_TRACKING_URI = os.getenv("MLFLOW_TRACKING_URI")
# Use the existing ANOMALY_MODEL_NAME variable but treat it as the classifier's name
CLASSIFIER_MODEL_NAME = os.getenv("ANOMALY_MODEL_NAME", f"{DOMAIN_NAME}_fraud_classifier")
MODEL_ALIAS = os.getenv("MODEL_ALIAS", "production")
APP_PORT = int(os.getenv("PORT", "5002"))

# --- Application and Logging Setup ---
# --- UPDATE 2: Title changed to reflect classification task ---
app = FastAPI(title=f"{DOMAIN_NAME.title()} Fraud Classification Service")
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# --- Global In-Memory Model Storage ---
# --- UPDATE 3: Renamed for clarity ---
classifier_model_g: Optional[any] = None
model_version_g: Optional[str] = None

# --- API Data Models ---
class VectorInput(BaseModel):
    """Input structure from the SDK."""
    input_index: Optional[int] = None
    vector: List[float]

# --- UPDATE 4: Simplified and renamed output to match mobile_money example ---
class ScoreOutput(BaseModel):
    """Output structure sending the fraud probability back to the SDK."""
    input_index: Optional[int] = None
    ml_score: float  # This now represents the fraud probability (0.0 to 1.0)
    model_version_used: str
    domain_used: str = DOMAIN_NAME

# --- Startup Event: Load the XGBoost Model ---
@app.on_event("startup")
async def load_model():
    """Runs once when the service starts to load the XGBoost model from MLflow."""
    global classifier_model_g, model_version_g
    
    if not all([MLFLOW_TRACKING_URI, CLASSIFIER_MODEL_NAME, MODEL_ALIAS]):
        error_msg = "CRITICAL: Missing environment variables (MLFLOW_TRACKING_URI, ANOMALY_MODEL_NAME, MODEL_ALIAS). Cannot start."
        logger.error(error_msg)
        raise RuntimeError(error_msg)

    logger.info(f"[{DOMAIN_NAME}] Starting service: loading fraud classifier model...")
    model_uri = f"models:/{CLASSIFIER_MODEL_NAME}@{MODEL_ALIAS}"
    logger.info(f"[{DOMAIN_NAME}] Connecting to MLflow at {MLFLOW_TRACKING_URI} to load {model_uri}")

    try:
        mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
        # --- UPDATE 5: Use mlflow.xgboost.load_model to correctly load the classifier ---
        classifier_model_g = mlflow.xgboost.load_model(model_uri)
        
        client = mlflow.tracking.MlflowClient()
        version_info = client.get_model_version_by_alias(CLASSIFIER_MODEL_NAME, MODEL_ALIAS)
        model_version_g = version_info.version

        logger.info(f"[{DOMAIN_NAME}] Successfully loaded model '{CLASSIFIER_MODEL_NAME}' version '{model_version_g}'.")

    except Exception as e:
        logger.error(f"[{DOMAIN_NAME}] FATAL ERROR loading classifier model: {e}", exc_info=True)
        classifier_model_g = None

# --- API Endpoints ---
@app.post("/score_vector", response_model=ScoreOutput, summary="Score a feature vector for fraud probability")
async def score_vector(vector_input: VectorInput):
    """Takes a vectorized transaction and returns its fraud probability."""
    if not classifier_model_g:
        raise HTTPException(status_code=503, detail="Model is not available. Service may have failed to start.")

    try:
        input_vector = np.array(vector_input.vector, dtype=np.float32).reshape(1, -1)
        
        # --- UPDATE 6: Use predict_proba() for classification models ---
        # It returns probabilities for each class: [[prob_for_class_0, prob_for_class_1]]
        probabilities = classifier_model_g.predict_proba(input_vector)
        
        # The fraud probability is the score for the "fraud" class (index 1)
        fraud_probability_score = probabilities[0][1]

        return ScoreOutput(
            input_index=vector_input.input_index,
            ml_score=float(fraud_probability_score),
            model_version_used=model_version_g
        )
    except Exception as e:
        logger.error(f"[{DOMAIN_NAME}] Error during scoring: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Internal error during scoring.")

@app.get("/health", summary="Health Check")
async def health_check():
    """Verifies that the service is running and the model is loaded."""
    if classifier_model_g:
        return {
            "status": "UP", 
            "domain": DOMAIN_NAME, 
            "model_name": CLASSIFIER_MODEL_NAME,
            "model_version": model_version_g
        }
    else:
        raise HTTPException(status_code=503, detail="Service is DOWN: Model failed to load.")
    
if __name__ == "__main__":
    logger.info(f"[{DOMAIN_NAME}] Starting Uvicorn server on http://0.0.0.0:{APP_PORT}")
    uvicorn.run("app:app", host="0.0.0.0", port=APP_PORT, reload=True)