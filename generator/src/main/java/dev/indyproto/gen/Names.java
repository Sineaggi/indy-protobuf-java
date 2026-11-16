package dev.indyproto.gen;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.lang.constant.ClassDesc;
import java.util.HashMap;
import java.util.Map;

/** Java naming rules, following protoc's java generator (name_resolver.cc / helpers.cc). */
final class Names {
  private final Map<String, String> packageRewrites;
  private final Map<FileDescriptor, String> outerNames = new HashMap<>();

  Names(Map<String, String> packageRewrites) {
    this.packageRewrites = packageRewrites;
  }

  /** protoc's UnderscoresToCamelCase. */
  static String camel(String input, boolean capFirst) {
    StringBuilder out = new StringBuilder();
    boolean capNext = capFirst;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c >= 'a' && c <= 'z') {
        out.append(capNext ? Character.toUpperCase(c) : c);
        capNext = false;
      } else if (c >= 'A' && c <= 'Z') {
        out.append(i == 0 && !capFirst ? Character.toLowerCase(c) : c);
        capNext = false;
      } else if (c >= '0' && c <= '9') {
        out.append(c);
        capNext = true;
      } else {
        capNext = true;
      }
    }
    return out.toString();
  }

  boolean isWellKnown(FileDescriptor fd) {
    return fd.getPackage().equals("google.protobuf")
        && fd.getOptions().getJavaPackage().equals("com.google.protobuf");
  }

  String javaPackage(FileDescriptor fd) {
    String pkg = fd.getOptions().hasJavaPackage() ? fd.getOptions().getJavaPackage() : fd.getPackage();
    if (isWellKnown(fd)) return pkg;
    for (var e : packageRewrites.entrySet()) {
      if (pkg.equals(e.getKey()) || pkg.startsWith(e.getKey() + ".")) {
        return e.getValue() + pkg.substring(e.getKey().length());
      }
    }
    return pkg;
  }

  String outerClassSimpleName(FileDescriptor fd) {
    return outerNames.computeIfAbsent(fd, f -> {
      if (f.getOptions().hasJavaOuterClassname()) return f.getOptions().getJavaOuterClassname();
      String base = f.getName();
      int slash = base.lastIndexOf('/');
      if (slash >= 0) base = base.substring(slash + 1);
      if (base.endsWith(".proto")) base = base.substring(0, base.length() - 6);
      String name = camel(base, true);
      boolean conflict = f.getMessageTypes().stream().anyMatch(m -> m.getName().equals(name))
          || f.getEnumTypes().stream().anyMatch(e -> e.getName().equals(name))
          || f.getServices().stream().anyMatch(s -> s.getName().equals(name));
      return conflict ? name + "OuterClass" : name;
    });
  }

  ClassDesc outerClass(FileDescriptor fd) {
    String pkg = javaPackage(fd);
    return ClassDesc.of(pkg.isEmpty() ? outerClassSimpleName(fd) : pkg + "." + outerClassSimpleName(fd));
  }

  private String prefix(FileDescriptor fd) {
    String pkg = javaPackage(fd);
    if (fd.getOptions().getJavaMultipleFiles()) return pkg.isEmpty() ? "" : pkg + ".";
    return outerClass(fd).descriptorString().substring(1).replace(';', ' ').trim().replace('/', '.') + "$";
  }

  /** Binary name (with '$' for nesting). */
  String binaryName(Descriptor d) {
    if (d.getContainingType() != null) return binaryName(d.getContainingType()) + "$" + d.getName();
    return prefix(d.getFile()) + d.getName();
  }

  String binaryName(EnumDescriptor e) {
    if (e.getContainingType() != null) return binaryName(e.getContainingType()) + "$" + e.getName();
    return prefix(e.getFile()) + e.getName();
  }

  ClassDesc message(Descriptor d) {
    return ClassDesc.of(binaryName(d));
  }

  ClassDesc builder(Descriptor d) {
    return ClassDesc.of(binaryName(d) + "$Builder");
  }

  ClassDesc orBuilder(Descriptor d) {
    return ClassDesc.of(binaryName(d) + "OrBuilder");
  }

  ClassDesc enumClass(EnumDescriptor e) {
    return ClassDesc.of(binaryName(e));
  }

  static String internal(ClassDesc cd) {
    String s = cd.descriptorString();
    return s.substring(1, s.length() - 1);
  }
}
