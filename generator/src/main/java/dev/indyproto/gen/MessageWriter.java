package dev.indyproto.gen;

import static dev.indyproto.gen.Cls.FINAL;
import static dev.indyproto.gen.Cls.PRIVATE;
import static dev.indyproto.gen.Cls.PROTECTED;
import static dev.indyproto.gen.Cls.PUBLIC;
import static dev.indyproto.gen.Cls.STATIC;
import static dev.indyproto.gen.Cls.mt;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import dev.indyproto.gen.Model.F;
import dev.indyproto.runtime.CD;
import dev.indyproto.runtime.Schema;
import dev.indyproto.runtime.Schema.Kind;
import dev.indyproto.runtime.Schema.ListKind;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Writes the message class, its Builder and its OrBuilder interface. */
final class MessageWriter {
  private final Model m;
  private final Names names;
  private final Hierarchy h;
  private final Gen gen;
  private final ClassDesc outer;
  private final List<Integer> path;
  private final OneofMapWriter om;

  MessageWriter(Model m, Names names, Hierarchy h, Gen gen, ClassDesc outer, List<Integer> path) {
    this.m = m;
    this.names = names;
    this.h = h;
    this.gen = gen;
    this.outer = outer;
    this.path = path;
    this.om = new OneofMapWriter(m, this);
  }

  // ---- constant-pool constants --------------------------------------------------------------

  static DirectMethodHandleDesc condyBsm(String name, ClassDesc ret, ClassDesc... extra) {
    return ConstantDescs.ofConstantBootstrap(CD.BOOTSTRAPS, name, ret, extra);
  }

  private static final ClassDesc OBJ_ARR = CD_Object.arrayType();

  DynamicConstantDesc<?> descriptorConst() {
    List<ConstantDesc> args = new ArrayList<>();
    args.add(outer);
    args.addAll(path);
    return DynamicConstantDesc.ofNamed(
        condyBsm("messageDescriptor", CD.DESCRIPTOR, CD.CLASS, OBJ_ARR),
        "descriptor", CD.DESCRIPTOR, args.toArray(ConstantDesc[]::new));
  }

  private DynamicConstantDesc<?> tableConst() {
    StringBuilder camel = new StringBuilder();
    for (F f : m.fields) {
      if (!camel.isEmpty()) camel.append(',');
      camel.append(f.cap());
    }
    for (String o : m.allOneofNames) {
      if (!camel.isEmpty()) camel.append(',');
      camel.append(o);
    }
    return DynamicConstantDesc.ofNamed(
        condyBsm("accessorTable", CD.GM_FIELD_ACCESSOR_TABLE, CD.CLASS, CD.CLASS, CD.STRING),
        "table", CD.GM_FIELD_ACCESSOR_TABLE, m.msg, m.builder, camel.toString());
  }

  private DynamicConstantDesc<?> defaultConst() {
    return DynamicConstantDesc.ofNamed(condyBsm("defaultInstance", CD.OBJECT),
        "default", m.msg);
  }

  private DynamicConstantDesc<?> parserConst() {
    return DynamicConstantDesc.ofNamed(condyBsm("parser", CD.PARSER, CD.CLASS),
        "parser", CD.PARSER, m.msg);
  }

  DynamicConstantDesc<?> converterConst(F f) {
    ClassDesc conv = ClassDesc.of("com.google.protobuf.Internal$IntListAdapter$IntConverter");
    return DynamicConstantDesc.ofNamed(condyBsm("enumConverter", conv, CD.CLASS, CD.STRING),
        "conv_" + f.cap(), conv, f.type(), f.openEnum() ? "UNRECOGNIZED" : "");
  }

  private void indy(CodeBuilder cb, String op, MethodTypeDesc type) {
    cb.invokedynamic(DynamicCallSiteDesc.of(
        ConstantDescs.ofCallsiteBootstrap(CD.BOOTSTRAPS, "bootstrap", ConstantDescs.CD_CallSite, CD.STRING),
        op, type, m.schema.encode()));
  }

  // ---- signature helpers ----------------------------------------------------------------------

  static String d(ClassDesc c) {
    return c.descriptorString();
  }

  static String listOf(String elem) {
    return "Ljava/util/List<" + elem + ">;";
  }

  // ---- registration (hierarchy is needed before any class is written) -------------------------

  private ClassDesc superMsg() {
    return m.extendable() ? CD.EXTENDABLE_MESSAGE : CD.GENERATED_MESSAGE;
  }

  private ClassDesc superBuilder() {
    return m.extendable() ? CD.EXTENDABLE_BUILDER : CD.GM_BUILDER;
  }

  private ClassDesc orBuilderSuper() {
    return m.extendable() ? CD.EXTENDABLE_MESSAGE_OR_BUILDER : CD.MESSAGE_OR_BUILDER;
  }

  void register() {
    h.register(m.msg, superMsg(), List.of(m.orBuilder), false);
    h.register(m.builder, superBuilder(), List.of(m.orBuilder), false);
    h.register(m.orBuilder, CD_Object, List.of(orBuilderSuper()), true);
  }

  // ==============================================================================================
  // OrBuilder interface

  byte[] writeOrBuilder() {
    return h.classFile().build(m.orBuilder, clb -> {
      clb.withFlags(AccessFlag.PUBLIC, AccessFlag.INTERFACE, AccessFlag.ABSTRACT);
      clb.withInterfaceSymbols(orBuilderSuper());
      gen.innerClasses(clb, m.orBuilder);
      Cls c = new Cls(m.orBuilder, clb);
      if (m.extendable()) {
        c.classSignature("Ljava/lang/Object;" + d(CD.EXTENDABLE_MESSAGE_OR_BUILDER).replace(";", "<" + d(m.msg) + ">;"));
      }
      readAccessors(c, null, true);
      h.addMethods(m.orBuilder, c.decls);
    });
  }

