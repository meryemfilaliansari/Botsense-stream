#!/bin/bash
echo "=== BotSense-Stream Startup ==="
echo ""

echo "Demarrage de Kafka et Zookeeper..."
docker-compose up -d

echo "Attente du demarrage complet (30 secondes)..."
sleep 30

echo "Creation des topics Kafka..."
docker exec -it kafka kafka-topics.sh --create --if-not-exists --topic bot-traffic --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
docker exec -it kafka kafka-topics.sh --create --if-not-exists --topic bot-detections --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1

echo ""
echo "Compilation du projet..."
mvn clean package -DskipTests

echo ""
echo "Lancement de BotSense-Stream..."
java -jar target/botsense-stream-1.0.0.jar
