package com.botsense.stream.external;

import com.botsense.stream.core.TrafficEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Instant;
import java.util.*;

/**
 * Connecteur pour les logs d'accès web (Apache, Nginx, etc.)
 * Parse les logs et les convertit en événements TrafficEvent
 */
public class WebAccessLogConnector implements ExternalDataSourceConnector {
    private static final Logger logger = LoggerFactory.getLogger(WebAccessLogConnector.class);
    
    private String logFilePath;
    private RandomAccessFile logFile;
    private long lastPosition = 0;
    private long eventCount = 0;
    private long lastUpdateTime = 0;
    private boolean connected = false;
    private Queue<TrafficEvent> eventQueue;
    
    public WebAccessLogConnector(String logFilePath) {
        this.logFilePath = logFilePath;
        this.eventQueue = new LinkedList<>();
    }
    
    @Override
    public boolean connect() {
        try {
            File file = new File(logFilePath);
            if (!file.exists()) {
                logger.warn("Log file not found: {}", logFilePath);
                return false;
            }
            
            logFile = new RandomAccessFile(file, "r");
            logFile.seek(lastPosition);
            connected = true;
            logger.info("Connected to web access log: {}", logFilePath);
            return true;
        } catch (IOException e) {
            logger.error("Failed to connect to log file: {}", logFilePath, e);
            return false;
        }
    }
    
    @Override
    public void disconnect() {
        try {
            if (logFile != null) {
                logFile.close();
            }
            connected = false;
            logger.info("Disconnected from web access log");
        } catch (IOException e) {
            logger.error("Error disconnecting from log file", e);
        }
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public TrafficEvent getNextEvent() {
        if (!connected) return null;
        
        if (eventQueue.isEmpty()) {
            readLogLines();
        }
        
        TrafficEvent event = eventQueue.poll();
        if (event != null) {
            lastUpdateTime = System.currentTimeMillis();
        }
        return event;
    }
    
    @Override
    public TrafficEvent[] getEventBatch(int batchSize) {
        List<TrafficEvent> events = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            TrafficEvent event = getNextEvent();
            if (event == null) break;
            events.add(event);
        }
        return events.toArray(new TrafficEvent[0]);
    }
    
    /**
     * Parse les lignes de log et crée des TrafficEvent
     * Format supporté: Apache Combined Log Format
     */
    private void readLogLines() {
        try {
            String line;
            while ((line = logFile.readLine()) != null && eventQueue.size() < 1000) {
                TrafficEvent event = parseLogLine(line);
                if (event != null) {
                    eventQueue.add(event);
                    eventCount++;
                }
            }
            lastPosition = logFile.getFilePointer();
        } catch (IOException e) {
            logger.error("Error reading log file", e);
        }
    }
    
    /**
     * Parse une ligne de log Apache et crée un TrafficEvent
     * Format: IP - - [timestamp] "METHOD /path HTTP/1.1" status bytes "referer" "user-agent"
     */
    private TrafficEvent parseLogLine(String logLine) {
        try {
            // Exemple: 192.168.1.1 - - [02/Dec/2025:12:30:45 +0100] "GET /index.html HTTP/1.1" 200 1234 "-" "Mozilla/5.0"
            String[] parts = logLine.split(" ");
            if (parts.length < 9) return null;
            
            String ip = parts[0];
            String userAgent = logLine.contains("Mozilla") ? "Mozilla/5.0" : "Unknown";
            String sessionId = ip + "_" + System.currentTimeMillis();
            
            TrafficEvent event = new TrafficEvent(
                sessionId,
                ip,
                userAgent
            );
            event.setTimestamp(Instant.now().toEpochMilli());
            
            // Ajouter les métadonnées
            event.addMetadata("source", "WebAccessLog");
            event.addMetadata("http_method", parts[5].replaceFirst("\"", ""));
            event.addMetadata("path", parts[6]);
            event.addMetadata("status_code", parts[8]);
            
            // Déterminer si c'est potentiellement un bot basé sur le user-agent
            if (logLine.contains("bot") || logLine.contains("crawler") || 
                logLine.contains("spider") || logLine.contains("scraper")) {
                event.addMetadata("bot_likelihood", "high");
            }
            
            return event;
        } catch (Exception e) {
            logger.debug("Failed to parse log line: {}", logLine, e);
            return null;
        }
    }
    
    @Override
    public String getSourceName() {
        return "WebAccessLog";
    }
    
    @Override
    public long getEventCount() {
        return eventCount;
    }
    
    @Override
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
}
