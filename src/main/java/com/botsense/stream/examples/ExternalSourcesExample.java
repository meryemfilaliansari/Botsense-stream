package com.botsense.stream.examples;

import com.botsense.stream.external.*;
import com.botsense.stream.core.TrafficEvent;

/**
 * Exemple d'utilisation des sources de données externes
 */
public class ExternalSourcesExample {
    
    public static void main(String[] args) throws Exception {
        // Exemple 1: Utiliser Twitter Stream
        exampleTwitterStream();
        
        // Exemple 2: Utiliser Reddit Stream
        exampleRedditStream();
        
        // Exemple 3: Utiliser Web Access Logs
        exampleWebAccessLogs();
        
        // Exemple 4: Agrégation multi-source
        exampleMultiSourceAggregation();
    }
    
    /**
     * Exemple 1: Connecteur Twitter
     */
    private static void exampleTwitterStream() throws InterruptedException {
        System.out.println("\n=== Example 1: Twitter Stream ===");
        
        // Créer le connecteur (avec ou sans token)
        TwitterStreamConnector twitter = new TwitterStreamConnector(
            null,  // Pas de token = mode simulation
            "bot detection AI ML"
        );
        
        // Connecter
        if (twitter.connect()) {
            System.out.println("Connected to Twitter: " + twitter.getSourceName());
            
            // Attendre un peu que les données soient générées
            Thread.sleep(100);
            
            // Récupérer les événements
            TrafficEvent[] events = twitter.getEventBatch(5);
            System.out.println("Fetched " + events.length + " tweets");
            
            for (TrafficEvent event : events) {
                System.out.println("  - User: " + event.getSessionId() + 
                                 ", Bot likelihood: " + event.getMetadata().get("bot_likelihood"));
            }
            
            twitter.disconnect();
            System.out.println("Disconnected from Twitter");
        }
    }
    
    /**
     * Exemple 2: Connecteur Reddit
     */
    private static void exampleRedditStream() throws InterruptedException {
        System.out.println("\n=== Example 2: Reddit Stream ===");
        
        // Créer le connecteur Reddit
        RedditStreamConnector reddit = new RedditStreamConnector(
            null,
            null,
            "technology"  // Subreddit
        );
        
        // Connecter
        if (reddit.connect()) {
            System.out.println("Connected to Reddit: r/technology");
            
            // Attendre un peu
            Thread.sleep(100);
            
            // Récupérer les événements
            TrafficEvent[] events = reddit.getEventBatch(3);
            System.out.println("Fetched " + events.length + " Reddit posts");
            
            for (TrafficEvent event : events) {
                String upvotes = (String) event.getMetadata().get("upvotes");
                String botLikelihood = (String) event.getMetadata().get("bot_likelihood");
                System.out.println("  - Author: " + event.getSessionId() + 
                                 ", Upvotes: " + upvotes +
                                 ", Bot: " + botLikelihood);
            }
            
            reddit.disconnect();
        }
    }
    
    /**
     * Exemple 3: Connecteur Web Access Logs
     */
    private static void exampleWebAccessLogs() {
        System.out.println("\n=== Example 3: Web Access Logs ===");
        
        // Créer le connecteur avec un fichier de log
        WebAccessLogConnector webLogs = new WebAccessLogConnector(
            "/var/log/apache2/access.log"  // Adapter le chemin
        );
        
        // Connecter
        if (webLogs.connect()) {
            System.out.println("Connected to Web Logs");
            
            // Récupérer les événements
            TrafficEvent[] events = webLogs.getEventBatch(5);
            System.out.println("Fetched " + events.length + " log entries");
            
            for (TrafficEvent event : events) {
                String method = (String) event.getMetadata().get("http_method");
                String path = (String) event.getMetadata().get("path");
                String botLikelihood = (String) event.getMetadata().get("bot_likelihood");
                System.out.println("  - IP: " + event.getIpAddress() + 
                                 ", " + method + " " + path +
                                 ", Bot: " + botLikelihood);
            }
            
            webLogs.disconnect();
        }
    }
    
    /**
     * Exemple 4: Agrégation multi-source
     */
    private static void exampleMultiSourceAggregation() throws InterruptedException {
        System.out.println("\n=== Example 4: Multi-Source Aggregation ===");
        
        // Créer le gestionnaire
        ExternalDataSourceManager manager = new ExternalDataSourceManager();
        
        // Enregistrer les sources
        TwitterStreamConnector twitter = new TwitterStreamConnector(null, "bot");
        RedditStreamConnector reddit = new RedditStreamConnector(null, null, "test");
        
        manager.registerConnector("twitter", twitter);
        manager.registerConnector("reddit", reddit);
        
        System.out.println("Registered 2 data sources");
        
        // Démarrer l'agrégation
        manager.startAggregation();
        System.out.println("Starting aggregation...");
        
        // Laisser tourner pendant 2 secondes
        Thread.sleep(2000);
        
        // Afficher les statistiques
        System.out.println("\n--- Statistics ---");
        System.out.println("Total events processed: " + manager.getTotalEventsProcessed());
        
        // Statistiques par source
        manager.getEventCountsBySource().forEach((source, count) -> 
            System.out.println("  " + source + ": " + count + " events")
        );
        
        // Statut des sources
        System.out.println("\n--- Sources Status ---");
        manager.getSourcesStatus().forEach((name, status) -> 
            System.out.println("  " + name + ": " + (status.isConnected ? "CONNECTED" : "DISCONNECTED") + 
                             " (" + status.eventCount + " events)")
        );
        
        // Récupérer les événements agrégés
        System.out.println("\n--- Aggregated Events ---");
        TrafficEvent[] events = manager.getAggregatedEventBatch(5);
        System.out.println("Fetched " + events.length + " aggregated events");
        
        for (TrafficEvent event : events) {
            String source = (String) event.getMetadata().get("source");
            String botLikelihood = (String) event.getMetadata().get("bot_likelihood");
            System.out.println("  - From " + source + ": User " + event.getSessionId() + 
                             " (Bot: " + botLikelihood + ")");
        }
        
        // Arrêter l'agrégation
        manager.stopAggregation();
        System.out.println("\nAggregation stopped");
    }
}
