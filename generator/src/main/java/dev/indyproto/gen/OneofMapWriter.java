package dev.indyproto.gen;

import static dev.indyproto.gen.Cls.PRIVATE;
import static dev.indyproto.gen.Cls.PROTECTED;
import static dev.indyproto.gen.Cls.PUBLIC;
import static dev.indyproto.gen.Cls.STATIC;
import static dev.indyproto.gen.Cls.mt;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.INIT_NAME;

import dev.indyproto.gen.Model.F;
import dev.indyproto.gen.Model.O;
import dev.indyproto.runtime.CD;
import dev.indyproto.runtime.Schema.Kind;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Accessors and mutators of oneof members and map fields, plus the oneof {@code FooCase} enums.
 * Method bodies stay small: map operations delegate to the runtime's {@code MapField} /
 * {@code MapFieldBuilder} and to {@code Rt}, as protoc's own gencode mostly does.
 */
final class OneofMapWriter {
  private final Model m;
  private final MessageWriter w;

  OneofMapWriter(Model m, MessageWriter w) {
    this.m = m;
    this.w = w;
  }

  // ---- small bytecode helpers ---------------------------------------------------------------

  static void box(CodeBuilder cb, ClassDesc t) {
    if (!t.isPrimitive()) return;
    ClassDesc b = Model.boxed(t);
    cb.invokestatic(b, "valueOf", mt(b, t));
  }

  /** Object on the stack -> value of type {@code t} (unboxed or checkcast). */
  static void unbox(CodeBuilder cb, ClassDesc t) {
    if (!t.isPrimitive()) {
      cb.checkcast(t);
      return;
    }
    ClassDesc b = Model.boxed(t);
    cb.checkcast(b).invokevirtual(b, t.displayName() + "Value", mt(t));
  }

  static String sig(ClassDesc t) {
    return (t.isPrimitive() ? Model.boxed(t) : t).descriptorString();
  }

  private static void requireNonNull(CodeBuilder cb, int slot, String what) {
    cb.aload(slot).ldc(what)
        .invokestatic(CD.OBJECTS, "requireNonNull", mt(CD_Object, CD_Object, CD.STRING)).pop();
  }

  private static void returnThis(CodeBuilder cb) {
    cb.aload(0).areturn();
  }

  // ==============================================================================================
  // oneofs

  private void loadCase(CodeBuilder cb, ClassDesc owner, O o) {
    cb.aload(0).getfield(owner, o.caseField(), CD.I);
  }

  private void loadValue(CodeBuilder cb, ClassDesc owner, O o) {
    cb.aload(0).getfield(owner, o.valueField(), CD_Object);
  }

  private void ifNotCase(CodeBuilder cb, ClassDesc owner, O o, F f, Label no) {
    loadCase(cb, owner, o);
    cb.loadConstant(f.s().number()).if_icmpne(no);
  }

  /** this.oCase_ = number; this.o_ = (value produced by {@code value}). */
  private void setCase(CodeBuilder cb, ClassDesc owner, O o, F f, Consumer<CodeBuilder> value) {
    cb.aload(0).loadConstant(f.s().number()).putfield(owner, o.caseField(), CD.I);
    cb.aload(0);
    value.accept(cb);
    cb.putfield(owner, o.valueField(), CD_Object);
  }

  private void clearCase(CodeBuilder cb, ClassDesc owner, O o) {
    cb.aload(0).iconst_0().putfield(owner, o.caseField(), CD.I);
    cb.aload(0).aconst_null().putfield(owner, o.valueField(), CD_Object);
  }

  private static ClassDesc memberType(F f) {
    return switch (f.kind()) {
      case STRING -> CD.STRING;
      case BYTES -> CD.BYTE_STRING;
      case MESSAGE, ENUM -> f.type();
      default -> f.kind().valueDesc();
    };
  }

