package com.botsense.stream.core.detector;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.botsense.stream.core.TrafficEvent;
import com.botsense.stream.core.drift.DriftDetector;
import com.botsense.stream.core.model.OnlineBaggingEnsemble;

/**
 * Détecteur de bots principal
 * Intègre le modèle d'ensemble, la détection de dérive et le réapprentissage automatique
 */
public class BotDetector implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(BotDetector.class);
    
    private OnlineBaggingEnsemble ensemble;
    private DriftDetector driftDetector;
    
    private double detectionThreshold;
    private int updateFrequency;
    private boolean driftDetectionEnabled;
    
    // Métriques
    private AtomicLong detectionsPerformed;
    private AtomicLong truePositives;
    private AtomicLong trueNegatives;
    private AtomicLong falsePositives;
    private AtomicLong falseNegatives;
    
    // Statistiques de performance
    private double currentPrecision;
    private double currentRecall;
    private double currentF1Score;
    private long lastMetricsUpdate;
    
    public BotDetector() {
        this(10, 0.7, 1000, true);
    }
    
    public BotDetector(int ensembleSize, double detectionThreshold,
                      int updateFrequency, boolean driftDetectionEnabled) {
        this.ensemble = new OnlineBaggingEnsemble(ensembleSize);
        this.driftDetector = new DriftDetector();
        this.detectionThreshold = detectionThreshold;
        this.updateFrequency = updateFrequency;
        this.driftDetectionEnabled = driftDetectionEnabled;
        
        this.detectionsPerformed = new AtomicLong(0);
        this.truePositives = new AtomicLong(0);
        this.trueNegatives = new AtomicLong(0);
        this.falsePositives = new AtomicLong(0);
        this.falseNegatives = new AtomicLong(0);
        
        this.currentPrecision = 0.0;
        this.currentRecall = 0.0;
        this.currentF1Score = 0.0;
        this.lastMetricsUpdate = System.currentTimeMillis();
        
        logger.info("BotDetector initialized with ensemble size: {}, threshold: {}",
                   ensembleSize, detectionThreshold);
    }
    
    /**
     * Détecte si un événement est un bot
     * @param event événement à analyser
     * @return true si bot détecté, false sinon
     */
    public boolean detect(TrafficEvent event) {
        // Prédiction avec l'ensemble
        boolean prediction = ensemble.predictWeighted(event);
        
        // Appliquer le seuil de confiance
        boolean isBot = prediction && event.getConfidence() >= detectionThreshold;
        
        detectionsPerformed.incrementAndGet();
        
        // Entraîner le modèle si le label réel est disponible
        if (event.isBot() || !event.isBot()) {  // Label disponible
            trainAndUpdateMetrics(event, isBot);
        }
        
        // Mettre à jour les métriques périodiquement
        if (detectionsPerformed.get() % updateFrequency == 0) {
            updatePerformanceMetrics();
        }
        
        return isBot;
    }
    
    /**
     * Entraîne le modèle et met à jour les métriques
     */
    private void trainAndUpdateMetrics(TrafficEvent event, boolean prediction) {
        boolean actual = event.isBot();
        
        // Mettre à jour la matrice de confusion
        if (prediction && actual) {
            truePositives.incrementAndGet();
        } else if (!prediction && !actual) {
            trueNegatives.incrementAndGet();
        } else if (prediction && !actual) {
            falsePositives.incrementAndGet();
        } else {
            falseNegatives.incrementAndGet();
        }
        
        // Entraîner l'ensemble
        ensemble.trainAndPredict(event);
        
        // Détection de dérive si activée
        if (driftDetectionEnabled) {
            DriftDetector.DriftDetectionResult driftResult = 
                driftDetector.addObservation(prediction, actual);
            
            if (driftResult.isDriftDetected()) {
                handleDrift();
            }
        }
    }
    
    /**
     * Gère la détection d'une dérive
     */
    private void handleDrift() {
        logger.warn("DRIFT DETECTED - Initiating model adaptation");
        
        // Stratégie 1: Remplacer le modèle le moins performant
        ensemble.replaceWorstModel();
        
        // Stratégie 2: Réinitialiser le détecteur de dérive
        driftDetector.reset();
        
        logger.info("Model adapted to drift. Continuing detection.");
    }
    
    /**
     * Met à jour les métriques de performance
     */
    private void updatePerformanceMetrics() {
        long tp = truePositives.get();
        long tn = trueNegatives.get();
        long fp = falsePositives.get();
        long fn = falseNegatives.get();
        
        // Précision: TP / (TP + FP)
        currentPrecision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
        
        // Rappel: TP / (TP + FN)
        currentRecall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
        
        // F1-Score: 2 * (Precision * Recall) / (Precision + Recall)
        if (currentPrecision + currentRecall > 0) {
            currentF1Score = 2 * (currentPrecision * currentRecall) / 
                           (currentPrecision + currentRecall);
        } else {
            currentF1Score = 0.0;
        }
        
        lastMetricsUpdate = System.currentTimeMillis();
        
        // Log des métriques
        if (detectionsPerformed.get() % (updateFrequency * 10) == 0) {
            logger.info("Performance Metrics - Precision: {:.3f}, Recall: {:.3f}, F1: {:.3f}",
                       currentPrecision, currentRecall, currentF1Score);
        }
    }
    
    /**
     * Retourne l'accuracy globale
     */
    public double getAccuracy() {
        long total = truePositives.get() + trueNegatives.get() + 
                    falsePositives.get() + falseNegatives.get();
        if (total == 0) return 0.0;
        
        return (double) (truePositives.get() + trueNegatives.get()) / total;
    }
    
    /**
     * Réinitialise le détecteur
     */
    public void reset() {
        ensemble.reset();
        driftDetector.fullReset();
        
        detectionsPerformed.set(0);
        truePositives.set(0);
        trueNegatives.set(0);
        falsePositives.set(0);
        falseNegatives.set(0);
        
        currentPrecision = 0.0;
        currentRecall = 0.0;
        currentF1Score = 0.0;
        
        logger.info("BotDetector reset");
    }
    
    /**
     * Sauvegarde le détecteur
     */
    public void save(String filepath) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(filepath))) {
            oos.writeObject(this);
            logger.info("Detector saved to: {}", filepath);
        }
    }
    
    /**
     * Charge un détecteur
     */
    public static BotDetector load(String filepath) 
            throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(filepath))) {
            BotDetector detector = (BotDetector) ois.readObject();
            logger.info("Detector loaded from: {}", filepath);
            return detector;
        }
    }
    
    /**
     * Retourne les statistiques complètes
     */
    public String getStatistics() {
        DetectorStatistics stats = getDetailedStatistics();
        return stats.toString();
    }
    
    /**
     * Retourne les statistiques détaillées
     */
    public DetectorStatistics getDetailedStatistics() {
        return new DetectorStatistics(
            detectionsPerformed.get(),
            truePositives.get(),
            trueNegatives.get(),
            falsePositives.get(),
            falseNegatives.get(),
            currentPrecision,
            currentRecall,
            currentF1Score,
            getAccuracy(),
            ensemble.getStatistics(),
            driftDetector.getStatistics(),
            lastMetricsUpdate
        );
    }
    
    // Getters
    public double getDetectionThreshold() {
        return detectionThreshold;
    }
    
    public void setDetectionThreshold(double threshold) {
        this.detectionThreshold = Math.max(0.0, Math.min(1.0, threshold));
    }
    
    public boolean isDriftDetectionEnabled() {
        return driftDetectionEnabled;
    }
    
    public void setDriftDetectionEnabled(boolean enabled) {
        this.driftDetectionEnabled = enabled;
    }
    
    public long getDetectionsPerformed() {
        return detectionsPerformed.get();
    }
    
    public double getPrecision() {
        return currentPrecision;
    }
    
    public double getRecall() {
        return currentRecall;
    }
    
    public double getF1Score() {
        return currentF1Score;
    }
    
    public OnlineBaggingEnsemble getEnsemble() {
        return ensemble;
    }
    
    public DriftDetector getDriftDetector() {
        return driftDetector;
    }
    
    /**
     * Statistiques complètes du détecteur
     */
    public static class DetectorStatistics implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private long detectionsPerformed;
        private long truePositives;
        private long trueNegatives;
        private long falsePositives;
        private long falseNegatives;
        private double precision;
        private double recall;
        private double f1Score;
        private double accuracy;
        private OnlineBaggingEnsemble.EnsembleStatistics ensembleStats;
        private DriftDetector.DriftStatistics driftStats;
        private long lastUpdate;
        
        public DetectorStatistics(long detectionsPerformed, long truePositives,
                                long trueNegatives, long falsePositives,
                                long falseNegatives, double precision, double recall,
                                double f1Score, double accuracy,
                                OnlineBaggingEnsemble.EnsembleStatistics ensembleStats,
                                DriftDetector.DriftStatistics driftStats,
                                long lastUpdate) {
            this.detectionsPerformed = detectionsPerformed;
            this.truePositives = truePositives;
            this.trueNegatives = trueNegatives;
            this.falsePositives = falsePositives;
            this.falseNegatives = falseNegatives;
            this.precision = precision;
            this.recall = recall;
            this.f1Score = f1Score;
            this.accuracy = accuracy;
            this.ensembleStats = ensembleStats;
            this.driftStats = driftStats;
            this.lastUpdate = lastUpdate;
        }
        
        // Getters
        public long getDetectionsPerformed() { return detectionsPerformed; }
        public long getTruePositives() { return truePositives; }
        public long getTrueNegatives() { return trueNegatives; }
        public long getFalsePositives() { return falsePositives; }
        public long getFalseNegatives() { return falseNegatives; }
        public double getPrecision() { return precision; }
        public double getRecall() { return recall; }
        public double getF1Score() { return f1Score; }
        public double getAccuracy() { return accuracy; }
        public OnlineBaggingEnsemble.EnsembleStatistics getEnsembleStats() { 
            return ensembleStats; 
        }
        public DriftDetector.DriftStatistics getDriftStats() { 
            return driftStats; 
        }
        public long getLastUpdate() { return lastUpdate; }
        
        @Override
        public String toString() {
            return String.format(
                "Detector{detections=%d, accuracy=%.3f, precision=%.3f, recall=%.3f, f1=%.3f, drifts=%d}",
                detectionsPerformed, accuracy, precision, recall, f1Score,
                driftStats.getDriftsDetected()
            );
        }
    }
}