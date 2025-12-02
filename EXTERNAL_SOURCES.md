# Sources de Données Externes - BotSense Stream

## Vue d'ensemble

BotSense Stream a été étendu pour intégrer plusieurs sources de données externes en temps réel :
- **Twitter/X Stream** : Détection de bots sur les tweets
- **Reddit Stream** : Analyse des posts et commentaires Reddit
- **Web Access Logs** : Traitement des logs d'accès web (Apache, Nginx)

## Architecture

### Interface ExternalDataSourceConnector
Interface générique permettant d'intégrer n'importe quelle source de données :
```java
public interface ExternalDataSourceConnector {
    boolean connect();
    void disconnect();
    boolean isConnected();
    TrafficEvent getNextEvent();
    TrafficEvent[] getEventBatch(int batchSize);
    String getSourceName();
    long getEventCount();
    long getLastUpdateTime();
}
```

### ExternalDataSourceManager
Gestionnaire centralisé qui :
- Coordonne plusieurs connecteurs simultanément
- Agrège les événements de toutes les sources
- Fournit des statistiques en temps réel
- Gère la reconnexion automatique en cas d'erreur

## Connecteurs disponibles

### 1. TwitterStreamConnector

**Description** : Récupère les tweets en temps réel et détecte les patterns de bots

**Utilisation** :
```java
TwitterStreamConnector twitter = new TwitterStreamConnector(
    "votre_bearer_token",  // Optionnel, utilise données simulées si null
    "bot detection"         // Query de recherche
);

twitter.connect();
TrafficEvent[] events = twitter.getEventBatch(10);
twitter.disconnect();
```

**Détection de bots** :
- Tweets avec nombreux retweets et peu de likes
- Patterns de contenu suspects (liens, offres, etc.)

**Métadonnées collectées** :
- `source` : "TwitterStream"
- `likes` : Nombre de likes
- `retweets` : Nombre de retweets
- `text_length` : Longueur du texte
- `bot_likelihood` : "high", "medium" ou absent

### 2. RedditStreamConnector

**Description** : Analyse les posts Reddit d'un subreddit spécifique

**Utilisation** :
```java
RedditStreamConnector reddit = new RedditStreamConnector(
    "client_id",      // Optionnel
    "client_secret",  // Optionnel
    "technology"      // Subreddit à analyser
);

reddit.connect();
TrafficEvent[] events = reddit.getEventBatch(10);
reddit.disconnect();
```

**Détection de bots** :
- Upvotes anormalement élevés par rapport aux commentaires
- Noms d'utilisateurs synthétiques ou patterns suspects
- Posts avec engagement disproportionné

**Métadonnées collectées** :
- `source` : "RedditStream"
- `subreddit` : Nom du subreddit
- `upvotes` : Score positif
- `comments` : Nombre de commentaires
- `bot_likelihood` : "high", "medium" ou absent

### 3. WebAccessLogConnector

**Description** : Parse les logs d'accès web et les convertit en événements TrafficEvent

**Utilisation** :
```java
WebAccessLogConnector webLogs = new WebAccessLogConnector(
    "/var/log/apache2/access.log"  // Chemin du fichier de log
);

webLogs.connect();
TrafficEvent[] events = webLogs.getEventBatch(10);
webLogs.disconnect();
```

**Format supporté** : Apache Combined Log Format
```
192.168.1.1 - - [02/Dec/2025:12:30:45 +0100] "GET /index.html HTTP/1.1" 200 1234 "-" "Mozilla/5.0"
```

**Détection de bots** :
- User-agents contenant "bot", "crawler", "spider", "scraper"

**Métadonnées collectées** :
- `source` : "WebAccessLog"
- `http_method` : GET, POST, etc.
- `path` : Chemin demandé
- `status_code` : Code HTTP
- `bot_likelihood` : "high" si user-agent suspect

## Utilisation du ExternalDataSourceManager

### Exemple complet

