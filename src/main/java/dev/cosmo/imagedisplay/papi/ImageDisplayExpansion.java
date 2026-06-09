package dev.cosmo.imagedisplay.papi;

import dev.cosmo.imagedisplay.ImageDisplay;
import dev.cosmo.imagedisplay.api.ImageDisplayApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ImageDisplayExpansion extends PlaceholderExpansion {
    private final ImageDisplay plugin;
    private final ImageDisplayApi api;

    public ImageDisplayExpansion(ImageDisplay plugin, ImageDisplayApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "imagedisplay";
    }

    @Override
    public @NotNull String getAuthor() {
        return plugin.getPluginMeta().getAuthors().isEmpty() ? "unknown" : plugin.getPluginMeta().getAuthors().get(0);
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (params.isBlank()) {
            return "";
        }

        if (params.equalsIgnoreCase("ids")) {
            return String.join(",", api.getTextureIds());
        }

        String id = stripKnownSuffix(params.trim().toLowerCase());
        if (!api.hasTexture(id)) {
            return "";
        }

        if (player != null && !api.isClientSupported(player)) {
            return api.renderFallbackTitle(player);
        }

        if (isCalledFromTab()) {
            return api.getTextureMarkup(id);
        }

        return api.getTextureToken(id);
    }

    private static boolean isCalledFromTab() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            if (className != null && className.startsWith("me.neznamy.tab.")) {
                return true;
            }
        }
        return false;
    }

    private static String stripKnownSuffix(String id) {
        String out = id;
        if (out.endsWith(".json")) {
            out = out.substring(0, out.length() - 5);
        } else if (out.endsWith(".png")) {
            out = out.substring(0, out.length() - 4);
        } else if (out.endsWith(".jpg")) {
            out = out.substring(0, out.length() - 4);
        } else if (out.endsWith(".jpeg")) {
            out = out.substring(0, out.length() - 5);
        } else if (out.endsWith(".webp")) {
            out = out.substring(0, out.length() - 5);
        }
        return out;
    }
}