  /** Read side of a oneof member, shared by message, builder and the OrBuilder interface. */
  void oneofReadAccessors(Cls c, ClassDesc owner, boolean iface, F f) {
    int flags = iface ? PUBLIC | Cls.ABSTRACT : PUBLIC;
    boolean isBuilder = owner != null && owner.equals(m.builder);
    O o = m.oneof(f);
    String cap = f.cap();
    c.method("has" + cap, flags, mt(CD.Z), MessageWriter.body(iface, cb -> {
      Label no = cb.newLabel();
      ifNotCase(cb, owner, o, f, no);
      cb.iconst_1().ireturn();
      cb.labelBinding(no);
      cb.iconst_0().ireturn();
    }));
    switch (f.kind()) {
      case STRING -> {
        c.method("get" + cap, flags, mt(CD.STRING), MessageWriter.body(iface, cb -> {
          Label set = cb.newLabel(), conv = cb.newLabel();
          ifNotCase(cb, owner, o, f, set);
          int r = cb.allocateLocal(TypeKind.REFERENCE);
          loadValue(cb, owner, o);
          cb.astore(r);
          cb.aload(r).instanceOf(CD.STRING).ifeq(conv);
          cb.aload(r).checkcast(CD.STRING).areturn();
          cb.labelBinding(conv);
          int s = cb.allocateLocal(TypeKind.REFERENCE);
          cb.aload(r).checkcast(CD.BYTE_STRING).invokevirtual(CD.BYTE_STRING, "toStringUtf8", mt(CD.STRING)).astore(s);
          Label skip = cb.newLabel();
          if (!f.s().utf8()) {
            cb.aload(r).checkcast(CD.BYTE_STRING).invokevirtual(CD.BYTE_STRING, "isValidUtf8", mt(CD.Z)).ifeq(skip);
          }
          cb.aload(0).aload(s).putfield(owner, o.valueField(), CD_Object);
          cb.labelBinding(skip);
          cb.aload(s).areturn();
          cb.labelBinding(set);
          if (f.s().customDefault()) MessageWriter.pushCustomDefault(cb, f); else cb.ldc("");
          cb.areturn();
        }));
        c.method("get" + cap + "Bytes", flags, mt(CD.BYTE_STRING), MessageWriter.body(iface, cb -> {
          Label set = cb.newLabel();
          ifNotCase(cb, owner, o, f, set);
          int r = cb.allocateLocal(TypeKind.REFERENCE);
          loadValue(cb, owner, o);
          cb.invokestatic(CD.RT, "toBytesValue", mt(CD.BYTE_STRING, CD_Object)).astore(r);
          cb.aload(0).aload(r).putfield(owner, o.valueField(), CD_Object);
          cb.aload(r).areturn();
          cb.labelBinding(set);
          if (f.s().customDefault()) {
            cb.ldc((String) f.fd().getDefaultValue()).invokestatic(CD.BYTE_STRING, "copyFromUtf8", mt(CD.BYTE_STRING, CD.STRING));
          } else {
            cb.getstatic(CD.BYTE_STRING, "EMPTY", CD.BYTE_STRING);
          }
          cb.areturn();
        }));
      }
      case ENUM -> {
        var dfltValue = (com.google.protobuf.Descriptors.EnumValueDescriptor) f.fd().getDefaultValue();
        int dflt = dfltValue.getNumber();
        if (f.openEnum()) {
          c.method("get" + cap + "Value", flags, mt(CD.I), MessageWriter.body(iface, cb -> {
            Label no = cb.newLabel();
            ifNotCase(cb, owner, o, f, no);
            loadValue(cb, owner, o);
            unbox(cb, CD.I);
            cb.ireturn();
            cb.labelBinding(no);
            cb.loadConstant(dflt).ireturn();
          }));
        }
        c.method("get" + cap, flags, mt(f.type()), MessageWriter.body(iface, cb -> {
          Label no = cb.newLabel();
          ifNotCase(cb, owner, o, f, no);
          loadValue(cb, owner, o);
          unbox(cb, CD.I);
          w.enumFromNumber(cb, f);
          cb.areturn();
          cb.labelBinding(no);
          cb.getstatic(f.type(), dfltValue.getName(), f.type()).areturn();
        }));
      }
      case MESSAGE -> {
        for (boolean orBuilder : new boolean[] {false, true}) {
          ClassDesc ret = orBuilder ? f.typeOrBuilder() : f.type();
          c.method("get" + cap + (orBuilder ? "OrBuilder" : ""), flags, mt(ret), MessageWriter.body(iface, cb -> {
            Label no = cb.newLabel();
            ifNotCase(cb, owner, o, f, no);
            if (isBuilder) {
              Label plain = cb.newLabel();
              cb.aload(0).getfield(owner, f.fb(), CD.SINGLE_FIELD_BUILDER).ifnull(plain);
              cb.aload(0).getfield(owner, f.fb(), CD.SINGLE_FIELD_BUILDER);
              if (orBuilder) {
                cb.invokevirtual(CD.SINGLE_FIELD_BUILDER, "getMessageOrBuilder", mt(CD.MESSAGE_OR_BUILDER));
              } else {
                cb.invokevirtual(CD.SINGLE_FIELD_BUILDER, "getMessage", mt(CD.GENERATED_MESSAGE));
              }
              cb.checkcast(ret).areturn();
              cb.labelBinding(plain);
            }
            loadValue(cb, owner, o);
            cb.checkcast(f.type()).areturn();
            cb.labelBinding(no);
            cb.invokestatic(f.type(), "getDefaultInstance", mt(f.type())).areturn();
          }));
        }
      }
      default -> {
        ClassDesc t = memberType(f);
        c.method("get" + cap, flags, mt(t), MessageWriter.body(iface, cb -> {
          Label no = cb.newLabel();
          ifNotCase(cb, owner, o, f, no);
          loadValue(cb, owner, o);
          unbox(cb, t);
          cb.return_(TypeKind.from(t));
          cb.labelBinding(no);
          if (f.s().customDefault()) {
            MessageWriter.pushCustomDefault(cb, f);
          } else if (f.kind() == Kind.BYTES) {
            cb.getstatic(CD.BYTE_STRING, "EMPTY", CD.BYTE_STRING);
          } else {
            switch (t.descriptorString()) {
              case "J" -> cb.lconst_0();
              case "F" -> cb.fconst_0();
              case "D" -> cb.dconst_0();
              default -> cb.iconst_0();
            }
          }
          cb.return_(TypeKind.from(t));
        }));
      }
    }
  }

