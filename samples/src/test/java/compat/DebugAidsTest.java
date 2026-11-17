package compat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Developer flags: -Dindyproto.dump and field-number line numbers in spun code. */
class DebugAidsTest {

  /** Run in a fresh JVM: fails inside the spun writeTo, while writing field 2. */
  public static void main(String[] args) throws Exception {
    indy.shapes.Point p = indy.shapes.Point.newBuilder().setY(300).build();
    try {
      p.writeTo(CodedOutputStream.newInstance(new byte[1]));
    } catch (CodedOutputStream.OutOfSpaceException e) {
      e.printStackTrace(System.out);
    }
  }

  @Test
  void dumpAndLineNumbers(@TempDir Path tmp) throws Exception {
    Path dump = tmp.resolve("dump");
    Process proc = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"),
        "-Dindyproto.strategy=hidden", "-Dindyproto.dump=" + dump,
        "-XX:+UnlockDiagnosticVMOptions", "-XX:+ShowHiddenFrames",
        DebugAidsTest.class.getName())
        .redirectErrorStream(true).start();
    String out = new String(proc.getInputStream().readAllBytes());
    assertEquals(0, proc.waitFor(), out);

    // the stack trace names the message and the proto field number being written
    assertTrue(out.contains("OutOfSpaceException"), out);
    assertTrue(out.contains("indy.shapes.Point$$Indy$Ops"), out);
    assertTrue(out.contains(".writeTo(Point field:2)"), out);

    // the spun classes are on disk, parseable, with the same SourceFile
    Path ops = dump.resolve("indy/shapes/Point$$Indy$Ops.class");
    assertTrue(Files.exists(ops), () -> list(dump).toString());
    ClassModel cm = ClassFile.of().parse(ops);
    assertEquals("Point field", cm.findAttribute(Attributes.sourceFile()).orElseThrow().sourceFile().stringValue());
    assertTrue(cm.methods().stream().anyMatch(m -> m.methodName().equalsString("writeTo")));
  }

  private static List<Path> list(Path dir) {
    try (Stream<Path> s = Files.walk(dir)) {
      return s.toList();
    } catch (java.io.IOException e) {
      return List.of();
    }
  }
}
