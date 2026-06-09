package dev.cosmo.imagedisplay.tab;

import dev.cosmo.imagedisplay.api.ImageDisplayApi;
import dev.cosmo.imagedisplay.i18n.MessageService;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

public final class TabApiPlaceholderBridge {
    private static final Pattern TAB_PLACEHOLDER_PATTERN = Pattern.compile("(?i)%imagedisplay_([a-z0-9_\\-\\.]+)%");

    private final Plugin plugin;
    private final ImageDisplayApi api;
    private final MessageService messages;
    private boolean enabled;
    private boolean tabLoadListenerRegistered;
    private Object tabLoadEventHandlerProxy;
    private Object boundPlaceholderManager;
    private boolean warnedAboutTabMiniMessage;

    public TabApiPlaceholderBridge(Plugin plugin, ImageDisplayApi api, MessageService messages) {
        this.plugin = plugin;
        this.api = api;
        this.messages = messages;
    }

    public void enable() {
        registerTabLoadListenerIfPossible();
        try {
            TabHandles handles = resolveHandles();
            boolean managerChanged = boundPlaceholderManager != handles.placeholderManager();

            if (!enabled || managerChanged) {
                registerDynamicPlaceholder(handles);
                boundPlaceholderManager = handles.placeholderManager();
            }
            registerExistingTexturePlaceholders(handles);
            enabled = true;
            warnIfTabMiniMessageEnabled();
            forceTabVisualRefresh();
        } catch (Throwable throwable) {
            messages.consoleWarn("log.tab.bridge-enable-failed", throwable);
        }
    }

    public void disableBinding() {
        enabled = false;
        boundPlaceholderManager = null;
    }

    public void refreshTexturePlaceholders() {
        try {
            TabHandles handles = resolveHandles();
            boolean managerChanged = boundPlaceholderManager != handles.placeholderManager();
            if (!enabled || managerChanged) {
                registerDynamicPlaceholder(handles);
                boundPlaceholderManager = handles.placeholderManager();
                enabled = true;
            }
            registerExistingTexturePlaceholders(handles);
            forceTabVisualRefresh();
        } catch (Throwable throwable) {
            if (isDiscardedTabInstanceError(throwable)) {
                return;
            }
            messages.consoleWarn("log.tab.refresh-failed", throwable);
        }
    }

    private void registerDynamicPlaceholder(TabHandles handles) throws Exception {
        Function<Matcher, Function<Object, String>> resolverFactory = matcher -> {
            String textureId = matcher.group(1);
            return tabPlayer -> api.getTextureMarkup(textureId);
        };

        handles.registerPlayerPlaceholderDynamicMethod().invoke(
                handles.placeholderManager(),
                TAB_PLACEHOLDER_PATTERN,
                1000,
                resolverFactory
        );
    }

    private void registerExistingTexturePlaceholders(TabHandles handles) throws Exception {
        Set<String> ids = api.getTextureIds();
        for (String id : ids) {
            registerStaticPlaceholder(handles, id);
        }
    }

    private void registerStaticPlaceholder(TabHandles handles, String id) throws Exception {
        String placeholder = "%imagedisplay_" + id + "%";
        Function<Object, String> function = tabPlayer -> api.getTextureMarkup(id);

        handles.registerPlayerPlaceholderStaticMethod().invoke(handles.placeholderManager(), placeholder, 1000, function);
    }

    private TabHandles resolveHandles() throws Exception {
        Plugin tabPlugin = Bukkit.getPluginManager().getPlugin("TAB");
        if (tabPlugin == null || !tabPlugin.isEnabled()) {
            throw new IllegalStateException("TAB plugin is not enabled");
        }

        ClassLoader tabClassLoader = tabPlugin.getClass().getClassLoader();
        Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI", true, tabClassLoader);
        Object tabApi = tabApiClass.getMethod("getInstance").invoke(null);
        Object placeholderManager = tabApi.getClass().getMethod("getPlaceholderManager").invoke(tabApi);
        Method registerDynamic = placeholderManager.getClass().getMethod(
                "registerPlayerPlaceholder",
                Pattern.class,
                int.class,
                Function.class
        );
        Method registerStatic = placeholderManager.getClass().getMethod(
                "registerPlayerPlaceholder",
                String.class,
                int.class,
                Function.class
        );
        return new TabHandles(placeholderManager, registerDynamic, registerStatic);
    }

    private void forceTabVisualRefresh() {
        try {
            Plugin tabPlugin = Bukkit.getPluginManager().getPlugin("TAB");
            if (tabPlugin == null || !tabPlugin.isEnabled()) {
                return;
            }

            ClassLoader tabClassLoader = tabPlugin.getClass().getClassLoader();
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI", true, tabClassLoader);
            Class<?> tabPlayerClass = Class.forName("me.neznamy.tab.api.TabPlayer", true, tabClassLoader);
            Object tabApi = tabApiClass.getMethod("getInstance").invoke(null);
            Object[] tabPlayers = (Object[]) tabApi.getClass().getMethod("getOnlinePlayers").invoke(tabApi);
            if (tabPlayers == null || tabPlayers.length == 0) {
                return;
            }

            Object nameTagManager = tabApi.getClass().getMethod("getNameTagManager").invoke(tabApi);
            Object tabListFormatManager = tabApi.getClass().getMethod("getTabListFormatManager").invoke(tabApi);

            refreshNameTags(nameTagManager, tabPlayerClass, tabPlayers);
            refreshTabList(tabListFormatManager, tabPlayerClass, tabPlayers);
        } catch (Throwable throwable) {
            if (isDiscardedTabInstanceError(throwable)) {
                return;
            }
            plugin.getLogger().log(Level.FINE, messages.plain("log.tab.force-refresh-failed"), throwable);
        }
    }

