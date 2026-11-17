package dev.indyproto.gen;

import static dev.indyproto.gen.Cls.mt;
import static java.lang.constant.ConstantDescs.CD_Object;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.indyproto.runtime.CD;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.util.List;

/**
 * Extension declarations ({@code extend Foo { ... }}): the {@code public static final
 * GeneratedExtension} fields and {@code FOO_FIELD_NUMBER} constants of the outer class (file
 * scope) or of the enclosing message (message scope), their initialization, and
 * {@code registerAllExtensions}.
 */
final class Extensions {
  private final Names names;

  Extensions(Names names) {
    this.names = names;
  }

  static String fieldName(FieldDescriptor ext) {
    return Names.camel(Model.fieldName(ext), false);
  }

  /** Singular Java type of the extension's value (boxed for scalars). */
  ClassDesc valueClass(FieldDescriptor ext) {
    return switch (ext.getJavaType()) {
      case INT -> ClassDesc.of("java.lang.Integer");
      case LONG -> ClassDesc.of("java.lang.Long");
      case FLOAT -> ClassDesc.of("java.lang.Float");
      case DOUBLE -> ClassDesc.of("java.lang.Double");
      case BOOLEAN -> ClassDesc.of("java.lang.Boolean");
      case STRING -> CD.STRING;
      case BYTE_STRING -> CD.BYTE_STRING;
      case ENUM -> names.enumClass(ext.getEnumType());
      case MESSAGE -> names.message(ext.getMessageType());
    };
  }

  /** Declares the field and its FIELD_NUMBER constant. */
  void declare(ClassBuilder clb, FieldDescriptor ext) {
    clb.withField(ext.getName().toUpperCase(java.util.Locale.ROOT) + "_FIELD_NUMBER", CD.I, fb -> fb
        .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
        .with(ConstantValueAttribute.of(ext.getNumber())));
    String v = valueClass(ext).descriptorString();
    if (ext.isRepeated()) v = "Ljava/util/List<" + v + ">;";
    String sig = "Lcom/google/protobuf/GeneratedMessage$GeneratedExtension<"
        + names.message(ext.getContainingType()).descriptorString() + v + ">;";
    clb.withField(fieldName(ext), CD.GENERATED_EXTENSION, fb -> fb
        .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
        .with(SignatureAttribute.of(Signature.parseFrom(sig))));
  }

  private void pushClassAndDefault(CodeBuilder cb, FieldDescriptor ext) {
    ClassDesc t = valueClass(ext);
    cb.ldc(t);
    if (ext.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
      cb.invokestatic(t, "getDefaultInstance", mt(t));
    } else {
      cb.aconst_null();
    }
  }

  /** {@code <clinit>} code of the outer class: create, then bind to the file's descriptors. */
  void initFileScoped(CodeBuilder cb, ClassDesc outer, List<FieldDescriptor> exts) {
    for (FieldDescriptor ext : exts) {
      pushClassAndDefault(cb, ext);
      cb.invokestatic(CD.GENERATED_MESSAGE, "newFileScopedGeneratedExtension",
          mt(CD.GENERATED_EXTENSION, CD.CLASS, CD.MESSAGE));
      cb.putstatic(outer, fieldName(ext), CD.GENERATED_EXTENSION);
    }
    for (int i = 0; i < exts.size(); i++) {
      cb.getstatic(outer, fieldName(exts.get(i)), CD.GENERATED_EXTENSION)
          .invokestatic(outer, "getDescriptor", mt(CD.FILE_DESCRIPTOR))
          .invokevirtual(CD.FILE_DESCRIPTOR, "getExtensions", mt(CD.LIST))
          .loadConstant(i).invokeinterface(CD.LIST, "get", mt(CD_Object, CD.I))
          .checkcast(CD.FIELD_DESCRIPTOR)
          .invokevirtual(CD.GENERATED_EXTENSION, "internalInit", mt(CD.V, CD.FIELD_DESCRIPTOR));
    }
  }

  /** {@code <clinit>} code of a message declaring extensions (scope = its default instance). */
  void initMessageScoped(CodeBuilder cb, ClassDesc msg, List<FieldDescriptor> exts) {
    for (int i = 0; i < exts.size(); i++) {
      FieldDescriptor ext = exts.get(i);
      cb.invokestatic(msg, "getDefaultInstance", mt(msg)).loadConstant(i);
      pushClassAndDefault(cb, ext);
      cb.invokestatic(CD.GENERATED_MESSAGE, "newMessageScopedGeneratedExtension",
          mt(CD.GENERATED_EXTENSION, CD.MESSAGE, CD.I, CD.CLASS, CD.MESSAGE));
      cb.putstatic(msg, fieldName(ext), CD.GENERATED_EXTENSION);
    }
  }

  /** Body of {@code registerAllExtensions(ExtensionRegistryLite)}: file scope, then messages. */
  void registerAll(CodeBuilder cb, FileDescriptor fd, ClassDesc outer) {
    for (FieldDescriptor ext : fd.getExtensions()) register(cb, outer, ext);
    for (Descriptor d : fd.getMessageTypes()) registerNested(cb, d);
    cb.return_();
  }

  private void registerNested(CodeBuilder cb, Descriptor d) {
    if (d.getOptions().getMapEntry()) return;
    for (FieldDescriptor ext : d.getExtensions()) register(cb, names.message(d), ext);
    for (Descriptor n : d.getNestedTypes()) registerNested(cb, n);
  }

  private void register(CodeBuilder cb, ClassDesc owner, FieldDescriptor ext) {
    cb.aload(0).getstatic(owner, fieldName(ext), CD.GENERATED_EXTENSION)
        .invokevirtual(CD.ERL, "add", mt(CD.V, ClassDesc.of("com.google.protobuf.ExtensionLite")));
  }
}
