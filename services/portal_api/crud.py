from typing import Any, Dict, List, Optional
from sqlalchemy.orm import Session
from sqlalchemy.orm.attributes import flag_modified

# In portal_api/crud.py

from . import models
from . import schemas
from . import security
def get_application_by_id(db: Session, app_id: str):
    """Fetches an application by its unique application_id."""
    return db.query(models.Application).filter(models.Application.application_id == app_id).first()

def get_application_by_username(db: Session, username: str):
    """Fetches an application by its unique username."""
    return db.query(models.Application).filter(models.Application.username == username).first()

def create_application(db: Session, app: schemas.ApplicationCreate) -> models.Application:
    """Creates a new application record, hashing the user's password."""
    hashed_password = security.get_password_hash(app.password)
    
    db_app = models.Application(
        client_name=app.client_name,
        domain_name=app.domain_name,
        rules_json=app.rules,  # Comma was missing here
        username=app.username,
        hashed_password=hashed_password
    )
    db.add(db_app)
    db.commit()
    db.refresh(db_app)
    return db_app

def update_application_rules(db: Session, app_id: str, rules: List[Dict[str, Any]]):
    """Replaces the entire ruleset for a given application."""
    db_app = get_application_by_id(db, app_id=app_id)
    if db_app:
        db_app.rules_json = rules
        flag_modified(db_app, "rules_json")
        db.commit()
        db.refresh(db_app)
    return db_app

def add_rule_to_application(db: Session, app: models.Application, new_rule: Dict[str, Any]) -> models.Application:
    """Adds a single new rule to an application's ruleset."""
    new_rule_id = new_rule.get("ruleId")
    if not new_rule_id:
        raise ValueError("The new rule must have a 'ruleId'.")

    current_rules = list(app.rules_json) if app.rules_json else []
    
    if any(r.get("ruleId") == new_rule_id for r in current_rules):
        raise ValueError(f"A rule with ID '{new_rule_id}' already exists.")

    current_rules.append(new_rule)
    app.rules_json = current_rules
    
    # Flag the JSON field as modified for SQLAlchemy
    flag_modified(app, "rules_json")
    
    db.add(app)
    db.commit()
    db.refresh(app)
    return app

# --- THIS FUNCTION HAS BEEN FIXED ---
def update_single_rule_in_application(db: Session, app: models.Application, rule_id: str, updated_rule: Dict[str, Any]) -> Optional[models.Application]:
    """
    Updates a single rule within an application's ruleset, identified by its original ruleId.
    This now correctly handles changes to the ruleId itself.
    """
    current_rules = app.rules_json or []
    new_rule_id = updated_rule.get("ruleId")

    if not new_rule_id:
        raise ValueError("The updated rule must have a 'ruleId'.")

    # --- NEW: Safety check for duplicate IDs ---
    # Check if the new ID already exists in another rule.
    if any(r.get("ruleId") == new_rule_id and r.get("ruleId") != rule_id for r in current_rules):
        raise ValueError(f"Another rule with the ID '{new_rule_id}' already exists.")

    rule_found = False
    new_rules = []
    for rule in current_rules:
        # Find the rule to update using its ORIGINAL ID
        if rule.get("ruleId") == rule_id:
            # Append the FULL updated rule object from the request body.
            # This correctly preserves the new, edited ruleId.
            new_rules.append(updated_rule)
            rule_found = True
        else:
            new_rules.append(rule)
    
    if not rule_found:
        return None # The original rule ID was not found

    app.rules_json = new_rules
    flag_modified(app, "rules_json")
    db.add(app)
    db.commit()
    db.refresh(app)
    return app

def delete_single_rule_from_application(db: Session, app: models.Application, rule_id: str) -> bool:
    """Deletes a single rule from an application's ruleset, identified by ruleId."""
    current_rules = app.rules_json or []
    initial_length = len(current_rules)
    
    new_rules = [r for r in current_rules if r.get("ruleId") != rule_id]

    if len(new_rules) == initial_length:
        return False # Rule was not found to be deleted

    app.rules_json = new_rules
    flag_modified(app, "rules_json")
    db.add(app)
    db.commit()
    db.refresh(app)
    return True