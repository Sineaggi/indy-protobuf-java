package dev.indyproto.gen;

import static dev.indyproto.gen.Cls.mt;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.indyproto.runtime.CD;
import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generator entry point. Reads a {@code FileDescriptorSet} (from {@code protoc
 * --descriptor_set_out --include_imports}) and writes class files directly, with the JDK
 * ClassFile API (java.lang.classfile, JDK 24+).
 *
 * <pre>
 * java -jar generator.jar --descriptor-set set.pb --out classes/ [--rewrite from.pkg=to.pkg] [file.proto...]
 * </pre>
 */
public final class Gen {

  /** binary name -> (outer binary name, simple name, flags) for InnerClasses / nests. */
  private record Nesting(ClassDesc outer, String simpleName, int flags) {}

  private final Names names;
  private final Hierarchy h = new Hierarchy();
  private final Map<ClassDesc, Nesting> nesting = new LinkedHashMap<>();
  private final Map<ClassDesc, byte[]> out = new LinkedHashMap<>();

  Gen(Names names) {
    this.names = names;
  }

  public static void main(String[] args) throws Exception {
    Path set = null, outDir = null;
    Map<String, String> rewrites = new LinkedHashMap<>();
    List<String> only = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--descriptor-set" -> set = Path.of(args[++i]);
        case "--out" -> outDir = Path.of(args[++i]);
        case "--rewrite" -> {
          String[] kv = args[++i].split("=", 2);
          rewrites.put(kv[0], kv[1]);
        }
        default -> only.add(args[i]);
      }
    }
    if (set == null || outDir == null) {
      System.err.println("usage: --descriptor-set FILE --out DIR [--rewrite from=to] [files...]");
      System.exit(2);
    }
    Map<ClassDesc, byte[]> classes = generate(Files.readAllBytes(set), rewrites, only);
    for (var e : classes.entrySet()) {
      Path p = outDir.resolve(Names.internal(e.getKey()) + ".class");
      Files.createDirectories(p.getParent());
      Files.write(p, e.getValue());
    }
    System.out.println("indyproto: wrote " + classes.size() + " classes to " + outDir);
  }

  public static Map<ClassDesc, byte[]> generate(byte[] descriptorSet, Map<String, String> rewrites,
      List<String> only) throws Exception {
    FileDescriptorSet fds = FileDescriptorSet.parseFrom(descriptorSet);
    Map<String, FileDescriptor> built = new HashMap<>();
    List<FileDescriptor> files = new ArrayList<>();
    for (FileDescriptorProto p : fds.getFileList()) {
      FileDescriptor[] deps = p.getDependencyList().stream().map(built::get).toArray(FileDescriptor[]::new);
      FileDescriptor fd = FileDescriptor.buildFrom(p, deps);
      built.put(p.getName(), fd);
      files.add(fd);
    }
    Names names = new Names(rewrites);
    Gen g = new Gen(names);
    List<FileDescriptor> targets = new ArrayList<>();
    for (FileDescriptor fd : files) {
      if (names.isWellKnown(fd)) continue;
      if (!only.isEmpty() && !only.contains(fd.getName())) continue;
      targets.add(fd);
    }
    List<Runnable> writers = new ArrayList<>();
    for (FileDescriptor fd : targets) g.planFile(fd, writers);
    for (Runnable w : writers) w.run();
    return g.out;
  }

  // ---------------------------------------------------------------------------------------------

  private void planFile(FileDescriptor fd, List<Runnable> writers) {
    ClassDesc outer = names.outerClass(fd);
    boolean multi = fd.getOptions().getJavaMultipleFiles();
    h.register(outer, CD.GENERATED_FILE, List.of(), false);
    writers.add(() -> out.put(outer, writeOuter(fd, outer)));
    for (int i = 0; i < fd.getMessageTypes().size(); i++) {
      planMessage(fd.getMessageTypes().get(i), outer, multi ? null : outer, List.of(i), writers);
    }
    for (int i = 0; i < fd.getEnumTypes().size(); i++) {
      planEnum(fd.getEnumTypes().get(i), outer, multi ? null : outer, List.of(i), writers);
    }
  }

  private static final int PSF = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL;

  private void planMessage(Descriptor d, ClassDesc outer, ClassDesc enclosing, List<Integer> path,
      List<Runnable> writers) {
    Model m = new Model(d, names);
    MessageWriter w = new MessageWriter(m, names, h, this, outer, path);
    w.register();
    if (enclosing != null) {
      nesting.put(m.msg, new Nesting(enclosing, d.getName(), PSF));
      nesting.put(m.orBuilder, new Nesting(enclosing, d.getName() + "OrBuilder",
          ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT));
    }
    nesting.put(m.builder, new Nesting(m.msg, "Builder", PSF));
    for (Model.O o : m.oneofs) {
      h.register(o.caseEnum(), ClassDesc.of("java.lang.Enum"), List.of(CD.ENUM_LITE, CD.ONEOF_ENUM), false);
      nesting.put(o.caseEnum(), new Nesting(m.msg, o.cap() + "Case", PSF | ClassFile.ACC_ENUM));
      writers.add(() -> out.put(o.caseEnum(), OneofMapWriter.writeCaseEnum(o, h, this)));
    }
    writers.add(() -> {
      out.put(m.orBuilder, w.writeOrBuilder());
    });
    for (int i = 0; i < d.getNestedTypes().size(); i++) {
      Descriptor n = d.getNestedTypes().get(i);
      if (n.getOptions().getMapEntry()) continue;
      List<Integer> p = new ArrayList<>(path);
      p.add(i);
      planMessage(n, outer, m.msg, p, writers);
    }
    for (int i = 0; i < d.getEnumTypes().size(); i++) {
      List<Integer> p = new ArrayList<>(path);
      p.add(i);
      planEnum(d.getEnumTypes().get(i), outer, m.msg, p, writers);
    }
    // message + builder after all OrBuilders of this subtree are known (bridges need them)
    writers.add(() -> {
      out.put(m.msg, w.writeMessage());
      out.put(m.builder, w.writeBuilder());
    });
  }

  private void planEnum(EnumDescriptor e, ClassDesc outer, ClassDesc enclosing, List<Integer> path,
      List<Runnable> writers) {
    EnumWriter w = new EnumWriter(e, names, h, this, outer, path);
    w.register();
    ClassDesc self = names.enumClass(e);
    if (enclosing != null) {
      nesting.put(self, new Nesting(enclosing, e.getName(), PSF | ClassFile.ACC_ENUM));
    }
    writers.add(() -> out.put(self, w.write()));
  }

  // ---------------------------------------------------------------------------------------------
  // InnerClasses / NestHost / NestMembers

  private ClassDesc top(ClassDesc c) {
    Nesting n = nesting.get(c);
    return n == null ? c : top(n.outer());
  }

  void innerClasses(ClassBuilder clb, ClassDesc self) {
    List<InnerClassInfo> infos = new ArrayList<>();
    Set<ClassDesc> seen = new java.util.HashSet<>();
    // self and its enclosing chain
    for (ClassDesc c = self; nesting.containsKey(c); c = nesting.get(c).outer()) {
      Nesting n = nesting.get(c);
      if (seen.add(c)) infos.add(InnerClassInfo.of(c, Optional.of(n.outer()), Optional.of(n.simpleName()), n.flags()));
    }
    // direct members
    for (var e : nesting.entrySet()) {
      if (e.getValue().outer().equals(self) && seen.add(e.getKey())) {
        Nesting n = e.getValue();
        infos.add(InnerClassInfo.of(e.getKey(), Optional.of(n.outer()), Optional.of(n.simpleName()), n.flags()));
      }
    }
    if (!infos.isEmpty()) clb.with(InnerClassesAttribute.of(infos));
  }

  void nest(ClassBuilder clb, ClassDesc self) {
    ClassDesc host = top(self);
    if (!host.equals(self)) {
      clb.with(NestHostAttribute.of(host));
      return;
    }
    List<ClassDesc> members = new ArrayList<>();
    for (ClassDesc c : nesting.keySet()) if (top(c).equals(self)) members.add(c);
    if (!members.isEmpty()) clb.with(NestMembersAttribute.ofSymbols(members));
  }

  // ---------------------------------------------------------------------------------------------
  // outer class

  private byte[] writeOuter(FileDescriptor fd, ClassDesc outer) {
    byte[] data = fd.toProto().toByteArray();
    String latin1 = new String(data, StandardCharsets.ISO_8859_1);
    List<ConstantDesc> args = new ArrayList<>();
    for (int i = 0; i < latin1.length(); i += 16000) {
      args.add(latin1.substring(i, Math.min(latin1.length(), i + 16000)));
    }
    for (FileDescriptor dep : fd.getDependencies()) args.add(names.outerClass(dep));
    DynamicConstantDesc<?> fileConst = DynamicConstantDesc.ofNamed(
        ConstantDescs.ofConstantBootstrap(CD.BOOTSTRAPS, "fileDescriptor", CD.FILE_DESCRIPTOR,
            ConstantDescs.CD_Object.arrayType()),
        "file", CD.FILE_DESCRIPTOR, args.toArray(ConstantDesc[]::new));
    return h.classFile().build(outer, clb -> {
      clb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL, AccessFlag.SUPER);
      clb.withSuperclass(CD.GENERATED_FILE);
      innerClasses(clb, outer);
      nest(clb, outer);
      clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PRIVATE, cb -> cb
          .aload(0).invokespecial(CD.GENERATED_FILE, INIT_NAME, MTD_void).return_());
      clb.withMethodBody("getDescriptor", mt(CD.FILE_DESCRIPTOR), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
          cb -> cb.ldc(fileConst).areturn());
      Extensions ex = new Extensions(names);
      for (var ext : fd.getExtensions()) ex.declare(clb, ext);
      if (!fd.getExtensions().isEmpty()) {
        clb.withMethodBody(ConstantDescs.CLASS_INIT_NAME, MTD_void, ClassFile.ACC_STATIC, cb -> {
          ex.initFileScoped(cb, outer, fd.getExtensions());
          cb.return_();
        });
      }
      clb.withMethodBody("registerAllExtensions", mt(CD.V, CD.ERL), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
          cb -> ex.registerAll(cb, fd, outer));
      clb.withMethodBody("registerAllExtensions", mt(CD.V, CD.ER), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
          cb -> cb.aload(0).invokestatic(outer, "registerAllExtensions", mt(CD.V, CD.ERL)).return_());
    });
  }

  static void unused() throws IOException {}
}
