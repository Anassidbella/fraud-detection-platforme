from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional
import mlflow.xgboost  # <--- CHANGE 1: Import the correct MLflow flavor
import numpy as np
import os
import logging
import pathlib
import uvicorn

from dotenv import load_dotenv

# --- Load Environment Variables ---
env_path = pathlib.Path(__file__).parent / ".env"
load_dotenv(dotenv_path=env_path)

# --- Configuration (Read from environment variables) ---
DOMAIN_NAME = "bank"
MLFLOW_TRACKING_URI = os.getenv("MLFLOW_TRACKING_URI")
# Updated name to reflect the new model type
CLASSIFIER_MODEL_NAME = os.getenv("ANOMALY_MODEL_NAME") 
MODEL_ALIAS = os.getenv("MODEL_ALIAS")
APP_PORT = int(os.getenv("PORT", "5004"))

# --- Application and Logging Setup ---
# Updated title for clarity
app = FastAPI(title=f"{DOMAIN_NAME.title()} Fraud Classification Service") 
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# --- Global In-Memory Model Storage ---
classifier_model_g: Optional[any] = None # Renamed for clarity
model_version_g: Optional[str] = None

# --- API Data Models ---
class VectorInput(BaseModel):
    input_index: Optional[int] = None
    vector: List[float]

class ScoreOutput(BaseModel):
    input_index: Optional[int] = None
    ml_score: float # This will now be the fraud probability (0.0 to 1.0)
    model_version_used: str
    domain_used: str = DOMAIN_NAME

# --- Startup Event: Load Model Once ---
@app.on_event("startup")
async def load_model():
    """Runs once when the service starts to load the XGBoost model from MLflow."""
    global classifier_model_g, model_version_g
    
    if not all([MLFLOW_TRACKING_URI, CLASSIFIER_MODEL_NAME, MODEL_ALIAS]):
        error_msg = "CRITICAL: Missing environment variables (MLFLOW_TRACKING_URI, ANOMALY_MODEL_NAME, MODEL_ALIAS)."
        logger.error(error_msg)
        raise RuntimeError(error_msg)

    logger.info(f"[{DOMAIN_NAME}] Starting service: loading fraud classifier model...")
    model_uri = f"models:/{CLASSIFIER_MODEL_NAME}@{MODEL_ALIAS}"
    logger.info(f"[{DOMAIN_NAME}] Connecting to MLflow at {MLFLOW_TRACKING_URI} to load {model_uri}")

    try:
        mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
        # <--- CHANGE 2: Use mlflow.xgboost.load_model ---
        classifier_model_g = mlflow.xgboost.load_model(model_uri)
        
        client = mlflow.tracking.MlflowClient()
        version_info = client.get_model_version_by_alias(CLASSIFIER_MODEL_NAME, MODEL_ALIAS)
        model_version_g = version_info.version

        logger.info(f"[{DOMAIN_NAME}] Successfully loaded model '{CLASSIFIER_MODEL_NAME}' version '{model_version_g}'.")

    except Exception as e:
        logger.error(f"[{DOMAIN_NAME}] FATAL ERROR loading model: {e}", exc_info=True)
        classifier_model_g = None

# --- API Endpoints ---
@app.post("/score_vector", response_model=ScoreOutput, summary="Score a feature vector for fraud probability")
async def score_vector(vector_input: VectorInput):
    """Takes a vectorized transaction and returns its fraud probability score."""
    if not classifier_model_g:
        raise HTTPException(status_code=503, detail="Model is not available. Service may have failed to start.")

    try:
        input_vector = np.array(vector_input.vector, dtype=np.float32).reshape(1, -1)
        
        # <--- CHANGE 3: Use predict_proba() to get the score ---
        # It returns probabilities for [class_0, class_1] (i.e., [not_fraud, fraud])
        probabilities = classifier_model_g.predict_proba(input_vector)
        
        # The fraud score is the probability of the second class (index 1)
        score = probabilities[0][1]

        return ScoreOutput(
            input_index=vector_input.input_index,
            ml_score=float(score),
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