  /** getFooCase() on message, builder and interface. */
  void caseGetter(Cls c, ClassDesc owner, boolean iface, O o) {
    int flags = iface ? PUBLIC | Cls.ABSTRACT : PUBLIC;
    c.method("get" + o.cap() + "Case", flags, mt(o.caseEnum()), MessageWriter.body(iface, cb -> {
      loadCase(cb, owner, o);
      cb.invokestatic(o.caseEnum(), "forNumber", mt(o.caseEnum(), CD.I)).areturn();
    }));
  }

  void oneofBuilderMutators(Cls c, F f) {
    ClassDesc B = m.builder;
    O o = m.oneof(f);
    String cap = f.cap();
    if (f.kind() == Kind.MESSAGE) {
      oneofMessageMutators(c, f, o);
      return;
    }
    ClassDesc t = memberType(f);
    if (f.kind() == Kind.ENUM) {
      if (f.openEnum()) {
        c.method("set" + cap + "Value", PUBLIC, mt(B, CD.I), cb -> {
          setCase(cb, B, o, f, v -> v.iload(1).invokestatic(Model.boxed(CD.I), "valueOf", mt(Model.boxed(CD.I), CD.I)));
          w.onChanged(cb);
          returnThis(cb);
        });
      }
      c.method("set" + cap, PUBLIC, mt(B, f.type()), cb -> {
        w.requireNonNull(cb, 1);
        setCase(cb, B, o, f, v -> v.aload(1).invokevirtual(f.type(), "getNumber", mt(CD.I))
            .invokestatic(Model.boxed(CD.I), "valueOf", mt(Model.boxed(CD.I), CD.I)));
        w.onChanged(cb);
        returnThis(cb);
      });
    } else {
      c.method("set" + cap, PUBLIC, mt(B, t), cb -> {
        TypeKind tk = TypeKind.from(t);
        if (tk == TypeKind.REFERENCE) w.requireNonNull(cb, 1);
        setCase(cb, B, o, f, v -> {
          v.loadLocal(tk, 1);
          box(v, t);
        });
        w.onChanged(cb);
        returnThis(cb);
      });
      if (f.kind() == Kind.STRING) {
        c.method("set" + cap + "Bytes", PUBLIC, mt(B, CD.BYTE_STRING), cb -> {
          w.requireNonNull(cb, 1);
          if (f.s().utf8()) cb.aload(1).invokestatic(CD.RT, "checkUtf8", mt(CD.V, CD.BYTE_STRING));
          setCase(cb, B, o, f, v -> v.aload(1));
          w.onChanged(cb);
          returnThis(cb);
        });
      }
    }
    c.method("clear" + cap, PUBLIC, mt(B), cb -> {
      Label no = cb.newLabel();
      ifNotCase(cb, B, o, f, no);
      clearCase(cb, B, o);
      w.onChanged(cb);
      cb.labelBinding(no);
      returnThis(cb);
    });
  }

