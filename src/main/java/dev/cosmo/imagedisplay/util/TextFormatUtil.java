package dev.cosmo.imagedisplay.util;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class TextFormatUtil {
    private static final Pattern IA_SHORTCODE = Pattern.compile(":([A-Za-z][A-Za-z0-9_\\-]*):");
    private static final Pattern IA_IMG_PLACEHOLDER = Pattern.compile("%img_([A-Za-z0-9_\\-]+)%");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private TextFormatUtil() {
    }

    public static String applyPlaceholders(Player player, String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String out = input;
        if (player != null) {
            out = out.replace("%player_name%", player.getName());
        }
        out = normalizeItemsAdderShortcodes(out);
        out = resolveImgPlaceholdersToSingleGlyph(player, out);
        out = applyPlaceholderApi(player, out);
        return out;
    }

    public static Component parseComponent(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        try {
            return MINI_MESSAGE.deserialize(input);
        } catch (Exception ignored) {
            return Component.text("Invalid_Message");
        }
    }

    public static String normalizeItemsAdderShortcodes(String input) {
        if (input == null || input.indexOf(':') == -1) {
            return input;
        }

        StringBuilder result = new StringBuilder(input.length());
        StringBuilder outsideTag = new StringBuilder(input.length());
        boolean insideMiniMessageTag = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '<') {
                if (outsideTag.length() > 0) {
                    result.append(replaceItemsAdderShortcodes(outsideTag.toString()));
                    outsideTag.setLength(0);
                }
                insideMiniMessageTag = true;
                result.append(c);
                continue;
            }

            if (c == '>' && insideMiniMessageTag) {
                insideMiniMessageTag = false;
                result.append(c);
                continue;
            }

            if (insideMiniMessageTag) {
                result.append(c);
            } else {
                outsideTag.append(c);
            }
        }

        if (outsideTag.length() > 0) {
            result.append(replaceItemsAdderShortcodes(outsideTag.toString()));
        }

        return result.toString();
    }

    private static String replaceItemsAdderShortcodes(String input) {
        Matcher matcher = IA_SHORTCODE.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String emojiId = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement("%img_" + emojiId + "%"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String resolveImgPlaceholdersToSingleGlyph(Player player, String input) {
        if (input == null || input.indexOf("%img_") == -1) {
            return input;
        }

        Matcher matcher = IA_IMG_PLACEHOLDER.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(0);
            String replacement = resolveSingleGlyphForImgPlaceholder(player, placeholder);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String resolveSingleGlyphForImgPlaceholder(Player player, String placeholder) {
        String resolved = applyPlaceholderApi(player, placeholder);
        if (resolved == null || resolved.isEmpty() || resolved.equals(placeholder) || resolved.indexOf('%') != -1) {
            return placeholder;
        }

        for (int offset = 0; offset < resolved.length();) {
            int cp = resolved.codePointAt(offset);
            if (cp == 167 || cp == '&') {
                offset += Character.charCount(cp);
                if (offset < resolved.length()) {
                    offset += Character.charCount(resolved.codePointAt(offset));
                }
                continue;
            }
            if (!Character.isWhitespace(cp)) {
                return new String(Character.toChars(cp));
            }
            offset += Character.charCount(cp);
        }

        return placeholder;
    }

    private static String applyPlaceholderApi(Player player, String input) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) {
            return input;
        }

        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method method = papiClass.getMethod("setPlaceholders", Player.class, String.class);
            Object result = method.invoke(null, player, input);
            return result instanceof String s ? s : input;
        } catch (Throwable ignored) {
            return input;
        }
    }
}
