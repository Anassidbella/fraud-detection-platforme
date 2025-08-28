from __future__ import annotations

import os
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import pendulum

# --- MLflow Imports ---
import mlflow
import mlflow.onnx
import mlflow.xgboost

# --- Scikit-learn & XGBoost Imports ---
from sklearn.model_selection import train_test_split, GridSearchCV
from sklearn.preprocessing import StandardScaler, OneHotEncoder
from sklearn.compose import ColumnTransformer
from sklearn.metrics import roc_auc_score, accuracy_score, precision_score, recall_score, f1_score
from xgboost import XGBClassifier

# --- ONNX Imports ---
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType, StringTensorType

# --- Airflow Imports ---
from airflow.models.dag import DAG
from airflow.operators.python import PythonOperator

# --- DOMAIN CONFIGURATION ---
DOMAIN_NAME = "ecommerce"
# --- UPDATED: Point to the final challenge v8 dataset ---
INPUT_CSV_FILENAME = "ecommerce_fraud_dataset_v8.1_final_challenge.csv"

# --- MLflow Configuration ---
EXPERIMENT_NAME = f"{DOMAIN_NAME.title()} Fraud Classification"
EXPERIMENT_ARTIFACT_LOCATION = f"s3://mlflow-artifacts/{DOMAIN_NAME}_fraud_classification_exp"
try:
    mlflow.set_tracking_uri(os.getenv('MLFLOW_TRACKING_URI', 'http://mlflow:5000'))
    experiment = mlflow.get_experiment_by_name(EXPERIMENT_NAME)
    if experiment is None:
        mlflow.create_experiment(EXPERIMENT_NAME, artifact_location=EXPERIMENT_ARTIFACT_LOCATION)
    mlflow.set_experiment(EXPERIMENT_NAME)
except Exception as e:
    print(f"ERROR during MLflow experiment setup: {e}"); raise

# --- FILE PATHS ---
BASE_DAG_DATA_PATH = f"/opt/airflow/dags/data/{DOMAIN_NAME}"
RAW_DATA_PATH = f"{BASE_DAG_DATA_PATH}/{INPUT_CSV_FILENAME}"
FEATURES_PATH_TEMP = f"{BASE_DAG_DATA_PATH}/features_temp.npy"
LABELS_PATH_TEMP = f"{BASE_DAG_DATA_PATH}/labels_temp.csv"
FEATURE_NAMES_PATH_TEMP = f"{BASE_DAG_DATA_PATH}/feature_names.txt"
FEATURE_IMPORTANCE_PATH_TEMP = f"{BASE_DAG_DATA_PATH}/feature_importance.png"

def ensure_dir(directory_path):
    os.makedirs(directory_path, exist_ok=True)

# --- TASK 1: Leak-Proof Feature Engineering (Unchanged and Correct) ---
def feature_engineering_and_vectorize_task():
    print(f"--- Task 1: LEAK-PROOF Feature Engineering for {DOMAIN_NAME} ---")
    ensure_dir(BASE_DAG_DATA_PATH)
    with mlflow.start_run(run_name=f"{DOMAIN_NAME}_feature_engineering_run") as run:
        mlflow.set_tags({"domain": DOMAIN_NAME, "task": "feature_engineering"})
        df = pd.read_csv(RAW_DATA_PATH); df.columns = df.columns.str.lower()
        print("Creating point-in-time behavioral features...")
        df["transactiontimestamp"] = pd.to_datetime(df["transactiontimestamp"]) # Ensure correct type
        df.sort_values(["userid", "transactiontimestamp"], inplace=True)
        # Use expanding to prevent data leakage from the future
        df["avg_txn_amt_for_user"] = df.groupby("userid")["transactionamount"].expanding().mean().reset_index(level=0, drop=True)
        df["time_since_last_txn_seconds"] = df.groupby("userid")["transactiontimestamp"].diff().dt.total_seconds()
        df["amount_vs_user_avg_ratio"] = df["transactionamount"] / (df["avg_txn_amt_for_user"] + 1e-6)
        df['time_since_last_txn_seconds'].fillna(-1, inplace=True) # Critical leak plug
        df.replace([np.inf, -np.inf], 0, inplace=True)
        mlflow.log_param("fillna_strategy", "time_since_last_txn_seconds filled with -1")
        y = df["isfraud"]; X_features = df.drop(columns=['isfraud', 'userid', 'transactiontimestamp'])
        numerical_features = X_features.select_dtypes(include=np.number).columns.tolist()
        categorical_features = X_features.select_dtypes(include=["object", "category"]).columns.tolist()
        vectorizer = ColumnTransformer([("num", StandardScaler(), numerical_features), ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), categorical_features)], remainder='passthrough')
        X_features_transformed = vectorizer.fit_transform(X_features)
        np.save(FEATURES_PATH_TEMP, X_features_transformed); y.to_csv(LABELS_PATH_TEMP, index=False, header=False)
        feature_names = vectorizer.get_feature_names_out()
        with open(FEATURE_NAMES_PATH_TEMP, "w") as f: f.write(",".join(feature_names))
        print(f"Feature engineering complete. Vector shape: {X_features_transformed.shape}")
        initial_types_onnx = [(col, FloatTensorType([None, 1])) for col in numerical_features] + [(col, StringTensorType([None, 1])) for col in categorical_features]
        onnx_model = convert_sklearn(vectorizer, initial_types=initial_types_onnx, target_opset=12)
        mlflow.onnx.log_model(onnx_model=onnx_model, artifact_path=f"{DOMAIN_NAME}_vectorizer_onnx", input_example=X_features.head(5), registered_model_name=f"{DOMAIN_NAME}_transaction_vectorizer")
        print("Preprocessor complete and vectorizer logged & registered.")

