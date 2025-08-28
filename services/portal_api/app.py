import json
import logging
import os
import pathlib
from typing import Any, Dict, List

from dotenv import load_dotenv
from fastapi import (Depends, FastAPI, HTTPException, Response,
                     Security, logger)
from fastapi.responses import HTMLResponse
from fastapi.security import (APIKeyCookie, APIKeyHeader,
                              OAuth2PasswordRequestForm)
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session
# Load environment variables at the very beginning
load_dotenv()

# In portal_api/app.py

# Use relative imports for sibling modules within the package
from . import crud
from . import kong_client
from . import models
from . import schemas
from . import security
from .database import BASE_DIR, Base, SessionLocal, engine # This was already correct
# Create database tables if they don't exist
Base.metadata.create_all(bind=engine)

# Configure logging
logging.basicConfig(level=logging.INFO)

# Initialize the FastAPI application
app = FastAPI(
    title="Fraud SDK Self-Service Portal & Config API",
    description="Provides a public registration UI and a secure configuration endpoint for the Fraud Detection SDK."
)

# --- Mount the static directory for serving HTML/CSS/JS files ---
app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")


# --- Database Dependency ---
def get_db():
    """Provides a database session to an endpoint."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

# --- Security Dependency 1: For the SDK (uses Authorization Header) ---
api_key_header_sdk = APIKeyHeader(name="Authorization", auto_error=False, description="Bearer token for SDK authentication.")

def get_current_app_from_token(token: str = Security(api_key_header_sdk), db: Session = Depends(get_db)):
    """Verifies a JWT from the 'Authorization: Bearer <token>' header."""
    credentials_exception = HTTPException(status_code=401, detail="Invalid token or application not found")
    if not token or not token.startswith("Bearer "):
        raise credentials_exception
    
    token = token.replace("Bearer ", "")
    token_data = security.verify_token(token, credentials_exception)
    app_record = crud.get_application_by_id(db, app_id=token_data.app_id)
    if app_record is None:
        raise credentials_exception
    return app_record

# --- Security Dependency 2: For the UI (uses a secure Cookie) ---
cookie_security_scheme = APIKeyCookie(name="access_token_cookie", auto_error=True)

def get_current_app_from_cookie(token: str = Security(cookie_security_scheme), db: Session = Depends(get_db)):
    """Verifies a JWT from the 'access_token_cookie'."""
    credentials_exception = HTTPException(
        status_code=401,
        detail="Not authenticated. Your session may have expired. Please log in again.",
    )
    token_data = security.verify_token(token, credentials_exception)
    app_record = crud.get_application_by_id(db, app_id=token_data.app_id)
    if app_record is None:
        raise credentials_exception
    return app_record


# --- PUBLIC UI ENDPOINTS ---

@app.get("/", response_class=HTMLResponse, tags=["UI"])
async def serve_registration_ui():
    """Serves the main HTML registration page."""
    try:
        with open(BASE_DIR / "static/index.html") as f:
            return HTMLResponse(content=f.read(), status_code=200)
    except FileNotFoundError:
        return HTMLResponse(content="<h1>UI file not found: index.html</h1>", status_code=500)

@app.get("/login-ui", response_class=HTMLResponse, tags=["UI"])
async def serve_login_ui():
    """Serves the HTML login page."""
    try:
        with open(BASE_DIR / "static/login.html") as f:
            return HTMLResponse(content=f.read(), status_code=200)
    except FileNotFoundError:
        return HTMLResponse(content="<h1>UI file not found: login.html</h1>", status_code=500)

@app.get("/rules-ui", response_class=HTMLResponse, tags=["UI"])
async def serve_rules_ui():
    """Serves the main HTML page for the Rule Management UI."""
    try:
        with open(BASE_DIR / "static/rules.html") as f:
            return HTMLResponse(content=f.read(), status_code=200)
    except FileNotFoundError:
        return HTMLResponse(content="<h1>UI file not found: rules.html</h1>", status_code=500)


# --- PUBLIC AUTHENTICATION API ---

@app.post("/register", response_model=schemas.ApplicationCredentials, tags=["Authentication"])
async def register_new_application(app_in: schemas.ApplicationCreate, db: Session = Depends(get_db)):
    """[PUBLIC] Registers a new user and provisions them in the API gateway."""
    db_user = crud.get_application_by_username(db, username=app_in.username)
    if db_user:
        raise HTTPException(status_code=400, detail="Username already registered")

    app_record = crud.create_application(db=db, app=app_in)
    
    success = await kong_client.create_kong_consumer_and_jwt_credential(
        username=app_record.application_id, custom_id=app_record.application_id
    )
    if not success:
        raise HTTPException(status_code=503, detail="Failed to provision API gateway credentials.")

    token_payload = {"sub": app_record.application_id, "iss": app_record.application_id}
    api_token = security.create_access_token(data=token_payload)
    
    logging.info(f"Successfully registered client: {app_record.application_id}")
    return {"application_id": app_record.application_id, "api_token": api_token}

@app.post("/login", response_model=schemas.Token, tags=["Authentication"])
async def login_for_access_token(response: Response, db: Session = Depends(get_db), form_data: OAuth2PasswordRequestForm = Depends()):
    """[PUBLIC] Verifies credentials and sets a secure cookie for UI sessions."""
    user = crud.get_application_by_username(db, username=form_data.username)
    if not user or not security.verify_password(form_data.password, user.hashed_password):
        raise HTTPException(status_code=401, detail="Incorrect username or password")
    
    access_token = security.create_access_token(data={"sub": user.application_id, "iss": user.application_id})
    response.set_cookie(key="access_token_cookie", value=f"{access_token}", httponly=True, secure=False, samesite="lax")
    return {"access_token": access_token, "token_type": "bearer"}

@app.post("/logout", tags=["Authentication"])
async def logout(response: Response):
    """[PUBLIC] Clears the authentication cookie, logging the user out."""
    response.delete_cookie("access_token_cookie")
    return {"message": "Successfully logged out"}


# --- SECURE RULES API (for the UI) ---

@app.get("/rules/schema", response_model=Dict[str, Any], tags=["Rules Management (UI Secure)"])
def get_rule_schema_for_client(current_app: models.Application = Depends(get_current_app_from_cookie)):
    """[UI-SECURE] Returns the full rule schema for the authenticated client's domain."""
    domain = current_app.domain_name
    try:
        with open(BASE_DIR / "rule_primitives.json", "r") as f: schema = json.load(f)
        domain_schema_path = BASE_DIR / "domain_schemas" / f"{domain}.json"
        with open(domain_schema_path, "r") as f:
            domain_data = json.load(f)
            schema["fields"] = [field for group in domain_data["field_groups"] for field in group["fields"]]
            schema["field_groups"] = domain_data["field_groups"]
        return schema
    except FileNotFoundError: raise HTTPException(status_code=404, detail=f"Rule schema for domain '{domain}' not found.")

