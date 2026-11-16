package dev.indyproto.runtime;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import com.google.protobuf.CodedOutputStream;
import dev.indyproto.runtime.Schema.Field;
import dev.indyproto.runtime.Schema.Kind;
import dev.indyproto.runtime.Schema.ListKind;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Link-time code generation: spins one hidden nestmate class per (message type, operation) with a
 * single static method holding straight-line code equivalent to what protoc would have emitted.
 *
 * <p>The hidden class is a nestmate of the message, so it reads and writes the message's and
 * builder's private fields directly. It is not a subclass of the protobuf runtime, so it reaches
 * protected hooks through private bridges the generator emits on the builder ({@code $onChanged},
 * {@code $parseUnknown}, {@code $unknownVarint}) and through {@link Rt}.
 */
final class Spinner {

  static final String OP_METHOD = "op";

  /**
   * Experimental: emit version-50 class files without StackMapTable, so the ClassFile API skips
   * stack-map generation and HotSpot falls back to the type-inferencing verifier.
   */
  static final boolean NO_STACK_MAPS = Boolean.getBoolean("indyproto.noStackMaps");

  private final MethodHandles.Lookup lookup;
  private final ClassDesc msg;
  private final ClassDesc builder;
  private final Schema schema;

  Spinner(MethodHandles.Lookup lookup, Class<?> msgClass, Schema schema) {
    this.lookup = lookup;
    this.msg = msgClass.describeConstable().orElseThrow();
    this.builder = ClassDesc.of(msgClass.getName() + "$Builder");
    this.schema = schema;
  }

  /** Spins the class and returns a lookup on it. */
  MethodHandles.Lookup spin(String opName, MethodType type, Consumer<CodeBuilder> body) {
    return spinClass(opName, cb -> cb.withMethodBody(OP_METHOD,
        type.describeConstable().orElseThrow(),
        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, body));
  }

  MethodHandles.Lookup spinClass(String opName, Consumer<ClassBuilder> members) {
    return spinClass(opName, CD_Object, members);
  }

