package dev.indyproto.gen;

import static dev.indyproto.gen.Cls.FINAL;
import static dev.indyproto.gen.Cls.PRIVATE;
import static dev.indyproto.gen.Cls.PUBLIC;
import static dev.indyproto.gen.Cls.STATIC;
import static dev.indyproto.gen.Cls.mt;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CLASS_INIT_NAME;
import static java.lang.constant.ConstantDescs.INIT_NAME;

import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import dev.indyproto.runtime.CD;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Writes a protobuf enum as a Java enum implementing ProtocolMessageEnum. */
final class EnumWriter {
  private final EnumDescriptor e;
  private final ClassDesc self;
  private final Hierarchy h;
  private final Gen gen;
  private final ClassDesc outer;
  private final List<Integer> path;
  private final boolean open;

  EnumWriter(EnumDescriptor e, Names names, Hierarchy h, Gen gen, ClassDesc outer, List<Integer> path) {
    this.e = e;
    this.self = names.enumClass(e);
    this.h = h;
    this.gen = gen;
    this.outer = outer;
    this.path = path;
    this.open = !e.isClosed();
    Set<Integer> seen = new HashSet<>();
    for (EnumValueDescriptor v : e.getValues()) {
      if (!seen.add(v.getNumber())) {
        throw new UnsupportedOperationException("prototype does not support enum aliases: " + e.getFullName());
      }
    }
  }

  void register() {
    h.register(self, ClassDesc.of("java.lang.Enum"), List.of(CD.PROTOCOL_MESSAGE_ENUM), false);
  }

