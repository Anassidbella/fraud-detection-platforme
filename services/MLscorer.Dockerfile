# services/MLscorer.Dockerfile

# 1. Base Image
# Start with a lean and official Python image.
FROM python:3.9-slim

# 2. Set Working Directory
# All subsequent commands will run from here.
WORKDIR /app

# 3. Copy Only Necessary Files
# First, copy the main requirements file to leverage Docker's layer caching.
COPY ./kong-stack/requirements.txt /app/requirements.txt

# 4. Install Dependencies
# Install the Python packages defined in the main requirements.txt file.
RUN pip install --no-cache-dir --upgrade pip -r requirements.txt

# 5. Copy All Service Code
# This copies the entire project context, making all services available.
# The specific service to run will be chosen in the docker-compose command.
COPY . /app

# 6. Default Command (will be overridden in docker-compose.yml)
# This is a placeholder; it's good practice but not strictly necessary here.
CMD ["echo", "Please specify a service to run in your docker-compose command."]