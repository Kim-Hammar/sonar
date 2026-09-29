package se.kth.sonar.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

class IdentityTest {
  @Test
  void contentHashIsTheSha256OfTheText() {
    assertThat(ContentHash.of("abc").hex())
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(ContentHash.of("abc").abbreviated()).isEqualTo("ba7816bf8f01");
    assertThatThrownBy(() -> new ContentHash("xyz")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void builderKeepsFieldsApart() {
    assertThat(ContentHash.builder().add("ab").add("c").build())
        .isNotEqualTo(ContentHash.builder().add("a").add("bc").build());
    assertThat(ContentHash.builder().add("1").build())
        .isNotEqualTo(ContentHash.builder().add(1L).build());
    assertThat(ContentHash.builder().add("x").add(2L).build())
        .isEqualTo(ContentHash.builder().add("x").add(2L).build());
  }

  @Test
  void routerIdsIgnoreCaseAndFileExtension() {
    assertThat(RouterId.fromFileName("0_as65002_Linz.cfg"))
        .isEqualTo(RouterId.of("0_as65002_linz"));
    assertThat(RouterId.fromFileName("r1")).isEqualTo(new RouterId("r1"));
    assertThatThrownBy(() -> new RouterId("R1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RouterId.of("r 1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addressesAndPrefixesParseStrictly() {
    assertThat(Ipv4.parse("10.0.0.1").toString()).isEqualTo("10.0.0.1");
    assertThat(Ipv4.tryParse("10.0.0.01")).isEmpty();
    assertThat(Ipv4.tryParse("10.0.0")).isEmpty();
    assertThat(Ipv4.parse("255.255.255.0").maskLength()).hasValue(24);
    assertThat(Ipv4.parse("0.0.0.255").wildcardLength()).hasValue(24);
    Prefix prefix = Prefix.parse("10.1.0.0/16");
    assertThat(prefix.contains(Ipv4.parse("10.1.200.3"))).isTrue();
    assertThat(prefix.contains(Prefix.parse("10.1.4.0/24"))).isTrue();
    assertThat(prefix.overlaps(Prefix.parse("10.0.0.0/8"))).isTrue();
    assertThat(prefix.overlaps(Prefix.parse("10.2.0.0/16"))).isFalse();
    assertThat(Prefix.tryParse("10.1.0.1/16")).isEmpty();
    assertThatThrownBy(() -> Prefix.parse("10.0.0.0/33"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotIdDependsOnContentNotOnOrder() {
    Map<String, String> forward = new LinkedHashMap<>();
    forward.put("R1.cfg", "hostname r1\n");
    forward.put("R2.cfg", "hostname r2\n");
    Map<String, String> backward = new LinkedHashMap<>();
    backward.put("R2.cfg", "hostname r2\n");
    backward.put("R1.cfg", "hostname r1\n");
    assertThat(Snapshot.of(forward).id()).isEqualTo(Snapshot.of(backward).id());
    assertThat(Snapshot.of(Map.of("R1.cfg", "hostname r1\n")).id())
        .isNotEqualTo(Snapshot.of(Map.of("R1.cfg", "hostname x\n")).id());
    assertThatThrownBy(() -> Snapshot.of(Map.of("R1.cfg", "a", "r1.txt", "b")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void transactionIdDependsOnEditsAndBaseVersions() {
    RouterId r1 = RouterId.of("r1");
    Snapshot base = Snapshot.of(Map.of("r1.cfg", "a\nb\n"));
    Snapshot other = Snapshot.of(Map.of("r1.cfg", "a\nc\n"));
    List<LineEdit> edits = List.of(new LineEdit.Delete(SourceSpan.line(r1, 1)));
    assertThat(Transaction.of(base, edits)).isEqualTo(Transaction.of(base, edits));
    assertThat(Transaction.of(base, edits).id()).isNotEqualTo(Transaction.of(other, edits).id());
    assertThat(Transaction.of(base, edits).id())
        .isNotEqualTo(
            Transaction.of(base, List.of(new LineEdit.Delete(SourceSpan.line(r1, 2)))).id());
  }
}
