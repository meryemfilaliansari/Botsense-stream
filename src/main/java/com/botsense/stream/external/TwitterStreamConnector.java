package com.botsense.stream.external;

import com.botsense.stream.core.TrafficEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Connecteur pour Twitter/X Stream API
 * Récupère les tweets en temps réel et les analyse pour détecter les bots
 */
public class TwitterStreamConnector implements ExternalDataSourceConnector {
    private static final Logger logger = LoggerFactory.getLogger(TwitterStreamConnector.class);
    
    private String bearerToken;
    private String searchQuery;
    private boolean connected = false;
    private long eventCount = 0;
    private long lastUpdateTime = 0;
    private Queue<TrafficEvent> eventQueue;
    private Thread streamThread;
    
    public TwitterStreamConnector(String bearerToken, String searchQuery) {
        this.bearerToken = bearerToken;
        this.searchQuery = searchQuery;
        this.eventQueue = new LinkedList<>();
    }
    
    @Override
    public boolean connect() {
        if (bearerToken == null || bearerToken.isEmpty()) {
            logger.warn("Twitter Bearer Token not configured - using simulated data");
            // Continuer en mode simulation
        }
        
        connected = true;
        startStreamThread();
        logger.info("Connected to Twitter Stream - Query: {}", searchQuery);
        return true;
    }
    
    private void startStreamThread() {
        streamThread = new Thread(() -> {
            while (connected) {
                try {
                    fetchTweetsFromAPI();
                } catch (Exception e) {
                    logger.error("Error fetching tweets", e);
                }
                
                try {
                    Thread.sleep(5000);  // Attendre 5 secondes avant la prochaine requête
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();
    }
    
    /**
     * Récupère les tweets via l'API Twitter/X (simulé si token non valide)
     */
    private void fetchTweetsFromAPI() throws IOException {
        try {
            // URL de l'API Twitter v2
            String url = "https://api.twitter.com/2/tweets/search/recent?query=" + 
                        searchQuery + "&max_results=100&tweet.fields=public_metrics,author_id,created_at";
            
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream())
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                parseTweetResponse(response.toString());
            } else {
                logger.debug("Twitter API response code: {}", responseCode);
            }
        } catch (Exception e) {
            logger.debug("Twitter API not available, using simulated data");
            generateSimulatedTweets();
        }
    }
    
    /**
     * Parse la réponse JSON de Twitter et crée des TrafficEvent
     */
    private void parseTweetResponse(String jsonResponse) {
        try {
            JSONObject response = new JSONObject(jsonResponse);
            if (!response.has("data")) return;
            
            JSONArray tweets = response.getJSONArray("data");
            for (int i = 0; i < tweets.length(); i++) {
                JSONObject tweet = tweets.getJSONObject(i);
                TrafficEvent event = createEventFromTweet(tweet);
                if (event != null) {
                    eventQueue.add(event);
                    eventCount++;
                }
            }
            lastUpdateTime = System.currentTimeMillis();
        } catch (Exception e) {
            logger.debug("Error parsing tweet response", e);
        }
    }
    
    /**
     * Crée un TrafficEvent à partir d'un tweet
     */
    private TrafficEvent createEventFromTweet(JSONObject tweet) {
        try {
            String userId = tweet.optString("author_id", "unknown");
            long createdAt = Instant.parse(tweet.getString("created_at")).toEpochMilli();
            String text = tweet.getString("text");
            
            JSONObject metrics = tweet.optJSONObject("public_metrics");
            int likes = metrics != null ? metrics.optInt("like_count", 0) : 0;
            int retweets = metrics != null ? metrics.optInt("retweet_count", 0) : 0;
            
            TrafficEvent event = new TrafficEvent(
                userId,
                "twitter_user",
                "TwitterClient/1.0"
            );
            event.setTimestamp(createdAt);
            
            event.addMetadata("source", "TwitterStream");
            event.addMetadata("likes", String.valueOf(likes));
            event.addMetadata("retweets", String.valueOf(retweets));
            event.addMetadata("text_length", String.valueOf(text.length()));
            
            // Heuristique simple: bot si beaucoup de retweets pour peu de likes
            if (retweets > likes * 5) {
                event.addMetadata("bot_likelihood", "high");
            }
            
            return event;
        } catch (Exception e) {
            logger.debug("Error creating event from tweet", e);
            return null;
        }
    }
    
    /**
     * Génère des tweets simulés pour les tests (si API non disponible)
     */
    private void generateSimulatedTweets() {
        String[] botPatterns = {
            "Check out this link!", "Follow for daily updates", "Free money!", 
            "Crypto investment opportunity", "Click here for rewards"
        };
        String[] normalPatterns = {
            "Just had a great meeting", "Looking forward to the weekend",
            "New blog post published", "Great insights from the conference"
        };
        
        for (int i = 0; i < 5; i++) {
            boolean isBot = Math.random() < 0.3;
            String[] patterns = isBot ? botPatterns : normalPatterns;
            String text = patterns[(int)(Math.random() * patterns.length)];
            String userId = "user_" + (int)(Math.random() * 100000);
            
            TrafficEvent event = new TrafficEvent(
                userId,
                "twitter_user",
                "TwitterClient/1.0"
            );
            
            event.addMetadata("source", "TwitterStream");
            event.addMetadata("simulated", "true");
            event.addMetadata("text_length", String.valueOf(text.length()));
            if (isBot) {
                event.addMetadata("bot_likelihood", "high");
            }
            
            eventQueue.add(event);
            eventCount++;
        }
    }
    
    @Override
    public void disconnect() {
        connected = false;
        try {
            if (streamThread != null) {
                streamThread.join(5000);
            }
        } catch (InterruptedException e) {
            logger.error("Error stopping stream thread", e);
        }
        logger.info("Disconnected from Twitter Stream");
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public TrafficEvent getNextEvent() {
        return eventQueue.poll();
    }
    
    @Override
    public TrafficEvent[] getEventBatch(int batchSize) {
        List<TrafficEvent> events = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            TrafficEvent event = getNextEvent();
            if (event == null) break;
            events.add(event);
        }
        if (events.isEmpty() && connected) {
            lastUpdateTime = System.currentTimeMillis();
        }
        return events.toArray(new TrafficEvent[0]);
    }
    
    @Override
    public String getSourceName() {
        return "TwitterStream";
    }
    
    @Override
    public long getEventCount() {
        return eventCount;
    }
    
    @Override
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
}
