package dev.cosmo.imagedisplay.command;

import dev.cosmo.imagedisplay.ImageDisplay;
import dev.cosmo.imagedisplay.api.ImageDisplayApi;
import dev.cosmo.imagedisplay.i18n.MessageService;
import dev.cosmo.imagedisplay.tab.TabApiPlaceholderBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ImageDisplayCommand implements CommandExecutor, TabCompleter {
    private final ImageDisplay plugin;
    private final ImageDisplayApi api;
    private final MessageService messages;

    public ImageDisplayCommand(ImageDisplay plugin, ImageDisplayApi api, MessageService messages) {
        this.plugin = plugin;
        this.api = api;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(messages.component("commands.usage", Map.of("label", label)));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("imagedisplay.command.reload")) {
                sender.sendMessage(messages.component("commands.no-permission"));
                return true;
            }
            api.reload();
            plugin.getMessageService().reload();
            TabApiPlaceholderBridge tabBridge = plugin.getTabBridge();
            if (tabBridge != null) {
                tabBridge.refreshTexturePlaceholders();
            }
            sender.sendMessage(messages.component("commands.reload.success"));
            return true;
        }

        if (sub.equals("generate")) {
            if (!sender.hasPermission("imagedisplay.command.generate")) {
                sender.sendMessage(messages.component("commands.no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(messages.component("commands.generate.usage", Map.of("label", label)));
                return true;
            }

            String pictureFile = args[1];
            String requestedId = args.length >= 3 ? args[2] : "";
            sender.sendMessage(messages.component("commands.generate.started", Map.of("picture", pictureFile)));

            CompletableFuture<String> future = api.generateTextureFromPicture(pictureFile, requestedId);
            future.whenComplete((textureId, throwable) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (throwable == null) {
                            TabApiPlaceholderBridge tabBridge = plugin.getTabBridge();
                            if (tabBridge != null) {
                                tabBridge.refreshTexturePlaceholders();
                            }
                            sender.sendMessage(messages.component("commands.generate.success", Map.of(
                                    "id", textureId,
                                    "placeholder", "%imagedisplay_" + textureId + "%"
                            )));
                            return;
                        }
                        String reason = rootCauseMessage(throwable);
                        sender.sendMessage(messages.component("commands.generate.failed", Map.of("reason", reason)));
                    }));
            return true;
        }


        sender.sendMessage(messages.component("commands.usage", Map.of("label", label)));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String label,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            return filterPrefix(List.of("reload", "generate"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("generate")) {
            return filterPrefix(api.getPictureFileNames(), args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("generate")) {
            return List.of(deriveId(args[1]));
        }


        return List.of();
    }

    private static List<String> filterPrefix(List<String> values, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return values;
        }

        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                out.add(value);
            }
        }
        return out;
    }

    private static String deriveId(String fileName) {
        String source = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        int dot = source.lastIndexOf('.');
        if (dot > 0) {
            source = source.substring(0, dot);
        }
        source = source.replaceAll("[^a-z0-9_\\-]", "-");
        source = source.replaceAll("^-+", "").replaceAll("-+$", "");
        return source.isEmpty() ? "picture" : source;
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
