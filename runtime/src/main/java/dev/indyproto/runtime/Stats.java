package dev.indyproto.runtime;

import java.util.concurrent.atomic.AtomicLong;

/** Link-time cost accounting, printed at exit with {@code -Dindyproto.stats=true}. */
final class Stats {
  private Stats() {}

  static final boolean ENABLED = Boolean.getBoolean("indyproto.stats");
  private static final AtomicLong bootstraps = new AtomicLong(), bootstrapNs = new AtomicLong();
  private static final AtomicLong spins = new AtomicLong(), buildNs = new AtomicLong(),
      defineNs = new AtomicLong(), bytes = new AtomicLong();

  static {
    if (ENABLED) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.printf(
          "[indyproto] indy bootstraps=%d total=%.1fms | hidden classes=%d bytes=%d classfile-build=%.1fms define=%.1fms%n",
          bootstraps.get(), bootstrapNs.get() / 1e6, spins.get(), bytes.get(),
          buildNs.get() / 1e6, defineNs.get() / 1e6)));
    }
  }

  static void bootstrap(long ns) {
    if (!ENABLED) return;
    bootstraps.incrementAndGet();
    bootstrapNs.addAndGet(ns);
  }

  static void spin(long build, long define, int size) {
    if (!ENABLED) return;
    spins.incrementAndGet();
    buildNs.addAndGet(build);
    defineNs.addAndGet(define);
    bytes.addAndGet(size);
  }
}
