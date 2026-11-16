package bench.jmh;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Steady-state throughput. Each (impl, message) pair runs in its own fork, so call sites in the
 * protobuf runtime stay monomorphic just like in a real single-schema application.
 * The indy strategy is selected per run with -jvmArgsAppend -Dindyproto.strategy=hidden|mh.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ProtoBench {
  @Param({"stock", "indy"})
  public String impl;

  @Param({"order", "person", "wide"})
  public String message;

  private Message msg;
  private Message copy;
  private byte[] bytes;
  private Parser<? extends Message> parser;

  @Setup
  public void setup() throws Exception {
    bytes = Messages.bytes(message);
    msg = Messages.parse(impl, message, bytes);
    copy = Messages.parse(impl, message, bytes);
    parser = msg.getParserForType();
    if (!msg.equals(copy)) throw new IllegalStateException();
  }

  @Benchmark
  public byte[] serialize() {
    return msg.toByteArray();
  }

  @Benchmark
  public Message parse() throws Exception {
    return parser.parseFrom(bytes);
  }

  @Benchmark
  public boolean equalsCopy() {
    return msg.equals(copy);
  }

  @Benchmark
  public Message toBuilderBuild() {
    return msg.toBuilder().build();
  }

  /** parse + hashCode on the fresh instance (hashCode is memoized, so it needs a new object). */
  @Benchmark
  public int parseAndHash() throws Exception {
    return parser.parseFrom(bytes).hashCode();
  }

  /** getSerializedSize on a fresh (unmemoized) copy: measures size computation + toBuilder/build. */
  @Benchmark
  public int rebuildAndSize() {
    return msg.toBuilder().build().getSerializedSize();
  }
}
