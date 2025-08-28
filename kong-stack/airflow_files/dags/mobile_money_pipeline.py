from __future__ import annotations

import os
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

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
from airflow.utils.dates import days_ago

# --- DOMAIN SPECIFIC CONFIGURATION ---
DOMAIN_NAME = "mobile_money"
INPUT_CSV_FILENAME = 'mobile_money_fraud_dataset_enhanced_200k.csv'

# --- MLflow Experiment Configuration ---
EXPERIMENT_NAME = f"{DOMAIN_NAME.replace('_', ' ').title()} Fraud Classification"
EXPERIMENT_ARTIFACT_LOCATION = f"s3://mlflow-artifacts/{DOMAIN_NAME}_fraud_classification_exp"

# --- MLflow Experiment Setup ---
try:
    experiment = mlflow.get_experiment_by_name(EXPERIMENT_NAME)
    if experiment is None:
        mlflow.create_experiment(name=EXPERIMENT_NAME, artifact_location=EXPERIMENT_ARTIFACT_LOCATION)
    mlflow.set_experiment(experiment_name=EXPERIMENT_NAME)
except Exception as e:
    print(f"ERROR during MLflow experiment setup: {e}")
    raise

# --- FILE PATHS & HELPERS ---
BASE_DAG_DATA_PATH = f'/opt/airflow/dags/data/{DOMAIN_NAME}'
RAW_DATA_PATH = f'{BASE_DAG_DATA_PATH}/{INPUT_CSV_FILENAME}'
FEATURES_PATH_TEMP = f'{BASE_DAG_DATA_PATH}/features_temp.npy'
LABELS_PATH_TEMP = f'{BASE_DAG_DATA_PATH}/labels_temp.csv'
FEATURE_NAMES_PATH_TEMP = f'{BASE_DAG_DATA_PATH}/feature_names.txt'
FEATURE_IMPORTANCE_PATH_TEMP = f'{BASE_DAG_DATA_PATH}/feature_importance.png'

def ensure_dir(directory_path):
    os.makedirs(directory_path, exist_ok=True)

# --- TASK 1: Enhanced, Point-in-Time Correct Feature Engineering ---
def feature_engineering_and_vectorize_task():
    print(f"--- Task 1: Starting FINAL Feature Engineering & Vectorization for {DOMAIN_NAME} ---")
    ensure_dir(BASE_DAG_DATA_PATH)

    with mlflow.start_run(run_name=f"{DOMAIN_NAME}_feature_engineering_run") as run:
        mlflow.set_tags({"domain": DOMAIN_NAME, "task": "feature_engineering"})
        df = pd.read_csv(RAW_DATA_PATH)
        df.columns = df.columns.str.lower()

        print("Creating realistic, point-in-time behavioral features...")
        df['timestamp_dt'] = pd.to_datetime(df['timestamp'])
        df.sort_values(['user_id', 'timestamp_dt'], inplace=True)

        df['amount_to_balance_ratio'] = df['transaction_amount'] / (df['balance_before'] + 1e-6)
        df['avg_txn_amt_for_user'] = df.groupby('user_id')['transaction_amount'].expanding().mean().reset_index(level=0, drop=True)
        df['time_since_last_txn_seconds'] = df.groupby('user_id')['timestamp_dt'].diff().dt.total_seconds()
        df['amount_vs_user_avg_ratio'] = df['transaction_amount'] / (df['avg_txn_amt_for_user'] + 1e-6)

        df.replace([np.inf, -np.inf], 0, inplace=True)
        df.fillna(0, inplace=True)

        mlflow.log_param("final_features", "amount_to_balance_ratio, avg_txn_amt_for_user, time_since_last_txn_seconds, amount_vs_user_avg_ratio")

        y = df['is_fraud']
        features_to_drop = ['is_fraud', 'user_id', 'recipient_id', 'timestamp', 'timestamp_dt']
        X_features = df.drop(columns=features_to_drop)

        numerical_features = X_features.select_dtypes(include=np.number).columns.tolist()
        categorical_features = X_features.select_dtypes(include=['object', 'category']).columns.tolist()

        vectorizer = ColumnTransformer([
            ('num', StandardScaler(), numerical_features),
            ('cat', OneHotEncoder(handle_unknown='ignore', sparse_output=False), categorical_features)
        ])

        X_features_transformed = vectorizer.fit_transform(X_features)

        np.save(FEATURES_PATH_TEMP, X_features_transformed)
        y.to_csv(LABELS_PATH_TEMP, index=False, header=False)
        feature_names = vectorizer.get_feature_names_out()
        with open(FEATURE_NAMES_PATH_TEMP, 'w') as f:
            f.write(','.join(feature_names))

        print(f"Feature engineering complete. Vector shape: {X_features_transformed.shape}")

        initial_types_onnx = [(col, FloatTensorType([None, 1])) for col in numerical_features] + \
                             [(col, StringTensorType([None, 1])) for col in categorical_features]
        onnx_model_object = convert_sklearn(vectorizer, initial_types=initial_types_onnx, target_opset=12)

        model_info = mlflow.onnx.log_model(
            onnx_model=onnx_model_object,
            artifact_path=f"{DOMAIN_NAME}_vectorizer_onnx",
            input_example=X_features.head(5)
        )
        mlflow.register_model(model_info.model_uri, f"{DOMAIN_NAME}_transaction_vectorizer")
        print("Preprocessor complete and vectorizer logged & registered.")

