package bench.startup;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;

/**
 * Startup / first-call cost. Usage: Startup (stock|indy) N
 * Touches the first N messages of the 400-message schema once (build, serialize, parse, equals,
 * hashCode) and prints one line of key=value metrics.
 */
public final class Startup {
  public static void main(String[] args) throws Exception {
    String impl = args[0];
    int n = Integer.parseInt(args[1]);
    long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
    long t0 = System.nanoTime();
    long acc = impl.equals("stock") ? TouchStock.touch(n) : TouchIndy.touch(n);
    long t1 = System.nanoTime();
    // first call of a second operation on an already-linked type
    long t2 = System.nanoTime();
    acc += impl.equals("stock") ? TouchStock.touch(1) : TouchIndy.touch(1);
    long t3 = System.nanoTime();
    System.gc();
    long meta = 0, ccs = 0;
    for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
      if (p.getName().equals("Metaspace")) meta = p.getUsage().getUsed();
      if (p.getName().equals("Compressed Class Space")) ccs = p.getUsage().getUsed();
    }
    ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
    long uptime = System.currentTimeMillis() - jvmStart;
    System.out.printf("impl=%s n=%d touch_ms=%.2f again_us=%.1f metaspace_kb=%d ccs_kb=%d classes=%d uptime_ms=%d acc=%d%n",
        impl, n, (t1 - t0) / 1e6, (t3 - t2) / 1e3, meta / 1024, ccs / 1024, cl.getLoadedClassCount(), uptime, acc);
  }
}
