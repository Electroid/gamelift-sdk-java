package com.amazonaws.services.gamelift.model;

import java.io.Serializable;
import java.util.Objects;

/** A request to stop a {@link StartMatchBackfillRequest}. */
public class StopMatchBackfillRequest implements Serializable, Cloneable {

  private String ticketId;
  private String configurationName;
  private String gameSessionArn;

  /**
   * Get the ticket id.
   *
   * @return The ticket id.
   */
  public String getTicketId() {
    return ticketId;
  }

  public void setTicketId(String ticketId) {
    this.ticketId = ticketId;
  }

  public StopMatchBackfillRequest withTicketId(String ticketId) {
    setTicketId(ticketId);
    return this;
  }

  /**
   * Get the configuration name.
   *
   * @return The configuration name.
   */
  public String getConfigurationName() {
    return configurationName;
  }

  public void setConfigurationName(String configurationName) {
    this.configurationName = configurationName;
  }

  public StopMatchBackfillRequest withConfigurationName(String configurationName) {
    setConfigurationName(configurationName);
    return this;
  }

  /**
   * Get the game session arn.
   *
   * @return The game session arn.
   */
  public String getGameSessionArn() {
    return gameSessionArn;
  }

  public void setGameSessionArn(String gameSessionArn) {
    this.gameSessionArn = gameSessionArn;
  }

  public StopMatchBackfillRequest withGameSessionArn(String gameSessionArn) {
    setGameSessionArn(gameSessionArn);
    return this;
  }

  @Override
  public int hashCode() {
    return Objects.hash(ticketId, configurationName, gameSessionArn);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof StopMatchBackfillRequest)) return false;
    StopMatchBackfillRequest o = (StopMatchBackfillRequest) obj;
    return Objects.equals(getTicketId(), o.getTicketId())
        && Objects.equals(getConfigurationName(), o.getConfigurationName())
        && Objects.equals(getGameSessionArn(), o.getGameSessionArn());
  }

  @Override
  public String toString() {
    return String.format(
        "{TicketId: %s, ConfigurationName: %s, GameSessionArn: %s}",
        getTicketId(), getConfigurationName(), getGameSessionArn());
  }

  @Override
  public StopMatchBackfillRequest clone() {
    try {
      return (StopMatchBackfillRequest) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new IllegalStateException(e);
    }
  }
}
