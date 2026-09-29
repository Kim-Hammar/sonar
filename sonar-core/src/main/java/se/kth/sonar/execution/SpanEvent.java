package se.kth.sonar.execution;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** A Java Flight Recorder event that times one stage of a repair run. */
@Name("se.kth.sonar.Span")
@Label("Sonar Span")
@Category("Sonar")
@Description("One stage of a repair run")
@StackTrace(false)
public final class SpanEvent extends Event {
  /** A stage of the pipeline. */
  public enum Stage {
    PARSE,
    GENERATE,
    ADMIT,
    VERIFY,
    DECIDE,
    ADOPT
  }

  @Label("Stage")
  String stage;

  @Label("Scenario")
  String scenario;

  @Label("Items")
  int items;

  private SpanEvent(Stage stage, String scenario) {
    this.stage = stage.name();
    this.scenario = scenario;
  }

  /** Starts timing {@code stage} for the run bound to the current thread. */
  public static SpanEvent start(Stage stage) {
    SpanEvent event = new SpanEvent(stage, RunScope.current().map(RunScope::scenario).orElse(""));
    event.begin();
    return event;
  }

  /** Stops timing, records that the stage processed {@code count} items, and commits the event. */
  public void finish(int count) {
    items = count;
    commit();
  }
}
