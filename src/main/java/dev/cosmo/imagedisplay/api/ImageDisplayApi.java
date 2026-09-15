package dev.cosmo.imagedisplay.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public interface ImageDisplayApi {
    Component resolveTitle(Player player, String defaultTitleTemplate);

    Component resolveTemplate(Player player, String template);

    boolean isEnabled();

    boolean isClientSupported(Player player);

    boolean hasTexture(String textureId);

    String getTextureToken(String textureId);

    java.util.Set<String> getTextureIds();

    java.util.List<String> getPictureFileNames();

    @FunctionalInterface
    interface GenerationProgressListener {
        void onProgress(int currentTile, int totalTiles, String statusKey);
    }

    java.util.Set<Integer> getAvailableApiKeySlots();

    default java.util.concurrent.CompletableFuture<String> generateTextureFromPicture(String pictureFileName, String requestedId) {
        return generateTextureFromPicture(pictureFileName, requestedId, null, null);
    }

    default java.util.concurrent.CompletableFuture<String> generateTextureFromPicture(String pictureFileName, String requestedId, GenerationProgressListener listener) {
        return generateTextureFromPicture(pictureFileName, requestedId, null, listener);
    }

    java.util.concurrent.CompletableFuture<String> generateTextureFromPicture(String pictureFileName, String requestedId, Integer apiKeySlot, GenerationProgressListener listener);

    boolean cancelGeneration(String textureId);

    boolean cancelAllGenerations();

    java.util.Set<String> getActiveGenerationIds();

    boolean isGenerationActive();

    String renderFallbackTitle(Player player);

    String getTextureMarkup(String textureId);

    String getTexturePermission(String textureId);

    boolean canUseTextureInChat(Player player, String textureId);

    net.kyori.adventure.text.Component replaceBridgeTokens(net.kyori.adventure.text.Component source, Player viewer);

    boolean mayContainBridgeTokens(String raw);

    void reload();
}