  byte[] write() {
    ClassDesc arr = self.arrayType();
    List<EnumValueDescriptor> values = e.getValues();
    return h.classFile().build(self, clb -> {
      clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_ENUM);
      clb.withSuperclass(ClassDesc.of("java.lang.Enum"));
      clb.withInterfaceSymbols(CD.PROTOCOL_MESSAGE_ENUM);
      gen.innerClasses(clb, self);
      gen.nest(clb, self);
      Cls c = new Cls(self, clb);
      c.classSignature("Ljava/lang/Enum<" + MessageWriter.d(self) + ">;Lcom/google/protobuf/ProtocolMessageEnum;");

      int constFlags = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_ENUM;
      List<String> constNames = new ArrayList<>();
      for (EnumValueDescriptor v : values) constNames.add(v.getName());
      if (open) constNames.add("UNRECOGNIZED");
      for (String n : constNames) clb.withField(n, self, constFlags);
      for (EnumValueDescriptor v : values) {
        clb.withField(v.getName() + "_VALUE", CD.I, fb -> fb
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
            .with(ConstantValueAttribute.of(v.getNumber())));
      }
      clb.withField("value", CD.I, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
      clb.withField("$VALUES", arr, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);

      clb.withMethodBody(CLASS_INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_STATIC, cb -> {
        for (int i = 0; i < constNames.size(); i++) {
          int number = i < values.size() ? values.get(i).getNumber() : -1;
          cb.new_(self).dup().ldc(constNames.get(i)).loadConstant(i).loadConstant(number)
              .invokespecial(self, INIT_NAME, mt(CD.V, CD.STRING, CD.I, CD.I))
              .putstatic(self, constNames.get(i), self);
        }
        cb.loadConstant(constNames.size()).anewarray(self);
        for (int i = 0; i < constNames.size(); i++) {
          cb.dup().loadConstant(i).getstatic(self, constNames.get(i), self).aastore();
        }
        cb.putstatic(self, "$VALUES", arr);
        cb.return_();
      });

      clb.withMethodBody(INIT_NAME, mt(CD.V, CD.STRING, CD.I, CD.I), ClassFile.ACC_PRIVATE, cb -> cb
          .aload(0).aload(1).iload(2)
          .invokespecial(ClassDesc.of("java.lang.Enum"), INIT_NAME, mt(CD.V, CD.STRING, CD.I))
          .aload(0).iload(3).putfield(self, "value", CD.I).return_());

      c.method("values", PUBLIC | STATIC, mt(arr), cb -> cb
          .getstatic(self, "$VALUES", arr).invokevirtual(arr, "clone", mt(CD_Object)).checkcast(arr).areturn());
      c.method("valueOf", PUBLIC | STATIC, mt(self, CD.STRING), cb -> cb
          .ldc(self).aload(0)
          .invokestatic(ClassDesc.of("java.lang.Enum"), "valueOf",
              mt(ClassDesc.of("java.lang.Enum"), CD.CLASS, CD.STRING))
          .checkcast(self).areturn());

      c.method("getNumber", PUBLIC | FINAL, mt(CD.I), cb -> {
        if (open) {
          Label ok = cb.newLabel();
          cb.aload(0).getstatic(self, "UNRECOGNIZED", self).if_acmpne(ok);
          throwNew(cb, "java.lang.IllegalArgumentException", "Can't get the number of an unknown enum value.");
          cb.labelBinding(ok);
        }
        cb.aload(0).getfield(self, "value", CD.I).ireturn();
      });
      c.method("valueOf", PUBLIC | STATIC, mt(self, CD.I), cb -> cb
          .iload(0).invokestatic(self, "forNumber", mt(self, CD.I)).areturn());
      c.method("forNumber", PUBLIC | STATIC, mt(self, CD.I), cb -> {
        Label dflt = cb.newLabel();
        List<SwitchCase> cases = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        for (EnumValueDescriptor v : values) {
          Label l = cb.newLabel();
          labels.add(l);
          cases.add(SwitchCase.of(v.getNumber(), l));
        }
        List<SwitchCase> sorted = new ArrayList<>(cases);
        sorted.sort((a, b) -> Integer.compare(a.caseValue(), b.caseValue()));
        cb.iload(0).lookupswitch(dflt, sorted);
        for (int i = 0; i < values.size(); i++) {
          cb.labelBinding(labels.get(i));
          cb.getstatic(self, values.get(i).getName(), self).areturn();
        }
        cb.labelBinding(dflt);
        cb.aconst_null().areturn();
      });
      c.method("internalGetValueMap", PUBLIC | STATIC, mt(CD.ENUM_LITE_MAP),
          "()Lcom/google/protobuf/Internal$EnumLiteMap<" + MessageWriter.d(self) + ">;",
          cb -> cb.ldc(DynamicConstantDesc.ofNamed(
              ConstantDescs.ofConstantBootstrap(CD.BOOTSTRAPS, "enumValueMap", CD.ENUM_LITE_MAP, CD.CLASS),
              "valueMap", CD.ENUM_LITE_MAP, self)).areturn());
      c.method("getValueDescriptor", PUBLIC | FINAL, mt(CD.ENUM_VALUE_DESCRIPTOR), cb -> {
        if (open) {
          Label ok = cb.newLabel();
          cb.aload(0).getstatic(self, "UNRECOGNIZED", self).if_acmpne(ok);
          throwNew(cb, "java.lang.IllegalStateException", "Can't get the descriptor of an unrecognized enum value.");
          cb.labelBinding(ok);
        }
        cb.invokestatic(self, "getDescriptor", mt(CD.ENUM_DESCRIPTOR))
            .invokevirtual(CD.ENUM_DESCRIPTOR, "getValues", mt(CD.LIST))
            .aload(0).invokevirtual(self, "ordinal", mt(CD.I))
            .invokeinterface(CD.LIST, "get", mt(CD_Object, CD.I))
            .checkcast(CD.ENUM_VALUE_DESCRIPTOR).areturn();
      });
      c.method("getDescriptorForType", PUBLIC | FINAL, mt(CD.ENUM_DESCRIPTOR), cb -> cb
          .invokestatic(self, "getDescriptor", mt(CD.ENUM_DESCRIPTOR)).areturn());
      List<ConstantDesc> args = new ArrayList<>();
      args.add(outer);
      args.addAll(path);
      c.method("getDescriptor", PUBLIC | STATIC, mt(CD.ENUM_DESCRIPTOR), cb -> cb
          .ldc(DynamicConstantDesc.ofNamed(
              ConstantDescs.ofConstantBootstrap(CD.BOOTSTRAPS, "enumDescriptor", CD.ENUM_DESCRIPTOR,
                  CD.CLASS, CD_Object.arrayType()),
              "descriptor", CD.ENUM_DESCRIPTOR, args.toArray(ConstantDesc[]::new)))
          .areturn());
      c.method("valueOf", PUBLIC | STATIC, mt(self, CD.ENUM_VALUE_DESCRIPTOR), cb -> {
        Label sameType = cb.newLabel();
        cb.aload(0).invokevirtual(CD.ENUM_VALUE_DESCRIPTOR, "getType", mt(CD.ENUM_DESCRIPTOR))
            .invokestatic(self, "getDescriptor", mt(CD.ENUM_DESCRIPTOR)).if_acmpeq(sameType);
        throwNew(cb, "java.lang.IllegalArgumentException", "EnumValueDescriptor is not for this type.");
        cb.labelBinding(sameType);
        if (open) {
          Label known = cb.newLabel();
          cb.aload(0).invokevirtual(CD.ENUM_VALUE_DESCRIPTOR, "getIndex", mt(CD.I)).iconst_m1().if_icmpne(known);
          cb.getstatic(self, "UNRECOGNIZED", self).areturn();
          cb.labelBinding(known);
        }
        cb.getstatic(self, "$VALUES", arr)
            .aload(0).invokevirtual(CD.ENUM_VALUE_DESCRIPTOR, "getIndex", mt(CD.I))
            .aaload().areturn();
      });
      h.addMethods(self, c.decls);
    });
  }

  private static void throwNew(CodeBuilder cb, String type, String msg) {
    ClassDesc t = ClassDesc.of(type);
    cb.new_(t).dup().ldc(msg).invokespecial(t, INIT_NAME, mt(CD.V, CD.STRING)).athrow();
  }

  static final int UNUSED = PRIVATE;
}
