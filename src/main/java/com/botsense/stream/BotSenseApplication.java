package com.botsense.stream;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.botsense.stream.config.ConfigurationManager;
import com.botsense.stream.core.detector.BotDetector;
import com.botsense.stream.generator.TrafficGenerator;
import com.botsense.stream.monitoring.MonitoringDashboard;
import com.botsense.stream.streaming.kafka.TrafficKafkaProducer;
import com.botsense.stream.streaming.spark.BotDetectionStreamProcessor;

/**
 * Application principale BotSense-Stream
 * Point d'entrée du système de détection adaptative de bots
 */
public class BotSenseApplication {
    private static final Logger logger = LoggerFactory.getLogger(BotSenseApplication.class);
    
    private ConfigurationManager config;
    private TrafficGenerator generator;
    private TrafficKafkaProducer producer;
    private BotDetector detector;
    private BotDetectionStreamProcessor processor;
    private MonitoringDashboard dashboard;
    
    private CountDownLatch shutdownLatch;
    private volatile boolean running;
    
    public BotSenseApplication() throws IOException {
        this.config = new ConfigurationManager();
        this.shutdownLatch = new CountDownLatch(1);
        this.running = false;
    }
    
    /**
     * Initialise tous les composants du système
     */
    public void initialize() {
        logger.info("=== Initializing BotSense-Stream ===");
        
        try {
            // 1. Initialiser le générateur de trafic
            initializeGenerator();
            
            // 2. Initialiser le détecteur
            initializeDetector();
            
            // 3. Initialiser le producteur Kafka
            initializeProducer();
            
            // 4. Initialiser le processeur Spark
            initializeProcessor();
            
            // 5. Initialiser le dashboard de monitoring
            initializeDashboard();
            
            logger.info("=== BotSense-Stream Initialized Successfully ===");
            
        } catch (Exception e) {
            logger.error("Error initializing BotSense-Stream", e);
            throw new RuntimeException("Initialization failed", e);
        }
    }
    
    /**
     * Initialise le générateur de trafic
     */
    private void initializeGenerator() {
        logger.info("Initializing traffic generator...");
        
        double botRatio = config.getDouble("generator.bot.ratio", 0.3);
        boolean evolutionEnabled = config.getBoolean("generator.evolution.enabled", true);
        long driftInterval = config.getLong("generator.drift.interval", 300000);
        
        generator = new TrafficGenerator(botRatio, evolutionEnabled, driftInterval);
        
        logger.info("Traffic generator initialized: bot ratio={}, evolution={}, drift interval={}ms",
                   botRatio, evolutionEnabled, driftInterval);
    }
    
    /**
     * Initialise le détecteur de bots
     */
    private void initializeDetector() {
        logger.info("Initializing bot detector...");
        
        int ensembleSize = config.getInt("model.ensemble.size", 10);
        double threshold = config.getDouble("detection.threshold", 0.7);
        int updateFreq = config.getInt("detection.update.frequency", 1000);
        boolean driftEnabled = config.getBoolean("drift.detection.enabled", true);
        
        detector = new BotDetector(ensembleSize, threshold, updateFreq, driftEnabled);
        
        logger.info("Bot detector initialized: ensemble size={}, threshold={}, drift detection={}",
                   ensembleSize, threshold, driftEnabled);
    }
    
    /**
     * Initialise le producteur Kafka
     */
    private void initializeProducer() {
        logger.info("Initializing Kafka producer...");
        
        String bootstrapServers = config.getString("kafka.bootstrap.servers", "localhost:9092");
        String topic = config.getString("kafka.topic.input", "bot-traffic");
        int rate = config.getInt("generator.rate", 1000);
        
        producer = new TrafficKafkaProducer(bootstrapServers, topic, generator, rate);
        
        logger.info("Kafka producer initialized: servers={}, topic={}, rate={}/s",
                   bootstrapServers, topic, rate);
    }
    
    /**
     * Initialise le processeur Spark Streaming
     */
    private void initializeProcessor() {
        logger.info("Initializing Spark Streaming processor...");
        
        String appName = config.getString("spark.app.name", "BotSense-Stream");
        String bootstrapServers = config.getString("kafka.bootstrap.servers", "localhost:9092");
        String inputTopic = config.getString("kafka.topic.input", "bot-traffic");
        String outputTopic = config.getString("kafka.topic.output", "bot-detections");
        int batchInterval = config.getInt("spark.streaming.batch.interval", 5000);
        
        processor = new BotDetectionStreamProcessor(
            appName, bootstrapServers, inputTopic, outputTopic, batchInterval, detector
        );
        
        logger.info("Spark Streaming processor initialized: batch interval={}ms", batchInterval);
    }
    
    /**
     * Initialise le dashboard de monitoring
     */
    private void initializeDashboard() {
        logger.info("Initializing monitoring dashboard...");
        
        boolean monitoringEnabled = config.getBoolean("monitoring.enabled", true);
        
        if (monitoringEnabled) {
            int port = config.getInt("monitoring.dashboard.port", 8090);
            int metricsInterval = config.getInt("monitoring.metrics.interval", 10000);
            
            dashboard = new MonitoringDashboard(
                port, metricsInterval, detector, producer, processor
            );
            
            logger.info("Monitoring dashboard initialized on port {}", port);
        } else {
            logger.info("Monitoring dashboard disabled");
        }
    }
    