  /**
   * Read-side accessors shared by the message, the builder and the OrBuilder interface.
   * {@code owner == null} declares abstract interface methods.
   */
  private void readAccessors(Cls c, ClassDesc owner, boolean iface) {
    int flags = iface ? PUBLIC | Cls.ABSTRACT : PUBLIC;
    boolean isBuilder = owner != null && owner.equals(m.builder);
    for (F f : m.fields) {
      if (f.isMap()) {
        om.mapReadAccessors(c, owner, iface, f);
        continue;
      }
      if (f.inOneof()) {
        om.oneofReadAccessors(c, owner, iface, f);
        continue;
      }
      String cap = f.cap();
      ClassDesc vt = Model.valueType(f);
      if (!f.repeated()) {
        if (f.presence()) {
          c.method("has" + cap, flags, mt(CD.Z), body(iface, cb -> {
            bitTest(cb, owner, f);
            Label no = cb.newLabel();
            cb.ifeq(no).iconst_1().ireturn().labelBinding(no);
            cb.iconst_0().ireturn();
          }));
        }
        switch (f.kind()) {
          case STRING -> {
            c.method("get" + cap, flags, mt(CD.STRING), body(iface, cb -> {
              int r = cb.allocateLocal(TypeKind.REFERENCE);
              cb.aload(0).getfield(owner, f.field(), CD_Object).astore(r);
              Label conv = cb.newLabel();
              cb.aload(r).instanceOf(CD.STRING).ifeq(conv);
              cb.aload(r).checkcast(CD.STRING).areturn();
              cb.labelBinding(conv);
              int s = cb.allocateLocal(TypeKind.REFERENCE);
              cb.aload(r).checkcast(CD.BYTE_STRING).invokevirtual(CD.BYTE_STRING, "toStringUtf8", mt(CD.STRING)).astore(s);
              if (f.s().utf8()) {
                cb.aload(0).aload(s).putfield(owner, f.field(), CD_Object);
              } else {
                Label skip = cb.newLabel();
                cb.aload(r).checkcast(CD.BYTE_STRING).invokevirtual(CD.BYTE_STRING, "isValidUtf8", mt(CD.Z)).ifeq(skip);
                cb.aload(0).aload(s).putfield(owner, f.field(), CD_Object);
                cb.labelBinding(skip);
              }
              cb.aload(s).areturn();
            }));
            c.method("get" + cap + "Bytes", flags, mt(CD.BYTE_STRING), body(iface, cb -> {
              int r = cb.allocateLocal(TypeKind.REFERENCE);
              cb.aload(0).getfield(owner, f.field(), CD_Object)
                  .invokestatic(CD.RT, "toBytesValue", mt(CD.BYTE_STRING, CD_Object)).astore(r);
              cb.aload(0).aload(r).putfield(owner, f.field(), CD_Object);
              cb.aload(r).areturn();
            }));
          }
          case ENUM -> {
            if (f.openEnum()) {
              c.method("get" + cap + "Value", flags, mt(CD.I), body(iface, cb ->
                  cb.aload(0).getfield(owner, f.field(), CD.I).ireturn()));
            }
            c.method("get" + cap, flags, mt(f.type()), body(iface, cb -> {
              cb.aload(0).getfield(owner, f.field(), CD.I);
              enumFromNumber(cb, f);
              cb.areturn();
            }));
          }
          case MESSAGE -> {
            c.method("get" + cap, flags, mt(f.type()), body(iface, cb -> {
              if (isBuilder) {
                Label noB = cb.newLabel();
                cb.aload(0).getfield(owner, f.fb(), CD.SINGLE_FIELD_BUILDER).ifnull(noB);
                cb.aload(0).getfield(owner, f.fb(), CD.SINGLE_FIELD_BUILDER)
                    .invokevirtual(CD.SINGLE_FIELD_BUILDER, "getMessage", mt(CD.GENERATED_MESSAGE))
                    .checkcast(f.type()).areturn();
                cb.labelBinding(noB);
              }
              messageOrDefault(cb, owner, f);
              cb.areturn();
            }));
            c.method("get" + cap + "OrBuilder", flags, mt(f.typeOrBuilder()), body(iface, cb -> {
              if (isBuilder) {
                Label noB = cb.newLabel();
                cb.aload(0).getfield(owner, f.fb(), CD.SINGLE_FIELD_BUILDER).ifnull(noB);
                cb.aload(0).getfield(owner, f.fb(), CD.SINGLE_FIELD_BUILDER)
                    .invokevirtual(CD.SINGLE_FIELD_BUILDER, "getMessageOrBuilder", mt(CD.MESSAGE_OR_BUILDER))
                    .checkcast(f.typeOrBuilder()).areturn();
                cb.labelBinding(noB);
              }
              messageOrDefault(cb, owner, f);
              cb.areturn();
            }));
          }
          default -> c.method("get" + cap, flags, mt(vt), body(iface, cb -> {
            cb.aload(0).getfield(owner, f.field(), f.storage());
            cb.return_(TypeKind.from(vt));
          }));
        }
        continue;
      }
      // ---------------- repeated
      boolean rfb = isBuilder && f.kind() == Kind.MESSAGE;
      Consumer<CodeBuilder> loadList = cb -> cb.aload(0).getfield(owner, f.field(), f.storage());
      switch (f.kind()) {
        case STRING -> {
          ClassDesc ret = iface ? CD.LIST : CD.PROTOCOL_STRING_LIST;
          c.method("get" + cap + "List", flags, mt(ret), iface ? "()" + listOf("Ljava/lang/String;") : null,
              body(iface, cb -> {
                loadList.accept(cb);
                if (isBuilder) {
                  cb.dup().invokevirtual(CD.LAZY_STRING_LIST, "makeImmutable", mt(CD.V));
                }
                cb.areturn();
              }));
          c.method("get" + cap, flags, mt(CD.STRING, CD.I), body(iface, cb -> {
            loadList.accept(cb);
            cb.iload(1).invokevirtual(CD.LAZY_STRING_LIST, "get", mt(CD.STRING, CD.I)).areturn();
          }));
          c.method("get" + cap + "Bytes", flags, mt(CD.BYTE_STRING, CD.I), body(iface, cb -> {
            loadList.accept(cb);
            cb.iload(1).invokevirtual(CD.LAZY_STRING_LIST, "getByteString", mt(CD.BYTE_STRING, CD.I)).areturn();
          }));
        }
        case ENUM -> {
          c.method("get" + cap + "List", flags, mt(CD.LIST), "()" + listOf(d(f.type())), body(iface, cb -> {
            ClassDesc ad = ClassDesc.of("com.google.protobuf.Internal$IntListAdapter");
            ClassDesc conv = ClassDesc.of("com.google.protobuf.Internal$IntListAdapter$IntConverter");
            cb.new_(ad).dup();
            loadList.accept(cb);
            cb.ldc(converterConst(f));
            cb.invokespecial(ad, INIT_NAME, mt(CD.V, CD.INT_LIST, conv)).areturn();
          }));
          c.method("get" + cap, flags, mt(f.type(), CD.I), body(iface, cb -> {
            loadList.accept(cb);
            cb.iload(1).invokeinterface(CD.INT_LIST, "getInt", mt(CD.I, CD.I));
            enumFromNumber(cb, f);
            cb.areturn();
          }));
          if (f.openEnum()) {
            c.method("get" + cap + "ValueList", flags, mt(CD.LIST), "()" + listOf("Ljava/lang/Integer;"),
                body(iface, cb -> {
                  loadList.accept(cb);
                  if (isBuilder) {
                    cb.invokestatic(CD.COLLECTIONS, "unmodifiableList", mt(CD.LIST, CD.LIST));
                  }
                  cb.areturn();
                }));
            c.method("get" + cap + "Value", flags, mt(CD.I, CD.I), body(iface, cb -> {
              loadList.accept(cb);
              cb.iload(1).invokeinterface(CD.INT_LIST, "getInt", mt(CD.I, CD.I)).ireturn();
            }));
          }
        }
        case MESSAGE -> {
          String et = d(f.type());
          c.method("get" + cap + "List", flags, mt(CD.LIST), "()" + listOf(et), body(iface, cb -> {
            if (rfb) {
              ifRfb(cb, f, yes -> yes.invokevirtual(CD.REPEATED_FIELD_BUILDER, "getMessageList", mt(CD.LIST)).areturn());
              loadList.accept(cb);
              cb.invokestatic(CD.COLLECTIONS, "unmodifiableList", mt(CD.LIST, CD.LIST)).areturn();
            } else {
              loadList.accept(cb);
              cb.areturn();
            }
          }));
          c.method("get" + cap + "OrBuilderList", flags, mt(CD.LIST),
              "()" + listOf("+" + d(f.typeOrBuilder())), body(iface, cb -> {
                if (rfb) {
                  ifRfb(cb, f, yes -> yes.invokevirtual(CD.REPEATED_FIELD_BUILDER, "getMessageOrBuilderList", mt(CD.LIST)).areturn());
                  loadList.accept(cb);
                  cb.invokestatic(CD.COLLECTIONS, "unmodifiableList", mt(CD.LIST, CD.LIST)).areturn();
                } else {
                  loadList.accept(cb);
                  cb.areturn();
                }
              }));
          c.method("get" + cap, flags, mt(f.type(), CD.I), body(iface, cb -> {
            if (rfb) {
              ifRfb(cb, f, yes -> yes.iload(1).invokevirtual(CD.REPEATED_FIELD_BUILDER, "getMessage",
                  mt(CD.GENERATED_MESSAGE, CD.I)).checkcast(f.type()).areturn());
            }
            loadList.accept(cb);
            cb.iload(1).invokeinterface(CD.LIST, "get", mt(CD_Object, CD.I)).checkcast(f.type()).areturn();
          }));
          c.method("get" + cap + "OrBuilder", flags, mt(f.typeOrBuilder(), CD.I), body(iface, cb -> {
            if (rfb) {
              ifRfb(cb, f, yes -> yes.iload(1).invokevirtual(CD.REPEATED_FIELD_BUILDER, "getMessageOrBuilder",
                  mt(CD.MESSAGE_OR_BUILDER, CD.I)).checkcast(f.typeOrBuilder()).areturn());
            }
            loadList.accept(cb);
            cb.iload(1).invokeinterface(CD.LIST, "get", mt(CD_Object, CD.I)).checkcast(f.typeOrBuilder()).areturn();
          }));
        }
        case BYTES -> {
          c.method("get" + cap + "List", flags, mt(CD.LIST), "()" + listOf(d(CD.BYTE_STRING)), body(iface, cb -> {
            loadList.accept(cb);
            if (isBuilder) cb.invokestatic(CD.RT, "makeImmutable", mt(CD.LIST, CD.LIST));
            cb.areturn();
          }));
          c.method("get" + cap, flags, mt(CD.BYTE_STRING, CD.I), body(iface, cb -> {
            loadList.accept(cb);
            cb.iload(1).invokeinterface(CD.LIST, "get", mt(CD_Object, CD.I)).checkcast(CD.BYTE_STRING).areturn();
          }));
        }
        default -> {
          ClassDesc box = Model.boxed(f.kind().valueDesc());
          c.method("get" + cap + "List", flags, mt(CD.LIST), "()" + listOf(d(box)), body(iface, cb -> {
            loadList.accept(cb);
            if (isBuilder) cb.dup().invokeinterface(CD.PROTOBUF_LIST, "makeImmutable", mt(CD.V));
            cb.areturn();
          }));
          c.method("get" + cap, flags, mt(f.kind().valueDesc(), CD.I), body(iface, cb -> {
            loadList.accept(cb);
            cb.iload(1).invokeinterface(f.kind().list.desc(), "get" + f.kind().list.accessorSuffix,
                mt(f.kind().valueDesc(), CD.I));
            cb.return_(TypeKind.from(f.kind().valueDesc()));
          }));
        }
      }
      c.method("get" + cap + "Count", flags, mt(CD.I), body(iface, cb -> {
        if (rfb) {
          ifRfb(cb, f, yes -> yes.invokevirtual(CD.REPEATED_FIELD_BUILDER, "getCount", mt(CD.I)).ireturn());
        }
        loadList.accept(cb);
        if (f.kind().list == ListKind.STRING) {
          cb.invokevirtual(CD.LAZY_STRING_LIST, "size", mt(CD.I));
        } else {
          cb.invokeinterface(f.storage(), "size", mt(CD.I));
        }
        cb.ireturn();
      }));
    }
    for (Model.O o : m.oneofs) om.caseGetter(c, owner, iface, o);
  }

