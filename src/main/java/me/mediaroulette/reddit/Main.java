package me.mediaroulette.reddit;

import me.hash.mediaroulette.model.content.MediaSource;
import me.hash.mediaroulette.plugins.Images.ImageSourcePlugin;
import me.hash.mediaroulette.plugins.Images.ImageSourceProvider;
import me.hash.mediaroulette.plugins.Plugin;

import me.hash.mediaroulette.utils.media.ffmpeg.resolvers.UrlResolverFactory;
import me.mediaroulette.reddit.providers.RedditProvider;
import me.mediaroulette.reddit.reddit.RedditClient;
import me.mediaroulette.reddit.reddit.SubredditManager;
import me.mediaroulette.reddit.resolvers.RedGifsResolver;

import java.util.Collections;
import java.util.List;

public class Main extends Plugin implements ImageSourcePlugin {
    
    private RedditProvider redditProvider;

    @Override
    public void onLoad() {
        try {
            MediaSource.register("REDDIT", "Reddit");

        } catch (Exception e) {
            getLogger().error("[RedditPlugin] Failed to register MediaSource", e);
        }
    }

    @Override
    public void onEnable() {
        try {
            RedditClient redditClient = new RedditClient();
            SubredditManager subredditManager = new SubredditManager(redditClient);
            redditProvider = new RedditProvider(redditClient, subredditManager);

            UrlResolverFactory.addResolver(new RedGifsResolver());


        } catch (Exception e) {
            getLogger().error("[RedditPlugin] Failed to initialize Reddit provider", e);
        }
    }

    @Override
    public void onDisable() {
        if (redditProvider != null) {
            redditProvider.cleanup();
        }
        super.onDisable();
        getLogger().info("[RedditPlugin] Disabled");
    }

    @Override
    public List<ImageSourceProvider> getImageSourceProviders() {
        return Collections.singletonList(redditProvider);
    }

    @Override
    public void onImageSourcesRegistered() {
        getLogger().info("[RedditPlugin] Image sources registered");
        ImageSourcePlugin.super.onImageSourcesRegistered();
    }

    @Override
    public void onImageSourcesUnregistered() {
        getLogger().info("[RedditPlugin] Image sources unregistered");
        ImageSourcePlugin.super.onImageSourcesUnregistered();
    }
}