  MethodHandles.Lookup spinClass(String opName, ClassDesc superclass, Consumer<ClassBuilder> members) {
    ClassDesc self = ClassDesc.of(lookup.lookupClass().getName() + "$$Indy$" + opName);
    ClassFile cf = NO_STACK_MAPS
        ? ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS)
        : ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            ClassHierarchyResolver.defaultResolver()
                .orElse(ClassHierarchyResolver.ofClassLoading(lookup))));
    long t0 = System.nanoTime();
    byte[] bytes = cf.build(self, clb -> {
      if (NO_STACK_MAPS) clb.withVersion(ClassFile.JAVA_6_VERSION, 0);
      clb.withFlags(AccessFlag.FINAL, AccessFlag.SYNTHETIC);
      clb.withSuperclass(superclass);
      members.accept(clb);
    });
    long t1 = System.nanoTime();
    try {
      return lookup.defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    } finally {
      Stats.spin(t1 - t0, System.nanoTime() - t1, bytes.length);
    }
  }

  // --------------------------------------------------------------------------------------------
  // helpers

  private static MethodTypeDesc mt(ClassDesc ret, ClassDesc... params) {
    return MethodTypeDesc.of(ret, params);
  }

  private void getField(CodeBuilder cb, ClassDesc owner, int slot, Field f) {
    cb.aload(slot).getfield(owner, f.fieldName(), f.storageDesc());
  }

  /** Pushes {@code (obj.bitFieldN_ & mask)}; non-zero iff the presence bit is set. */
  private static void bitTest(CodeBuilder cb, ClassDesc owner, int slot, Field f) {
    cb.aload(slot).getfield(owner, Schema.bitFieldName(f.bit()), CD.I)
        .loadConstant(1 << (f.bit() % 32)).iand();
  }

  private static void setBit(CodeBuilder cb, ClassDesc owner, int slot, Field f) {
    String n = Schema.bitFieldName(f.bit());
    cb.aload(slot).dup().getfield(owner, n, CD.I)
        .loadConstant(1 << (f.bit() % 32)).ior().putfield(owner, n, CD.I);
  }

  /** Given the value of a singular implicit-presence field on the stack, jumps if it is default. */
  private static void ifDefault(CodeBuilder cb, Field f, Label isDefault) {
    switch (f.kind()) {
      case STRING -> cb.invokestatic(CD.RT, "isStringEmpty", mt(CD.Z, CD_Object)).ifne(isDefault);
      case BYTES -> cb.invokevirtual(CD.BYTE_STRING, "isEmpty", mt(CD.Z)).ifne(isDefault);
      case MESSAGE -> cb.ifnull(isDefault);
      default -> {
        switch (f.kind().jvm) {
          case 'J' -> cb.lconst_0().lcmp().ifeq(isDefault);
          case 'F' -> cb.invokestatic(CD.FLOAT_BOX, "floatToRawIntBits", mt(CD.I, CD.F)).ifeq(isDefault);
          case 'D' -> cb.invokestatic(CD.DOUBLE_BOX, "doubleToRawLongBits", mt(CD.J, CD.D))
              .lconst_0().lcmp().ifeq(isDefault);
          default -> cb.ifeq(isDefault);
        }
      }
    }
  }

  private static boolean isClassList(Field f) {
    return f.kind().list == ListKind.STRING; // LazyStringArrayList is a class, the rest interfaces
  }

  private static void invokeList(CodeBuilder cb, Field f, String name, MethodTypeDesc type) {
    if (isClassList(f)) {
      cb.invokevirtual(CD.LAZY_STRING_LIST, name, type);
    } else {
      cb.invokeinterface(f.kind().list.desc(), name, type);
    }
  }

  /** list on stack, index local -> element pushed in its natural write type. */
  private static void listGet(CodeBuilder cb, Field f, int index) {
    cb.iload(index);
    switch (f.kind().list) {
      case STRING -> cb.invokevirtual(CD.LAZY_STRING_LIST, "getRaw", mt(CD_Object, CD.I));
      case OBJECT -> {
        cb.invokeinterface(CD.LIST, "get", mt(CD_Object, CD.I));
        cb.checkcast(f.kind() == Kind.BYTES ? CD.BYTE_STRING : CD.MESSAGE_LITE);
      }
      default -> cb.invokeinterface(f.kind().list.desc(), "get" + f.kind().list.accessorSuffix,
          mt(f.kind().valueDesc(), CD.I));
    }
  }

  /** Emits {@code for (int i = 0; i < list.size(); i++) body(i)} with list stored in a local. */
  private static void forEach(CodeBuilder cb, Field f, Consumer<CodeBuilder> loadList,
      java.util.function.BiConsumer<Integer, Integer> body) {
    int list = cb.allocateLocal(TypeKind.REFERENCE);
    int i = cb.allocateLocal(TypeKind.INT);
    loadList.accept(cb);
    cb.astore(list);
    cb.iconst_0().istore(i);
    Label top = cb.newBoundLabel();
    Label end = cb.newLabel();
    cb.iload(i).aload(list);
    invokeList(cb, f, "size", mt(CD.I));
    cb.if_icmpge(end);
    body.accept(list, i);
    cb.iinc(i, 1).goto_(top);
    cb.labelBinding(end);
  }

  // --------------------------------------------------------------------------------------------
  // writeTo(M, CodedOutputStream)

  void writeTo(CodeBuilder cb) {
    int m = 0, out = 1;
    for (Field f : schema.fields()) {
      Label skip = cb.newLabel();
      if (f.repeated()) {
        if (f.packed()) {
          getField(cb, msg, m, f);
          invokeList(cb, f, "isEmpty", mt(CD.Z));
          cb.ifne(skip);
          cb.aload(out).loadConstant(f.packedTag()).invokevirtual(CD.COS, "writeUInt32NoTag", mt(CD.V, CD.I));
          cb.aload(out).aload(m).getfield(msg, f.memoFieldName(), CD.I)
              .invokevirtual(CD.COS, "writeUInt32NoTag", mt(CD.V, CD.I));
          forEach(cb, f, c -> getField(c, msg, m, f), (list, i) -> {
            cb.aload(out).aload(list);
            listGet(cb, f, i);
            cb.invokevirtual(CD.COS, "write" + f.kind().io + "NoTag", mt(CD.V, f.kind().valueDesc()));
          });
        } else {
          forEach(cb, f, c -> getField(c, msg, m, f), (list, i) -> {
            cb.aload(out).loadConstant(f.number()).aload(list);
            listGet(cb, f, i);
            writeTagged(cb, f);
          });
        }
      } else {
        if (f.presence()) {
          bitTest(cb, msg, m, f);
          cb.ifeq(skip);
        } else {
          getField(cb, msg, m, f);
          ifDefault(cb, f, skip);
        }
        cb.aload(out).loadConstant(f.number());
        getField(cb, msg, m, f);
        writeTagged(cb, f);
      }
      cb.labelBinding(skip);
    }
    cb.aload(m).invokevirtual(msg, "getUnknownFields", mt(CD.UNKNOWN_FIELD_SET))
        .aload(out).invokevirtual(CD.UNKNOWN_FIELD_SET, "writeTo", mt(CD.V, CD.COS));
    cb.return_();
  }

  /** stack: out, number, value -> (). */
  private static void writeTagged(CodeBuilder cb, Field f) {
    switch (f.kind()) {
      case STRING -> cb.invokestatic(CD.RT, "writeString", mt(CD.V, CD.COS, CD.I, CD_Object));
      case BYTES -> cb.invokevirtual(CD.COS, "writeBytes", mt(CD.V, CD.I, CD.BYTE_STRING));
      case MESSAGE -> cb.invokevirtual(CD.COS, "writeMessage", mt(CD.V, CD.I, CD.MESSAGE_LITE));
      default -> cb.invokevirtual(CD.COS, "write" + f.kind().io, mt(CD.V, CD.I, f.kind().valueDesc()));
    }
  }

  // --------------------------------------------------------------------------------------------
  // serializedSize(M) -> int

  void serializedSize(CodeBuilder cb) {
    int m = 0;
    int size = cb.allocateLocal(TypeKind.INT);
    cb.iconst_0().istore(size);
    for (Field f : schema.fields()) {
      Label skip = cb.newLabel();
      if (f.repeated()) {
        int data = cb.allocateLocal(TypeKind.INT);
        cb.iconst_0().istore(data);
        if (f.kind() == Kind.MESSAGE) {
          forEach(cb, f, c -> getField(c, msg, m, f), (list, i) -> {
            cb.iload(data).loadConstant(f.number()).aload(list);
            listGet(cb, f, i);
            cb.invokestatic(CD.COS, "computeMessageSize", mt(CD.I, CD.I, CD.MESSAGE_LITE));
            cb.iadd().istore(data);
          });
          cb.iload(size).iload(data).iadd().istore(size);
        } else {
          forEach(cb, f, c -> getField(c, msg, m, f), (list, i) -> {
            cb.iload(data).aload(list);
            listGet(cb, f, i);
            computeNoTag(cb, f);
            cb.iadd().istore(data);
          });
          int tagSize = CodedOutputStream.computeTagSize(f.number());
          if (f.packed()) {
            cb.iload(size).iload(data).iadd().istore(size);
            getField(cb, msg, m, f);
            invokeList(cb, f, "isEmpty", mt(CD.Z));
            Label empty = cb.newLabel();
            cb.ifne(empty);
            cb.iload(size).loadConstant(tagSize).iadd()
                .iload(data).invokestatic(CD.COS, "computeInt32SizeNoTag", mt(CD.I, CD.I))
                .iadd().istore(size);
            cb.labelBinding(empty);
            cb.aload(m).iload(data).putfield(msg, f.memoFieldName(), CD.I);
          } else {
            cb.iload(size).iload(data).iadd();
            getField(cb, msg, m, f);
            invokeList(cb, f, "size", mt(CD.I));
            cb.loadConstant(tagSize).imul().iadd().istore(size);
          }
        }
      } else {
        if (f.presence()) {
          bitTest(cb, msg, m, f);
          cb.ifeq(skip);
        } else {
          getField(cb, msg, m, f);
          ifDefault(cb, f, skip);
        }
        cb.iload(size).loadConstant(f.number());
        getField(cb, msg, m, f);
        switch (f.kind()) {
          case STRING -> cb.invokestatic(CD.RT, "computeStringSize", mt(CD.I, CD.I, CD_Object));
          case BYTES -> cb.invokestatic(CD.COS, "computeBytesSize", mt(CD.I, CD.I, CD.BYTE_STRING));
          case MESSAGE -> cb.invokestatic(CD.COS, "computeMessageSize", mt(CD.I, CD.I, CD.MESSAGE_LITE));
          default -> cb.invokestatic(CD.COS, "compute" + f.kind().io + "Size",
              mt(CD.I, CD.I, f.kind().valueDesc()));
        }
        cb.iadd().istore(size);
      }
      cb.labelBinding(skip);
    }
    cb.iload(size).aload(m).invokevirtual(msg, "getUnknownFields", mt(CD.UNKNOWN_FIELD_SET))
        .invokevirtual(CD.UNKNOWN_FIELD_SET, "getSerializedSize", mt(CD.I)).iadd().ireturn();
  }

  private static void computeNoTag(CodeBuilder cb, Field f) {
    switch (f.kind()) {
      case STRING -> cb.invokestatic(CD.RT, "computeStringSizeNoTag", mt(CD.I, CD_Object));
      case BYTES -> cb.invokestatic(CD.COS, "computeBytesSizeNoTag", mt(CD.I, CD.BYTE_STRING));
      default -> cb.invokestatic(CD.COS, "compute" + f.kind().io + "SizeNoTag",
          mt(CD.I, f.kind().valueDesc()));
    }
  }

  // --------------------------------------------------------------------------------------------
  // equals(M, M) -> boolean

  void equals(CodeBuilder cb) {
    int a = 0, b = 1;
    Label notEqual = cb.newLabel();
    for (Field f : schema.fields()) {
      Label next = cb.newLabel();
      if (f.repeated()) {
        getField(cb, msg, a, f);
        getField(cb, msg, b, f);
        invokeList(cb, f, "equals", mt(CD.Z, CD_Object));
        cb.ifeq(notEqual);
      } else {
        if (f.presence()) {
          String bf = Schema.bitFieldName(f.bit());
          cb.aload(a).getfield(msg, bf, CD.I).aload(b).getfield(msg, bf, CD.I).ixor()
              .loadConstant(1 << (f.bit() % 32)).iand().ifne(notEqual);
          bitTest(cb, msg, a, f);
          cb.ifeq(next);
        }
        switch (f.kind()) {
          case STRING -> {
            String getter = "get" + f.camelName();
            cb.aload(a).invokevirtual(msg, getter, mt(CD.STRING))
                .aload(b).invokevirtual(msg, getter, mt(CD.STRING))
                .invokevirtual(CD.STRING, "equals", mt(CD.Z, CD_Object)).ifeq(notEqual);
          }
          case BYTES -> {
            getField(cb, msg, a, f);
            getField(cb, msg, b, f);
            cb.invokevirtual(CD.BYTE_STRING, "equals", mt(CD.Z, CD_Object)).ifeq(notEqual);
          }
          case MESSAGE -> {
            String getter = "get" + f.camelName();
            cb.aload(a).invokevirtual(msg, getter, mt(f.typeDesc()))
                .aload(b).invokevirtual(msg, getter, mt(f.typeDesc()))
                .invokevirtual(f.typeDesc(), "equals", mt(CD.Z, CD_Object)).ifeq(notEqual);
          }
          default -> {
            getField(cb, msg, a, f);
            switch (f.kind().jvm) {
              case 'F' -> cb.invokestatic(CD.FLOAT_BOX, "floatToIntBits", mt(CD.I, CD.F));
              case 'D' -> cb.invokestatic(CD.DOUBLE_BOX, "doubleToLongBits", mt(CD.J, CD.D));
              default -> {}
            }
            getField(cb, msg, b, f);
            switch (f.kind().jvm) {
              case 'F' -> cb.invokestatic(CD.FLOAT_BOX, "floatToIntBits", mt(CD.I, CD.F)).if_icmpne(notEqual);
              case 'D' -> cb.invokestatic(CD.DOUBLE_BOX, "doubleToLongBits", mt(CD.J, CD.D))
                  .lcmp().ifne(notEqual);
              case 'J' -> cb.lcmp().ifne(notEqual);
              default -> cb.if_icmpne(notEqual);
            }
          }
        }
      }
      cb.labelBinding(next);
    }
    cb.aload(a).invokevirtual(msg, "getUnknownFields", mt(CD.UNKNOWN_FIELD_SET))
        .aload(b).invokevirtual(msg, "getUnknownFields", mt(CD.UNKNOWN_FIELD_SET))
        .invokevirtual(CD.UNKNOWN_FIELD_SET, "equals", mt(CD.Z, CD_Object)).ifeq(notEqual);
    cb.iconst_1().ireturn();
    cb.labelBinding(notEqual);
    cb.iconst_0().ireturn();
  }

  // --------------------------------------------------------------------------------------------
  // hashCode(M) -> int   (same formula as protoc gencode)

  void hashCode(CodeBuilder cb) {
    int m = 0;
    int h = cb.allocateLocal(TypeKind.INT);
    cb.loadConstant(19 * 41)
        .invokestatic(msg, "getDescriptor", mt(CD.DESCRIPTOR))
        .invokevirtual(CD_Object, "hashCode", mt(CD.I)).iadd().istore(h);
    for (Field f : schema.fields()) {
      Label skip = cb.newLabel();
      if (f.repeated()) {
        getField(cb, msg, m, f);
        invokeList(cb, f, "isEmpty", mt(CD.Z));
        cb.ifne(skip);
      } else if (f.presence()) {
        bitTest(cb, msg, m, f);
        cb.ifeq(skip);
      }
      // h = 53 * (37 * h + number) + valueHash
      cb.loadConstant(53).loadConstant(37).iload(h).imul().loadConstant(f.number()).iadd().imul();
      if (f.repeated()) {
        getField(cb, msg, m, f);
        invokeList(cb, f, "hashCode", mt(CD.I));
      } else {
        switch (f.kind()) {
          case STRING -> cb.aload(m).invokevirtual(msg, "get" + f.camelName(), mt(CD.STRING))
              .invokevirtual(CD.STRING, "hashCode", mt(CD.I));
          case MESSAGE -> cb.aload(m).invokevirtual(msg, "get" + f.camelName(), mt(f.typeDesc()))
              .invokevirtual(f.typeDesc(), "hashCode", mt(CD.I));
          case BYTES -> {
            getField(cb, msg, m, f);
            cb.invokevirtual(CD.BYTE_STRING, "hashCode", mt(CD.I));
          }
          default -> {
            getField(cb, msg, m, f);
            switch (f.kind().jvm) {
              case 'J' -> cb.invokestatic(CD.INTERNAL, "hashLong", mt(CD.I, CD.J));
              case 'Z' -> cb.invokestatic(CD.INTERNAL, "hashBoolean", mt(CD.I, CD.Z));
              case 'F' -> cb.invokestatic(CD.FLOAT_BOX, "floatToIntBits", mt(CD.I, CD.F));
              case 'D' -> cb.invokestatic(CD.DOUBLE_BOX, "doubleToLongBits", mt(CD.J, CD.D))
                  .invokestatic(CD.INTERNAL, "hashLong", mt(CD.I, CD.J));
              default -> {}
            }
          }
        }
      }
      cb.iadd().istore(h);
      cb.labelBinding(skip);
    }
    cb.loadConstant(29).iload(h).imul()
        .aload(m).invokevirtual(msg, "getUnknownFields", mt(CD.UNKNOWN_FIELD_SET))
        .invokevirtual(CD.UNKNOWN_FIELD_SET, "hashCode", mt(CD.I)).iadd().ireturn();
  }

  // --------------------------------------------------------------------------------------------
  // mergeFrom(B, CodedInputStream, ExtensionRegistryLite)

  void mergeFromStream(CodeBuilder cb) {
    int b = 0, in = 1, reg = 2;
    int tag = cb.allocateLocal(TypeKind.INT);
    int iv = cb.allocateLocal(TypeKind.INT);
    int lv = cb.allocateLocal(TypeKind.LONG);
    int fv = cb.allocateLocal(TypeKind.FLOAT);
    int dv = cb.allocateLocal(TypeKind.DOUBLE);
    int ov = cb.allocateLocal(TypeKind.REFERENCE);
    int limit = cb.allocateLocal(TypeKind.INT);

    cb.trying(tb -> {
      Label loop = tb.newBoundLabel();
      Label done = tb.breakLabel();
      Label dflt = tb.newLabel();
      List<SwitchCase> cases = new ArrayList<>();
      List<Runnable> bodies = new ArrayList<>();
      cases.add(SwitchCase.of(0, done));
      for (Field f : schema.fields()) {
        Label l = tb.newLabel();
        cases.add(SwitchCase.of(f.tag(), l));
        bodies.add(() -> {
          tb.labelBinding(l);
          readOne(tb, f, b, in, reg, iv, lv, fv, dv, ov, loop);
          tb.goto_(loop);
        });
        if (f.repeated() && f.kind().packable()) {
          Label p = tb.newLabel();
          cases.add(SwitchCase.of(f.packedTag(), p));
          bodies.add(() -> {
            tb.labelBinding(p);
            tb.aload(in).invokevirtual(CD.CIS, "readRawVarint32", mt(CD.I));
            tb.aload(in).swap().invokevirtual(CD.CIS, "pushLimit", mt(CD.I, CD.I)).istore(limit);
            tb.aload(b).invokevirtual(builder, "ensure" + f.camelName() + "IsMutable", mt(CD.V));
            Label inner = tb.newBoundLabel();
            Label innerEnd = tb.newLabel();
            tb.aload(in).invokevirtual(CD.CIS, "getBytesUntilLimit", mt(CD.I)).ifle(innerEnd);
            readOne(tb, f, b, in, reg, iv, lv, fv, dv, ov, inner);
            tb.goto_(inner);
            tb.labelBinding(innerEnd);
            tb.aload(in).iload(limit).invokevirtual(CD.CIS, "popLimit", mt(CD.V, CD.I));
            tb.goto_(loop);
          });
        }
      }
      cases.sort((x, y) -> Integer.compare(x.caseValue(), y.caseValue()));
      tb.aload(in).invokevirtual(CD.CIS, "readTag", mt(CD.I)).istore(tag);
      tb.iload(tag).lookupswitch(dflt, cases);
      bodies.forEach(Runnable::run);
      tb.labelBinding(dflt);
      tb.aload(b).aload(in).aload(reg).iload(tag)
          .invokevirtual(builder, "$parseUnknown", mt(CD.Z, CD.CIS, CD.ERL, CD.I))
          .ifeq(done);
      tb.goto_(loop);
    }, catches -> catches
        .catching(CD.IPBE, hb -> {
          hb.astore(ov);
          hb.aload(b).invokevirtual(builder, "$onChanged", mt(CD.V));
          hb.aload(ov).invokevirtual(CD.IPBE, "unwrapIOException", mt(CD.IO_EXCEPTION)).athrow();
        })
        .catchingAll(hb -> {
          hb.astore(ov);
          hb.aload(b).invokevirtual(builder, "$onChanged", mt(CD.V));
          hb.aload(ov).athrow();
        }));
    cb.aload(b).invokevirtual(builder, "$onChanged", mt(CD.V));
    cb.return_();
  }

  /**
   * Reads one value for {@code f} and stores it into the builder. For repeated fields the caller
   * has either called ensureXIsMutable (packed) or this method does so.
   */
  private void readOne(CodeBuilder cb, Field f, int b, int in, int reg,
      int iv, int lv, int fv, int dv, int ov, Label afterUnknownEnum) {
    Kind k = f.kind();
    if (k == Kind.MESSAGE) {
      cb.aload(in).invokestatic(f.typeDesc(), "parser", mt(CD.PARSER)).aload(reg)
          .invokevirtual(CD.CIS, "readMessage", mt(CD.MESSAGE_LITE, CD.PARSER, CD.ERL))
          .checkcast(f.typeDesc()).astore(ov);
      cb.aload(b).aload(ov)
          .invokevirtual(builder, (f.repeated() ? "add" : "merge") + f.camelName(),
              mt(builder, f.typeDesc()))
          .pop();
      return;
    }
    // read the value into a typed local
    int local;
    TypeKind tk;
    switch (k) {
      case STRING -> {
        if (f.utf8()) {
          cb.aload(in).invokevirtual(CD.CIS, "readStringRequireUtf8", mt(CD.STRING));
        } else {
          cb.aload(in).invokevirtual(CD.CIS, "readBytes", mt(CD.BYTE_STRING));
        }
        local = ov;
        tk = TypeKind.REFERENCE;
      }
      case BYTES -> {
        cb.aload(in).invokevirtual(CD.CIS, "readBytes", mt(CD.BYTE_STRING));
        local = ov;
        tk = TypeKind.REFERENCE;
      }
      default -> {
        cb.aload(in).invokevirtual(CD.CIS, "read" + k.io, mt(k.valueDesc()));
        tk = TypeKind.from(k.valueDesc());
        local = switch (k.jvm) {
          case 'J' -> lv;
          case 'F' -> fv;
          case 'D' -> dv;
          default -> iv;
        };
      }
    }
    cb.storeLocal(tk, local);
    if (k == Kind.ENUM && f.closedEnum()) {
      Label known = cb.newLabel();
      cb.iload(local).invokestatic(f.typeDesc(), "forNumber", mt(f.typeDesc(), CD.I)).ifnonnull(known);
      cb.aload(b).loadConstant(f.number()).iload(local)
          .invokevirtual(builder, "$unknownVarint", mt(CD.V, CD.I, CD.I));
      cb.goto_(afterUnknownEnum);
      cb.labelBinding(known);
    }
    if (f.repeated()) {
      cb.aload(b).invokevirtual(builder, "ensure" + f.camelName() + "IsMutable", mt(CD.V));
      cb.aload(b).getfield(builder, f.fieldName(), f.storageDesc());
      cb.loadLocal(tk, local);
      switch (k.list) {
        case STRING -> {
          if (f.utf8()) {
            cb.invokevirtual(CD.LAZY_STRING_LIST, "add", mt(CD.Z, CD_Object)).pop();
          } else {
            cb.invokevirtual(CD.LAZY_STRING_LIST, "add", mt(CD.V, CD.BYTE_STRING));
          }
        }
        case OBJECT -> cb.invokeinterface(CD.LIST, "add", mt(CD.Z, CD_Object)).pop();
        default -> cb.invokeinterface(k.list.desc(), "add" + k.list.accessorSuffix,
            mt(CD.V, k.valueDesc()));
      }
    } else {
      cb.aload(b).loadLocal(tk, local).putfield(builder, f.fieldName(), f.storageDesc());
      if (f.presence()) setBit(cb, builder, b, f);
    }
  }

  // --------------------------------------------------------------------------------------------
  // mergeFromMessage(B, M)

  void mergeFromMessage(CodeBuilder cb) {
    int b = 0, o = 1;
    for (Field f : schema.fields()) {
      Label skip = cb.newLabel();
      if (f.repeated()) {
        getField(cb, msg, o, f);
        invokeList(cb, f, "isEmpty", mt(CD.Z));
        cb.ifne(skip);
        Label useList = cb.newLabel();
        if (f.kind() == Kind.MESSAGE) {
          cb.aload(b).getfield(builder, f.builderFieldName(), CD.REPEATED_FIELD_BUILDER).ifnull(useList);
          cb.aload(b).getfield(builder, f.builderFieldName(), CD.REPEATED_FIELD_BUILDER);
          getField(cb, msg, o, f);
          cb.invokevirtual(CD.REPEATED_FIELD_BUILDER, "addAllMessages",
              mt(CD.REPEATED_FIELD_BUILDER, CD.ITERABLE)).pop();
          cb.goto_(skip);
        }
        cb.labelBinding(useList);
        Label append = cb.newLabel();
        getField(cb, builder, b, f);
        invokeList(cb, f, "isEmpty", mt(CD.Z));
        cb.ifeq(append);
        cb.aload(b);
        getField(cb, msg, o, f);
        cb.putfield(builder, f.fieldName(), f.storageDesc());
        cb.goto_(skip);
        cb.labelBinding(append);
        cb.aload(b).invokevirtual(builder, "ensure" + f.camelName() + "IsMutable", mt(CD.V));
        getField(cb, builder, b, f);
        getField(cb, msg, o, f);
        invokeList(cb, f, "addAll", mt(CD.Z, ClassDesc.of("java.util.Collection")));
        cb.pop();
      } else if (f.kind() == Kind.MESSAGE) {
        bitTest(cb, msg, o, f);
        cb.ifeq(skip);
        cb.aload(b);
        getField(cb, msg, o, f);
        cb.invokevirtual(builder, "merge" + f.camelName(), mt(builder, f.typeDesc())).pop();
      } else {
        if (f.presence()) {
          bitTest(cb, msg, o, f);
          cb.ifeq(skip);
        } else {
          getField(cb, msg, o, f);
          ifDefault(cb, f, skip);
        }
        cb.aload(b);
        getField(cb, msg, o, f);
        cb.putfield(builder, f.fieldName(), f.storageDesc());
        if (f.presence()) setBit(cb, builder, b, f);
      }
      cb.labelBinding(skip);
    }
    cb.aload(b).aload(o).invokevirtual(msg, "getUnknownFields", mt(CD.UNKNOWN_FIELD_SET))
        .invokevirtual(builder, "mergeUnknownFields", mt(CD.GM_BUILDER, CD.UNKNOWN_FIELD_SET)).pop();
    cb.aload(b).invokevirtual(builder, "$onChanged", mt(CD.V));
    cb.return_();
  }

  // --------------------------------------------------------------------------------------------
  // buildPartial(B, M): copy builder state into the freshly constructed message

  void buildPartial(CodeBuilder cb) {
    int b = 0, r = 1;
    for (int w = 0; w < schema.bitWords(); w++) {
      String n = "bitField" + w + "_";
      cb.aload(r).aload(b).getfield(builder, n, CD.I).putfield(msg, n, CD.I);
    }
    for (Field f : schema.fields()) {
      if (f.repeated()) {
        Label done = cb.newLabel();
        if (f.kind() == Kind.MESSAGE) {
          Label useList = cb.newLabel();
          cb.aload(b).getfield(builder, f.builderFieldName(), CD.REPEATED_FIELD_BUILDER).ifnull(useList);
          cb.aload(r).aload(b).getfield(builder, f.builderFieldName(), CD.REPEATED_FIELD_BUILDER)
              .invokevirtual(CD.REPEATED_FIELD_BUILDER, "build", mt(CD.LIST))
              .putfield(msg, f.fieldName(), f.storageDesc());
          cb.goto_(done);
          cb.labelBinding(useList);
        }
        if (f.kind().list == ListKind.OBJECT) {
          cb.aload(b);
          getField(cb, builder, b, f);
          cb.invokestatic(CD.RT, "makeImmutable", mt(CD.LIST, CD.LIST))
              .putfield(builder, f.fieldName(), f.storageDesc());
        } else {
          getField(cb, builder, b, f);
          invokeList(cb, f, "makeImmutable", mt(CD.V));
        }
        cb.aload(r);
        getField(cb, builder, b, f);
        cb.putfield(msg, f.fieldName(), f.storageDesc());
        cb.labelBinding(done);
      } else if (f.kind() == Kind.MESSAGE) {
        Label useField = cb.newLabel();
        Label store = cb.newLabel();
        cb.aload(r);
        cb.aload(b).getfield(builder, f.builderFieldName(), CD.SINGLE_FIELD_BUILDER).ifnull(useField);
        cb.aload(b).getfield(builder, f.builderFieldName(), CD.SINGLE_FIELD_BUILDER)
            .invokevirtual(CD.SINGLE_FIELD_BUILDER, "build", mt(CD.GENERATED_MESSAGE))
            .checkcast(f.typeDesc());
        cb.goto_(store);
        cb.labelBinding(useField);
        getField(cb, builder, b, f);
        cb.labelBinding(store);
        cb.putfield(msg, f.fieldName(), f.storageDesc());
      } else {
        cb.aload(r);
        getField(cb, builder, b, f);
        cb.putfield(msg, f.fieldName(), f.storageDesc());
      }
    }
    cb.return_();
  }

  // --------------------------------------------------------------------------------------------
  // clear(B)

  void clear(CodeBuilder cb) {
    int b = 0;
    for (int w = 0; w < schema.bitWords(); w++) {
      cb.aload(b).iconst_0().putfield(builder, "bitField" + w + "_", CD.I);
    }
    for (Field f : schema.fields()) {
      if (f.kind() == Kind.MESSAGE) {
        ClassDesc fb = f.repeated() ? CD.REPEATED_FIELD_BUILDER : CD.SINGLE_FIELD_BUILDER;
        Label noBuilder = cb.newLabel();
        Label done = cb.newLabel();
        cb.aload(b).getfield(builder, f.builderFieldName(), fb).ifnull(noBuilder);
        if (f.repeated()) {
          cb.aload(b).getfield(builder, f.builderFieldName(), fb).invokevirtual(fb, "clear", mt(CD.V));
          cb.aload(b).aconst_null().putfield(builder, f.fieldName(), f.storageDesc());
          cb.goto_(done);
          cb.labelBinding(noBuilder);
          cb.aload(b).invokestatic(CD.COLLECTIONS, "emptyList", mt(CD.LIST))
              .putfield(builder, f.fieldName(), f.storageDesc());
        } else {
          cb.aload(b).getfield(builder, f.builderFieldName(), fb).invokevirtual(fb, "dispose", mt(CD.V));
          cb.aload(b).aconst_null().putfield(builder, f.builderFieldName(), fb);
          cb.labelBinding(noBuilder);
          cb.aload(b).aconst_null().putfield(builder, f.fieldName(), f.storageDesc());
        }
        cb.labelBinding(done);
        continue;
      }
      cb.aload(b);
      pushDefault(cb, f);
      cb.putfield(builder, f.fieldName(), f.storageDesc());
    }
    cb.return_();
  }

  /** Pushes the default value of a non-message field (shared with the generator). */
  static void pushDefault(CodeBuilder cb, Field f) {
    if (f.repeated()) {
      switch (f.kind().list) {
        case STRING -> cb.invokestatic(CD.LAZY_STRING_LIST, "emptyList", mt(CD.LAZY_STRING_LIST));
        case OBJECT -> cb.invokestatic(CD.COLLECTIONS, "emptyList", mt(CD.LIST));
        default -> cb.invokestatic(CD.RT, "empty" + f.kind().list.internalListName,
            mt(f.kind().list.desc()));
      }
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

  // --------------------------------------------------------------------------------------------
  // Parser: a hidden subclass of AbstractParser<M> with monomorphic calls into M / M.Builder

  MethodHandles.Lookup parserClass() {
    ClassDesc cis = CD.CIS, erl = CD.ERL;
    return spinClass("Parser", CD.ABSTRACT_PARSER, clb -> {
      clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> cb
          .aload(0).invokespecial(CD.ABSTRACT_PARSER, INIT_NAME, MTD_void).return_());
      clb.withMethod("parsePartialFrom", mt(CD.OBJECT, cis, erl), ClassFile.ACC_PUBLIC,
          mb -> mb.with(java.lang.classfile.attribute.ExceptionsAttribute.ofSymbols(CD.IPBE))
              .withCode(cb -> {
                int bl = cb.allocateLocal(TypeKind.REFERENCE);
                int e = cb.allocateLocal(TypeKind.REFERENCE);
                cb.invokestatic(msg, "newBuilder", mt(builder)).astore(bl);
                ClassDesc ume = ClassDesc.of("com.google.protobuf.UninitializedMessageException");
                cb.trying(tb -> tb.aload(bl).aload(1).aload(2)
                        .invokevirtual(builder, "mergeFrom", mt(builder, cis, erl)).pop(),
                    c -> c.catching(CD.IPBE, hb -> {
                          hb.astore(e);
                          hb.aload(e).aload(bl).invokevirtual(builder, "buildPartial", mt(msg))
                              .invokevirtual(CD.IPBE, "setUnfinishedMessage", mt(CD.IPBE, CD.MESSAGE_LITE))
                              .athrow();
                        })
                        .catching(ume, hb -> {
                          hb.astore(e);
                          hb.aload(e).invokevirtual(ume, "asInvalidProtocolBufferException", mt(CD.IPBE))
                              .aload(bl).invokevirtual(builder, "buildPartial", mt(msg))
                              .invokevirtual(CD.IPBE, "setUnfinishedMessage", mt(CD.IPBE, CD.MESSAGE_LITE))
                              .athrow();
                        })
                        .catching(CD.IO_EXCEPTION, hb -> {
                          hb.astore(e);
                          hb.new_(CD.IPBE).dup().aload(e)
                              .invokespecial(CD.IPBE, INIT_NAME, mt(CD.V, CD.IO_EXCEPTION))
                              .aload(bl).invokevirtual(builder, "buildPartial", mt(msg))
                              .invokevirtual(CD.IPBE, "setUnfinishedMessage", mt(CD.IPBE, CD.MESSAGE_LITE))
                              .athrow();
                        }));
                cb.aload(bl).invokevirtual(builder, "buildPartial", mt(msg)).areturn();
              }));
    });
  }
}
