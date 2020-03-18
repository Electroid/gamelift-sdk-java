package com.amazonaws.services.gamelift;

import com.amazonaws.services.gamelift.model.AmazonGameLiftException;
import com.amazonaws.services.gamelift.model.DescribePlayerSessionsRequest;
import com.amazonaws.services.gamelift.model.DescribePlayerSessionsResult;
import com.amazonaws.services.gamelift.model.GameSession;
import com.amazonaws.services.gamelift.model.PlayerSessionCreationPolicy;
import com.amazonaws.services.gamelift.model.ProcessParameters;
import com.amazonaws.services.gamelift.model.StartMatchBackfillRequest;
import com.amazonaws.services.gamelift.model.StopMatchBackfillRequest;
import java.util.logging.Logger;

/**
 * Interface for creating an Amazon GameLift custom server.
 *
 * <p>Your game server needs to communicate with the Amazon GameLift service once it is deployed and
 * running on Amazon GameLift instances. Each game server process must be able to respond to events
 * when they are triggered by the Amazon GameLift service, and it must keep Amazon GameLift informed
 * about the server process status and (optionally) player connections.
 *
 * <ul>
 *   <li>1. {@link #initSdk()} - initialization and setup.
 *   <li>2. {@link #processReady(ProcessParameters)} - ready to accept a game.
 *   <li>3. {@link #activateGameSession()} - accepts the game, allows players to join.
 *   <li>4. {@link #acceptPlayerSession(String)} - accepts a player session.
 *   <li>5. {@link #terminateGameSession()} - stops the game.
 *   <li>6. then either do:
 *       <ul>
 *         <li>A. {@link #processReady(ProcessParameters)} - ready to accept another game, repeat
 *             steps.
 *         <li>B. {@link #processEnding()} - ready to shutdown and recycle the host.
 *       </ul>
 *       <p>
 * </ul>
 *
 * @apiNote Design and method names are designed to conform to the existing C# SDKs.
 * @link https://docs.aws.amazon.com/gamelift/latest/developerguide/gamelift-sdk-server-api.html
 */
public interface AmazonGameLiftServer {

  /**
   * Get the global instance of the GameLift server.
   *
   * @return The global GameLift server.
   */
  static AmazonGameLiftServer get() {
    return AmazonGameLiftServerImpl.INSTANCE;
  }

  /**
   * Set the logger for the server.
   *
   * @param logger A logger.
   */
  void setLogger(Logger logger);

  /**
   * Get the current sdk version.
   *
   * @return The sdk version.
   */
  String getSdkVersion();

  /**
   * Initialize a connection to the GameLift server.
   *
   * @return If the GameLift server is connected.
   */
  boolean initSdk();

  /**
   * Mark as ready to accept a new game session.
   *
   * <p>When a {@link GameSession} is assigned, the {@link ProcessParameters#getGameSessionStart()}
   * callback is invoked asynchronously. To acknowledge this assignment, invoke {@link
   * #activateGameSession()}.
   *
   * @param processParameters Parameters for the new game session.
   * @throws IllegalStateException If {@link #initSdk()} was not yet invoked.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void processReady(ProcessParameters processParameters);

  /**
   * Mark as ready to shutdown the host.
   *
   * <p>Prior to host shutdown, {@link ProcessParameters#getProcessTerminate()} is invoked
   * asynchronously.
   *
   * @throws IllegalStateException If {@link #processReady(ProcessParameters)} was not yet invoked.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void processEnding();

  /**
   * Get the game session id, if a game has been assigned.
   *
   * @return A game session id or null if not assigned.
   */
  String getGameSessionId();

  /**
   * Mark the game session as ready and accept player sessions.
   *
   * @throws IllegalStateException If {@link #getGameSessionId()} was null.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void activateGameSession();

  /**
   * Terminate the activated game session.
   *
   * <p>Next, invoke either {@link #processReady(ProcessParameters)} to request another game
   * session, or {@link #processEnding()} to queue a termination.
   *
   * @throws IllegalStateException If {@link #getGameSessionId()} was null.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void terminateGameSession();

  /**
   * Update the policy of new player sessions.
   *
   * @param playerSessionCreationPolicy The creation policy of player sessions.
   * @throws IllegalStateException If {@link #getGameSessionId()} was null.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void updatePlayerSessionCreationPolicy(PlayerSessionCreationPolicy playerSessionCreationPolicy);

  /**
   * Accept a player session.
   *
   * <p>To find the player session id from a player id, search using {@link
   * #describePlayerSessions(DescribePlayerSessionsRequest)}.
   *
   * @param playerSessionId The player session id.
   * @throws IllegalStateException If {@link #getGameSessionId()} was null.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void acceptPlayerSession(String playerSessionId);

  /**
   * Remove a player session.
   *
   * @param playerSessionId The player session id.
   * @throws IllegalStateException If {@link #getGameSessionId()} was null.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void removePlayerSession(String playerSessionId);

  /**
   * Search for player sessions by criteria.
   *
   * <p>By default, all player sessions are returned, including those from other game sessions.
   *
   * @param describePlayerSessionsRequest A request for player sessions.
   * @return A response of player sessions.
   * @throws IllegalStateException If {@link #initSdk()} is not yet invoked.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  DescribePlayerSessionsResult describePlayerSessions(
      DescribePlayerSessionsRequest describePlayerSessionsRequest);

  /**
   * Request for additional player sessions.
   *
   * <p>Each request should have a unique ticketId, which could also be used to issue a {@link
   * #stopMatchBackfill(StopMatchBackfillRequest)}.
   *
   * @param startMatchBackfillRequest A request for new player sessions.
   * @throws IllegalStateException If {@link #initSdk()} is not yet invoked.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void startMatchBackfill(StartMatchBackfillRequest startMatchBackfillRequest);

  /**
   * Stop a request for additional player sessions.
   *
   * @param stopMatchBackfillRequest A request to stop a backfill.
   * @throws IllegalStateException If {@link #initSdk()} is not yet invoked.
   * @throws AmazonGameLiftException If the server failed to response.
   */
  void stopMatchBackfill(StopMatchBackfillRequest stopMatchBackfillRequest);

  /**
   * Get the expected termination time of the host.
   *
   * @return Seconds since epoch, or 0 if termination is not scheduled.
   */
  long getTerminationTime();

  /** Disconnect from all network connections. */
  void destroy();
}
