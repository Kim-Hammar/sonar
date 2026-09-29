package se.kth.sonar.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import se.kth.sonar.trace.TraceRecord;
import se.kth.sonar.trace.TraceSink;

/**
 * The persistent record of one run: {@code run.json} with its settings and status, {@code
 * events.jsonl} with one summary line per event, and {@code details.jsonl} with the matching detail
 * line.
 *
 * <p>Appends from concurrently running scenarios are serialised by one lock, which assigns the
 * run-wide sequence number and the scenario's step, writes the detail before the summary, and wakes
 * waiting readers. Readers find lines through an in-memory index of byte ranges and read them with
 * positional reads. Reopening a log cuts it at the first incomplete or unreadable line, so a crash
 * loses at most the event being written.
 */
public final class RunLog implements TraceSink, AutoCloseable {
  static final String DOCUMENT = "run.json";
  static final String EVENTS = "events.jsonl";
  static final String DETAILS = "details.jsonl";
  private static final byte NEWLINE = '\n';
  private static final int RANGE_FIELDS = 4;

  private final Path directory;
  private final FileChannel events;
  private final FileChannel details;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private final Map<String, Long> steps = new HashMap<>();
  private final TraceJson json = new TraceJson();
  private long[] ranges = new long[RANGE_FIELDS * 64];
  private int size;
  private long eventsEnd;
  private long detailsEnd;
  private RunDocument document;
  private boolean sealed;
  private boolean closed;
  private IOException failure;

  private RunLog(Path directory, RunDocument document, FileChannel events, FileChannel details) {
    this.directory = directory;
    this.document = document;
    this.events = events;
    this.details = details;
  }

  /** Creates the log of a new run in the empty or missing directory {@code directory}. */
  public static RunLog create(Path directory, RunDocument document) throws IOException {
    Files.createDirectories(directory);
    writeDocument(directory, document);
    FileChannel events = open(directory.resolve(EVENTS), StandardOpenOption.CREATE_NEW);
    FileChannel details = open(directory.resolve(DETAILS), StandardOpenOption.CREATE_NEW);
    return new RunLog(directory, document, events, details);
  }

  /**
   * Opens the log in {@code directory}, rebuilding its index and cutting off an incomplete or
   * unreadable tail.
   */
  public static RunLog open(Path directory) throws IOException {
    RunDocument document = readDocument(directory);
    FileChannel events = open(directory.resolve(EVENTS), StandardOpenOption.CREATE);
    FileChannel details = open(directory.resolve(DETAILS), StandardOpenOption.CREATE);
    RunLog log = new RunLog(directory, document, events, details);
    log.rebuild();
    log.sealed = document.runStatus().terminal();
    return log;
  }

  /** Reads the {@code run.json} of the run in {@code directory}. */
  public static RunDocument readDocument(Path directory) throws IOException {
    return Json.mapper()
        .readValue(Files.readAllBytes(directory.resolve(DOCUMENT)), RunDocument.class);
  }

  /** Returns the run's directory. */
  public Path directory() {
    return directory;
  }

