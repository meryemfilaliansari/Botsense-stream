package com.botsense.stream.external;

import com.botsense.stream.core.TrafficEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Gestionnaire centralisé des sources de données externes
 * Coordonne plusieurs connecteurs et fournit une interface unifiée
 */
public class ExternalDataSourceManager {
    private static final Logger logger = LoggerFactory.getLogger(ExternalDataSourceManager.class);
    
    private Map<String, ExternalDataSourceConnector> connectors;
    private Queue<TrafficEvent> aggregatedEventQueue;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutor;
    private volatile boolean running = false;
    private long totalEventsProcessed = 0;
    private Map<String, Long> sourceEventCounts;
    
    public ExternalDataSourceManager() {
        this.connectors = new ConcurrentHashMap<>();
        this.aggregatedEventQueue = new ConcurrentLinkedQueue<>();
        this.executorService = Executors.newFixedThreadPool(5);
        this.scheduledExecutor = Executors.newScheduledThreadPool(2);
        this.sourceEventCounts = new ConcurrentHashMap<>();
    }
    
    /**
     * Enregistre une nouvelle source de données
     */
    public void registerConnector(String name, ExternalDataSourceConnector connector) {
        connectors.put(name, connector);
        sourceEventCounts.put(name, 0L);
        logger.info("Registered external data source connector: {}", name);
    }
    
    /**
     * Démarre l'agrégation des données de toutes les sources
     */
    public void startAggregation() {
        if (running) {
            logger.warn("Aggregation already running");
            return;
        }
        
        running = true;
        logger.info("Starting external data source aggregation with {} sources", connectors.size());
        
        // Connecte toutes les sources
        for (Map.Entry<String, ExternalDataSourceConnector> entry : connectors.entrySet()) {
            String name = entry.getKey();
            ExternalDataSourceConnector connector = entry.getValue();
            
            if (connector.connect()) {
                // Lance un thread pour chaque connecteur
                executorService.submit(() -> pollSourceData(name, connector));
            } else {
                logger.warn("Failed to connect to source: {}", name);
            }
        }
        
        // Lance un task périodique pour afficher les statistiques
        scheduledExecutor.scheduleAtFixedRate(this::logStatistics, 10, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Arrête l'agrégation des données
     */
    public void stopAggregation() {
        running = false;
        logger.info("Stopping external data source aggregation");
        
        for (ExternalDataSourceConnector connector : connectors.values()) {
            try {
                connector.disconnect();
            } catch (Exception e) {
                logger.error("Error disconnecting source", e);
            }
        }
        
        executorService.shutdown();
        scheduledExecutor.shutdown();
        
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            if (!scheduledExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Interroge une source de données et agrège les événements
     */
    private void pollSourceData(String sourceName, ExternalDataSourceConnector connector) {
        int errorCount = 0;
        final int MAX_ERRORS = 5;
        
        while (running && errorCount < MAX_ERRORS) {
            try {
                if (!connector.isConnected()) {
                    logger.warn("Source {} disconnected, attempting to reconnect", sourceName);
                    if (connector.connect()) {
                        errorCount = 0;
                    } else {
                        errorCount++;
                    }
                    continue;
                }
                
                // Récupère un batch d'événements
                TrafficEvent[] events = connector.getEventBatch(10);
                if (events.length > 0) {
                    for (TrafficEvent event : events) {
                        aggregatedEventQueue.add(event);
                        totalEventsProcessed++;
                        sourceEventCounts.merge(sourceName, 1L, Long::sum);
                    }
                    errorCount = 0;  // Réinitialise le compteur d'erreurs
                }
                
                // Petit délai pour éviter une charge excessive
                Thread.sleep(100);
            } catch (Exception e) {
                logger.error("Error polling source {}: {}", sourceName, e.getMessage());
                errorCount++;
            }
        }
        
        logger.warn("Stopping polling for source {} after {} errors", sourceName, errorCount);
    }
    
    /**
     * Récupère le prochain événement agrégé de toutes les sources
     */
    public TrafficEvent getNextAggregatedEvent() {
        return aggregatedEventQueue.poll();
    }
    
    /**
     * Récupère un batch d'événements agrégés
     */
    public TrafficEvent[] getAggregatedEventBatch(int batchSize) {
        List<TrafficEvent> events = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            TrafficEvent event = aggregatedEventQueue.poll();
            if (event == null) break;
            events.add(event);
        }
        return events.toArray(new TrafficEvent[0]);
    }
    
    /**
     * Obtient le nombre total d'événements traités de toutes les sources
     */
    public long getTotalEventsProcessed() {
        return totalEventsProcessed;
    }
    
    /**
     * Obtient le nombre d'événements par source
     */
    public Map<String, Long> getEventCountsBySource() {
        return new HashMap<>(sourceEventCounts);
    }
    
    /**
     * Obtient le statut de toutes les sources
     */
    public Map<String, ConnectorStatus> getSourcesStatus() {
        Map<String, ConnectorStatus> status = new HashMap<>();
        
        for (Map.Entry<String, ExternalDataSourceConnector> entry : connectors.entrySet()) {
            ExternalDataSourceConnector connector = entry.getValue();
            status.put(entry.getKey(), new ConnectorStatus(
                connector.getSourceName(),
                connector.isConnected(),
                connector.getEventCount(),
                connector.getLastUpdateTime()
            ));
        }
        
        return status;
    }
    
    /**
     * Affiche les statistiques des sources
     */
    private void logStatistics() {
        if (!running) return;
        
        logger.info("=== External Data Sources Statistics ===");
        logger.info("Total events aggregated: {}", totalEventsProcessed);
        logger.info("Events in queue: {}", aggregatedEventQueue.size());
        
        for (Map.Entry<String, ConnectorStatus> entry : getSourcesStatus().entrySet()) {
            ConnectorStatus status = entry.getValue();
            logger.info("  {} - Connected: {}, Events: {}, Last update: {}ms ago",
                entry.getKey(),
                status.isConnected,
                status.eventCount,
                System.currentTimeMillis() - status.lastUpdateTime
            );
        }
    }
    
    /**
     * Vérifie si au moins une source est connectée
     */
    public boolean isAnySourceConnected() {
        return connectors.values().stream().anyMatch(ExternalDataSourceConnector::isConnected);
    }
    
    /**
     * Classe interne pour représenter le statut d'un connecteur
     */
    public static class ConnectorStatus {
        public String sourceName;
        public boolean isConnected;
        public long eventCount;
        public long lastUpdateTime;
        
        public ConnectorStatus(String sourceName, boolean isConnected, 
                             long eventCount, long lastUpdateTime) {
            this.sourceName = sourceName;
            this.isConnected = isConnected;
            this.eventCount = eventCount;
            this.lastUpdateTime = lastUpdateTime;
        }
    }
}
