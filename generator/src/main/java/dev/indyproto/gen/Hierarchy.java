package dev.indyproto.gen;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Type hierarchy of generated classes plus the protobuf runtime (via reflection). Used both for
 * stack-map generation and for computing the covariant-return bridge methods javac would emit.
 */
final class Hierarchy {
  record Info(ClassDesc superclass, List<ClassDesc> interfaces, boolean isInterface,
      List<Cls.Decl> methods) {}

  private final Map<ClassDesc, Info> generated = new HashMap<>();

  void register(ClassDesc c, ClassDesc superclass, List<ClassDesc> interfaces, boolean isInterface) {
    generated.put(c, new Info(superclass, interfaces, isInterface, new ArrayList<>()));
  }

  void addMethods(ClassDesc c, List<Cls.Decl> decls) {
    generated.get(c).methods().addAll(decls);
  }

  ClassHierarchyResolver resolver() {
    ClassHierarchyResolver own = desc -> {
      Info i = generated.get(desc);
      if (i == null) return null;
      return i.isInterface()
          ? ClassHierarchyResolver.ClassHierarchyInfo.ofInterface()
          : ClassHierarchyResolver.ClassHierarchyInfo.ofClass(i.superclass());
    };
    return own.orElse(ClassHierarchyResolver.defaultResolver())
        .orElse(ClassHierarchyResolver.ofResourceParsing(Hierarchy.class.getClassLoader()));
  }

  ClassFile classFile() {
    return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver()));
  }

  private static Class<?> load(ClassDesc d) {
    try {
      String n = d.descriptorString();
      return Class.forName(n.substring(1, n.length() - 1).replace('/', '.'), false,
          Hierarchy.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  /** All supertypes (transitively, excluding {@code c}). */
  Set<ClassDesc> supertypes(ClassDesc c) {
    Set<ClassDesc> out = new HashSet<>();
    collect(c, out);
    out.remove(c);
    return out;
  }

  private void collect(ClassDesc c, Set<ClassDesc> out) {
    if (c == null || !out.add(c)) return;
    Info i = generated.get(c);
    if (i != null) {
      collect(i.superclass(), out);
      for (ClassDesc x : i.interfaces()) collect(x, out);
      return;
    }
    Class<?> k = load(c);
    if (k == null) return;
    if (k.getSuperclass() != null) collect(desc(k.getSuperclass()), out);
    for (Class<?> x : k.getInterfaces()) collect(desc(x), out);
  }

  boolean isSubtype(ClassDesc sub, ClassDesc sup) {
    if (sub.equals(sup)) return true;
    if (sub.isPrimitive() || sup.isPrimitive()) return false;
    if (sup.equals(ClassDesc.of("java.lang.Object"))) return true;
    return supertypes(sub).contains(sup);
  }

  static ClassDesc desc(Class<?> k) {
    return k.describeConstable().orElseThrow();
  }

  /** Overridable instance methods declared by supertypes of {@code c}. */
  List<Cls.Decl> inheritedMethods(ClassDesc c) {
    List<Cls.Decl> out = new ArrayList<>();
    for (ClassDesc s : supertypes(c)) {
      Info i = generated.get(s);
      if (i != null) {
        out.addAll(i.methods());
        continue;
      }
      Class<?> k = load(s);
      if (k == null) continue;
      for (Method m : k.getDeclaredMethods()) {
        int mod = m.getModifiers();
        if (Modifier.isStatic(mod) || Modifier.isPrivate(mod) || m.isSynthetic()) continue;
        if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue;
        out.add(new Cls.Decl(m.getName(), MethodTypeDesc.of(desc(m.getReturnType()),
            java.util.Arrays.stream(m.getParameterTypes()).map(Hierarchy::desc).toArray(ClassDesc[]::new)),
            mod, List.of()));
      }
    }
    return out;
  }

  /** Emits javac-style bridges for every declared method that covariantly overrides a supertype's. */
  void emitBridges(Cls cls) {
    Map<String, Cls.Decl> bridges = new LinkedHashMap<>();
    Set<String> declared = new HashSet<>();
    for (Cls.Decl d : cls.decls) declared.add(d.name() + d.type().descriptorString());
    List<Cls.Decl> inherited = inheritedMethods(cls.self);
    for (Cls.Decl d : List.copyOf(cls.decls)) {
      if ((d.flags() & (Cls.STATIC | Cls.PRIVATE)) != 0 || d.name().startsWith("<")) continue;
      for (Cls.Decl s : inherited) {
        if (!s.name().equals(d.name())) continue;
        if (!s.type().parameterList().equals(d.type().parameterList())) continue;
        ClassDesc sr = s.type().returnType(), dr = d.type().returnType();
        if (sr.equals(dr) || sr.isPrimitive() || dr.isPrimitive()) continue;
        if (!isSubtype(dr, sr)) continue;
        String key = d.name() + s.type().descriptorString();
        if (declared.contains(key) || bridges.containsKey(key)) continue;
        bridges.put(key, new Cls.Decl(d.name(), s.type(),
            (d.flags() & (Cls.PUBLIC | Cls.PROTECTED)), d.exceptions()));
        // Remember which concrete method to delegate to.
        targets.put(cls.self + "#" + key, d);
      }
    }
    for (var e : bridges.entrySet()) {
      Cls.Decl br = e.getValue();
      Cls.Decl target = targets.get(cls.self + "#" + e.getKey());
      cls.b.withMethod(br.name(), br.type(),
          br.flags() | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC, mb -> {
            if (!br.exceptions().isEmpty()) {
              mb.with(java.lang.classfile.attribute.ExceptionsAttribute.ofSymbols(br.exceptions()));
            }
            mb.withCode(cb -> {
              cb.aload(0);
              int slot = 1;
              for (ClassDesc p : br.type().parameterList()) {
                var tk = java.lang.classfile.TypeKind.from(p);
                cb.loadLocal(tk, slot);
                slot += tk.slotSize();
              }
              cb.invokevirtual(cls.self, target.name(), target.type());
              cb.areturn();
            });
          });
    }
  }

  private final Map<String, Cls.Decl> targets = new HashMap<>();

  static void unused(CodeBuilder cb) {}
}