# --- TASK 2: Hyperparameter Tuning and Final Model Training ---
def tune_and_train_classifier_task():
    print(f"--- Task 2: Starting Hyperparameter Tuning & Training for {DOMAIN_NAME} ---")
    with mlflow.start_run(run_name=f"{DOMAIN_NAME}_classifier_tuning_and_training") as run:
        mlflow.set_tags({"domain": DOMAIN_NAME, "task": "tuning_and_training", "model_type": "XGBoost"})

        X = np.load(FEATURES_PATH_TEMP)
        y = pd.read_csv(LABELS_PATH_TEMP, header=None).squeeze("columns")
        X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.25, random_state=42, stratify=y)

        param_grid = {
            'n_estimators': [100, 200],
            'max_depth': [3, 5],
            'learning_rate': [0.05, 0.1],
            'scale_pos_weight': [10, 20]
        }

        xgb_classifier = XGBClassifier(
            objective='binary:logistic',
            eval_metric='logloss',
            use_label_encoder=False,
            random_state=42
        )

        grid_search = GridSearchCV(
            estimator=xgb_classifier,
            param_grid=param_grid,
            cv=3,
            scoring='roc_auc',
            verbose=2
        )

        grid_search.fit(X_train, y_train)
        best_model = grid_search.best_estimator_

        mlflow.log_params(grid_search.best_params_)
        mlflow.log_metric("best_cv_roc_auc", grid_search.best_score_)

        y_pred_proba = best_model.predict_proba(X_test)[:, 1]
        metrics = {
            "roc_auc": roc_auc_score(y_test, y_pred_proba),
            "accuracy": accuracy_score(y_test, (y_pred_proba > 0.5)),
            "precision": precision_score(y_test, (y_pred_proba > 0.5)),
            "recall": recall_score(y_test, (y_pred_proba > 0.5)),
            "f1_score": f1_score(y_test, (y_pred_proba > 0.5))
        }
        mlflow.log_metrics({f"test_{k}": v for k, v in metrics.items()})

        with open(FEATURE_NAMES_PATH_TEMP, 'r') as f:
            feature_names = f.read().split(',')

        importances = best_model.feature_importances_
        sorted_indices = np.argsort(importances)[-15:]

        plt.figure(figsize=(10, 8))
        plt.barh(np.array(feature_names)[sorted_indices], importances[sorted_indices])
        plt.xlabel("XGBoost Feature Importance")
        plt.title("Top 15 Feature Importances (Mobile Money)")
        plt.tight_layout()
        plt.savefig(FEATURE_IMPORTANCE_PATH_TEMP)
        mlflow.log_artifact(FEATURE_IMPORTANCE_PATH_TEMP, "plots")

        model_info = mlflow.xgboost.log_model(
            xgb_model=best_model,
            artifact_path=f"{DOMAIN_NAME}_fraud_classifier_model",
            input_example=X_train[:5]
        )
        mlflow.register_model(model_info.model_uri, f"{DOMAIN_NAME}_fraud_classifier")
        print("Best classifier model logged and registered.")

# --- DAG DEFINITION ---
dag_id = f'{DOMAIN_NAME}_robust_fraud_classification_pipeline'

with DAG(
    dag_id=dag_id,
    schedule=None,
    start_date=days_ago(1),
    catchup=False,
    tags=[DOMAIN_NAME, 'classification', 'tuning', 'xgboost'],
    doc_md="""
    ### Robust Supervised Fraud Classification Pipeline for Mobile Money
    This pipeline uses enhanced synthetic features and point-in-time correct logic to build an XGBoost model against mobile money fraud.
    """
) as dag:
    feature_engineering_task = PythonOperator(
        task_id='feature_engineering_and_vectorize',
        python_callable=feature_engineering_and_vectorize_task,
    )

    train_task = PythonOperator(
        task_id='tune_train_and_evaluate',
        python_callable=tune_and_train_classifier_task,
    )

    feature_engineering_task >> train_task