# --- TASK 2: Fast Hyperparameter Tuning and Training ---
def tune_and_train_classifier_task():
    print(f"--- Task 2: FAST Tuning & Training for {DOMAIN_NAME} ---")
    with mlflow.start_run(run_name=f"{DOMAIN_NAME}_classifier_tuning_and_training") as run:
        mlflow.set_tags({"domain": DOMAIN_NAME, "task": "tuning_and_training", "model_type": "XGBoost"})
        X = np.load(FEATURES_PATH_TEMP); y = pd.read_csv(LABELS_PATH_TEMP, header=None).squeeze("columns")
        X_train, X_test, y_train, y_test = train_test_split(X, y, stratify=y, test_size=0.25, random_state=42)

        param_grid = {
            "n_estimators": [150], "max_depth": [5, 6],
            "learning_rate": [0.1], "scale_pos_weight": [25, 35]
        }
        
        xgb_classifier = XGBClassifier(
            objective="binary:logistic", eval_metric="logloss",
            use_label_encoder=False, random_state=42, n_jobs=-1
        )
        
        print(f"Starting GridSearchCV with a focused grid: {param_grid}")
        grid_search = GridSearchCV(xgb_classifier, param_grid, scoring="roc_auc", cv=2, verbose=2)
        grid_search.fit(X_train, y_train)

        best_model = grid_search.best_estimator_
        mlflow.log_params(grid_search.best_params_)
        mlflow.log_metric("best_cv_roc_auc", grid_search.best_score_)
        y_pred_proba = best_model.predict_proba(X_test)[:, 1]
        y_pred = (y_pred_proba > 0.5).astype(int)
        metrics = {"roc_auc": roc_auc_score(y_test, y_pred_proba), "accuracy": accuracy_score(y_test, y_pred), "precision": precision_score(y_test, y_pred), "recall": recall_score(y_test, y_pred), "f1_score": f1_score(y_test, y_pred)}
        mlflow.log_metrics({f"test_{k}": v for k, v in metrics.items()})
        print(f"Final Test Metrics for {DOMAIN_NAME}: {metrics}")

        with open(FEATURE_NAMES_PATH_TEMP, "r") as f: feature_names = f.read().split(",")
        importances = best_model.feature_importances_; top_indices = np.argsort(importances)[-15:]
        plt.figure(figsize=(10, 8)); plt.barh(np.array(feature_names)[top_indices], importances[top_indices])
        plt.xlabel("XGBoost Feature Importance"); plt.title(f"Top 15 Features ({DOMAIN_NAME.title()})")
        plt.tight_layout(); plt.savefig(FEATURE_IMPORTANCE_PATH_TEMP)
        mlflow.log_artifact(FEATURE_IMPORTANCE_PATH_TEMP, "plots")
        model_info = mlflow.xgboost.log_model(xgb_model=best_model, artifact_path=f"{DOMAIN_NAME}_fraud_classifier_model", input_example=pd.DataFrame(X_train[:5], columns=feature_names), registered_model_name=f"{DOMAIN_NAME}_fraud_classifier")
        print("Best classifier model logged and registered.")

# --- DAG DEFINITION ---
with DAG(
    dag_id=f'{DOMAIN_NAME}_final_challenge_pipeline',
    schedule=None, start_date=pendulum.datetime(2023, 1, 1, tz="UTC"), catchup=False,
    tags=[DOMAIN_NAME, "classification", "xgboost", "final_version"],
    doc_md="""
    ### Final Challenge E-commerce Fraud Pipeline (v8)
    This DAG trains a robust XGBoost model on the **definitive v8 dataset**, which is
    designed to be ambiguous and challenging, forcing the model to learn complex patterns.
    It uses a fast, focused hyperparameter tuning strategy.
    """
) as dag:
    feature_engineering_task = PythonOperator(task_id="leak_proof_feature_engineering", python_callable=feature_engineering_and_vectorize_task)
    train_task = PythonOperator(task_id="fast_tune_and_train_model", python_callable=tune_and_train_classifier_task)
    feature_engineering_task >> train_task