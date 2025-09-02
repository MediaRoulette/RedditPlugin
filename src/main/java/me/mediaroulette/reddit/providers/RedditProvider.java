package me.mediaroulette.reddit.providers;

import me.hash.mediaroulette.model.User;
import me.hash.mediaroulette.model.content.MediaResult;
import me.hash.mediaroulette.model.content.CachedMediaResult;
import me.hash.mediaroulette.plugins.Images.ImageSourceProvider;
import me.hash.mediaroulette.utils.DictionaryIntegration;
import me.hash.mediaroulette.utils.ErrorReporter;
import me.hash.mediaroulette.utils.LocalConfig;
import me.hash.mediaroulette.utils.PersistentCache;
import me.mediaroulette.reddit.reddit.RedditClient;
import me.mediaroulette.reddit.reddit.RedditPostProcessor;
import me.mediaroulette.reddit.reddit.SubredditManager;
import net.dv8tion.jda.api.interactions.Interaction;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class RedditProvider implements ImageSourceProvider {
    private static final Logger logger = LoggerFactory.getLogger(RedditProvider.class);

    // Cache and performance constants
    private static final long CACHE_EXPIRATION_TIME = 10 * 60 * 1000; // 10 minutes
    private static final int POST_LIMIT = 50;
    private static final int MAX_RESULTS_PER_SUBREDDIT = 200;
    private static final int MIN_QUEUE_SIZE = 10;
    private static final int MAX_CONCURRENT_REQUESTS = 3;
    private static final int EXECUTOR_THREAD_COUNT = 4;
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    // In-memory queues for active use
    private final Map<String, Queue<MediaResult>> imageQueues = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpdated = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> processedPostIds = new ConcurrentHashMap<>();

    // Persistent cache for Reddit media results
    private final PersistentCache<List<CachedMediaResult>> persistentCache =
            new PersistentCache<>("reddit_media_cache.json", new TypeReference<>() {
            });
    private final PersistentCache<Long> timestampCache =
            new PersistentCache<>("reddit_timestamps.json", new TypeReference<>() {
            });

    private final ExecutorService executorService = Executors.newFixedThreadPool(EXECUTOR_THREAD_COUNT);

    private final RedditClient redditClient;
    private final SubredditManager subredditManager;
    private final RedditPostProcessor postProcessor;

    public RedditProvider(RedditClient redditClient, SubredditManager subredditManager) {
        this.redditClient = redditClient;
        this.subredditManager = subredditManager;
        this.postProcessor = new RedditPostProcessor();

        // Add shutdown hook to ensure cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
    }

    /**
     * Gets a random image from Reddit with optional user context for dictionary integration
     * This method is used internally and for backward compatibility
     */
    public MediaResult getRandomImage(String subreddit, String userId) throws IOException {
        try {
            return getRandomRedditMedia(subreddit, userId);
        } catch (ExecutionException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Error fetching Reddit media", e);
        }
    }

    private MediaResult getRandomRedditMedia(String subreddit, String userId) throws IOException, ExecutionException, InterruptedException {
        // Always try dictionary first if userId is provided and no specific subreddit requested
        if (subreddit == null && userId != null) {
            System.out.println("RedditProvider: Trying to get dictionary subreddit for user: " + userId);
            String dictSubreddit = DictionaryIntegration.getRandomWordForSource(userId, "reddit");
            System.out.println("RedditProvider: Dictionary returned subreddit: " + dictSubreddit);
            if (dictSubreddit != null && subredditManager.doesSubredditExist(dictSubreddit)) {
                subreddit = dictSubreddit;
                System.out.println("RedditProvider: Using dictionary subreddit: " + subreddit);
                logger.info("Using dictionary subreddit: {}", subreddit);
            } else {
                System.out.println("RedditProvider: Dictionary subreddit invalid or null");
            }
        }

        // If still no subreddit or invalid subreddit, use fallback logic
        if (subreddit == null || !subredditManager.doesSubredditExist(subreddit)) {
            try {
                subreddit = subredditManager.getRandomSubreddit();
            } catch (IOException e) {
                logger.error("Failed to get random subreddit: {}", e.getMessage());
                ErrorReporter.reportProviderError("reddit", "random subreddit selection", e.getMessage(), userId);
                throw new IOException("Unable to find a valid subreddit. " + e.getMessage());
            }
        }

        ensureCacheReady(subreddit);

        Queue<MediaResult> queue = imageQueues.get(subreddit);
        MediaResult result = queue.poll();

        if (result == null) {
            logger.warn("No images available for subreddit {} after cache refresh", subreddit);
            ErrorReporter.reportProviderError("reddit", "empty queue", "No images available for subreddit: " + subreddit, userId);
            throw new IOException("No images available for subreddit: " + subreddit);
        }

        // Update persistent cache asynchronously to avoid blocking
        String finalSubreddit = subreddit;
        CompletableFuture.runAsync(() -> saveToPersistentCache(finalSubreddit, queue), executorService);

        return result;
    }

    /**
     * Ensures the cache is ready for the given subreddit, initializing and refreshing as needed
     */
    private void ensureCacheReady(String subreddit) {
        // Initialize data structures if needed
        imageQueues.computeIfAbsent(subreddit, _ -> new ConcurrentLinkedQueue<>());
        processedPostIds.computeIfAbsent(subreddit, _ -> ConcurrentHashMap.newKeySet());

        // Load from persistent cache if available and not already loaded
        if (!lastUpdated.containsKey(subreddit)) {
            loadFromPersistentCache(subreddit);
        }

        // Check if refresh is needed
        Queue<MediaResult> imageQueue = imageQueues.get(subreddit);
        long lastUpdateTime = lastUpdated.getOrDefault(subreddit, 0L);
        boolean needsRefresh = imageQueue.size() < MIN_QUEUE_SIZE ||
                System.currentTimeMillis() - lastUpdateTime > CACHE_EXPIRATION_TIME;

        if (needsRefresh) {
            refreshCache(subreddit);
        }
    }

    private void loadFromPersistentCache(String subreddit) {
        Long cachedTimestamp = timestampCache.get(subreddit);
        if (cachedTimestamp != null) {
            lastUpdated.put(subreddit, cachedTimestamp);

            List<CachedMediaResult> cachedResults = persistentCache.get(subreddit);
            if (cachedResults != null && !cachedResults.isEmpty()) {
                Queue<MediaResult> queue = imageQueues.get(subreddit);
                cachedResults.stream()
                        .filter(cached -> cached.isValid(CACHE_EXPIRATION_TIME))
                        .map(CachedMediaResult::toMediaResult)
                        .forEach(queue::offer);
            }
        } else {
            lastUpdated.put(subreddit, 0L);
        }
    }

    private void refreshCache(String subreddit) {
        updateImageQueue(subreddit);
        long currentTime = System.currentTimeMillis();
        lastUpdated.put(subreddit, currentTime);
        timestampCache.put(subreddit, currentTime);
    }

    private void updateImageQueue(String subreddit) {
        String[] sortMethods = {"hot", "top", "new"};

        // Limit concurrent requests to avoid overwhelming Reddit API
        List<CompletableFuture<List<MediaResult>>> futures = Arrays.stream(sortMethods)
                .limit(MAX_CONCURRENT_REQUESTS)
                .map(sortMethod -> CompletableFuture.supplyAsync(() ->
                        fetchImagesFromSubredditSafely(subreddit, sortMethod), executorService))
                .toList();

        // Wait for all requests to complete with timeout
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("Timeout waiting for Reddit API requests for subreddit: {}", subreddit);
            // Cancel any remaining futures
            futures.forEach(future -> future.cancel(true));
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error waiting for Reddit API requests: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        // Collect and process results
        List<MediaResult> allNewResults = futures.stream()
                .map(this::getFutureResultSafely)
                .flatMap(List::stream)
                .toList();

        if (allNewResults.isEmpty()) {
            logger.warn("No valid images found for subreddit: {}", subreddit);
            return;
        }

        // Shuffle for variety and add to queue
        List<MediaResult> shuffledResults = new ArrayList<>(allNewResults);
        Collections.shuffle(shuffledResults);
        addResultsToQueue(subreddit, shuffledResults);
    }

    private List<MediaResult> fetchImagesFromSubredditSafely(String subreddit, String sortMethod) {
        try {
            return fetchImagesFromSubreddit(subreddit, sortMethod);
        } catch (IOException | InterruptedException | ExecutionException e) {
            logger.error("Error fetching images for subreddit {} with sort {}: {}",
                    subreddit, sortMethod, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Collections.emptyList();
        }
    }

    private List<MediaResult> getFutureResultSafely(CompletableFuture<List<MediaResult>> future) {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error getting future result: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Collections.emptyList();
        }
    }

    private void addResultsToQueue(String subreddit, List<MediaResult> results) {
        Queue<MediaResult> queue = imageQueues.get(subreddit);
        Set<String> processedIds = processedPostIds.get(subreddit);

        int addedCount = 0;
        for (MediaResult result : results) {
            if (queue.size() >= MAX_RESULTS_PER_SUBREDDIT) {
                break;
            }

            String resultId = generateResultId(result);
            if (!processedIds.contains(resultId)) {
                queue.offer(result);
                processedIds.add(resultId);
                addedCount++;
            }
        }

        logger.debug("Added {} new results to queue for subreddit: {}", addedCount, subreddit);

        // Clean up processed IDs if set gets too large
        if (processedIds.size() > MAX_RESULTS_PER_SUBREDDIT * 2) {
            processedIds.clear();
            logger.debug("Cleared processed IDs cache for subreddit: {}", subreddit);
        }

        // Save to persistent cache asynchronously
        CompletableFuture.runAsync(() -> saveToPersistentCache(subreddit, queue), executorService);
    }

    private List<MediaResult> fetchImagesFromSubreddit(String subreddit, String sortMethod)
            throws IOException, ExecutionException, InterruptedException {
        String accessToken = redditClient.getAccessToken();
        String timeParam = "top".equals(sortMethod) ? "&t=week" : "";
        String url = String.format("https://oauth.reddit.com/r/%s/%s?limit=%d%s",
                subreddit, sortMethod, POST_LIMIT, timeParam);

        try (Response response = redditClient.sendGetRequestAsync(url, accessToken).get()) {
            if (!response.isSuccessful()) {
                logger.warn("Failed to fetch posts for subreddit: {} with sort: {} (HTTP {})",
                        subreddit, sortMethod, response.code());
                return Collections.emptyList();
            }

            String responseBody = response.body().string();
            JSONObject json = new JSONObject(responseBody);

            if (json.has("error")) {
                logger.warn("Reddit API error for {}/{}: {}", subreddit, sortMethod, json.getString("error"));
                return Collections.emptyList();
            }

            JSONArray posts = json.getJSONObject("data").getJSONArray("children");
            return postProcessor.processPosts(posts);

        } catch (Exception e) {
            logger.error("Error processing Reddit response for {}/{}: {}", subreddit, sortMethod, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String generateResultId(MediaResult result) {
        // Create a simple ID based on URL and title to avoid duplicates
        return (result.getImageUrl() + "|" + result.getTitle()).hashCode() + "";
    }

    @Override
    public boolean supportsSearch() {
        return true;
    }

    @Override
    public int getPriority() {
        return 90;
    }

    @Override
    public String getName() {
        return "REDDIT";
    }

    @Override
    public String getDisplayName() {
        return "Reddit";
    }

    @Override
    public String getDescription() {
        return "Reddit image provider with caching, dictionary integration, and optimized performance";
    }

    @Override
    public boolean isEnabled() {
        // Check if Reddit is enabled in the configuration
        LocalConfig config = LocalConfig.getInstance();
        return config.isSourceEnabled("reddit");
    }

    @Override
    public MediaResult getRandomImage(Interaction interaction, User user, String query) throws Exception {
        String userId = user != null ? user.getUserId() : null;
        return getRandomImage(query, userId);
    }

    @Override
    public Map<String, String> getRandomImageAsMap(Interaction interaction, User user, String query) throws Exception {
        return ImageSourceProvider.super.getRandomImageAsMap(interaction, user, query);
    }

    @Override
    public String getConfigKey() {
        return "reddit";
    }

    /**
     * Saves current queue to persistent cache for later retrieval
     */
    private void saveToPersistentCache(String subreddit, Queue<MediaResult> queue) {
        try {
            List<CachedMediaResult> cachedResults = queue.stream()
                    .map(CachedMediaResult::new)
                    .toList();
            persistentCache.put(subreddit, cachedResults);
        } catch (Exception e) {
            logger.warn("Failed to save cache for subreddit {}: {}", subreddit, e.getMessage());
        }
    }

    /**
     * Cleanup method to prevent memory leaks and save state
     */
    public void cleanup() {
        logger.info("Cleaning up RedditProvider resources...");

        // Save all current caches before shutdown
        imageQueues.entrySet().parallelStream()
                .forEach(entry -> saveToPersistentCache(entry.getKey(), entry.getValue()));

        // Clear in-memory caches
        imageQueues.clear();
        lastUpdated.clear();
        processedPostIds.clear();

        // Shutdown executor service gracefully
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate gracefully, forcing shutdown");
                executorService.shutdownNow();

                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for executor termination");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("RedditProvider cleanup completed");
    }

    /**
     * Get cache statistics for monitoring
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cached_subreddits", imageQueues.size());
        stats.put("total_cached_images", imageQueues.values().stream().mapToInt(Queue::size).sum());
        stats.put("persistent_cache_size", persistentCache.size());
        return stats;
    }
}