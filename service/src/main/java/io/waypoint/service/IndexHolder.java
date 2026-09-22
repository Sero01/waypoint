package io.waypoint.service;

import io.waypoint.core.Index;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Opens the index once at startup and holds it for the life of the process.
 *
 * <p>A missing index is not a startup failure. Index building is an offline job
 * in this design -- the file is written once and frozen -- so a service that
 * refused to start before its input existed would just be an awkward ordering
 * constraint. Instead the service starts, reports the situation as 503, and
 * says which file it was looking for.
 */
@Component
public class IndexHolder {

  private final Index index;
  private final String failure;

  public IndexHolder(WaypointProperties properties) throws IOException {
    String configured = properties.index();
    if (configured == null || configured.isBlank()) {
      this.index = null;
      this.failure = "no index configured: set waypoint.index";
      return;
    }
    Path path = Path.of(configured);
    if (!Files.isRegularFile(path)) {
      this.index = null;
      this.failure = "index not built yet: " + path.toAbsolutePath();
      return;
    }
    Index opened = Index.open(path);
    if (properties.verifyOrDefault()) {
      opened.verify();
    }
    this.index = opened;
    this.failure = null;
  }

  /** The open index, or null when {@link #failure()} explains why there is none. */
  public Index index() {
    return index;
  }

  public String failure() {
    return failure;
  }

  @PreDestroy
  void close() {
    if (index != null) {
      index.close();
    }
  }
}