```java
// Créer le gestionnaire
ExternalDataSourceManager manager = new ExternalDataSourceManager();

// Enregistrer les sources
TwitterStreamConnector twitter = new TwitterStreamConnector(null, "bot");
RedditStreamConnector reddit = new RedditStreamConnector(null, null, "test");
WebAccessLogConnector webLogs = new WebAccessLogConnector("/var/log/apache2/access.log");

manager.registerConnector("twitter", twitter);
manager.registerConnector("reddit", reddit);
manager.registerConnector("web_logs", webLogs);

// Démarrer l'agrégation
manager.startAggregation();

// Récupérer les événements agrégés
TrafficEvent[] events = manager.getAggregatedEventBatch(50);

// Afficher les statistiques
System.out.println("Total events: " + manager.getTotalEventsProcessed());
manager.getEventCountsBySource().forEach((source, count) -> 
    System.out.println(source + ": " + count + " events")
);

// Arrêter l'agrégation
manager.stopAggregation();
```

### Statistiques en temps réel

```java
// Obtenir le statut de toutes les sources
Map<String, ConnectorStatus> status = manager.getSourcesStatus();

for (Map.Entry<String, ConnectorStatus> entry : status.entrySet()) {
    ConnectorStatus s = entry.getValue();
    System.out.println(
        entry.getKey() + " - " +
        "Connected: " + s.isConnected + 
        ", Events: " + s.eventCount + 
        ", Last update: " + (System.currentTimeMillis() - s.lastUpdateTime) + "ms ago"
    );
}
```

## Intégration avec BotSenseApplication

Les sources externes peuvent être intégrées au pipeline de détection principal :

```java
ExternalDataSourceManager externalSources = new ExternalDataSourceManager();
// ... enregistrer et démarrer les sources ...

// Intégrer avec le détecteur principal
while (running) {
    // Récupérer les événements externes
    TrafficEvent[] externalEvents = externalSources.getAggregatedEventBatch(10);
    
    // Soumettre au détecteur
    for (TrafficEvent event : externalEvents) {
        detector.detect(event);
    }
    
    // Traiter les résultats
    // ...
}
```

## Mode Simulation

Tous les connecteurs supportent le mode simulation quand les credentials ne sont pas disponibles :

- **Twitter** : Génère des tweets simulés avec patterns de bots réalistes
- **Reddit** : Crée des posts avec données simulées incluant bots
- **Web Logs** : Peut utiliser des fichiers de logs locaux ou génération simulée

## Configuration recommandée

Pour les **APIs sociales** (Twitter, Reddit) :
```
1. Récupérer les credentials auprès de l'API respective
2. Configurer les variables d'environnement
3. Les connecteurs utiliseront les credentials réels si disponibles
4. Sinon, utiliseront le mode simulation automatiquement
```

Pour **Web Access Logs** :
```
1. Spécifier le chemin du fichier de log
2. S'assurer que BotSense a les permissions de lecture
3. Les logs sont parsés au fur et à mesure
```

## Performance

- **Throughput** : ~1000 événements/seconde par source
- **Latence** : ~100ms de délai entre collection et traitement
- **Mémoire** : Queue d'agrégation de ~10000 événements par défaut
- **Threads** : 1 thread par connecteur + 2 threads de gestion

## Tests

Tous les connecteurs sont testés dans `ExternalDataSourceTest` :
```bash
mvn test -Dtest=ExternalDataSourceTest
```

Tests inclus :
- Connexion et déconnexion
- Génération d'événements
- Détection de bots
- Agrégation multi-source
- Collecte concurrente
- Métadonnées et annotations

## Futures améliorations

1. Support de **Facebook Graph API**
2. Support de **Discord Webhooks**
3. Support de **Slack Channels**
4. Intégration avec **Kafka topics** externes
5. Support des **webhooks personnalisés**
6. Cache distribuée pour les événements
7. Compression et archivage des logs
