package com.amazonaws.services.gamelift;

import static com.google.common.base.Preconditions.checkNotNull;

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
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

class AmazonGameLiftServerImpl implements AmazonGameLiftServer {

  static final AmazonGameLiftServer INSTANCE = new AmazonGameLiftServerImpl();

  private static final String SDK_VERSION = "3.4.0";
  private static final String SDK_LANGUAGE = "Java";

  private Logger logger;
  private Socket socket;
  private ScheduledExecutorService executorService;
  private ScheduledFuture healthCheck;
  private ProcessParameters processParameters;
  private String gameSessionId;
  private long terminationTime;

  private AmazonGameLiftServerImpl() {
    this("127.0.0.1", 5757, ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
  }

  private AmazonGameLiftServerImpl(String hostname, int port, String processId) {
    String className = AmazonGameLiftServer.class.getSimpleName();
    setLogger(Logger.getLogger(className));

    executorService =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setThreadFactory(Executors.defaultThreadFactory())
                .setNameFormat(className)
                .setUncaughtExceptionHandler(
                    (Thread thread, Throwable throwable) ->
                        logger.log(Level.SEVERE, "Caught an unhandled exception", throwable))
                .build());

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

    for (String event :
        Lists.newArrayList(
            Socket.EVENT_CONNECT,
            Socket.EVENT_CONNECT_ERROR,
            Socket.EVENT_CONNECT_TIMEOUT,
            Socket.EVENT_DISCONNECT,
            Socket.EVENT_ERROR)) {
      new SocketListener(event, socket);
    }

    new StartGameSessionListener(socket);
    new UpdateGameSessionListener(socket);
    new TerminateProcessListener(socket);
  }

  @Override
  public void setLogger(Logger logger) {
    this.logger = checkNotNull(logger, "logger is null");
  }

  @Override
  public String getSdkVersion() {
    return SDK_VERSION;
  }

  @Override
  public boolean initSdk() {
    if (executorService.isShutdown()) return false;
    if (socket.connected()) return true;

    CountDownLatch latch = new CountDownLatch(1);
    socket.once(Socket.EVENT_CONNECT, data -> latch.countDown());
    socket.once(Socket.EVENT_CONNECT_TIMEOUT, data -> latch.countDown());
    socket.once(Socket.EVENT_CONNECT_ERROR, data -> latch.countDown());

    socket.connect();

    try {
      if (latch.await(5, TimeUnit.SECONDS)) {
        return socket.connected();
      }
    } catch (InterruptedException e) {
    }

    return false;
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
      logger.log(Level.WARNING, "Unable to process health check", t);
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

    if (healthCheck != null) healthCheck.cancel(true);
    healthCheck = executorService.scheduleAtFixedRate(this::reportHealth, 0L, 1L, TimeUnit.MINUTES);
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
    if (request.getLimit() != null)
      builder.setLimit(Math.max(1, Math.min(1024, request.getLimit())));
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
      throw new AmazonGameLiftServerException("Received a malformed session response", e);
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
    if (!builder.hasGameSessionArn()) builder.setGameSessionArn(gameSessionId);
    if (request.getConfigurationName() != null && gameSessionId != null)
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
    if (!builder.hasGameSessionArn() && gameSessionId != null)
      builder.setGameSessionArn(gameSessionId);
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
    executorService.shutdown();
    socket.disconnect();
  }

  private String sendMessage(Message message) {
    CompletableFuture<String> response = new CompletableFuture<>();

    logger.log(
        Level.FINE,
        String.format(
            "Sending socket %s request: %s", message.getDescriptorForType().getName(), message));
    socket.emit(
        message.getDescriptorForType().getFullName(),
        message.toByteArray(),
        (Ack) responseData -> receiveMessage(responseData, response));

    try {
      return response.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw cause instanceof AmazonGameLiftServerException
          ? (AmazonGameLiftServerException) cause
          : new AmazonGameLiftServerException("Unhandled socket response", cause);
    } catch (InterruptedException | TimeoutException e) {
      throw new AmazonGameLiftServerException("Timeout socket response", e);
    }
  }

