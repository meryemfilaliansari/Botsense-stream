package com.botsense.stream.core.model;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.commons.math3.distribution.PoissonDistribution;

import com.botsense.stream.core.TrafficEvent;

/**
 * Implémentation du Online Bagging
 * Ensemble de classificateurs Hoeffding Tree avec vote majoritaire
 */
public class OnlineBaggingEnsemble implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private List<HoeffdingTreeClassifier> ensemble;
    private int ensembleSize;
    private PoissonDistribution poissonDistribution;
    
    // Métriques
    private AtomicLong instancesProcessed;
    private AtomicLong correctPredictions;
    private ConcurrentHashMap<Integer, Double> modelAccuracies;
    
    // Statistiques d'ensemble
    private double ensembleAccuracy;
    private long lastUpdateTime;
    
    public OnlineBaggingEnsemble() {
        this(10);
    }
    
    public OnlineBaggingEnsemble(int ensembleSize) {
        this.ensembleSize = ensembleSize;
        this.ensemble = new ArrayList<>();
        this.instancesProcessed = new AtomicLong(0);
        this.correctPredictions = new AtomicLong(0);
        this.modelAccuracies = new ConcurrentHashMap<>();
        this.poissonDistribution = new PoissonDistribution(1.0);
        
        initializeEnsemble();
    }
    
    /**
     * Initialise l'ensemble de classificateurs
     */
    private void initializeEnsemble() {
        ensemble.clear();
        modelAccuracies.clear();
        
        for (int i = 0; i < ensembleSize; i++) {
            HoeffdingTreeClassifier classifier = new HoeffdingTreeClassifier(
                200,      // grace period
                0.0001,   // split confidence
                0.05      // tie threshold
            );
            ensemble.add(classifier);
            modelAccuracies.put(i, 0.0);
        }
    }
    
    /**
     * Entraîne l'ensemble sur une nouvelle instance
     * Utilise le tirage de Poisson pour le bagging online
     */
    public void train(TrafficEvent event) {
        for (int i = 0; i < ensembleSize; i++) {
            // Tirage de Poisson(1) pour déterminer le nombre de fois 
            // qu'on entraîne ce classificateur
            int k = poissonDistribution.sample();
            
            HoeffdingTreeClassifier classifier = ensemble.get(i);
            for (int j = 0; j < k; j++) {
                classifier.train(event);
            }
        }
        
        instancesProcessed.incrementAndGet();
    }
    
    /**
     * Prédit la classe par vote majoritaire
     */
    public boolean predict(TrafficEvent event) {
        int botVotes = 0;
        int humanVotes = 0;
        double totalConfidence = 0.0;
        
        for (HoeffdingTreeClassifier classifier : ensemble) {
            boolean prediction = classifier.predict(event);
            if (prediction) {
                botVotes++;
            } else {
                humanVotes++;
            }
            totalConfidence += event.getConfidence();
        }
        
        // Calculer la confiance moyenne
        double avgConfidence = totalConfidence / ensembleSize;
        event.setConfidence(avgConfidence);
        
        // Vote majoritaire
        return botVotes > humanVotes;
    }
    
    /**
     * Prédit avec pondération par accuracy
     */
    public boolean predictWeighted(TrafficEvent event) {
        double botScore = 0.0;
        double humanScore = 0.0;
        double totalWeight = 0.0;
        
        for (int i = 0; i < ensembleSize; i++) {
            HoeffdingTreeClassifier classifier = ensemble.get(i);
            double weight = modelAccuracies.getOrDefault(i, 0.5);
            
            boolean prediction = classifier.predict(event);
            if (prediction) {
                botScore += weight;
            } else {
                humanScore += weight;
            }
            totalWeight += weight;
        }
        
        // Normaliser et calculer confiance
        if (totalWeight > 0) {
            double confidence = botScore / totalWeight;
            event.setConfidence(confidence);
            return botScore > humanScore;
        }
        
        // Fallback sur vote simple
        return predict(event);
    }
    
    /**
     * Entraîne et évalue (prequential evaluation)
     */
    public boolean trainAndPredict(TrafficEvent event) {
        // Prédire d'abord
        boolean prediction = predictWeighted(event);
        
        // Puis entraîner
        train(event);
        
        // Mettre à jour les métriques
        if (prediction == event.isBot()) {
            correctPredictions.incrementAndGet();
        }
        
        updateMetrics();
        
        return prediction;
    }
    
    /**
     * Met à jour les métriques de l'ensemble
     */
    private void updateMetrics() {
        long processed = instancesProcessed.get();
        if (processed > 0) {
            ensembleAccuracy = (double) correctPredictions.get() / processed;
        }
        
        // Mettre à jour les accuracies individuelles
        for (int i = 0; i < ensembleSize; i++) {
            HoeffdingTreeClassifier classifier = ensemble.get(i);
            modelAccuracies.put(i, classifier.getCurrentAccuracy());
        }
        
        lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Retourne les prédictions de chaque modèle (pour analyse)
     */
    public List<Boolean> getAllPredictions(TrafficEvent event) {
        return ensemble.stream()
            .map(classifier -> classifier.predict(event))
            .collect(Collectors.toList());
    }
    
    /**
     * Retourne le modèle le plus performant
     */
    public HoeffdingTreeClassifier getBestModel() {
        int bestIndex = 0;
        double bestAccuracy = 0.0;
        
        for (int i = 0; i < ensembleSize; i++) {
            double accuracy = modelAccuracies.getOrDefault(i, 0.0);
            if (accuracy > bestAccuracy) {
                bestAccuracy = accuracy;
                bestIndex = i;
            }
        }
        
        return ensemble.get(bestIndex);
    }
    
    /**
     * Remplace le modèle le moins performant par un nouveau
     */
    public void replaceWorstModel() {
        int worstIndex = 0;
        double worstAccuracy = Double.MAX_VALUE;
        
        for (int i = 0; i < ensembleSize; i++) {
            double accuracy = modelAccuracies.getOrDefault(i, 1.0);
            if (accuracy < worstAccuracy) {
                worstAccuracy = accuracy;
                worstIndex = i;
            }
        }
        
        // Remplacer par un nouveau modèle
        HoeffdingTreeClassifier newClassifier = new HoeffdingTreeClassifier();
        ensemble.set(worstIndex, newClassifier);
        modelAccuracies.put(worstIndex, 0.0);
    }
    
    /**
     * Réinitialise l'ensemble
     */
    public void reset() {
        initializeEnsemble();
        instancesProcessed.set(0);
        correctPredictions.set(0);
        ensembleAccuracy = 0.0;
    }
    
    /**
     * Sauvegarde l'ensemble
     */
    public void saveEnsemble(String filepath) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(filepath))) {
            oos.writeObject(this);
        }
    }
    
    /**
     * Charge un ensemble
     */
    public static OnlineBaggingEnsemble loadEnsemble(String filepath) 
            throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(filepath))) {
            return (OnlineBaggingEnsemble) ois.readObject();
        }
    }
    
    /**
     * Retourne les statistiques de l'ensemble
     */
    public EnsembleStatistics getStatistics() {
        List<HoeffdingTreeClassifier.ModelStatistics> modelStats = 
            ensemble.stream()
                .map(HoeffdingTreeClassifier::getStatistics)
                .collect(Collectors.toList());
        
        return new EnsembleStatistics(
            instancesProcessed.get(),
            correctPredictions.get(),
            ensembleAccuracy,
            modelStats,
            modelAccuracies,
            lastUpdateTime
        );
    }
    
    // Getters
    public int getEnsembleSize() {
        return ensembleSize;
    }
    
    public double getEnsembleAccuracy() {
        return ensembleAccuracy;
    }
    
    public long getInstancesProcessed() {
        return instancesProcessed.get();
    }
    
    public List<HoeffdingTreeClassifier> getEnsemble() {
        return new ArrayList<>(ensemble);
    }
    
    /**
     * Classe interne pour les statistiques de l'ensemble
     */
    public static class EnsembleStatistics implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private long instancesProcessed;
        private long correctPredictions;
        private double ensembleAccuracy;
        private List<HoeffdingTreeClassifier.ModelStatistics> modelStatistics;
        private ConcurrentHashMap<Integer, Double> modelAccuracies;
        private long lastUpdateTime;
        
        public EnsembleStatistics(long instancesProcessed, long correctPredictions,
                                double ensembleAccuracy,
                                List<HoeffdingTreeClassifier.ModelStatistics> modelStatistics,
                                ConcurrentHashMap<Integer, Double> modelAccuracies,
                                long lastUpdateTime) {
            this.instancesProcessed = instancesProcessed;
            this.correctPredictions = correctPredictions;
            this.ensembleAccuracy = ensembleAccuracy;
            this.modelStatistics = modelStatistics;
            this.modelAccuracies = modelAccuracies;
            this.lastUpdateTime = lastUpdateTime;
        }
        
        // Getters
        public long getInstancesProcessed() { return instancesProcessed; }
        public long getCorrectPredictions() { return correctPredictions; }
        public double getEnsembleAccuracy() { return ensembleAccuracy; }
        public List<HoeffdingTreeClassifier.ModelStatistics> getModelStatistics() { 
            return modelStatistics; 
        }
        public ConcurrentHashMap<Integer, Double> getModelAccuracies() { 
            return modelAccuracies; 
        }
        public long getLastUpdateTime() { return lastUpdateTime; }
        
        public double getAverageModelAccuracy() {
            return modelAccuracies.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        }
        
        public double getMinModelAccuracy() {
            return modelAccuracies.values().stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0.0);
        }
        
        public double getMaxModelAccuracy() {
            return modelAccuracies.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
        }
        
        @Override
        public String toString() {
            return String.format(
                "EnsembleStats{instances=%d, accuracy=%.3f, models=%d, avgModelAcc=%.3f}",
                instancesProcessed, ensembleAccuracy, modelStatistics.size(),
                getAverageModelAccuracy()
            );
        }
    }
}