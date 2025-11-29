package com.botsense.stream.core.model;

import com.botsense.stream.core.TrafficEvent;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Classifieur basé sur des règles pour la détection de bots
 * Alternative au Hoeffding Tree quand MOA n'est pas disponible
 */
public class HoeffdingTreeClassifier implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final AtomicLong instancesProcessed;
    private final AtomicLong correctPredictions;
    
    // Paramètres du modèle
    private int gracePeriod;
    private double splitConfidence;
    private double tieThreshold;
    
    // Métriques
    private double currentAccuracy;
    private long lastUpdateTime;
    
    // Règles de décision apprises
    private List<DecisionRule> rules;
    private double[] featureWeights;
    private double detectionThreshold;
    
    // Statistiques des features pour normalisation
    private double[] featureMeans;
    private double[] featureStdDevs;
    private boolean isTrained;
    
    public HoeffdingTreeClassifier() {
        this(200, 0.0001, 0.05);
    }
    
    public HoeffdingTreeClassifier(int gracePeriod, double splitConfidence, 
                                   double tieThreshold) {
        this.gracePeriod = gracePeriod;
        this.splitConfidence = splitConfidence;
        this.tieThreshold = tieThreshold;
        this.instancesProcessed = new AtomicLong(0);
        this.correctPredictions = new AtomicLong(0);
        this.currentAccuracy = 0.0;
        this.lastUpdateTime = System.currentTimeMillis();
        this.rules = new ArrayList<>();
        this.detectionThreshold = 0.7;
        this.isTrained = false;
        
        initializeWeights();
    }
    
    /**
     * Initialise les poids des features
     */
    private void initializeWeights() {
        String[] featureNames = TrafficEvent.getFeatureNames();
        this.featureWeights = new double[featureNames.length];
        this.featureMeans = new double[featureNames.length];
        this.featureStdDevs = new double[featureNames.length];
        
        // Initialisation uniforme
        for (int i = 0; i < featureWeights.length; i++) {
            featureWeights[i] = 1.0 / featureWeights.length;
        }
    }
    
    /**
     * Entraîne le modèle sur une nouvelle instance
     */
    public void train(TrafficEvent event) {
        double[] features = event.toFeatureVector();
        boolean actualClass = event.isBot();
        
        // Mettre à jour les statistiques des features
        updateFeatureStatistics(features);
        
        // Apprendre une nouvelle règle si nécessaire
        if (instancesProcessed.get() % gracePeriod == 0) {
            learnNewRule(features, actualClass);
        }
        
        // Ajuster les poids des features
        adjustFeatureWeights(features, actualClass);
        
        instancesProcessed.incrementAndGet();
        isTrained = true;
    }
    
    /**
     * Prédit la classe d'un événement
     * @return true si bot détecté, false sinon
     */
    public boolean predict(TrafficEvent event) {
        double[] features = event.toFeatureVector();
        double score = calculateScore(features);
        
        // Calculer la confiance de prédiction
        double confidence = Math.abs(score - 0.5) * 2; // Normaliser entre 0 et 1
        event.setConfidence(confidence);
        
        return score > detectionThreshold;
    }
    
    /**
     * Entraîne et évalue (prequential evaluation)
     */
    public boolean trainAndPredict(TrafficEvent event) {
        // Prédire d'abord
        boolean prediction = predict(event);
        
        // Puis entraîner
        train(event);
        
        // Mettre à jour les métriques
        if (prediction == event.isBot()) {
            correctPredictions.incrementAndGet();
        }
        
        updateAccuracy();
        
        return prediction;
    }
    
    /**
     * Calcule le score de détection
     */
    private double calculateScore(double[] features) {
        if (!isTrained || rules.isEmpty()) {
            return 0.5; // Score neutre si non entraîné
        }
        
        double score = 0.0;
        double totalWeight = 0.0;
        
        // Appliquer les règles
        for (DecisionRule rule : rules) {
            if (rule.matches(features)) {
                score += rule.getWeight() * rule.getConfidence();
                totalWeight += rule.getWeight();
            }
        }
        
        // Appliquer les poids des features
        double featureScore = 0.0;
        for (int i = 0; i < features.length; i++) {
            double normalizedFeature = normalizeFeature(features[i], i);
            featureScore += normalizedFeature * featureWeights[i];
        }
        
        // Combiner les scores
        if (totalWeight > 0) {
            double ruleScore = score / totalWeight;
            return 0.7 * ruleScore + 0.3 * featureScore;
        } else {
            return featureScore;
        }
    }
    
    /**
     * Normalise une feature
     */
    private double normalizeFeature(double value, int featureIndex) {
        if (featureStdDevs[featureIndex] == 0) {
            return 0.0;
        }
        return (value - featureMeans[featureIndex]) / featureStdDevs[featureIndex];
    }
    
    /**
     * Met à jour les statistiques des features
     */
    private void updateFeatureStatistics(double[] features) {
        long n = instancesProcessed.get() + 1;
        
        for (int i = 0; i < features.length; i++) {
            double oldMean = featureMeans[i];
            featureMeans[i] = oldMean + (features[i] - oldMean) / n;
            
            if (n > 1) {
                double oldStdDev = featureStdDevs[i];
                double newVariance = ((n - 2) * oldStdDev * oldStdDev + 
                                    (features[i] - oldMean) * (features[i] - featureMeans[i])) / (n - 1);
                featureStdDevs[i] = Math.sqrt(Math.max(newVariance, 0));
            }
        }
    }
    
    /**
     * Apprend une nouvelle règle
     */
    private void learnNewRule(double[] features, boolean actualClass) {
        // Implémentation simplifiée d'apprentissage de règles
        DecisionRule newRule = new DecisionRule(features, actualClass);
        
        // Vérifier la confiance de la règle
        if (newRule.getConfidence() > splitConfidence) {
            rules.add(newRule);
            
            // Limiter le nombre de règles
            if (rules.size() > 100) {
                rules.remove(0); // Supprimer la plus ancienne
            }
        }
    }
    
    /**
     * Ajuste les poids des features
     */
    private void adjustFeatureWeights(double[] features, boolean actualClass) {
        boolean prediction = calculateScore(features) > detectionThreshold;
        
        if (prediction != actualClass) {
            // Ajuster les poids en fonction de l'erreur
            for (int i = 0; i < features.length; i++) {
                double errorContribution = Math.abs(features[i] * featureWeights[i]);
                if (actualClass) {
                    featureWeights[i] += errorContribution * 0.01;
                } else {
                    featureWeights[i] -= errorContribution * 0.01;
                }
                
                // Garder les poids entre 0 et 1
                featureWeights[i] = Math.max(0, Math.min(1, featureWeights[i]));
            }
            
            // Normaliser les poids
            normalizeWeights();
        }
    }
    
    /**
     * Normalise les poids pour qu'ils somment à 1
     */
    private void normalizeWeights() {
        double sum = 0.0;
        for (double weight : featureWeights) {
            sum += weight;
        }
        
        if (sum > 0) {
            for (int i = 0; i < featureWeights.length; i++) {
                featureWeights[i] /= sum;
            }
        }
    }
    
    /**
     * Met à jour l'accuracy courante
     */
    private void updateAccuracy() {
        long processed = instancesProcessed.get();
        if (processed > 0) {
            currentAccuracy = (double) correctPredictions.get() / processed;
        }
        lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Réinitialise le modèle
     */
    public void reset() {
        instancesProcessed.set(0);
        correctPredictions.set(0);
        currentAccuracy = 0.0;
        rules.clear();
        initializeWeights();
        isTrained = false;
    }
    
    /**
     * Sauvegarde le modèle
     */
    public void saveModel(String filepath) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(filepath))) {
            oos.writeObject(this);
        }
    }
    
    /**
     * Charge un modèle
     */
    public static HoeffdingTreeClassifier loadModel(String filepath) 
            throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(filepath))) {
            return (HoeffdingTreeClassifier) ois.readObject();
        }
    }
    
    /**
     * Retourne des statistiques sur le modèle
     */
    public ModelStatistics getStatistics() {
        return new ModelStatistics(
            instancesProcessed.get(),
            correctPredictions.get(),
            currentAccuracy,
            rules.size(), // profondeur approximative
            estimateModelSize(),
            lastUpdateTime
        );
    }
    
    /**
     * Estime la taille du modèle en bytes
     */
    private double estimateModelSize() {
        return rules.size() * 100.0 + // ~100 bytes par règle
               featureWeights.length * 8.0 + // poids des features
               featureMeans.length * 8.0 + // moyennes
               featureStdDevs.length * 8.0; // écarts-types
    }
    
    // Getters
    public double getCurrentAccuracy() {
        return currentAccuracy;
    }
    
    public long getInstancesProcessed() {
        return instancesProcessed.get();
    }
    
    public long getCorrectPredictions() {
        return correctPredictions.get();
    }
    
    public int getGracePeriod() {
        return gracePeriod;
    }
    
    public double getSplitConfidence() {
        return splitConfidence;
    }
    
    public double getTieThreshold() {
        return tieThreshold;
    }
    
    public int getRuleCount() {
        return rules.size();
    }
    
    /**
     * Règle de décision simple
     */
    private static class DecisionRule implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final double[] thresholds;
        private final boolean outcome;
        private final double confidence;
        private double weight;
        
        public DecisionRule(double[] features, boolean outcome) {
            this.thresholds = features.clone();
            this.outcome = outcome;
            this.confidence = 0.8; // Confiance initiale
            this.weight = 1.0;
        }
        
        public boolean matches(double[] features) {
            int matches = 0;
            for (int i = 0; i < features.length; i++) {
                if (Math.abs(features[i] - thresholds[i]) < 0.1) {
                    matches++;
                }
            }
            return matches >= features.length / 2;
        }
        
        public boolean getOutcome() { return outcome; }
        public double getConfidence() { return confidence; }
        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }
    }
    
    /**
     * Classe interne pour les statistiques du modèle
     */
    public static class ModelStatistics implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final long instancesProcessed;
        private final long correctPredictions;
        private final double accuracy;
        private final double treeDepth;
        private final double modelSize;
        private final long lastUpdateTime;
        
        public ModelStatistics(long instancesProcessed, long correctPredictions,
                             double accuracy, double treeDepth, double modelSize,
                             long lastUpdateTime) {
            this.instancesProcessed = instancesProcessed;
            this.correctPredictions = correctPredictions;
            this.accuracy = accuracy;
            this.treeDepth = treeDepth;
            this.modelSize = modelSize;
            this.lastUpdateTime = lastUpdateTime;
        }
        
        // Getters
        public long getInstancesProcessed() { return instancesProcessed; }
        public long getCorrectPredictions() { return correctPredictions; }
        public double getAccuracy() { return accuracy; }
        public double getTreeDepth() { return treeDepth; }
        public double getModelSize() { return modelSize; }
        public long getLastUpdateTime() { return lastUpdateTime; }
        
        @Override
        public String toString() {
            return String.format(
                "ModelStats{instances=%d, correct=%d, accuracy=%.3f, depth=%.1f, size=%.0f bytes}",
                instancesProcessed, correctPredictions, accuracy, treeDepth, modelSize
            );
        }
    }
}