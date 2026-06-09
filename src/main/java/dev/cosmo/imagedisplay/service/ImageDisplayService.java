package dev.cosmo.imagedisplay.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.cosmo.imagedisplay.api.ImageDisplayApi;
import dev.cosmo.imagedisplay.i18n.MessageService;
import dev.cosmo.imagedisplay.util.TextFormatUtil;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class ImageDisplayService implements ImageDisplayApi {
    private static final Pattern TEXTURE_TAG_PATTERN = Pattern.compile("(?i)<texture:([a-z0-9_\\-]+)>");
    private static final Pattern IMAGE_EXTENSION_PATTERN = Pattern.compile("(?i).+\\.(png|jpg|jpeg|webp)$");
    private static final Pattern INVALID_ID_PATTERN = Pattern.compile("[^a-z0-9_\\-]");
    private static final Pattern BRIDGE_TOKEN_PATTERN = Pattern.compile("__IMAGEDISPLAY_([a-z0-9_\\-]+)__");
    private static final Pattern RAW_PLACEHOLDER_PATTERN = Pattern.compile("(?i)%imagedisplay_([a-z0-9_\\-\\.]+)%");
    private static final String BRIDGE_TOKEN_PREFIX = "__IMAGEDISPLAY_";
    private static final String BRIDGE_TOKEN_SUFFIX = "__";
    private static final int TILE_SIZE = 8;
    private static final int SKIN_SIZE = 64;
    private static final int DEFAULT_MIN_PROTOCOL_1_21_9 = 773;
    private static final int MAX_MINE_SKIN_RETRIES = 8;
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 3500L;

    private final Plugin plugin;
    private final MessageService messages;
    private final HttpClient httpClient;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private volatile boolean enabled;
    private volatile String fallbackTitle;
    private volatile String apiKey;
    private volatile int minClientProtocol = DEFAULT_MIN_PROTOCOL_1_21_9;
    private volatile File picturesFolder;
    private volatile File cacheFolder;

    private final Map<String, Component> textureComponents = new ConcurrentHashMap<>();
    private final Map<String, String> textureMarkup = new ConcurrentHashMap<>();
    private final Map<String, CachedTexture> cachedTextures = new ConcurrentHashMap<>();

    public ImageDisplayService(Plugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .build();
    }

    @Override
    public void reload() {
        plugin.reloadConfig();
        enabled = plugin.getConfig().getBoolean("ImageDisplay.enabled", true);
        fallbackTitle = plugin.getConfig().getString("ImageDisplay.fallback-title", "");
        apiKey = trimToEmpty(plugin.getConfig().getString("ImageDisplay.mineskin-api-key"));
        minClientProtocol = resolveMinProtocol(plugin.getConfig().getString("ImageDisplay.min-client-version", "1.21.9"));

        String picturesPath = trimToEmpty(plugin.getConfig().getString("ImageDisplay.pictures-folder"));
        String cachePath = trimToEmpty(plugin.getConfig().getString("ImageDisplay.cache-folder"));
        if (picturesPath.isEmpty()) {
            picturesPath = "pictures";
        }
        if (cachePath.isEmpty()) {
            cachePath = "pictures_cache";
        }

        picturesFolder = safeFolderUnderData(picturesPath);
        cacheFolder = safeFolderUnderData(cachePath);
        ensureDirectory(picturesFolder);
        ensureDirectory(cacheFolder);

        textureComponents.clear();
        textureMarkup.clear();
        cachedTextures.clear();
        loadDiskCache();
        buildComponentsFromCache();
    }

    @Override
    public Component resolveTitle(Player player, String defaultTitleTemplate) {
        return resolveTemplate(player, defaultTitleTemplate);
    }

    @Override
    public Component resolveTemplate(Player player, String template) {
        if (!enabled) {
            return TextFormatUtil.parseComponent(TextFormatUtil.applyPlaceholders(player, template));
        }

        String prepared = TextFormatUtil.applyPlaceholders(player, template == null ? "" : template);
        if (!containsTextureTag(prepared)) {
            return TextFormatUtil.parseComponent(prepared);
        }
        if (!isClientSupported(player)) {
            return TextFormatUtil.parseComponent(renderFallbackTitle(player));
        }

        Component replaced = replaceTextureTags(prepared);
        if (replaced == null) {
            return TextFormatUtil.parseComponent(renderFallbackTitle(player));
        }
        return replaced;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isClientSupported(Player player) {
        if (player == null) {
            return false;
        }

        int protocol = resolvePlayerProtocol(player);
        if (protocol <= 0) {
            return true;
        }
        return protocol >= minClientProtocol;
    }

    @Override
    public boolean hasTexture(String textureId) {
        if (textureId == null || textureId.isBlank()) {
            return false;
        }
        return textureComponents.containsKey(normalizeTextureId(textureId));
    }

    @Override
    public String getTextureToken(String textureId) {
        String normalized = normalizeTextureId(textureId);
        if (!textureComponents.containsKey(normalized)) {
            return "";
        }
        return buildBridgeToken(normalized);
    }

    @Override
    public Set<String> getTextureIds() {
        return Set.copyOf(textureComponents.keySet());
    }

    @Override
    public List<String> getPictureFileNames() {
        File dir = picturesFolder;
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return List.of();
        }

        File[] files = dir.listFiles(file -> file.isFile() && IMAGE_EXTENSION_PATTERN.matcher(file.getName()).matches());
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<String> names = new ArrayList<>(files.length);
        for (File file : files) {
            names.add(file.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    @Override
    public CompletableFuture<String> generateTextureFromPicture(String pictureFileName, String requestedId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (apiKey.isBlank()) {
                    throw new IllegalStateException("MineSkin API key is empty");
                }

                File imageFile = resolvePictureFile(pictureFileName);
                if (imageFile == null || !imageFile.exists() || !imageFile.isFile()) {
                    throw new IllegalArgumentException("Picture file not found: " + pictureFileName);
                }

                String textureId = requestedId == null || requestedId.isBlank()
                        ? deriveIdFromFileName(imageFile.getName())
                        : normalizeTextureId(requestedId);
                if (textureId.isEmpty()) {
                    throw new IllegalArgumentException("Invalid texture id: " + requestedId);
                }

                List<List<String>> valuesGrid = generateTextureValuesGrid(imageFile, textureId);
                if (valuesGrid.isEmpty()) {
                    throw new IllegalStateException("No texture tiles were generated");
                }

                Component component = buildTextureComponent(valuesGrid);
                String markup = buildTextureMarkup(valuesGrid);
                textureComponents.put(textureId, component);
                textureMarkup.put(textureId, markup);
                CachedTexture cached = toCached(textureId, imageFile.getName(), imageFile.lastModified(), valuesGrid);
                cachedTextures.put(textureId, cached);
                saveCacheEntry(cached);
                future.complete(textureId);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    @Override
    public String renderFallbackTitle(Player player) {
        return TextFormatUtil.applyPlaceholders(player, fallbackTitle == null ? "" : fallbackTitle);
    }

    @Override
    public String getTextureMarkup(String textureId) {
        if (textureId == null || textureId.isBlank()) {
            return "";
        }
        String id = normalizeTextureId(stripKnownSuffix(textureId));
        if (id.isEmpty()) {
            return "";
        }
        return textureMarkup.getOrDefault(id, "");
    }

    @Override
    public String getTexturePermission(String textureId) {
        String id = normalizeTextureId(stripKnownSuffix(textureId));
        if (id.isEmpty()) {
            return "";
        }

        CachedTexture cached = cachedTextures.get(id);
        if (cached != null && cached.permission != null && !cached.permission.isBlank()) {
            return cached.permission.trim();
        }
        return buildTexturePermission(id);
    }

    @Override
    public boolean canUseTextureInChat(Player player, String textureId) {
        if (player == null) {
            return false;
        }

        String id = normalizeTextureId(stripKnownSuffix(textureId));
        if (id.isEmpty() || !hasTexture(id)) {
            return true;
        }

        if (player.isOp() || player.hasPermission("*")) {
            return true;
        }

        String permission = getTexturePermission(id);
        return permission.isBlank() || player.hasPermission(permission);
    }

    @Override
    public Component replaceBridgeTokens(Component source, Player viewer) {
        if (source == null) {
            return Component.empty();
        }

        String plain = PlainTextComponentSerializer.plainText().serialize(source);
        if (!mayContainBridgeTokens(plain)) {
            return source;
        }

        Component result = source;
        Matcher matcher = BRIDGE_TOKEN_PATTERN.matcher(plain);
        while (matcher.find()) {
            String id = normalizeTextureId(matcher.group(1));
            Component replacement = resolveReplacementForViewer(id, viewer);
            if (replacement == null) {
                continue;
            }
            String token = buildBridgeToken(id);
            result = replaceLiteral(result, token, replacement);
        }

        Matcher rawMatcher = RAW_PLACEHOLDER_PATTERN.matcher(plain);
        while (rawMatcher.find()) {
            String rawId = rawMatcher.group(1);
            String id = normalizeTextureId(stripKnownSuffix(rawId));
            Component replacement = resolveReplacementForViewer(id, viewer);
            if (replacement == null) {
                continue;
            }

            String rawToken = rawMatcher.group(0);
            result = replaceLiteral(result, rawToken, replacement);
        }
        return result;
    }

    @Override
    public boolean mayContainBridgeTokens(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        return (raw.contains(BRIDGE_TOKEN_PREFIX) && raw.contains(BRIDGE_TOKEN_SUFFIX))
                || raw.toLowerCase().contains("%imagedisplay_");
    }

    private Component replaceLiteral(Component source, String literal, Component replacement) {
        return source.replaceText(TextReplacementConfig.builder()
                .matchLiteral(literal)
                .replacement(replacement)
                .build());
    }

    private boolean containsTextureTag(String input) {
        return input != null && TEXTURE_TAG_PATTERN.matcher(input).find();
    }

    private Component replaceTextureTags(String preparedTemplate) {
        Matcher matcher = TEXTURE_TAG_PATTERN.matcher(preparedTemplate);
        int cursor = 0;
        Component result = Component.empty();
        boolean foundAny = false;

        while (matcher.find()) {
            foundAny = true;
            String before = preparedTemplate.substring(cursor, matcher.start());
            if (!before.isEmpty()) {
                result = result.append(TextFormatUtil.parseComponent(before));
            }

            String textureKey = normalizeTextureId(matcher.group(1));
            Component texture = textureComponents.get(textureKey);
            if (texture == null) {
                return null;
            }

            result = result.append(texture);
            cursor = matcher.end();
        }

        if (!foundAny) {
            return TextFormatUtil.parseComponent(preparedTemplate);
        }
        if (cursor < preparedTemplate.length()) {
            result = result.append(TextFormatUtil.parseComponent(preparedTemplate.substring(cursor)));
        }
        return result;
    }

    private List<List<String>> generateTextureValuesGrid(File imageFile, String textureId) throws IOException, InterruptedException {
        BufferedImage source = ImageIO.read(imageFile);
        if (source == null) {
            throw new IOException("Unsupported image format: " + imageFile.getName());
        }
        if (source.getWidth() % TILE_SIZE != 0 || source.getHeight() % TILE_SIZE != 0) {
            throw new IOException("Image dimensions must be multiples of 8: " + imageFile.getName());
        }

        int cols = source.getWidth() / TILE_SIZE;
        int rows = source.getHeight() / TILE_SIZE;
        List<List<String>> valuesGrid = new ArrayList<>(rows);
        Map<String, String> localTileCache = new HashMap<>();

        for (int tileY = 0; tileY < rows; tileY++) {
            List<String> row = new ArrayList<>(cols);
            for (int tileX = 0; tileX < cols; tileX++) {
                BufferedImage tile = source.getSubimage(tileX * TILE_SIZE, tileY * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                byte[] skinPng = toPngBytes(createSkinFromTile(tile));
                String tileHash = Base64.getEncoder().encodeToString(skinPng);

                String token = localTileCache.get(tileHash);
                if (token == null) {
                    token = requestSkinToken(skinPng, textureId + "_" + tileY + "_" + tileX);
                    localTileCache.put(tileHash, token);
                }
                row.add(token);
            }
            valuesGrid.add(row);
        }
        return valuesGrid;
    }

    private String requestSkinToken(byte[] skinPng, String requestName) throws IOException, InterruptedException {
        String boundary = "----ImageDisplayMineskin" + UUID.randomUUID();
        List<byte[]> parts = new ArrayList<>();
        parts.add(formField(boundary, "name", requestName));
        parts.add(fileFieldHeader(boundary, "file", requestName + ".png", "image/png"));
        parts.add(skinPng);
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
        parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mineskin.org/v2/generate"))
                .timeout(Duration.ofSeconds(35))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .header("User-Agent", "ImageDisplay/1.0");

        if (!apiKey.isBlank()) {
            String auth = apiKey.startsWith("Bearer ") ? apiKey : "Bearer " + apiKey;
            requestBuilder.header("Authorization", auth);
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                .build();

        return executeMineSkinRequestWithRetry(request);
    }

    private String executeMineSkinRequestWithRetry(HttpRequest request) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= MAX_MINE_SKIN_RETRIES; attempt++) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                SignedTextureData data = extractSignedTextureData(response.body());
                if (data == null || data.value() == null || data.value().isBlank()) {
                    throw new IOException("MineSkin response does not contain skin texture value");
                }
                return data.signature() == null || data.signature().isBlank()
                        ? data.value()
                        : data.value() + ";" + data.signature();
            }

            if (code == 429 || (code >= 500 && code <= 599)) {
                long retryDelay = resolveRetryDelayMillis(response, attempt);
                if (attempt == 1 || attempt == MAX_MINE_SKIN_RETRIES) {
                    messages.consoleWarn("log.service.mineskin-limit", java.util.Map.of(
                            "code", String.valueOf(code),
                            "attempt", String.valueOf(attempt),
                            "max_attempts", String.valueOf(MAX_MINE_SKIN_RETRIES),
                            "delay_ms", String.valueOf(retryDelay)
                    ));
                }
                Thread.sleep(retryDelay);
                continue;
            }

            throw new IOException("MineSkin response code " + code);
        }
        throw new IOException("MineSkin rate limit retries exceeded");
    }

    private void loadDiskCache() {
        File dir = cacheFolder;
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }

        File[] files = dir.listFiles(file -> file.isFile() && file.getName().toLowerCase().endsWith(".json"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            try {
                String json = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
                CachedTexture cache = gson.fromJson(json, CachedTexture.class);
                if (cache == null || cache.id == null || cache.id.isBlank() || cache.rows == null || cache.rows.isEmpty()) {
                    continue;
                }
                String normalizedId = normalizeTextureId(cache.id);
                if (normalizedId.isEmpty()) {
                    continue;
                }
                cache.id = normalizedId;
                if (cache.permission == null || cache.permission.isBlank()) {
                    cache.permission = buildTexturePermission(normalizedId);
                    try {
                        saveCacheEntry(cache);
                    } catch (IOException ignored) {
                    }
                } else {
                    cache.permission = cache.permission.trim();
                }
                cachedTextures.put(normalizedId, cache);
            } catch (IOException | JsonSyntaxException ignored) {
            }
        }
    }

    private void buildComponentsFromCache() {
        for (Map.Entry<String, CachedTexture> entry : cachedTextures.entrySet()) {
            List<List<String>> rows = parseRows(entry.getValue().rows);
            if (!rows.isEmpty()) {
                textureComponents.put(entry.getKey(), buildTextureComponent(rows));
                textureMarkup.put(entry.getKey(), buildTextureMarkup(rows));
            }
        }
    }

    private void saveCacheEntry(CachedTexture cache) throws IOException {
        File target = new File(cacheFolder, cache.id + ".json");
        ensureDirectory(cacheFolder);
        java.nio.file.Files.writeString(target.toPath(), gson.toJson(cache), StandardCharsets.UTF_8);
    }

    private File resolvePictureFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Path basePath = picturesFolder.toPath().toAbsolutePath().normalize();
        Path resolved = basePath.resolve(fileName).normalize();
        if (!resolved.startsWith(basePath)) {
            return null;
        }
        return resolved.toFile();
    }

    private File safeFolderUnderData(String relativePath) {
        Path basePath = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path resolved = basePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(basePath)) {
            return new File(plugin.getDataFolder(), relativePath.replace("..", ""));
        }
        return resolved.toFile();
    }

    private static void ensureDirectory(File folder) {
        if (folder != null && !folder.exists()) {
            folder.mkdirs();
        }
    }

    private static String deriveIdFromFileName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        return normalizeTextureId(base);
    }

    private static String normalizeTextureId(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = INVALID_ID_PATTERN.matcher(raw.trim().toLowerCase()).replaceAll("-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        return normalized;
    }

    private static String stripKnownSuffix(String id) {
        String out = id == null ? "" : id.toLowerCase();
        if (out.endsWith(".json")) {
            return out.substring(0, out.length() - 5);
        }
        if (out.endsWith(".png") || out.endsWith(".jpg")) {
            return out.substring(0, out.length() - 4);
        }
        if (out.endsWith(".jpeg") || out.endsWith(".webp")) {
            return out.substring(0, out.length() - 5);
        }
        return out;
    }

    private Component resolveReplacementForViewer(String id, Player viewer) {
        Component replacement = textureComponents.get(id);
        if (replacement == null) {
            return null;
        }
        if (viewer != null && !isClientSupported(viewer)) {
            return TextFormatUtil.parseComponent(renderFallbackTitle(viewer));
        }
        return replacement;
    }

    private static String buildBridgeToken(String id) {
        return BRIDGE_TOKEN_PREFIX + id + BRIDGE_TOKEN_SUFFIX;
    }

    private static String buildTexturePermission(String id) {
        String normalized = normalizeTextureId(stripKnownSuffix(id));
        return normalized.isEmpty() ? "" : "imagedisplay_" + normalized;
    }

    private CachedTexture toCached(String id, String sourceImage, long lastModified, List<List<String>> rows) {
        List<String> serializedRows = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            serializedRows.add(String.join("|", row));
        }
        CachedTexture cache = new CachedTexture();
        cache.id = id;
        cache.image = sourceImage;
        cache.permission = buildTexturePermission(id);
        cache.lastModified = lastModified;
        cache.rows = serializedRows;
        return cache;
    }

    private static List<List<String>> parseRows(Collection<String> serializedRows) {
        if (serializedRows == null || serializedRows.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<String>> rows = new ArrayList<>();
        for (String row : serializedRows) {
            if (row == null || row.isBlank()) {
                continue;
            }
            String[] values = row.split("\\|");
            List<String> parsed = new ArrayList<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    parsed.add(value);
                }
            }
            if (!parsed.isEmpty()) {
                rows.add(parsed);
            }
        }
        return rows;
    }

    private int resolveMinProtocol(String minVersion) {
        int protocolFromProtocolLib = resolveProtocolByProtocolLib(minVersion);
        if (protocolFromProtocolLib > 0) {
            return protocolFromProtocolLib;
        }
        if ("1.21.9".equalsIgnoreCase(minVersion)) {
            return DEFAULT_MIN_PROTOCOL_1_21_9;
        }
        return DEFAULT_MIN_PROTOCOL_1_21_9;
    }

    private int resolveProtocolByProtocolLib(String version) {
        try {
            Class<?> minecraftVersionClass = Class.forName("com.comphenix.protocol.utility.MinecraftVersion");
            Object minecraftVersion = minecraftVersionClass.getConstructor(String.class).newInstance(version);
            Class<?> protocolVersionClass = Class.forName("com.comphenix.protocol.utility.MinecraftProtocolVersion");
            Object value = protocolVersionClass.getMethod("getVersion", minecraftVersionClass).invoke(null, minecraftVersion);
            if (value instanceof Integer protocol) {
                return protocol;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private int resolvePlayerProtocol(Player player) {
        int viaVersion = resolveViaVersionProtocol(player);
        if (viaVersion > 0) {
            return viaVersion;
        }

        int protocolLib = resolveProtocolLibProtocol(player);
        if (protocolLib > 0) {
            return protocolLib;
        }

        return -1;
    }

    private int resolveViaVersionProtocol(Player player) {
        try {
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            Object value = api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId());
            if (value instanceof Integer protocol) {
                return protocol;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private int resolveProtocolLibProtocol(Player player) {
        try {
            Class<?> protocolLibraryClass = Class.forName("com.comphenix.protocol.ProtocolLibrary");
            Object protocolManager = protocolLibraryClass.getMethod("getProtocolManager").invoke(null);
            Object value = protocolManager.getClass().getMethod("getProtocolVersion", Player.class).invoke(protocolManager, player);
            if (value instanceof Integer protocol) {
                return protocol;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static long resolveRetryDelayMillis(HttpResponse<?> response, int attempt) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0L) {
                    return Math.min(60_000L, Math.max(1000L, seconds * 1000L));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        long backoff = DEFAULT_RETRY_DELAY_MILLIS * attempt;
        return Math.min(60_000L, Math.max(DEFAULT_RETRY_DELAY_MILLIS, backoff));
    }

    private static byte[] formField(String boundary, String name, String value) {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        return part.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fileFieldHeader(String boundary, String name, String fileName, String contentType) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        return header.getBytes(StandardCharsets.UTF_8);
    }

    private static BufferedImage createSkinFromTile(BufferedImage tile) {
        BufferedImage skin = new BufferedImage(SKIN_SIZE, SKIN_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = skin.createGraphics();
        try {
            drawHeadFaces(g, tile);
        } finally {
            g.dispose();
        }
        return skin;
    }

    private static void drawHeadFaces(Graphics2D g, BufferedImage tile) {
        g.drawImage(tile, 8, 8, null);
        g.drawImage(tile, 24, 8, null);
        g.drawImage(tile, 0, 8, null);
        g.drawImage(tile, 16, 8, null);
        g.drawImage(tile, 8, 0, null);
        g.drawImage(tile, 16, 0, null);
    }

    private static byte[] toPngBytes(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
    }

    private static Component buildTextureComponent(List<List<String>> valuesGrid) {
        Component component = Component.empty();
        for (int rowIndex = 0; rowIndex < valuesGrid.size(); rowIndex++) {
            if (rowIndex > 0) {
                component = component.append(Component.newline());
            }
            for (String token : valuesGrid.get(rowIndex)) {
                String value = extractValuePart(token);
                if (value == null || value.isBlank()) {
                    continue;
                }
                component = component.append(Component.object().contents(
                        ObjectContents.playerHead().profileProperty(
                                PlayerHeadObjectContents.property("textures", value)
                        ).build()
                ));
            }
        }
        return component;
    }

    private static String buildTextureMarkup(List<List<String>> valuesGrid) {
        StringBuilder out = new StringBuilder();
        for (int rowIndex = 0; rowIndex < valuesGrid.size(); rowIndex++) {
            if (rowIndex > 0) {
                out.append('\n');
            }
            for (String token : valuesGrid.get(rowIndex)) {
                String value = extractValuePart(token);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String signature = extractSignaturePart(token);
                if (signature != null && !signature.isBlank()) {
                    out.append("<head:signed_texture:").append(value).append(';').append(signature).append('>');
                } else {
                    out.append("<head:signed_texture:").append(value).append('>');
                }
            }
        }
        return out.toString();
    }

    private static SignedTextureData extractSignedTextureData(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            String value = readPath(root, "skin", "texture", "data", "value");
            String signature = readPath(root, "skin", "texture", "data", "signature");

            if (value == null || value.isBlank()) {
                value = readPath(root, "data", "texture", "value");
            }
            if (signature == null || signature.isBlank()) {
                signature = readPath(root, "data", "texture", "signature");
            }

            if (value == null || value.isBlank()) {
                return null;
            }
            return new SignedTextureData(value, signature == null ? "" : signature);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readPath(JsonObject root, String... path) {
        JsonElement current = root;
        for (String key : path) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(key);
        }
        if (current == null || current.isJsonNull()) {
            return null;
        }
        try {
            return current.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractValuePart(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int split = token.indexOf(';');
        if (split < 0) {
            return token;
        }
        return token.substring(0, split);
    }

    private static String extractSignaturePart(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        int split = token.indexOf(';');
        if (split < 0 || split + 1 >= token.length()) {
            return "";
        }
        return token.substring(split + 1);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class CachedTexture {
        private String id;
        private String image;
        private String permission;
        private long lastModified;
        private List<String> rows = List.of();
    }

    private record SignedTextureData(String value, String signature) {}
}