  private void oneofMessageMutators(Cls c, F f, O o) {
    ClassDesc B = m.builder, X = f.type(), XB = f.typeBuilder(), SFB = CD.SINGLE_FIELD_BUILDER;
    String cap = f.cap();
    Consumer<CodeBuilder> loadSfb = cb -> cb.aload(0).getfield(B, f.fb(), SFB);
    c.method("set" + cap, PUBLIC, mt(B, X), cb -> {
      Label useB = cb.newLabel(), done = cb.newLabel();
      loadSfb.accept(cb);
      cb.ifnonnull(useB);
      w.requireNonNull(cb, 1);
      cb.aload(0).aload(1).putfield(B, o.valueField(), CD_Object);
      w.onChanged(cb);
      cb.goto_(done);
      cb.labelBinding(useB);
      loadSfb.accept(cb);
      cb.aload(1).invokevirtual(SFB, "setMessage", mt(SFB, CD.GENERATED_MESSAGE)).pop();
      cb.labelBinding(done);
      cb.aload(0).loadConstant(f.s().number()).putfield(B, o.caseField(), CD.I);
      returnThis(cb);
    });
    c.method("set" + cap, PUBLIC, mt(B, XB), cb -> cb
        .aload(0).aload(1).invokevirtual(XB, "build", mt(X)).invokevirtual(B, "set" + cap, mt(B, X)).areturn());
    c.method("merge" + cap, PUBLIC, mt(B, X), cb -> {
      Label useB = cb.newLabel(), replace = cb.newLabel(), stored = cb.newLabel(), done = cb.newLabel();
      loadSfb.accept(cb);
      cb.ifnonnull(useB);
      ifNotCase(cb, B, o, f, replace);
      loadValue(cb, B, o);
      cb.invokestatic(X, "getDefaultInstance", mt(X)).if_acmpeq(replace);
      cb.aload(0);
      loadValue(cb, B, o);
      cb.checkcast(X).aload(1)
          .invokestatic(CD.RT, "mergeMessage", mt(CD.MESSAGE, CD.MESSAGE, CD.MESSAGE_LITE))
          .putfield(B, o.valueField(), CD_Object);
      cb.goto_(stored);
      cb.labelBinding(replace);
      cb.aload(0).aload(1).putfield(B, o.valueField(), CD_Object);
      cb.labelBinding(stored);
      w.onChanged(cb);
      cb.goto_(done);
      cb.labelBinding(useB);
      Label setMsg = cb.newLabel(), after = cb.newLabel();
      ifNotCase(cb, B, o, f, setMsg);
      loadSfb.accept(cb);
      cb.aload(1).invokevirtual(SFB, "mergeFrom", mt(SFB, CD.GENERATED_MESSAGE)).pop().goto_(after);
      cb.labelBinding(setMsg);
      loadSfb.accept(cb);
      cb.aload(1).invokevirtual(SFB, "setMessage", mt(SFB, CD.GENERATED_MESSAGE)).pop();
      cb.labelBinding(after);
      cb.labelBinding(done);
      cb.aload(0).loadConstant(f.s().number()).putfield(B, o.caseField(), CD.I);
      returnThis(cb);
    });
    c.method("clear" + cap, PUBLIC, mt(B), cb -> {
      Label useB = cb.newLabel(), done = cb.newLabel(), notCase = cb.newLabel();
      loadSfb.accept(cb);
      cb.ifnonnull(useB);
      ifNotCase(cb, B, o, f, done);
      clearCase(cb, B, o);
      w.onChanged(cb);
      cb.goto_(done);
      cb.labelBinding(useB);
      ifNotCase(cb, B, o, f, notCase);
      clearCase(cb, B, o);
      cb.labelBinding(notCase);
      loadSfb.accept(cb);
      cb.invokevirtual(SFB, "clear", mt(SFB)).pop();
      cb.labelBinding(done);
      returnThis(cb);
    });
    c.method("get" + cap + "Builder", PUBLIC, mt(XB), cb -> cb
        .aload(0).invokevirtual(B, "internalGet" + cap + "FieldBuilder", mt(SFB))
        .invokevirtual(SFB, "getBuilder", mt(CD.GM_BUILDER)).checkcast(XB).areturn());
    c.method("internalGet" + cap + "FieldBuilder", PRIVATE, mt(SFB), cb -> {
      Label have = cb.newLabel();
      loadSfb.accept(cb);
      cb.ifnonnull(have);
      Label isCase = cb.newLabel();
      loadCase(cb, B, o);
      cb.loadConstant(f.s().number()).if_icmpeq(isCase);
      cb.aload(0).invokestatic(X, "getDefaultInstance", mt(X)).putfield(B, o.valueField(), CD_Object);
      cb.labelBinding(isCase);
      cb.aload(0).new_(SFB).dup();
      loadValue(cb, B, o);
      cb.checkcast(CD.GENERATED_MESSAGE)
          .aload(0).invokevirtual(B, "getParentForChildren", mt(CD.GM_BUILDER_PARENT))
          .aload(0).invokevirtual(B, "isClean", mt(CD.Z))
          .invokespecial(SFB, INIT_NAME, mt(CD.V, CD.GENERATED_MESSAGE, CD.GM_BUILDER_PARENT, CD.Z))
          .putfield(B, f.fb(), SFB);
      cb.aload(0).aconst_null().putfield(B, o.valueField(), CD_Object);
      cb.labelBinding(have);
      cb.aload(0).loadConstant(f.s().number()).putfield(B, o.caseField(), CD.I);
      w.onChanged(cb);
      loadSfb.accept(cb);
      cb.areturn();
    });
  }

  void clearOneof(Cls c, O o) {
    ClassDesc B = m.builder;
    c.method("clear" + o.cap(), PUBLIC, mt(B), cb -> {
      clearCase(cb, B, o);
      w.onChanged(cb);
      returnThis(cb);
    });
  }

