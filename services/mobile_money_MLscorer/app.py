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

# --- Configuration ---
DOMAIN_NAME = "mobile_money"
MLFLOW_TRACKING_URI = os.getenv("MLFLOW_TRACKING_URI", "http://localhost:5000")
# This variable now points to the classifier model
CLASSIFIER_MODEL_NAME = os.getenv("ANOMALY_MODEL_NAME", f"{DOMAIN_NAME}_fraud_classifier")
MODEL_ALIAS = os.getenv("MODEL_ALIAS", "production")
APP_PORT = int(os.getenv("PORT", "5001"))

# --- Application and Logging Setup ---
app = FastAPI(title="Mobile Money Fraud Classification Service")
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# --- Global Model Storage ---
classifier_model_g: Optional[any] = None # Renamed for clarity
model_version_g: Optional[str] = None

# --- API Data Models ---
class VectorInput(BaseModel):
    input_index: Optional[int] = None
    vector: List[float]

# <--- CHANGE 2: Simplified ScoreOutput model ---
# The scorer's only job is to provide the score. The SDK's rules will make the decision.
class ScoreOutput(BaseModel):
    input_index: Optional[int] = None
    ml_score: float # This is the fraud probability (0.0 to 1.0)
    model_version_used: str
    domain_used: str = DOMAIN_NAME

# --- Startup: Load the XGBoost Model ---
@app.on_event("startup")
async def load_model():
    global classifier_model_g, model_version_g
    logger.info(f"[{DOMAIN_NAME}] Starting service: loading fraud classifier model...")
    model_uri = f"models:/{CLASSIFIER_MODEL_NAME}@{MODEL_ALIAS}"
    logger.info(f"[{DOMAIN_NAME}] Loading model from: {model_uri}")

    try:
        mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
        # <--- CHANGE 3: Use mlflow.xgboost.load_model ---
        classifier_model_g = mlflow.xgboost.load_model(model_uri)
        
        client = mlflow.tracking.MlflowClient()
        version_info = client.get_model_version_by_alias(CLASSIFIER_MODEL_NAME, MODEL_ALIAS)
        model_version_g = version_info.version

        logger.info(f"[{DOMAIN_NAME}] Successfully loaded model '{CLASSIFIER_MODEL_NAME}' version '{model_version_g}'.")

    except Exception as e:
        logger.error(f"[{DOMAIN_NAME}] Failed to load classifier model: {e}", exc_info=True)
        classifier_model_g = None

# --- Main Endpoint: Score Vector ---
@app.post("/score_vector", response_model=ScoreOutput, summary="Score a feature vector for fraud probability")
async def score_vector(vector_input: VectorInput):
    if not classifier_model_g:
        raise HTTPException(status_code=503, detail="Model not loaded. Service unavailable.")

    try:
        input_vector_np = np.array(vector_input.vector, dtype=np.float32).reshape(1, -1)

        # <--- CHANGE 4: Use predict_proba() to get the fraud score ---
        probabilities = classifier_model_g.predict_proba(input_vector_np)
        
        # The fraud score is the probability of the second class (index 1)
        score = probabilities[0][1]

        return ScoreOutput(
            input_index=vector_input.input_index,
            ml_score=float(score),
            model_version_used=model_version_g
        )
    except Exception as e:
        logger.error(f"[{DOMAIN_NAME}] Internal error during scoring: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Internal error during scoring.")

# --- Health Check ---
@app.get("/health", summary="Health check")
async def health_check():
    if classifier_model_g:
        return {
            "status": "UP", 
            "domain": DOMAIN_NAME, 
            "model_name": CLASSIFIER_MODEL_NAME,
            "model_version": model_version_g
        }
    else:
        raise HTTPException(status_code=503, detail="Model not loaded.")

if __name__ == "__main__":
    logger.info(f"[{DOMAIN_NAME}] Starting Uvicorn server on http://0.0.0.0:{APP_PORT}")
    uvicorn.run("app:app", host="0.0.0.0", port=APP_PORT, reload=True)