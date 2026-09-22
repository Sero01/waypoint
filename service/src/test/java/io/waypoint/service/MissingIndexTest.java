package io.waypoint.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A service pointed at an index that has not been built yet must start anyway
 * and report the situation as retryable, naming the file it wanted. Index
 * building is an offline job here, so refusing to start would only impose an
 * ordering constraint on deployment.
 */
@SpringBootTest(properties = "waypoint.index=target/definitely-not-built.wpt")
@AutoConfigureMockMvc
class MissingIndexTest {

  @Autowired private MockMvc mvc;

  @Test
  void reportsAMissingIndexAsUnavailableAndSaysWhichFile() throws Exception {
    mvc.perform(get("/search").param("q", "anything"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("definitely-not-built.wpt")));
    mvc.perform(get("/health")).andExpect(status().isServiceUnavailable());
    mvc.perform(get("/stats")).andExpect(status().isServiceUnavailable());
  }
}
