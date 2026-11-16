package dev.indyproto.gen;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Thin wrapper over {@link ClassBuilder} that records declared methods for bridge generation. */
final class Cls {
  record Decl(String name, MethodTypeDesc type, int flags, List<ClassDesc> exceptions) {}

  final ClassDesc self;
  final ClassBuilder b;
  final List<Decl> decls = new ArrayList<>();

  Cls(ClassDesc self, ClassBuilder b) {
    this.self = self;
    this.b = b;
  }

  static final int PUBLIC = ClassFile.ACC_PUBLIC;
  static final int PRIVATE = ClassFile.ACC_PRIVATE;
  static final int PROTECTED = ClassFile.ACC_PROTECTED;
  static final int STATIC = ClassFile.ACC_STATIC;
  static final int FINAL = ClassFile.ACC_FINAL;
  static final int ABSTRACT = ClassFile.ACC_ABSTRACT;

  void classSignature(String sig) {
    b.with(SignatureAttribute.of(ClassSignature.parseFrom(sig)));
  }

  /** Declares a method. {@code sig} is an optional generic signature, {@code code} null = abstract. */
  void method(String name, int flags, MethodTypeDesc type, String sig, List<ClassDesc> exceptions,
      Consumer<CodeBuilder> code) {
    decls.add(new Decl(name, type, flags, exceptions));
    b.withMethod(name, type, flags, mb -> {
      if (sig != null) mb.with(SignatureAttribute.of(MethodSignature.parseFrom(sig)));
      if (!exceptions.isEmpty()) mb.with(ExceptionsAttribute.ofSymbols(exceptions));
      if (code != null) mb.withCode(code);
    });
  }

  void method(String name, int flags, MethodTypeDesc type, Consumer<CodeBuilder> code) {
    method(name, flags, type, null, List.of(), code);
  }

  void method(String name, int flags, MethodTypeDesc type, String sig, Consumer<CodeBuilder> code) {
    method(name, flags, type, sig, List.of(), code);
  }

  static MethodTypeDesc mt(ClassDesc ret, ClassDesc... params) {
    return MethodTypeDesc.of(ret, params);
  }
}
