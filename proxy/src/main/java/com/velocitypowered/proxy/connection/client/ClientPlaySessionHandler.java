/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import static com.velocitypowered.proxy.protocol.util.PluginMessageUtil.constructChannelsPacket;

import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.suggestion.Suggestion;
import com.velocityctd.api.event.player.TabCompleteRequestEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.CookieReceiveEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.PlayerClientLoadedWorldEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.event.player.configuration.PlayerEnteredConfigurationEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.player.ClientWorldSwitches;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.backend.BackendConnectionPhases;
import com.velocitypowered.proxy.connection.backend.BungeeCordMessageResponder;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.forge.legacy.LegacyForgeConstants;
import com.velocitypowered.proxy.connection.player.resourcepack.ResourcePackResponseBundle;
import com.velocitypowered.proxy.connection.registry.DimensionInfo;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.packet.BossBarPacket;
import com.velocitypowered.proxy.protocol.packet.ClientSettingsPacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundPlayerLoadedPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteRequestPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket.Offer;
import com.velocitypowered.proxy.protocol.packet.chat.ChatAcknowledgementPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.ChatTimeKeeper;
import com.velocitypowered.proxy.protocol.packet.chat.CommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerCommandPacket;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerCommandPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.title.GenericTitlePacket;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import com.velocitypowered.proxy.util.CharacterUtil;
import com.velocitypowered.proxy.util.except.QuietRuntimeException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Handles communication with the connected Minecraft client. This is effectively the primary nerve
 * center that joins backend servers with players.
 */
public class ClientPlaySessionHandler implements MinecraftSessionHandler {
  private static final boolean BACKPRESSURE_LOG =
      Boolean.getBoolean("velocity.log-server-backpressure");

  // Caps the per-connection queue used while the FML/login phases are not yet "complete". Without
  // these caps, a client that never completes its handshake phase can spam plugin messages (each up
  // to ~32 KiB serverbound) and grow the queue without bound.
  private static final long MAX_QUEUED_LOGIN_PLUGIN_MESSAGE_BYTES =
      Long.getLong("velocity.max-queued-login-plugin-message-bytes", 4L * 1024 * 1024);

  private static final int MAX_QUEUED_LOGIN_PLUGIN_MESSAGES =
      Integer.getInteger("velocity.max-queued-login-plugin-messages", 1024);

  private static final Logger LOGGER = LogManager.getLogger(ClientPlaySessionHandler.class);

  private final ConnectedPlayer player;

  private boolean spawned = false;

  /**
   * The entity ID and dimension the client currently believes it is in. A world-preserving switch
   * is only safe when the destination reproduces both, so both are tracked from whatever last told
   * the client about them -- a join game on a switch, or a respawn from a local world change.
   */
  private int clientEntityId = -1;

  private @Nullable String clientDimension;

  private final List<UUID> serverBossBars = new ArrayList<>();

  private final Queue<PluginMessagePacket> loginPluginMessages = new ConcurrentLinkedQueue<>();

  private final AtomicLong loginPluginMessagesBytes = new AtomicLong();

  private final AtomicInteger loginPluginMessagesCount = new AtomicInteger();

  private volatile boolean loginPluginMessagesOverflowed;

  private final VelocityServer server;

  private @Nullable TabCompleteRequestPacket outstandingTabComplete;

  private final ChatHandler<? extends MinecraftPacket> chatHandler;

  private final CommandHandler<? extends MinecraftPacket> commandHandler;

  private final ChatTimeKeeper timeKeeper = new ChatTimeKeeper();

  private CompletableFuture<Void> configSwitchFuture;

  private int failedTabCompleteAttempts;

