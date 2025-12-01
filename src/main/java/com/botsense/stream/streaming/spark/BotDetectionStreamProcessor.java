package com.botsense.stream.streaming.spark;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.SparkConf;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.botsense.stream.core.TrafficEvent;
import com.botsense.stream.core.detector.BotDetector;
import com.google.gson.Gson;

/**
 * Processeur Spark Streaming pour la détection de bots en temps réel
 * VERSION FINALE CORRIGÉE - Tous les bugs résolus
 */
public class BotDetectionStreamProcessor implements AutoCloseable, Serializable {
    private static final Logger logger = LoggerFactory.getLogger(BotDetectionStreamProcessor.class);
    private static final long serialVersionUID = 1L;
    
    private final String appName;
    private final String kafkaBootstrap;
    private final String inputTopic;
    private final String outputTopic;
    private final int batchInterval;
    
    private transient JavaStreamingContext streamingContext;
    private transient BotDetector detector;
    private transient Gson gson;
    private transient AtomicBoolean running;
    private transient AtomicLong eventsProcessed;
    private transient AtomicLong botsDetected;
    private transient long startTime;
    
    public BotDetectionStreamProcessor(String appName, String kafkaBootstrap,
                                      String inputTopic, String outputTopic,
                                      int batchInterval, BotDetector detector) {
        this.appName = appName;
        this.kafkaBootstrap = kafkaBootstrap;
        this.inputTopic = inputTopic;
        this.outputTopic = outputTopic;
        this.batchInterval = batchInterval;
        this.detector = detector;
        
        initializeTransientFields();
        initializeStreamingContext();
    }
    
    private void initializeTransientFields() {
        this.gson = new Gson();
        this.running = new AtomicBoolean(false);
        this.eventsProcessed = new AtomicLong(0);
        this.botsDetected = new AtomicLong(0);
        this.startTime = System.currentTimeMillis();
    }
    
    private void initializeStreamingContext() {
        try {
            SparkConf sparkConf = new SparkConf()
                .setAppName(appName)
                .setMaster("local[*]")
                .set("spark.serializer", "org.apache.spark.serializer.JavaSerializer")
                .set("spark.executor.memory", "2g")
                .set("spark.driver.memory", "2g")
                .set("spark.streaming.backpressure.enabled", "true")
                .set("spark.streaming.kafka.maxRatePerPartition", "1000")
                .set("spark.ui.enabled", "false")
                .set("spark.network.timeout", "300s")
                .set("spark.executor.heartbeatInterval", "60s");
            
            streamingContext = new JavaStreamingContext(sparkConf, Durations.milliseconds(batchInterval));
            
            logger.info("Spark Streaming Context initialized successfully");
            
        } catch (Exception e) {
            logger.error("Error initializing Spark Streaming", e);
            throw new RuntimeException("Failed to initialize processor", e);
        }
    }
    
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting Spark Streaming processor...");
            try {
                Map<String, Object> kafkaParams = new HashMap<>();
                kafkaParams.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
                kafkaParams.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                kafkaParams.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                kafkaParams.put(ConsumerConfig.GROUP_ID_CONFIG, "botsense-consumer-group");
                kafkaParams.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
                kafkaParams.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
                kafkaParams.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
                
                Collection<String> topics = Collections.singletonList(inputTopic);
                JavaInputDStream<ConsumerRecord<String, String>> kafkaStream = 
                    KafkaUtils.createDirectStream(
                        streamingContext,
                        LocationStrategies.PreferConsistent(),
                        ConsumerStrategies.Subscribe(topics, kafkaParams)
                    );
                
                JavaDStream<String> jsonStream = kafkaStream.map(ConsumerRecord::value);
                
                // Traiter les événements dans le driver
                jsonStream.foreachRDD(rdd -> {
                    if (!rdd.isEmpty()) {
                        long batchSize = rdd.count();
                        logger.info("Processing batch of {} events", batchSize);
                        
                        // Collecter les événements dans le driver
                        List<String> jsonEvents = rdd.collect();
                        
                        // Traiter chaque événement
                        for (String jsonEvent : jsonEvents) {
                            try {
                                TrafficEvent event = gson.fromJson(jsonEvent, TrafficEvent.class);
                                
                                if (event != null) {
                                    boolean isBot = detector.detect(event);
                                    
                                    eventsProcessed.incrementAndGet();
                                    if (isBot) {
                                        botsDetected.incrementAndGet();
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Error processing event: {}", e.getMessage());
                            }
                        }
                        
                        // Log des statistiques
                        long processed = eventsProcessed.get();
                        long bots = botsDetected.get();
                        double botRate = processed > 0 ? (bots * 100.0 / processed) : 0.0;
                        double accuracy = detector.getAccuracy() * 100;
                        
                        logger.info(String.format(
                            "Stats - Processed: %d, Bots: %d (%.2f%%), Accuracy: %.2f%%",
                            processed, bots, botRate, accuracy
                        ));
                    }
                });
                
                streamingContext.start();
                logger.info("Spark Streaming started successfully");
                
            } catch (Exception e) {
                logger.error("Error starting Spark Streaming", e);
                running.set(false);
                throw new RuntimeException("Failed to start processor", e);
            }
        }
    }
    
