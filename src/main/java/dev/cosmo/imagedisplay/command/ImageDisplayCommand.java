package dev.cosmo.imagedisplay.command;

import dev.cosmo.imagedisplay.ImageDisplay;
import dev.cosmo.imagedisplay.api.ImageDisplayApi;
import dev.cosmo.imagedisplay.i18n.MessageService;
import dev.cosmo.imagedisplay.tab.TabApiPlaceholderBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
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

            Integer requestedKeySlot = null;
            List<String> positional = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.equalsIgnoreCase("--apikey")) {
                    if (i + 1 < args.length) {
                        try {
                            requestedKeySlot = Integer.parseInt(args[i + 1]);
                            i++;
                        } catch (NumberFormatException ex) {
                            sender.sendMessage(messages.component("commands.generate.invalid-apikey", Map.of("key", args[i + 1])));
                            return true;
                        }
                    } else {
                        sender.sendMessage(messages.component("commands.generate.usage", Map.of("label", label)));
                        return true;
                    }
                } else if (arg.toLowerCase(Locale.ROOT).startsWith("--apikey=")) {
                    String val = arg.substring("--apikey=".length());
                    try {
                        requestedKeySlot = Integer.parseInt(val);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(messages.component("commands.generate.invalid-apikey", Map.of("key", val)));
                        return true;
                    }
                } else {
                    positional.add(arg);
                }
            }

            if (positional.isEmpty()) {
                sender.sendMessage(messages.component("commands.generate.usage", Map.of("label", label)));
                return true;
            }

            Set<Integer> availableSlots = api.getAvailableApiKeySlots();
            if (availableSlots.isEmpty()) {
                sender.sendMessage(messages.component("commands.generate.no-apikeys"));
                return true;
            }

            int resolvedSlot;
            if (requestedKeySlot != null) {
                if (!availableSlots.contains(requestedKeySlot)) {
                    String availableStr = availableSlots.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
                    sender.sendMessage(messages.component("commands.generate.apikey-not-found", Map.of(
                            "slot", String.valueOf(requestedKeySlot),
                            "available", availableStr
                    )));
                    return true;
                }
                resolvedSlot = requestedKeySlot;
            } else {
                if (availableSlots.size() == 1) {
                    resolvedSlot = availableSlots.iterator().next();
                } else {
                    List<Integer> slotsList = new ArrayList<>(availableSlots);
                    resolvedSlot = slotsList.get(ThreadLocalRandom.current().nextInt(slotsList.size()));
                }
            }

            String pictureFile = positional.get(0);
            String requestedId = positional.size() >= 2 ? positional.get(1) : "";
            sender.sendMessage(messages.component("commands.generate.started", Map.of(
                    "picture", pictureFile,
                    "key", String.valueOf(resolvedSlot)
            )));

            BossBar bossBar = null;
            if (sender instanceof Player player) {
                bossBar = BossBar.bossBar(
                        messages.component("commands.generate.bossbar.title", Map.of(
                                "picture", pictureFile,
                                "key", String.valueOf(resolvedSlot),
                                "current", "0",
                                "total", "0",
                                "percent", "0",
                                "status", messages.plain("commands.generate.bossbar.status-preparing")
                        )),
                        0.0f,
                        BossBar.Color.PURPLE,
                        BossBar.Overlay.PROGRESS
                );
                player.showBossBar(bossBar);
            }

            final BossBar activeBossBar = bossBar;
            final int slot = resolvedSlot;
            final AtomicInteger totalTilesRef = new AtomicInteger(0);
            ImageDisplayApi.GenerationProgressListener listener = (currentTile, totalTiles, statusKey) -> {
                totalTilesRef.set(totalTiles);
                float progress = totalTiles <= 0 ? 0.0f : Math.min(1.0f, (float) currentTile / (float) totalTiles);
                int percent = Math.round(progress * 100.0f);
                String statusText = switch (statusKey) {
                    case "queued" -> messages.plain("commands.generate.bossbar.status-queued");
                    case "active" -> messages.plain("commands.generate.bossbar.status-active");
                    case "completed" -> messages.plain("commands.generate.bossbar.status-completed");
                    default -> messages.plain("commands.generate.bossbar.status-preparing");
                };

                if (activeBossBar != null) {
                    activeBossBar.progress(progress);
                    activeBossBar.name(messages.component("commands.generate.bossbar.title", Map.of(
                            "picture", pictureFile,
                            "key", String.valueOf(slot),
                            "current", String.valueOf(currentTile),
                            "total", String.valueOf(totalTiles),
                            "percent", String.valueOf(percent),
                            "status", statusText
                    )));
                }

                if ("completed".equalsIgnoreCase(statusKey)) {
                    messages.consoleInfo("commands.generate.console-progress", Map.of(
                            "picture", pictureFile,
                            "key", String.valueOf(slot),
                            "current", String.valueOf(currentTile),
                            "total", String.valueOf(totalTiles),
                            "percent", String.valueOf(percent),
                            "status", statusText
                    ));
                }
            };

            CompletableFuture<String> future = api.generateTextureFromPicture(pictureFile, requestedId, resolvedSlot, listener);
            future.whenComplete((textureId, throwable) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (activeBossBar != null && sender instanceof Player player) {
                            if (throwable == null) {
                                activeBossBar.progress(1.0f);
                                activeBossBar.color(BossBar.Color.GREEN);
                                int finalTotal = totalTilesRef.get();
                                String countStr = finalTotal > 0 ? String.valueOf(finalTotal) : "100";
                                activeBossBar.name(messages.component("commands.generate.bossbar.title", Map.of(
                                        "picture", pictureFile,
                                        "key", String.valueOf(slot),
                                        "current", countStr,
                                        "total", countStr,
                                        "percent", "100",
                                        "status", messages.plain("commands.generate.bossbar.status-completed")
                                )));
                                plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.hideBossBar(activeBossBar), 60L);
                            } else {
                                player.hideBossBar(activeBossBar);
                            }
                        }

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

                        if (isCancellation(throwable)) {
                            if (sender instanceof Player) {
                                messages.consoleInfo("commands.generate.console-cancelled", Map.of("picture", pictureFile));
                            }
                            sender.sendMessage(messages.component("commands.generate.cancelled", Map.of("picture", pictureFile)));
                            return;
                        }

                        String reason = rootCauseMessage(throwable);
                        sender.sendMessage(messages.component("commands.generate.failed", Map.of("reason", reason)));
                    }));
            return true;
        }

        if (sub.equals("cancel")) {
            if (!sender.hasPermission("imagedisplay.command.generate")) {
                sender.sendMessage(messages.component("commands.no-permission"));
                return true;
            }

            if (args.length >= 2) {
                String target = args[1].trim();
                if (target.equalsIgnoreCase("all") || target.equals("*")) {
                    boolean cancelled = api.cancelAllGenerations();
                    if (cancelled) {
                        sender.sendMessage(messages.component("commands.cancel.success-all"));
                    } else {
                        sender.sendMessage(messages.component("commands.cancel.no-active"));
                    }
                    return true;
                }

                boolean cancelled = api.cancelGeneration(target);
                if (cancelled) {
                    sender.sendMessage(messages.component("commands.cancel.success", Map.of("id", target)));
                } else {
                    sender.sendMessage(messages.component("commands.cancel.not-found", Map.of("id", target)));
                }
                return true;
            }

            Set<String> activeIds = api.getActiveGenerationIds();
            if (activeIds.isEmpty()) {
                sender.sendMessage(messages.component("commands.cancel.no-active"));
                return true;
            }

            if (activeIds.size() == 1) {
                String singleId = activeIds.iterator().next();
                api.cancelGeneration(singleId);
                sender.sendMessage(messages.component("commands.cancel.success", Map.of("id", singleId)));
                return true;
            }

            sender.sendMessage(messages.component("commands.cancel.multiple-active", Map.of(
                    "ids", String.join(", ", activeIds),
                    "label", label
            )));
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
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("imagedisplay.command.reload")) {
                subs.add("reload");
            }
            if (sender.hasPermission("imagedisplay.command.generate")) {
                subs.add("generate");
                subs.add("cancel");
            }
            return filterPrefix(subs, args[0]);
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("generate") && sender.hasPermission("imagedisplay.command.generate")) {
            String current = args[args.length - 1];
            String prev = args[args.length - 2];

            if (prev.equalsIgnoreCase("--apikey")) {
                List<String> slotStrings = api.getAvailableApiKeySlots().stream()
                        .map(String::valueOf)
                        .toList();
                return filterPrefix(slotStrings, current);
            }

            boolean hasApikeyFlag = false;
            for (int i = 1; i < args.length - 1; i++) {
                if (args[i].equalsIgnoreCase("--apikey") || args[i].toLowerCase(Locale.ROOT).startsWith("--apikey=")) {
                    hasApikeyFlag = true;
                    break;
                }
            }

            if (args.length == 2) {
                return filterPrefix(api.getPictureFileNames(), current);
            }

            if (args.length == 3) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add(deriveId(args[1]));
                if (!hasApikeyFlag) {
                    suggestions.add("--apikey");
                }
                return filterPrefix(suggestions, current);
            }

            if (args.length == 4 && !hasApikeyFlag) {
                return filterPrefix(List.of("--apikey"), current);
            }

            return List.of();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("cancel") && sender.hasPermission("imagedisplay.command.generate")) {
            List<String> options = new ArrayList<>(api.getActiveGenerationIds());
            if (options.size() > 1) {
                options.add("all");
            }
            return filterPrefix(options, args[1]);
        }

        return List.of();
    }

    private static boolean isCancellation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof CancellationException || cursor instanceof InterruptedException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
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