  /** Returns the run's current document. */
  public RunDocument document() {
    lock.lock();
    try {
      return document;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Changes the run's document and writes it atomically. A terminal status seals the log, which
   * wakes every waiting reader.
   */
  public RunDocument update(UnaryOperator<RunDocument> change) throws IOException {
    lock.lock();
    try {
      RunDocument next = change.apply(document);
      writeDocument(directory, next);
      document = next;
      if (next.runStatus().terminal()) {
        sealed = true;
        events.force(true);
        details.force(true);
      }
      changed.signalAll();
      return next;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Appends {@code record}. A failed write is kept and reported by {@link #failure()} instead of
   * being thrown into the run that emitted the record; later records are dropped.
   */
  @Override
  public void accept(TraceRecord record) {
    lock.lock();
    try {
      if (failure != null || sealed) {
        return;
      }
      long step = steps.merge(record.scenario(), 1L, Long::sum);
      TraceJson.Encoded encoded = json.encode(size + 1L, step, record);
      byte[] detail = line(encoded.detail());
      byte[] summary = line(encoded.summary());
      write(details, detail, detailsEnd);
      write(events, summary, eventsEnd);
      addRange(eventsEnd, summary.length - 1L, detailsEnd, detail.length - 1L);
      eventsEnd += summary.length;
      detailsEnd += detail.length;
      changed.signalAll();
    } catch (IOException e) {
      failure = e;
    } finally {
      lock.unlock();
    }
  }

  /** Returns the write failure that stopped the log, if any. */
  public Optional<IOException> failure() {
    lock.lock();
    try {
      return Optional.ofNullable(failure);
    } finally {
      lock.unlock();
    }
  }

  /** Returns the number of events. */
  public long size() {
    lock.lock();
    try {
      return size;
    } finally {
      lock.unlock();
    }
  }

  /** Returns whether the run has ended, so that no event will follow. */
  public boolean sealed() {
    lock.lock();
    try {
      return sealed;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns up to {@code limit} summary lines of the events after sequence number {@code after}.
   */
  public List<String> summaries(long after, int limit) {
    long first;
    long end;
    int count;
    lock.lock();
    try {
      if (after < 0 || after >= size || limit < 1) {
        return List.of();
      }
      count = (int) Math.min(limit, size - after);
      first = ranges[(int) after * RANGE_FIELDS];
      int last = (int) after + count - 1;
      end = ranges[last * RANGE_FIELDS] + ranges[last * RANGE_FIELDS + 1];
    } finally {
      lock.unlock();
    }
    String text = read(events, first, end - first);
    List<String> lines = List.of(text.split("\n", -1));
    return lines.subList(0, count);
  }

  /** Returns the detail line of event {@code seq}. */
  public Optional<String> detail(long seq) {
    long offset;
    long length;
    lock.lock();
    try {
      if (seq < 1 || seq > size) {
        return Optional.empty();
      }
      offset = ranges[(int) (seq - 1) * RANGE_FIELDS + 2];
      length = ranges[(int) (seq - 1) * RANGE_FIELDS + 3];
    } finally {
      lock.unlock();
    }
    return Optional.of(read(details, offset, length));
  }

  /**
   * Waits up to {@code timeout} until an event after {@code seq} exists or the log is sealed, and
   * returns whether such an event exists.
   */
  public boolean awaitAfter(long seq, Duration timeout) throws InterruptedException {
    long remaining = timeout.toNanos();
    lock.lock();
    try {
      while (size <= seq && !sealed && !closed && remaining > 0) {
        remaining = changed.awaitNanos(remaining);
      }
      return size > seq;
    } finally {
      lock.unlock();
    }
  }

  /** Wakes every waiting reader without changing the log, for example before a shutdown. */
  public void wake() {
    lock.lock();
    try {
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /** Closes the files and releases every waiting reader. */
  @Override
  public void close() {
    lock.lock();
    try {
      closed = true;
      changed.signalAll();
    } finally {
      lock.unlock();
    }
    try (events;
        details) {
      events.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot close the log of " + directory, e);
    }
  }

  private void rebuild() throws IOException {
    List<long[]> summaryLines = lines(events);
    List<long[]> detailLines = lines(details);
    int count = Math.min(summaryLines.size(), detailLines.size());
    for (int index = 0; index < count; index++) {
      long[] summary = summaryLines.get(index);
      long[] detail = detailLines.get(index);
      addRange(summary[0], summary[1], detail[0], detail[1]);
      String scenario =
          Json.mapper().readTree(read(events, summary[0], summary[1])).path("scenario").asText();
      steps.merge(scenario, 1L, Long::sum);
    }
    eventsEnd =
        count == 0 ? 0 : summaryLines.get(count - 1)[0] + summaryLines.get(count - 1)[1] + 1;
    detailsEnd = count == 0 ? 0 : detailLines.get(count - 1)[0] + detailLines.get(count - 1)[1] + 1;
    events.truncate(eventsEnd);
    details.truncate(detailsEnd);
  }

  private static List<long[]> lines(FileChannel channel) throws IOException {
    long length = channel.size();
    ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(length, Integer.MAX_VALUE - 8));
    channel.read(buffer, 0);
    byte[] bytes = Arrays.copyOf(buffer.array(), buffer.position());
    List<long[]> lines = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < bytes.length; index++) {
      if (bytes[index] == NEWLINE) {
        String text = new String(bytes, start, index - start, StandardCharsets.UTF_8);
        if (!readable(text)) {
          break;
        }
        lines.add(new long[] {start, index - start});
        start = index + 1;
      }
    }
    return lines;
  }

  private static boolean readable(String text) {
    try {
      return Json.mapper().readTree(text).isObject();
    } catch (IOException e) {
      return false;
    }
  }

  private void addRange(
      long summaryOffset, long summaryLength, long detailOffset, long detailLength) {
    if ((size + 1) * RANGE_FIELDS > ranges.length) {
      ranges = Arrays.copyOf(ranges, ranges.length * 2);
    }
    int base = size * RANGE_FIELDS;
    ranges[base] = summaryOffset;
    ranges[base + 1] = summaryLength;
    ranges[base + 2] = detailOffset;
    ranges[base + 3] = detailLength;
    size++;
  }

  private static byte[] line(String text) {
    return (text + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static void write(FileChannel channel, byte[] bytes, long position) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    long at = position;
    while (buffer.hasRemaining()) {
      at += channel.write(buffer, at);
    }
  }

  private static String read(FileChannel channel, long offset, long length) {
    ByteBuffer buffer = ByteBuffer.allocate((int) length);
    try {
      long at = offset;
      while (buffer.hasRemaining()) {
        int read = channel.read(buffer, at);
        if (read < 0) {
          throw new IOException("unexpected end of " + channel);
        }
        at += read;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read a run log", e);
    }
    return new String(buffer.array(), StandardCharsets.UTF_8);
  }

  private static FileChannel open(Path file, StandardOpenOption creation) throws IOException {
    return FileChannel.open(file, creation, StandardOpenOption.READ, StandardOpenOption.WRITE);
  }

  private static void writeDocument(Path directory, RunDocument document) throws IOException {
    Path temporary = directory.resolve(DOCUMENT + ".tmp");
    Files.write(temporary, Json.mapper().writeValueAsBytes(document));
    Files.move(
        temporary,
        directory.resolve(DOCUMENT),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }
}
