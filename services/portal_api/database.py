from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
import os
import pathlib # <-- Import pathlib
from dotenv import load_dotenv

# --- Find the .env file reliably ---
# 1. Get the directory of the current file (database.py)
# 2. This is the path to the .env file in the same directory.
BASE_DIR = pathlib.Path(__file__).parent
# 3. Load the .env file from that specific path.
load_dotenv(dotenv_path=BASE_DIR / ".env")
load_dotenv(dotenv_path=BASE_DIR / ".env.local", override=True)

SQLALCHEMY_DATABASE_URL = os.getenv("DATABASE_URL")

# Make sure there is a URL before trying to create an engine
if not SQLALCHEMY_DATABASE_URL:
    raise ValueError("No DATABASE_URL found in environment variables. Please check your .env file.")

engine = create_engine(SQLALCHEMY_DATABASE_URL)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

# This is the single, declarative base that all your models will inherit from.
Base = declarative_base()