  /** The nested {@code FooCase} enum of a oneof. */
  static byte[] writeCaseEnum(O o, Hierarchy h, Gen gen) {
    ClassDesc self = o.caseEnum();
    ClassDesc arr = self.arrayType();
    ClassDesc enumCd = ClassDesc.of("java.lang.Enum");
    List<String> consts = new ArrayList<>();
    List<Integer> numbers = new ArrayList<>();
    for (F f : o.members()) {
      consts.add(f.fd().getName().toUpperCase(java.util.Locale.ROOT));
      numbers.add(f.s().number());
    }
    consts.add(o.notSet());
    numbers.add(0);
    return h.classFile().build(self, clb -> {
      clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_ENUM);
      clb.withSuperclass(enumCd);
      clb.withInterfaceSymbols(CD.ENUM_LITE, CD.ONEOF_ENUM);
      gen.innerClasses(clb, self);
      gen.nest(clb, self);
      Cls c = new Cls(self, clb);
      c.classSignature("Ljava/lang/Enum<" + self.descriptorString() + ">;"
          + CD.ENUM_LITE.descriptorString() + CD.ONEOF_ENUM.descriptorString());
      int constFlags = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_ENUM;
      for (String n : consts) clb.withField(n, self, constFlags);
      clb.withField("value", CD.I, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
      clb.withField("$VALUES", arr, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
      clb.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_STATIC, cb -> {
        for (int i = 0; i < consts.size(); i++) {
          cb.new_(self).dup().ldc(consts.get(i)).loadConstant(i).loadConstant(numbers.get(i))
              .invokespecial(self, INIT_NAME, mt(CD.V, CD.STRING, CD.I, CD.I))
              .putstatic(self, consts.get(i), self);
        }
        cb.loadConstant(consts.size()).anewarray(self);
        for (int i = 0; i < consts.size(); i++) {
          cb.dup().loadConstant(i).getstatic(self, consts.get(i), self).aastore();
        }
        cb.putstatic(self, "$VALUES", arr).return_();
      });
      clb.withMethodBody(INIT_NAME, mt(CD.V, CD.STRING, CD.I, CD.I), ClassFile.ACC_PRIVATE, cb -> cb
          .aload(0).aload(1).iload(2).invokespecial(enumCd, INIT_NAME, mt(CD.V, CD.STRING, CD.I))
          .aload(0).iload(3).putfield(self, "value", CD.I).return_());
      c.method("values", PUBLIC | STATIC, mt(arr), cb -> cb
          .getstatic(self, "$VALUES", arr).invokevirtual(arr, "clone", mt(CD_Object)).checkcast(arr).areturn());
      c.method("valueOf", PUBLIC | STATIC, mt(self, CD.STRING), cb -> cb
          .ldc(self).aload(0).invokestatic(enumCd, "valueOf", mt(enumCd, CD.CLASS, CD.STRING))
          .checkcast(self).areturn());
      c.method("valueOf", PUBLIC | STATIC, mt(self, CD.I), cb -> cb
          .iload(0).invokestatic(self, "forNumber", mt(self, CD.I)).areturn());
      c.method("forNumber", PUBLIC | STATIC, mt(self, CD.I), cb -> {
        Label dflt = cb.newLabel();
        List<SwitchCase> cases = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        for (int i = 0; i < consts.size(); i++) {
          Label l = cb.newLabel();
          labels.add(l);
          cases.add(SwitchCase.of(numbers.get(i), l));
        }
        cases.sort((a, b) -> Integer.compare(a.caseValue(), b.caseValue()));
        cb.iload(0).lookupswitch(dflt, cases);
        for (int i = 0; i < consts.size(); i++) {
          cb.labelBinding(labels.get(i));
          cb.getstatic(self, consts.get(i), self).areturn();
        }
        cb.labelBinding(dflt);
        cb.aconst_null().areturn();
      });
      c.method("getNumber", PUBLIC, mt(CD.I), cb -> cb.aload(0).getfield(self, "value", CD.I).ireturn());
      h.addMethods(self, c.decls);
    });
  }

  // ==============================================================================================
  // maps

  static ClassDesc keyType(F f) {
    Kind k = f.s().mapKey();
    if (k == Kind.STRING) return CD.STRING;
    return k.valueDesc();
  }

  /** Value type as seen by users (getFooOrDefault / putFoo). */
  static ClassDesc valueType(F f) {
    return switch (f.s().mapValue()) {
      case STRING -> CD.STRING;
      case BYTES -> CD.BYTE_STRING;
      case MESSAGE, ENUM -> f.type();
      default -> f.s().mapValue().valueDesc();
    };
  }

  /** Map<K, V> signature of the user-facing map. */
  private static String mapSig(F f, boolean rawEnumValues) {
    String v = rawEnumValues && f.s().mapValue() == Kind.ENUM ? "Ljava/lang/Integer;" : sig(valueType(f));
    return "Ljava/util/Map<" + sig(keyType(f)) + v + ">;";
  }

  private boolean enumValues(F f) {
    return f.s().mapValue() == Kind.ENUM;
  }

  DynamicConstantDesc<?> entryConst(F f) {
    ClassDesc valueClass = f.s().mapValue() == Kind.MESSAGE ? f.type() : CD_Object;
    return DynamicConstantDesc.ofNamed(
        MessageWriter.condyBsm("mapEntry", CD.MAP_ENTRY, CD.CLASS, CD.I, CD.CLASS),
        "entry_" + f.cap(), CD.MAP_ENTRY, m.msg, f.s().number(), valueClass);
  }

  private DynamicConstantDesc<?> enumConverterConst(F f) {
    String unrecognized = f.openEnum() ? "UNRECOGNIZED" : f.fd().getMessageType()
        .findFieldByNumber(2).getEnumType().getValues().get(0).getName();
    return DynamicConstantDesc.ofNamed(
        MessageWriter.condyBsm("mapEnumConverter", CD.MAP_ADAPTER_CONVERTER, CD.CLASS, CD.STRING),
        "mapConv_" + f.cap(), CD.MAP_ADAPTER_CONVERTER, f.type(), unrecognized);
  }

  private DynamicConstantDesc<?> builderConverterConst(F f) {
    return DynamicConstantDesc.ofNamed(
        MessageWriter.condyBsm("mapConverter", CD.MAP_FIELD_BUILDER_CONVERTER, CD.CLASS, CD.STRING),
        "builderConv_" + f.cap(), CD.MAP_FIELD_BUILDER_CONVERTER, m.msg, f.s().mapEntryMethod());
  }

  private boolean usesMfb(ClassDesc owner, F f) {
    return owner.equals(m.builder) && f.s().mapOfMessages();
  }

