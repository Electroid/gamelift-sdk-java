package com.amazonaws.services.gamelift.model;

import java.io.Serializable;
import java.util.Objects;

/** An event when a {@link GameSession} is updated. */
public class UpdateGameSession implements Serializable, Cloneable {

  private GameSession gameSession;
  private UpdateReason updateReason;
  private String backfillTicketId;

  /**
   * Get the game session that was updated.
   *
   * @return The game session.
   */
  public GameSession getGameSession() {
    return gameSession;
  }

  public void setGameSession(GameSession gameSession) {
    this.gameSession = gameSession;
  }

  public UpdateGameSession withGameSession(GameSession gameSession) {
    setGameSession(gameSession);
    return this;
  }

  /**
   * Get the reason the game session was updated.
   *
   * @return The update reason.
   */
  public UpdateReason getUpdateReason() {
    return updateReason;
  }

  public void setUpdateReason(String updateReason) {
    this.updateReason = UpdateReason.valueOf(updateReason);
  }

  public UpdateGameSession withUpdateReason(String updateReason) {
    setUpdateReason(updateReason);
    return this;
  }

  /**
   * Get the ticket id that caused this update.
   *
   * @return The ticket id.
   */
  public String getBackfillTicketId() {
    return backfillTicketId;
  }

  public void setBackfillTicketId(String backfillTicketId) {
    this.backfillTicketId = backfillTicketId;
  }

  public UpdateGameSession withBackfillTicketId(String backfillTicketId) {
    setBackfillTicketId(backfillTicketId);
    return this;
  }

  @Override
  public int hashCode() {
    return Objects.hash(gameSession, updateReason, backfillTicketId);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof UpdateGameSession)) return false;
    UpdateGameSession o = (UpdateGameSession) obj;
    return Objects.equals(getGameSession(), o.getGameSession())
        && Objects.equals(getUpdateReason(), o.getUpdateReason())
        && Objects.equals(getBackfillTicketId(), o.getBackfillTicketId());
  }

  @Override
  public String toString() {
    return String.format(
        "{GameSession: %s, UpdateReason: %s, BackfillTicketId: %s}",
        getGameSession(), getUpdateReason(), getBackfillTicketId());
  }

  @Override
  public UpdateGameSession clone() {
    try {
      return (UpdateGameSession) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new IllegalStateException(e);
    }
  }
}
