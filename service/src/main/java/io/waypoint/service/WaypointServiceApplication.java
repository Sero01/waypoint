package io.waypoint.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * A small REST front end over {@code waypoint-core}.
 *
 * <p>Note what this module is <em>not</em> for. It is not where the cold-start
 * claim is measured -- that is the CLI, because a Spring Boot application's
 * startup is dominated by Spring, not by the search engine, and measuring one
 * through the other would say nothing about either. This exists because a
 * static index behind an HTTP endpoint is how the engine would actually be
 * deployed, and because an engine nobody can call is hard to evaluate.
 *
 * <p>The index is opened once at startup and shared across every request:
 * {@link io.waypoint.core.Index} is immutable after construction and safe for
 * concurrent searching, and the whole point of the design is that reopening it
 * per request would be pointless when opening it is nearly free anyway.
 */
@SpringBootApplication
@EnableConfigurationProperties(WaypointProperties.class)
public class WaypointServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(WaypointServiceApplication.class, args);
  }
}
