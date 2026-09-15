package dev.cosmo.imagedisplay;

import dev.cosmo.imagedisplay.api.ImageDisplayApi;
import dev.cosmo.imagedisplay.bridge.ProtocolLibComponentBridge;
import dev.cosmo.imagedisplay.chat.ChatPlaceholderPermissionListener;
import dev.cosmo.imagedisplay.command.ImageDisplayCommand;
import dev.cosmo.imagedisplay.i18n.MessageService;
import dev.cosmo.imagedisplay.papi.ImageDisplayExpansion;
import dev.cosmo.imagedisplay.service.ImageDisplayService;
import dev.cosmo.imagedisplay.tab.TabApiPlaceholderBridge;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class ImageDisplay extends JavaPlugin implements Listener {
    private static final String[][] STARTUP_GRADIENTS = new String[][]{
            {"#7B61FF", "#28D7FF"},
            {"#8B6FFF", "#3CE7D2"},
            {"#9A7DFF", "#56F2AF"}
    };

    private ImageDisplayService service;
    private MessageService messageService;
    private ProtocolLibComponentBridge bridge;
    private TabApiPlaceholderBridge tabBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messageService = new MessageService(this);
        this.messageService.reload();
        printStartupAsciiLogo();

        this.service = new ImageDisplayService(this, messageService);
        this.service.reload();

        Bukkit.getServicesManager().register(ImageDisplayApi.class, service, this, ServicePriority.Normal);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new ImageDisplayExpansion(this, service).register();
        }
        if (Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            this.bridge = new ProtocolLibComponentBridge(this, service, messageService);
            this.bridge.enable();
        }
        if (Bukkit.getPluginManager().isPluginEnabled("TAB")) {
            this.tabBridge = new TabApiPlaceholderBridge(this, service, messageService);
            this.tabBridge.enable();
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new ChatPlaceholderPermissionListener(service), this);

        ImageDisplayCommand command = new ImageDisplayCommand(this, service, messageService);
        Objects.requireNonNull(getCommand("imagedisplay")).setExecutor(command);
        Objects.requireNonNull(getCommand("imagedisplay")).setTabCompleter(command);

        messageService.consoleInfo("plugin.enabled", java.util.Map.of("locale", messageService.locale()));
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.cancelAllGenerations();
        }
        if (bridge != null) {
            bridge.disable();
        }
        Bukkit.getServicesManager().unregister(ImageDisplayApi.class, service);
    }

    public ImageDisplayApi getApi() {
        return service;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public ProtocolLibComponentBridge getBridge() {
        return bridge;
    }

    public TabApiPlaceholderBridge getTabBridge() {
        return tabBridge;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!"TAB".equalsIgnoreCase(event.getPlugin().getName())) {
            return;
        }
        if (tabBridge == null) {
            tabBridge = new TabApiPlaceholderBridge(this, service, messageService);
        }
        tabBridge.enable();
        scheduleTabPlaceholderRefresh();
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (!"TAB".equalsIgnoreCase(event.getPlugin().getName())) {
            return;
        }
        if (tabBridge != null) {
            tabBridge.disableBinding();
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String command = event.getCommand();
        if (command == null) {
            return;
        }
        String normalized = command.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("tab reload") && !normalized.equals("/tab reload")) {
            return;
        }
        scheduleTabBridgeRebind();
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message == null) {
            return;
        }
        String normalized = message.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("/tab reload")) {
            return;
        }
        scheduleTabBridgeRebind();
    }

    private void scheduleTabPlaceholderRefresh() {
        if (tabBridge == null) {
            return;
        }
        // так надо
        Bukkit.getScheduler().runTaskLater(this, tabBridge::refreshTexturePlaceholders, 1L);
        Bukkit.getScheduler().runTaskLater(this, tabBridge::refreshTexturePlaceholders, 20L);
        Bukkit.getScheduler().runTaskLater(this, tabBridge::refreshTexturePlaceholders, 60L);
        Bukkit.getScheduler().runTaskLater(this, tabBridge::refreshTexturePlaceholders, 120L);
        Bukkit.getScheduler().runTaskLater(this, tabBridge::refreshTexturePlaceholders, 200L);
    }

    private void scheduleTabBridgeRebind() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("TAB")) {
                return;
            }
            if (tabBridge == null) {
                tabBridge = new TabApiPlaceholderBridge(this, service, messageService);
            }
            tabBridge.enable();
            scheduleTabPlaceholderRefresh();
        }, 2L);
    }

    private void printStartupAsciiLogo() {
        List<String> lines = loadAsciiLines();
        if (lines.isEmpty()) {
            return;
        }

        MiniMessage miniMessage = MiniMessage.miniMessage();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] gradient = STARTUP_GRADIENTS[i % STARTUP_GRADIENTS.length];
            String escapedLine = miniMessage.escapeTags(line);
            Component component = miniMessage.deserialize(
                    "<gradient:" + gradient[0] + ":" + gradient[1] + ">" + escapedLine + "</gradient>"
            );
            messageService.consoleInfo(component);
        }
    }

    private List<String> loadAsciiLines() {
        File externalAscii = new File(getDataFolder(), "ascii.md");
        if (externalAscii.isFile()) {
            try {
                return Files.readAllLines(externalAscii.toPath(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }

        List<String> lines = new ArrayList<>();
        try (InputStream input = getResource("ascii.md")) {
            if (input == null) {
                return lines;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        } catch (IOException ignored) {
        }
        return lines;
    }
}
