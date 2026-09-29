package se.kth.sonar.jev;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JevJsonTest {
  record Question(String title, int choices, Map<String, Integer> weights) {}

  @Test
  void writesPropertiesAndMapEntriesInSortedOrder() throws JsonProcessingException {
    Map<String, Integer> weights = new LinkedHashMap<>();
    weights.put("b", 2);
    weights.put("a", 1);
    assertThat(JevJson.mapper().writeValueAsString(new Question("x", 2, weights)))
        .isEqualTo("{\"choices\":2,\"title\":\"x\",\"weights\":{\"a\":1,\"b\":2}}");
  }

  @Test
  void ignoresUnknownPropertiesInReplies() throws JsonProcessingException {
    Question question =
        JevJson.mapper()
            .readValue(
                "{\"title\":\"x\",\"choices\":1,\"weights\":{},\"extra\":true}", Question.class);
    assertThat(question).isEqualTo(new Question("x", 1, Map.of()));
  }
}