    /**
     * Démarre le système
     */
    public void start() {
        if (running) {
            logger.warn("System already running");
            return;
        }
        
        logger.info("=== Starting BotSense-Stream ===");
        running = true;
        
        try {
            // 1. Démarrer le dashboard
            if (dashboard != null) {
                dashboard.start();
            }
            
            // 2. Démarrer le processeur Spark
            processor.start();
            
            // 3. Attendre que Spark soit prêt
            Thread.sleep(3000);
            
            // 4. Démarrer le producteur Kafka
            producer.start();
            
            logger.info("=== BotSense-Stream Started Successfully ===");
            logger.info("Monitoring dashboard: http://localhost:{}",
                       config.getInt("monitoring.dashboard.port", 8090));
            
            // Configurer le hook d'arrêt
            setupShutdownHook();
            
        } catch (Exception e) {
            logger.error("Error starting BotSense-Stream", e);
            running = false;
            throw new RuntimeException("Start failed", e);
        }
    }
    
    /**
     * Arrête le système
     */
    public void stop() {
        if (!running) {
            logger.warn("System not running");
            return;
        }
        
        logger.info("=== Stopping BotSense-Stream ===");
        running = false;
        
        try {
            // Arrêter dans l'ordre inverse du démarrage
            
            // 1. Arrêter le producteur
            if (producer != null) {
                logger.info("Stopping Kafka producer...");
                producer.stop();
            }
            
            // 2. Attendre que les messages soient traités
            Thread.sleep(2000);
            
            // 3. Arrêter le processeur Spark
            if (processor != null) {
                logger.info("Stopping Spark processor...");
                processor.stop();
            }
            
            // 4. Arrêter le dashboard
            if (dashboard != null) {
                logger.info("Stopping monitoring dashboard...");
                dashboard.stop();
            }
            
            // 5. Sauvegarder le modèle
            saveModel();
            
            logger.info("=== BotSense-Stream Stopped Successfully ===");
            shutdownLatch.countDown();
            
        } catch (Exception e) {
            logger.error("Error stopping BotSense-Stream", e);
        }
    }
    
    /**
     * Sauvegarde le modèle
     */
    private void saveModel() {
        try {
            String modelPath = "./models/detector_" + System.currentTimeMillis() + ".model";
            detector.save(modelPath);
            logger.info("Model saved to: {}", modelPath);
        } catch (IOException e) {
            logger.error("Error saving model", e);
        }
    }
    
    /**
     * Configure le hook d'arrêt
     */
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown hook triggered");
            stop();
        }));
    }
    
    /**
     * Attend la fin du système
     */
    public void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }
    
    /**
     * Affiche les statistiques
     */
    public void printStatistics() {
        logger.info("=== System Statistics ===");
        logger.info("Generator: {}", generator.getStatistics());
        logger.info("Producer: {}", producer.getStatistics());
        logger.info("Processor: {}", processor.getStatistics());
        logger.info("Detector: {}", detector.getStatistics());
    }
    
    /**
     * Point d'entrée principal
     */
    public static void main(String[] args) {
        try {
            // Parser les arguments de ligne de commande
            CommandLineOptions options = parseCommandLine(args);
            
            // Créer et initialiser l'application
            BotSenseApplication app = new BotSenseApplication();
            app.initialize();
            
            // Démarrer le système
            app.start();
            
            // Si durée spécifiée, arrêter après ce temps
            if (options.duration > 0) {
                logger.info("Running for {} seconds...", options.duration);
                Thread.sleep(options.duration * 1000L);
                app.printStatistics();
                app.stop();
            } else {
                // Sinon, attendre l'arrêt manuel
                logger.info("Press Ctrl+C to stop...");
                app.awaitTermination();
            }
            
        } catch (Exception e) {
            logger.error("Fatal error in BotSense-Stream", e);
            System.exit(1);
        }
    }
    
    /**
     * Parse les arguments de ligne de commande
     */
    private static CommandLineOptions parseCommandLine(String[] args) {
        Options options = new Options();
        
        options.addOption("m", "mode", true, "Execution mode (production/test)");
        options.addOption("d", "duration", true, "Run duration in seconds (0 = infinite)");
        options.addOption("h", "help", false, "Show help");
        
        CommandLineParser parser = new DefaultParser();
        CommandLineOptions result = new CommandLineOptions();
        
        try {
            CommandLine cmd = parser.parse(options, args);
            
            if (cmd.hasOption("help")) {
                printHelp(options);
                System.exit(0);
            }
            
            result.mode = cmd.getOptionValue("mode", "production");
            result.duration = Integer.parseInt(cmd.getOptionValue("duration", "0"));
            
        } catch (ParseException e) {
            logger.error("Error parsing command line", e);
            printHelp(options);
            System.exit(1);
        }
        
        return result;
    }
    
    /**
     * Affiche l'aide
     */
    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("BotSense-Stream", options);
    }
    
    /**
     * Options de ligne de commande
     */
    private static class CommandLineOptions {
        String mode = "production";
        int duration = 0;
    }
    
    // Getters pour les tests
    public TrafficGenerator getGenerator() { return generator; }
    public BotDetector getDetector() { return detector; }
    public TrafficKafkaProducer getProducer() { return producer; }
    public BotDetectionStreamProcessor getProcessor() { return processor; }
    public boolean isRunning() { return running; }
}
