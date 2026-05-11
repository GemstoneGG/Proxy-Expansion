package com.extendedclip.papi.proxyexpansion;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.clip.placeholderapi.expansion.Configurable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Taskable;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

@SuppressWarnings("unused")
public final class ProxyExpansion extends PlaceholderExpansion implements PluginMessageListener, Taskable, Configurable {

    private static final String MESSAGE_CHANNEL = "BungeeCord";
    private static final String SERVERS_CHANNEL = "GetServers";
    private static final String PLAYERS_CHANNEL = "PlayerCount";
    private static final String CONFIG_INTERVAL = "check_interval";

    private static final Splitter SPLITTER = Splitter.on(",").trimResults();

    private final Map<String, Integer>                counts = new ConcurrentHashMap<>();
    private final AtomicReference<ScheduledFuture<?>> cached = new AtomicReference<>();

    private ScheduledExecutorService executor;

    @Override
    @NotNull
    public String getIdentifier() {
        return "proxy";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return "clip";
    }

    @Override
    @NotNull
    public String getVersion() {
        return "1.0";
    }

    @Override
    public Map<String, Object> getDefaults() {
        return Collections.singletonMap(CONFIG_INTERVAL, 30);
    }

    @Override
    public String onRequest(final OfflinePlayer player, String identifier) {
        final int value = switch (identifier.toLowerCase()) {
            case "all", "total" -> counts.values().stream().mapToInt(Integer::intValue).sum();
            default -> counts.getOrDefault(identifier.toLowerCase(), 0);
        };

        return String.valueOf(value);
    }

    @Override
    public void start() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, "ProxyExpansion-Scheduler");
                thread.setDaemon(true);
                return thread;
            });
        }

        final ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {

            if (counts.isEmpty()) {
                sendServersChannelMessage();
            } else {
                counts.keySet().forEach(this::sendPlayersChannelMessage);
            }
        }, 2L, getLong(CONFIG_INTERVAL, 30), TimeUnit.SECONDS);

        final ScheduledFuture<?> prev = cached.getAndSet(task);
        if (prev != null) {
            prev.cancel(false);
        } else {
            Bukkit.getMessenger().registerOutgoingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL);
            Bukkit.getMessenger().registerIncomingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL, this);
        }
    }

    @Override
    public void stop() {
        final ScheduledFuture<?> prev = cached.getAndSet(null);
        if (prev == null) {
            return;
        }

        prev.cancel(false);
        counts.clear();

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        Bukkit.getMessenger().unregisterOutgoingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL, this);
    }

    @Override
    public void onPluginMessageReceived(final @NotNull String channel, final @NotNull Player player, final byte @NotNull [] message) {
        if (!MESSAGE_CHANNEL.equals(channel)) {
            return;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            switch (in.readUTF()) {
                case PLAYERS_CHANNEL -> {
                    if (in.available() == 0) {
                        return;
                    }

                    final String server = in.readUTF();
                    if (in.available() == 0) {
                        getPlaceholderAPI().getLogger().log(Level.SEVERE, String.format("[%s] Could not get the player count from server %s.", getName(), server));
                        counts.put(server.toLowerCase(), 0);
                    } else {
                        counts.put(server.toLowerCase(), in.readInt());
                    }
                }
                case SERVERS_CHANNEL ->
                      SPLITTER.split(in.readUTF()).forEach(serverName -> counts.putIfAbsent(serverName.toLowerCase(), 0));
            }
        } catch (Exception e) {
            getPlaceholderAPI().getLogger().log(Level.SEVERE, String.format("[%s] Failed to handle plugin message on channel %s.", getName(), channel), e);
        }
    }

    private void sendServersChannelMessage() {
        sendMessage(SERVERS_CHANNEL, _ -> {
        });
    }

    private void sendPlayersChannelMessage(final String serverName) {
        sendMessage(PLAYERS_CHANNEL, out -> out.writeUTF(serverName));
    }

    private void sendMessage(final String channel, final Consumer<ByteArrayDataOutput> consumer) {
        final Player player = Iterables.getFirst(new ArrayList<>(Bukkit.getOnlinePlayers()), null);
        if (player == null) {
            return;
        }

        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(channel);

        consumer.accept(out);

        player.sendPluginMessage(getPlaceholderAPI(), MESSAGE_CHANNEL, out.toByteArray());
    }
}
