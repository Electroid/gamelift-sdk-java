package com.amazonaws.services.gamelift.model;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Parameters and callbacks for a GameLift server.
 *
 * <p>{@link #getPort()} and {@link #getLogPathsToUpload()} are sent to the GameLift server on
 * startup.
 *
 * <p>All callbacks, such as {@link #getHealthCheck()}, are invoked locally on an async thread in
 * response to GameLift server events.
 */
public class ProcessParameters implements Serializable, Cloneable {

  private int port;
  private Collection<String> logPathsToUpload;

  private Supplier<Boolean> onHealthCheck;
  private Consumer<GameSession> onGameSessionStart;
  private Consumer<UpdateGameSession> onGameSessionUpdate;
  private Runnable onProcessTerminate;

  public ProcessParameters() {
    this(0, Collections.emptyList());
  }

  public ProcessParameters(int port, Collection<String> logPathsToUpload) {
    this.port = port;
    this.logPathsToUpload = logPathsToUpload;
    this.onHealthCheck = () -> true;
    this.onGameSessionStart = session -> {};
    this.onGameSessionUpdate = session -> {};
    this.onProcessTerminate = () -> {};
  }

  /**
   * Get the port for clients to connect.
   *
   * @return The game port.
   */
  public int getPort() {
    return port;
  }

  public ProcessParameters withPort(int port) {
    this.port = port;
    return this;
  }

  /**
   * Get the collection of log file paths to upload.
   *
   * @return A collection of file paths.
   */
  public Collection<String> getLogPathsToUpload() {
    return logPathsToUpload;
  }

  public ProcessParameters withLogPathsToUpload(Collection<String> logPathsToUpload) {
    this.logPathsToUpload = logPathsToUpload;
    return this;
  }

  /**
   * Get the supplier for health checks.
   *
   * @return A boolean supplier.
   */
  public Supplier<Boolean> getHealthCheck() {
    return onHealthCheck;
  }

  public ProcessParameters withHealthCheck(Supplier<Boolean> onHealthCheck) {
    this.onHealthCheck = onHealthCheck;
    return this;
  }

  /**
   * Get the callback when a game session is assigned.
   *
   * @return A game session callback.
   */
  public Consumer<GameSession> getGameSessionStart() {
    return onGameSessionStart;
  }

  public ProcessParameters withGameSessionStart(Consumer<GameSession> onGameSessionStart) {
    this.onGameSessionStart = onGameSessionStart;
    return this;
  }

  /**
   * Get the callback when a game session is updated.
   *
   * @return An update game session callback.
   */
  public Consumer<UpdateGameSession> getGameSessionUpdate() {
    return onGameSessionUpdate;
  }

  public ProcessParameters withGameSessionUpdate(Consumer<UpdateGameSession> onGameSessionUpdate) {
    this.onGameSessionUpdate = onGameSessionUpdate;
    return this;
  }

  /**
   * Get the callback when the process is terminated.
   *
   * @return A process terminate callback.
   */
  public Runnable getProcessTerminate() {
    return onProcessTerminate;
  }

  public ProcessParameters withProcessTerminate(Runnable onProcessTerminate) {
    this.onProcessTerminate = onProcessTerminate;
    return this;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        port,
        logPathsToUpload,
        onHealthCheck,
        onGameSessionStart,
        onGameSessionUpdate,
        onProcessTerminate);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ProcessParameters)) return false;
    ProcessParameters o = (ProcessParameters) obj;
    return getPort() == o.getPort()
        && Objects.equals(getLogPathsToUpload(), o.getLogPathsToUpload())
        && Objects.equals(getHealthCheck(), o.getHealthCheck())
        && Objects.equals(getGameSessionStart(), o.getGameSessionStart())
        && Objects.equals(getGameSessionUpdate(), o.getGameSessionUpdate())
        && Objects.equals(getProcessTerminate(), o.getProcessTerminate());
  }

  @Override
  public String toString() {
    return String.format("{Port: %d, LogPathsToUpload: %s}", getPort(), getLogPathsToUpload());
  }

  @Override
  public ProcessParameters clone() {
    try {
      return (ProcessParameters) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new IllegalStateException(e);
    }
  }
}
