package com.amazonaws.services.gamelift;

import static com.google.common.base.Preconditions.checkNotNull;

import com.amazon.whitewater.auxproxy.pbuffer.Sdk;
import com.amazonaws.services.gamelift.model.AmazonGameLiftException;
import com.amazonaws.services.gamelift.model.DescribePlayerSessionsRequest;
import com.amazonaws.services.gamelift.model.DescribePlayerSessionsResult;
import com.amazonaws.services.gamelift.model.GameSession;
import com.amazonaws.services.gamelift.model.GameSessionStatus;
import com.amazonaws.services.gamelift.model.PlayerSession;
import com.amazonaws.services.gamelift.model.PlayerSessionCreationPolicy;
import com.amazonaws.services.gamelift.model.ProcessParameters;
import com.amazonaws.services.gamelift.model.StartMatchBackfillRequest;
import com.amazonaws.services.gamelift.model.StopMatchBackfillRequest;
import com.amazonaws.services.gamelift.model.UpdateGameSession;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class AmazonGameLiftServerImpl implements AmazonGameLiftServer {

  static final AmazonGameLiftServer INSTANCE = new AmazonGameLiftServerImpl();

  private static final String SDK_VERSION = "3.4.0";
  private static final String SDK_LANGUAGE = "Java";

  private Socket socket;
  private ScheduledFuture healthCheck;
  private ProcessParameters processParameters;
  private String gameSessionId;
  private long terminationTime;

  private AmazonGameLiftServerImpl() {
    this("127.0.0.1", 5757, ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
  }

  private AmazonGameLiftServerImpl(String hostname, int port, String processId) {
    IO.Options options = new IO.Options();
    options.query =
        String.format("sdkVersion=%s&sdkLanguage=%s&pID=%s", SDK_VERSION, SDK_LANGUAGE, processId);
    options.transports = new String[] {"websocket"};
    options.reconnection = false;
    options.forceNew = true;
    options.multiplex = true;
    options.timeout = 5000L;

    try {
      socket = IO.socket(String.format("http://%s:%d", hostname, port), options);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }

    socket.on("StartGameSession", new StartGameSessionListener());
    socket.on("UpdateGameSession", new UpdateGameSessionListener());
    socket.on("TerminateProcess", new TerminateProcessListener());
  }

  @Override
  public String getSdkVersion() {
    return SDK_VERSION;
  }

  @Override
  public void initSdk() throws TimeoutException {
    if (socket.connected()) return;

    CountDownLatch latch = new CountDownLatch(1);
    socket.once(Socket.EVENT_CONNECT, data -> latch.countDown());
    socket.connect();

    try {
      if (latch.await(5, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException e) {
      // Fallthrough to become a timeout
    }

    throw new TimeoutException();
  }

  private void checkInitialized() {
    if (!socket.connected()) {
      throw new IllegalStateException(
          "Network connection is not initialized (called initSdk() yet?)");
    }
  }

  private void reportHealth() {
    if (!socket.connected() || processParameters == null) return;

    boolean ok;
    try {
      ok = processParameters.getHealthCheck().get();
    } catch (Throwable t) {
      ok = false;
    }

    sendMessage(Sdk.ReportHealth.newBuilder().setHealthStatus(ok).build());
  }

  @Override
  public void processReady(ProcessParameters processParameters) {
    checkNotNull(processParameters, "process parameters are null");
    checkInitialized();

    sendMessage(
        Sdk.ProcessReady.newBuilder()
            .setPort(processParameters.getPort())
            .addAllLogPathsToUpload(processParameters.getLogPathsToUpload())
            .build());
    this.processParameters = processParameters;

    if (healthCheck == null || healthCheck.isDone()) {
      healthCheck =
          Executors.newSingleThreadScheduledExecutor()
              .scheduleAtFixedRate(this::reportHealth, 0L, 15L, TimeUnit.SECONDS);
    }
  }

  @Override
  public void processEnding() {
    checkInitialized();

    sendMessage(Sdk.ProcessEnding.newBuilder().build());
  }

  @Override
  public String getGameSessionId() {
    return gameSessionId;
  }

  private void checkGameSession() {
    checkInitialized();
    if (gameSessionId == null) {
      throw new IllegalStateException("Game session is not bound (called readyProcess() yet?)");
    }
  }

  @Override
  public void activateGameSession() {
    checkGameSession();

    sendMessage(Sdk.GameSessionActivate.newBuilder().setGameSessionId(gameSessionId).build());
  }

  @Override
  public void terminateGameSession() {
    checkGameSession();

    sendMessage(Sdk.GameSessionTerminate.newBuilder().setGameSessionId(gameSessionId).build());
    gameSessionId = null;
  }

  @Override
  public void updatePlayerSessionCreationPolicy(PlayerSessionCreationPolicy creationPolicy) {
    checkNotNull(creationPolicy, "player session creation policy is null");
    checkGameSession();

    sendMessage(
        Sdk.UpdatePlayerSessionCreationPolicy.newBuilder()
            .setGameSessionId(gameSessionId)
            .setNewPlayerSessionCreationPolicy(creationPolicy.name())
            .build());
  }

  @Override
  public void acceptPlayerSession(String playerSessionId) {
    checkNotNull(playerSessionId, "player session id is null");
    checkGameSession();

    sendMessage(
        Sdk.AcceptPlayerSession.newBuilder()
            .setGameSessionId(gameSessionId)
            .setPlayerSessionId(playerSessionId)
            .build());
  }

  @Override
  public void removePlayerSession(String playerSessionId) {
    checkNotNull(playerSessionId, "player session id is null");
    checkGameSession();

    sendMessage(
        Sdk.RemovePlayerSession.newBuilder()
            .setGameSessionId(gameSessionId)
            .setPlayerSessionId(playerSessionId)
            .build());
  }

  @Override
  public DescribePlayerSessionsResult describePlayerSessions(
      DescribePlayerSessionsRequest request) {
    checkNotNull(request, "describe player sessions request is null");
    checkInitialized();

    Sdk.DescribePlayerSessionsRequest.Builder builder =
        Sdk.DescribePlayerSessionsRequest.newBuilder();
    if (request.getNextToken() != null) builder.setNextToken(request.getNextToken());
    if (request.getLimit() != null) builder.setLimit(Math.max(1024, request.getLimit()));
    if (request.getGameSessionId() != null) builder.setGameSessionId(request.getGameSessionId());
    if (request.getPlayerSessionId() != null)
      builder.setPlayerSessionId(request.getPlayerSessionId());
    if (request.getPlayerId() != null) builder.setPlayerId(request.getPlayerId());
    if (request.getPlayerSessionStatusFilter() != null)
      builder.setPlayerSessionStatusFilter(request.getPlayerSessionStatusFilter());

    String rawResponse = sendMessage(builder.build());
    Sdk.DescribePlayerSessionsResponse.Builder response =
        Sdk.DescribePlayerSessionsResponse.newBuilder();
    try {
      JsonFormat.parser().merge(rawResponse, response);
    } catch (InvalidProtocolBufferException e) {
      throw new AmazonGameLiftException("Received a malformed session response");
    }

    DescribePlayerSessionsResult result = new DescribePlayerSessionsResult();
    if (response.hasNextToken()) result.setNextToken(response.getNextToken());

    List<PlayerSession> players = new LinkedList<>();
    for (Sdk.PlayerSession sdkPlayer : response.getPlayerSessionsList()) {
      PlayerSession player = new PlayerSession();

      if (sdkPlayer.hasPlayerId()) player.setPlayerId(sdkPlayer.getPlayerId());
      if (sdkPlayer.hasPlayerSessionId()) player.setPlayerSessionId(sdkPlayer.getPlayerSessionId());
      if (sdkPlayer.hasPlayerData()) player.setPlayerData(sdkPlayer.getPlayerData());
      if (sdkPlayer.hasGameSessionId()) player.setGameSessionId(sdkPlayer.getGameSessionId());
      if (sdkPlayer.hasIpAddress()) player.setIpAddress(sdkPlayer.getIpAddress());
      if (sdkPlayer.hasPort()) player.setPort(sdkPlayer.getPort());
      if (sdkPlayer.hasStatus()) player.setStatus(sdkPlayer.getStatus());
      if (sdkPlayer.hasCreationTime())
        player.setCreationTime(new Date(sdkPlayer.getCreationTime()));
      if (sdkPlayer.hasTerminationTime())
        player.setTerminationTime(new Date(sdkPlayer.getTerminationTime()));
      if (sdkPlayer.hasDnsName()) {
        player.setDnsName(sdkPlayer.getDnsName());
      } else if (sdkPlayer.hasIpAddress() && sdkPlayer.getIpAddress().equals("127.0.0.1")) {
        player.setDnsName("localhost");
      }
      if (sdkPlayer.hasFleetId()) {
        player.setFleetId(sdkPlayer.getFleetId());
      }

      players.add(player);
    }
    result.setPlayerSessions(players);

    return result;
  }

  @Override
  public void startMatchBackfill(StartMatchBackfillRequest request) {
    checkNotNull(request, "start match backfill request is null");
    checkInitialized();

    Sdk.BackfillMatchmakingRequest.Builder builder = Sdk.BackfillMatchmakingRequest.newBuilder();
    if (request.getTicketId() != null) builder.setTicketId(request.getTicketId());
    if (request.getGameSessionArn() != null) builder.setGameSessionArn(request.getGameSessionArn());
    if (request.getConfigurationName() != null)
      builder.setMatchmakingConfigurationArn(request.getConfigurationName());
    if (request.getPlayers() != null && !request.getPlayers().isEmpty()) {
      // TODO: add setPlayers support
      throw new UnsupportedOperationException("Backfill players are not yet supported!");
    }

    sendMessage(builder.build());
  }

  @Override
  public void stopMatchBackfill(StopMatchBackfillRequest request) {
    checkNotNull(request, "stop match backfill request is null");
    checkInitialized();

    Sdk.StopMatchmakingRequest.Builder builder = Sdk.StopMatchmakingRequest.newBuilder();
    if (request.getTicketId() != null) builder.setTicketId(request.getTicketId());
    if (request.getGameSessionArn() != null) builder.setGameSessionArn(request.getGameSessionArn());
    if (request.getConfigurationName() != null)
      builder.setMatchmakingConfigurationArn(request.getConfigurationName());

    sendMessage(builder.build());
  }

  @Override
  public long getTerminationTime() {
    return terminationTime;
  }

  @Override
  public void destroy() {
    try {
      if (gameSessionId != null) terminateGameSession();
      processEnding();
    } catch (Throwable t) {
      // Try to terminate gracefully, but silence any errors
    }

    if (healthCheck != null) healthCheck.cancel(true);
    socket.disconnect();
    terminationTime = System.currentTimeMillis();
  }

  private String sendMessage(Message message) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> response = new AtomicReference<>();
    Ack ack =
        (Object... objects) -> {
          if (objects.length < 1 || objects[0] == null) {
            return;
          }

          boolean ok = Boolean.valueOf(objects[0].toString());
          if (ok) {
            response.set(objects[1].toString());
            latch.countDown();
          }
        };

    socket.emit(message.getDescriptorForType().getFullName(), message.toByteArray(), ack);

    try {
      if (latch.await(5, TimeUnit.SECONDS)) {
        return response.get();
      }
    } catch (InterruptedException e) {
      // Fallthrough to become a timeout
    }

    throw new AmazonGameLiftException("Did not receive a response from the GameLift server");
  }

  // TODO: add creatorId, fleetArn, gameProperties, status, statusReason,
  // currentPlayerSessionCount, creationTime, and terminationTime support
  GameSession gameSession(Sdk.GameSession sdkGame) {
    GameSession game = new GameSession();

    if (sdkGame.hasGameSessionId()) game.setGameSessionId(sdkGame.getGameSessionId());
    if (sdkGame.hasFleetId()) game.setFleetId(sdkGame.getFleetId());
    if (sdkGame.hasMaxPlayers()) game.setMaximumPlayerSessionCount(sdkGame.getMaxPlayers());
    if (sdkGame.hasDnsName()) game.setDnsName(sdkGame.getDnsName());
    if (sdkGame.hasIpAddress()) game.setIpAddress(sdkGame.getIpAddress());
    if (sdkGame.hasPort()) game.setPort(sdkGame.getPort());
    if (sdkGame.hasName()) game.setName(sdkGame.getName());
    if (sdkGame.hasGameSessionData()) game.setName(sdkGame.getGameSessionData());
    if (sdkGame.hasMatchmakerData()) game.setMatchmakerData(sdkGame.getMatchmakerData());

    return game;
  }

  class TerminateProcessListener extends SocketListener {
    @Override
    void onResponse(String rawResponse) {
      Sdk.TerminateProcess.Builder response = Sdk.TerminateProcess.newBuilder();

      try {
        JsonFormat.parser().merge(rawResponse, response);
        terminationTime = response.getTerminationTime();
      } catch (InvalidProtocolBufferException e) {
        terminationTime = System.currentTimeMillis();
      }

      Executors.newCachedThreadPool().submit(() -> processParameters.getProcessTerminate().run());
    }
  }

  class StartGameSessionListener extends SocketListener {
    @Override
    void onResponse(String rawResponse) throws InvalidProtocolBufferException {
      Sdk.ActivateGameSession.Builder response = Sdk.ActivateGameSession.newBuilder();
      JsonFormat.parser().merge(rawResponse, response);

      GameSession game =
          gameSession(response.getGameSession()).withStatus(GameSessionStatus.ACTIVATING);
      gameSessionId = game.getGameSessionId();
      Executors.newCachedThreadPool()
          .submit(() -> processParameters.getGameSessionStart().accept(game));
    }
  }

  class UpdateGameSessionListener extends StartGameSessionListener {
    @Override
    void onResponse(String rawResponse) throws InvalidProtocolBufferException {
      Sdk.UpdateGameSession.Builder response = Sdk.UpdateGameSession.newBuilder();
      JsonFormat.parser().merge(rawResponse, response);

      UpdateGameSession update =
          new UpdateGameSession()
              .withUpdateReason(response.getUpdateReason())
              .withBackfillTicketId(response.getBackfillTicketId())
              .withGameSession(gameSession(response.getGameSession()));

      if (!Objects.equals(gameSessionId, update.getGameSession().getGameSessionId())) {
        throw new AmazonGameLiftException("Received an update from a different game session");
      }

      Executors.newCachedThreadPool()
          .submit(() -> processParameters.getGameSessionUpdate().accept(update));
    }
  }

  abstract class SocketListener implements Emitter.Listener {
    abstract void onResponse(String rawResponse) throws InvalidProtocolBufferException;

    @Override
    public void call(Object... objects) {
      Ack ack = objects.length > 1 ? (Ack) objects[1] : (Object... o) -> {};

      if (processParameters == null) {
        ack.call(false);
        return;
      }

      try {
        onResponse(objects[0].toString());
        ack.call(true);
      } catch (InvalidProtocolBufferException t) {
        ack.call(false);
      }
    }
  }
}
