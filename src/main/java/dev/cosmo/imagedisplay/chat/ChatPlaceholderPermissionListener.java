package dev.cosmo.imagedisplay.chat;

import dev.cosmo.imagedisplay.api.ImageDisplayApi;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class ChatPlaceholderPermissionListener implements Listener {
    private static final Pattern IMAGE_PLACEHOLDER_PATTERN = Pattern.compile("(?i)%imagedisplay_([a-z0-9_\\-.]+)%");

    private final ImageDisplayApi api;

    public ChatPlaceholderPermissionListener(ImageDisplayApi api) {
        this.api = api;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (!canSend(event.getPlayer(), message)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyAsyncChat(AsyncPlayerChatEvent event) {
        if (!canSend(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    private boolean canSend(Player player, String message) {
        if (message == null || message.isBlank()) {
            return true;
        }

        Matcher matcher = IMAGE_PLACEHOLDER_PATTERN.matcher(message);
        while (matcher.find()) {
            String textureId = matcher.group(1);
            if (!api.canUseTextureInChat(player, textureId)) {
                return false;
            }
        }
        return true;
    }
}
