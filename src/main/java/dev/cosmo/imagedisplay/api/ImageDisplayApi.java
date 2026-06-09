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

    java.util.concurrent.CompletableFuture<String> generateTextureFromPicture(String pictureFileName, String requestedId);

    String renderFallbackTitle(Player player);

    String getTextureMarkup(String textureId);

    String getTexturePermission(String textureId);

    boolean canUseTextureInChat(Player player, String textureId);

    net.kyori.adventure.text.Component replaceBridgeTokens(net.kyori.adventure.text.Component source, Player viewer);

    boolean mayContainBridgeTokens(String raw);

    void reload();
}

