#!/bin/bash

# PDMS Monolith Deployment Script
# This script helps deploy the monolith container to a remote server

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}PDMS Monolith Deployment Script${NC}"
echo "=================================="
echo ""

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo -e "${RED}Error: Docker is not installed${NC}"
    exit 1
fi

# Configuration (modify these values)
POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:-pdms_user}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-pdms_password}"
KAFKA_HOST="${KAFKA_HOST:-localhost}"
KAFKA_PORT="${KAFKA_PORT:-9092}"
APP_PORT="${APP_PORT:-8080}"

echo "Configuration:"
echo "  PostgreSQL: ${POSTGRES_HOST}:${POSTGRES_PORT}"
echo "  Kafka: ${KAFKA_HOST}:${KAFKA_PORT}"
echo "  App Port: ${APP_PORT}"
echo ""

# Prompt for values if not set via environment variables
read -p "PostgreSQL Host [${POSTGRES_HOST}]: " input
POSTGRES_HOST=${input:-$POSTGRES_HOST}

read -p "PostgreSQL Port [${POSTGRES_PORT}]: " input
POSTGRES_PORT=${input:-$POSTGRES_PORT}

read -p "PostgreSQL Username [${POSTGRES_USER}]: " input
POSTGRES_USER=${input:-$POSTGRES_USER}

read -sp "PostgreSQL Password: " input
echo ""
POSTGRES_PASSWORD=${input:-$POSTGRES_PASSWORD}

read -p "Kafka Host [${KAFKA_HOST}]: " input
KAFKA_HOST=${input:-$KAFKA_HOST}

read -p "Kafka Port [${KAFKA_PORT}]: " input
KAFKA_PORT=${input:-$KAFKA_PORT}

read -p "Application Port [${APP_PORT}]: " input
APP_PORT=${input:-$APP_PORT}

echo ""
echo -e "${YELLOW}Step 1: Building Docker image...${NC}"
cd "$(dirname "$0")/.."
docker build -f pdms-monolith/Dockerfile -t pdms-monolith:latest pdms-monolith/

if [ $? -ne 0 ]; then
    echo -e "${RED}Build failed!${NC}"
    exit 1
fi

echo -e "${GREEN}Build successful!${NC}"
echo ""

# Check if container already exists
if docker ps -a --format '{{.Names}}' | grep -q "^pdms-monolith$"; then
    echo -e "${YELLOW}Stopping and removing existing container...${NC}"
    docker stop pdms-monolith 2>/dev/null || true
    docker rm pdms-monolith 2>/dev/null || true
fi

echo -e "${YELLOW}Step 2: Starting container...${NC}"
docker run -d \
  --name pdms-monolith \
  -p ${APP_PORT}:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/pdms \
  -e SPRING_DATASOURCE_USERNAME=${POSTGRES_USER} \
  -e SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD} \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_HOST}:${KAFKA_PORT} \
  -v "$(pwd)/pdms-monolith/src/main/resources/jwt-keys:/app/jwt-keys:ro" \
  pdms-monolith:latest

if [ $? -ne 0 ]; then
    echo -e "${RED}Failed to start container!${NC}"
    exit 1
fi

echo -e "${GREEN}Container started successfully!${NC}"
echo ""
echo "Container ID: $(docker ps -q -f name=pdms-monolith)"
echo ""
echo "Useful commands:"
echo "  View logs:    docker logs -f pdms-monolith"
echo "  Stop:        docker stop pdms-monolith"
echo "  Restart:     docker restart pdms-monolith"
echo "  Health check: curl http://localhost:${APP_PORT}/actuator/health"
echo ""

# Wait a bit and check health
echo -e "${YELLOW}Waiting for application to start...${NC}"
sleep 5

if curl -f -s http://localhost:${APP_PORT}/actuator/health > /dev/null 2>&1; then
    echo -e "${GREEN}Application is healthy!${NC}"
    curl -s http://localhost:${APP_PORT}/actuator/health | python3 -m json.tool 2>/dev/null || curl -s http://localhost:${APP_PORT}/actuator/health
else
    echo -e "${YELLOW}Application may still be starting. Check logs with: docker logs -f pdms-monolith${NC}"
fi
