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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Dashboard de monitoring professionnel avec design moderne ultra-attractif
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
    
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext("/", this::handleRoot);
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/statistics", this::handleStatistics);
        server.createContext("/api/health", this::handleHealth);
        
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        
        startMetricsCollection();
        
        logger.info("Monitoring dashboard started on port {}", port);
    }
    
    private void startMetricsCollection() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                metricsCollector.collect();
            } catch (Exception e) {
                logger.error("Error collecting metrics", e);
            }
        }, 0, metricsInterval, TimeUnit.MILLISECONDS);
    }
    
    private void handleRoot(HttpExchange exchange) throws IOException {
        String html = generateDashboardHTML();
        sendResponse(exchange, 200, html, "text/html");
    }
    
    private void handleMetrics(HttpExchange exchange) throws IOException {
        Map<String, Object> metrics = metricsCollector.getCurrentMetrics();
        String json = gson.toJson(metrics);
        sendResponse(exchange, 200, json, "application/json");
    }
    
    private void handleStatistics(HttpExchange exchange) throws IOException {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // DÉTECTEUR
            if (detector != null) {
                BotDetector.DetectorStatistics detectorStats = detector.getDetailedStatistics();
                
                Map<String, Object> detectorMap = new HashMap<>();
                detectorMap.put("detectionsPerformed", detectorStats.getDetectionsPerformed());
                detectorMap.put("accuracy", detectorStats.getAccuracy());
                detectorMap.put("precision", detectorStats.getPrecision());
                detectorMap.put("recall", detectorStats.getRecall());
                detectorMap.put("f1Score", detectorStats.getF1Score());
                detectorMap.put("truePositives", detectorStats.getTruePositives());
                detectorMap.put("trueNegatives", detectorStats.getTrueNegatives());
                detectorMap.put("falsePositives", detectorStats.getFalsePositives());
                detectorMap.put("falseNegatives", detectorStats.getFalseNegatives());
                
                // Stats de dérive
                if (detectorStats.getDriftStats() != null) {
                    Map<String, Object> driftMap = new HashMap<>();
                    driftMap.put("totalChecks", detectorStats.getDriftStats().getTotalChecks());
                    driftMap.put("warningsDetected", detectorStats.getDriftStats().getWarningsDetected());
                    driftMap.put("driftsDetected", detectorStats.getDriftStats().getDriftsDetected());
                    driftMap.put("currentErrorRate", detectorStats.getDriftStats().getCurrentErrorRate());
                    driftMap.put("inWarningZone", detectorStats.getDriftStats().isInWarningZone());
                    detectorMap.put("driftStats", driftMap);
                }
                
                stats.put("detector", detectorMap);
            }
            
            // PRODUCTEUR
            if (producer != null) {
                TrafficKafkaProducer.ProducerStatistics producerStats = producer.getStatistics();
                
                Map<String, Object> producerMap = new HashMap<>();
                producerMap.put("messagesSent", producerStats.getMessagesSent());
                producerMap.put("throughput", producerStats.getThroughput());
                producerMap.put("running", producerStats.isRunning());
                producerMap.put("targetRate", producerStats.getTargetRate());
                
                if (producerStats.getGeneratorStats() != null) {
                    Map<String, Object> generatorStats = new HashMap<>();
                    generatorStats.put("botRatio", producerStats.getGeneratorStats().getBotRatio());
                    generatorStats.put("behaviorPhase", producerStats.getGeneratorStats().getBehaviorPhase());
                    generatorStats.put("eventsGenerated", producerStats.getGeneratorStats().getEventsGenerated());
                    producerMap.put("generatorStats", generatorStats);
                }
                
                stats.put("producer", producerMap);
            }
            
            // PROCESSEUR
            if (processor != null) {
                BotDetectionStreamProcessor.ProcessorStatistics processorStats = processor.getStatistics();
                
                Map<String, Object> processorMap = new HashMap<>();
                processorMap.put("eventsProcessed", processorStats.getEventsProcessed());
                processorMap.put("botsDetected", processorStats.getBotsDetected());
                processorMap.put("running", processorStats.isRunning());
                processorMap.put("throughput", processorStats.getThroughput());
                
                long processed = processorStats.getEventsProcessed();
                long bots = processorStats.getBotsDetected();
                double botRate = processed > 0 ? (bots * 100.0 / processed) : 0.0;
                processorMap.put("botRate", botRate);
                
                stats.put("processor", processorMap);
            }
            
        } catch (Exception e) {
            logger.error("Error collecting statistics", e);
            stats.put("error", "Error: " + e.getMessage());
        }
        
        String json = gson.toJson(stats);
        sendResponse(exchange, 200, json, "application/json");
    }
    
    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("producer_running", producer != null && producer.isRunning());
        health.put("processor_running", processor != null && processor.isRunning());
        
        String json = gson.toJson(health);
        sendResponse(exchange, 200, json, "application/json");
    }
    
    private void sendResponse(HttpExchange exchange,
                            int statusCode, String response, String contentType) 
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    /**
     * Génère le HTML du dashboard avec design ultra-moderne et attractif
     */
    private String generateDashboardHTML() {
        return "<!DOCTYPE html>\n" +
"<html lang=\"fr\">\n" +
"<head>\n" +
"    <meta charset=\"UTF-8\">\n" +
"    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
"    <title>BotSense-Stream | Real-Time Bot Detection Platform</title>\n" +
"    <link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap\" rel=\"stylesheet\">\n" +
"    <style>\n" +
"        :root {\n" +
"            --gradient-primary: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
"            --gradient-secondary: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);\n" +
"            --gradient-success: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);\n" +
"            --gradient-warning: linear-gradient(135deg, #fa709a 0%, #fee140 100%);\n" +
"            --gradient-danger: linear-gradient(135deg, #ff6b6b 0%, #ee5a6f 100%);\n" +
"            --gradient-drift: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);\n" +
"            --gradient-dark: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);\n" +
"            \n" +
"            --color-bg: #0a0e27;\n" +
"            --color-surface: rgba(255, 255, 255, 0.03);\n" +
"            --color-surface-hover: rgba(255, 255, 255, 0.06);\n" +
"            --color-text: #e2e8f0;\n" +
"            --color-text-muted: #94a3b8;\n" +
"            --color-border: rgba(255, 255, 255, 0.08);\n" +
"            \n" +
"            --shadow-sm: 0 2px 8px rgba(0, 0, 0, 0.3);\n" +
"            --shadow-md: 0 8px 32px rgba(0, 0, 0, 0.4);\n" +
"            --shadow-lg: 0 20px 60px rgba(0, 0, 0, 0.5);\n" +
"            --shadow-glow: 0 0 40px rgba(102, 126, 234, 0.4);\n" +
"        }\n" +
"        \n" +
"        * {\n" +
"            margin: 0;\n" +
"            padding: 0;\n" +
"            box-sizing: border-box;\n" +
"        }\n" +
"        \n" +
"        body {\n" +
"            font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;\n" +
"            background: var(--color-bg);\n" +
"            color: var(--color-text);\n" +
"            min-height: 100vh;\n" +
"            padding: 0;\n" +
"            overflow-x: hidden;\n" +
"            position: relative;\n" +
"        }\n" +
"        \n" +
"        body::before {\n" +
"            content: '';\n" +
"            position: fixed;\n" +
"            top: -50%;\n" +
"            left: -50%;\n" +
"            width: 200%;\n" +
"            height: 200%;\n" +
"            background: radial-gradient(circle at 30% 50%, rgba(102, 126, 234, 0.15) 0%, transparent 50%),\n" +
"                        radial-gradient(circle at 70% 50%, rgba(118, 75, 162, 0.1) 0%, transparent 50%);\n" +
"            animation: rotate 30s linear infinite;\n" +
"            z-index: 0;\n" +
"        }\n" +
"        \n" +
"        @keyframes rotate {\n" +
"            0% { transform: rotate(0deg); }\n" +
"            100% { transform: rotate(360deg); }\n" +
"        }\n" +
"        \n" +
"        .container {\n" +
"            max-width: 1800px;\n" +
"            margin: 0 auto;\n" +
"            padding: 40px 32px;\n" +
"            position: relative;\n" +
"            z-index: 1;\n" +
"        }\n" +
"        \n" +
"        /* Header */\n" +
"        .header {\n" +
"            background: var(--color-surface);\n" +
"            backdrop-filter: blur(20px);\n" +
"            border-radius: 24px;\n" +
"            padding: 48px;\n" +
"            margin-bottom: 32px;\n" +
"            box-shadow: var(--shadow-md);\n" +
"            border: 1px solid var(--color-border);\n" +
"            position: relative;\n" +
"            overflow: hidden;\n" +
"        }\n" +
"        \n" +
"        .header::before {\n" +
"            content: '';\n" +
"            position: absolute;\n" +
"            top: 0;\n" +
"            left: 0;\n" +
"            right: 0;\n" +
"            height: 4px;\n" +
"            background: var(--gradient-primary);\n" +
"        }\n" +
"        \n" +
"        .header-top {\n" +
"            display: flex;\n" +
"            justify-content: space-between;\n" +
"            align-items: center;\n" +
"        }\n" +
"        \n" +
"        .header h1 {\n" +
"            font-size: 48px;\n" +
"            font-weight: 800;\n" +
"            background: var(--gradient-primary);\n" +
"            -webkit-background-clip: text;\n" +
"            -webkit-text-fill-color: transparent;\n" +
"            background-clip: text;\n" +
"            letter-spacing: -0.02em;\n" +
"            margin-bottom: 12px;\n" +
"        }\n" +
"        \n" +
"        .header-subtitle {\n" +
"            color: var(--color-text-muted);\n" +
"            font-size: 18px;\n" +
"            font-weight: 400;\n" +
"            letter-spacing: -0.01em;\n" +
"        }\n" +
"        \n" +
"        .status-badge {\n" +
"            display: inline-flex;\n" +
"            align-items: center;\n" +
"            gap: 12px;\n" +
"            padding: 16px 32px;\n" +
"            background: var(--gradient-success);\n" +
"            border-radius: 100px;\n" +
"            font-size: 16px;\n" +
"            font-weight: 600;\n" +
"            color: white;\n" +
"            box-shadow: var(--shadow-sm);\n" +
"            transition: all 0.3s ease;\n" +
"        }\n" +
"        \n" +
"        .status-badge:hover {\n" +
"            transform: translateY(-2px);\n" +
"            box-shadow: var(--shadow-md);\n" +
"        }\n" +
"        \n" +
"        .status-badge.error {\n" +
"            background: var(--gradient-danger);\n" +
"        }\n" +
"        \n" +
"        .pulse {\n" +
"            width: 10px;\n" +
"            height: 10px;\n" +
"            background: white;\n" +
"            border-radius: 50%;\n" +
"            box-shadow: 0 0 0 0 rgba(255, 255, 255, 0.7);\n" +
"            animation: pulse-animation 2s ease-out infinite;\n" +
"        }\n" +
"        \n" +
"        @keyframes pulse-animation {\n" +
"            0% { box-shadow: 0 0 0 0 rgba(255, 255, 255, 0.7); }\n" +
"            70% { box-shadow: 0 0 0 10px rgba(255, 255, 255, 0); }\n" +
"            100% { box-shadow: 0 0 0 0 rgba(255, 255, 255, 0); }\n" +
"        }\n" +
"        \n" +
"        /* Grid Layout */\n" +
"        .grid {\n" +
"            display: grid;\n" +
"            grid-template-columns: repeat(auto-fit, minmax(450px, 1fr));\n" +
"            gap: 32px;\n" +
"            margin-bottom: 32px;\n" +
"        }\n" +
"        \n" +
"        /* Cards */\n" +
"        .card {\n" +
"            background: var(--color-surface);\n" +
"            backdrop-filter: blur(20px);\n" +
"            border-radius: 24px;\n" +
"            padding: 36px;\n" +
"            box-shadow: var(--shadow-md);\n" +
"            border: 1px solid var(--color-border);\n" +
"            transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1);\n" +
"            position: relative;\n" +
"            overflow: hidden;\n" +
"        }\n" +
"        \n" +
"        .card::before {\n" +
"            content: '';\n" +
"            position: absolute;\n" +
"            top: 0;\n" +
"            left: 0;\n" +
"            right: 0;\n" +
"            height: 3px;\n" +
"            background: var(--gradient-primary);\n" +
"            transform: scaleX(0);\n" +
"            transform-origin: left;\n" +
"            transition: transform 0.4s ease;\n" +
"        }\n" +
"        \n" +
"        .card:hover::before {\n" +
"            transform: scaleX(1);\n" +
"        }\n" +
"        \n" +
"        .card:hover {\n" +
"            transform: translateY(-8px);\n" +
"            box-shadow: var(--shadow-lg);\n" +
"            background: var(--color-surface-hover);\n" +
"            border-color: rgba(102, 126, 234, 0.3);\n" +
"        }\n" +
"        \n" +
"        .card-header {\n" +
"            margin-bottom: 32px;\n" +
"            padding-bottom: 24px;\n" +
"            border-bottom: 1px solid var(--color-border);\n" +
"        }\n" +
"        \n" +
"        .card-title {\n" +
"            font-size: 22px;\n" +
"            font-weight: 700;\n" +
"            color: var(--color-text);\n" +
"            letter-spacing: -0.02em;\n" +
"        }\n" +
"        \n" +
"        /* Metrics */\n" +
"        .metric-row {\n" +
"            display: flex;\n" +
"            justify-content: space-between;\n" +
"            align-items: center;\n" +
"            padding: 20px 0;\n" +
"            border-bottom: 1px solid var(--color-border);\n" +
"            transition: all 0.3s ease;\n" +
"        }\n" +
"        \n" +
"        .metric-row:hover {\n" +
"            padding-left: 8px;\n" +
"            background: rgba(255, 255, 255, 0.02);\n" +
"            border-radius: 12px;\n" +
"        }\n" +
"        \n" +
"        .metric-row:last-child {\n" +
"            border-bottom: none;\n" +
"        }\n" +
"        \n" +
"        .metric-label {\n" +
"            color: var(--color-text-muted);\n" +
"            font-size: 15px;\n" +
"            font-weight: 500;\n" +
"            display: flex;\n" +
"            flex-direction: column;\n" +
"            gap: 6px;\n" +
"        }\n" +
"        \n" +
"        .metric-help {\n" +
"            font-size: 12px;\n" +
"            color: #64748b;\n" +
"            font-weight: 400;\n" +
"        }\n" +
"        \n" +
"        .metric-value {\n" +
"            font-size: 28px;\n" +
"            font-weight: 800;\n" +
"            color: var(--color-text);\n" +
"            letter-spacing: -0.02em;\n" +
"            background: linear-gradient(135deg, #e2e8f0 0%, #cbd5e1 100%);\n" +
"            -webkit-background-clip: text;\n" +
"            -webkit-text-fill-color: transparent;\n" +
"            background-clip: text;\n" +
"        }\n" +
"        \n" +
"        .metric-value.good { \n" +
"            background: var(--gradient-success);\n" +
"            -webkit-background-clip: text;\n" +
"            -webkit-text-fill-color: transparent;\n" +
"            background-clip: text;\n" +
"        }\n" +
"        \n" +
"        .metric-value.warning { \n" +
"            background: var(--gradient-warning);\n" +
"            -webkit-background-clip: text;\n" +
"            -webkit-text-fill-color: transparent;\n" +
"            background-clip: text;\n" +
"        }\n" +
"        \n" +
"        .metric-value.danger { \n" +
"            background: var(--gradient-danger);\n" +
"            -webkit-background-clip: text;\n" +
"            -webkit-text-fill-color: transparent;\n" +
"            background-clip: text;\n" +
"        }\n" +
"        \n" +
"        /* Progress Bars */\n" +
"        .progress-bar {\n" +
"            width: 100%;\n" +
"            height: 6px;\n" +
"            background: rgba(255, 255, 255, 0.05);\n" +
"            border-radius: 100px;\n" +
"            overflow: hidden;\n" +
"            margin-top: 10px;\n" +
"            box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.2);\n" +
"        }\n" +
"        \n" +
"        .progress-fill {\n" +
"            height: 100%;\n" +
"            background: var(--gradient-primary);\n" +
"            border-radius: 100px;\n" +
"            transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1);\n" +
"            box-shadow: 0 0 10px rgba(102, 126, 234, 0.5);\n" +
"            position: relative;\n" +
"            overflow: hidden;\n" +
"        }\n" +
"        \n" +
"        .progress-fill::after {\n" +
"            content: '';\n" +
"            position: absolute;\n" +
"            top: 0;\n" +
"            left: 0;\n" +
"            right: 0;\n" +
"            bottom: 0;\n" +
"            background: linear-gradient(90deg, transparent, rgba(255,255,255,0.3), transparent);\n" +
"            animation: shimmer 2s infinite;\n" +
"        }\n" +
"        \n" +
"        @keyframes shimmer {\n" +
"            0% { transform: translateX(-100%); }\n" +
"            100% { transform: translateX(100%); }\n" +
"        }\n" +
"        \n" +
"        .progress-fill.success { \n" +
"            background: var(--gradient-success);\n" +
"            box-shadow: 0 0 10px rgba(79, 172, 254, 0.5);\n" +
"        }\n" +
"        \n" +
"        .progress-fill.warning { \n" +
"            background: var(--gradient-warning);\n" +
"            box-shadow: 0 0 10px rgba(250, 112, 154, 0.5);\n" +
"        }\n" +
"        \n" +
"        .progress-fill.danger { \n" +
"            background: var(--gradient-danger);\n" +
"            box-shadow: 0 0 10px rgba(255, 107, 107, 0.5);\n" +
"        }\n" +
"        \n" +
"        /* Info Panel */\n" +
"        .info-panel {\n" +
"            background: var(--color-surface);\n" +
"            backdrop-filter: blur(20px);\n" +
"            border-radius: 24px;\n" +
"            padding: 40px;\n" +
"            margin-top: 32px;\n" +
"            border: 1px solid var(--color-border);\n" +
"            box-shadow: var(--shadow-md);\n" +
"        }\n" +
"        \n" +
"        .info-panel h3 {\n" +
"            color: var(--color-text);\n" +
"            font-size: 24px;\n" +
"            font-weight: 700;\n" +
"            margin-bottom: 24px;\n" +
"            background: var(--gradient-primary);\n" +
"            -webkit-background-clip: text;\n" +
"            -webkit-text-fill-color: transparent;\n" +
"            background-clip: text;\n" +
"        }\n" +
"        \n" +
"        .info-panel p {\n" +
"            color: var(--color-text-muted);\n" +
"            font-size: 15px;\n" +
"            line-height: 1.8;\n" +
"            margin-bottom: 16px;\n" +
"        }\n" +
"        \n" +
"        .info-panel p strong {\n" +
"            color: var(--color-text);\n" +
"            font-weight: 600;\n" +
"        }\n" +
"        \n" +
"        .refresh-info {\n" +
"            text-align: center;\n" +
"            color: var(--color-text-muted);\n" +
"            padding: 24px;\n" +
"            background: var(--color-surface);\n" +
"            border-radius: 16px;\n" +
"            margin-top: 32px;\n" +
"            font-size: 14px;\n" +
"            backdrop-filter: blur(20px);\n" +
"            border: 1px solid var(--color-border);\n" +
"            font-weight: 500;\n" +
"        }\n" +
"        \n" +
"        /* Stats Grid */\n" +
"        .stats-grid {\n" +
"            display: grid;\n" +
"            grid-template-columns: repeat(3, 1fr);\n" +
"            gap: 16px;\n" +
"            margin-top: 24px;\n" +
"        }\n" +
"        \n" +
"        .stat-box {\n" +
"            background: rgba(255, 255, 255, 0.03);\n" +
"            padding: 20px;\n" +
"            border-radius: 16px;\n" +
"            border: 1px solid var(--color-border);\n" +
"            text-align: center;\n" +
"            transition: all 0.3s ease;\n" +
"        }\n" +
"        \n" +
"        .stat-box:hover {\n" +
"            background: rgba(255, 255, 255, 0.05);\n" +
"            transform: translateY(-4px);\n" +
"        }\n" +
"        \n" +
"        .stat-label {\n" +
"            font-size: 12px;\n" +
"            color: var(--color-text-muted);\n" +
"            text-transform: uppercase;\n" +
"            letter-spacing: 0.05em;\n" +
"            margin-bottom: 8px;\n" +
"            font-weight: 600;\n" +
"        }\n" +
"        \n" +
"        .stat-value {\n" +
"            font-size: 24px;\n" +
"            font-weight: 800;\n" +
"            color: var(--color-text);\n" +
"        }\n" +
"        \n" +
"        /* Responsive */\n" +
"        @media (max-width: 1400px) {\n" +
"            .grid {\n" +
"                grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));\n" +
"            }\n" +
"        }\n" +
"        \n" +
"        @media (max-width: 768px) {\n" +
"            .container {\n" +
"                padding: 24px 16px;\n" +
"            }\n" +
"            \n" +
"            .header {\n" +
"                padding: 32px 24px;\n" +
"            }\n" +
"            \n" +
"            .header h1 {\n" +
"                font-size: 32px;\n" +
"            }\n" +
"            \n" +
"            .grid {\n" +
"                grid-template-columns: 1fr;\n" +
"                gap: 24px;\n" +
"            }\n" +
"            \n" +
"            .stats-grid {\n" +
"                grid-template-columns: 1fr;\n" +
"            }\n" +
"        }\n" +
"    </style>\n" +
"</head>\n" +
"<body>\n" +
"    <div class=\"container\">\n" +
"        <div class=\"header\">\n" +
"            <div class=\"header-top\">\n" +
"                <div>\n" +
"                    <h1>BotSense-Stream</h1>\n" +
"                    <p class=\"header-subtitle\">Real-Time Adaptive Bot Detection Platform with Continuous Learning</p>\n" +
"                </div>\n" +
"                <div class=\"status-badge\" id=\"global-status\">\n" +
"                    <span class=\"pulse\"></span>\n" +
"                    <span id=\"status-text\">Loading...</span>\n" +
"                </div>\n" +
"            </div>\n" +
"        </div>\n" +
"        \n" +
"        <div class=\"grid\">\n" +
"            <div class=\"card\">\n" +
"                <div class=\"card-header\">\n" +
"                    <span class=\"card-title\">System Status</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Kafka Producer\n" +
"                        <span class=\"metric-help\">Generates and sends simulated traffic</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"producer-status\">-</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Spark Processor\n" +
"                        <span class=\"metric-help\">Processes stream in micro-batches</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"processor-status\">-</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        AI Detector\n" +
"                        <span class=\"metric-help\">Ensemble of 10 Hoeffding Trees</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"detector-status\">-</span>\n" +
"                </div>\n" +
"            </div>\n" +
"            \n" +
"            <div class=\"card\">\n" +
"                <div class=\"card-header\">\n" +
"                    <span class=\"card-title\">Production Metrics</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Messages Sent\n" +
"                        <span class=\"metric-help\">Total events generated</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"messages-sent\">0</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Current Throughput\n" +
"                        <span class=\"metric-help\">Events processed per second</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"throughput\">0</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Bot Ratio in Stream\n" +
"                        <span class=\"metric-help\">Configured percentage of bot traffic</span>\n" +
"                        <div class=\"progress-bar\">\n" +
"                            <div class=\"progress-fill\" id=\"bot-ratio-bar\" style=\"width: 0%\"></div>\n" +
"                        </div>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"bot-ratio\">0%</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Behavior Phase\n" +
"                        <span class=\"metric-help\">0=Basic, 1=Moderate, 2=Sophisticated</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"behavior-phase\">0</span>\n" +
"                </div>\n" +
"            </div>\n" +
"            \n" +
"            <div class=\"card\">\n" +
"                <div class=\"card-header\">\n" +
"                    <span class=\"card-title\">Detection Performance</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Events Processed\n" +
"                        <span class=\"metric-help\">Total events analyzed</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"events-processed\">0</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Accuracy\n" +
"                        <span class=\"metric-help\">Correct predictions / Total</span>\n" +
"                        <div class=\"progress-bar\">\n" +
"                            <div class=\"progress-fill success\" id=\"accuracy-bar\" style=\"width: 0%\"></div>\n" +
"                        </div>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value good\" id=\"accuracy\">0%</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Precision\n" +
"                        <span class=\"metric-help\">True bots / Detected bots</span>\n" +
"                        <div class=\"progress-bar\">\n" +
"                            <div class=\"progress-fill success\" id=\"precision-bar\" style=\"width: 0%\"></div>\n" +
"                        </div>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value good\" id=\"precision\">0%</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Recall\n" +
"                        <span class=\"metric-help\">Bots detected / Total bots</span>\n" +
"                        <div class=\"progress-bar\">\n" +
"                            <div class=\"progress-fill warning\" id=\"recall-bar\" style=\"width: 0%\"></div>\n" +
"                        </div>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"recall\">0%</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        F1-Score\n" +
"                        <span class=\"metric-help\">Harmonic mean precision/recall</span>\n" +
"                        <div class=\"progress-bar\">\n" +
"                            <div class=\"progress-fill\" id=\"f1-bar\" style=\"width: 0%\"></div>\n" +
"                        </div>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"f1-score\">0%</span>\n" +
"                </div>\n" +
"            </div>\n" +
"            \n" +
"            <div class=\"card\">\n" +
"                <div class=\"card-header\">\n" +
"                    <span class=\"card-title\">Drift Detection (ADWIN)</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Total Checks\n" +
"                        <span class=\"metric-help\">Number of drift tests performed</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"drift-checks\">0</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Warnings\n" +
"                        <span class=\"metric-help\">Potential change alerts</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value warning\" id=\"drift-warnings\">0</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Drifts Detected\n" +
"                        <span class=\"metric-help\">Confirmed distribution changes</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value danger\" id=\"drift-detected\">0</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Current Error Rate\n" +
"                        <span class=\"metric-help\">Percentage of incorrect predictions</span>\n" +
"                        <div class=\"progress-bar\">\n" +
"                            <div class=\"progress-fill danger\" id=\"error-rate-bar\" style=\"width: 0%\"></div>\n" +
"                        </div>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"error-rate\">0%</span>\n" +
"                </div>\n" +
"                <div class=\"metric-row\">\n" +
"                    <div class=\"metric-label\">\n" +
"                        Warning Zone\n" +
"                        <span class=\"metric-help\">ADWIN monitoring a change</span>\n" +
"                    </div>\n" +
"                    <span class=\"metric-value\" id=\"warning-zone\">No</span>\n" +
"                </div>\n" +
"            </div>\n" +
"        </div>\n" +
"        \n" +
"        <div class=\"info-panel\">\n" +
"            <h3>Understanding the Metrics</h3>\n" +
"            <p><strong>Accuracy:</strong> Overall percentage of correct predictions. A high score (>90%) indicates the system is globally accurate.</p>\n" +
"            <p><strong>Precision:</strong> When the system says \"it's a bot\", it's correct in this percentage of cases. High precision (>95%) avoids false alarms.</p>\n" +
"            <p><strong>Recall:</strong> Percentage of real bots that the system detects. High recall (>80%) means few bots go unnoticed.</p>\n" +
"            <p><strong>F1-Score:</strong> Balance between Precision and Recall. It's the harmonic mean of both. A good F1 (>85%) indicates a balanced system.</p>\n" +
"            <p><strong>ADWIN Warning Zone:</strong> When active, indicates the ADWIN algorithm is monitoring a potential change in data distribution. This is normal during bot behavior evolution (Phase 0 → 1 → 2).</p>\n" +
"            <p><strong>Behavior Phase:</strong> Bots evolve every 5 minutes: Phase 0 (basic) → Phase 1 (moderate) → Phase 2 (sophisticated). The system adapts automatically.</p>\n" +
"        </div>\n" +
"        \n" +
"        <div class=\"refresh-info\">\n" +
"            Auto-refresh every 5 seconds | BotSense-Stream v1.0\n" +
"        </div>\n" +
"    </div>\n" +
"    \n" +
"    <script>\n" +
"        function safeGet(obj, path, defaultValue = '-') {\n" +
"            return path.split('.').reduce((o, p) => (o && o[p] !== undefined) ? o[p] : defaultValue, obj);\n" +
"        }\n" +
"        \n" +
"        function formatNumber(value) {\n" +
"            return typeof value === 'number' ? value.toLocaleString('fr-FR') : value;\n" +
"        }\n" +
"        \n" +
"        function formatPercent(value) {\n" +
"            if (typeof value !== 'number') return '0%';\n" +
"            return (value * 100).toFixed(2) + '%';\n" +
"        }\n" +
"        \n" +
"        function updateProgressBar(elementId, value) {\n" +
"            const bar = document.getElementById(elementId);\n" +
"            if (bar) {\n" +
"                const percent = typeof value === 'number' ? value * 100 : parseFloat(value);\n" +
"                bar.style.width = Math.min(100, Math.max(0, percent)) + '%';\n" +
"            }\n" +
"        }\n" +
"        \n" +
"        function getStatusClass(value, type = 'accuracy') {\n" +
"            if (type === 'accuracy' || type === 'precision' || type === 'f1') {\n" +
"                if (value >= 0.9) return 'good';\n" +
"                if (value >= 0.7) return 'warning';\n" +
"                return 'danger';\n" +
"            }\n" +
"            if (type === 'recall') {\n" +
"                if (value >= 0.85) return 'good';\n" +
"                if (value >= 0.65) return 'warning';\n" +
"                return 'danger';\n" +
"            }\n" +
"            if (type === 'error') {\n" +
"                if (value <= 0.05) return 'good';\n" +
"                if (value <= 0.1) return 'warning';\n" +
"                return 'danger';\n" +
"            }\n" +
"            return '';\n" +
"        }\n" +
"        \n" +
"        async function updateMetrics() {\n" +
"            try {\n" +
"                const response = await fetch('/api/statistics');\n" +
"                if (!response.ok) throw new Error(`HTTP ${response.status}`);\n" +
"                const data = await response.json();\n" +
"                \n" +
"                const globalStatus = document.getElementById('global-status');\n" +
"                const statusText = document.getElementById('status-text');\n" +
"                if (data && !data.error) {\n" +
"                    globalStatus.className = 'status-badge';\n" +
"                    statusText.textContent = 'System Operational';\n" +
"                } else {\n" +
"                    globalStatus.className = 'status-badge error';\n" +
"                    statusText.textContent = 'System Error';\n" +
"                }\n" +
"                \n" +
"                const producerRunning = safeGet(data, 'producer.running', false);\n" +
"                const processorRunning = safeGet(data, 'processor.running', false);\n" +
"                \n" +
"                document.getElementById('producer-status').textContent = producerRunning ? 'Active' : 'Stopped';\n" +
"                document.getElementById('producer-status').className = 'metric-value ' + (producerRunning ? 'good' : 'danger');\n" +
"                \n" +
"                document.getElementById('processor-status').textContent = processorRunning ? 'Active' : 'Stopped';\n" +
"                document.getElementById('processor-status').className = 'metric-value ' + (processorRunning ? 'good' : 'danger');\n" +
"                \n" +
"                document.getElementById('detector-status').textContent = data.detector ? 'Active' : 'Stopped';\n" +
"                document.getElementById('detector-status').className = 'metric-value ' + (data.detector ? 'good' : 'danger');\n" +
"                \n" +
"                document.getElementById('messages-sent').textContent = formatNumber(safeGet(data, 'producer.messagesSent', 0));\n" +
"                \n" +
"                const throughput = safeGet(data, 'producer.throughput', 0);\n" +
"                document.getElementById('throughput').textContent = throughput.toFixed(1) + ' msg/s';\n" +
"                \n" +
"                const botRatio = safeGet(data, 'producer.generatorStats.botRatio', 0);\n" +
"                document.getElementById('bot-ratio').textContent = formatPercent(botRatio);\n" +
"                updateProgressBar('bot-ratio-bar', botRatio);\n" +
"                \n" +
"                document.getElementById('behavior-phase').textContent = 'Phase ' + safeGet(data, 'producer.generatorStats.behaviorPhase', 0);\n" +
"                \n" +
"                document.getElementById('events-processed').textContent = formatNumber(safeGet(data, 'detector.detectionsPerformed', 0));\n" +
"                \n" +
"                const accuracy = safeGet(data, 'detector.accuracy', 0);\n" +
"                document.getElementById('accuracy').textContent = formatPercent(accuracy);\n" +
"                document.getElementById('accuracy').className = 'metric-value ' + getStatusClass(accuracy, 'accuracy');\n" +
"                updateProgressBar('accuracy-bar', accuracy);\n" +
"                \n" +
"                const precision = safeGet(data, 'detector.precision', 0);\n" +
"                document.getElementById('precision').textContent = formatPercent(precision);\n" +
"                document.getElementById('precision').className = 'metric-value ' + getStatusClass(precision, 'precision');\n" +
"                updateProgressBar('precision-bar', precision);\n" +
"                \n" +
"                const recall = safeGet(data, 'detector.recall', 0);\n" +
"                document.getElementById('recall').textContent = formatPercent(recall);\n" +
"                document.getElementById('recall').className = 'metric-value ' + getStatusClass(recall, 'recall');\n" +
"                updateProgressBar('recall-bar', recall);\n" +
"                \n" +
"                const f1Score = safeGet(data, 'detector.f1Score', 0);\n" +
"                document.getElementById('f1-score').textContent = formatPercent(f1Score);\n" +
"                document.getElementById('f1-score').className = 'metric-value ' + getStatusClass(f1Score, 'f1');\n" +
"                updateProgressBar('f1-bar', f1Score);\n" +
"                \n" +
"                document.getElementById('drift-checks').textContent = formatNumber(safeGet(data, 'detector.driftStats.totalChecks', 0));\n" +
"                document.getElementById('drift-warnings').textContent = safeGet(data, 'detector.driftStats.warningsDetected', 0);\n" +
"                document.getElementById('drift-detected').textContent = safeGet(data, 'detector.driftStats.driftsDetected', 0);\n" +
"                \n" +
"                const errorRate = safeGet(data, 'detector.driftStats.currentErrorRate', 0);\n" +
"                document.getElementById('error-rate').textContent = formatPercent(errorRate);\n" +
"                document.getElementById('error-rate').className = 'metric-value ' + getStatusClass(errorRate, 'error');\n" +
"                updateProgressBar('error-rate-bar', errorRate);\n" +
"                \n" +
"                const inWarningZone = safeGet(data, 'detector.driftStats.inWarningZone', false);\n" +
"                document.getElementById('warning-zone').textContent = inWarningZone ? 'Yes' : 'No';\n" +
"                document.getElementById('warning-zone').className = 'metric-value ' + (inWarningZone ? 'warning' : 'good');\n" +
"                \n" +
"            } catch (error) {\n" +
"                console.error('Update error:', error);\n" +
"                const globalStatus = document.getElementById('global-status');\n" +
"                const statusText = document.getElementById('status-text');\n" +
"                globalStatus.className = 'status-badge error';\n" +
"                statusText.textContent = 'Connection Error';\n" +
"            }\n" +
"        }\n" +
"        \n" +
"        updateMetrics();\n" +
"        setInterval(updateMetrics, 5000);\n" +
"    </script>\n" +
"</body>\n" +
"</html>";
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        logger.info("Monitoring dashboard stopped");
    }

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