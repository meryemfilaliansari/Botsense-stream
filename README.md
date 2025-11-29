# BotSense-Stream

Système de détection adaptative de bots en temps réel utilisant l'apprentissage en flux.

## Description

BotSense-Stream implémente des algorithmes de stream learning (Hoeffding Trees, Online Bagging) pour détecter les comportements de bots dans des flux de données en temps réel. Le système s'adapte automatiquement aux évolutions comportementales et aux dérives de données.

## Architecture

- **Générateur de flux**: Simulation de trafic réseau
- **Apache Kafka**: Ingestion de données en temps réel
- **Apache Spark Streaming**: Traitement distribué
- **Modèles adaptatifs**: Hoeffding Trees avec Online Bagging
- **Détection de dérive**: ADWIN pour adaptation continue
- **Monitoring**: Dashboard temps réel

## Prérequis

- Java 11 ou supérieur
- Apache Maven 3.6+
- Apache Kafka 3.6+
- Apache Spark 3.5+
- 8GB RAM minimum

## Installation

### 1. Cloner et construire le projet

```bash
mvn clean install
```

### 2. Démarrer Kafka et Zookeeper

```bash
# Démarrer Zookeeper
docker-compose up -d zookeeper

# Démarrer Kafka
docker-compose up -d kafka

# Créer les topics
docker exec -it kafka kafka-topics.sh --create --topic bot-traffic --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
docker exec -it kafka kafka-topics.sh --create --topic bot-detections --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
```

### 3. Lancer l'application

```bash
mvn exec:java -Dexec.mainClass="com.botsense.stream.BotSenseApplication"
```

## Configuration

Modifier `src/main/resources/application.properties` pour ajuster:

- Paramètres Kafka (brokers, topics)
- Configuration Spark (batch interval, checkpointing)
- Paramètres du modèle (grace period, confidence)
- Seuils de détection
- Détection de dérive

## Utilisation

### Mode Production

```bash
java -jar target/botsense-stream-1.0.0.jar --mode production
```

### Mode Test

```bash
java -jar target/botsense-stream-1.0.0.jar --mode test --duration 3600
```

### Monitoring

Accéder au dashboard: http://localhost:8080

## Algorithmes Implémentés

### Hoeffding Tree
- Arbre de décision incrémental
- Apprentissage avec garanties statistiques
- Adaptation aux nouvelles données

### Online Bagging
- Ensemble de modèles
- Vote majoritaire
- Robustesse accrue

### ADWIN (Adaptive Windowing)
- Détection de dérive de concept
- Fenêtre adaptative
- Réapprentissage automatique

## Structure du Projet

```
botsense-stream/
├── src/main/java/com/botsense/stream/
│   ├── core/                 # Modèles et algorithmes
│   ├── streaming/            # Kafka et Spark
│   ├── generator/            # Générateur de données
│   ├── monitoring/           # Dashboard et métriques
│   └── config/               # Configuration
├── src/main/resources/       # Fichiers de configuration
├── config/                   # Configuration externe
├── data/                     # Données de test
├── models/                   # Modèles sauvegardés
└── docs/                     # Documentation

```

## Métriques de Performance

Le système collecte et affiche:

- Précision, Rappel, F1-Score
- Taux de détection de bots
- Latence de traitement
- Nombre de dérives détectées
- Évolution de l'accuracy

## Tests

```bash
mvn test
```

## Publication et Recherche

Ce projet implémente des techniques avancées de:
- Stream learning adaptatif
- Détection de dérive de concept
- Cybersurveillance auto-évolutive

Idéal pour publications sur:
- Systèmes de détection temps réel
- Apprentissage machine adaptatif
- Sécurité réseau intelligente

## Licence

MIT License

## Auteurs

Projet académique - BotSense-Stream Team

## Contact

Pour questions et contributions: [votre-email]
