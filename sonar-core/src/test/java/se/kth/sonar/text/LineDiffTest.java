package se.kth.sonar.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LineDiffTest {
  private static final RouterId R1 = RouterId.of("r1");

  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of("a\nb\nc\n", "a\nB\nc\n", 1, "replace"),
        Arguments.of("a\nb\nc\n", "a\nb\nx\ny\nc\n", 1, "insert"),
        Arguments.of("a\nb\nc\nd\n", "a\nd\n", 1, "delete"),
        Arguments.of("a\nb\n", "a\nb\nc\n", 1, "append"),
        Arguments.of("a\nb\n", "z\na\nb\n", 1, "prepend"),
        Arguments.of("a\nb\nc\nd\ne\n", "a\nB\nc\nd\nE\nf\n", 2, "two hunks"),
        Arguments.of("a\nb\n", "a\nb\n", 0, "nothing"));
  }

  @ParameterizedTest(name = "{3}")
  @MethodSource("cases")
  void editsTurnTheTextIntoTheOther(String before, String after, int hunks, String name) {
    ConfigText from = ConfigText.of(before);
    List<LineEdit> edits = LineDiff.between(R1, from, ConfigText.of(after));
    assertThat(edits).hasSize(hunks);
    assertThat(from.apply(edits).text()).isEqualTo(after);
  }
}
