package dev.cosmo.imagedisplay.bridge;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedTeamParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import dev.cosmo.imagedisplay.api.ImageDisplayApi;
import dev.cosmo.imagedisplay.i18n.MessageService;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class ProtocolLibComponentBridge {
    private final Plugin plugin;
    private final ImageDisplayApi api;
    private final MessageService messages;
    private PacketAdapter adapter;

    public ProtocolLibComponentBridge(Plugin plugin, ImageDisplayApi api, MessageService messages) {
        this.plugin = plugin;
        this.api = api;
        this.messages = messages;
    }

    public void enable() {
        if (adapter != null) {
            return;
        }
        // буду расширять
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        adapter = new PacketAdapter(
                plugin,
                ListenerPriority.MONITOR,
                PacketType.Play.Server.SYSTEM_CHAT,
                PacketType.Play.Server.DISGUISED_CHAT,
                PacketType.Play.Server.CHAT,
                PacketType.Play.Server.PLAYER_INFO,
                PacketType.Play.Server.PLAYER_LIST_HEADER_FOOTER,
                PacketType.Play.Server.SCOREBOARD_OBJECTIVE,
                PacketType.Play.Server.SCOREBOARD_TEAM,
                PacketType.Play.Server.ENTITY_METADATA
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    processPacket(event);
                } catch (Throwable throwable) {
                    messages.consoleWarn("log.bridge.packet-error", java.util.Map.of(
                            "reason", throwable.getMessage() == null ? "unknown" : throwable.getMessage()
                    ));
                }
            }
        };
        manager.addPacketListener(adapter);
    }

    public void disable() {
        if (adapter == null) {
            return;
        }
        ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
        adapter = null;
    }


    private void processPacket(PacketEvent event) {
        PacketContainer packet = event.getPacket();
        Player viewer = event.getPlayer();
        PacketType packetType = packet.getType();
        processWrappedModifier(packet.getChatComponents(), viewer);
        if (PacketType.Play.Server.SCOREBOARD_TEAM.equals(packetType)) {
            processTeamParameters(packet, viewer);
        }
        if (PacketType.Play.Server.ENTITY_METADATA.equals(packetType)) {
            processEntityMetadata(packet, viewer);
        }
        if (isPlayerInfoType(packetType)) {
            processPlayerInfoData(packet, viewer);
        }
    }

    private void processPlayerInfoData(PacketContainer packet, Player viewer) {
        StructureModifier<List<PlayerInfoData>> lists = packet.getPlayerInfoDataLists();
        if (lists == null || lists.size() == 0) {
            return;
        }

        for (int i = 0; i < lists.size(); i++) {
            List<PlayerInfoData> list = lists.readSafely(i);
            if (list == null || list.isEmpty()) {
                continue;
            }

            boolean changed = false;
            List<PlayerInfoData> out = new ArrayList<>(list.size());
            for (PlayerInfoData data : list) {
                if (data == null) {
                    continue;
                }

                WrappedChatComponent displayName = data.getDisplayName();
                WrappedChatComponent replacedDisplay = replaceWrapped(displayName, viewer);
                if (replacedDisplay == displayName) {
                    out.add(data);
                    continue;
                }

                PlayerInfoData rebuilt = rebuildPlayerInfoData(data, replacedDisplay);
                if (rebuilt != null) {
                    out.add(rebuilt);
                    changed = true;
                } else {
                    out.add(data);
                }
            }

            if (changed) {
                lists.write(i, out);
            }
        }
    }

    private void processWrappedModifier(StructureModifier<WrappedChatComponent> modifier, Player viewer) {
        if (modifier == null || modifier.size() == 0) {
            return;
        }

        for (int i = 0; i < modifier.size(); i++) {
            WrappedChatComponent wrapped = modifier.readSafely(i);
            if (wrapped == null) {
                continue;
            }

            String json = wrapped.getJson();
            if (!api.mayContainBridgeTokens(json)) {
                continue;
            }

            Component original;
            try {
                original = GsonComponentSerializer.gson().deserialize(json);
            } catch (Exception ignored) {
                continue;
            }

            Component replaced = api.replaceBridgeTokens(original, viewer);
            if (replaced == original) {
                continue;
            }

            String replacedJson = GsonComponentSerializer.gson().serialize(replaced);
            modifier.write(i, WrappedChatComponent.fromJson(replacedJson));
        }
    }

    private void processEntityMetadata(PacketContainer packet, Player viewer) {
        StructureModifier<List<WrappedDataValue>> modifier = packet.getDataValueCollectionModifier();
        if (modifier == null || modifier.size() == 0) {
            return;
        }

        for (int i = 0; i < modifier.size(); i++) {
            List<WrappedDataValue> values = modifier.readSafely(i);
            if (values == null || values.isEmpty()) {
                continue;
            }

            boolean changed = false;
            List<WrappedDataValue> updated = new ArrayList<>(values.size());
            for (WrappedDataValue value : values) {
                if (value == null) {
                    continue;
                }

                WrappedDataValue replaced = replaceDataValue(value, viewer);
                if (replaced != value) {
                    changed = true;
                }
                updated.add(replaced);
            }

            if (changed) {
                modifier.write(i, updated);
            }
        }
    }

    private void processTeamParameters(PacketContainer packet, Player viewer) {
        StructureModifier<Optional<WrappedTeamParameters>> modifier = packet.getOptionalTeamParameters();
        if (modifier == null || modifier.size() == 0) {
            return;
        }

        for (int i = 0; i < modifier.size(); i++) {
            Optional<WrappedTeamParameters> optional = modifier.readSafely(i);
            if (optional == null || optional.isEmpty()) {
                continue;
            }

            WrappedTeamParameters current = optional.get();
            WrappedChatComponent display = replaceWrapped(current.getDisplayName(), viewer);
            WrappedChatComponent prefix = replaceWrapped(current.getPrefix(), viewer);
            WrappedChatComponent suffix = replaceWrapped(current.getSuffix(), viewer);

            boolean changed = display != current.getDisplayName()
                    || prefix != current.getPrefix()
                    || suffix != current.getSuffix();
            if (!changed) {
                continue;
            }

            WrappedTeamParameters rebuilt = WrappedTeamParameters.newBuilder(current)
                    .displayName(display)
                    .prefix(prefix)
                    .suffix(suffix)
                    .build();
            modifier.write(i, Optional.of(rebuilt));
        }
    }

    private WrappedDataValue replaceDataValue(WrappedDataValue value, Player viewer) {
        Object originalValue = value.getValue();
        if (originalValue == null) {
            return value;
        }

        Object replacedValue = replaceMetadataValue(originalValue, viewer);
        if (replacedValue == originalValue) {
            return value;
        }

        return WrappedDataValue.fromWrappedValue(value.getIndex(), value.getSerializer(), replacedValue);
    }

    private Object replaceMetadataValue(Object value, Player viewer) {
        if (value instanceof WrappedChatComponent wrapped) {
            return replaceWrapped(wrapped, viewer);
        }

        if (value instanceof Component component) {
            Component replaced = api.replaceBridgeTokens(component, viewer);
            return replaced == component ? value : replaced;
        }

        if (value instanceof Optional<?> optional) {
            if (optional.isEmpty()) {
                return value;
            }

            Object current = optional.get();
            Object replaced = replaceMetadataValue(current, viewer);
            if (replaced == current) {
                return value;
            }
            return Optional.ofNullable(replaced);
        }

        return value;
    }

    private WrappedChatComponent replaceWrapped(WrappedChatComponent wrapped, Player viewer) {
        if (wrapped == null) {
            return null;
        }
        String json = wrapped.getJson();
        if (!api.mayContainBridgeTokens(json)) {
            return wrapped;
        }

        Component original;
        try {
            original = GsonComponentSerializer.gson().deserialize(json);
        } catch (Exception ignored) {
            return wrapped;
        }

        Component replaced = api.replaceBridgeTokens(original, viewer);
        if (replaced == original) {
            return wrapped;
        }
        String replacedJson = GsonComponentSerializer.gson().serialize(replaced);
        return WrappedChatComponent.fromJson(replacedJson);
    }

    private PlayerInfoData rebuildPlayerInfoData(PlayerInfoData oldData, WrappedChatComponent newDisplay) {
        try {
            return new PlayerInfoData(
                    oldData.getProfileId(),
                    oldData.getLatency(),
                    oldData.isListed(),
                    oldData.getGameMode(),
                    oldData.getProfile(),
                    newDisplay,
                    oldData.getRemoteChatSessionData()
            );
        } catch (Throwable ignored) {
        }

        try {
            return new PlayerInfoData(
                    oldData.getProfileId(),
                    oldData.getLatency(),
                    oldData.isListed(),
                    oldData.getGameMode(),
                    oldData.getProfile(),
                    newDisplay,
                    oldData.getProfileKeyData()
            );
        } catch (Throwable ignored) {
        }

        try {
            return new PlayerInfoData(
                    oldData.getProfile(),
                    oldData.getLatency(),
                    oldData.getGameMode(),
                    newDisplay
            );
        } catch (Throwable ignored) {
            return null;
        }
    }


    private boolean isPlayerInfoType(PacketType packetType) {
        if (packetType == null) {
            return false;
        }
        if (PacketType.Play.Server.PLAYER_INFO.equals(packetType)) {
            return true;
        }
        String name = packetType.name();
        return name != null && name.toUpperCase(Locale.ROOT).contains("PLAYER_INFO");
    }
}
