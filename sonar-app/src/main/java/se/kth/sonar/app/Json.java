package se.kth.sonar.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The JSON mapper of run logs and of the web API. It is configured explicitly and never discovers
 * modules, because the Batfish jar registers modules that would change how values are written.
 */
public final class Json {
  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .defaultPropertyInclusion(
              JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
          .build();

  private Json() {}

  /** Returns the shared mapper, which is safe to use from several threads. */
  public static JsonMapper mapper() {
    return MAPPER;
  }
}
