package se.kth.sonar.jev;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The JSON mapper of the Jev adapter. It writes properties and map entries in sorted order, so
 * equal requests serialise to equal bytes and share a cache key, and it ignores properties of
 * replies that it does not know.
 */
public final class JevJson {
  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private JevJson() {}

  /** Returns the shared mapper, which is safe to use from several threads. */
  public static JsonMapper mapper() {
    return MAPPER;
  }
}
