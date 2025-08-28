# kong-stack/Airflow.Dockerfile

# Use the same base Airflow image version as in your docker-compose.yml
FROM apache/airflow:2.9.1

# The requirements.txt file is in the build context's root (kong-stack/)
COPY requirements.txt /requirements.txt

RUN pip install --upgrade pip && \
    pip install --no-cache-dir -r /requirements.txt