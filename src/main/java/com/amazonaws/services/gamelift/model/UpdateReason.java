package com.amazonaws.services.gamelift.model;

/** A reason for a {@link GameSession} to be updated. */
public enum UpdateReason {
  MATCHMAKING_DATA_UPDATED,
  BACKFILL_FAILED,
  BACKFILL_TIMED_OUT,
  BACKFILL_CANCELLED,
  UNKNOWN
}
