package compat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** protoc driving protoc-gen-indy: jar output, options, and the errors protoc reports. */
class PluginTest {
  static final Path PROTO_DIR = Path.of(System.getProperty("indyproto.protoDir"));
  static final Path INDY = Path.of(System.getProperty("indyproto.indyClasses"));

  record Run(int exit, String stderr) {}

  static Run protoc(Path cwd, String... args) throws Exception {
    List<String> cmd = new ArrayList<>(List.of(System.getProperty("indyproto.protoc"),
        "--plugin=protoc-gen-indy=" + System.getProperty("indyproto.plugin")));
    cmd.addAll(List.of(args));
    Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    String err = new String(p.getErrorStream().readAllBytes());
    return new Run(p.waitFor(), err);
  }

  static TreeMap<String, byte[]> jar(Path jar) throws IOException {
    TreeMap<String, byte[]> out = new TreeMap<>();
    try (var in = new ZipInputStream(Files.newInputStream(jar))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        if (e.getName().endsWith(".class")) out.put(e.getName(), in.readAllBytes());
      }
    }
    return out;
  }

  @Test
  void jarOutputMatchesTheBuild(@TempDir Path tmp) throws Exception {
    Path jar = tmp.resolve("shapes-indy.jar");
    Run r = protoc(tmp, "-I", PROTO_DIR.toString(), "--indy_opt=rewrite=shapes=indy.shapes",
        "--indy_out=" + jar, "full2.proto", "ext2.proto");
    assertEquals(0, r.exit(), r.stderr());
    var classes = jar(jar);
    assertTrue(classes.containsKey("indy/shapes/ext/Ext2$Set.class"), classes.keySet().toString());
    assertTrue(classes.containsKey("indy/shapes/full/Full2.class"));
    // only the requested files: nothing from imports that were not listed
    assertTrue(classes.keySet().stream().allMatch(n -> n.startsWith("indy/shapes/full/") || n.startsWith("indy/shapes/ext/")),
        classes.keySet().toString());
    for (var e : classes.entrySet()) {
      assertArrayEquals(Files.readAllBytes(INDY.resolve(e.getKey())), e.getValue(), e.getKey());
    }
  }

  @Test
  void importsAreNotGenerated(@TempDir Path tmp) throws Exception {
    Run r = protoc(tmp, "-I", PROTO_DIR.toString(), "--indy_out=" + tmp, "ext2.proto");
    assertEquals(0, r.exit(), r.stderr());
    assertTrue(Files.exists(tmp.resolve("shapes/ext/Ext2.class")));
    assertFalse(Files.exists(tmp.resolve("shapes/full/Full2.class")));
  }

  @Test
  void unknownOptionIsAProtocError(@TempDir Path tmp) throws Exception {
    Run r = protoc(tmp, "-I", PROTO_DIR.toString(), "--indy_opt=lite", "--indy_out=" + tmp, "sample.proto");
    assertNotEquals(0, r.exit());
    assertTrue(r.stderr().contains("unknown --indy_opt 'lite'"), r.stderr());
  }

  @Test
  void editionsAreRefusedByProtoc(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("ed.proto"), "edition = \"2023\";\npackage ed;\nmessage E { int32 a = 1; }\n");
    Run r = protoc(tmp, "-I", tmp.toString(), "--indy_out=" + tmp, "ed.proto");
    assertNotEquals(0, r.exit());
    assertTrue(r.stderr().contains("edition"), r.stderr());
  }

  @Test
  void messageSetWithFieldsIsAProtocError(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("ms.proto"), """
        syntax = "proto2";
        package ms;
        message S { option message_set_wire_format = true; optional int32 a = 1; extensions 4 to max; }
        """);
    Run r = protoc(tmp, "-I", tmp.toString(), "--indy_out=" + tmp, "ms.proto");
    assertNotEquals(0, r.exit());
  }
}
