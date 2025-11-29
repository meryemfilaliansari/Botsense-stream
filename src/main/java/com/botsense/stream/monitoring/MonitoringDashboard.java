package com.botsense.stream.monitoring;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.botsense.stream.core.detector.BotDetector;
import com.botsense.stream.streaming.kafka.TrafficKafkaProducer;
import com.botsense.stream.streaming.spark.BotDetectionStreamProcessor;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;

/**
 * Dashboard de monitoring en temps réel
 * Interface web pour visualiser les métriques du système
 */
public class MonitoringDashboard {
    private static final Logger logger = LoggerFactory.getLogger(MonitoringDashboard.class);
    
    private HttpServer server;
    private ScheduledExecutorService scheduler;
    private Gson gson;
    
    private int port;
    private int metricsInterval;
    
    private BotDetector detector;
    private TrafficKafkaProducer producer;
    private BotDetectionStreamProcessor processor;
    
    private MetricsCollector metricsCollector;
    
    public MonitoringDashboard(int port, int metricsInterval,
                              BotDetector detector,
                              TrafficKafkaProducer producer,
                              BotDetectionStreamProcessor processor) {
        this.port = port;
        this.metricsInterval = metricsInterval;
        this.detector = detector;
        this.producer = producer;
        this.processor = processor;
        this.gson = new Gson();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.metricsCollector = new MetricsCollector();
    }
    