  static Consumer<CodeBuilder> body(boolean iface, Consumer<CodeBuilder> code) {
    return iface ? null : code;
  }

  /** if (this.xBuilder_ != null) { yes(builder on stack) } */
  private void ifRfb(CodeBuilder cb, F f, Consumer<CodeBuilder> yes) {
    Label no = cb.newLabel();
    cb.aload(0).getfield(m.builder, f.fb(), CD.REPEATED_FIELD_BUILDER).ifnull(no);
    cb.aload(0).getfield(m.builder, f.fb(), CD.REPEATED_FIELD_BUILDER);
    yes.accept(cb);
    cb.labelBinding(no);
  }

  private static void bitTest(CodeBuilder cb, ClassDesc owner, F f) {
    cb.aload(0).getfield(owner, Schema.bitFieldName(f.s().bit()), CD.I)
        .loadConstant(1 << (f.s().bit() % 32)).iand();
  }

  private static void setBit(CodeBuilder cb, ClassDesc owner, F f) {
    String n = Schema.bitFieldName(f.s().bit());
    cb.aload(0).dup().getfield(owner, n, CD.I).loadConstant(1 << (f.s().bit() % 32)).ior()
        .putfield(owner, n, CD.I);
  }

  private static void clearBit(CodeBuilder cb, ClassDesc owner, F f) {
    String n = Schema.bitFieldName(f.s().bit());
    cb.aload(0).dup().getfield(owner, n, CD.I).loadConstant(~(1 << (f.s().bit() % 32))).iand()
        .putfield(owner, n, CD.I);
  }

  /** int on stack -> enum constant (UNRECOGNIZED / first value when unknown). */
  void enumFromNumber(CodeBuilder cb, F f) {
    cb.invokestatic(f.type(), "forNumber", mt(f.type(), CD.I));
    cb.dup();
    Label ok = cb.newLabel();
    cb.ifnonnull(ok);
    cb.pop();
    String fallback = f.openEnum() ? "UNRECOGNIZED"
        : f.repeated() ? f.fd().getEnumType().getValues().get(0).getName()
        : ((com.google.protobuf.Descriptors.EnumValueDescriptor) f.fd().getDefaultValue()).getName();
    cb.getstatic(f.type(), fallback, f.type());
    cb.labelBinding(ok);
  }

  private static void messageOrDefault(CodeBuilder cb, ClassDesc owner, F f) {
    Label nonNull = cb.newLabel();
    Label end = cb.newLabel();
    cb.aload(0).getfield(owner, f.field(), f.type()).ifnonnull(nonNull);
    cb.invokestatic(f.type(), "getDefaultInstance", mt(f.type())).goto_(end);
    cb.labelBinding(nonNull);
    cb.aload(0).getfield(owner, f.field(), f.type());
    cb.labelBinding(end);
  }

  // ==============================================================================================
  // message class

  byte[] writeMessage() {
    return h.classFile().build(m.msg, clb -> {
      clb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL, AccessFlag.SUPER);
      clb.withSuperclass(superMsg());
      clb.withInterfaceSymbols(m.orBuilder);
      gen.innerClasses(clb, m.msg);
      gen.nest(clb, m.msg);
      Cls c = new Cls(m.msg, clb);
      if (m.extendable()) {
        c.classSignature(d(CD.EXTENDABLE_MESSAGE).replace(";", "<" + d(m.msg) + ">;") + d(m.orBuilder));
        extensionHooks(c);
      }

      clb.withField("serialVersionUID", CD.J, fb -> fb
          .withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
          .with(java.lang.classfile.attribute.ConstantValueAttribute.of(0L)));
      // Java serialization (GeneratedMessageLite.SerializedForm.readResolve) finds the default
      // instance reflectively through a static field with this exact name, so keep one. It is
      // filled from the same condy constant that getDefaultInstance() returns.
      clb.withField("DEFAULT_INSTANCE", m.msg,
          ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
      Extensions ex = new Extensions(names);
      for (var ext : m.d.getExtensions()) ex.declare(clb, ext);
      clb.withMethodBody(ConstantDescs.CLASS_INIT_NAME, MTD_void, ClassFile.ACC_STATIC, cb -> {
        cb.ldc(defaultConst()).putstatic(m.msg, "DEFAULT_INSTANCE", m.msg);
        ex.initMessageScoped(cb, m.msg, m.d.getExtensions());
        cb.return_();
      });
      for (int w = 0; w < m.schema.bitWords(); w++) {
        clb.withField("bitField" + w + "_", CD.I, ClassFile.ACC_PRIVATE);
      }
      for (F f : m.fields) {
        clb.withField(f.fd().getName().toUpperCase(java.util.Locale.ROOT) + "_FIELD_NUMBER", CD.I, fb -> fb
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
            .with(java.lang.classfile.attribute.ConstantValueAttribute.of(f.s().number())));
        if (f.inOneof()) continue;
        int flags = ClassFile.ACC_PRIVATE;
        if (!f.repeated() && f.kind() == Kind.STRING) flags |= ClassFile.ACC_VOLATILE;
        clb.withField(f.field(), f.storage(), flags);
        if (f.s().packed()) clb.withField(f.s().memoFieldName(), CD.I, ClassFile.ACC_PRIVATE);
        if (f.isMap()) om.mapPrivates(c, m.msg, f);
      }
      for (Model.O o : m.oneofs) {
        clb.withField(o.caseField(), CD.I, ClassFile.ACC_PRIVATE);
        clb.withField(o.valueField(), CD_Object, ClassFile.ACC_PRIVATE);
      }
      om.mapReflection(c, m.msg, false);

      // constructors
      c.method(INIT_NAME, PRIVATE, mt(CD.V, CD.GM_BUILDER), cb -> cb
          .aload(0).aload(1).checkcast(m.extendable() ? CD.EXTENDABLE_BUILDER : CD.GM_BUILDER)
          .invokespecial(superMsg(), INIT_NAME, mt(CD.V, m.extendable() ? CD.EXTENDABLE_BUILDER : CD.GM_BUILDER))
          .aload(0).iconst_m1().putfield(m.msg, "memoizedIsInitialized", CD.B)
          .return_());
      c.method(INIT_NAME, PRIVATE, MTD_void, cb -> {
        cb.aload(0).invokespecial(superMsg(), INIT_NAME, MTD_void);
        cb.aload(0).iconst_m1().putfield(m.msg, "memoizedIsInitialized", CD.B);
        initDefaults(cb, m.msg);
        cb.return_();
      });

      c.method("getDescriptor", PUBLIC | STATIC | FINAL, mt(CD.DESCRIPTOR), cb ->
          cb.ldc(descriptorConst()).areturn());
      c.method("internalGetFieldAccessorTable", PROTECTED, mt(CD.GM_FIELD_ACCESSOR_TABLE), cb ->
          cb.ldc(tableConst()).areturn());
      c.method("getDescriptorForType", PUBLIC, mt(CD.DESCRIPTOR), cb ->
          cb.ldc(descriptorConst()).areturn());

      readAccessors(c, m.msg, false);

      clb.withField("memoizedIsInitialized", CD.B, ClassFile.ACC_PRIVATE);
      c.method("isInitialized", PUBLIC | FINAL, mt(CD.Z), cb -> isInitialized(cb, m.msg, true));

      c.method("writeTo", PUBLIC, mt(CD.V, CD.COS), null, List.of(CD.IO_EXCEPTION), cb -> {
        cb.aload(0).invokevirtual(m.msg, "getSerializedSize", mt(CD.I)).pop();
        cb.aload(0).aload(1);
        indy(cb, "writeTo", mt(CD.V, m.msg, CD.COS));
        cb.return_();
      });
      c.method("getSerializedSize", PUBLIC, mt(CD.I), cb -> {
        Label compute = cb.newLabel();
        cb.aload(0).getfield(m.msg, "memoizedSize", CD.I).iconst_m1().if_icmpeq(compute);
        cb.aload(0).getfield(m.msg, "memoizedSize", CD.I).ireturn();
        cb.labelBinding(compute);
        cb.aload(0).aload(0);
        indy(cb, "serializedSize", mt(CD.I, m.msg));
        cb.dup_x1().putfield(m.msg, "memoizedSize", CD.I).ireturn();
      });
      c.method("equals", PUBLIC, mt(CD.Z, CD_Object), cb -> {
        Label notSame = cb.newLabel();
        Label isMsg = cb.newLabel();
        cb.aload(1).aload(0).if_acmpne(notSame).iconst_1().ireturn();
        cb.labelBinding(notSame);
        cb.aload(1).instanceOf(m.msg).ifne(isMsg);
        cb.aload(0).aload(1).invokespecial(CD.GENERATED_MESSAGE, "equals",
            mt(CD.Z, CD_Object)).ireturn();
        cb.labelBinding(isMsg);
        cb.aload(0).aload(1).checkcast(m.msg);
        indy(cb, "equals", mt(CD.Z, m.msg, m.msg));
        cb.ireturn();
      });
      c.method("hashCode", PUBLIC, mt(CD.I), cb -> {
        Label compute = cb.newLabel();
        cb.aload(0).getfield(m.msg, "memoizedHashCode", CD.I).ifeq(compute);
        cb.aload(0).getfield(m.msg, "memoizedHashCode", CD.I).ireturn();
        cb.labelBinding(compute);
        cb.aload(0).aload(0);
        indy(cb, "hashCode", mt(CD.I, m.msg));
        cb.dup_x1().putfield(m.msg, "memoizedHashCode", CD.I).ireturn();
      });

      parseMethods(c);

      c.method("newBuilderForType", PUBLIC, mt(m.builder), cb ->
          cb.invokestatic(m.msg, "newBuilder", mt(m.builder)).areturn());
      c.method("newBuilder", PUBLIC | STATIC, mt(m.builder), cb -> cb
          .invokestatic(m.msg, "getDefaultInstance", mt(m.msg))
          .invokevirtual(m.msg, "toBuilder", mt(m.builder)).areturn());
      c.method("newBuilder", PUBLIC | STATIC, mt(m.builder, m.msg), cb -> cb
          .invokestatic(m.msg, "getDefaultInstance", mt(m.msg))
          .invokevirtual(m.msg, "toBuilder", mt(m.builder))
          .aload(0).invokevirtual(m.builder, "mergeFrom", mt(m.builder, m.msg)).areturn());
      c.method("toBuilder", PUBLIC, mt(m.builder), cb -> {
        Label notDefault = cb.newLabel();
        cb.aload(0).invokestatic(m.msg, "getDefaultInstance", mt(m.msg)).if_acmpne(notDefault);
        cb.new_(m.builder).dup().invokespecial(m.builder, INIT_NAME, MTD_void).areturn();
        cb.labelBinding(notDefault);
        cb.new_(m.builder).dup().invokespecial(m.builder, INIT_NAME, MTD_void)
            .aload(0).invokevirtual(m.builder, "mergeFrom", mt(m.builder, m.msg)).areturn();
      });
      c.method("newBuilderForType", PROTECTED, mt(m.builder, CD.GM_BUILDER_PARENT), cb -> cb
          .new_(m.builder).dup().aload(1)
          .invokespecial(m.builder, INIT_NAME, mt(CD.V, CD.GM_BUILDER_PARENT)).areturn());
      c.method("getDefaultInstance", PUBLIC | STATIC, mt(m.msg), cb -> cb.ldc(defaultConst()).areturn());
      c.method("getDefaultInstanceForType", PUBLIC, mt(m.msg), cb -> cb.ldc(defaultConst()).areturn());
      String parserSig = "()Lcom/google/protobuf/Parser<" + d(m.msg) + ">;";
      c.method("parser", PUBLIC | STATIC, mt(CD.PARSER), parserSig, cb -> cb.ldc(parserConst()).areturn());
      c.method("getParserForType", PUBLIC, mt(CD.PARSER), parserSig, cb -> cb.ldc(parserConst()).areturn());

      h.addMethods(m.msg, c.decls);
      h.emitBridges(c);
    });
  }

