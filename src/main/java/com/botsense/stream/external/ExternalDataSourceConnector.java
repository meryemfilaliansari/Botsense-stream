package com.botsense.stream.external;

import com.botsense.stream.core.TrafficEvent;

/**
 * Interface générique pour les connecteurs de sources de données externes
 * Permet d'intégrer plusieurs APIs sociales et sources de logs
 */
public interface ExternalDataSourceConnector {
    
    /**
     * Initialise la connexion à la source de données
     * @return true si l'initialisation est réussie
     */
    boolean connect();
    
    /**
     * Arrête la connexion à la source de données
     */
    void disconnect();
    
    /**
     * Vérifie si le connecteur est actuellement connecté
     * @return true si connecté
     */
    boolean isConnected();
    
    /**
     * Récupère le prochain événement de la source de données
     * @return TrafficEvent ou null si aucun événement disponible
     */
    TrafficEvent getNextEvent();
    
    /**
     * Récupère une liste d'événements en batch
     * @param batchSize nombre d'événements à récupérer
     * @return tableau d'événements TrafficEvent
     */
    TrafficEvent[] getEventBatch(int batchSize);
    
    /**
     * Obtient le nom du connecteur
     * @return nom de la source de données
     */
    String getSourceName();
    
    /**
     * Obtient le nombre total d'événements récupérés
     * @return nombre d'événements
     */
    long getEventCount();
    
    /**
     * Obtient la dernière heure de mise à jour
     * @return timestamp en millisecondes
     */
    long getLastUpdateTime();
}
