package com.botsense.stream.core;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Représente un événement de trafic réseau
 * Contient les caractéristiques pour la détection de bots
 */
public class TrafficEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String sessionId;
    private long timestamp;
    private String ipAddress;
    private String userAgent;
    
    // Caractéristiques comportementales
    private int requestsPerSecond;
    private double avgResponseTime;
    private int uniqueEndpoints;
    private double clickRate;
    private double scrollDepth;
    private int sessionDuration;
    private double pageViewsPerSession;
    private int errorRate;
    private double timeBetweenRequests;
    private int distinctHeaders;
    
    // Caractéristiques techniques
    private boolean javascriptEnabled;
    private boolean cookiesEnabled;
    private int screenResolution;
    private String httpVersion;
    
    // Label (pour l'apprentissage supervisé)
    private boolean isBot;
    private double confidence;
    
    // Métadonnées
    private Map<String, Object> metadata;
    
    public TrafficEvent() {
        this.metadata = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public TrafficEvent(String sessionId, String ipAddress, String userAgent) {
        this();
        this.sessionId = sessionId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }
    
    // Constructeur complet
    public TrafficEvent(String sessionId, long timestamp, String ipAddress, 
                       String userAgent, int requestsPerSecond, double avgResponseTime,
                       int uniqueEndpoints, double clickRate, double scrollDepth,
                       int sessionDuration, double pageViewsPerSession, int errorRate,
                       double timeBetweenRequests, int distinctHeaders,
                       boolean javascriptEnabled, boolean cookiesEnabled,
                       int screenResolution, String httpVersion, boolean isBot) {
        this();
        this.sessionId = sessionId;
        this.timestamp = timestamp;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.requestsPerSecond = requestsPerSecond;
        this.avgResponseTime = avgResponseTime;
        this.uniqueEndpoints = uniqueEndpoints;
        this.clickRate = clickRate;
        this.scrollDepth = scrollDepth;
        this.sessionDuration = sessionDuration;
        this.pageViewsPerSession = pageViewsPerSession;
        this.errorRate = errorRate;
        this.timeBetweenRequests = timeBetweenRequests;
        this.distinctHeaders = distinctHeaders;
        this.javascriptEnabled = javascriptEnabled;
        this.cookiesEnabled = cookiesEnabled;
        this.screenResolution = screenResolution;
        this.httpVersion = httpVersion;
        this.isBot = isBot;
    }
    
    /**
     * Convertit l'événement en vecteur de features pour le modèle
     */
    public double[] toFeatureVector() {
        return new double[] {
            requestsPerSecond,
            avgResponseTime,
            uniqueEndpoints,
            clickRate,
            scrollDepth,
            sessionDuration,
            pageViewsPerSession,
            errorRate,
            timeBetweenRequests,
            distinctHeaders,
            javascriptEnabled ? 1.0 : 0.0,
            cookiesEnabled ? 1.0 : 0.0,
            screenResolution,
            httpVersion.equals("HTTP/2") ? 1.0 : 0.0
        };
    }
    
    /**
     * Retourne le nombre de features
     */
    public static int getFeatureCount() {
        return 14;
    }
    
    /**
     * Retourne les noms des features
     */
    public static String[] getFeatureNames() {
        return new String[] {
            "requests_per_second",
            "avg_response_time",
            "unique_endpoints",
            "click_rate",
            "scroll_depth",
            "session_duration",
            "page_views_per_session",
            "error_rate",
            "time_between_requests",
            "distinct_headers",
            "javascript_enabled",
            "cookies_enabled",
            "screen_resolution",
            "http_version"
        };
    }
    
    // Getters et Setters
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    
    public int getRequestsPerSecond() { return requestsPerSecond; }
    public void setRequestsPerSecond(int requestsPerSecond) { 
        this.requestsPerSecond = requestsPerSecond; 
    }
    
    public double getAvgResponseTime() { return avgResponseTime; }
    public void setAvgResponseTime(double avgResponseTime) { 
        this.avgResponseTime = avgResponseTime; 
    }
    
    public int getUniqueEndpoints() { return uniqueEndpoints; }
    public void setUniqueEndpoints(int uniqueEndpoints) { 
        this.uniqueEndpoints = uniqueEndpoints; 
    }
    
    public double getClickRate() { return clickRate; }
    public void setClickRate(double clickRate) { this.clickRate = clickRate; }
    
    public double getScrollDepth() { return scrollDepth; }
    public void setScrollDepth(double scrollDepth) { this.scrollDepth = scrollDepth; }
    
    public int getSessionDuration() { return sessionDuration; }
    public void setSessionDuration(int sessionDuration) { 
        this.sessionDuration = sessionDuration; 
    }
    
    public double getPageViewsPerSession() { return pageViewsPerSession; }
    public void setPageViewsPerSession(double pageViewsPerSession) { 
        this.pageViewsPerSession = pageViewsPerSession; 
    }
    
    public int getErrorRate() { return errorRate; }
    public void setErrorRate(int errorRate) { this.errorRate = errorRate; }
    
    public double getTimeBetweenRequests() { return timeBetweenRequests; }
    public void setTimeBetweenRequests(double timeBetweenRequests) { 
        this.timeBetweenRequests = timeBetweenRequests; 
    }
    
    public int getDistinctHeaders() { return distinctHeaders; }
    public void setDistinctHeaders(int distinctHeaders) { 
        this.distinctHeaders = distinctHeaders; 
    }
    
    public boolean isJavascriptEnabled() { return javascriptEnabled; }
    public void setJavascriptEnabled(boolean javascriptEnabled) { 
        this.javascriptEnabled = javascriptEnabled; 
    }
    
    public boolean isCookiesEnabled() { return cookiesEnabled; }
    public void setCookiesEnabled(boolean cookiesEnabled) { 
        this.cookiesEnabled = cookiesEnabled; 
    }
    
    public int getScreenResolution() { return screenResolution; }
    public void setScreenResolution(int screenResolution) { 
        this.screenResolution = screenResolution; 
    }
    
    public String getHttpVersion() { return httpVersion; }
    public void setHttpVersion(String httpVersion) { this.httpVersion = httpVersion; }
    
    public boolean isBot() { return isBot; }
    public void setBot(boolean isBot) { this.isBot = isBot; }
    
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    
    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
    
    @Override
    public String toString() {
        return String.format(
            "TrafficEvent{session=%s, ip=%s, rps=%d, isBot=%b, confidence=%.2f}",
            sessionId, ipAddress, requestsPerSecond, isBot, confidence
        );
    }
}