  /** Pushes {@code this.internalGetFoo()}. */
  private void internalGet(CodeBuilder cb, ClassDesc owner, F f) {
    ClassDesc t = usesMfb(owner, f) ? CD.MAP_FIELD_BUILDER : CD.MAP_FIELD;
    cb.aload(0).invokevirtual(owner, "internalGet" + f.cap(), mt(t));
  }

  /** Pushes the read-only Map with stored values (Integer for enums). */
  private void rawMap(CodeBuilder cb, ClassDesc owner, F f) {
    internalGet(cb, owner, f);
    if (usesMfb(owner, f)) {
      cb.invokevirtual(CD.MAP_FIELD_BUILDER, "getImmutableMap", mt(CD.MAP));
    } else {
      cb.invokevirtual(CD.MAP_FIELD, "getMap", mt(CD.MAP));
    }
  }

  /** map on stack -> MapAdapter over it (enum values). */
  private void adapt(CodeBuilder cb, F f, Consumer<CodeBuilder> map) {
    cb.new_(CD.MAP_ADAPTER).dup();
    map.accept(cb);
    cb.ldc(enumConverterConst(f));
    cb.invokespecial(CD.MAP_ADAPTER, INIT_NAME, mt(CD.V, CD.MAP, CD.MAP_ADAPTER_CONVERTER));
  }

  private void loadBoxedKey(CodeBuilder cb, F f) {
    ClassDesc k = keyType(f);
    cb.loadLocal(TypeKind.from(k), 1);
    box(cb, k);
  }

  private static int afterKey(F f) {
    return 1 + TypeKind.from(keyType(f)).slotSize();
  }

  private void checkKey(CodeBuilder cb, F f) {
    if (keyType(f).equals(CD.STRING)) requireNonNull(cb, 1, "map key");
  }

  void mapReadAccessors(Cls c, ClassDesc owner, boolean iface, F f) {
    int flags = iface ? PUBLIC | Cls.ABSTRACT : PUBLIC;
    String cap = f.cap();
    ClassDesc K = keyType(f), V = valueType(f);
    c.method("get" + cap + "Count", flags, mt(CD.I), MessageWriter.body(iface, cb -> {
      rawMap(cb, owner, f);
      cb.invokeinterface(CD.MAP, "size", mt(CD.I)).ireturn();
    }));
    c.method("contains" + cap, flags, mt(CD.Z, K), MessageWriter.body(iface, cb -> {
      checkKey(cb, f);
      rawMap(cb, owner, f);
      loadBoxedKey(cb, f);
      cb.invokeinterface(CD.MAP, "containsKey", mt(CD.Z, CD_Object)).ireturn();
    }));
    c.method("get" + cap, flags, mt(CD.MAP), "()" + mapSig(f, false), MessageWriter.body(iface, cb -> cb
        .aload(0).invokevirtual(owner, "get" + cap + "Map", mt(CD.MAP)).areturn()));
    c.method("get" + cap + "Map", flags, mt(CD.MAP), "()" + mapSig(f, false), MessageWriter.body(iface, cb -> {
      if (enumValues(f)) {
        adapt(cb, f, x -> rawMap(x, owner, f));
      } else {
        rawMap(cb, owner, f);
      }
      cb.areturn();
    }));
    c.method("get" + cap + "OrDefault", flags, mt(V, K, V), MessageWriter.body(iface, cb -> {
      checkKey(cb, f);
      cb.aload(0).invokevirtual(owner, "get" + cap + "Map", mt(CD.MAP));
      loadBoxedKey(cb, f);
      cb.loadLocal(TypeKind.from(V), afterKey(f));
      box(cb, V);
      cb.invokestatic(CD.RT, "mapGetOrDefault", mt(CD_Object, CD.MAP, CD_Object, CD_Object));
      unbox(cb, V);
      cb.return_(TypeKind.from(V));
    }));
    c.method("get" + cap + "OrThrow", flags, mt(V, K), MessageWriter.body(iface, cb -> {
      checkKey(cb, f);
      cb.aload(0).invokevirtual(owner, "get" + cap + "Map", mt(CD.MAP));
      loadBoxedKey(cb, f);
      cb.invokestatic(CD.RT, "mapGetOrThrow", mt(CD_Object, CD.MAP, CD_Object));
      unbox(cb, V);
      cb.return_(TypeKind.from(V));
    }));
    if (enumValues(f) && f.openEnum()) {
      c.method("get" + cap + "Value", flags, mt(CD.MAP), "()" + mapSig(f, true), MessageWriter.body(iface, cb -> cb
          .aload(0).invokevirtual(owner, "get" + cap + "ValueMap", mt(CD.MAP)).areturn()));
      c.method("get" + cap + "ValueMap", flags, mt(CD.MAP), "()" + mapSig(f, true), MessageWriter.body(iface, cb -> {
        rawMap(cb, owner, f);
        cb.areturn();
      }));
      c.method("get" + cap + "ValueOrDefault", flags, mt(CD.I, K, CD.I), MessageWriter.body(iface, cb -> {
        checkKey(cb, f);
        rawMap(cb, owner, f);
        loadBoxedKey(cb, f);
        cb.iload(afterKey(f));
        box(cb, CD.I);
        cb.invokestatic(CD.RT, "mapGetOrDefault", mt(CD_Object, CD.MAP, CD_Object, CD_Object));
        unbox(cb, CD.I);
        cb.ireturn();
      }));
      c.method("get" + cap + "ValueOrThrow", flags, mt(CD.I, K), MessageWriter.body(iface, cb -> {
        checkKey(cb, f);
        rawMap(cb, owner, f);
        loadBoxedKey(cb, f);
        cb.invokestatic(CD.RT, "mapGetOrThrow", mt(CD_Object, CD.MAP, CD_Object));
        unbox(cb, CD.I);
        cb.ireturn();
      }));
    }
  }

