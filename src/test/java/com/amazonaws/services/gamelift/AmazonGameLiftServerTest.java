package com.amazonaws.services.gamelift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.gamelift.model.CreateGameSessionRequest;
import com.amazonaws.services.gamelift.model.CreatePlayerSessionRequest;
import com.amazonaws.services.gamelift.model.DescribeGameSessionsRequest;
import com.amazonaws.services.gamelift.model.DescribePlayerSessionsRequest;
import com.amazonaws.services.gamelift.model.GameSession;
import com.amazonaws.services.gamelift.model.GameSessionStatus;
import com.amazonaws.services.gamelift.model.PlayerSession;
import com.amazonaws.services.gamelift.model.PlayerSessionCreationPolicy;
import com.amazonaws.services.gamelift.model.PlayerSessionStatus;
import com.amazonaws.services.gamelift.model.ProcessParameters;
import com.amazonaws.services.gamelift.model.StartMatchBackfillRequest;
import com.amazonaws.services.gamelift.model.UpdateGameSession;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AmazonGameLiftServerTest {

  private AmazonGameLift client;
  private AmazonGameLiftServer server;
  private boolean healthCheck;
  private GameSession gameSession;
  private PlayerSession playerSession;
  private UpdateGameSession updateGameSession;

  @BeforeAll
  void setUp() {
    client =
        AmazonGameLiftClientBuilder.standard()
            .withEndpointConfiguration(
                new AwsClientBuilder.EndpointConfiguration("http://127.0.0.1:8080", null))
            .build();
    server = AmazonGameLiftServer.get();

    Handler handler = new ConsoleHandler();
    handler.setLevel(Level.ALL);
    Logger logger = Logger.getGlobal();
    logger.setLevel(Level.ALL);
    logger.addHandler(handler);

    server.setLogger(logger);
  }

  @AfterAll
  void tearDown() {
    server.destroy();

    assertFalse(server.initSdk(), "destroy did not reject future attempts to initialize");
  }

  @Order(1)
  @Test
  void testInitSdk() throws TimeoutException {
    assertTrue(server.initSdk(), "sdk initialization did not succeed");
    assertEquals("3.4.0", server.getSdkVersion(), "sdk version is not updated");
    assertEquals(0L, server.getTerminationTime(), "termination time does not start with 0");
    assertNull(server.getGameSessionId(), "game session id does not start with null");
  }

  @Order(2)
  @Test
  void testProcessReady() {
    server.processReady(
        new ProcessParameters()
            .withPort(1337)
            .withLogPathsToUpload(Collections.singleton("logs.txt"))
            .withHealthCheck(() -> healthCheck = true)
            .withGameSessionStart(start -> gameSession = start)
            .withGameSessionUpdate(update -> updateGameSession = update));

    GameSession newGameSession =
        client
            .createGameSession(
                new CreateGameSessionRequest()
                    .withFleetId("fleet-123")
                    .withMaximumPlayerSessionCount(1)
                    .withGameSessionData("data"))
            .getGameSession();
    assertEquals(
        newGameSession, gameSession, "callback game session does not match api game session");
    assertEquals(
        newGameSession.getGameSessionId(),
        server.getGameSessionId(),
        "game session id does not match");
    assertTrue(healthCheck, "health check was not reported");
  }

  @Order(3)
  @Test
  void testActivateGameSession() {
    server.activateGameSession();

    assertEquals(
        GameSessionStatus.ACTIVE.name(),
        gameSession().getStatus(),
        "game session was not activated");
  }

  @Order(4)
  @Test
  void testAcceptPlayerSession() {
    playerSession =
        client
            .createPlayerSession(
                new CreatePlayerSessionRequest()
                    .withGameSessionId(gameSession.getGameSessionId())
                    .withPlayerId("electroid"))
            .getPlayerSession();
    server.acceptPlayerSession(playerSession.getPlayerSessionId());

    assertEquals(
        PlayerSessionStatus.ACTIVE.name(),
        playerSession().getStatus(),
        "player session was not accepted");
  }

  @Order(5)
  @Test
  void testDescribePlayerSessions() {
    assertEquals(
        playerSession,
        server
            .describePlayerSessions(
                new DescribePlayerSessionsRequest()
                    .withPlayerSessionId(playerSession.getPlayerSessionId()))
            .getPlayerSessions()
            .get(0),
        "server player session does not match api player session");
  }

  @Order(6)
  @Test
  void testRemovePlayerSession() {
    server.removePlayerSession(playerSession.getPlayerSessionId());

    assertEquals(
        PlayerSessionStatus.COMPLETED.name(),
        playerSession().getStatus(),
        "player session was not removed");
  }

  @Order(7)
  @Test
  void testStartMatchBackfill() {
    assertThrows(
        AmazonGameLiftServerException.class,
        () ->
            server.startMatchBackfill(new StartMatchBackfillRequest().withTicketId("goldenticket")),
        "match backfill did not return 400 error");
  }

  @Order(8)
  @Test
  void testTerminateGameSession() {
    server.terminateGameSession();

    assertEquals(
        GameSessionStatus.TERMINATED.name(),
        gameSession().getStatus(),
        "game session was not terminated");
    assertNull(server.getGameSessionId(), "game session id was not cleared");
  }

  @Order(9)
  @Test
  void testProcessReadyAgain() {
    testProcessReady();
  }

  @Order(10)
  @Test
  void testUpdatePlayerSessionCreationPolicy() {
    server.updatePlayerSessionCreationPolicy(PlayerSessionCreationPolicy.DENY_ALL);
  }

  @Order(11)
  @Test
  void testProcessEnding() {
    server.processEnding();

    assertEquals(
        GameSessionStatus.TERMINATED.name(),
        gameSession().getStatus(),
        "game session did not terminate");
  }

  private GameSession gameSession() {
    return gameSession =
        client
            .describeGameSessions(
                new DescribeGameSessionsRequest().withGameSessionId(gameSession.getGameSessionId()))
            .getGameSessions()
            .get(0);
  }

  private PlayerSession playerSession() {
    return playerSession =
        client
            .describePlayerSessions(
                new DescribePlayerSessionsRequest()
                    .withPlayerSessionId(playerSession.getPlayerSessionId()))
            .getPlayerSessions()
            .get(0);
  }
}
