package io.waypoint.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the service.
 *
 * @param index path to the .wpt index file; the service starts without it and
 *     reports 503 until it appears, because an index is built by a separate
 *     offline job and may legitimately not exist yet
 * @param maxK largest number of results a caller may ask for
 * @param verifyOnStartup recompute the CRC32 when the index is opened. Off by
 *     default, matching the engine's open-time behaviour; turn it on where a
 *     slower start is worth knowing the file is intact
 */
@ConfigurationProperties(prefix = "waypoint")
public record WaypointProperties(String index, Integer maxK, Boolean verifyOnStartup) {

  public int maxKOrDefault() {
    return maxK == null ? 100 : maxK;
  }

  public boolean verifyOrDefault() {
    return verifyOnStartup != null && verifyOnStartup;
  }
}