@app.get("/rules", response_model=List[Dict[str, Any]], tags=["Rules Management (UI Secure)"])
def get_rules_for_client(current_app: models.Application = Depends(get_current_app_from_cookie)):
    """[UI-SECURE] Fetches the current list of rules for the authenticated client."""
    return current_app.rules_json or []

@app.post("/rules", response_model=Dict[str, Any], status_code=201, tags=["Rules Management (UI Secure)"])
def add_new_rule(new_rule: Dict[str, Any], current_app: models.Application = Depends(get_current_app_from_cookie), db: Session = Depends(get_db)):
    """[UI-SECURE] Adds a single new rule to the client's existing ruleset."""
    try:
        crud.add_rule_to_application(db=db, app=current_app, new_rule=new_rule)
        return new_rule
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e))

@app.put("/rules/{rule_id}", response_model=Dict[str, Any], tags=["Rules Management (UI Secure)"])
def update_existing_rule(rule_id: str, updated_rule: Dict[str, Any], current_app: models.Application = Depends(get_current_app_from_cookie), db: Session = Depends(get_db)):
    """[UI-SECURE] Updates a single existing rule identified by its ruleId."""
    updated_app = crud.update_single_rule_in_application(db=db, app=current_app, rule_id=rule_id, updated_rule=updated_rule)
    if updated_app is None:
        raise HTTPException(status_code=404, detail=f"Rule with ID '{rule_id}' not found.")
    return updated_rule

@app.delete("/rules/{rule_id}", status_code=204, tags=["Rules Management (UI Secure)"])
def delete_existing_rule(rule_id: str, current_app: models.Application = Depends(get_current_app_from_cookie), db: Session = Depends(get_db)):
    """[UI-SECURE] Deletes a single existing rule identified by its ruleId."""
    success = crud.delete_single_rule_from_application(db=db, app=current_app, rule_id=rule_id)
    if not success:
        raise HTTPException(status_code=404, detail=f"Rule with ID '{rule_id}' not found.")
    return


# --- SECURE SDK API ---

@app.get("/runtime-config", response_model=schemas.SdkRuntimeConfig, tags=["SDK (Secure)"])
def get_sdk_runtime_configuration(current_app: models.Application = Depends(get_current_app_from_token)):
    """[SDK-SECURE] This is the 'phone home' endpoint for the SDK."""
    try:
        with open(BASE_DIR / "domain_blueprints.json", "r") as f: blueprints = json.load(f)
    except FileNotFoundError:
        raise HTTPException(status_code=500, detail="Server misconfiguration: domain blueprints file not found.")

    app_domain = current_app.domain_name
    if app_domain not in blueprints["domainDetails"]:
        raise HTTPException(status_code=500, detail=f"Configuration for domain '{app_domain}' not found in server blueprints.")

    domain_config_blueprint = blueprints["domainDetails"][app_domain]
    domain_config_blueprint["domainName"] = app_domain

    runtime_config = {
        "mlflowTrackingUri": blueprints["global"]["mlflowTrackingUri"],
        "kongGatewayBaseUrl": blueprints["global"]["kongGatewayBaseUrl"],
        "domainConfigurations": { app_domain: domain_config_blueprint },
        "rules": { app_domain: current_app.rules_json or [] }
    }
    return runtime_config


