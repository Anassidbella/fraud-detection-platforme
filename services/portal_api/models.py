# In services/portal_api/models.py

from sqlalchemy import Column, Integer, String, JSON, DateTime, func
from .database import Base  # <-- This import is critical
import uuid

def generate_app_id(context):
    """Generates a unique application ID with a domain prefix."""
    domain = context.get_current_parameters().get('domain_name', 'generic')
    prefix = "app_" + domain[:4].lower()
    return f"{prefix}_{uuid.uuid4().hex[:16]}"

class Application(Base):
    __tablename__ = "applications"

    id = Column(Integer, primary_key=True, index=True)
    
    # Use the default function to generate the application_id
    application_id = Column(String, unique=True, index=True, default=generate_app_id)
    username = Column(String, unique=True, index=True, nullable=False)
    hashed_password = Column(String, nullable=False)    
    client_name = Column(String, index=True, nullable=False)
    domain_name = Column(String, nullable=False)
    
    # Store rules as JSON in the database
    rules_json = Column(JSON)
    
    status = Column(String, default="active", nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())