package me.mediaroulette.reddit.resolvers;

import me.hash.mediaroulette.utils.media.ffmpeg.resolvers.UrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Concise RedGifs resolver using Playwright and M3U8 parsing
 * TODO: Improve the resolution here, use some better scraping since this does NOT always work!
 */
public class RedGifsResolver implements UrlResolver {
    private static final Logger logger = LoggerFactory.getLogger(RedGifsResolver.class);

    @Override
    public boolean canResolve(String url) {
        return url != null && url.contains("redgifs.com");
    }

    @Override
    public CompletableFuture<String> resolve(String url) {
        if (!url.contains("redgifs.com/watch/")) {
            return CompletableFuture.completedFuture(url);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String gifId = M3u8Parser.extractGifId(url);
                if (gifId == null) return url;

                // Try M3U8 approach first
                String m3u8Url = M3u8Parser.buildRedGifsM3u8Url(gifId);
                String videoUrl = M3u8Parser.extractVideoUrl(m3u8Url);
                if (videoUrl != null) {
                    return videoUrl.replace(".m4s", ".mp4");
                }

            } catch (Exception e) {
                logger.error("Failed to resolve RedGifs URL: {}", e.getMessage());
            }
            return url;
        });
    }

    private String extractGifNameFromPoster(String posterUrl) {
        try {
            String[] parts = posterUrl.split("/");
            String filename = parts[parts.length - 1];
            return filename.replace("-mobile.jpg", "").replace(".jpg", "");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isUrlAccessible(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(true);
            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int getPriority() {
        return 10; // High priority for RedGifs URLs
    }
}