  /**
   * Constructs a client play session handler.
   *
   * @param server the Velocity server instance
   * @param player the player
   */
  public ClientPlaySessionHandler(VelocityServer server, ConnectedPlayer player) {
    this.player = player;
    this.server = server;

    if (this.player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      this.chatHandler = new SessionChatHandler(this.player, this.server);
      this.commandHandler = new SessionCommandHandler(this.player, this.server);
    } else if (this.player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19)) {
      this.chatHandler = new KeyedChatHandler(this.server, this.player);
      this.commandHandler = new KeyedCommandHandler(this.player, this.server);
    } else {
      this.chatHandler = new LegacyChatHandler(this.server, this.player);
      this.commandHandler = new LegacyCommandHandler(this.player, this.server);
    }
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean updateTimeKeeper(@Nullable Instant instant) {
    if (instant == null) {
      return true;
    }

    if (!this.timeKeeper.update(instant)) {
      player.disconnect(Component.translatable("multiplayer.disconnect.out_of_order_chat"));
      return false;
    }

    return true;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean validateChat(String message) {
    if (!server.getConfiguration().isAllowIllegalCharactersInChat() && CharacterUtil.containsIllegalCharacters(message)) {
      player.disconnect(Component.translatable("velocity.error.illegal-chat-characters", NamedTextColor.RED));
      return false;
    }

    return true;
  }

  @Override
  public void activated() {
    configSwitchFuture = new CompletableFuture<>();
    Collection<ChannelIdentifier> channels = server.getChannelRegistrar().getChannelsForProtocol(player.getProtocolVersion());
    if (!channels.isEmpty()) {
      PluginMessagePacket register = constructChannelsPacket(player.getProtocolVersion(), channels);
      player.getConnection().write(register);
    }
  }

  @Override
  public void deactivated() {
    player.discardChatQueue();
    PluginMessagePacket message;
    while ((message = loginPluginMessages.poll()) != null) {
      ReferenceCountUtil.release(message);
    }

    loginPluginMessagesBytes.set(0);
    loginPluginMessagesCount.set(0);
  }

  /**
   * Adds a retained plugin message to the queue used while the FML/login phases are still in
   * progress, enforcing the per-connection byte and count caps. Returns {@code true} if queued,
   * {@code false} if the packet was released (and the player disconnected on overflow).
   */
  private boolean enqueueLoginPluginMessage(PluginMessagePacket packet) {
    if (loginPluginMessagesOverflowed) {
      ReferenceCountUtil.release(packet);
      return false;
    }

    int packetSize = packet.content().readableBytes();
    long newBytes = loginPluginMessagesBytes.addAndGet(packetSize);
    int newCount = loginPluginMessagesCount.incrementAndGet();
    if (newBytes > MAX_QUEUED_LOGIN_PLUGIN_MESSAGE_BYTES
        || newCount > MAX_QUEUED_LOGIN_PLUGIN_MESSAGES) {
      loginPluginMessagesOverflowed = true;
      ReferenceCountUtil.release(packet);
      LOGGER.warn("Disconnecting {}: pre-join plugin-message queue exceeded its limits "
              + "({} messages, {} bytes).", player, newCount, newBytes);
      player.disconnect(Component.translatable("velocity.error.plugin-message-overflow"));
      return false;
    }

    loginPluginMessages.add(packet);
    return true;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    player.forwardKeepAlive(packet);
    return true;
  }

  @Override
  public boolean handle(ClientSettingsPacket packet) {
    player.setClientSettings(packet);
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      // No server connection yet, probably transitioning.
      return true;
    }

    player.getConnectedServer().ensureConnected().write(packet);
    return true; // will forward onto the server
  }

