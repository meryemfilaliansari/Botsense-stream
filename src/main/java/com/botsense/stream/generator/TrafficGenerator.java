package com.botsense.stream.generator;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.botsense.stream.core.TrafficEvent;

/**
 * Générateur de flux de trafic simulé
 * Crée des événements de trafic réalistes avec des bots et utilisateurs légitimes
 * Implémente des dérives de comportement pour tester l'adaptation
 */
public class TrafficGenerator {
    private static final Logger logger = LoggerFactory.getLogger(TrafficGenerator.class);
    
    private Random random;
    private double botRatio;
    private boolean evolutionEnabled;
    private AtomicLong eventsGenerated;
    private long driftInterval;
    private long lastDriftTime;
    private int currentBehaviorPhase;
    
    // Paramètres de génération
    private static final String[] USER_AGENTS_HUMAN = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15"
    };
    
    private static final String[] USER_AGENTS_BOT = {
        "Mozilla/5.0 (compatible; Googlebot/2.1)",
        "Python-urllib/3.8",
        "curl/7.68.0",
        "Scrapy/2.5.0",
        "Bot/1.0"
    };
    
    public TrafficGenerator() {
        this(0.3, true, 300000);
    }
    
    public TrafficGenerator(double botRatio, boolean evolutionEnabled, 
                           long driftInterval) {
        this.random = new Random();
        this.botRatio = botRatio;
        this.evolutionEnabled = evolutionEnabled;
        this.driftInterval = driftInterval;
        this.eventsGenerated = new AtomicLong(0);
        this.lastDriftTime = System.currentTimeMillis();
        this.currentBehaviorPhase = 0;
    }
    
    /**
     * Génère un événement de trafic
     */
    public TrafficEvent generateEvent() {
        // Vérifier si dérive nécessaire
        if (evolutionEnabled && shouldTriggerDrift()) {
            triggerBehaviorDrift();
        }
        
        boolean isBot = random.nextDouble() < botRatio;
        TrafficEvent event;
        
        if (isBot) {
            event = generateBotTraffic();
        } else {
            event = generateHumanTraffic();
        }
        
        event.setBot(isBot);
        eventsGenerated.incrementAndGet();
        
        return event;
    }
    
    /**
     * Génère un trafic humain réaliste
     */
    private TrafficEvent generateHumanTraffic() {
        String sessionId = UUID.randomUUID().toString();
        String ipAddress = generateRandomIP();
        String userAgent = USER_AGENTS_HUMAN[random.nextInt(USER_AGENTS_HUMAN.length)];
        
        TrafficEvent event = new TrafficEvent(sessionId, ipAddress, userAgent);
        
        // Comportement humain typique
        event.setRequestsPerSecond(random.nextInt(5) + 1);  // 1-5 req/s
        event.setAvgResponseTime(100 + random.nextDouble() * 200);  // 100-300ms
        event.setUniqueEndpoints(random.nextInt(10) + 3);  // 3-12 endpoints
        event.setClickRate(0.6 + random.nextDouble() * 0.35);  // 0.6-0.95
        event.setScrollDepth(0.4 + random.nextDouble() * 0.5);  // 0.4-0.9
        event.setSessionDuration(60 + random.nextInt(600));  // 1-10 minutes
        event.setPageViewsPerSession(3 + random.nextDouble() * 7);  // 3-10 pages
        event.setErrorRate(random.nextInt(3));  // 0-2 erreurs
        event.setTimeBetweenRequests(1000 + random.nextDouble() * 5000);  // 1-6s
        event.setDistinctHeaders(random.nextInt(5) + 10);  // 10-14 headers
        
        event.setJavascriptEnabled(random.nextDouble() > 0.05);  // 95% activé
        event.setCookiesEnabled(random.nextDouble() > 0.1);  // 90% activé
        event.setScreenResolution(1920 + random.nextInt(1000));
        event.setHttpVersion(random.nextDouble() > 0.3 ? "HTTP/2" : "HTTP/1.1");
        
        // Ajouter du bruit
        addNoise(event);
        
        return event;
    }
    
    /**
     * Génère un trafic bot avec évolution selon la phase
     */
    private TrafficEvent generateBotTraffic() {
        String sessionId = UUID.randomUUID().toString();
        String ipAddress = generateRandomIP();
        String userAgent = USER_AGENTS_BOT[random.nextInt(USER_AGENTS_BOT.length)];
        
        TrafficEvent event = new TrafficEvent(sessionId, ipAddress, userAgent);
        
        // Comportement bot avec évolution
        double sophisticationFactor = currentBehaviorPhase * 0.2;
        
        // Phase 0: Bots basiques
        // Phase 1: Bots modérés
        // Phase 2: Bots sophistiqués
        
        int baseRps = 10 + random.nextInt(40);
        event.setRequestsPerSecond((int)(baseRps * (1 - sophisticationFactor * 0.5)));
        
        event.setAvgResponseTime(10 + random.nextDouble() * 50);  // Très rapide
        event.setUniqueEndpoints(random.nextInt(30) + 5);  // Beaucoup d'endpoints
        
        // Les bots sophistiqués imitent mieux les humains
        event.setClickRate(0.1 + sophisticationFactor * 0.5);
        event.setScrollDepth(sophisticationFactor * 0.4);
        event.setSessionDuration(10 + random.nextInt(100 + (int)(sophisticationFactor * 400)));
        event.setPageViewsPerSession(10 + random.nextDouble() * 40);
        event.setErrorRate(random.nextInt(10) + 5);
        
        double baseTimeBetween = 50 + random.nextDouble() * 200;
        event.setTimeBetweenRequests(baseTimeBetween * (1 + sophisticationFactor * 2));
        
        event.setDistinctHeaders(random.nextInt(5) + 3);  // Peu de headers
        
        // Les bots sophistiqués activent JS et cookies
        event.setJavascriptEnabled(random.nextDouble() < sophisticationFactor);
        event.setCookiesEnabled(random.nextDouble() < sophisticationFactor * 0.8);
        event.setScreenResolution(1024 + random.nextInt(500));
        event.setHttpVersion("HTTP/1.1");
        
        // Ajouter du bruit
        addNoise(event);
        
        return event;
    }
    
    /**
     * Ajoute du bruit réaliste aux données
     */
    private void addNoise(TrafficEvent event) {
        double noise = (random.nextDouble() - 0.5) * 0.2;  // -10% à +10%
        
        event.setAvgResponseTime(event.getAvgResponseTime() * (1 + noise));
        event.setClickRate(Math.max(0, Math.min(1, event.getClickRate() * (1 + noise))));
        event.setScrollDepth(Math.max(0, Math.min(1, event.getScrollDepth() * (1 + noise))));
    }
    
    /**
     * Vérifie si une dérive doit être déclenchée
     */
    private boolean shouldTriggerDrift() {
        long currentTime = System.currentTimeMillis();
        return (currentTime - lastDriftTime) >= driftInterval;
    }
    
    /**
     * Déclenche une dérive de comportement
     */
    private void triggerBehaviorDrift() {
        currentBehaviorPhase = (currentBehaviorPhase + 1) % 3;
        lastDriftTime = System.currentTimeMillis();
        
        String[] phases = {"Basique", "Modéré", "Sophistiqué"};
        logger.info("DRIFT TRIGGERED: Bots passent en phase {} ({})", 
                   currentBehaviorPhase, phases[currentBehaviorPhase]);
        
        // Ajuster le ratio de bots lors de la dérive
        if (random.nextDouble() < 0.3) {
            double oldRatio = botRatio;
            botRatio = Math.max(0.1, Math.min(0.7, botRatio + (random.nextDouble() - 0.5) * 0.2));
            logger.info("Bot ratio changed: {:.2f} -> {:.2f}", oldRatio, botRatio);
        }
    }
    
    /**
     * Génère une adresse IP aléatoire
     */
    private String generateRandomIP() {
        return String.format("%d.%d.%d.%d",
            random.nextInt(256),
            random.nextInt(256),
            random.nextInt(256),
            random.nextInt(256)
        );
    }
    
    /**
     * Force une dérive immédiate
     */
    public void forceDrift() {
        triggerBehaviorDrift();
    }
    
    /**
     * Réinitialise le générateur
     */
    public void reset() {
        eventsGenerated.set(0);
        currentBehaviorPhase = 0;
        lastDriftTime = System.currentTimeMillis();
    }
    
    // Getters et Setters
    public double getBotRatio() {
        return botRatio;
    }
    
    public void setBotRatio(double botRatio) {
        this.botRatio = Math.max(0.0, Math.min(1.0, botRatio));
    }
    
    public boolean isEvolutionEnabled() {
        return evolutionEnabled;
    }
    
    public void setEvolutionEnabled(boolean evolutionEnabled) {
        this.evolutionEnabled = evolutionEnabled;
    }
    
    public long getEventsGenerated() {
        return eventsGenerated.get();
    }
    
    public int getCurrentBehaviorPhase() {
        return currentBehaviorPhase;
    }
    
    public long getDriftInterval() {
        return driftInterval;
    }
    
    public void setDriftInterval(long driftInterval) {
        this.driftInterval = driftInterval;
    }
    
    public long getTimeSinceLastDrift() {
        return System.currentTimeMillis() - lastDriftTime;
    }
    
    /**
     * Génère un batch d'événements
     */
    public TrafficEvent[] generateBatch(int size) {
        TrafficEvent[] batch = new TrafficEvent[size];
        for (int i = 0; i < size; i++) {
            batch[i] = generateEvent();
        }
        return batch;
    }
    
    /**
     * Retourne des statistiques de génération
     */
    public GeneratorStatistics getStatistics() {
        return new GeneratorStatistics(
            eventsGenerated.get(),
            botRatio,
            currentBehaviorPhase,
            evolutionEnabled,
            driftInterval,
            System.currentTimeMillis() - lastDriftTime
        );
    }
    
    /**
     * Statistiques du générateur
     */
    public static class GeneratorStatistics {
        private long eventsGenerated;
        private double botRatio;
        private int behaviorPhase;
        private boolean evolutionEnabled;
        private long driftInterval;
        private long timeSinceLastDrift;
        
        public GeneratorStatistics(long eventsGenerated, double botRatio,
                                 int behaviorPhase, boolean evolutionEnabled,
                                 long driftInterval, long timeSinceLastDrift) {
            this.eventsGenerated = eventsGenerated;
            this.botRatio = botRatio;
            this.behaviorPhase = behaviorPhase;
            this.evolutionEnabled = evolutionEnabled;
            this.driftInterval = driftInterval;
            this.timeSinceLastDrift = timeSinceLastDrift;
        }
        
        // Getters
        public long getEventsGenerated() { return eventsGenerated; }
        public double getBotRatio() { return botRatio; }
        public int getBehaviorPhase() { return behaviorPhase; }
        public boolean isEvolutionEnabled() { return evolutionEnabled; }
        public long getDriftInterval() { return driftInterval; }
        public long getTimeSinceLastDrift() { return timeSinceLastDrift; }
        
        @Override
        public String toString() {
            return String.format(
                "GeneratorStats{generated=%d, botRatio=%.2f, phase=%d, evolution=%b}",
                eventsGenerated, botRatio, behaviorPhase, evolutionEnabled
            );
        }
    }
}