  private void receiveMessage(Object[] responseData, CompletableFuture<String> response) {
    if (responseData.length < 2) {
      response.completeExceptionally(
          new AmazonGameLiftServerException(
              String.format("Unrecognized socket response: %s", Lists.newArrayList(responseData))));
      return;
    }

    boolean ok = Boolean.valueOf(responseData[0].toString());
    String data = responseData[1].toString();
    logger.log(
        Level.FINE, String.format("Received %s socket response: %s", ok ? "ok" : "bad", data));

    try {
      Sdk.GameLiftResponse.Builder builder = Sdk.GameLiftResponse.newBuilder();
      JsonFormat.parser().merge(data, builder);
      Sdk.GameLiftResponse status = builder.build();

      if (status.getStatus() == Sdk.GameLiftResponseStatus.OK) {
        response.complete(status.getResponseData());
      } else {
        response.completeExceptionally(
            new AmazonGameLiftServerException(
                String.format(
                    "Received %s socket response: %s",
                    status.getStatus(), status.getErrorMessage())));
      }
    } catch (InvalidProtocolBufferException e) {
      response.complete(data);
    }
  }

  // TODO: add creatorId, fleetArn, gameProperties, status, statusReason,
  // currentPlayerSessionCount, creationTime, and terminationTime support
  private GameSession gameSession(Sdk.GameSession sdkGame) {
    GameSession game = new GameSession();

    if (sdkGame.hasGameSessionId()) game.setGameSessionId(sdkGame.getGameSessionId());
    if (sdkGame.hasFleetId()) game.setFleetId(sdkGame.getFleetId());
    if (sdkGame.hasMaxPlayers()) game.setMaximumPlayerSessionCount(sdkGame.getMaxPlayers());
    if (sdkGame.hasDnsName()) game.setDnsName(sdkGame.getDnsName());
    if (sdkGame.hasIpAddress()) game.setIpAddress(sdkGame.getIpAddress());
    if (sdkGame.hasPort()) game.setPort(sdkGame.getPort());
    if (sdkGame.hasName()) game.setName(sdkGame.getName());
    if (sdkGame.hasGameSessionData()) game.setGameSessionData(sdkGame.getGameSessionData());
    if (sdkGame.hasMatchmakerData()) game.setMatchmakerData(sdkGame.getMatchmakerData());

    return game;
  }

  private class TerminateProcessListener extends SocketListener {
    private TerminateProcessListener(Socket socket) {
      super("ProcessTerminate", socket);
    }

    @Override
    void onResponse(String rawResponse) {
      Sdk.TerminateProcess.Builder response = Sdk.TerminateProcess.newBuilder();

      try {
        JsonFormat.parser().merge(rawResponse, response);
        terminationTime = response.getTerminationTime();
      } catch (InvalidProtocolBufferException e) {
        terminationTime = System.currentTimeMillis();
      }

      executorService.submit(processParameters.getProcessTerminate());
    }
  }

  private class StartGameSessionListener extends SocketListener {
    private StartGameSessionListener(Socket socket) {
      super("StartGameSession", socket);
    }

    @Override
    void onResponse(String rawResponse) throws InvalidProtocolBufferException {
      Sdk.ActivateGameSession.Builder response = Sdk.ActivateGameSession.newBuilder();
      JsonFormat.parser().merge(rawResponse, response);

      GameSession game =
          gameSession(response.getGameSession()).withStatus(GameSessionStatus.ACTIVATING);
      gameSessionId = game.getGameSessionId();

      executorService.submit(() -> processParameters.getGameSessionStart().accept(game));
    }
  }

  private class UpdateGameSessionListener extends SocketListener {
    private UpdateGameSessionListener(Socket socket) {
      super("UpdateGameSession", socket);
    }

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
        throw new AmazonGameLiftServerException("Received an update from an unknown game session");
      }

      executorService.submit(() -> processParameters.getGameSessionUpdate().accept(update));
    }
  }

  private class SocketListener implements Emitter.Listener {
    private final String name;

    private SocketListener(String name, Socket socket) {
      this.name = name;

      socket.on(name, this);
    }

    void onResponse(String data) throws Throwable {}

    @Override
    public void call(Object... objects) {
      boolean ack = false;

      if (objects.length == 0) {
        logger.log(Level.FINE, String.format("Received socket %s message", name));
      } else {
        logger.log(Level.FINE, String.format("Received socket %s message: %s", name, objects[0]));
      }

      try {
        if (processParameters != null) {
          onResponse(objects[0].toString());
          ack = true;
        }
      } catch (Throwable t) {
        logger.log(Level.SEVERE, String.format("Unable to process socket %s message", name), t);
      }

      if (objects.length > 1) {
        logger.log(
            Level.FINE,
            String.format(
                "Sending %sack in response to socket %s message", ack ? "" : "un-", name));

        ((Ack) objects[1]).call(ack);
      }
    }
  }
}
