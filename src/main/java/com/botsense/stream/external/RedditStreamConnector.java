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
 * Connecteur pour Reddit API
 * Récupère les posts et commentaires en temps réel depuis des subreddits spécifiques
 */
public class RedditStreamConnector implements ExternalDataSourceConnector {
    private static final Logger logger = LoggerFactory.getLogger(RedditStreamConnector.class);
    
    private String clientId;
    private String clientSecret;
    private String subreddit;
    private boolean connected = false;
    private long eventCount = 0;
    private long lastUpdateTime = 0;
    private Queue<TrafficEvent> eventQueue;
    private String accessToken;
    
    public RedditStreamConnector(String clientId, String clientSecret, String subreddit) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.subreddit = subreddit;
        this.eventQueue = new LinkedList<>();
    }
    
    @Override
    public boolean connect() {
        try {
            // Authentification Reddit (optionnel si credentials valides)
            if (clientId != null && !clientId.isEmpty()) {
                authenticateReddit();
            }
            connected = true;
            logger.info("Connected to Reddit Stream - Subreddit: r/{}", subreddit);
            return true;
        } catch (Exception e) {
            logger.warn("Reddit authentication failed, using public data: {}", e.getMessage());
            connected = true;  // Peut toujours accéder aux données publiques
            return true;
        }
    }
    
    /**
     * Authentifie l'accès à Reddit API
     */
    private void authenticateReddit() throws IOException {
        // Implémentation simplifiée - normalement OAuth2
        // Pour cet exemple, nous utilisons les données publiques directement
        logger.debug("Reddit OAuth2 authentication not implemented in demo version");
    }
    
    @Override
    public void disconnect() {
        connected = false;
        logger.info("Disconnected from Reddit Stream");
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public TrafficEvent getNextEvent() {
        if (eventQueue.isEmpty()) {
            fetchRedditData();
        }
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
        return events.toArray(new TrafficEvent[0]);
    }
    
    /**
     * Récupère les données Reddit (posts et commentaires)
     */
    private void fetchRedditData() {
        try {
            // URL pour récupérer les posts populaires d'un subreddit
            String url = String.format("https://www.reddit.com/r/%s/hot.json?limit=25", subreddit);
            
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "BotSense/1.0");
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
                
                parseRedditResponse(response.toString());
            } else {
                logger.debug("Reddit API response code: {}", responseCode);
                generateSimulatedRedditData();
            }
        } catch (Exception e) {
            logger.debug("Error fetching Reddit data, using simulated data: {}", e.getMessage());
            generateSimulatedRedditData();
        }
    }
    
    /**
     * Parse la réponse JSON de Reddit
     */
    private void parseRedditResponse(String jsonResponse) {
        try {
            JSONObject response = new JSONObject(jsonResponse);
            JSONObject data = response.getJSONObject("data");
            JSONArray children = data.getJSONArray("children");
            
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                if ("t3".equals(child.getString("kind"))) {  // t3 = post
                    JSONObject postData = child.getJSONObject("data");
                    TrafficEvent event = createEventFromPost(postData);
                    if (event != null) {
                        eventQueue.add(event);
                        eventCount++;
                    }
                }
            }
            lastUpdateTime = System.currentTimeMillis();
        } catch (Exception e) {
            logger.debug("Error parsing Reddit response", e);
        }
    }
    
    /**
     * Crée un TrafficEvent à partir d'un post Reddit
     */
    private TrafficEvent createEventFromPost(JSONObject post) {
        try {
            String author = post.optString("author", "unknown");
            long createdUtc = post.getLong("created_utc") * 1000;
            String title = post.getString("title");
            int upvotes = post.optInt("ups", 0);
            int downvotes = post.optInt("downs", 0);
            int commentCount = post.optInt("num_comments", 0);
            
            TrafficEvent event = new TrafficEvent(
                author,
                "reddit_user",
                "RedditClient/1.0"
            );
            event.setTimestamp(createdUtc);
            
            event.addMetadata("source", "RedditStream");
            event.addMetadata("subreddit", subreddit);
            event.addMetadata("upvotes", String.valueOf(upvotes));
            event.addMetadata("downvotes", String.valueOf(downvotes));
            event.addMetadata("comments", String.valueOf(commentCount));
            event.addMetadata("title_length", String.valueOf(title.length()));
            
            // Détection de bots: upvotes anormalement élevés vs commentaires bas
            if (upvotes > commentCount * 10 && commentCount < 5) {
                event.addMetadata("bot_likelihood", "medium");
            }
            
            // Détection de bots: noms d'utilisateurs synthétiques
            if (author.matches(".*_+[0-9]{4,}.*") || author.matches("[A-Z]{10,}")) {
                event.addMetadata("bot_likelihood", "high");
            }
            
            return event;
        } catch (Exception e) {
            logger.debug("Error creating event from Reddit post", e);
            return null;
        }
    }
    
    /**
     * Génère des données Reddit simulées pour les tests
     */
    private void generateSimulatedRedditData() {
        String[] botTitles = {
            "CLICK HERE FOR AMAZING DEALS!",
            "Free cryptocurrency for everyone!!!",
            "Incredible weight loss secret revealed",
            "Make $5000 per week from home"
        };
        
        String[] normalTitles = {
            "Discussion about recent tech trends",
            "My thoughts on the latest news",
            "Question about this topic",
            "Has anyone tried this?"
        };
        
        for (int i = 0; i < 10; i++) {
            boolean isBot = Math.random() < 0.4;
            String[] titles = isBot ? botTitles : normalTitles;
            String title = titles[(int)(Math.random() * titles.length)];
            String author = isBot ? "BOT_" + (int)(Math.random() * 10000) : 
                           "user_" + (int)(Math.random() * 100000);
            
            int upvotes = isBot ? 5000 + (int)(Math.random() * 5000) : 
                         100 + (int)(Math.random() * 500);
            int comments = isBot ? 5 + (int)(Math.random() * 10) : 
                          20 + (int)(Math.random() * 100);
            
            TrafficEvent event = new TrafficEvent(
                author,
                "reddit_user",
                "RedditClient/1.0"
            );
            
            event.addMetadata("source", "RedditStream");
            event.addMetadata("subreddit", subreddit);
            event.addMetadata("upvotes", String.valueOf(upvotes));
            event.addMetadata("comments", String.valueOf(comments));
            event.addMetadata("simulated", "true");
            event.addMetadata("title_length", String.valueOf(title.length()));
            
            if (isBot) {
                event.addMetadata("bot_likelihood", "high");
            }
            
            eventQueue.add(event);
            eventCount++;
        }
        lastUpdateTime = System.currentTimeMillis();
    }
    
    @Override
    public String getSourceName() {
        return "RedditStream";
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
