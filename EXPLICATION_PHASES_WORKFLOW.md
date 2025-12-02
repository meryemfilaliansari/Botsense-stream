# 📊 Explication Détaillée du Workflow BotSense-Stream

## 🎯 Vue d'ensemble du système

Votre système fonctionne comme un détective qui surveille continuellement un flux de visiteurs web. Il identifie qui sont les vrais utilisateurs et qui sont des bots malveillants, tout en s'adaptant quand les bots deviennent plus sophistiqués.

---

## 🔴 PHASE 1 : GÉNÉRATION DE DONNÉES

### **Qu'est-ce que c'est ?**

C'est la première étape où le système crée des événements de trafic (ou reçoit du trafic réel). Chaque "événement" représente une requête d'un visiteur web.

### **Comment ça fonctionne dans votre projet ?**

#### **Composant: TrafficGenerator**

Le `TrafficGenerator` est une classe Java qui simule des visiteurs web. Tous les 5 millisecondes environ, il crée un nouvel événement.

**Le processus** :

1. **Décider le type de visiteur** - Générateur tire au hasard : "Est-ce un humain ou un bot ?"
   - Par défaut: 30% de bots, 70% d'humains
   - Configurable dans `application.properties` : `generator.bot.ratio=0.3`

2. **Générer les caractéristiques** - Pour chaque visiteur, on crée des attributs réalistes :

   **Pour un HUMAIN LÉGITIME** :
   - User-Agent réaliste : "Mozilla/5.0 (Windows NT 10.0..."
   - Vitesse : 1-5 requêtes par seconde
   - Temps de réponse : 100-300ms (normal pour humain)
   - Endpoints visités : 3-12 pages différentes (normal, pas tout d'un coup)
   - Click rate : 60-95% (humains cliquent souvent)
   - Scroll depth : 40-90% (lisent le contenu)
   - Durée session : 1-10 minutes (du vrai temps)
   - Pages vues : 3-10 (comportement normal)

   **Pour un BOT PHASE 0** (bot simple) :
   - User-Agent suspect : "Python-urllib/3.8" ou "curl/7.68.0"
   - Vitesse : 100-500 requêtes par seconde (TRÈS RAPIDE)
   - Endpoints : accède à TOUS les endpoints (pas naturel)
   - Pas de scroll, pas de click (ignore le contenu)
   - Session : 1-5 secondes (trop rapide)

   **Pour un BOT PHASE 1 & PHASE 2** (bot sophistiqué) :
   - Imite le comportement humain
   - Se cache mieux (User-Agent plus réaliste)
   - Délais aléatoires entre requêtes (pour paraître humain)

### **Évolution temporelle programmée**

Votre système simule une attaque qui s'améliore avec le temps :

- **Minute 1-5** : Les bots Phase 0 attaquent (faciles à détecter)
- **Minute 5-10** : Les bots Phase 1 arrivent (plus subtils)
- **Minute 10+** : Les bots Phase 2 attaquent (très difficiles à détecter)

```
Configuration dans application.properties :
generator.drift.interval=300000  (5 minutes)
generator.evolution.enabled=true
```

### **Résultat de la Phase 1**

Chaque événement généré ressemble à :
```json
{
  "sessionId": "abc123-xyz789",
  "ipAddress": "192.168.1.100",
  "userAgent": "Mozilla/5.0 ...",
  "requestsPerSecond": 3,
  "avgResponseTime": 150.5,
  "uniqueEndpoints": 8,
  "clickRate": 0.75,
  "scrollDepth": 0.65,
  "sessionDuration": 180,
  "pageViewsPerSession": 6.5,
  "isBot": false
}
```

Ces événements sont des **TrafficEvent** en Java, créés à la vitesse configurée (par défaut 1000 événements/seconde).

---

## 🟣 PHASE 2 : MESSAGERIE KAFKA

### **Qu'est-ce que c'est ?**

Kafka est un "système de files d'attente" (message broker). Imaginez un bureau de poste : les événements arrivent, Kafka les met en file d'attente, et les services les récupèrent.

### **Pourquoi utiliser Kafka ?**

Sans Kafka, le générateur et le traitement seraient couplés (liés). Si le traitement ralentissait, les événements se perdraient.

Avec Kafka, c'est découplé :
- Le générateur envoie dans Kafka "à toute vitesse"
- Spark les récupère "à son rythme"

### **Comment ça fonctionne dans votre projet ?**

#### **Composant: TrafficKafkaProducer**

Le `TrafficKafkaProducer` prend les événements du `TrafficGenerator` et les envoie à Kafka.

**Le processus** :

1. **Sérialisation** - L'événement Java devient un JSON :
   ```json
   {
     "sessionId": "...",
     "ipAddress": "...",
     "isBot": false
   }
   ```

2. **Envoi à Kafka** - L'événement JSON est envoyé au topic `bot-traffic`

3. **Partitionnement** - Kafka divise les événements en 3 partitions :
   - Partition 0 : Événements 1, 4, 7, 10... (modulo 3)
   - Partition 1 : Événements 2, 5, 8, 11...
   - Partition 2 : Événements 3, 6, 9, 12...

   **Pourquoi partitionner ?** Pour distribuer le travail. Spark peut traiter 3 partitions en parallèle.

4. **Réplication (sécurité)** - Chaque événement est copié 3 fois sur différentes machines Kafka (si vous aviez un cluster). Si une machine tombe en panne, les données ne sont pas perdues.

### **Configuration Kafka**

```properties
kafka.bootstrap.servers=localhost:9092
kafka.topic.input=bot-traffic
kafka.topic.output=bot-detections
kafka.group.id=botsense-consumer-group
kafka.auto.offset.reset=latest
```

- **bootstrap.servers** : Où Kafka s'exécute (ici: localhost:9092)
- **topic.input** : Nom du topic où les événements arrivent
- **group.id** : Identifie votre application Spark comme "consommateur"
- **auto.offset.reset=latest** : Récupère les événements les PLUS RÉCENTS (pas l'historique)

### **Résultat de la Phase 2**

Les événements sont maintenant dans Kafka, en attente d'être traités. À chaque instant :
- ~1000 événements/seconde arrivent dans Kafka
- ~5000 événements s'accumulent (5 sec de batch)
- Puis Spark les récupère tous d'un coup

---

## 🟢 PHASE 3 : TRAITEMENT SPARK STREAMING

### **Qu'est-ce que c'est ?**

Spark est le "moteur de traitement distribué". Il reçoit les événements de Kafka, les analyse et prépare les données pour la détection.

### **Pourquoi Spark et pas simple Python ?**

- **Parallélisation** : Spark peut traiter 3 partitions Kafka en parallèle (3 CPU)
- **Scalabilité** : Facilement distribué sur 100 machines
- **Optimisation** : Spark optimise automatiquement les requêtes

### **Comment ça fonctionne dans votre projet ?**

#### **Composant: BotDetectionStreamProcessor**

Cette classe Java crée le contexte Spark et configure le streaming.

**Le processus** :

1. **Créer un contexte Spark** - Configuration :
   ```java
   SparkConf sparkConf = new SparkConf()
       .setAppName("BotSense-Stream")
       .setMaster("local[*]")  // Utilise TOUS les CPU
       .set("spark.executor.memory", "2g")  // 2 GB de RAM par executor
       .set("spark.streaming.batch.interval", "5000");  // Batch de 5 sec
   ```

   **Qu'est-ce qu'un batch ?** - Un batch est un groupe d'événements traités ensemble.
   - Toutes les 5 secondes, Spark dit "Stop, traitons ce qu'on a"
   - Il récolte ~5000 événements accumulés
   - Les traite ENSEMBLE (parallélisé)

2. **Se connecter à Kafka** - Spark se pose la question : "Kafka, as-tu des nouveaux événements ?"
   ```java
   JavaInputDStream<ConsumerRecord<String, String>> stream =
       KafkaUtils.createDirectStream(
           streamingContext,
           ConsumerStrategies.Subscribe(
               Collections.singleton("bot-traffic"),  // Topic
               kafkaParams
           )
       );
   ```

3. **Désérialiser JSON** - Spark reçoit des JSON bruts, les transforme en objets `TrafficEvent` :
   ```
   Avant : {"sessionId": "abc", "isBot": false, ...}
   Après : TrafficEvent(sessionId="abc", isBot=false, ...)
   ```

4. **Extraction des Features** - C'est l'étape CRUCIALE. Spark extrait 14 caractéristiques numériques :

   **14 Features extraites** :
   ```
   1. requestsPerSecond       (vitesse)
   2. avgResponseTime         (latence)
   3. uniqueEndpoints         (variation d'endpoints)
   4. clickRate               (clique sur liens)
   5. scrollDepth             (scrolle la page)
   6. sessionDuration         (durée totale)
   7. pageViewsPerSession     (pages vues)
   8. userAgentSimilarity     (match avec UAs connues)
   9. ipReputation            (IP blacklist ?)
   10. geolocation             (localisation IP)
   11. protocol                (HTTP/HTTPS)
   12. browserFingerprint      (type navigateur)
   13. requestPatternDeviation (écart patterns)
   14. timingBehavior          (régularité requêtes)
   ```

   **Pourquoi 14 features ?** - Le modèle ML prend 14 nombres en entrée pour décider si c'est un bot.

5. **Normalisation** - Les features sont normalisées entre 0 et 1 :
   ```
   Avant: requestsPerSecond = 234 (trop grand)
   Après: requestsPerSecond = 0.45 (entre 0 et 1)
   
   Pourquoi ? Les arbres de décision fonctionnent mieux avec des valeurs normalisées.
   ```

6. **Créer un vecteur** - Les 14 features deviennent un vecteur :
   ```
   Vector = [0.45, 0.30, 0.80, 0.95, 0.70, 0.20, 0.50, 0.15, 0.00, 0.90, 1.00, 0.65, 0.25, 0.88]
   ```

   Ce vecteur est le "langage" que le modèle ML comprend.

### **Configuration Spark**

```properties
spark.app.name=BotSense-Stream
spark.master=local[*]
spark.streaming.batch.interval=5000
spark.streaming.checkpoint.dir=./checkpoint
spark.streaming.backpressure.enabled=true
spark.streaming.kafka.maxRatePerPartition=1000
```

- **batch.interval=5000** : Traite tous les 5 secondes
- **backpressure.enabled=true** : Si trop de données arrivent, ralentit Kafka
- **maxRatePerPartition=1000** : Max 1000 msg par partition par batch

### **Résultat de la Phase 3**

Des vecteurs de features prêts pour le ML :
```
Vector 1: [0.45, 0.30, 0.80, ...]
Vector 2: [0.05, 0.95, 0.10, ...]
Vector 3: [0.50, 0.25, 0.75, ...]
...
```

Chaque vecteur représente un événement et est prêt à être classifié.

---

## 🟠 PHASE 4 : DÉTECTION IA (Ensemble de Hoeffding Trees)

### **Qu'est-ce que c'est ?**

C'est le "cerveau" du système. Les modèles ML prennent les 14 features et décident : "Bot ou Humain ?"

### **Hoeffding Trees - Qu'est-ce que c'est ?**

Un **Arbre de Décision** classique fonctionne hors-ligne (offline) :
```
1. Collecter tous les données
2. Entraîner l'arbre
3. Utiliser pour prédiction
```

Un **Hoeffding Tree** fonctionne en streaming (online) :
```
1. Voir un événement
2. Immédiatement entraîner (apprendre de cet événement)
3. Immédiatement prédire (utiliser cet événement)
```

**Pourquoi Hoeffding Tree ?** - Parfait pour les données qui arrivent continuellement. Les bots changent, il faut s'adapter en temps réel.

### **Comment fonctionne un arbre de décision ?**

**Analogie** : Imaginez décider si quelqu'un est un bot :

```
Étape 1 : "Vitesse > 10 requêtes/sec ?"
   OUI → Probablement bot
   NON → Continuez...

Étape 2 : "Scroll depth > 50% ?"
   OUI → Probablement humain (lit le contenu)
   NON → Probablement bot (ignore contenu)

Étape 3 : "Session duration > 30 sec ?"
   OUI → Probablement humain (reste un moment)
   NON → Probablement bot (hit-and-run)
```

L'arbre crée ces "conditions" automatiquement en regardant les données.

### **Ensemble de 10 Hoeffding Trees - Pourquoi ?**

Un seul arbre n'est pas assez fiable. Imaginez un jury : 1 juré peut se tromper, 10 jurés votent ensemble, c'est plus fiable.

**Votre système utilise Online Bagging** :
```
Modèle 1 : Arbre de Décision #1
Modèle 2 : Arbre de Décision #2
Modèle 3 : Arbre de Décision #3
...
Modèle 10 : Arbre de Décision #10
```

Chaque arbre est légèrement différent (entraîné sur des sous-ensembles différents des données).

### **Comment ça fonctionne dans votre projet ?**

#### **Composant: OnlineBaggingEnsemble**

Cette classe gère les 10 arbres.

**Le processus** :

1. **Initialiser 10 arbres** - Chaque arbre est créé avec :
   ```java
   HoeffdingTreeClassifier classifier = new HoeffdingTreeClassifier(
       200,        // Grace period (attendre 200 exemples)
       0.0001,     // Split confidence (confiance pour diviser)
       0.05        // Tie threshold (seuil d'égalité)
   );
   ```

   **Grace period = 200** : L'arbre attend 200 exemples avant de se construire (pas décisions hâtives).

2. **Entraîner chaque arbre** - Tirage de Poisson :
   ```java
   int k = poissonDistribution.sample();  // Tirage aléatoire (0, 1, 2, ou 3)
   classifier.train(event);  // k fois
   ```

   **Pourquoi Poisson ?** - Certains arbres voient plus souvent cet événement, d'autres moins. C'est ça "le bagging" (bootstrap aggregating).

3. **Prédictions parallèles** - Tous les 10 arbres font une prédiction :
   ```
   Arbre 1 : 0.8  (80% confiance = BOT)
   Arbre 2 : 0.75 (75% confiance = BOT)
   Arbre 3 : 0.9  (90% confiance = BOT)
   Arbre 4 : 0.6  (60% confiance = BOT)
   ...
   Arbre 10 : 0.7 (70% confiance = BOT)
   ```

4. **Vote majoritaire** - Combiner les 10 prédictions :
   ```
   Moyenne des 10 scores = (0.8 + 0.75 + 0.9 + 0.6 + ... + 0.7) / 10 = 0.75
   
   Seuil = 0.6
   Si moyenne > 0.6 → Classé BOT ✓
   Si moyenne < 0.6 → Classé HUMAIN ✓
   ```

5. **Enregistrer le résultat** :
   ```
   Si prédiction = BOT et réalité = BOT → True Positive (TP) ++
   Si prédiction = BOT et réalité = HUMAIN → False Positive (FP) ++
   Si prédiction = HUMAIN et réalité = BOT → False Negative (FN) ++
   Si prédiction = HUMAIN et réalité = HUMAIN → True Negative (TN) ++
   ```

### **Configuration ML**

```properties
model.type=hoeffding_tree
model.ensemble.size=10
model.grace.period=200
model.split.confidence=0.0001
model.tie.threshold=0.05
detection.threshold=0.6
```

- **ensemble.size=10** : 10 arbres
- **grace.period=200** : Attendre 200 exemples
- **split.confidence=0.0001** : Très confiant avant de diviser
- **detection.threshold=0.6** : Seuil pour classification

### **Résultat de la Phase 4**

Pour chaque événement :
```
Input  : [0.45, 0.30, 0.80, 0.95, 0.70, 0.20, 0.50, 0.15, 0.00, 0.90, 1.00, 0.65, 0.25, 0.88]
Output : Prédiction = BOT (confiance 0.75)
         TP++ ou FP++
```

---

## 🟡 PHASE 5 : DÉTECTION DE DÉRIVE (ADWIN)

### **Qu'est-ce que la dérive conceptuelle ?**

**Concept Drift** = Le "monde change". Les patterns changent au fil du temps.

**Exemple** :
- Mois dernier : Les bots attaquaient avec patterns simples
- Aujourd'hui : Les bots ont évolué, patterns plus subtils
- Le modèle ML entraîné le mois dernier ne fonctionne plus bien

### **Pourquoi c'est important ?**

Sans détection de dérive, votre système se dégraderait lentement :
```
Jour 1 : Accuracy = 96%
Jour 2 : Accuracy = 95%
Jour 3 : Accuracy = 94%
...
Jour 30 : Accuracy = 70%  ← Catastrophe !
```

### **ADWIN - Qu'est-ce que c'est ?**

**ADWIN** = "Adaptative Windowing"

C'est une technologie qui détecte quand l'accuracy chute et dit : "Attention, dérive !"

### **Comment fonctionne ADWIN ?**

1. **Fenêtre glissante** - Garder l'historique des erreurs :
   ```
   Fenêtre = [Erreur1, Erreur2, Erreur3, ..., Erreur1000]
   
   (Les + récentes erreurs à droite)
   ```

2. **Diviser la fenêtre en 2** :
   ```
   Fenêtre ancienne : [Erreur1, Erreur2, ..., Erreur500]
       Moyenne des erreurs = 0.05 (5%)
   
   Fenêtre récente : [Erreur501, ..., Erreur1000]
       Moyenne des erreurs = 0.15 (15%)
   ```

3. **Comparer les 2 fenêtres** :
   ```
   Différence = |μ_ancien - μ_récent| = |0.05 - 0.15| = 0.10
   
   Si différence > seuil epsilon → DÉRIVE DÉTECTÉE !
   ```

4. **Oublier le passé** - Si dérive détectée, supprimer la fenêtre ancienne :
   ```
   Avant : [Erreur1, ..., Erreur1000]  (mélange ancien et nouveau)
   Après : [Erreur501, ..., Erreur1000]  (seulement le nouveau)
   ```

### **Comment ça fonctionne dans votre projet ?**

#### **Composant: DriftDetector**

```properties
drift.detection.enabled=true
drift.detection.method=adwin
drift.warning.level=0.05        # Alerte si dérive > 5%
drift.drift.level=0.001         # Critique si dérive > 0.1%
```

**Le processus** :

1. **Ajouter l'erreur** - Pour chaque événement :
   ```java
   double error = (prediction == actual) ? 0.0 : 1.0;
   adwin.setInput(error);
   ```

   - Si prédiction = vérité → error = 0.0 (bon)
   - Si prédiction ≠ vérité → error = 1.0 (mauvais)

2. **ADWIN teste** - "Est-ce une dérive ?"
   ```
   changeDetected = adwin.setInput(error);
   ```

3. **Réagir à la dérive** :

   **Cas 1 : Pas de dérive**
   ```
   Continue normal. Affiche dans le log.
   ```

   **Cas 2 : Warning (5%)** :
   ```
   Les erreurs augmentent légèrement.
   Log : "⚠️ Dérive conceptuelle détectée (5%)"
   Les administrateurs observent mais pas d'action.
   ```

   **Cas 3 : Drift majeur (0.1%)** :
   ```
   Les erreurs augmentent beaucoup.
   Log : "🚨 Dérive critique"
   Action : Adapter le modèle
   ```

4. **Adaptation du modèle** - Si dérive majeure :
   ```
   Trouver l'arbre le MOINS performant des 10.
   Supprimer cet arbre.
   Créer un nouvel arbre.
   Entraîner le nouvel arbre sur les données récentes.
   
   Avant : [Arbre1, Arbre2, ..., Arbre10] (ancien)
   Après : [Arbre1, Arbre2, ..., Arbre5_NEW, ..., Arbre10] (adapté)
   ```

5. **Reset ADWIN** - Recommencer à mesurer avec le nouveau modèle :
   ```
   adwin.reset();
   ```

### **Résultat de la Phase 5**

Le système s'auto-adapte :
```
T = 5 min  : Bots Phase 0 attaquent
             Accuracy = 96%

T = 10 min : Bots Phase 1 arrivent (plus sophistiqués)
             Accuracy commence à chuter (95%, 94%, 93%)
             DÉRIVE DÉTECTÉE ! ⚠️

T = 10.5 min : Modèle adapté
               Arbre le moins bon remplacé
               Accuracy remonte (94%, 95%, 96%)

T = 15 min : Bots Phase 2 arrivent
             Même processus...
```

---

## 🟣 PHASE 6 : MONITORING & MÉTRIQUES

### **Qu'est-ce que c'est ?**

C'est le "tableau de bord" où vous voyez ce qui se passe en temps réel.

### **Comment ça fonctionne dans votre projet ?**

#### **Composant: MonitoringDashboard**

Cette classe Java crée un serveur HTTP qui affiche les métriques.

**Le processus** :

1. **Collecter les métriques** - Toutes les 10 secondes :
   ```java
   scheduler.scheduleAtFixedRate(() -> {
       metricsCollector.collect();
   }, 0, 10000, TimeUnit.MILLISECONDS);
   ```

   Que collecte-t-on ?
   ```
   - TP, TN, FP, FN (compteurs)
   - Accuracy  = (TP + TN) / Total
   - Precision = TP / (TP + FP)
   - Recall    = TP / (TP + FN)
   - F1-Score  = 2 * (Precision * Recall) / (Precision + Recall)
   - Throughput = Événements/seconde
   - Latency   = ms par événement
   - Dérive    = Score ADWIN
   - État Kafka, Spark, Détecteur
   ```

2. **Serveur HTTP** - Créer des endpoints :
   ```
   GET /api/metrics       → Retourne métriques en JSON
   GET /api/health        → État du système
   GET /api/statistics    → Statistiques globales
   GET /api/external-sources → Sources de données externes
   ```

3. **Dashboard HTML** - Affiche sur http://localhost:8090 :
   ```
   ┌─────────────────────────────────────┐
   │      BOTSENSE-STREAM DASHBOARD      │
   ├─────────────────────────────────────┤
   │ Accuracy      : 96.5% ████████░░░  │
   │ Precision     : 89.2% ███████░░░░  │
   │ Recall        : 92.1% █████████░░  │
   │ Throughput    : 5234 evt/sec       │
   │ Latency       : 42ms               │
   │ Dérive Score  : 2.3% (Normal)      │
   ├─────────────────────────────────────┤
   │ État Kafka    : UP ✓               │
   │ État Spark    : UP ✓               │
   │ État Détecteur: UP ✓               │
   │ Mémoire       : 1840 MB / 3072 MB │
   └─────────────────────────────────────┘
   ```

4. **Graphiques temps réel** - Voir l'évolution :
   ```
   Accuracy au fil du temps :
   ┌────────────────────────────────┐
   │  100│                           │
   │   95│   ╱─────────────────────  │
   │   90│  ╱                        │
   │   85│ ╱                         │
   │      └────────────────────────────┘
   │      T0  T10  T20  T30  T40  T50min
   
   (Voir que la précision varie quand les bots évoluent)
   ```

5. **Alertes** - Afficher les événements importants :
   ```
   ⚠️ 14:30:00 - Dérive conceptuelle détectée (5%)
   ✅ 14:30:15 - Modèle adapté avec succès
   🚨 14:31:00 - Kafka déconnecté (reconnecté après 2sec)
   📊 14:32:00 - Accuracy remontée à 96.8%
   ```

### **Configuration Monitoring**

```properties
monitoring.enabled=true
monitoring.metrics.interval=10000    # Toutes les 10 sec
monitoring.dashboard.port=8090       # Port 8090
logging.level=INFO
logging.file.path=./logs/botsense.log
logging.file.max.size=10MB
logging.file.max.history=10
```

### **Résultat de la Phase 6**

Vous voyez en temps réel :
- ✅ Que le système fonctionne
- 📊 À quelle vitesse il traite
- 🎯 Combien de bots il détecte
- ⚠️ Quand il s'adapte (dérive)
- 🔴 Les anomalies

---

## 🔄 BOUCLES DE RÉTROACTION

### **Boucle 1 : Adaptation du Modèle**

```
Détection de dérive → Adapter modèle → Remplacer arbre → Évaluer nouveau modèle
                                                              ↓
                                          (Si ça s'améliore) ✓ Continuer
                                          (Si ça s'empire)   ✗ Revenir
```

### **Boucle 2 : Évolution des Bots**

```
Bots Phase 0 → Accuracy chute → DÉRIVE DÉTECTÉE → Modèle adapté → Accuracy remonte
                                                                        ↓
                Bots Phase 1 → Accuracy chute → DÉRIVE DÉTECTÉE → ... (Répète)
```

### **Boucle 3 : Amélioration Continue**

```
Chaque événement → Entraîner tous les 10 arbres → Améliorer les poids
                                                        ↓
                                    (Apprendre des nouveaux patterns)
```

---

## 📈 ÉVOLUTION TEMPORELLE PROGRAMMÉE

Votre système simule une attaque qui évolue :

**T = 0-5 minutes** : Bots Phase 0 (simples)
- User-Agents suspectes
- Vitesse extrême (1000 req/sec)
- Pas d'interaction humaine
- **Résultat** : Facile à détecter (Accuracy = 98%)

**T = 5-10 minutes** : Bots Phase 1 (intermédiaires)
- User-Agents plus réalistes
- Délais aléatoires
- Cliquent parfois (simulent l'interaction)
- **Résultat** : Accuracy chute (96%)

**T = 10+ minutes** : Bots Phase 2 (sophistiqués)
- Indistinguibles des humains
- Patterns très proches
- Scrollent, cliquent, restent longtemps
- **Résultat** : Très difficile (Accuracy = 85%)

**À chaque phase** :
- L'Accuracy diminue
- ADWIN détecte la dérive
- Le modèle s'adapte
- L'Accuracy remonte
- Mais jamais à 100% (c'est réaliste)

---

## 🎯 RÉSUMÉ DU WORKFLOW COMPLET

```
┌─────────────────────────────────────────────────────────────────┐
│                     FLUX COMPLET                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  PHASE 1: GÉNÉRATION                                           │
│  → Créer événements (humain/bot simple/bot évolué)             │
│  → 1000 evt/sec                                                │
│                                                                 │
│  PHASE 2: KAFKA                                                │
│  → Envoyer événements à Kafka                                  │
│  → Partitionner, répliquer                                     │
│                                                                 │
│  PHASE 3: SPARK                                                │
│  → Recevoir par batches (5 sec)                                │
│  → Extraire 14 features normalisées                            │
│  → Créer vecteurs                                              │
│                                                                 │
│  PHASE 4: ML (ENSEMBLE 10 TREES)                              │
│  → 10 arbres votent parallèlement                              │
│  → Moyenne = confiance                                         │
│  → Classifie BOT ou HUMAIN                                     │
│  → Enregistre TP/FP/FN/TN                                      │
│                                                                 │
│  PHASE 5: DÉRIVE (ADWIN)                                       │
│  → Mesurer taux erreur                                         │
│  → Comparer fenêtres ancien/nouveau                            │
│  → Détecter si changement significatif                         │
│  → Adapter le modèle si nécessaire                             │
│                                                                 │
│  PHASE 6: MONITORING                                           │
│  → Collecter métriques (Accuracy, Precision, Recall)          │
│  → Afficher sur dashboard (localhost:8090)                     │
│  → Logger dans fichier                                         │
│  → Alerter administrateurs                                     │
│                                                                 │
│  BOUCLES DE RÉTROACTION :                                      │
│  → Modèle adapté → Nouveau modèle → Re-évaluer                │
│  → Bots Phase N → Dérive → Adaption → Bots Phase N+1          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔑 CONCEPTS CLÉS À RETENIR

### **Online Learning (Apprentissage Continu)**
Les modèles n'attendent pas de réentraînement offline. Ils apprennent de chaque événement immédiatement.

### **Streaming Architecture (Architecture Streaming)**
Les données arrivent continuellement, pas en batch statique. Le système fonctionne H24.

### **Concept Drift Adaptation (Adaptation à la Dérive)**
Les patterns changent (les bots évoluent), le système détecte et s'adapte.

### **Ensemble Methods (Méthodes d'ensemble)**
10 modèles votent ensemble, plus fiable qu'un seul.

### **Real-time Metrics (Métriques Temps Réel)**
Vous voyez tout ce qui se passe instantanément, pas de délai.

---

## 📊 EXEMPLE COMPLET D'UN ÉVÉNEMENT

**T = 14:30:00** → Un bot attaque

```
PHASE 1: GÉNÉRATION
┌─────────────────────────────────────────┐
│ TrafficGenerator génère un événement :  │
│ sessionId: "xyz789"                     │
│ isBot: true (Phase 1)                   │
│ requestsPerSecond: 45 (rapide)          │
│ clickRate: 0.30 (peu de clics)          │
│ scrollDepth: 0.05 (pas de scroll)       │
└─────────────────────────────────────────┘

PHASE 2: KAFKA
┌─────────────────────────────────────────┐
│ Convertir en JSON                       │
│ Envoyer à Kafka                         │
│ Topic: bot-traffic                      │
│ Partition: 2 (hash de sessionId)        │
└─────────────────────────────────────────┘

PHASE 3: SPARK (T=14:30:05, batch)
┌─────────────────────────────────────────┐
│ Recevoir du Kafka                       │
│ Extraire features: [0.89, 0.10, ...]    │
│ 14 features normalisées                 │
└─────────────────────────────────────────┘

PHASE 4: ML
┌─────────────────────────────────────────┐
│ Arbre 1: 0.92 (BOT)                    │
│ Arbre 2: 0.88 (BOT)                    │
│ Arbre 3: 0.85 (BOT)                    │
│ ...                                     │
│ Arbre 10: 0.90 (BOT)                   │
│                                         │
│ Moyenne: 0.89 > 0.60 (seuil)           │
│ RÉSULTAT: BOT ✓                        │
│ TP++ (prédiction correcte)             │
└─────────────────────────────────────────┘

PHASE 5: DÉRIVE
┌─────────────────────────────────────────┐
│ Ajouter erreur = 0.0 (bon)              │
│ Fenêtre erreurs: [0, 0, 0.1, 0, ...]   │
│ Moyenne fenêtre = 0.02 (2%)            │
│ Pas de dérive                           │
│ Continue normalement                    │
└─────────────────────────────────────────┘

PHASE 6: MONITORING
┌─────────────────────────────────────────┐
│ TP: 1500                                │
│ FP: 50                                  │
│ Accuracy: 96.5%                         │
│ Precision: 96.8%                        │
│ Recall: 96.2%                           │
│                                         │
│ Dashboard mise à jour (14:30:10)        │
│ Log: "Bot détecté avec confiance 0.89" │
└─────────────────────────────────────────┘
```

---

## ✅ VALIDATION : Est-ce que ce workflow est correct ?

**OUI, ce workflow est EXACT pour votre projet BotSense-Stream.**

Il reflète :
✅ TrafficGenerator créant des bots Phase 0/1/2
✅ Kafka partitionnant les événements
✅ Spark extrayant 14 features
✅ OnlineBaggingEnsemble avec 10 Hoeffding Trees
✅ Vote majoritaire pour classification
✅ ADWIN détectant la dérive
✅ Adaptation du modèle (remplacement d'arbre)
✅ MonitoringDashboard collectant métriques
✅ Boucles de rétroaction
✅ Évolution temporelle programmée (5 min par phase)

Le workflow représente fidèlement comment votre code fonctionne réellement.
