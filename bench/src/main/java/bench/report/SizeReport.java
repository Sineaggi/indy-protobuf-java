package bench.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Class-file and jar size of protoc gencode vs indy gencode, per sample schema. */
public final class SizeReport {
  public static void main(String[] args) throws IOException {
    Path samples = Path.of(args[0]);
    Path stock = samples.resolve("classes/java/main");
    Path indy = samples.resolve("gen/indy-classes");
    System.out.printf("%-10s %8s %8s %10s %10s %10s %10s %8s %8s%n",
        "schema", "classes", "classes", "bytes", "bytes", "jar", "jar", "methods", "methods");
    System.out.printf("%-10s %8s %8s %10s %10s %10s %10s %8s %8s%n",
        "", "protoc", "indy", "protoc", "indy", "protoc", "indy", "protoc", "indy");
    for (String pkg : List.of("sample", "bench", "wide", "many")) {
      Stats s = stats(stock.resolve(pkg));
      Stats i = stats(indy.resolve("indy").resolve(pkg));
      System.out.printf("%-10s %8d %8d %10d %10d %10d %10d %8d %8d%n",
          pkg, s.classes, i.classes, s.bytes, i.bytes, s.jar, i.jar, s.methods, i.methods);
    }
  }

  record Stats(int classes, long bytes, long jar, int methods) {}

  static Stats stats(Path dir) throws IOException {
    List<Path> files;
    try (Stream<Path> w = Files.walk(dir)) {
      files = w.filter(p -> p.toString().endsWith(".class")).sorted().toList();
    }
    long bytes = 0;
    int methods = 0;
    ByteArrayOutputStream jar = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(jar)) {
      for (Path p : files) {
        byte[] b = Files.readAllBytes(p);
        bytes += b.length;
        ClassModel cm = ClassFile.of().parse(b);
        methods += cm.methods().size();
        z.putNextEntry(new ZipEntry(dir.relativize(p).toString()));
        z.write(b);
        z.closeEntry();
      }
    }
    return new Stats(files.size(), bytes, jar.size(), methods);
  }
}
