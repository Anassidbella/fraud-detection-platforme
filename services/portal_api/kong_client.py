import httpx
import os
import logging

KONG_ADMIN_URL = os.getenv("KONG_ADMIN_URL")
JWT_SECRET_KEY = os.getenv("JWT_SECRET_KEY")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

async def create_kong_consumer_and_jwt_credential(username: str, custom_id: str):
    """
    Creates a consumer in Kong and adds a JWT credential to it, ensuring the
    credential key matches the username/issuer.
    """
    if not KONG_ADMIN_URL or not JWT_SECRET_KEY:
        logger.error("KONG_ADMIN_URL or JWT_SECRET_KEY is not set in environment.")
        return False

    async with httpx.AsyncClient() as client:
        try:
            # 1. Create the Consumer
            consumer_payload = {"username": username, "custom_id": custom_id}
            consumer_response = await client.post(f"{KONG_ADMIN_URL}/consumers", json=consumer_payload)
            if consumer_response.status_code == 409:
                logger.warning(f"Kong consumer '{username}' already exists.")
            else:
                consumer_response.raise_for_status()
                logger.info(f"Successfully created/verified Kong consumer '{username}'")

            # 2. Add JWT Credential, explicitly setting the 'key'
            jwt_payload = {
                "key": username,  # <-- THIS IS THE CRITICAL FIX
                "algorithm": "HS256",
                "secret": JWT_SECRET_KEY
            }
            jwt_response = await client.post(f"{KONG_ADMIN_URL}/consumers/{username}/jwt", json=jwt_payload)
            
            if jwt_response.status_code == 409:
                 logger.warning(f"JWT credential for consumer '{username}' already exists.")
            else:
                jwt_response.raise_for_status()
                logger.info(f"Successfully added JWT credential to consumer '{username}'")
            
            return True

        except httpx.HTTPStatusError as e:
            logger.error(f"Error communicating with Kong Admin API: {e.response.status_code} - {e.response.text}")
            return False
        except httpx.RequestError as e:
            logger.error(f"Network error connecting to Kong Admin API at {KONG_ADMIN_URL}: {e}")
            return False