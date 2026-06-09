package dev.cosmo.imagedisplay.i18n;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MessageService {
    private static final String DEFAULT_LOCALE = "en";

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration active;
    private YamlConfiguration fallback;
    private String activeLocale = DEFAULT_LOCALE;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        ensureDefaultMessageFiles();
        this.fallback = loadLocaleFile(DEFAULT_LOCALE);

        String locale = plugin.getConfig().getString("ImageDisplay.locale", DEFAULT_LOCALE);
        String normalized = locale == null ? DEFAULT_LOCALE : locale.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            normalized = DEFAULT_LOCALE;
        }

        this.activeLocale = normalized;
        this.active = loadLocaleFile(normalized);
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, String> placeholders) {
        String template = template(key);
        if (placeholders.isEmpty()) {
            return miniMessage.deserialize(template);
        }

        List<TagResolver> resolvers = new ArrayList<>(placeholders.size());
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolvers.add(Placeholder.parsed(entry.getKey(), Objects.toString(entry.getValue(), "")));
        }
        return miniMessage.deserialize(template, TagResolver.resolver(resolvers));
    }

    public String raw(String key) {
        return template(key);
    }

    public String plain(String key, Map<String, String> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, placeholders));
    }

    public String plain(String key) {
        return plain(key, Map.of());
    }

    public String locale() {
        return activeLocale;
    }

    public void consoleInfo(String key) {
        consoleInfo(key, Map.of());
    }

    public void consoleInfo(String key, Map<String, String> placeholders) {
        sendConsole(component("log.console.level-info"), component(key, placeholders));
    }

    public void consoleInfo(Component message) {
        sendConsole(component("log.console.level-info"), message == null ? Component.empty() : message);
    }

    public void consoleWarn(String key) {
        consoleWarn(key, Map.of());
    }

    public void consoleWarn(String key, Map<String, String> placeholders) {
        sendConsole(component("log.console.level-warn"), component(key, placeholders));
    }

    public void consoleWarn(String key, Throwable throwable) {
        consoleWarn(key, Map.of(), throwable);
    }

    public void consoleWarn(String key, Map<String, String> placeholders, Throwable throwable) {
        sendConsole(component("log.console.level-warn"), component(key, placeholders));
        if (throwable != null) {
            throwable.printStackTrace();
        }
    }

    private void sendConsole(Component level, Component message) {
        Component line = component("log.console.prefix")
                .append(Component.space())
                .append(level)
                .append(Component.space())
                .append(message);
        Bukkit.getConsoleSender().sendMessage(line);
    }

    private String template(String key) {
        if (active != null && active.contains(key)) {
            return Objects.toString(active.getString(key), key);
        }
        if (fallback != null && fallback.contains(key)) {
            return Objects.toString(fallback.getString(key), key);
        }
        return key;
    }

    private void ensureDefaultMessageFiles() {
        File dir = new File(plugin.getDataFolder(), "messages");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }

        copyIfMissing("messages/en.yml");
        copyIfMissing("messages/ru.yml");
    }

    private void copyIfMissing(String resourcePath) {
        File out = new File(plugin.getDataFolder(), resourcePath);
        if (!out.exists()) {
            plugin.saveResource(resourcePath, false);
        }
    }

    private YamlConfiguration loadLocaleFile(String locale) {
        String effectiveLocale = locale;
        File file = new File(plugin.getDataFolder(), "messages/" + effectiveLocale + ".yml");
        if (!file.exists()) {
            effectiveLocale = DEFAULT_LOCALE;
            file = new File(plugin.getDataFolder(), "messages/" + DEFAULT_LOCALE + ".yml");
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        String resourcePath = "messages/" + effectiveLocale + ".yml";
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(input, StandardCharsets.UTF_8)
                );
                configuration.setDefaults(defaults);
            }
        } catch (Exception ignored) {
        }
        return configuration;
    }
}