  private void initDefaults(CodeBuilder cb, ClassDesc owner) {
    for (F f : m.fields) {
      if (f.inOneof() || f.isMap()) continue;
      if (!f.repeated() && f.kind() != Kind.STRING && f.kind() != Kind.BYTES && !f.s().customDefault()) continue;
      cb.aload(0);
      pushDefault(cb, f);
      cb.putfield(owner, f.field(), f.storage());
    }
  }

  /** An explicit proto2 {@code [default = ...]}, as protoc's field initializers spell it. */
  static void pushCustomDefault(CodeBuilder cb, F f) {
    Object v = f.fd().getDefaultValue();
    switch (f.kind()) {
      case STRING -> cb.ldc((String) v);
      case BYTES -> cb.ldc(new String(((com.google.protobuf.ByteString) v).toByteArray(),
              java.nio.charset.StandardCharsets.ISO_8859_1))
          .invokestatic(CD.INTERNAL, "bytesDefaultValue", mt(CD.BYTE_STRING, CD.STRING));
      case ENUM -> cb.loadConstant(((com.google.protobuf.Descriptors.EnumValueDescriptor) v).getNumber());
      case BOOL -> cb.loadConstant((Boolean) v ? 1 : 0);
      default -> cb.loadConstant((java.lang.constant.ConstantDesc) v);
    }
  }

  static void pushDefault(CodeBuilder cb, F f) {
    if (f.repeated()) {
      switch (f.kind().list) {
        case STRING -> cb.invokestatic(CD.LAZY_STRING_LIST, "emptyList", mt(CD.LAZY_STRING_LIST));
        case OBJECT -> cb.invokestatic(CD.COLLECTIONS, "emptyList", mt(CD.LIST));
        default -> cb.invokestatic(CD.RT, "empty" + f.kind().list.internalListName, mt(f.kind().list.desc()));
      }
      return;
    }
    if (f.s().customDefault()) {
      pushCustomDefault(cb, f);
      return;
    }
    switch (f.kind()) {
      case STRING -> cb.ldc("");
      case BYTES -> cb.getstatic(CD.BYTE_STRING, "EMPTY", CD.BYTE_STRING);
      case MESSAGE -> cb.aconst_null();
      default -> {
        switch (f.kind().jvm) {
          case 'J' -> cb.lconst_0();
          case 'F' -> cb.fconst_0();
          case 'D' -> cb.dconst_0();
          default -> cb.iconst_0();
        }
      }
    }
  }

  private void parseMethods(Cls c) {
    List<ClassDesc> ipbe = List.of(CD.IPBE);
    List<ClassDesc> ioe = List.of(CD.IO_EXCEPTION);
    ClassDesc[][] direct = {{CD.BYTE_BUFFER}, {CD.BYTE_STRING}, {CD.B.arrayType()}};
    for (ClassDesc[] in : direct) {
      c.method("parseFrom", PUBLIC | STATIC, mt(m.msg, in[0]), null, ipbe, cb -> cb
          .ldc(parserConst()).aload(0)
          .invokeinterface(CD.PARSER, "parseFrom", mt(CD_Object, in[0])).checkcast(m.msg).areturn());
      c.method("parseFrom", PUBLIC | STATIC, mt(m.msg, in[0], CD.ERL), null, ipbe, cb -> cb
          .ldc(parserConst()).aload(0).aload(1)
          .invokeinterface(CD.PARSER, "parseFrom", mt(CD_Object, in[0], CD.ERL)).checkcast(m.msg).areturn());
    }
    String[][] viaGm = {
        {"parseFrom", "parseWithIOException"},
        {"parseDelimitedFrom", "parseDelimitedWithIOException"},
        {"parseFrom", "parseWithIOException"}};
    ClassDesc[] src = {CD.INPUT_STREAM, CD.INPUT_STREAM, CD.CIS};
    for (int i = 0; i < 3; i++) {
      String name = viaGm[i][0], helper = viaGm[i][1];
      ClassDesc s = src[i];
      c.method(name, PUBLIC | STATIC, mt(m.msg, s), null, ioe, cb -> cb
          .ldc(parserConst()).aload(0)
          .invokestatic(CD.GENERATED_MESSAGE, helper, mt(CD.MESSAGE, CD.PARSER, s))
          .checkcast(m.msg).areturn());
      c.method(name, PUBLIC | STATIC, mt(m.msg, s, CD.ERL), null, ioe, cb -> cb
          .ldc(parserConst()).aload(0).aload(1)
          .invokestatic(CD.GENERATED_MESSAGE, helper, mt(CD.MESSAGE, CD.PARSER, s, CD.ERL))
          .checkcast(m.msg).areturn());
    }
  }

  // ==============================================================================================
  // Builder

