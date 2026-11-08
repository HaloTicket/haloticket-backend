# Use the ultra-lightweight Python 3.14 slim version.
FROM python:3.14-slim

# Set the working directory in the container
WORKDIR /app

# No libpq-dev/gcc needed: psycopg2-binary ships its own libpq.

# Copy file requirements and install
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy entire code to container
COPY . .

# Open port 8089 to the outside
EXPOSE 8089

# Command to start the server (single worker: the in-process rate-limit fallback assumes one process) when the container starts
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8089"]