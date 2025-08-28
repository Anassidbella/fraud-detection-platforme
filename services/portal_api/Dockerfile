# Filename: services/portal_api/Dockerfile

# 1. Base Image
FROM python:3.9-slim

# 2. Set the working directory
WORKDIR /code

# 3. Copy requirements file and install dependencies
# This path is relative to the build context defined in docker-compose.yml
# We will set the context to the project root, so this path is correct.
COPY kong-stack/requirements.txt .
RUN pip install --no-cache-dir --upgrade pip -r requirements.txt

# 4. Copy your application package into the container.
# This creates the /code/portal_api/ directory.
COPY services/portal_api/ /code/portal_api/

# 5. [CRITICAL] Define the correct command to run the application as a package.
CMD ["uvicorn", "portal_api.app:app", "--host", "0.0.0.0", "--port", "5003", "--reload"]