package io.waypoint.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.waypoint.core.IndexBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end HTTP behaviour, including the error mappings. */
@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerTest {

  private static Path indexPath;

  @BeforeAll
  static void buildFixtureIndex() throws IOException {
    Path dir = Files.createTempDirectory("waypoint-service-test");
    indexPath = dir.resolve("fixture.wpt");
    try (IndexBuilder b = IndexBuilder.create(indexPath, true)) {
      b.addText("alpha-doc", "the quick brown fox jumps over the lazy dog");
      b.addText("beta-doc", "a quick brown dog outpaces a quick fox");
      b.addText("gamma-doc", "lorem ipsum dolor sit amet");
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("waypoint.index", () -> indexPath.toString());
    registry.add("waypoint.max-k", () -> 50);
  }

  @Autowired private MockMvc mvc;

  @Test
  void searchesAndRanksByScore() throws Exception {
    mvc.perform(get("/search").param("q", "quick").param("k", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(2))
        .andExpect(jsonPath("$.operator").value("or"))
        .andExpect(jsonPath("$.hits[0].id").value("beta-doc"))
        .andExpect(jsonPath("$.hits.length()").value(2));
  }

  @Test
  void conjunctionNarrowsTheResult() throws Exception {
    mvc.perform(get("/search").param("q", "quick lorem").param("op", "and"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(0));

    mvc.perform(get("/search").param("q", "quick lorem").param("op", "or"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(3));
  }

  @Test
  void anUnknownTermIsAnEmptyResultNotAnError() throws Exception {
    mvc.perform(get("/search").param("q", "zzznotaterm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(0))
        .andExpect(jsonPath("$.hits.length()").value(0));
  }

  @Test
  void rejectsAQueryWithNothingIndexableInIt() throws Exception {
    mvc.perform(get("/search").param("q", "  !!! ??? "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("query contains no indexable terms"));
  }

  @Test
  void rejectsAnOutOfRangeK() throws Exception {
    mvc.perform(get("/search").param("q", "quick").param("k", "0"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/search").param("q", "quick").param("k", "51"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsAnUnknownOperator() throws Exception {
    mvc.perform(get("/search").param("q", "quick").param("op", "xor"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("op must be 'and', 'or' or 'phrase', got 'xor'"));
  }

  /**
   * The phrase has to be adjacent and in order. "quick brown" appears in both
   * documents that contain both words; "brown quick" appears in neither, which
   * is what separates a phrase from a conjunction.
   */
  @Test
  void phraseRequiresAdjacentTermsInOrder() throws Exception {
    mvc.perform(get("/search").param("q", "quick brown").param("op", "phrase"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operator").value("phrase"))
        .andExpect(jsonPath("$.totalHits").value(2));

    mvc.perform(get("/search").param("q", "brown quick").param("op", "phrase"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(0));

    // The same two words as a conjunction match regardless of order.
    mvc.perform(get("/search").param("q", "brown quick").param("op", "and"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalHits").value(2));
  }

  @Test
  void reportsStatistics() throws Exception {
    mvc.perform(get("/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documents").value(3))
        .andExpect(jsonPath("$.tokens").value(22));
  }

  @Test
  void isHealthyWhenAnIndexIsOpen() throws Exception {
    mvc.perform(get("/health")).andExpect(status().isOk());
  }
}
