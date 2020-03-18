package com.amazonaws.services.gamelift;

public class AmazonGameLiftServerException extends RuntimeException {

  AmazonGameLiftServerException(String message, Throwable cause) {
    super(message, cause);
  }

  AmazonGameLiftServerException(String message) {
    this(message, null);
  }
}
