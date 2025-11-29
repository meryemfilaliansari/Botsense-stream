package com.botsense.stream.core.drift;

import moa.classifiers.core.driftdetection.ADWIN;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Détecteur de dérive de concept utilisant ADWIN
 * Surveille l'évolution des performances et détecte les changements de distribution
 */
public class DriftDetector implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(DriftDetector.class);
    
    private ADWIN adwin;
    private double warningLevel;
    private double driftLevel;
    
    // Statistiques
    private AtomicLong totalChecks;
    private AtomicLong warningsDetected;
    private AtomicLong driftsDetected;
    private List<DriftEvent> driftHistory;
    
    // État
    private boolean inWarningZone;
    private long lastDriftTime;
    private double currentErrorRate;
    
    public DriftDetector() {
        this(0.002);
    }
    
    public DriftDetector(double delta) {
        // CORRECTION : ADWIN prend le delta dans le constructeur, pas avec setDelta()
        this.adwin = new ADWIN(delta);
        this.warningLevel = 0.05;
        this.driftLevel = 0.001;
        this.totalChecks = new AtomicLong(0);
        this.warningsDetected = new AtomicLong(0);
        this.driftsDetected = new AtomicLong(0);
        this.driftHistory = new ArrayList<>();
        this.inWarningZone = false;
        this.lastDriftTime = System.currentTimeMillis();
        this.currentErrorRate = 0.0;
    }
    
    /**
     * Ajoute une observation et vérifie la dérive
     * @param prediction prédiction du modèle
     * @param actual valeur réelle
     * @return état de détection
     */
    public DriftDetectionResult addObservation(boolean prediction, boolean actual) {
        totalChecks.incrementAndGet();
        
        // Calculer l'erreur (0 = correct, 1 = erreur)
        double error = (prediction == actual) ? 0.0 : 1.0;
        
        // Ajouter à ADWIN
        boolean changeDetected = adwin.setInput(error);
        currentErrorRate = adwin.getEstimation();
        
        DriftDetectionResult result = new DriftDetectionResult();
        result.setTimestamp(System.currentTimeMillis());
        result.setErrorRate(currentErrorRate);
        result.setWindowLength(adwin.getWidth());
        
        // Vérifier les seuils
        if (changeDetected) {
            result.setDriftDetected(true);
            driftsDetected.incrementAndGet();
            inWarningZone = false;
            
            DriftEvent driftEvent = new DriftEvent(
                System.currentTimeMillis(),
                totalChecks.get(),
                currentErrorRate,
                DriftType.DRIFT
            );
            driftHistory.add(driftEvent);
            lastDriftTime = System.currentTimeMillis();
            
            logger.warn("DRIFT DETECTED! Error rate: {}, Window: {}", 
                       currentErrorRate, adwin.getWidth());
            
        } else if (currentErrorRate > warningLevel && !inWarningZone) {
            result.setWarningDetected(true);
            warningsDetected.incrementAndGet();
            inWarningZone = true;
            
            DriftEvent warningEvent = new DriftEvent(
                System.currentTimeMillis(),
                totalChecks.get(),
                currentErrorRate,
                DriftType.WARNING
            );
            driftHistory.add(warningEvent);
            
            logger.info("Warning zone entered. Error rate: {}", currentErrorRate);
            
        } else if (currentErrorRate <= warningLevel && inWarningZone) {
            inWarningZone = false;
            logger.info("Warning zone exited. Error rate: {}", currentErrorRate);
        }
        
        result.setInWarningZone(inWarningZone);
        
        return result;
    }
    
    /**
     * Vérifie si un réapprentissage est nécessaire
     */
    public boolean shouldRetrain() {
        return !driftHistory.isEmpty() && 
               driftHistory.get(driftHistory.size() - 1).getType() == DriftType.DRIFT;
    }
    
    /**
     * Réinitialise le détecteur après réapprentissage
     */
    public void reset() {
        // CORRECTION : Recréer ADWIN avec le driftLevel comme delta
        this.adwin = new ADWIN(driftLevel);
        inWarningZone = false;
        currentErrorRate = 0.0;
        logger.info("Drift detector reset after retraining");
    }
    
    /**
     * Réinitialise complètement les statistiques
     */
    public void fullReset() {
        reset();
        totalChecks.set(0);
        warningsDetected.set(0);
        driftsDetected.set(0);
        driftHistory.clear();
        lastDriftTime = System.currentTimeMillis();
    }
    
    /**
     * Retourne les statistiques du détecteur
     */
    public DriftStatistics getStatistics() {
        return new DriftStatistics(
            totalChecks.get(),
            warningsDetected.get(),
            driftsDetected.get(),
            currentErrorRate,
            adwin.getWidth(),
            inWarningZone,
            lastDriftTime,
            new ArrayList<>(driftHistory)
        );
    }
    
    /**
     * Retourne les dérives récentes
     */
    public List<DriftEvent> getRecentDrifts(int count) {
        int size = driftHistory.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(driftHistory.subList(start, size));
    }
    
    /**
     * Calcule le temps depuis la dernière dérive
     */
    public long getTimeSinceLastDrift() {
        return System.currentTimeMillis() - lastDriftTime;
    }
    
    // Getters et Setters
    public double getCurrentErrorRate() {
        return currentErrorRate;
    }
    
    public boolean isInWarningZone() {
        return inWarningZone;
    }
    
    public long getTotalChecks() {
        return totalChecks.get();
    }
    
    public long getWarningsDetected() {
        return warningsDetected.get();
    }
    
    public long getDriftsDetected() {
        return driftsDetected.get();
    }
    
    public List<DriftEvent> getDriftHistory() {
        return new ArrayList<>(driftHistory);
    }
    
    public double getWarningLevel() {
        return warningLevel;
    }
    
    public void setWarningLevel(double warningLevel) {
        this.warningLevel = warningLevel;
    }
    
    public double getDriftLevel() {
        return driftLevel;
    }
    
    public void setDriftLevel(double driftLevel) {
        this.driftLevel = driftLevel;
    }
    
    /**
     * Type de dérive
     */
    public enum DriftType {
        WARNING,
        DRIFT
    }
    
    /**
     * Événement de dérive
     */
    public static class DriftEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final long timestamp;
        private final long instanceNumber;
        private final double errorRate;
        private final DriftType type;
        
        public DriftEvent(long timestamp, long instanceNumber, 
                         double errorRate, DriftType type) {
            this.timestamp = timestamp;
            this.instanceNumber = instanceNumber;
            this.errorRate = errorRate;
            this.type = type;
        }
        
        public long getTimestamp() { return timestamp; }
        public long getInstanceNumber() { return instanceNumber; }
        public double getErrorRate() { return errorRate; }
        public DriftType getType() { return type; }
        
        @Override
        public String toString() {
            return String.format("%s at instance %d (error: %.3f)", 
                               type, instanceNumber, errorRate);
        }
    }
    
    /**
     * Résultat de détection de dérive
     */
    public static class DriftDetectionResult implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private long timestamp;
        private boolean driftDetected;
        private boolean warningDetected;
        private boolean inWarningZone;
        private double errorRate;
        private int windowLength;
        
        public DriftDetectionResult() {
            this.driftDetected = false;
            this.warningDetected = false;
            this.inWarningZone = false;
        }
        
        // Getters et Setters
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        
        public boolean isDriftDetected() { return driftDetected; }
        public void setDriftDetected(boolean driftDetected) { 
            this.driftDetected = driftDetected; 
        }
        
        public boolean isWarningDetected() { return warningDetected; }
        public void setWarningDetected(boolean warningDetected) { 
            this.warningDetected = warningDetected; 
        }
        
        public boolean isInWarningZone() { return inWarningZone; }
        public void setInWarningZone(boolean inWarningZone) { 
            this.inWarningZone = inWarningZone; 
        }
        
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
        
        public int getWindowLength() { return windowLength; }
        public void setWindowLength(int windowLength) { 
            this.windowLength = windowLength; 
        }
        
        @Override
        public String toString() {
            return String.format(
                "DriftResult{drift=%b, warning=%b, error=%.3f, window=%d}",
                driftDetected, warningDetected, errorRate, windowLength
            );
        }
    }
    
    /**
     * Statistiques de dérive
     */
    public static class DriftStatistics implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final long totalChecks;
        private final long warningsDetected;
        private final long driftsDetected;
        private final double currentErrorRate;
        private final int currentWindowLength;
        private final boolean inWarningZone;
        private final long lastDriftTime;
        private final List<DriftEvent> driftHistory;
        
        public DriftStatistics(long totalChecks, long warningsDetected, 
                             long driftsDetected, double currentErrorRate,
                             int currentWindowLength, boolean inWarningZone,
                             long lastDriftTime, List<DriftEvent> driftHistory) {
            this.totalChecks = totalChecks;
            this.warningsDetected = warningsDetected;
            this.driftsDetected = driftsDetected;
            this.currentErrorRate = currentErrorRate;
            this.currentWindowLength = currentWindowLength;
            this.inWarningZone = inWarningZone;
            this.lastDriftTime = lastDriftTime;
            this.driftHistory = driftHistory;
        }
        
        // Getters
        public long getTotalChecks() { return totalChecks; }
        public long getWarningsDetected() { return warningsDetected; }
        public long getDriftsDetected() { return driftsDetected; }
        public double getCurrentErrorRate() { return currentErrorRate; }
        public int getCurrentWindowLength() { return currentWindowLength; }
        public boolean isInWarningZone() { return inWarningZone; }
        public long getLastDriftTime() { return lastDriftTime; }
        public List<DriftEvent> getDriftHistory() { return driftHistory; }
        
        public double getDriftRate() {
            return totalChecks > 0 ? (double) driftsDetected / totalChecks : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "DriftStats{checks=%d, warnings=%d, drifts=%d, error=%.3f, window=%d}",
                totalChecks, warningsDetected, driftsDetected, 
                currentErrorRate, currentWindowLength
            );
        }
    }
}