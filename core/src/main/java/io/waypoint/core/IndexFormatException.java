package io.waypoint.core;

import java.io.IOException;

/**
 * Thrown when a file is not a readable Waypoint index.
 *
 * <p>Extends {@link IOException} rather than {@code RuntimeException} because a
 * corrupt file on disk is an expected I/O condition, not a programming error.
 */
public final class IndexFormatException extends IOException {

  private static final long serialVersionUID = 1L;

  public IndexFormatException(String message) {
    super(message);
  }
}
