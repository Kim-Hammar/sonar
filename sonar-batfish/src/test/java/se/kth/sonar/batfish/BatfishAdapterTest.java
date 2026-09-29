package se.kth.sonar.batfish;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.relation.Dialect;

class BatfishAdapterTest {
  @Test
  void embedsTheBenchmarkBatfishBuild() {
    assertThat(BatfishInfo.version()).isEqualTo("0.36.0");
  }

  @Test
  void theProgramFactoryComputesEveryDerivedAttributeOfTheCiscoDialect() {
    Dialect dialect = Dialect.load("cisco-ios");
    assertThat(new BatfishProgramFactory().functions())
        .containsAll(dialect.schema().derivedFunctions());
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      assertThat(new ProgramService(dialect, new BatfishProgramFactory(), context)).isNotNull();
    }
  }
}
