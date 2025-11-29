package com.botsense.stream.streaming.kafka;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.botsense.stream.core.TrafficEvent;
import com.botsense.stream.generator.TrafficGenerator;
import com.google.gson.Gson;

/**
 * Producteur Kafka pour envoyer les événements de trafic
 * Génère et envoie continuellement des événements au topic Kafka
 */
public class TrafficKafkaProducer implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TrafficKafkaProducer.class);
    
    private KafkaProducer<String, String> producer;
    private TrafficGenerator generator;
    private String topic;
    private Gson gson;
    
    private AtomicBoolean running;
    private AtomicLong messagesSent;
    private AtomicLong sendErrors;
    private ExecutorService executorService;
    
    private int targetRate;  // messages par seconde
    private long startTime;
    
    public TrafficKafkaProducer(String bootstrapServers, String topic, 
                               TrafficGenerator generator) {
        this(bootstrapServers, topic, generator, 1000);
    }
    
    public TrafficKafkaProducer(String bootstrapServers, String topic,
                               TrafficGenerator generator, int targetRate) {
        this.topic = topic;
        this.generator = generator;
        this.targetRate = targetRate;
        this.gson = new Gson();
        this.running = new AtomicBoolean(false);
        this.messagesSent = new AtomicLong(0);
        this.sendErrors = new AtomicLong(0);
        this.executorService = Executors.newSingleThreadExecutor();
        
        // Configuration du producteur Kafka
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
                 StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
                 StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        
        this.producer = new KafkaProducer<>(props);
        this.startTime = System.currentTimeMillis();
        
        logger.info("Kafka producer initialized for topic: {}", topic);
    }
    
    /**
     * Démarre la production de messages
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting traffic producer at {} msg/s", targetRate);
            executorService.submit(this::produceMessages);
        }
    }
    
    /**
     * Boucle principale de production
     */
    private void produceMessages() {
        long intervalMs = 1000 / targetRate;
        
        while (running.get()) {
            try {
                long iterationStart = System.currentTimeMillis();
                
                // Générer et envoyer un événement
                TrafficEvent event = generator.generateEvent();
                sendEvent(event);
                
                // Contrôle du débit
                long elapsed = System.currentTimeMillis() - iterationStart;
                long sleepTime = intervalMs - elapsed;
                
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
                
            } catch (InterruptedException e) {
                logger.warn("Producer interrupted", e);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error producing message", e);
                sendErrors.incrementAndGet();
            }
        }
        
        logger.info("Producer stopped");
    }
    
    /**
     * Envoie un événement au topic Kafka
     */
    public void sendEvent(TrafficEvent event) {
        try {
            String json = gson.toJson(event);
            String key = event.getSessionId();
            
            ProducerRecord<String, String> record = 
                new ProducerRecord<>(topic, key, json);
            
            // Envoi asynchrone avec callback
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    logger.error("Error sending message", exception);
                    sendErrors.incrementAndGet();
                } else {
                    messagesSent.incrementAndGet();
                    if (messagesSent.get() % 1000 == 0) {
                        logger.info("Sent {} messages (partition: {}, offset: {})",
                                  messagesSent.get(), 
                                  metadata.partition(), 
                                  metadata.offset());
                    }
                }
            });
            
        } catch (Exception e) {
            logger.error("Error serializing event", e);
            sendErrors.incrementAndGet();
        }
    }
    
    /**
     * Envoie un événement de manière synchrone
     */
    public RecordMetadata sendEventSync(TrafficEvent event) throws Exception {
        String json = gson.toJson(event);
        String key = event.getSessionId();
        
        ProducerRecord<String, String> record = 
            new ProducerRecord<>(topic, key, json);
        
        Future<RecordMetadata> future = producer.send(record);
        RecordMetadata metadata = future.get();
        messagesSent.incrementAndGet();
        
        return metadata;
    }
    
    /**
     * Arrête la production
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping producer...");
            
            // Attendre que le thread se termine
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Flush les messages en attente
            producer.flush();
            
            logger.info("Producer stopped. Total messages sent: {}, errors: {}",
                       messagesSent.get(), sendErrors.get());
        }
    }
    
    /**
     * Change le débit de production
     */
    public void setTargetRate(int targetRate) {
        this.targetRate = Math.max(1, targetRate);
        logger.info("Target rate changed to {} msg/s", targetRate);
    }
    
    /**
     * Force une dérive de comportement
     */
    public void triggerDrift() {
        generator.forceDrift();
        logger.info("Drift triggered manually");
    }
    
    /**
     * Retourne les statistiques du producteur
     */
    public ProducerStatistics getStatistics() {
        long uptime = System.currentTimeMillis() - startTime;
        double throughput = messagesSent.get() > 0 ? 
            (messagesSent.get() * 1000.0 / uptime) : 0.0;
        
        return new ProducerStatistics(
            messagesSent.get(),
            sendErrors.get(),
            throughput,
            targetRate,
            uptime,
            running.get(),
            generator.getStatistics()
        );
    }
    
    @Override
    public void close() {
        stop();
        if (producer != null) {
            producer.close();
        }
        executorService.shutdown();
    }
    
    // Getters
    public boolean isRunning() {
        return running.get();
    }
    
    public long getMessagesSent() {
        return messagesSent.get();
    }
    
    public long getSendErrors() {
        return sendErrors.get();
    }
    
    public int getTargetRate() {
        return targetRate;
    }
    
    /**
     * Statistiques du producteur
     */
    public static class ProducerStatistics {
        private long messagesSent;
        private long sendErrors;
        private double throughput;
        private int targetRate;
        private long uptime;
        private boolean running;
        private TrafficGenerator.GeneratorStatistics generatorStats;
        
        public ProducerStatistics(long messagesSent, long sendErrors, 
                                double throughput, int targetRate, long uptime,
                                boolean running, 
                                TrafficGenerator.GeneratorStatistics generatorStats) {
            this.messagesSent = messagesSent;
            this.sendErrors = sendErrors;
            this.throughput = throughput;
            this.targetRate = targetRate;
            this.uptime = uptime;
            this.running = running;
            this.generatorStats = generatorStats;
        }
        
        // Getters
        public long getMessagesSent() { return messagesSent; }
        public long getSendErrors() { return sendErrors; }
        public double getThroughput() { return throughput; }
        public int getTargetRate() { return targetRate; }
        public long getUptime() { return uptime; }
        public boolean isRunning() { return running; }
        public TrafficGenerator.GeneratorStatistics getGeneratorStats() { 
            return generatorStats; 
        }
        
        public double getErrorRate() {
            long total = messagesSent + sendErrors;
            return total > 0 ? (double) sendErrors / total : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ProducerStats{sent=%d, errors=%d, throughput=%.1f msg/s, target=%d msg/s}",
                messagesSent, sendErrors, throughput, targetRate
            );
        }
    }
}