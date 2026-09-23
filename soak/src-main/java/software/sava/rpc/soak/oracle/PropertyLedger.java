package software.sava.rpc.soak.oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/// The correctness ledger: per property, how many times it was evaluated, how many of those
/// passed, up to three failing examples, and — when it was deliberately not evaluated — the
/// stated reason.
///
/// Two behaviours matter more than the counting. A property with no stated skip that was never
/// evaluated is a FAIL, not a pass, so the file always carries a row for every id in
/// [Properties#ALL]. And the first failure of an id writes `findings/finding-NN/note.md`
/// immediately, from the failing thread: a run that is later killed still leaves the evidence of
/// what it found, and the note carries the guarantee text so the person triaging it is not
/// re-deriving why the assertion was allowed to exist.
public final class PropertyLedger {

  private static final int MAX_EXAMPLES = 3;

  private final Path runDir;
  private final ConcurrentHashMap<String, State> states;
  private final AtomicInteger findingNumber;

  public PropertyLedger(final Path runDir) {
    this.runDir = runDir;
    this.states = new ConcurrentHashMap<>(Properties.ALL.size() * 2);
    this.findingNumber = new AtomicInteger();
    for (final var property : Properties.ALL) {
      states.put(property.id(), new State(property));
    }
  }

  public void pass(final String id) {
    final var state = state(id);
    state.evaluated.increment();
    state.passed.increment();
  }

  /// Records a failure and, the first time this id fails, writes its finding note.
  public void fail(final String id, final String example) {
    final var state = state(id);
    state.evaluated.increment();
    state.failed.increment();
    synchronized (state) {
      if (state.examples.size() < MAX_EXAMPLES) {
        state.examples.add(example == null ? "" : example);
      }
      if (state.findingDirectory == null) {
        state.findingDirectory = writeFinding(state.property, example);
      }
    }
  }

  /// A stated skip: a live run with no controlled peer, a missing `--add-opens`, a profile too
  /// short to reach a fault minimum. The verdict treats this as a NOTE; an unstated absence is a
  /// FAIL, which is why the reason is required.
  public void notEvaluated(final String id, final String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("a skip needs a stated reason: " + id);
    }
    final var state = state(id);
    synchronized (state) {
      state.skipReason = reason;
    }
  }

  public long evaluated(final String id) {
    return state(id).evaluated.sum();
  }

  public long failed(final String id) {
    return state(id).failed.sum();
  }

  /// The ids whose grade may fail a run and which did fail, for the shutdown summary.
  public List<String> fatalFailures() {
    final var failures = new ArrayList<String>();
    for (final var property : Properties.ALL) {
      final var state = states.get(property.id());
      if (property.grade().fatal() && state.failed.sum() > 0L) {
        failures.add(property.id());
      }
    }
    return List.copyOf(failures);
  }

  /// The ids whose grade may fail a run, which were never evaluated, and which named no reason.
  /// The runner turns these into `UNEXERCISED`.
  public List<String> unexercised() {
    final var unexercised = new ArrayList<String>();
    for (final var property : Properties.ALL) {
      final var state = states.get(property.id());
      if (property.grade().fatal() && state.evaluated.sum() == 0L && state.skipReason == null) {
        unexercised.add(property.id());
      }
    }
    return List.copyOf(unexercised);
  }

  /// Writes the whole table. Called at every phase boundary and by the shutdown hook, so a
  /// killed run still has the ledger it had reached.
  public void write(final Path propertiesTsv) throws IOException {
    final var out = new StringBuilder(16384);
    out.append("id\tgrade\tevaluated\tpassed\tfailed\tskipReason\tstatement\tguarantee\texamples\n");
    for (final var property : Properties.ALL) {
      final var state = states.get(property.id());
      final String skipReason;
      final String examples;
      synchronized (state) {
        skipReason = state.skipReason == null ? "" : state.skipReason;
        examples = String.join(" | ", state.examples);
      }
      out.append(property.id()).append('\t')
          .append(property.grade().name()).append('\t')
          .append(state.evaluated.sum()).append('\t')
          .append(state.passed.sum()).append('\t')
          .append(state.failed.sum()).append('\t')
          .append(cell(skipReason)).append('\t')
          .append(cell(property.statement())).append('\t')
          .append(cell(property.guarantee())).append('\t')
          .append(cell(examples)).append('\n');
    }
    final var parent = propertiesTsv.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(propertiesTsv, out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
  }

  private State state(final String id) {
    final var state = states.get(id);
    if (state == null) {
      throw new IllegalArgumentException("unknown property id '" + id + "'; the table is fixed in "
          + Properties.class.getName());
    }
    return state;
  }

  private Path writeFinding(final Property property, final String example) {
    final int number = findingNumber.incrementAndGet();
    final var directory = runDir.resolve("findings").resolve(String.format(Locale.ROOT, "finding-%02d", number));
    final var note = new StringBuilder(2048);
    note.append("# finding-").append(String.format(Locale.ROOT, "%02d", number))
        .append(": ").append(property.id()).append('\n').append('\n');
    note.append("First observed ").append(Instant.now()).append(" on thread `")
        .append(Thread.currentThread().getName()).append("`.\n\n");
    note.append("- **Grade**: ").append(property.grade().name()).append('\n');
    note.append("- **Statement**: ").append(property.statement()).append('\n');
    note.append("- **Guarantee**: ").append(property.guarantee()).append('\n');
    note.append("- **Bound**: ").append(property.bound()).append('\n').append('\n');
    note.append("## First example\n\n```\n").append(example == null ? "" : example).append("\n```\n\n");
    note.append("## What this is not\n\n");
    note.append("This is a harness observation, not a defect report. sava is a published library:"
        + " the next step is a deterministic regression test in the owning module's own test source"
        + " set that reproduces this from the recorded seed, fault ordinal and frames - and, if the"
        + " oracle contradicts current behaviour, pinning and reporting it rather than changing what"
        + " a shipped signature returns.\n");
    try {
      Files.createDirectories(directory);
      Files.writeString(directory.resolve("note.md"), note, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    } catch (final IOException e) {
      throw new UncheckedIOException("could not write " + directory.resolve("note.md"), e);
    }
    return directory;
  }

  private static String cell(final String value) {
    if (value == null) {
      return "";
    }
    return value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0
        ? value
        : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
  }

  private static final class State {

    private final Property property;
    private final LongAdder evaluated;
    private final LongAdder passed;
    private final LongAdder failed;
    private final List<String> examples;
    private String skipReason;
    private Path findingDirectory;

    private State(final Property property) {
      this.property = property;
      this.evaluated = new LongAdder();
      this.passed = new LongAdder();
      this.failed = new LongAdder();
      this.examples = new ArrayList<>(MAX_EXAMPLES);
    }
  }
}