    public void awaitTermination() {
        try {
            if (streamingContext != null) {
                streamingContext.awaitTermination();
            }
        } catch (InterruptedException e) {
            logger.warn("Streaming context interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    public boolean awaitTermination(long timeout) {
        try {
            if (streamingContext != null) {
                return streamingContext.awaitTerminationOrTimeout(timeout);
            }
            return false;
        } catch (InterruptedException e) {
            logger.warn("Streaming context interrupted", e);
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping Spark Streaming processor...");
            try {
                if (streamingContext != null) {
                    streamingContext.stop(true, true);
                    streamingContext = null;
                }
                logger.info("Spark Streaming stopped. Processed {} events, detected {} bots",
                          eventsProcessed.get(), botsDetected.get());
            } catch (Exception e) {
                logger.error("Error stopping Spark Streaming", e);
            }
        }
    }
    
    public ProcessorStatistics getStatistics() {
        long uptime = System.currentTimeMillis() - startTime;
        double throughput = eventsProcessed.get() > 0 ?
            (eventsProcessed.get() * 1000.0 / uptime) : 0.0;
        
        return new ProcessorStatistics(
            eventsProcessed.get(),
            botsDetected.get(),
            throughput,
            uptime,
            running.get(),
            detector != null ? detector.getStatistics().toString() : "Detector not available"
        );
    }
    
    @Override
    public void close() {
        stop();
    }
    
    // Getters
    public boolean isRunning() {
        return running != null && running.get();
    }
    
    public long getEventsProcessed() {
        return eventsProcessed != null ? eventsProcessed.get() : 0;
    }
    
    public long getBotsDetected() {
        return botsDetected != null ? botsDetected.get() : 0;
    }
    
    public BotDetector getDetector() {
        return detector;
    }
    
    /**
     * Statistiques du processeur
     */
    public static class ProcessorStatistics implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final long eventsProcessed;
        private final long botsDetected;
        private final double throughput;
        private final long uptime;
        private final boolean running;
        private final String detectorStatistics;
        
        public ProcessorStatistics(long eventsProcessed, long botsDetected,
                                 double throughput, long uptime, boolean running,
                                 String detectorStatistics) {
            this.eventsProcessed = eventsProcessed;
            this.botsDetected = botsDetected;
            this.throughput = throughput;
            this.uptime = uptime;
            this.running = running;
            this.detectorStatistics = detectorStatistics;
        }
        
        // Getters
        public long getEventsProcessed() { return eventsProcessed; }
        public long getBotsDetected() { return botsDetected; }
        public double getThroughput() { return throughput; }
        public long getUptime() { return uptime; }
        public boolean isRunning() { return running; }
        public String getDetectorStatistics() { return detectorStatistics; }
        
        public double getBotRate() {
            return eventsProcessed > 0 ? 
                (double) botsDetected / eventsProcessed : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ProcessorStats{processed=%d, bots=%d, throughput=%.1f msg/s, running=%b}",
                eventsProcessed, botsDetected, throughput, running
            );
        }
    }
}