    /**
     * Démarre le serveur HTTP
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Routes
        server.createContext("/", this::handleRoot);
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/statistics", this::handleStatistics);
        server.createContext("/api/health", this::handleHealth);
        
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        
        // Démarrer la collecte de métriques
        startMetricsCollection();
        
        logger.info("Monitoring dashboard started on port {}", port);
    }
    
    /**
     * Démarre la collecte périodique de métriques
     */
    private void startMetricsCollection() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                metricsCollector.collect();
            } catch (Exception e) {
                logger.error("Error collecting metrics", e);
            }
        }, 0, metricsInterval, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Handler pour la page principale
     */
    private void handleRoot(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String html = generateDashboardHTML();
        sendResponse(exchange, 200, html, "text/html");
    }
    
    /**
     * Handler pour les métriques
     */
    private void handleMetrics(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Map<String, Object> metrics = metricsCollector.getCurrentMetrics();
        String json = gson.toJson(metrics);
        sendResponse(exchange, 200, json, "application/json");
    }
    
    /**
     * Handler pour les statistiques détaillées
     */
    private void handleStatistics(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Map<String, Object> stats = new HashMap<>();
        
        if (detector != null) {
            stats.put("detector", detector.getDetailedStatistics());
        }
        if (producer != null) {
            stats.put("producer", producer.getStatistics());
        }
        if (processor != null) {
            stats.put("processor", processor.getStatistics());
        }
        
        String json = gson.toJson(stats);
        sendResponse(exchange, 200, json, "application/json");
    }
    
    /**
     * Handler pour la santé du système
     */
    private void handleHealth(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("producer_running", producer != null && producer.isRunning());
        health.put("processor_running", processor != null && processor.isRunning());
        
        String json = gson.toJson(health);
        sendResponse(exchange, 200, json, "application/json");
    }
    
    /**
     * Envoie une réponse HTTP
     */
    private void sendResponse(com.sun.net.httpserver.HttpExchange exchange,
                            int statusCode, String response, String contentType) 
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    /**
     * Génère le HTML du dashboard - VERSION COMPATIBLE Java < 15
     */
    private String generateDashboardHTML() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
            .append("<html>\n")
            .append("<head>\n")
            .append("    <title>BotSense-Stream Dashboard</title>\n")
            .append("    <meta charset=\"UTF-8\">\n")
            .append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("    <style>\n")
            .append("        * { margin: 0; padding: 0; box-sizing: border-box; }\n")
            .append("        body {\n")
            .append("            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;\n")
            .append("            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n")
            .append("            color: #333;\n")
            .append("            min-height: 100vh;\n")
            .append("            padding: 20px;\n")
            .append("        }\n")
            .append("        .container {\n")
            .append("            max-width: 1400px;\n")
            .append("            margin: 0 auto;\n")
            .append("        }\n")
            .append("        .header {\n")
            .append("            background: white;\n")
            .append("            border-radius: 10px;\n")
            .append("            padding: 30px;\n")
            .append("            margin-bottom: 20px;\n")
            .append("            box-shadow: 0 4px 6px rgba(0,0,0,0.1);\n")
            .append("        }\n")
            .append("        .header h1 {\n")
            .append("            color: #667eea;\n")
            .append("            font-size: 2.5em;\n")
            .append("            margin-bottom: 10px;\n")
            .append("        }\n")
            .append("        .header p {\n")
            .append("            color: #666;\n")
            .append("            font-size: 1.1em;\n")
            .append("        }\n")
            .append("        .grid {\n")
            .append("            display: grid;\n")
            .append("            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));\n")
            .append("            gap: 20px;\n")
            .append("            margin-bottom: 20px;\n")
            .append("        }\n")
            .append("        .card {\n")
            .append("            background: white;\n")
            .append("            border-radius: 10px;\n")
            .append("            padding: 25px;\n")
            .append("            box-shadow: 0 4px 6px rgba(0,0,0,0.1);\n")
            .append("        }\n")
            .append("        .card h2 {\n")
            .append("            color: #667eea;\n")
            .append("            margin-bottom: 15px;\n")
            .append("            font-size: 1.3em;\n")
            .append("            border-bottom: 2px solid #667eea;\n")
            .append("            padding-bottom: 10px;\n")
            .append("        }\n")
            .append("        .metric {\n")
            .append("            display: flex;\n")
            .append("            justify-content: space-between;\n")
            .append("            padding: 10px 0;\n")
            .append("            border-bottom: 1px solid #eee;\n")
            .append("        }\n")
            .append("        .metric:last-child {\n")
            .append("            border-bottom: none;\n")
            .append("        }\n")
            .append("        .metric-label {\n")
            .append("            color: #666;\n")
            .append("            font-weight: 500;\n")
            .append("        }\n")
            .append("        .metric-value {\n")
            .append("            color: #333;\n")
            .append("            font-weight: bold;\n")
            .append("            font-size: 1.1em;\n")
            .append("        }\n")
            .append("        .status-indicator {\n")
            .append("            display: inline-block;\n")
            .append("            width: 12px;\n")
            .append("            height: 12px;\n")
            .append("            border-radius: 50%;\n")
            .append("            margin-right: 8px;\n")
            .append("        }\n")
            .append("        .status-active { background: #10b981; }\n")
            .append("        .status-warning { background: #f59e0b; }\n")
            .append("        .status-error { background: #ef4444; }\n")
            .append("        .refresh-info {\n")
            .append("            text-align: center;\n")
            .append("            color: white;\n")
            .append("            padding: 15px;\n")
            .append("            background: rgba(255,255,255,0.1);\n")
            .append("            border-radius: 10px;\n")
            .append("            margin-top: 20px;\n")
            .append("        }\n")
            .append("        .error-message {\n")
            .append("            color: #ef4444;\n")
            .append("            font-size: 0.9em;\n")
            .append("            margin-top: 5px;\n")
            .append("            text-align: center;\n")
            .append("        }\n")
            .append("        @keyframes pulse {\n")
            .append("            0%, 100% { opacity: 1; }\n")
            .append("            50% { opacity: 0.5; }\n")
            .append("        }\n")
            .append("        .updating {\n")
            .append("            animation: pulse 1.5s infinite;\n")
            .append("        }\n")
            .append("    </style>\n")
            .append("</head>\n")
            .append("<body>\n")
            .append("    <div class=\"container\">\n")
            .append("        <div class=\"header\">\n")
            .append("            <h1>BotSense-Stream Dashboard</h1>\n")
            .append("            <p>Système de détection adaptative de bots en temps réel</p>\n")
            .append("        </div>\n")
            .append("        \n")
            .append("        <div class=\"grid\">\n")
            .append("            <div class=\"card\">\n")
            .append("                <h2>État du Système</h2>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">\n")
            .append("                        <span class=\"status-indicator status-active\" id=\"system-status-indicator\"></span>\n")
            .append("                        Statut Global\n")
            .append("                    </span>\n")
            .append("                    <span class=\"metric-value\" id=\"system-status\">Chargement...</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Producteur Kafka</span>\n")
            .append("                    <span class=\"metric-value\" id=\"producer-status\">-</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Processeur Spark</span>\n")
            .append("                    <span class=\"metric-value\" id=\"processor-status\">-</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Détecteur</span>\n")
            .append("                    <span class=\"metric-value\" id=\"detector-status\">-</span>\n")
            .append("                </div>\n")
            .append("            </div>\n")
            .append("            \n")
            .append("            <div class=\"card\">\n")
            .append("                <h2>Métriques de Production</h2>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Messages Envoyés</span>\n")
            .append("                    <span class=\"metric-value\" id=\"messages-sent\">0</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Débit Actuel</span>\n")
            .append("                    <span class=\"metric-value\" id=\"throughput\">0 msg/s</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Ratio de Bots</span>\n")
            .append("                    <span class=\"metric-value\" id=\"bot-ratio\">0%</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Phase Comportement</span>\n")
            .append("                    <span class=\"metric-value\" id=\"behavior-phase\">0</span>\n")
            .append("                </div>\n")
            .append("            </div>\n")
            .append("            \n")
            .append("            <div class=\"card\">\n")
            .append("                <h2>Performance Détection</h2>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Événements Traités</span>\n")
            .append("                    <span class=\"metric-value\" id=\"events-processed\">0</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Accuracy</span>\n")
            .append("                    <span class=\"metric-value\" id=\"accuracy\">0%</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Précision</span>\n")
            .append("                    <span class=\"metric-value\" id=\"precision\">0%</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Rappel</span>\n")
            .append("                    <span class=\"metric-value\" id=\"recall\">0%</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">F1-Score</span>\n")
            .append("                    <span class=\"metric-value\" id=\"f1-score\">0%</span>\n")
            .append("                </div>\n")
            .append("            </div>\n")
            .append("            \n")
            .append("            <div class=\"card\">\n")
            .append("                <h2>Détection de Dérive</h2>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Vérifications Totales</span>\n")
            .append("                    <span class=\"metric-value\" id=\"drift-checks\">0</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Avertissements</span>\n")
            .append("                    <span class=\"metric-value\" id=\"drift-warnings\">0</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Dérives Détectées</span>\n")
            .append("                    <span class=\"metric-value\" id=\"drift-detected\">0</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Taux d'Erreur</span>\n")
            .append("                    <span class=\"metric-value\" id=\"error-rate\">0%</span>\n")
            .append("                </div>\n")
            .append("                <div class=\"metric\">\n")
            .append("                    <span class=\"metric-label\">Zone d'Alerte</span>\n")
            .append("                    <span class=\"metric-value\" id=\"warning-zone\">Non</span>\n")
            .append("                </div>\n")
            .append("            </div>\n")
            .append("        </div>\n")
            .append("        \n")
            .append("        <div class=\"refresh-info updating\" id=\"refresh-info\">\n")
            .append("            Actualisation automatique toutes les 5 secondes\n")
            .append("        </div>\n")
            .append("        <div id=\"error-message\" class=\"error-message\" style=\"display: none;\"></div>\n")
            .append("    </div>\n")
            .append("    \n")
            .append("    <script>\n")
            .append("        // Fonction utilitaire pour accéder aux propriétés en toute sécurité\n")
            .append("        function safeGet(obj, path, defaultValue = '-') {\n")
            .append("            return path.split('.').reduce((o, p) => (o && o[p] !== undefined) ? o[p] : defaultValue, obj);\n")
            .append("        }\n")
            .append("        \n")
            .append("        // Fonction pour formater les nombres\n")
            .append("        function formatNumber(value) {\n")
            .append("            if (typeof value === 'number') {\n")
            .append("                return value.toLocaleString();\n")
            .append("            }\n")
            .append("            return value;\n")
            .append("        }\n")
            .append("        \n")
            .append("        // Fonction pour formater les pourcentages\n")
            .append("        function formatPercent(value) {\n")
            .append("            if (typeof value === 'number') {\n")
            .append("                return (value * 100).toFixed(2) + '%';\n")
            .append("            }\n")
            .append("            return '0%';\n")
            .append("        }\n")
            .append("        \n")
            .append("        async function updateMetrics() {\n")
            .append("            try {\n")
            .append("                const response = await fetch('/api/statistics');\n")
            .append("                if (!response.ok) {\n")
            .append("                    throw new Error(`Erreur HTTP: ${response.status}`);\n")
            .append("                }\n")
            .append("                const data = await response.json();\n")
            .append("                \n")
            .append("                // Mettre à jour l'indicateur de statut\n")
            .append("                document.getElementById('system-status-indicator').className = 'status-indicator status-active';\n")
            .append("                document.getElementById('system-status').textContent = 'En ligne';\n")
            .append("                document.getElementById('error-message').style.display = 'none';\n")
            .append("                \n")
            .append("                // Métriques du producteur\n")
            .append("                document.getElementById('messages-sent').textContent = \n")
            .append("                    formatNumber(safeGet(data, 'producer.messagesSent', 0));\n")
            .append("                document.getElementById('throughput').textContent = \n")
            .append("                    safeGet(data, 'producer.throughput', 0).toFixed(1) + ' msg/s';\n")
            .append("                document.getElementById('bot-ratio').textContent = \n")
            .append("                    formatPercent(safeGet(data, 'producer.generatorStats.botRatio', 0));\n")
            .append("                document.getElementById('behavior-phase').textContent = \n")
            .append("                    safeGet(data, 'producer.generatorStats.behaviorPhase', 0);\n")
            .append("                \n")
            .append("                // Métriques du détecteur\n")
            .append("                document.getElementById('events-processed').textContent = \n")
            .append("                    formatNumber(safeGet(data, 'detector.detectionsPerformed', 0));\n")
            .append("                document.getElementById('accuracy').textContent = \n")
            .append("                    formatPercent(safeGet(data, 'detector.accuracy', 0));\n")
            .append("                document.getElementById('precision').textContent = \n")
            .append("                    formatPercent(safeGet(data, 'detector.precision', 0));\n")
            .append("                document.getElementById('recall').textContent = \n")
            .append("                    formatPercent(safeGet(data, 'detector.recall', 0));\n")
            .append("                document.getElementById('f1-score').textContent = \n")
            .append("                    formatPercent(safeGet(data, 'detector.f1Score', 0));\n")
            .append("                \n")
            .append("                // Statistiques de dérive\n")
            .append("                document.getElementById('drift-checks').textContent = \n")
            .append("                    formatNumber(safeGet(data, 'detector.driftStats.totalChecks', 0));\n")
            .append("                document.getElementById('drift-warnings').textContent = \n")
            .append("                    safeGet(data, 'detector.driftStats.warningsDetected', 0);\n")
            .append("                document.getElementById('drift-detected').textContent = \n")
            .append("                    safeGet(data, 'detector.driftStats.driftsDetected', 0);\n")
            .append("                document.getElementById('error-rate').textContent = \n")
            .append("                    formatPercent(safeGet(data, 'detector.driftStats.currentErrorRate', 0));\n")
            .append("                document.getElementById('warning-zone').textContent = \n")
            .append("                    safeGet(data, 'detector.driftStats.inWarningZone', false) ? 'Oui' : 'Non';\n")
            .append("                    \n")
            .append("            } catch (error) {\n")
            .append("                console.error('Erreur lors de la mise à jour des métriques:', error);\n")
            .append("                document.getElementById('system-status-indicator').className = 'status-indicator status-error';\n")
            .append("                document.getElementById('system-status').textContent = 'Erreur';\n")
            .append("                document.getElementById('error-message').textContent = \n")
            .append("                    'Impossible de charger les données: ' + error.message;\n")
            .append("                document.getElementById('error-message').style.display = 'block';\n")
            .append("            }\n")
            .append("        }\n")
            .append("        \n")
            .append("        // Mettre à jour immédiatement au chargement\n")
            .append("        updateMetrics();\n")
            .append("        \n")
            .append("        // Mettre à jour périodiquement\n")
            .append("        setInterval(updateMetrics, 5000);\n")
            .append("    </script>\n")
            .append("</body>\n")
            .append("</html>");
        
        return html.toString();
    }
    
    /**
     * Arrête le dashboard
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        logger.info("Monitoring dashboard stopped");
    }
    
    /**
     * Collecteur de métriques
     */
    private class MetricsCollector {
        private Map<String, Object> currentMetrics;
        
        public MetricsCollector() {
            this.currentMetrics = new HashMap<>();
        }
        
        public void collect() {
            currentMetrics.clear();
            currentMetrics.put("timestamp", System.currentTimeMillis());
            
            if (detector != null) {
                currentMetrics.put("detector", detector.getDetailedStatistics());
            }
            if (producer != null) {
                currentMetrics.put("producer", producer.getStatistics());
            }
            if (processor != null) {
                currentMetrics.put("processor", processor.getStatistics());
            }
        }
        
        public Map<String, Object> getCurrentMetrics() {
            return new HashMap<>(currentMetrics);
        }
    }
}