  /** Private internalGetFoo() (and on the message, the static $fooEntry()). */
  void mapPrivates(Cls c, ClassDesc owner, F f) {
    boolean mfb = usesMfb(owner, f);
    ClassDesc t = mfb ? CD.MAP_FIELD_BUILDER : CD.MAP_FIELD;
    if (owner.equals(m.msg)) {
      c.method(f.s().mapEntryMethod(), PRIVATE | STATIC, mt(CD.MAP_ENTRY), cb -> cb.ldc(entryConst(f)).areturn());
    }
    c.method("internalGet" + f.cap(), PRIVATE, mt(t), cb -> {
      Label have = cb.newLabel();
      cb.aload(0).getfield(owner, f.field(), t).ifnonnull(have);
      if (mfb) {
        cb.new_(t).dup().ldc(builderConverterConst(f))
            .invokespecial(t, INIT_NAME, mt(CD.V, CD.MAP_FIELD_BUILDER_CONVERTER)).areturn();
      } else {
        cb.invokestatic(m.msg, f.s().mapEntryMethod(), mt(CD.MAP_ENTRY))
            .invokestatic(CD.MAP_FIELD, "emptyMapField", mt(CD.MAP_FIELD, CD.MAP_ENTRY)).areturn();
      }
      cb.labelBinding(have);
      cb.aload(0).getfield(owner, f.field(), t).areturn();
    });
    if (owner.equals(m.builder)) {
      c.method("internalGetMutable" + f.cap(), PRIVATE, mt(t), cb -> {
        Label have = cb.newLabel();
        cb.aload(0).getfield(owner, f.field(), t).ifnonnull(have);
        cb.aload(0);
        if (mfb) {
          cb.new_(t).dup().ldc(builderConverterConst(f))
              .invokespecial(t, INIT_NAME, mt(CD.V, CD.MAP_FIELD_BUILDER_CONVERTER));
        } else {
          cb.invokestatic(m.msg, f.s().mapEntryMethod(), mt(CD.MAP_ENTRY))
              .invokestatic(CD.MAP_FIELD, "newMapField", mt(CD.MAP_FIELD, CD.MAP_ENTRY));
        }
        cb.putfield(owner, f.field(), t);
        cb.labelBinding(have);
        if (!mfb) {
          Label mutable = cb.newLabel();
          cb.aload(0).getfield(owner, f.field(), t).invokevirtual(t, "isMutable", mt(CD.Z)).ifne(mutable);
          cb.aload(0).aload(0).getfield(owner, f.field(), t).invokevirtual(t, "copy", mt(t))
              .putfield(owner, f.field(), t);
          cb.labelBinding(mutable);
        }
        w.onChanged(cb);
        cb.aload(0).getfield(owner, f.field(), t).areturn();
      });
    }
  }

  /** protected internalGetMapFieldReflection / internalGetMutableMapFieldReflection overrides. */
  void mapReflection(Cls c, ClassDesc owner, boolean mutable) {
    List<F> maps = new ArrayList<>();
    for (F f : m.fields) if (f.isMap()) maps.add(f);
    if (maps.isEmpty()) return;
    String name = mutable ? "internalGetMutableMapFieldReflection" : "internalGetMapFieldReflection";
    c.method(name, PROTECTED, mt(CD.MAP_FIELD_REFLECTION, CD.I), cb -> {
      Label dflt = cb.newLabel();
      List<SwitchCase> cases = new ArrayList<>();
      List<Label> labels = new ArrayList<>();
      for (F f : maps) {
        Label l = cb.newLabel();
        labels.add(l);
        cases.add(SwitchCase.of(f.s().number(), l));
      }
      List<SwitchCase> sorted = new ArrayList<>(cases);
      sorted.sort((a, b) -> Integer.compare(a.caseValue(), b.caseValue()));
      cb.iload(1).lookupswitch(dflt, sorted);
      for (int i = 0; i < maps.size(); i++) {
        F f = maps.get(i);
        cb.labelBinding(labels.get(i));
        ClassDesc t = usesMfb(owner, f) ? CD.MAP_FIELD_BUILDER : CD.MAP_FIELD;
        cb.aload(0).invokevirtual(owner, (mutable ? "internalGetMutable" : "internalGet") + f.cap(), mt(t)).areturn();
      }
      cb.labelBinding(dflt);
      cb.iload(1).invokestatic(CD.RT, "invalidMapField", mt(ClassDesc.of("java.lang.RuntimeException"), CD.I)).athrow();
    });
  }

