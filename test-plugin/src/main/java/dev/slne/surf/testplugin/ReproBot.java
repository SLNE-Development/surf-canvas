package dev.slne.surf.testplugin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Minimal headless Minecraft client that logs into this server over loopback using the server's own
 * protocol classes. It answers keep-alives, known-pack queries, cookies and teleport confirmations and
 * ignores everything else, which is enough to be a real player with a chunk loader.
 */
final class ReproBot {

    private static final String TAG = "[REPRO-BOT]";

    private final Logger log;
    private final String name;
    private final int port;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(task -> {
        final Thread thread = new Thread(task, "ReproBot-tick");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Connection connection;
    private volatile PositionMoveRotation pose = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F);

    ReproBot(final Logger log, final String name, final int port) {
        this.log = log;
        this.name = name;
        this.port = port;
    }

    void connect() {
        final InetSocketAddress address = new InetSocketAddress("127.0.0.1", this.port);
        final Connection conn = Connection.connectToServer(address, EventLoopGroupHolder.remote(false), null);
        this.connection = conn;
        conn.initiateServerboundPlayConnection("127.0.0.1", this.port, this.loginListener(conn));
        conn.send(new ServerboundHelloPacket(this.name, UUID.nameUUIDFromBytes(("OfflinePlayer:" + this.name).getBytes(StandardCharsets.UTF_8))));
        this.ticker.scheduleAtFixedRate(() -> {
            try {
                conn.tick();
            } catch (final Throwable thrown) {
                this.log.warn("{} tick failed", TAG, thrown);
            }
        }, 50L, 50L, TimeUnit.MILLISECONDS);
        this.log.info("{} connecting {} to 127.0.0.1:{}", TAG, this.name, this.port);
    }

    void close() {
        this.ticker.shutdownNow();
        final Connection conn = this.connection;
        if (conn != null && conn.isConnected()) {
            conn.disconnect(Component.literal("repro bot done"));
        }
    }

    // ---------------------------------------------------------------- listeners

    private ClientLoginPacketListener loginListener(final Connection conn) {
        return this.proxy(ClientLoginPacketListener.class, conn, ConnectionProtocol.LOGIN, Map.of(
            "handleCompression", packet -> conn.setupCompression(((ClientboundLoginCompressionPacket) packet).getCompressionThreshold(), false),
            "handleCustomQuery", packet -> conn.send(new ServerboundCustomQueryAnswerPacket(((ClientboundCustomQueryPacket) packet).transactionId(), null)),
            "handleDisconnect", packet -> this.log.warn("{} login disconnect: {}", TAG, ((ClientboundLoginDisconnectPacket) packet).reason().getString()),
            "handleHello", packet -> this.log.error("{} server requested encryption; the bot needs online-mode=false", TAG),
            "handleLoginFinished", packet -> {
                // same order as the vanilla client: inbound switch, acknowledge, outbound switch
                conn.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, this.configurationListener(conn));
                conn.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                conn.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                conn.send(new ServerboundClientInformationPacket(new ClientInformation(
                    "en_us", 10, ChatVisiblity.FULL, true, 127, HumanoidArm.RIGHT, false, true, ParticleStatus.ALL)));
                this.log.info("{} login finished, entering configuration", TAG);
            }
        ));
    }

    private ClientConfigurationPacketListener configurationListener(final Connection conn) {
        return this.proxy(ClientConfigurationPacketListener.class, conn, ConnectionProtocol.CONFIGURATION, Map.of(
            "handleKeepAlive", packet -> conn.send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) packet).getId())),
            "handlePing", packet -> conn.send(new ServerboundPongPacket(((ClientboundPingPacket) packet).getId())),
            "handleRequestCookie", packet -> conn.send(new ServerboundCookieResponsePacket(((ClientboundCookieRequestPacket) packet).key(), null)),
            "handleSelectKnownPacks", packet -> conn.send(new ServerboundSelectKnownPacks(List.of())),
            "handleDisconnect", packet -> this.log.warn("{} configuration disconnect: {}", TAG, ((ClientboundDisconnectPacket) packet).reason().getString()),
            "handleConfigurationFinished", packet -> {
                final RegistryAccess registries = MinecraftServer.getServer().registryAccess();
                conn.setupInboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)), this.gameListener(conn));
                conn.send(ServerboundFinishConfigurationPacket.INSTANCE);
                conn.setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), new GameProtocols.Context() {
                    @Override
                    public boolean hasInfiniteMaterials() {
                        return false;
                    }

                    @Override
                    public boolean canUseCommandBlocks() {
                        return false;
                    }
                }));
                this.log.info("{} configuration finished, entering play", TAG);
            }
        ));
    }

    private ClientGamePacketListener gameListener(final Connection conn) {
        return this.proxy(ClientGamePacketListener.class, conn, ConnectionProtocol.PLAY, Map.of(
            "handleKeepAlive", packet -> conn.send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) packet).getId())),
            "handlePing", packet -> conn.send(new ServerboundPongPacket(((ClientboundPingPacket) packet).getId())),
            "handleRequestCookie", packet -> conn.send(new ServerboundCookieResponsePacket(((ClientboundCookieRequestPacket) packet).key(), null)),
            "handleDisconnect", packet -> this.log.warn("{} play disconnect: {}", TAG, ((ClientboundDisconnectPacket) packet).reason().getString()),
            "handleLogin", packet -> {
                conn.send(new ServerboundPlayerLoadedPacket());
                this.log.info("{} received login packet, in the world", TAG);
            },
            "handleMovePlayer", packet -> {
                final ClientboundPlayerPositionPacket position = (ClientboundPlayerPositionPacket) packet;
                final PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(this.pose, position.change(), position.relatives());
                this.pose = absolute;
                // a real client confirms after a round trip, not inside the same server tick
                this.ticker.schedule(() -> conn.send(new ServerboundAcceptTeleportationPacket(position.id(),
                    absolute.position().x, absolute.position().y, absolute.position().z, absolute.yRot(), absolute.xRot())), 100L, TimeUnit.MILLISECONDS);
            }
        ));
    }

    @SuppressWarnings("unchecked")
    private <T extends PacketListener> T proxy(final Class<T> type, final Connection conn, final ConnectionProtocol protocol,
                                               final Map<String, Consumer<Object>> handlers) {
        final InvocationHandler handler = (proxyInstance, method, args) -> {
            switch (method.getName()) {
                case "flow" -> {
                    return PacketFlow.CLIENTBOUND;
                }
                case "protocol" -> {
                    return protocol;
                }
                case "isAcceptingMessages" -> {
                    return conn.isConnected();
                }
                case "onDisconnect" -> {
                    final DisconnectionDetails details = (DisconnectionDetails) args[0];
                    this.log.warn("{} disconnected in {}: {}", TAG, protocol, details.reason().getString());
                    return null;
                }
                case "toString" -> {
                    return "ReproBot" + protocol;
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxyInstance);
                }
                case "equals" -> {
                    return proxyInstance == args[0];
                }
                default -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxyInstance, method, args);
                    }
                    final Consumer<Object> packetHandler = handlers.get(method.getName());
                    if (packetHandler != null && args != null && args.length == 1) {
                        packetHandler.accept(args[0]);
                    }
                    return defaultValue(method);
                }
            }
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(final Method method) {
        final Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0;
    }
}