  @Override
  public boolean handle(ServerboundPlayerLoadedPacket packet) {
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      // No server connection yet, probably transitioning - shouldn't be possible with a vanilla client
      return true;
    }
    if (!serverConnection.isClientLoaded()) {
      serverConnection.setClientLoaded(true);
      server.getEventManager().fireAndForget(new PlayerClientLoadedWorldEvent(player));
    }
    return false;
  }

  @Override
  public boolean handle(SessionPlayerCommandPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimeStamp())) {
      return true;
    }

    if (!validateChat(packet.getCommand())) {
      return true;
    }

    return this.commandHandler.handlePlayerCommand(packet);
  }

  @Override
  public boolean handle(SessionPlayerChatPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimestamp())) {
      return true;
    }

    if (!validateChat(packet.getMessage())) {
      return true;
    }

    return this.chatHandler.handlePlayerChat(packet);
  }

  @Override
  public boolean handle(KeyedPlayerCommandPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimestamp())) {
      return true;
    }

    if (!validateChat(packet.getCommand())) {
      return true;
    }

    return this.commandHandler.handlePlayerCommand(packet);
  }

  @Override
  public boolean handle(KeyedPlayerChatPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getExpiry())) {
      return true;
    }

    if (!validateChat(packet.getMessage())) {
      return true;
    }

    return this.chatHandler.handlePlayerChat(packet);
  }

  @Override
  public boolean handle(LegacyChatPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    String msg = packet.getMessage();
    if (!validateChat(msg)) {
      return true;
    }

    if (msg.startsWith("/")) {
      this.commandHandler.handlePlayerCommand(packet);
    } else {
      this.chatHandler.handlePlayerChat(packet);
    }

    return true;
  }

  @Override
  public boolean handle(TabCompleteRequestPacket packet) {
    boolean isCommand = !packet.isAssumeCommand() && packet.getCommand().startsWith("/");

    if (isCommand) {
      return this.handleCommandTabComplete(packet);
    } else {
      return this.handleRegularTabComplete(packet);
    }
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    // Handling an edge case, when a packet with FML client handshake (state COMPLETE)
    // arrives after JoinGame packet from destination server
    VelocityServerConnection serverConn =
        (player.getConnectedServer() == null
            && packet.getChannel().equals(
            LegacyForgeConstants.FORGE_LEGACY_HANDSHAKE_CHANNEL))
            ? player.getConnectionInFlight() : player.getConnectedServer();

    MinecraftConnection backendConn = serverConn != null ? serverConn.getConnection() : null;
    if (serverConn != null && backendConn != null) {
      if (backendConn.getState() != StateRegistry.PLAY) {
        LOGGER.warn("A plugin message was received while the backend server was not "
            + "ready. Channel: {}. Packet discarded.", packet.getChannel());
      } else if (PluginMessageUtil.isRegister(packet)) {
        List<ChannelIdentifier> channels = PluginMessageUtil.getChannels(this.player.getClientsideChannels().size(), packet,
                this.player.getProtocolVersion(), this.server);
        player.getClientsideChannels().addAll(channels);
        server.getEventManager().fireAndForget(new PlayerChannelRegisterEvent(player, ImmutableList.copyOf(channels)));
        backendConn.write(packet.retain());
      } else if (PluginMessageUtil.isUnregister(packet)) {
        List<ChannelIdentifier> channels =
            PluginMessageUtil.getChannels(0, packet, this.player.getProtocolVersion(), this.server);
        player.getClientsideChannels().removeAll(channels);
        server.getEventManager()
            .fireAndForget(
                new PlayerChannelUnregisterEvent(player, ImmutableList.copyOf(channels)));
        backendConn.write(packet.retain());
      } else if (PluginMessageUtil.isMcBrand(packet)) {
        String brand = PluginMessageUtil.readBrandMessage(packet.content());
        server.getEventManager().fireAndForget(new PlayerClientBrandEvent(player, brand));
        player.setClientBrand(brand);
        backendConn.write(packet.retain());
      } else if (BungeeCordMessageResponder.isBungeeCordMessage(packet)) {
        return true;
      } else {
        if (serverConn.getPhase() == BackendConnectionPhases.IN_TRANSITION) {
          // We must bypass the currently connected server when forwarding Forge packets.
          VelocityServerConnection inFlight = player.getConnectionInFlight();
          if (inFlight != null) {
            player.getPhase().handle(player, packet, inFlight);
          }

          return true;
        }

        if (!player.getPhase().handle(player, packet, serverConn)) {
          ChannelIdentifier id = server.getChannelRegistrar().getFromId(packet.getChannel());
          if (id == null) {
            // We don't have any plugins listening on this channel, process the packet now.
            if (!player.getPhase().consideredComplete() || !serverConn.getPhase().consideredComplete()) {
              // The client is trying to send messages too early. This is primarily caused by mods,
              // but further aggravated by Velocity. To work around these issues, we will queue any
              // non-FML handshake messages to be sent once the FML handshake has completed or the
              // JoinGame packet has been received by "the" proxy, whichever comes first.
              //
              // We also need to make sure to retain these packets, so they can be flushed
              // appropriately.
              enqueueLoginPluginMessage(packet.retain());
            } else {
              // The connection is ready, send the packet now.
              backendConn.write(packet.retain());
            }
          } else {
            byte[] copy = ByteBufUtil.getBytes(packet.content());
            PluginMessageEvent event = new PluginMessageEvent(player, serverConn, id, copy);
            server.getEventManager().fire(event).thenAcceptAsync(pme -> {
              if (pme.getResult().isAllowed()) {
                PluginMessagePacket message = new PluginMessagePacket(packet.getChannel(),
                    Unpooled.wrappedBuffer(copy));
                if (!player.getPhase().consideredComplete() || !serverConn.getPhase()
                    .consideredComplete()) {
                  // We're still processing the connection (see above), enqueue the packet for now.
                  enqueueLoginPluginMessage(message.retain());
                } else {
                  backendConn.write(message);
                }
              }
            }, backendConn.eventLoop()).exceptionally((ex) -> {
              LOGGER.error("Exception while handling plugin message packet for {}", player, ex);
              return null;
            });
          }
        }
      }
    }

    return true;
  }

  @Override
  public boolean handle(ResourcePackResponsePacket packet) {
    return player.resourcePackHandler().onResourcePackResponse(
        new ResourcePackResponseBundle(packet.getId(),
            packet.getHash(),
            packet.getStatus()));
  }

  @Override
  public boolean handle(FinishedUpdatePacket packet) {
    if (!player.getConnection().pendingConfigurationSwitch) {
      throw new QuietRuntimeException("Not expecting reconfiguration");
    }

    // Complete client switch
    player.getConnection().setActiveSessionHandler(StateRegistry.CONFIG);
    VelocityServerConnection serverConnection = player.getConnectedServer();
    server.getEventManager().fireAndForget(new PlayerEnteredConfigurationEvent(player, serverConnection));
    if (serverConnection != null) {
      MinecraftConnection smc = serverConnection.ensureConnected();
      CompletableFuture.runAsync(() -> {
        smc.write(packet);
        smc.setActiveSessionHandler(StateRegistry.CONFIG);
        smc.setAutoReading(true);
      }, smc.eventLoop()).exceptionally((ex) -> {
        LOGGER.error("Error forwarding config state acknowledgement to server:", ex);
        return null;
      });
    }

    configSwitchFuture.complete(null);
    return true;
  }

  @Override
  public boolean handle(ChatAcknowledgementPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    player.getChatQueue().handleAcknowledgement(packet.offset());
    return true;
  }

  @Override
  public boolean handle(ServerboundCookieResponsePacket packet) {
    server.getEventManager()
        .fire(new CookieReceiveEvent(player, packet.getKey(), packet.getPayload()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            VelocityServerConnection serverConnection = player.getConnectedServer();
            if (serverConnection != null) {
              Key resultedKey = event.getResult().getKey() == null
                  ? event.getOriginalKey() : event.getResult().getKey();
              byte[] resultedData = event.getResult().getData() == null
                  ? event.getOriginalData() : event.getResult().getData();

              serverConnection.ensureConnected().write(new ServerboundCookieResponsePacket(resultedKey, resultedData));
            }
          }
        }, player.getConnection().eventLoop());

    return true;
  }

  @Override
  public boolean handle(JoinGamePacket packet) {
    // Forward the packet as normal, but discard any chat state we have queued - the client will do this too
    player.discardChatQueue();
    return false;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      // No server connection yet, probably transitioning.
      return;
    }

    MinecraftConnection smc = serverConnection.getConnection();
    boolean stateAllowsForward = smc != null
        && !smc.isClosed()
        && serverConnection.getPhase().consideredComplete()
        && smc.getState() == StateRegistry.PLAY;
    if (stateAllowsForward) {
      if (packet instanceof PluginMessagePacket) {
        ((PluginMessagePacket) packet).retain();
      }
      smc.write(packet);
    }
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      // No server connection yet, probably transitioning.
      return;
    }

    MinecraftConnection smc = serverConnection.getConnection();
    boolean stateAllowsForward = smc != null
        && !smc.isClosed()
        && serverConnection.getPhase().consideredComplete()
        && smc.getState() == StateRegistry.PLAY;
    if (stateAllowsForward) {
      smc.write(buf.retain());
    }
  }

  @Override
  public void disconnected() {
    player.teardown();
  }

  @Override
  public void exception(Throwable throwable) {
    player.disconnect(Component.translatable("velocity.error.player-connection-error", NamedTextColor.RED));
    if (MinecraftDecoder.DEBUG) {
      LOGGER.info("Exception while handling packet for {}", player, throwable);
    }
  }

  @Override
  public void writabilityChanged() {
    boolean writable = player.getConnection().getChannel().isWritable();

    if (BACKPRESSURE_LOG) {
      if (writable) {
        LOGGER.info("{} is writable, will auto-read backend connection data", player);
      } else {
        LOGGER.info("{} is not writable, not auto-reading backend connection data", player);
      }
    }

    if (!writable) {
      // We might have packets queued from the server, so flush them now to free up memory. Make
      // sure to do it on a future invocation of the event loop, otherwise while the issue will
      // fix itself, we'll still disable auto-reading, and instead of backpressure resolution, we
      // get client timeouts.
      player.getConnection().eventLoop().execute(() -> player.getConnection().flush());
    }

    VelocityServerConnection serverConn = player.getConnectedServer();
    if (serverConn != null) {
      MinecraftConnection smc = serverConn.getConnection();
      if (smc != null) {
        smc.setAutoReading(writable);
      }
    }
  }

  /**
   * Handles switching stages for swapping between servers.
   *
   * @return a future that completes when the switch is complete
   */
  public CompletableFuture<Void> doSwitch() {
    VelocityServerConnection existingConnection = player.getConnectedServer();

    if (existingConnection != null) {
      // Shut down the existing server connection.
      player.setConnectedServer(null);
      existingConnection.disconnect();

      // Send keep alive to try to avoid timeouts
      player.sendKeepAlive();

      // Config state clears everything in the client. No need to clear later.
      spawned = false;
      player.clearPlayerListHeaderAndFooterSilent();
      player.getTabList().clearAllSilent();
      if (player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        player.getBossBarManager().dropPackets();
      } else {
        serverBossBars.clear();
      }
    }

    player.switchToConfigState();

    return configSwitchFuture;
  }

  /**
   * Handles the {@code JoinGame} packet. This function is responsible for handling the client-side
   * switching servers in Velocity.
   *
   * @param joinGame    the join game packet
   * @param destination the new server we are connecting to
   */
  public void handleBackendJoinGame(JoinGamePacket joinGame, VelocityServerConnection destination) {
    MinecraftConnection serverMc = destination.ensureConnected();

    if (!spawned) {
      // The player wasn't spawned in yet, so we don't need to do anything special.
      // Send JoinGame.
      spawned = true;
      player.getConnection().delayedWrite(joinGame);
      // Required for Legacy Forge
      player.getPhase().onFirstJoin(player);
      rememberClientWorld(joinGame);
    } else if (canKeepClientWorld(joinGame)) {
      // The destination has put the player back in the dimension the client already has, under the
      // entity ID it already has. Withholding the join game and respawn packets is precisely what
      // stops the client from tearing down and rebuilding its level -- no terrain loading screen.
      player.getTabList().clearAll();

      // Because it never receives a join game, the client never reports that it finished loading
      // the world. The backend waits for that before it accepts movement, so the player would
      // stand still server-side while their client walks away. It is loaded by definition here,
      // so say so on its behalf.
      if (acknowledgesLoading(player.getProtocolVersion())) {
        serverMc.write(ServerboundPlayerLoadedPacket.INSTANCE);
      }
      destination.setClientLoaded(true);
    } else {
      // Clear tab list to avoid duplicate entries
      player.getTabList().clearAll();

      // The player is switching from a server already, so we need to tell the client to change
      // entity IDs and send new dimension information.
      if (player.getConnection().getType() == ConnectionTypes.LEGACY_FORGE) {
        this.doSafeClientServerSwitch(joinGame);
      } else {
        this.doFastClientServerSwitch(joinGame);
      }

      rememberClientWorld(joinGame);
    }

    destination.setEntityId(joinGame.getEntityId()); // Sound API function

    if (!server.getConfiguration().isRemoveReconfig()
        && player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      player.getBossBarManager().sendBossBars();
    } else {
      // Remove previous boss bars. These don't get cleared when sending JoinGame (up until 1.20.2),
      // thus the need to track them.
      for (UUID serverBossBar : serverBossBars) {
        BossBarPacket deletePacket = new BossBarPacket();
        deletePacket.setUuid(serverBossBar);
        deletePacket.setAction(BossBarPacket.REMOVE);
        player.getConnection().delayedWrite(deletePacket);
      }

      serverBossBars.clear();
    }

    // Tell the server about the proxy's plugin message channels.
    ProtocolVersion serverVersion = serverMc.getProtocolVersion();
    Collection<ChannelIdentifier> channels = server.getChannelRegistrar().getChannelsForProtocol(serverMc.getProtocolVersion());
    if (!channels.isEmpty()) {
      serverMc.delayedWrite(constructChannelsPacket(serverVersion, channels));
    }

    // Tell the server about this client's plugin message channels.
    if (!player.getClientsideChannels().isEmpty()) {
      serverMc.delayedWrite(constructChannelsPacket(serverVersion, player.getClientsideChannels()));
    }

    // If we had plugin messages queued during login/FML handshake, send them now.
    PluginMessagePacket pm;
    while ((pm = loginPluginMessages.poll()) != null) {
      serverMc.delayedWrite(pm);
    }

    loginPluginMessagesBytes.set(0);
    loginPluginMessagesCount.set(0);

    // Clear any title from the previous server. Skipped when the client was left in play, so the
    // fade of an in-progress teleport survives the switch.
    if (!server.getConfiguration().isRemoveReconfig()
        && player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
      player.getConnection().delayedWrite(
          GenericTitlePacket.constructTitlePacket(GenericTitlePacket.ActionType.RESET,
              player.getProtocolVersion()));
    }

    // Flush everything
    player.getConnection().flush();
    serverMc.flush();
    destination.completeJoin();
  }

  private void rememberClientWorld(JoinGamePacket joinGame) {
    clientEntityId = joinGame.getEntityId();
    clientDimension = dimensionKey(joinGame.getDimensionInfo(), joinGame.getDimension());
    ClientWorldSwitches.rememberClientEntityId(player.getUniqueId(), clientEntityId);
  }

  /**
   * Records a dimension change a backend made without a server switch, such as a nether portal.
   * Without this the proxy keeps whatever dimension it last saw in a join game, which goes stale
   * the moment the player changes world locally, and then refuses to preserve the client world on
   * the next switch.
   *
   * @param respawn the respawn packet the backend sent the client
   */
  public void rememberClientDimension(RespawnPacket respawn) {
    clientDimension = dimensionKey(respawn.getDimensionInfo(), respawn.getDimension());
  }

  /**
   * Decides whether a respawn the backend is about to send would leave the client's world exactly
   * as it is, so that withholding it costs the client nothing and saves it a loading screen.
   *
   * <p>Folia moves a player across a region boundary by respawning them, so on Folia this is what
   * an ordinary long teleport looks like. Both halves have to hold. The world has to be the one
   * the client already holds, or withholding the packet would leave it in the wrong one. And the
   * server has to be asking for all player data to be kept: anything less means it wants the
   * player reset, as after a death, and then the screen is honest.</p>
   *
   * @param respawn the respawn packet the backend sent
   * @return whether the packet can be withheld
   */
  public boolean respawnKeepsClientWorld(RespawnPacket respawn) {
    if (!server.getConfiguration().isHideTeleportLoadingScreen()
        || player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_3)) {
      return false;
    }

    final String respawnedInto = dimensionKey(respawn.getDimensionInfo(), respawn.getDimension());
    final boolean sameWorld = clientDimension != null
        && Objects.equals(respawnedInto, clientDimension);
    final boolean keepsEverything = respawn.getDataToKeep() == RespawnPacket.KEEP_ALL_DATA;

    // One line per respawn, and only for those who turned this on. A respawn is rare and the two
    // reasons to decline are impossible to tell apart from the outside, which has cost enough
    // rounds of guessing already.
    if (!sameWorld || !keepsEverything) {
      LOGGER.info("[seamless] {}: forwarding a respawn into {} (the client holds {}), "
              + "dataToKeep={}; its loading screen stays", player.getUsername(),
          describe(respawnedInto), describe(clientDimension), respawn.getDataToKeep());
      return false;
    }

    LOGGER.info("[seamless] {}: withholding a respawn into {}; the client keeps its world and "
        + "draws no loading screen", player.getUsername(), describe(respawnedInto));
    return true;
  }

  /**
   * Says whether a client of this version reports back that it has finished loading, and so
   * whether the proxy has to report it on the client's behalf when it withholds the request to
   * wait.
   *
   * @param version the client's protocol version
   * @return whether the loaded handshake exists
   */
  public static boolean acknowledgesLoading(ProtocolVersion version) {
    return version.noLessThan(ProtocolVersion.MINECRAFT_1_21_4);
  }

  /**
   * Renders a level identity for a human. The key itself joins its parts with null bytes, which
   * read as nothing at all in a log line.
   *
   * @param key a level identity, or null if none has been recorded yet
   * @return something legible
   */
  private static String describe(final @Nullable String key) {
    if (key == null) {
      return "(nothing yet)";
    }
    final String[] parts = key.split("\u0000");
    if (parts.length < 3) {
      return key;
    }
    // The type identifier is empty from 1.20.5, where it is sent as the registry id instead.
    final String type = parts[0].isEmpty() ? "type " + parts[2] : parts[0];
    return parts[1] + " (" + type + ")";
  }

  /**
   * The identity of a client's level, for deciding whether it can be kept.
   *
   * <p>Both halves of the dimension are used, and both are load-bearing. From 1.20.5 the dimension
   * type is no longer sent as a string at all -- it is a registry id, and the identifier here is
   * left empty -- so on every modern version the level name is the only part that distinguishes
   * anything. A key built from the identifier alone compares "" against "" and calls every world
   * the same one, which withholds the respawn for a nether portal and leaves the client reading
   * 16-section nether chunks as a 384-block overworld.</p>
   *
   * <p>Being stricter than the client strictly requires is the safe direction: two worlds of one
   * dimension type really are interchangeable to a client, and are refused here anyway. The cost
   * is a loading screen that was not needed; the cost of the other mistake is a broken session.</p>
   *
   * @param info            the dimension the packet named, or null before 1.16
   * @param legacyDimension the numeric dimension used before 1.16
   * @return a key that is equal only when the client's level need not be rebuilt
   */
  static @Nullable String dimensionKey(@Nullable DimensionInfo info, int legacyDimension) {
    if (info == null) {
      return Integer.toString(legacyDimension);
    }
    return info.getRegistryIdentifier() + "\u0000" + info.getLevelName()
        + "\u0000" + legacyDimension;
  }

  /**
   * Decides whether the client can keep the world it already has for this switch.
   *
   * <p>Both halves have to hold: the destination has to have joined the player into the dimension
   * the client was last told about, and it has to have given them the entity ID the client already
   * holds -- which a backend arranges for itself, from the ID this proxy publishes through
   * {@link ClientWorldSwitches}. When either does not hold we fall back to an ordinary switch, so
   * turning this on cannot break a session, only fail to help.</p>
   */
  private boolean canKeepClientWorld(JoinGamePacket joinGame) {
    if (!server.getConfiguration().isKeepClientWorldOnSwitch()) {
      return false;
    }

    // Legacy Forge needs its own respawn dance, and before 1.16 a same-dimension switch needed an
    // extra respawn anyway, so neither can skip the packets.
    if (player.getConnection().getType() == ConnectionTypes.LEGACY_FORGE
        || player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_16)) {
      return false;
    }

    if (joinGame.getEntityId() != clientEntityId) {
      return false;
    }

    return Objects.equals(dimensionKey(joinGame.getDimensionInfo(), joinGame.getDimension()),
        clientDimension);
  }

  private void doFastClientServerSwitch(JoinGamePacket joinGame) {
    // In order to handle switching to another server, you will need to send two packets:
    //
    // - The join game packet from the backend server, with a different dimension
    // - A respawn with the correct dimension,
    //
    // Most notably, by having the client accept the join game packet, we can work around the need
    // to perform entity ID rewrites, eliminating potential issues from rewriting packets and
    // improving compatibility with mods.
    RespawnPacket respawn = RespawnPacket.fromJoinGame(joinGame);

    if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_16)) {
      // Before Minecraft 1.16, we could not switch to the same dimension without sending an
      // additional respawn. On older versions of Minecraft this forces the client to perform
      // garbage collection which adds additional latency.
      joinGame.setDimension(joinGame.getDimension() == 0 ? -1 : 0);
    }

    player.getConnection().delayedWrite(joinGame);
    player.getConnection().delayedWrite(respawn);
  }

  private void doSafeClientServerSwitch(JoinGamePacket joinGame) {
    // Some clients do not behave well with the "fast" respawn sequence.
    // In this case, we will use a "safe" respawn sequence that involves sending three packets to the client.
    // They have the same effect but tend to work better with buggier clients (Forge 1.8 in particular).

    // Send the JoinGame packet itself, unmodified.
    player.getConnection().delayedWrite(joinGame);

    // Send a respawn packet in a different dimension.
    RespawnPacket fakeSwitchPacket = RespawnPacket.fromJoinGame(joinGame);
    fakeSwitchPacket.setDimension(joinGame.getDimension() == 0 ? -1 : 0);
    player.getConnection().delayedWrite(fakeSwitchPacket);

    // Now send a respawn packet in the correct dimension.
    RespawnPacket correctSwitchPacket = RespawnPacket.fromJoinGame(joinGame);
    player.getConnection().delayedWrite(correctSwitchPacket);
  }

  public List<UUID> getServerBossBars() {
    return serverBossBars;
  }

  private boolean handleCommandTabComplete(TabCompleteRequestPacket packet) {
    // In 1.13+, we need to do additional work for the richer suggestions available.
    String command = packet.getCommand().substring(1);
    int commandEndPosition = command.indexOf(' ');
    if (commandEndPosition == -1) {
      commandEndPosition = command.length();
    }

    String commandLabel = command.substring(0, commandEndPosition);
    if (!server.getCommandManager().hasCommand(commandLabel, player)) {
      if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13)) {
        // Outstanding tab completes are recorded for use with 1.12 clients and below to provide
        // additional tab completion support.
        outstandingTabComplete = packet;
      }

      return false;
    }

    if (!server.getTabCompleteRateLimiter().attempt(player.getUniqueId())) {
      if (server.getConfiguration().isKickOnTabCompleteRateLimit()
          && failedTabCompleteAttempts++ >= server.getConfiguration().getKickAfterRateLimitedTabCompletes()) {
        player.disconnect(Component.translatable("velocity.kick.tab-complete-rate-limit"));
      }

      return true;
    }

    failedTabCompleteAttempts = 0;

    server.getCommandManager().offerBrigadierSuggestions(player, command)
        .thenAcceptAsync(suggestions -> {
          if (suggestions.isEmpty()) {
            return;
          }

          int startPos = -1;
          for (var suggestion : suggestions.getList()) {
            if (startPos == -1 || startPos > suggestion.getRange().getStart()) {
              startPos = suggestion.getRange().getStart();
            }
          }

          if (startPos > 0) {
            List<Offer> offers = new ArrayList<>();
            for (Suggestion suggestion : suggestions.getList()) {
              String offer;
              if (suggestion.getRange().getStart() == startPos) {
                offer = suggestion.getText();
              } else {
                offer = command.substring(startPos, suggestion.getRange().getStart()) + suggestion.getText();
              }

              ComponentHolder tooltip = null;
              if (suggestion.getTooltip() instanceof ComponentLike componentLike) {
                tooltip = new ComponentHolder(player.getProtocolVersion(), componentLike.asComponent());
              } else if (suggestion.getTooltip() != null) {
                tooltip = new ComponentHolder(player.getProtocolVersion(), Component.text(suggestion.getTooltip().getString()));
              }

              offers.add(new Offer(offer, tooltip));
            }

            TabCompleteResponsePacket resp = new TabCompleteResponsePacket();
            resp.setTransactionId(packet.getTransactionId());
            resp.setStart(startPos + 1);
            resp.setLength(packet.getCommand().length() - startPos - 1);
            resp.getOffers().addAll(offers);
            player.getConnection().write(resp);
          }
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          LOGGER.error("Exception while handling command tab completion for player {} executing {}",
              player, command, ex);
          return null;
        });

    return true; // Sorry, handler; we're just going to have to lie to you here.
  }

  private boolean handleRegularTabComplete(TabCompleteRequestPacket packet) {
    if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13)) {
      // Outstanding tab completes are recorded for use with 1.12 clients and below to provide
      // additional tab completion support.
      outstandingTabComplete = packet;
    }

    return false;
  }

  /**
   * Handles additional tab complete.
   *
   * @param response the tab complete response from the backend
   */
  public void handleTabCompleteResponse(TabCompleteResponsePacket response) {
    if (outstandingTabComplete != null && !outstandingTabComplete.isAssumeCommand()) {
      if (outstandingTabComplete.getCommand().startsWith("/")) {
        this.finishCommandTabComplete(outstandingTabComplete, response);
      } else {
        this.finishRegularTabComplete(outstandingTabComplete, response);
      }
      outstandingTabComplete = null;
    } else {
      // Nothing to do
      player.getConnection().write(response);
    }
  }

  private void finishCommandTabComplete(TabCompleteRequestPacket request,
                                        TabCompleteResponsePacket response) {
    server.getEventManager().fire(new TabCompleteRequestEvent(player, request.getCommand().substring(1)))
        .thenAcceptAsync(e -> {
          String command = e.getPartialMessage();
          server.getCommandManager().offerBrigadierSuggestions(player, command)
              .thenAcceptAsync(offers -> {
                boolean legacy = player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13);
                try {
                  for (Suggestion suggestion : offers.getList()) {
                    String offer = suggestion.getText();
                    offer = legacy && !offer.startsWith("/") ? "/" + offer : offer;
                    if (legacy && offer.startsWith(command)) {
                      offer = offer.substring(command.length());
                    }

                    ComponentHolder tooltip = null;
                    if (suggestion.getTooltip() instanceof ComponentLike componentLike) {
                      tooltip = new ComponentHolder(player.getProtocolVersion(), componentLike.asComponent());
                    } else if (suggestion.getTooltip() != null) {
                      tooltip = new ComponentHolder(player.getProtocolVersion(), Component.text(suggestion.getTooltip().getString()));
                    }

                    response.getOffers().add(new Offer(offer, tooltip));
                  }

                  response.getOffers().sort(null);
                  player.getConnection().write(response);
                } catch (Exception ex) {
                  LOGGER.error("Unable to provide tab list completions for {} for command '{}'", player.getUsername(), command, ex);
                }
              }, player.getConnection().eventLoop()).exceptionally((ex) -> {
                LOGGER.error("Exception while finishing command tab completion,"
                        + " with request {} and response {}",
                    request, response, ex);
                return null;
              });
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          LOGGER.error("Exception while finishing command tab completion,"
                  + " with request {} and response {}",
              request, response, ex);
          return null;
        });
  }

  private void finishRegularTabComplete(TabCompleteRequestPacket request,
                                        TabCompleteResponsePacket response) {
    List<String> offers = new ArrayList<>();
    for (Offer offer : response.getOffers()) {
      offers.add(offer.getText());
    }
    server.getEventManager().fire(new TabCompleteEvent(player, request.getCommand(), offers))
        .thenAcceptAsync(e -> {
          response.getOffers().clear();
          for (String s : e.getSuggestions()) {
            response.getOffers().add(new Offer(s));
          }
          player.getConnection().write(response);
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          LOGGER.error(
              "Exception while finishing regular tab completion,"
                  + " with request {} and response {}", request, response, ex);
          return null;
        });
  }

  /**
   * Immediately send any queued messages to the server.
   */
  public void flushQueuedMessages() {
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection != null) {
      MinecraftConnection connection = serverConnection.getConnection();
      if (connection != null) {
        PluginMessagePacket pm;
        while ((pm = loginPluginMessages.poll()) != null) {
          connection.write(pm);
        }

        loginPluginMessagesBytes.set(0);
        loginPluginMessagesCount.set(0);
      }
    }
  }
}
