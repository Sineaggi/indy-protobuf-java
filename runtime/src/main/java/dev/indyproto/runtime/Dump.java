package dev.indyproto.runtime;

import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Developer diagnostics, not for production: {@code -Dindyproto.dump=DIR} writes every spun
 * hidden class to {@code DIR/<binary name>.class} before it is defined, so a class that fails
 * verification is on disk too. Read them with {@code javap -c -p} or a decompiler. Like the JDK's
 * own dump flags, this is meant for diagnosing the runtime, not as an end-user feature.
 */
final class Dump {
  private Dump() {}

  static final Path DIR = dir();

  private static Path dir() {
    String d = System.getProperty("indyproto.dump");
    return d == null || d.isBlank() ? null : Path.of(d).toAbsolutePath();
  }

  static void write(ClassDesc self, byte[] bytes) {
    if (DIR == null) return;
    Path p = DIR.resolve(self.descriptorString().substring(1, self.descriptorString().length() - 1) + ".class");
    try {
      Files.createDirectories(p.getParent());
      Files.write(p, bytes);
      if (ProtoBootstraps.TRACE) System.err.println("[indyproto] dumped " + p);
    } catch (IOException e) {
      // diagnostics must never break linking
      System.err.println("[indyproto] could not dump " + p + ": " + e);
    }
  }
}