    private void refreshNameTags(Object nameTagManager, Class<?> tabPlayerClass, Object[] tabPlayers) throws Exception {
        if (nameTagManager == null) {
            return;
        }
        Method getCustomPrefix = nameTagManager.getClass().getMethod("getCustomPrefix", tabPlayerClass);
        Method getCustomSuffix = nameTagManager.getClass().getMethod("getCustomSuffix", tabPlayerClass);
        Method setPrefix = nameTagManager.getClass().getMethod("setPrefix", tabPlayerClass, String.class);
        Method setSuffix = nameTagManager.getClass().getMethod("setSuffix", tabPlayerClass, String.class);

        for (Object tabPlayer : tabPlayers) {
            if (tabPlayer == null) {
                continue;
            }
            String customPrefix = (String) getCustomPrefix.invoke(nameTagManager, tabPlayer);
            String customSuffix = (String) getCustomSuffix.invoke(nameTagManager, tabPlayer);
            setPrefix.invoke(nameTagManager, tabPlayer, customPrefix);
            setSuffix.invoke(nameTagManager, tabPlayer, customSuffix);
        }
    }

    private void refreshTabList(Object tabListFormatManager, Class<?> tabPlayerClass, Object[] tabPlayers) throws Exception {
        if (tabListFormatManager == null) {
            return;
        }
        Method getCustomPrefix = tabListFormatManager.getClass().getMethod("getCustomPrefix", tabPlayerClass);
        Method getCustomName = tabListFormatManager.getClass().getMethod("getCustomName", tabPlayerClass);
        Method getCustomSuffix = tabListFormatManager.getClass().getMethod("getCustomSuffix", tabPlayerClass);
        Method setPrefix = tabListFormatManager.getClass().getMethod("setPrefix", tabPlayerClass, String.class);
        Method setName = tabListFormatManager.getClass().getMethod("setName", tabPlayerClass, String.class);
        Method setSuffix = tabListFormatManager.getClass().getMethod("setSuffix", tabPlayerClass, String.class);

        for (Object tabPlayer : tabPlayers) {
            if (tabPlayer == null) {
                continue;
            }
            String customPrefix = (String) getCustomPrefix.invoke(tabListFormatManager, tabPlayer);
            String customName = (String) getCustomName.invoke(tabListFormatManager, tabPlayer);
            String customSuffix = (String) getCustomSuffix.invoke(tabListFormatManager, tabPlayer);
            setPrefix.invoke(tabListFormatManager, tabPlayer, customPrefix);
            setName.invoke(tabListFormatManager, tabPlayer, customName);
            setSuffix.invoke(tabListFormatManager, tabPlayer, customSuffix);
        }
    }

    private void registerTabLoadListenerIfPossible() {
        if (tabLoadListenerRegistered) {
            return;
        }
        try {
            Plugin tabPlugin = Bukkit.getPluginManager().getPlugin("TAB");
            if (tabPlugin == null || !tabPlugin.isEnabled()) {
                return;
            }
            ClassLoader tabClassLoader = tabPlugin.getClass().getClassLoader();

            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI", true, tabClassLoader);
            Object tabApi = tabApiClass.getMethod("getInstance").invoke(null);
            Object eventBus = tabApi.getClass().getMethod("getEventBus").invoke(tabApi);
            if (eventBus == null) {
                return;
            }

            Class<?> tabLoadEventClass = Class.forName("me.neznamy.tab.api.event.plugin.TabLoadEvent", true, tabClassLoader);
            Class<?> eventHandlerClass = Class.forName("me.neznamy.tab.api.event.EventHandler", true, tabClassLoader);
            Method registerMethod = eventBus.getClass().getMethod("register", Class.class, eventHandlerClass);

            tabLoadEventHandlerProxy = Proxy.newProxyInstance(
                    tabClassLoader,
                    new Class<?>[]{eventHandlerClass},
                    (proxy, method, args) -> {
                        if ("handle".equals(method.getName())) {
                            Bukkit.getScheduler().runTask(plugin, this::refreshTexturePlaceholders);
                            Bukkit.getScheduler().runTaskLater(plugin, this::refreshTexturePlaceholders, 2L);
                            Bukkit.getScheduler().runTaskLater(plugin, this::refreshTexturePlaceholders, 20L);
                        }
                        return null;
                    }
            );
            registerMethod.invoke(eventBus, tabLoadEventClass, tabLoadEventHandlerProxy);
            tabLoadListenerRegistered = true;
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, messages.plain("log.tab.load-listener-register-failed"), throwable);
        }
    }

    private static boolean isDiscardedTabInstanceError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("discarded because plugin was reloaded")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void warnIfTabMiniMessageEnabled() {
        try {
            Plugin tab = Bukkit.getPluginManager().getPlugin("TAB");
            if (tab == null || !tab.isEnabled()) {
                return;
            }

            File configFile = new File(tab.getDataFolder(), "config.yml");
            if (!configFile.isFile()) {
                return;
            }

            FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            boolean miniMessageSupport = config.getBoolean("components.minimessage-support", true);
            if (warnedAboutTabMiniMessage || !miniMessageSupport) {
                return;
            }

            warnedAboutTabMiniMessage = true;
            messages.consoleWarn("log.tab.minimessage-enabled-warning");
        } catch (Throwable ignored) {
        }
    }

    private record TabHandles(
            Object placeholderManager,
            Method registerPlayerPlaceholderDynamicMethod,
            Method registerPlayerPlaceholderStaticMethod
    ) {}
}
