# Use the ultra-lightweight Python 3.14 slim version.
FROM python:3.14-slim

# Set the working directory in the container
WORKDIR /app

# Fix the error of missing C++ library when installing psycopg2
RUN apt-get update && apt-get install -y libpq-dev gcc && rm -rf /var/lib/apt/lists/*

# Copy file requirements and install
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy entire code to container
COPY . .

# Open port 8089 to the outside
EXPOSE 8089

# Command to start the server when the container starts
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8089"]