  /** Pushes the builder's mutable Map (stored values; for maps of messages, the builder map). */
  private void mutableMap(CodeBuilder cb, F f) {
    ClassDesc B = m.builder;
    boolean mfb = f.s().mapOfMessages();
    cb.aload(0).invokevirtual(B, "internalGetMutable" + f.cap(), mt(mfb ? CD.MAP_FIELD_BUILDER : CD.MAP_FIELD));
    if (mfb) {
      cb.invokevirtual(CD.MAP_FIELD_BUILDER, "ensureBuilderMap", mt(CD.MAP));
    } else {
      cb.invokevirtual(CD.MAP_FIELD, "getMutableMap", mt(CD.MAP));
    }
  }

  void mapMutators(Cls c, F f) {
    ClassDesc B = m.builder;
    String cap = f.cap();
    ClassDesc K = keyType(f), V = valueType(f);
    boolean mfb = f.s().mapOfMessages();
    String putAllSig = "(" + mapSig(f, false) + ")" + B.descriptorString();
    c.method("clear" + cap, PUBLIC, mt(B), cb -> {
      if (mfb) {
        cb.aload(0).invokevirtual(B, "internalGetMutable" + cap, mt(CD.MAP_FIELD_BUILDER))
            .invokevirtual(CD.MAP_FIELD_BUILDER, "clear", mt(CD.V));
      } else {
        mutableMap(cb, f);
        cb.invokeinterface(CD.MAP, "clear", mt(CD.V));
      }
      returnThis(cb);
    });
    c.method("remove" + cap, PUBLIC, mt(B, K), cb -> {
      checkKey(cb, f);
      mutableMap(cb, f);
      loadBoxedKey(cb, f);
      cb.invokeinterface(CD.MAP, "remove", mt(CD_Object, CD_Object)).pop();
      returnThis(cb);
    });
    c.method("getMutable" + cap, PUBLIC, mt(CD.MAP), "()" + mapSig(f, false), cb -> {
      if (mfb) {
        cb.aload(0).invokevirtual(B, "internalGetMutable" + cap, mt(CD.MAP_FIELD_BUILDER))
            .invokevirtual(CD.MAP_FIELD_BUILDER, "ensureMessageMap", mt(CD.MAP));
      } else if (enumValues(f)) {
        adapt(cb, f, x -> mutableMap(x, f));
      } else {
        mutableMap(cb, f);
      }
      cb.areturn();
    });
    c.method("put" + cap, PUBLIC, mt(B, K, V), cb -> {
      checkKey(cb, f);
      int vs = afterKey(f);
      if (!V.isPrimitive() && !enumValues(f)) requireNonNull(cb, vs, "map value");
      mutableMap(cb, f);
      loadBoxedKey(cb, f);
      if (enumValues(f)) {
        cb.ldc(enumConverterConst(f)).aload(vs)
            .invokeinterface(CD.MAP_ADAPTER_CONVERTER, "doBackward", mt(CD_Object, CD_Object));
      } else {
        cb.loadLocal(TypeKind.from(V), vs);
        box(cb, V);
      }
      cb.invokeinterface(CD.MAP, "put", mt(CD_Object, CD_Object, CD_Object)).pop();
      returnThis(cb);
    });
    c.method("putAll" + cap, PUBLIC, mt(B, CD.MAP), putAllSig, cb -> {
      if (mfb) cb.aload(1).invokestatic(CD.RT, "requireEntriesNonNull", mt(CD.V, CD.MAP));
      if (enumValues(f)) {
        adapt(cb, f, x -> mutableMap(x, f));
      } else {
        mutableMap(cb, f);
      }
      cb.aload(1).invokeinterface(CD.MAP, "putAll", mt(CD.V, CD.MAP));
      returnThis(cb);
    });
    if (enumValues(f) && f.openEnum()) {
      c.method("getMutable" + cap + "Value", PUBLIC, mt(CD.MAP), "()" + mapSig(f, true), cb -> {
        mutableMap(cb, f);
        cb.areturn();
      });
      c.method("put" + cap + "Value", PUBLIC, mt(B, K, CD.I), cb -> {
        checkKey(cb, f);
        mutableMap(cb, f);
        loadBoxedKey(cb, f);
        cb.iload(afterKey(f));
        box(cb, CD.I);
        cb.invokeinterface(CD.MAP, "put", mt(CD_Object, CD_Object, CD_Object)).pop();
        returnThis(cb);
      });
      c.method("putAll" + cap + "Value", PUBLIC, mt(B, CD.MAP), "(" + mapSig(f, true) + ")" + B.descriptorString(), cb -> {
        mutableMap(cb, f);
        cb.aload(1).invokeinterface(CD.MAP, "putAll", mt(CD.V, CD.MAP));
        returnThis(cb);
      });
    }
    if (mfb) {
      c.method("put" + cap + "BuilderIfAbsent", PUBLIC, mt(f.typeBuilder(), K), cb -> {
        mutableMap(cb, f);
        loadBoxedKey(cb, f);
        cb.invokestatic(f.type(), "getDefaultInstance", mt(f.type()))
            .invokestatic(CD.RT, "builderIfAbsent", mt(CD.MESSAGE_BUILDER, CD.MAP, CD_Object, CD.MESSAGE))
            .checkcast(f.typeBuilder()).areturn();
      });
    }
  }

}