  byte[] writeBuilder() {
    ClassDesc B = m.builder;
    return h.classFile().build(B, clb -> {
      clb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL, AccessFlag.SUPER);
      clb.withSuperclass(superBuilder());
      clb.withInterfaceSymbols(m.orBuilder);
      gen.innerClasses(clb, B);
      gen.nest(clb, B);
      Cls c = new Cls(B, clb);
      c.classSignature(m.extendable()
          ? d(CD.EXTENDABLE_BUILDER).replace(";", "<" + d(m.msg) + d(B) + ">;") + d(m.orBuilder)
          : "Lcom/google/protobuf/GeneratedMessage$Builder<" + d(B) + ">;" + d(m.orBuilder));
      if (m.extendable()) builderExtensionMethods(c);

      for (int w = 0; w < m.schema.bitWords(); w++) {
        clb.withField("bitField" + w + "_", CD.I, ClassFile.ACC_PRIVATE);
      }
      for (F f : m.fields) {
        if (f.isMap()) {
          clb.withField(f.field(), f.s().builderStorageDesc(), ClassFile.ACC_PRIVATE);
          om.mapPrivates(c, B, f);
          continue;
        }
        if (!f.inOneof()) clb.withField(f.field(), f.storage(), ClassFile.ACC_PRIVATE);
        if (f.kind() == Kind.MESSAGE) {
          clb.withField(f.fb(), f.repeated() ? CD.REPEATED_FIELD_BUILDER : CD.SINGLE_FIELD_BUILDER,
              ClassFile.ACC_PRIVATE);
        }
      }

      c.method("getDescriptor", PUBLIC | STATIC | FINAL, mt(CD.DESCRIPTOR), cb ->
          cb.invokestatic(m.msg, "getDescriptor", mt(CD.DESCRIPTOR)).areturn());
      c.method("internalGetFieldAccessorTable", PROTECTED, mt(CD.GM_FIELD_ACCESSOR_TABLE), cb ->
          cb.ldc(tableConst()).areturn());
      c.method(INIT_NAME, PRIVATE, MTD_void, cb -> {
        cb.aload(0).invokespecial(superBuilder(), INIT_NAME, MTD_void);
        initDefaults(cb, B);
        cb.return_();
      });
      c.method(INIT_NAME, PRIVATE, mt(CD.V, CD.GM_BUILDER_PARENT), cb -> {
        cb.aload(0).aload(1).invokespecial(superBuilder(), INIT_NAME, mt(CD.V, CD.GM_BUILDER_PARENT));
        initDefaults(cb, B);
        cb.return_();
      });
      c.method("clear", PUBLIC, mt(B), cb -> {
        cb.aload(0).invokespecial(superBuilder(), "clear", mt(superBuilder())).pop();
        cb.aload(0);
        indy(cb, "clear", mt(CD.V, B));
        cb.aload(0).areturn();
      });
      c.method("getDescriptorForType", PUBLIC, mt(CD.DESCRIPTOR), cb ->
          cb.invokestatic(m.msg, "getDescriptor", mt(CD.DESCRIPTOR)).areturn());
      c.method("getDefaultInstanceForType", PUBLIC, mt(m.msg), cb ->
          cb.invokestatic(m.msg, "getDefaultInstance", mt(m.msg)).areturn());
      c.method("build", PUBLIC, mt(m.msg), cb -> {
        cb.aload(0).invokevirtual(B, "buildPartial", mt(m.msg));
        if (mayHaveRequired(m.d)) {
          Label ok = cb.newLabel();
          cb.dup().invokevirtual(m.msg, "isInitialized", mt(CD.Z)).ifne(ok);
          cb.invokestatic(ClassDesc.of("com.google.protobuf.AbstractMessage$Builder"), "newUninitializedMessageException",
              mt(ClassDesc.of("com.google.protobuf.UninitializedMessageException"), CD.MESSAGE)).athrow();
          cb.labelBinding(ok);
        }
        cb.areturn();
      });
      c.method("buildPartial", PUBLIC, mt(m.msg), cb -> {
        int r = cb.allocateLocal(TypeKind.REFERENCE);
        cb.new_(m.msg).dup().aload(0).invokespecial(m.msg, INIT_NAME, mt(CD.V, CD.GM_BUILDER)).astore(r);
        cb.aload(0).aload(r);
        indy(cb, "buildPartial", mt(CD.V, B, m.msg));
        cb.aload(0).invokevirtual(B, "onBuilt", mt(CD.V));
        cb.aload(r).areturn();
      });
      c.method("mergeFrom", PUBLIC, mt(B, CD.MESSAGE), cb -> {
        Label other = cb.newLabel();
        cb.aload(1).instanceOf(m.msg).ifeq(other);
        cb.aload(0).aload(1).checkcast(m.msg).invokevirtual(B, "mergeFrom", mt(B, m.msg)).areturn();
        cb.labelBinding(other);
        cb.aload(0).aload(1).invokespecial(CD.GM_BUILDER, "mergeFrom",
            mt(ClassDesc.of("com.google.protobuf.AbstractMessage$Builder"), CD.MESSAGE)).pop();
        cb.aload(0).areturn();
      });
      c.method("mergeFrom", PUBLIC, mt(B, m.msg), cb -> {
        Label go = cb.newLabel();
        cb.aload(1).invokestatic(m.msg, "getDefaultInstance", mt(m.msg)).if_acmpne(go);
        cb.aload(0).areturn();
        cb.labelBinding(go);
        cb.aload(0).aload(1);
        indy(cb, "mergeFromMessage", mt(CD.V, B, m.msg));
        cb.aload(0).areturn();
      });
      c.method("isInitialized", PUBLIC | FINAL, mt(CD.Z), cb -> isInitialized(cb, B, false));
      c.method("mergeFrom", PUBLIC, mt(B, CD.CIS, CD.ERL), null, List.of(CD.IO_EXCEPTION), cb -> {
        Label ok = cb.newLabel();
        cb.aload(2).ifnonnull(ok);
        cb.new_(ClassDesc.of("java.lang.NullPointerException")).dup()
            .invokespecial(ClassDesc.of("java.lang.NullPointerException"), INIT_NAME, MTD_void).athrow();
        cb.labelBinding(ok);
        cb.aload(0).aload(1).aload(2);
        indy(cb, "mergeFrom", mt(CD.V, B, CD.CIS, CD.ERL));
        cb.aload(0).areturn();
      });

      // private hooks for link-time spun nestmates (which are not subclasses of GeneratedMessage.Builder)
      c.method("$onChanged", PRIVATE, mt(CD.V), cb -> cb.aload(0).invokevirtual(B, "onChanged", mt(CD.V)).return_());
      c.method("$parseUnknown", PRIVATE, mt(CD.Z, CD.CIS, CD.ERL, CD.I), cb -> cb
          .aload(0).aload(1).aload(2).iload(3)
          .invokevirtual(B, "parseUnknownField", mt(CD.Z, CD.CIS, CD.ERL, CD.I)).ireturn());
      c.method("$unknownVarint", PRIVATE, mt(CD.V, CD.I, CD.I), cb -> cb
          .aload(0).iload(1).iload(2).invokevirtual(B, "mergeUnknownVarintField", mt(CD.V, CD.I, CD.I)).return_());

      c.method("$unknownLengthDelimited", PRIVATE, mt(CD.V, CD.I, CD.BYTE_STRING), cb -> cb
          .aload(0).iload(1).aload(2)
          .invokevirtual(B, "mergeUnknownLengthDelimitedField", mt(CD.V, CD.I, CD.BYTE_STRING)).return_());
      for (Model.O o : m.oneofs) {
        clb.withField(o.caseField(), CD.I, ClassFile.ACC_PRIVATE);
        clb.withField(o.valueField(), CD_Object, ClassFile.ACC_PRIVATE);
      }
      om.mapReflection(c, B, false);
      om.mapReflection(c, B, true);

      readAccessors(c, B, false);
      builderMutators(c);
      for (Model.O o : m.oneofs) om.clearOneof(c, o);

      h.addMethods(B, c.decls);
      h.emitBridges(c);
    });
  }

  /**
   * protoc's isInitialized(): required fields set, sub-messages that may have required fields
   * initialized, extensions initialized; memoized on the message.
   */
  private void isInitialized(CodeBuilder cb, ClassDesc owner, boolean memo) {
    Label no = cb.newLabel();
    String memoField = "memoizedIsInitialized";
    if (memo) {
      Label compute = cb.newLabel(), notOne = cb.newLabel();
      cb.aload(0).getfield(owner, memoField, CD.B).iconst_1().if_icmpne(notOne);
      cb.iconst_1().ireturn();
      cb.labelBinding(notOne);
      cb.aload(0).getfield(owner, memoField, CD.B).ifne(compute);
      cb.iconst_0().ireturn();
      cb.labelBinding(compute);
    }
    for (F f : m.fields) {
      if (f.fd().isRequired()) {
        cb.aload(0).invokevirtual(owner, "has" + f.cap(), mt(CD.Z)).ifeq(no);
      }
    }
    for (F f : m.fields) {
      if (f.isMap()) {
        if (f.s().mapValue() != Kind.MESSAGE || !mayHaveRequired(f.fd().getMessageType().findFieldByNumber(2).getMessageType())) continue;
        cb.aload(0).invokevirtual(owner, "get" + f.cap() + "Map", mt(CD.MAP))
            .invokeinterface(CD.MAP, "values", mt(ClassDesc.of("java.util.Collection")))
            .invokestatic(CD.RT, "allInitialized", mt(CD.Z, ClassDesc.of("java.util.Collection"))).ifeq(no);
        continue;
      }
      if (f.kind() != Kind.MESSAGE || !mayHaveRequired(f.fd().getMessageType())) continue;
      if (f.repeated()) {
        cb.aload(0).invokevirtual(owner, "get" + f.cap() + "List", mt(CD.LIST))
            .invokestatic(CD.RT, "allInitialized", mt(CD.Z, ClassDesc.of("java.util.Collection"))).ifeq(no);
      } else {
        Label skip = cb.newLabel();
        cb.aload(0).invokevirtual(owner, "has" + f.cap(), mt(CD.Z)).ifeq(skip);
        cb.aload(0).invokevirtual(owner, "get" + f.cap(), mt(f.type()))
            .invokeinterface(CD.MESSAGE_LITE_OR_BUILDER, "isInitialized", mt(CD.Z)).ifeq(no);
        cb.labelBinding(skip);
      }
    }
    if (m.extendable()) {
      cb.aload(0).invokevirtual(owner, "extensionsAreInitialized", mt(CD.Z)).ifeq(no);
    }
    if (memo) cb.aload(0).iconst_1().putfield(owner, memoField, CD.B);
    cb.iconst_1().ireturn();
    cb.labelBinding(no);
    if (memo) cb.aload(0).iconst_0().putfield(owner, memoField, CD.B);
    cb.iconst_0().ireturn();
  }

  /** protoc's HasRequiredFields: required fields reachable through message fields, or extensions. */
  static boolean mayHaveRequired(com.google.protobuf.Descriptors.Descriptor d) {
    return mayHaveRequired(d, new java.util.HashSet<>());
  }

  private static boolean mayHaveRequired(com.google.protobuf.Descriptors.Descriptor d,
      java.util.Set<com.google.protobuf.Descriptors.Descriptor> seen) {
    if (!seen.add(d)) return false;
    if (!d.toProto().getExtensionRangeList().isEmpty()) return true;
    for (var f : d.getFields()) {
      if (f.isRequired()) return true;
      if (f.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE
          && mayHaveRequired(f.getMessageType(), seen)) return true;
    }
    return false;
  }

  /** Private hooks so link-time nestmates can reach the protected extension machinery. */
  private void extensionHooks(Cls c) {
    ClassDesc M = m.msg, EM = CD.EXTENDABLE_MESSAGE;
    c.method("$extWriter", PRIVATE, mt(CD_Object), cb -> cb
        .aload(0).invokevirtual(M, "newExtensionSerializer", mt(CD.EXTENSION_SERIALIZER)).areturn());
    c.method("$extWriteUntil", PRIVATE, mt(CD.V, CD_Object, CD.I, CD.COS), null, List.of(CD.IO_EXCEPTION), cb -> cb
        .aload(1).checkcast(CD.EXTENSION_SERIALIZER).iload(2).aload(3)
        .invokeinterface(CD.EXTENSION_SERIALIZER, "writeUntil", mt(CD.V, CD.I, CD.COS)).return_());
    c.method("$extSize", PRIVATE, mt(CD.I), cb -> cb
        .aload(0).invokevirtual(M, "extensionsSerializedSize", mt(CD.I)).ireturn());
    c.method("$extEquals", PRIVATE, mt(CD.Z, M), cb -> cb
        .aload(0).aload(1).invokevirtual(M, "extensionsEquals", mt(CD.Z, EM)).ireturn());
    c.method("$extHash", PRIVATE, mt(CD.I, CD.I), cb -> cb
        .iload(1).aload(0).invokevirtual(M, "getExtensionFields", mt(CD.MAP))
        .invokestatic(M, "hashFields", mt(CD.I, CD.I, CD.MAP)).ireturn());
  }

  /** protoc's typed setExtension / addExtension / clearExtension overloads, and the merge hook. */
  private void builderExtensionMethods(Cls c) {
    ClassDesc B = m.builder, GE = CD.GENERATED_EXTENSION, EB = CD.EXTENDABLE_BUILDER;
    ClassDesc ext = ClassDesc.of("com.google.protobuf.ExtensionLite");
    String ge = "Lcom/google/protobuf/GeneratedMessage$GeneratedExtension<" + d(m.msg);
    String single = ge + "TType;>;", list = ge + "Ljava/util/List<TType;>;>;";
    c.method("setExtension", PUBLIC, mt(B, GE, CD_Object),
        "<Type:Ljava/lang/Object;>(" + single + "TType;)" + d(B), cb -> cb
            .aload(0).aload(1).aload(2)
            .invokespecial(EB, "setExtension", mt(EB, ext, CD_Object)).checkcast(B).areturn());
    c.method("setExtension", PUBLIC, mt(B, GE, CD.I, CD_Object),
        "<Type:Ljava/lang/Object;>(" + list + "ITType;)" + d(B), cb -> cb
            .aload(0).aload(1).iload(2).aload(3)
            .invokespecial(EB, "setExtension", mt(EB, ext, CD.I, CD_Object)).checkcast(B).areturn());
    c.method("addExtension", PUBLIC, mt(B, GE, CD_Object),
        "<Type:Ljava/lang/Object;>(" + list + "TType;)" + d(B), cb -> cb
            .aload(0).aload(1).aload(2)
            .invokespecial(EB, "addExtension", mt(EB, ext, CD_Object)).checkcast(B).areturn());
    c.method("clearExtension", PUBLIC, mt(B, GE),
        "<Type:Ljava/lang/Object;>(" + single + ")" + d(B), cb -> cb
            .aload(0).aload(1)
            .invokespecial(EB, "clearExtension", mt(EB, ext)).checkcast(B).areturn());
    c.method("$mergeExtensions", PRIVATE, mt(CD.V, m.msg), cb -> cb
        .aload(0).aload(1).invokevirtual(B, "mergeExtensionFields", mt(CD.V, CD.EXTENDABLE_MESSAGE)).return_());
  }

  void onChanged(CodeBuilder cb) {
    cb.aload(0).invokevirtual(m.builder, "onChanged", mt(CD.V));
  }

  void requireNonNull(CodeBuilder cb, int slot) {
    cb.aload(slot).invokestatic(CD.OBJECTS, "requireNonNull", mt(CD_Object, CD_Object)).pop();
  }

  private void ensure(CodeBuilder cb, F f) {
    cb.aload(0).invokevirtual(m.builder, "ensure" + f.cap() + "IsMutable", mt(CD.V));
  }

  private void builderMutators(Cls c) {
    ClassDesc B = m.builder;
    for (F f : m.fields) {
      if (f.isMap()) {
        om.mapMutators(c, f);
        continue;
      }
      if (f.inOneof()) {
        om.oneofBuilderMutators(c, f);
        continue;
      }
      String cap = f.cap();
      ClassDesc vt = Model.valueType(f);
      if (!f.repeated()) {
        switch (f.kind()) {
          case MESSAGE -> singularMessageMutators(c, f);
          case ENUM -> {
            if (f.openEnum()) {
              c.method("set" + cap + "Value", PUBLIC, mt(B, CD.I), cb -> {
                cb.aload(0).iload(1).putfield(B, f.field(), CD.I);
                if (f.presence()) setBit(cb, B, f);
                onChanged(cb);
                cb.aload(0).areturn();
              });
            }
            c.method("set" + cap, PUBLIC, mt(B, f.type()), cb -> {
              requireNonNull(cb, 1);
              cb.aload(0).aload(1).invokevirtual(f.type(), "getNumber", mt(CD.I)).putfield(B, f.field(), CD.I);
              if (f.presence()) setBit(cb, B, f);
              onChanged(cb);
              cb.aload(0).areturn();
            });
            simpleClear(c, f);
          }
          default -> {
            c.method("set" + cap, PUBLIC, mt(B, vt), cb -> {
              TypeKind tk = TypeKind.from(vt);
              if (tk == TypeKind.REFERENCE) requireNonNull(cb, 1);
              cb.aload(0).loadLocal(tk, 1).putfield(B, f.field(), f.storage());
              if (f.presence()) setBit(cb, B, f);
              onChanged(cb);
              cb.aload(0).areturn();
            });
            simpleClear(c, f);
            if (f.kind() == Kind.STRING) {
              c.method("set" + cap + "Bytes", PUBLIC, mt(B, CD.BYTE_STRING), cb -> {
                requireNonNull(cb, 1);
                if (f.s().utf8()) cb.aload(1).invokestatic(CD.RT, "checkUtf8", mt(CD.V, CD.BYTE_STRING));
                cb.aload(0).aload(1).putfield(B, f.field(), CD_Object);
                if (f.presence()) setBit(cb, B, f);
                onChanged(cb);
                cb.aload(0).areturn();
              });
            }
          }
        }
        continue;
      }
      // ----- repeated
      if (f.kind() == Kind.MESSAGE) {
        repeatedMessageMutators(c, f);
        continue;
      }
      c.method("ensure" + cap + "IsMutable", PRIVATE, mt(CD.V), cb -> {
        switch (f.kind().list) {
          case STRING -> {
            Label ok = cb.newLabel();
            cb.aload(0).getfield(B, f.field(), f.storage()).invokevirtual(CD.LAZY_STRING_LIST, "isModifiable", mt(CD.Z)).ifne(ok);
            cb.aload(0).new_(CD.LAZY_STRING_LIST).dup().aload(0).getfield(B, f.field(), f.storage())
                .invokespecial(CD.LAZY_STRING_LIST, INIT_NAME, mt(CD.V, ClassDesc.of("com.google.protobuf.LazyStringList")))
                .putfield(B, f.field(), f.storage());
            cb.labelBinding(ok);
          }
          case OBJECT -> cb.aload(0).aload(0).getfield(B, f.field(), f.storage())
              .invokestatic(CD.RT, "ensureMutable", mt(CD.LIST, CD.LIST)).putfield(B, f.field(), f.storage());
          default -> {
            Label ok = cb.newLabel();
            cb.aload(0).getfield(B, f.field(), f.storage()).invokeinterface(CD.PROTOBUF_LIST, "isModifiable", mt(CD.Z)).ifne(ok);
            cb.aload(0).aload(0).getfield(B, f.field(), f.storage())
                .invokestatic(CD.RT, "mutableCopy", mt(CD.PROTOBUF_LIST, CD.PROTOBUF_LIST))
                .checkcast(f.storage()).putfield(B, f.field(), f.storage());
            cb.labelBinding(ok);
          }
        }
        cb.return_();
      });
      // element type as seen by setX/addX
      ClassDesc et = f.kind() == Kind.ENUM ? f.type() : vt;
      c.method("set" + cap, PUBLIC, mt(B, CD.I, et), cb -> {
        TypeKind tk = TypeKind.from(et);
        if (tk == TypeKind.REFERENCE) requireNonNull(cb, 2);
        ensure(cb, f);
        cb.aload(0).getfield(B, f.field(), f.storage()).iload(1).loadLocal(tk, 2);
        storeElement(cb, f, true);
        onChanged(cb);
        cb.aload(0).areturn();
      });
      c.method("add" + cap, PUBLIC, mt(B, et), cb -> {
        TypeKind tk = TypeKind.from(et);
        if (tk == TypeKind.REFERENCE) requireNonNull(cb, 1);
        ensure(cb, f);
        cb.aload(0).getfield(B, f.field(), f.storage()).loadLocal(tk, 1);
        storeElement(cb, f, false);
        onChanged(cb);
        cb.aload(0).areturn();
      });
      String iterSig = switch (f.kind()) {
        case STRING -> "(Ljava/lang/Iterable<Ljava/lang/String;>;)" + d(B);
        case ENUM -> "(Ljava/lang/Iterable<+" + d(f.type()) + ">;)" + d(B);
        case BYTES -> "(Ljava/lang/Iterable<+" + d(CD.BYTE_STRING) + ">;)" + d(B);
        default -> "(Ljava/lang/Iterable<+" + d(Model.boxed(f.kind().valueDesc())) + ">;)" + d(B);
      };
      c.method("addAll" + cap, PUBLIC, mt(B, CD.ITERABLE), iterSig, cb -> {
        ensure(cb, f);
        if (f.kind() == Kind.ENUM) {
          cb.aload(1).aload(0).getfield(B, f.field(), f.storage())
              .invokestatic(CD.RT, "addAllEnums", mt(CD.V, CD.ITERABLE, CD.INT_LIST));
        } else {
          cb.aload(1).aload(0).getfield(B, f.field(), f.storage())
              .invokestatic(CD.ABSTRACT_MESSAGE_LITE_BUILDER, "addAll", mt(CD.V, CD.ITERABLE, CD.LIST));
        }
        onChanged(cb);
        cb.aload(0).areturn();
      });
      c.method("clear" + cap, PUBLIC, mt(B), cb -> {
        cb.aload(0);
        pushDefault(cb, f);
        cb.putfield(B, f.field(), f.storage());
        onChanged(cb);
        cb.aload(0).areturn();
      });
      if (f.kind() == Kind.STRING) {
        c.method("add" + cap + "Bytes", PUBLIC, mt(B, CD.BYTE_STRING), cb -> {
          requireNonNull(cb, 1);
          if (f.s().utf8()) cb.aload(1).invokestatic(CD.RT, "checkUtf8", mt(CD.V, CD.BYTE_STRING));
          ensure(cb, f);
          cb.aload(0).getfield(B, f.field(), f.storage()).aload(1)
              .invokevirtual(CD.LAZY_STRING_LIST, "add", mt(CD.V, CD.BYTE_STRING));
          onChanged(cb);
          cb.aload(0).areturn();
        });
      }
      if (f.kind() == Kind.ENUM && f.openEnum()) {
        c.method("set" + cap + "Value", PUBLIC, mt(B, CD.I, CD.I), cb -> {
          ensure(cb, f);
          cb.aload(0).getfield(B, f.field(), f.storage()).iload(1).iload(2)
              .invokeinterface(CD.INT_LIST, "setInt", mt(CD.I, CD.I, CD.I)).pop();
          onChanged(cb);
          cb.aload(0).areturn();
        });
        c.method("add" + cap + "Value", PUBLIC, mt(B, CD.I), cb -> {
          ensure(cb, f);
          cb.aload(0).getfield(B, f.field(), f.storage()).iload(1)
              .invokeinterface(CD.INT_LIST, "addInt", mt(CD.V, CD.I));
          onChanged(cb);
          cb.aload(0).areturn();
        });
        c.method("addAll" + cap + "Value", PUBLIC, mt(B, CD.ITERABLE),
            "(Ljava/lang/Iterable<Ljava/lang/Integer;>;)" + d(B), cb -> {
              ensure(cb, f);
              cb.aload(1).aload(0).getfield(B, f.field(), f.storage())
                  .invokestatic(CD.RT, "addAllInts", mt(CD.V, CD.ITERABLE, CD.INT_LIST));
              onChanged(cb);
              cb.aload(0).areturn();
            });
      }
    }
  }

  /** stack: list, [index], value -> (); converts enum constants to numbers. */
  private void storeElement(CodeBuilder cb, F f, boolean set) {
    if (f.kind() == Kind.ENUM) {
      cb.invokevirtual(f.type(), "getNumber", mt(CD.I));
    }
    ListKind lk = f.kind().list;
    switch (lk) {
      case STRING -> {
        if (set) cb.invokevirtual(CD.LAZY_STRING_LIST, "set", mt(CD.STRING, CD.I, CD.STRING)).pop();
        else cb.invokevirtual(CD.LAZY_STRING_LIST, "add", mt(CD.Z, CD_Object)).pop();
      }
      case OBJECT -> {
        if (set) cb.invokeinterface(CD.LIST, "set", mt(CD_Object, CD.I, CD_Object)).pop();
        else cb.invokeinterface(CD.LIST, "add", mt(CD.Z, CD_Object)).pop();
      }
      default -> {
        ClassDesc v = f.kind().valueDesc();
        if (set) {
          cb.invokeinterface(lk.desc(), "set" + lk.accessorSuffix, mt(v, CD.I, v));
          if (TypeKind.from(v).slotSize() == 2) cb.pop2(); else cb.pop();
        } else {
          cb.invokeinterface(lk.desc(), "add" + lk.accessorSuffix, mt(CD.V, v));
        }
      }
    }
  }

  private void simpleClear(Cls c, F f) {
    ClassDesc B = m.builder;
    c.method("clear" + f.cap(), PUBLIC, mt(B), cb -> {
      cb.aload(0);
      pushDefault(cb, f);
      cb.putfield(B, f.field(), f.storage());
      if (f.presence()) clearBit(cb, B, f);
      onChanged(cb);
      cb.aload(0).areturn();
    });
  }

  private void singularMessageMutators(Cls c, F f) {
    ClassDesc B = m.builder, X = f.type(), XB = f.typeBuilder(), SFB = CD.SINGLE_FIELD_BUILDER;
    String cap = f.cap();
    c.method("set" + cap, PUBLIC, mt(B, X), cb -> {
      Label useB = cb.newLabel(), done = cb.newLabel();
      cb.aload(0).getfield(B, f.fb(), SFB).ifnonnull(useB);
      requireNonNull(cb, 1);
      cb.aload(0).aload(1).putfield(B, f.field(), X).goto_(done);
      cb.labelBinding(useB);
      cb.aload(0).getfield(B, f.fb(), SFB).aload(1)
          .invokevirtual(SFB, "setMessage", mt(SFB, CD.GENERATED_MESSAGE)).pop();
      cb.labelBinding(done);
      setBit(cb, B, f);
      onChanged(cb);
      cb.aload(0).areturn();
    });
    c.method("set" + cap, PUBLIC, mt(B, XB), cb -> cb
        .aload(0).aload(1).invokevirtual(XB, "build", mt(X)).invokevirtual(B, "set" + cap, mt(B, X)).areturn());
    c.method("merge" + cap, PUBLIC, mt(B, X), cb -> {
      Label useB = cb.newLabel(), replace = cb.newLabel(), done = cb.newLabel();
      cb.aload(0).getfield(B, f.fb(), SFB).ifnonnull(useB);
      bitTest(cb, B, f);
      cb.ifeq(replace);
      cb.aload(0).getfield(B, f.field(), X).ifnull(replace);
      cb.aload(0).getfield(B, f.field(), X).invokestatic(X, "getDefaultInstance", mt(X)).if_acmpeq(replace);
      cb.aload(0).aload(0).getfield(B, f.field(), X).aload(1)
          .invokestatic(CD.RT, "mergeMessage", mt(CD.MESSAGE, CD.MESSAGE, CD.MESSAGE_LITE))
          .checkcast(X).putfield(B, f.field(), X).goto_(done);
      cb.labelBinding(replace);
      requireNonNull(cb, 1);
      cb.aload(0).aload(1).putfield(B, f.field(), X).goto_(done);
      cb.labelBinding(useB);
      cb.aload(0).getfield(B, f.fb(), SFB).aload(1)
          .invokevirtual(SFB, "mergeFrom", mt(SFB, CD.GENERATED_MESSAGE)).pop();
      cb.labelBinding(done);
      setBit(cb, B, f);
      onChanged(cb);
      cb.aload(0).areturn();
    });
    c.method("clear" + cap, PUBLIC, mt(B), cb -> {
      clearBit(cb, B, f);
      cb.aload(0).aconst_null().putfield(B, f.field(), X);
      Label none = cb.newLabel();
      cb.aload(0).getfield(B, f.fb(), SFB).ifnull(none);
      cb.aload(0).getfield(B, f.fb(), SFB).invokevirtual(SFB, "dispose", mt(CD.V));
      cb.aload(0).aconst_null().putfield(B, f.fb(), SFB);
      cb.labelBinding(none);
      onChanged(cb);
      cb.aload(0).areturn();
    });
    c.method("get" + cap + "Builder", PUBLIC, mt(XB), cb -> {
      setBit(cb, B, f);
      onChanged(cb);
      cb.aload(0).invokevirtual(B, "internalGet" + cap + "FieldBuilder", mt(SFB))
          .invokevirtual(SFB, "getBuilder", mt(CD.GM_BUILDER)).checkcast(XB).areturn();
    });
    c.method("internalGet" + cap + "FieldBuilder", PRIVATE, mt(SFB), cb -> {
      Label have = cb.newLabel();
      cb.aload(0).getfield(B, f.fb(), SFB).ifnonnull(have);
      cb.aload(0).new_(SFB).dup()
          .aload(0).invokevirtual(B, "get" + cap, mt(X))
          .aload(0).invokevirtual(B, "getParentForChildren", mt(CD.GM_BUILDER_PARENT))
          .aload(0).invokevirtual(B, "isClean", mt(CD.Z))
          .invokespecial(SFB, INIT_NAME, mt(CD.V, CD.GENERATED_MESSAGE, CD.GM_BUILDER_PARENT, CD.Z))
          .putfield(B, f.fb(), SFB);
      cb.aload(0).aconst_null().putfield(B, f.field(), X);
      cb.labelBinding(have);
      cb.aload(0).getfield(B, f.fb(), SFB).areturn();
    });
  }

  private void repeatedMessageMutators(Cls c, F f) {
    ClassDesc B = m.builder, X = f.type(), XB = f.typeBuilder(), RFB = CD.REPEATED_FIELD_BUILDER;
    String cap = f.cap();
    c.method("ensure" + cap + "IsMutable", PRIVATE, mt(CD.V), cb -> cb
        .aload(0).aload(0).getfield(B, f.field(), CD.LIST)
        .invokestatic(CD.RT, "ensureMutable", mt(CD.LIST, CD.LIST)).putfield(B, f.field(), CD.LIST)
        .return_());
    // helper: if (rfb != null) { rfbOp; } else { listOp; onChanged } ; return this
    record Op(Consumer<CodeBuilder> list, Consumer<CodeBuilder> rfb) {}
    java.util.function.BiConsumer<CodeBuilder, Op> either = (cb, op) -> {
      Label useB = cb.newLabel(), done = cb.newLabel();
      cb.aload(0).getfield(B, f.fb(), RFB).ifnonnull(useB);
      op.list().accept(cb);
      onChanged(cb);
      cb.goto_(done);
      cb.labelBinding(useB);
      cb.aload(0).getfield(B, f.fb(), RFB);
      op.rfb().accept(cb);
      cb.labelBinding(done);
      cb.aload(0).areturn();
    };
    c.method("set" + cap, PUBLIC, mt(B, CD.I, X), cb -> either.accept(cb, new Op(
        l -> { requireNonNull(l, 2); ensure(l, f); l.aload(0).getfield(B, f.field(), CD.LIST).iload(1).aload(2)
            .invokeinterface(CD.LIST, "set", mt(CD_Object, CD.I, CD_Object)).pop(); },
        r -> r.iload(1).aload(2).invokevirtual(RFB, "setMessage", mt(RFB, CD.I, CD.GENERATED_MESSAGE)).pop())));
    c.method("set" + cap, PUBLIC, mt(B, CD.I, XB), cb -> cb
        .aload(0).iload(1).aload(2).invokevirtual(XB, "build", mt(X))
        .invokevirtual(B, "set" + cap, mt(B, CD.I, X)).areturn());
    c.method("add" + cap, PUBLIC, mt(B, X), cb -> either.accept(cb, new Op(
        l -> { requireNonNull(l, 1); ensure(l, f); l.aload(0).getfield(B, f.field(), CD.LIST).aload(1)
            .invokeinterface(CD.LIST, "add", mt(CD.Z, CD_Object)).pop(); },
        r -> r.aload(1).invokevirtual(RFB, "addMessage", mt(RFB, CD.GENERATED_MESSAGE)).pop())));
    c.method("add" + cap, PUBLIC, mt(B, CD.I, X), cb -> either.accept(cb, new Op(
        l -> { requireNonNull(l, 2); ensure(l, f); l.aload(0).getfield(B, f.field(), CD.LIST).iload(1).aload(2)
            .invokeinterface(CD.LIST, "add", mt(CD.V, CD.I, CD_Object)); },
        r -> r.iload(1).aload(2).invokevirtual(RFB, "addMessage", mt(RFB, CD.I, CD.GENERATED_MESSAGE)).pop())));
    c.method("add" + cap, PUBLIC, mt(B, XB), cb -> cb
        .aload(0).aload(1).invokevirtual(XB, "build", mt(X)).invokevirtual(B, "add" + cap, mt(B, X)).areturn());
    c.method("add" + cap, PUBLIC, mt(B, CD.I, XB), cb -> cb
        .aload(0).iload(1).aload(2).invokevirtual(XB, "build", mt(X))
        .invokevirtual(B, "add" + cap, mt(B, CD.I, X)).areturn());
    c.method("addAll" + cap, PUBLIC, mt(B, CD.ITERABLE), "(Ljava/lang/Iterable<+" + d(X) + ">;)" + d(B),
        cb -> either.accept(cb, new Op(
            l -> { ensure(l, f); l.aload(1).aload(0).getfield(B, f.field(), CD.LIST)
                .invokestatic(CD.ABSTRACT_MESSAGE_LITE_BUILDER, "addAll", mt(CD.V, CD.ITERABLE, CD.LIST)); },
            r -> r.aload(1).invokevirtual(RFB, "addAllMessages", mt(RFB, CD.ITERABLE)).pop())));
    c.method("clear" + cap, PUBLIC, mt(B), cb -> either.accept(cb, new Op(
        l -> l.aload(0).invokestatic(CD.COLLECTIONS, "emptyList", mt(CD.LIST)).putfield(B, f.field(), CD.LIST),
        r -> r.invokevirtual(RFB, "clear", mt(CD.V)))));
    c.method("remove" + cap, PUBLIC, mt(B, CD.I), cb -> either.accept(cb, new Op(
        l -> { ensure(l, f); l.aload(0).getfield(B, f.field(), CD.LIST).iload(1)
            .invokeinterface(CD.LIST, "remove", mt(CD_Object, CD.I)).pop(); },
        r -> r.iload(1).invokevirtual(RFB, "remove", mt(CD.V, CD.I)))));
    c.method("get" + cap + "Builder", PUBLIC, mt(XB, CD.I), cb -> cb
        .aload(0).invokevirtual(B, "internalGet" + cap + "FieldBuilder", mt(RFB)).iload(1)
        .invokevirtual(RFB, "getBuilder", mt(CD.GM_BUILDER, CD.I)).checkcast(XB).areturn());
    c.method("add" + cap + "Builder", PUBLIC, mt(XB), cb -> cb
        .aload(0).invokevirtual(B, "internalGet" + cap + "FieldBuilder", mt(RFB))
        .invokestatic(X, "getDefaultInstance", mt(X))
        .invokevirtual(RFB, "addBuilder", mt(CD.GM_BUILDER, CD.GENERATED_MESSAGE)).checkcast(XB).areturn());
    c.method("add" + cap + "Builder", PUBLIC, mt(XB, CD.I), cb -> cb
        .aload(0).invokevirtual(B, "internalGet" + cap + "FieldBuilder", mt(RFB)).iload(1)
        .invokestatic(X, "getDefaultInstance", mt(X))
        .invokevirtual(RFB, "addBuilder", mt(CD.GM_BUILDER, CD.I, CD.GENERATED_MESSAGE)).checkcast(XB).areturn());
    c.method("get" + cap + "BuilderList", PUBLIC, mt(CD.LIST), "()" + listOf(d(XB)), cb -> cb
        .aload(0).invokevirtual(B, "internalGet" + cap + "FieldBuilder", mt(RFB))
        .invokevirtual(RFB, "getBuilderList", mt(CD.LIST)).areturn());
    c.method("internalGet" + cap + "FieldBuilder", PRIVATE, mt(RFB), cb -> {
      Label have = cb.newLabel();
      cb.aload(0).getfield(B, f.fb(), RFB).ifnonnull(have);
      cb.aload(0).new_(RFB).dup()
          .aload(0).getfield(B, f.field(), CD.LIST)
          .aload(0).getfield(B, f.field(), CD.LIST).instanceOf(CD.ARRAY_LIST)
          .aload(0).invokevirtual(B, "getParentForChildren", mt(CD.GM_BUILDER_PARENT))
          .aload(0).invokevirtual(B, "isClean", mt(CD.Z))
          .invokespecial(RFB, INIT_NAME, mt(CD.V, CD.LIST, CD.Z, CD.GM_BUILDER_PARENT, CD.Z))
          .putfield(B, f.fb(), RFB);
      cb.aload(0).aconst_null().putfield(B, f.field(), CD.LIST);
      cb.labelBinding(have);
      cb.aload(0).getfield(B, f.fb(), RFB).areturn();
    });
  }
}
