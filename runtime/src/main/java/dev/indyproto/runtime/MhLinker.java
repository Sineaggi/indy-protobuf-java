package dev.indyproto.runtime;

import static java.lang.invoke.MethodType.methodType;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Internal;
import com.google.protobuf.LazyStringArrayList;
import com.google.protobuf.MessageLite;
import com.google.protobuf.UnknownFieldSet;
import dev.indyproto.runtime.Schema.Field;
import dev.indyproto.runtime.Schema.Kind;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The MethodHandle-combinator strategy: builds writeTo / serializedSize / equals / hashCode as a
 * tree of {@code guardWithTest}/{@code foldArguments}/{@code filterArguments} over field getters
 * and a handful of generic helpers that switch on a (link-time constant) field kind.
 *
 * <p>No class is spun. Trees are combined as balanced binary trees so that their depth is
 * O(log fields), which keeps them within C2's inlining limits for longer than a linear chain would.
 */
final class MhLinker {
  private MhLinker() {}

  private static final Lookup L = MethodHandles.lookup();
  /** Indexed by {@link Schema.Field#wireKind()}: the kinds, then MESSAGE again for groups. */
  private static final Kind[] KINDS = Rt.wireKinds();
  private static final int GROUP = Schema.Field.GROUP_WIRE_KIND;

  static MethodHandle link(Lookup lookup, String name, MethodType type, Class<?> msgClass,
      Schema schema) throws Throwable {
    return switch (name) {
      case "writeTo" -> writeTo(lookup, msgClass, schema);
      case "serializedSize" -> serializedSize(lookup, msgClass, schema);
      case "equals" -> equalsMh(lookup, msgClass, schema);
      case "hashCode" -> hashCodeMh(lookup, msgClass, schema);
      default -> throw new IllegalArgumentException(name);
    };
  }

  // ---------------------------------------------------------------------------------------------
  // field access

  private static Class<?> storageClass(Lookup lookup, Field f) throws ReflectiveOperationException {
    if (f.isMap()) return com.google.protobuf.MapField.class;
    if (f.bytesList()) return Internal.ProtobufList.class;
    if (f.repeated()) {
      return switch (f.kind().list) {
        case INT -> Internal.IntList.class;
        case LONG -> Internal.LongList.class;
        case FLOAT -> Internal.FloatList.class;
        case DOUBLE -> Internal.DoubleList.class;
        case BOOLEAN -> Internal.BooleanList.class;
        case STRING -> LazyStringArrayList.class;
        case OBJECT -> List.class;
      };
    }
    return switch (f.kind()) {
      case STRING -> Object.class;
      case BYTES -> ByteString.class;
      case MESSAGE -> lookup.findClass(f.typeInternalName().replace('/', '.'));
      default -> switch (f.kind().jvm) {
        case 'J' -> long.class;
        case 'F' -> float.class;
        case 'D' -> double.class;
        case 'Z' -> boolean.class;
        default -> int.class;
      };
    };
  }

  private static MethodHandle getter(Lookup lookup, Class<?> m, Field f) throws ReflectiveOperationException {
    return lookup.findGetter(m, f.fieldName(), storageClass(lookup, f));
  }

  /** (M)boolean: presence bit set. */
  private static MethodHandle has(Lookup lookup, Class<?> m, Field f) throws ReflectiveOperationException {
    MethodHandle word = lookup.findGetter(m, Schema.bitFieldName(f.bit()), int.class);
    return MethodHandles.filterArguments(
        MethodHandles.insertArguments(H_BIT_SET, 1, 1 << (f.bit() % 32)), 0, word);
  }

  /** (M)boolean: field should be written (presence bit, or non-default, or non-empty). */
  private static MethodHandle present(Lookup lookup, Class<?> m, Field f) throws ReflectiveOperationException {
    if (f.repeated()) {
      return MethodHandles.filterArguments(H_NON_EMPTY, 0, getter(lookup, m, f).asType(
          methodType(List.class, m)));
    }
    if (f.presence()) return has(lookup, m, f);
    MethodHandle g = getter(lookup, m, f);
    MethodHandle test = switch (f.kind()) {
      case STRING -> H_NON_EMPTY_STR;
      case BYTES -> H_NON_EMPTY_BYTES;
      default -> switch (f.kind().jvm) {
        case 'J' -> H_NZ_J;
        case 'F' -> H_NZ_F;
        case 'D' -> H_NZ_D;
        case 'Z' -> H_NZ_Z;
        default -> H_NZ_I;
      };
    };
    return MethodHandles.filterArguments(test, 0, g);
  }

  // ---------------------------------------------------------------------------------------------
  // oneof members and map fields go through generic helpers in Rt (kind is a constant)

  /** (M)boolean: the member's oneof currently holds it. */
  private static MethodHandle isCase(Lookup lookup, Class<?> m, Schema schema, Field f)
      throws ReflectiveOperationException {
    MethodHandle c = lookup.findGetter(m, schema.oneofCaseField(f.oneof()), int.class);
    return MethodHandles.filterArguments(MethodHandles.insertArguments(H_EQ_I, 1, f.number()), 0, c);
  }

  private static MethodHandle oneofValue(Lookup lookup, Class<?> m, Schema schema, Field f)
      throws ReflectiveOperationException {
    return lookup.findGetter(m, schema.oneofValueField(f.oneof()), Object.class);
  }

  private static Object mapEntryOf(Lookup lookup, Class<?> m, Field f) throws Throwable {
    return lookup.findStatic(m, f.mapEntryMethod(),
        methodType(com.google.protobuf.MapEntry.class)).invoke();
  }

  private static MethodHandle rt(String name, MethodType t) {
    try {
      return L.findStatic(Rt.class, name, t);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final Class<?> MF = com.google.protobuf.MapField.class;
  private static final Class<?> ME = com.google.protobuf.MapEntry.class;

  // ---------------------------------------------------------------------------------------------
  // writeTo: (M, COS)void

  private static MethodHandle writeTo(Lookup lookup, Class<?> m, Schema schema) throws Throwable {
    List<MethodHandle> leaves = new ArrayList<>();
    MethodType leafType = methodType(void.class, m, CodedOutputStream.class);
    for (Field f : schema.fields()) {
      if (f.isMap()) {
        MethodHandle h = MethodHandles.insertArguments(
            rt("writeMap", methodType(void.class, COS, int.class, int.class, MF, ME)),
            1, f.number(), f.mapKey().ordinal());
        h = MethodHandles.insertArguments(h, 2, mapEntryOf(lookup, m, f));
        h = MethodHandles.filterArguments(h, 1, getter(lookup, m, f));
        leaves.add(MethodHandles.permuteArguments(h, leafType, 1, 0));
        continue;
      }
      if (f.inOneof()) {
        MethodHandle h = MethodHandles.insertArguments(
            rt("writeOneof", methodType(void.class, COS, int.class, int.class, Object.class)),
            1, f.number(), f.wireKind());
        h = MethodHandles.filterArguments(h, 1, oneofValue(lookup, m, schema, f));
        h = MethodHandles.permuteArguments(h, leafType, 1, 0);
        MethodHandle test = MethodHandles.dropArguments(isCase(lookup, m, schema, f), 1, CodedOutputStream.class);
        leaves.add(MethodHandles.guardWithTest(test, h, MethodHandles.empty(leafType)));
        continue;
      }
      MethodHandle g = getter(lookup, m, f);
      MethodHandle w;
      if (f.repeated()) {
        if (f.packed()) {
          MethodHandle memo = lookup.findGetter(m, f.memoFieldName(), int.class);
          // writePacked(out, tag, kind, list, dataSize) with list/size from m
          MethodHandle h = MethodHandles.insertArguments(H_WRITE_PACKED, 1, f.packedTag(), f.wireKind());
          h = MethodHandles.filterArguments(h, 1, g.asType(methodType(List.class, m)), memo);
          // (out, m, m) -> (m, out)
          w = MethodHandles.permuteArguments(h, leafType, 1, 0, 0);
        } else {
          MethodHandle h = MethodHandles.insertArguments(H_WRITE_LIST, 1, f.number(), f.wireKind());
          h = MethodHandles.filterArguments(h, 1, g.asType(methodType(List.class, m)));
          w = MethodHandles.permuteArguments(h, leafType, 1, 0);
        }
      } else {
        MethodHandle base = switch (f.kind().jvm) {
          case 'J' -> H_WRITE_J;
          case 'F' -> H_WRITE_F;
          case 'D' -> H_WRITE_D;
          case 'Z' -> H_WRITE_Z;
          case 'I' -> H_WRITE_I;
          default -> H_WRITE_O;
        };
        MethodHandle h = MethodHandles.insertArguments(base, 1, f.number(), f.wireKind());
        MethodHandle gg = base == H_WRITE_O ? g.asType(methodType(Object.class, m)) : g;
        h = MethodHandles.filterArguments(h, 1, gg);
        h = MethodHandles.permuteArguments(h, leafType, 1, 0);
        MethodHandle test = MethodHandles.dropArguments(present(lookup, m, f), 1, CodedOutputStream.class);
        w = MethodHandles.guardWithTest(test, h, MethodHandles.empty(leafType));
      }
      leaves.add(w);
    }
    MethodHandle ufs = MethodHandles.filterArguments(H_WRITE_UFS, 0,
        lookup.findVirtual(m, "getUnknownFields", methodType(UnknownFieldSet.class)));
    leaves.add(ufs);
    return balance(leaves, (a, b) -> MethodHandles.foldArguments(b, a));
  }

  // ---------------------------------------------------------------------------------------------
  // serializedSize: (M)int

  private static MethodHandle serializedSize(Lookup lookup, Class<?> m, Schema schema) throws Throwable {
    List<MethodHandle> leaves = new ArrayList<>();
    MethodType leafType = methodType(int.class, m);
    MethodHandle zero = MethodHandles.dropArguments(MethodHandles.constant(int.class, 0), 0, m);
    for (Field f : schema.fields()) {
      if (f.isMap()) {
        MethodHandle h = MethodHandles.insertArguments(
            rt("sizeMap", methodType(int.class, int.class, MF, ME)), 0, f.number());
        h = MethodHandles.insertArguments(h, 1, mapEntryOf(lookup, m, f));
        leaves.add(MethodHandles.filterArguments(h, 0, getter(lookup, m, f)));
        continue;
      }
      if (f.inOneof()) {
        MethodHandle h = MethodHandles.insertArguments(
            rt("sizeOneof", methodType(int.class, int.class, int.class, Object.class)),
            0, f.number(), f.wireKind());
        h = MethodHandles.filterArguments(h, 0, oneofValue(lookup, m, schema, f));
        leaves.add(MethodHandles.guardWithTest(isCase(lookup, m, schema, f), h, zero));
        continue;
      }
      MethodHandle g = getter(lookup, m, f);
      MethodHandle s;
      if (f.repeated()) {
        MethodHandle list = g.asType(methodType(List.class, m));
        if (f.packed()) {
          MethodHandle data = MethodHandles.filterArguments(
              MethodHandles.insertArguments(H_SIZE_LIST_NOTAG, 0, f.wireKind()), 0, list);
          MethodHandle setter = lookup.findSetter(m, f.memoFieldName(), int.class);
          MethodHandle store = MethodHandles.foldArguments(
              MethodHandles.dropArguments(MethodHandles.identity(int.class), 0, m), setter);
          store = MethodHandles.permuteArguments(store, methodType(int.class, int.class, m), 1, 0);
          MethodHandle stored = MethodHandles.foldArguments(store, data);
          s = MethodHandles.filterReturnValue(stored, MethodHandles.insertArguments(H_PACKED_TOTAL, 0,
              CodedOutputStream.computeTagSize(f.number())));
        } else {
          s = MethodHandles.filterArguments(
              MethodHandles.insertArguments(H_SIZE_LIST, 0, f.number(), f.wireKind()), 0, list);
        }
      } else {
        MethodHandle base = switch (f.kind().jvm) {
          case 'J' -> H_SIZE_J;
          case 'F' -> H_SIZE_F;
          case 'D' -> H_SIZE_D;
          case 'Z' -> H_SIZE_Z;
          case 'I' -> H_SIZE_I;
          default -> H_SIZE_O;
        };
        MethodHandle h = MethodHandles.insertArguments(base, 0, f.number(), f.wireKind());
        MethodHandle gg = base == H_SIZE_O ? g.asType(methodType(Object.class, m)) : g;
        h = MethodHandles.filterArguments(h, 0, gg);
        s = MethodHandles.guardWithTest(present(lookup, m, f), h,
            MethodHandles.dropArguments(MethodHandles.constant(int.class, 0), 0, m));
      }
      leaves.add(s.asType(leafType));
    }
    leaves.add(MethodHandles.filterArguments(H_UFS_SIZE, 0,
        lookup.findVirtual(m, "getUnknownFields", methodType(UnknownFieldSet.class))));
    if (schema.extendable()) leaves.add(lookup.findVirtual(m, "$extSize", methodType(int.class)).asType(leafType));
    return balance(leaves, (a, b) -> MethodHandles.permuteArguments(
        MethodHandles.filterArguments(H_IADD, 0, a, b), leafType, 0, 0));
  }

  // ---------------------------------------------------------------------------------------------
  // equals: (M, M)boolean

  private static MethodHandle equalsMh(Lookup lookup, Class<?> m, Schema schema) throws Throwable {
    MethodType leafType = methodType(boolean.class, m, m);
    MethodHandle t = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0, m, m);
    MethodHandle fls = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, m, m);
    List<MethodHandle> leaves = new ArrayList<>();
    for (Field f : schema.fields()) {
      if (f.inOneof()) continue;
      if (f.isMap()) {
        MethodHandle eq = MethodHandles.insertArguments(
            rt("eqMap", methodType(boolean.class, MF, MF, ME)), 2, mapEntryOf(lookup, m, f));
        MethodHandle g = getter(lookup, m, f);
        leaves.add(MethodHandles.filterArguments(eq, 0, g, g));
        continue;
      }
      MethodHandle value = valueForCompare(lookup, m, f);
      MethodHandle eq = switch (value.type().returnType().getName()) {
        case "int" -> H_EQ_I;
        case "long" -> H_EQ_J;
        case "boolean" -> H_EQ_Z;
        default -> H_EQ_O;
      };
      if (eq == H_EQ_O) value = value.asType(methodType(Object.class, m));
      MethodHandle leaf = MethodHandles.filterArguments(eq, 0, value, value);
      if (!f.repeated() && f.presence()) {
        MethodHandle has = has(lookup, m, f);
        MethodHandle sameHas = MethodHandles.filterArguments(H_EQ_Z, 0, has, has);
        MethodHandle valueIfSet = MethodHandles.guardWithTest(
            MethodHandles.dropArguments(has, 1, m), leaf, t);
        leaf = MethodHandles.guardWithTest(sameHas, valueIfSet, fls);
      }
      leaves.add(leaf);
    }
    for (int i = 0; i < schema.oneofs().size(); i++) {
      MethodHandle c = lookup.findGetter(m, schema.oneofCaseField(i), int.class);
      leaves.add(MethodHandles.filterArguments(H_EQ_I, 0, c, c));
      for (Field f : schema.oneofMembers(i)) {
        MethodHandle v = oneofValue(lookup, m, schema, f);
        MethodHandle eq = MethodHandles.insertArguments(
            rt("eqOneof", methodType(boolean.class, int.class, Object.class, Object.class)),
            0, f.wireKind());
        eq = MethodHandles.filterArguments(eq, 0, v, v);
        leaves.add(MethodHandles.guardWithTest(
            MethodHandles.dropArguments(isCase(lookup, m, schema, f), 1, m), eq, t));
      }
    }
    MethodHandle ufs = lookup.findVirtual(m, "getUnknownFields", methodType(UnknownFieldSet.class))
        .asType(methodType(Object.class, m));
    leaves.add(MethodHandles.filterArguments(H_EQ_O, 0, ufs, ufs));
    if (schema.extendable()) {
      leaves.add(lookup.findVirtual(m, "$extEquals", methodType(boolean.class, m)).asType(leafType));
    }
    return balance(leaves, (a, b) -> MethodHandles.guardWithTest(a, b, fls));
  }

  /** (M)T where T is int/long/boolean or a reference compared with equals. */
  private static MethodHandle valueForCompare(Lookup lookup, Class<?> m, Field f) throws Throwable {
    if (f.repeated()) return getter(lookup, m, f);
    return switch (f.kind()) {
      case STRING -> lookup.findVirtual(m, "get" + f.camelName(), methodType(String.class));
      case MESSAGE -> getter(lookup, m, f);
      case BYTES -> getter(lookup, m, f);
      default -> switch (f.kind().jvm) {
        case 'F' -> MethodHandles.filterReturnValue(getter(lookup, m, f), H_FLOAT_BITS);
        case 'D' -> MethodHandles.filterReturnValue(getter(lookup, m, f), H_DOUBLE_BITS);
        default -> getter(lookup, m, f);
      };
    };
  }

  // ---------------------------------------------------------------------------------------------
  // hashCode: (M)int, same formula as protoc

  private static MethodHandle hashCodeMh(Lookup lookup, Class<?> m, Schema schema) throws Throwable {
    MethodType stepType = methodType(int.class, int.class, m);
    List<MethodHandle> steps = new ArrayList<>();
    MethodHandle keep = MethodHandles.dropArguments(MethodHandles.identity(int.class), 1, m);
    for (Field f : schema.declared()) {
      if (f.inOneof()) continue;
      if (f.isMap()) {
        MethodHandle mix = MethodHandles.insertArguments(
            rt("mixMap", methodType(int.class, int.class, int.class, MF)), 1, f.number());
        steps.add(MethodHandles.filterArguments(mix, 1, getter(lookup, m, f)).asType(stepType));
        continue;
      }
      MethodHandle vh = valueHash(lookup, m, f);
      MethodHandle step = MethodHandles.filterArguments(
          MethodHandles.insertArguments(H_MIX, 1, f.number()), 1, vh);
      if (f.repeated() || f.presence()) {
        step = MethodHandles.guardWithTest(
            MethodHandles.dropArguments(present(lookup, m, f), 0, int.class), step,
            MethodHandles.dropArguments(MethodHandles.identity(int.class), 1, m));
      }
      steps.add(step.asType(stepType));
    }
    for (int i = 0; i < schema.oneofs().size(); i++) {
      for (Field f : schema.oneofMembers(i)) {
        MethodHandle vh = MethodHandles.filterArguments(MethodHandles.insertArguments(
            rt("hashOneof", methodType(int.class, int.class, Object.class)), 0, f.wireKind()),
            0, oneofValue(lookup, m, schema, f));
        MethodHandle step = MethodHandles.filterArguments(
            MethodHandles.insertArguments(H_MIX, 1, f.number()), 1, vh);
        steps.add(MethodHandles.guardWithTest(
            MethodHandles.dropArguments(isCase(lookup, m, schema, f), 0, int.class), step, keep));
      }
    }
    MethodHandle ufs = lookup.findVirtual(m, "getUnknownFields", methodType(UnknownFieldSet.class))
        .asType(methodType(Object.class, m));
    if (schema.extendable()) {
      MethodHandle ext = lookup.findVirtual(m, "$extHash", methodType(int.class, int.class));
      steps.add(MethodHandles.permuteArguments(ext, methodType(int.class, int.class, m), 1, 0));
    }
    steps.add(MethodHandles.filterArguments(H_MIX_UFS, 1,
        MethodHandles.filterReturnValue(ufs, H_HASH_O)));
    MethodHandle chain = balance(steps, (a, b) -> MethodHandles.permuteArguments(
        MethodHandles.collectArguments(b, 0, a), stepType, 0, 1, 1));
    Object descriptor = lookup.findStatic(m, "getDescriptor",
        methodType(com.google.protobuf.Descriptors.Descriptor.class)).invoke();
    return MethodHandles.insertArguments(chain, 0, 19 * 41 + descriptor.hashCode());
  }

  private static MethodHandle valueHash(Lookup lookup, Class<?> m, Field f) throws Throwable {
    MethodHandle g = getter(lookup, m, f);
    if (f.repeated()) return MethodHandles.filterReturnValue(g.asType(methodType(Object.class, m)), H_HASH_O);
    return switch (f.kind()) {
      case STRING -> MethodHandles.filterReturnValue(
          lookup.findVirtual(m, "get" + f.camelName(), methodType(String.class))
              .asType(methodType(Object.class, m)), H_HASH_O);
      case MESSAGE, BYTES -> MethodHandles.filterReturnValue(
          g.asType(methodType(Object.class, m)), H_HASH_O);
      default -> switch (f.kind().jvm) {
        case 'J' -> MethodHandles.filterReturnValue(g, H_HASH_LONG);
        case 'Z' -> MethodHandles.filterReturnValue(g, H_HASH_BOOL);
        case 'F' -> MethodHandles.filterReturnValue(g, H_FLOAT_BITS);
        case 'D' -> MethodHandles.filterReturnValue(MethodHandles.filterReturnValue(g, H_DOUBLE_BITS), H_HASH_LONG);
        default -> g;
      };
    };
  }

  // ---------------------------------------------------------------------------------------------

  interface Combiner {
    MethodHandle combine(MethodHandle a, MethodHandle b);
  }

  static MethodHandle balance(List<MethodHandle> xs, Combiner c) {
    if (xs.size() == 1) return xs.get(0);
    int mid = xs.size() / 2;
    return c.combine(balance(xs.subList(0, mid), c), balance(xs.subList(mid, xs.size()), c));
  }

  // ---------------------------------------------------------------------------------------------
  // generic helpers (kind is a link-time constant, so the switches fold after inlining)

  static boolean bitSet(int word, int mask) { return (word & mask) != 0; }
  static boolean nzI(int v) { return v != 0; }
  static boolean nzJ(long v) { return v != 0; }
  static boolean nzZ(boolean v) { return v; }
  static boolean nzF(float v) { return Float.floatToRawIntBits(v) != 0; }
  static boolean nzD(double v) { return Double.doubleToRawLongBits(v) != 0; }
  static boolean nonEmptyStr(Object v) { return !Rt.isStringEmpty(v); }
  static boolean nonEmptyBytes(ByteString v) { return !v.isEmpty(); }
  static boolean nonEmpty(List<?> v) { return !v.isEmpty(); }
  static int iadd(int a, int b) { return a + b; }
  static boolean eqI(int a, int b) { return a == b; }
  static boolean eqJ(long a, long b) { return a == b; }
  static boolean eqZ(boolean a, boolean b) { return a == b; }
  static boolean eqO(Object a, Object b) { return Objects.equals(a, b); }
  static int hashO(Object o) { return o.hashCode(); }
  static int mix(int h, int number, int vh) { return 53 * (37 * h + number) + vh; }
  static int mixUfs(int h, int ufsHash) { return 29 * h + ufsHash; }

  static void writeI(CodedOutputStream out, int n, int kind, int v) throws IOException {
    switch (KINDS[kind]) {
      case INT32 -> out.writeInt32(n, v);
      case UINT32 -> out.writeUInt32(n, v);
      case SINT32 -> out.writeSInt32(n, v);
      case FIXED32 -> out.writeFixed32(n, v);
      case SFIXED32 -> out.writeSFixed32(n, v);
      case ENUM -> out.writeEnum(n, v);
      default -> throw new AssertionError();
    }
  }

  static void writeJ(CodedOutputStream out, int n, int kind, long v) throws IOException {
    switch (KINDS[kind]) {
      case INT64 -> out.writeInt64(n, v);
      case UINT64 -> out.writeUInt64(n, v);
      case SINT64 -> out.writeSInt64(n, v);
      case FIXED64 -> out.writeFixed64(n, v);
      case SFIXED64 -> out.writeSFixed64(n, v);
      default -> throw new AssertionError();
    }
  }

  static void writeF(CodedOutputStream out, int n, int kind, float v) throws IOException { out.writeFloat(n, v); }
  static void writeD(CodedOutputStream out, int n, int kind, double v) throws IOException { out.writeDouble(n, v); }
  static void writeZ(CodedOutputStream out, int n, int kind, boolean v) throws IOException { out.writeBool(n, v); }

  static void writeO(CodedOutputStream out, int n, int kind, Object v) throws IOException {
    if (kind == GROUP) {
      out.writeGroup(n, (MessageLite) v);
      return;
    }
    switch (KINDS[kind]) {
      case STRING -> Rt.writeString(out, n, v);
      case BYTES -> out.writeBytes(n, (ByteString) v);
      case MESSAGE -> out.writeMessage(n, (MessageLite) v);
      default -> throw new AssertionError();
    }
  }

  static void writeList(CodedOutputStream out, int n, int kind, List<?> list) throws IOException {
    Kind k = KINDS[kind];
    int size = list.size();
    switch (k.list) {
      case INT -> { Internal.IntList l = (Internal.IntList) list; for (int i = 0; i < size; i++) writeI(out, n, kind, l.getInt(i)); }
      case LONG -> { Internal.LongList l = (Internal.LongList) list; for (int i = 0; i < size; i++) writeJ(out, n, kind, l.getLong(i)); }
      case FLOAT -> { Internal.FloatList l = (Internal.FloatList) list; for (int i = 0; i < size; i++) out.writeFloat(n, l.getFloat(i)); }
      case DOUBLE -> { Internal.DoubleList l = (Internal.DoubleList) list; for (int i = 0; i < size; i++) out.writeDouble(n, l.getDouble(i)); }
      case BOOLEAN -> { Internal.BooleanList l = (Internal.BooleanList) list; for (int i = 0; i < size; i++) out.writeBool(n, l.getBoolean(i)); }
      case STRING -> { LazyStringArrayList l = (LazyStringArrayList) list; for (int i = 0; i < size; i++) Rt.writeString(out, n, l.getRaw(i)); }
      case OBJECT -> { for (int i = 0; i < size; i++) writeO(out, n, kind, list.get(i)); }
    }
  }

  static void writePacked(CodedOutputStream out, int tag, int kind, List<?> list, int dataSize)
      throws IOException {
    if (list.isEmpty()) return;
    out.writeUInt32NoTag(tag);
    out.writeUInt32NoTag(dataSize);
    Kind k = KINDS[kind];
    int size = list.size();
    switch (k) {
      case INT32 -> { Internal.IntList l = (Internal.IntList) list; for (int i = 0; i < size; i++) out.writeInt32NoTag(l.getInt(i)); }
      case UINT32 -> { Internal.IntList l = (Internal.IntList) list; for (int i = 0; i < size; i++) out.writeUInt32NoTag(l.getInt(i)); }
      case SINT32 -> { Internal.IntList l = (Internal.IntList) list; for (int i = 0; i < size; i++) out.writeSInt32NoTag(l.getInt(i)); }
      case FIXED32 -> { Internal.IntList l = (Internal.IntList) list; for (int i = 0; i < size; i++) out.writeFixed32NoTag(l.getInt(i)); }
      case SFIXED32 -> { Internal.IntList l = (Internal.IntList) list; for (int i = 0; i < size; i++) out.writeSFixed32NoTag(l.getInt(i)); }
      case ENUM -> { Internal.IntList l = (Internal.IntList) list; for (int i = 0; i < size; i++) out.writeEnumNoTag(l.getInt(i)); }
      case INT64 -> { Internal.LongList l = (Internal.LongList) list; for (int i = 0; i < size; i++) out.writeInt64NoTag(l.getLong(i)); }
      case UINT64 -> { Internal.LongList l = (Internal.LongList) list; for (int i = 0; i < size; i++) out.writeUInt64NoTag(l.getLong(i)); }
      case SINT64 -> { Internal.LongList l = (Internal.LongList) list; for (int i = 0; i < size; i++) out.writeSInt64NoTag(l.getLong(i)); }
      case FIXED64 -> { Internal.LongList l = (Internal.LongList) list; for (int i = 0; i < size; i++) out.writeFixed64NoTag(l.getLong(i)); }
      case SFIXED64 -> { Internal.LongList l = (Internal.LongList) list; for (int i = 0; i < size; i++) out.writeSFixed64NoTag(l.getLong(i)); }
      case FLOAT -> { Internal.FloatList l = (Internal.FloatList) list; for (int i = 0; i < size; i++) out.writeFloatNoTag(l.getFloat(i)); }
      case DOUBLE -> { Internal.DoubleList l = (Internal.DoubleList) list; for (int i = 0; i < size; i++) out.writeDoubleNoTag(l.getDouble(i)); }
      case BOOL -> { Internal.BooleanList l = (Internal.BooleanList) list; for (int i = 0; i < size; i++) out.writeBoolNoTag(l.getBoolean(i)); }
      default -> throw new AssertionError();
    }
  }

  static void writeUfs(UnknownFieldSet ufs, CodedOutputStream out) throws IOException {
    ufs.writeTo(out);
  }

  static int sizeI(int n, int kind, int v) {
    return switch (KINDS[kind]) {
      case INT32 -> CodedOutputStream.computeInt32Size(n, v);
      case UINT32 -> CodedOutputStream.computeUInt32Size(n, v);
      case SINT32 -> CodedOutputStream.computeSInt32Size(n, v);
      case FIXED32 -> CodedOutputStream.computeFixed32Size(n, v);
      case SFIXED32 -> CodedOutputStream.computeSFixed32Size(n, v);
      case ENUM -> CodedOutputStream.computeEnumSize(n, v);
      default -> throw new AssertionError();
    };
  }

  static int sizeJ(int n, int kind, long v) {
    return switch (KINDS[kind]) {
      case INT64 -> CodedOutputStream.computeInt64Size(n, v);
      case UINT64 -> CodedOutputStream.computeUInt64Size(n, v);
      case SINT64 -> CodedOutputStream.computeSInt64Size(n, v);
      case FIXED64 -> CodedOutputStream.computeFixed64Size(n, v);
      case SFIXED64 -> CodedOutputStream.computeSFixed64Size(n, v);
      default -> throw new AssertionError();
    };
  }

  static int sizeF(int n, int kind, float v) { return CodedOutputStream.computeFloatSize(n, v); }
  static int sizeD(int n, int kind, double v) { return CodedOutputStream.computeDoubleSize(n, v); }
  static int sizeZ(int n, int kind, boolean v) { return CodedOutputStream.computeBoolSize(n, v); }

  static int sizeO(int n, int kind, Object v) {
    if (kind == GROUP) return CodedOutputStream.computeGroupSize(n, (MessageLite) v);
    return switch (KINDS[kind]) {
      case STRING -> Rt.computeStringSize(n, v);
      case BYTES -> CodedOutputStream.computeBytesSize(n, (ByteString) v);
      case MESSAGE -> CodedOutputStream.computeMessageSize(n, (MessageLite) v);
      default -> throw new AssertionError();
    };
  }

  static int sizeNoTag(int kind, Object element) {
    if (kind == GROUP) return ((MessageLite) element).getSerializedSize();
    return switch (KINDS[kind]) {
      case STRING -> Rt.computeStringSizeNoTag(element);
      case BYTES -> CodedOutputStream.computeBytesSizeNoTag((ByteString) element);
      case MESSAGE -> CodedOutputStream.computeMessageSizeNoTag((MessageLite) element);
      default -> throw new AssertionError();
    };
  }

  static int sizeListNoTag(int kind, List<?> list) {
    Kind k = KINDS[kind];
    int size = list.size();
    int data = 0;
    switch (k.list) {
      case INT -> {
        Internal.IntList l = (Internal.IntList) list;
        for (int i = 0; i < size; i++) {
          int v = l.getInt(i);
          data += switch (k) {
            case INT32 -> CodedOutputStream.computeInt32SizeNoTag(v);
            case UINT32 -> CodedOutputStream.computeUInt32SizeNoTag(v);
            case SINT32 -> CodedOutputStream.computeSInt32SizeNoTag(v);
            case ENUM -> CodedOutputStream.computeEnumSizeNoTag(v);
            default -> 4;
          };
        }
      }
      case LONG -> {
        Internal.LongList l = (Internal.LongList) list;
        for (int i = 0; i < size; i++) {
          long v = l.getLong(i);
          data += switch (k) {
            case INT64 -> CodedOutputStream.computeInt64SizeNoTag(v);
            case UINT64 -> CodedOutputStream.computeUInt64SizeNoTag(v);
            case SINT64 -> CodedOutputStream.computeSInt64SizeNoTag(v);
            default -> 8;
          };
        }
      }
      case FLOAT -> data = 4 * size;
      case DOUBLE -> data = 8 * size;
      case BOOLEAN -> data = size;
      case STRING -> {
        LazyStringArrayList l = (LazyStringArrayList) list;
        for (int i = 0; i < size; i++) data += Rt.computeStringSizeNoTag(l.getRaw(i));
      }
      case OBJECT -> {
        for (int i = 0; i < size; i++) data += sizeNoTag(kind, list.get(i));
      }
    }
    return data;
  }

  static int sizeList(int n, int kind, List<?> list) {
    int tags = kind == GROUP ? 2 : 1;
    return sizeListNoTag(kind, list) + tags * list.size() * CodedOutputStream.computeTagSize(n);
  }

  static int packedTotal(int tagSize, int data) {
    return data == 0 ? 0 : data + tagSize + CodedOutputStream.computeUInt32SizeNoTag(data);
  }

  static int ufsSize(UnknownFieldSet ufs) { return ufs.getSerializedSize(); }

  private static MethodHandle h(String name, MethodType t) {
    try {
      return L.findStatic(MhLinker.class, name, t);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final Class<?> COS = CodedOutputStream.class;
  static final MethodHandle H_BIT_SET = h("bitSet", methodType(boolean.class, int.class, int.class));
  static final MethodHandle H_NZ_I = h("nzI", methodType(boolean.class, int.class));
  static final MethodHandle H_NZ_J = h("nzJ", methodType(boolean.class, long.class));
  static final MethodHandle H_NZ_Z = h("nzZ", methodType(boolean.class, boolean.class));
  static final MethodHandle H_NZ_F = h("nzF", methodType(boolean.class, float.class));
  static final MethodHandle H_NZ_D = h("nzD", methodType(boolean.class, double.class));
  static final MethodHandle H_NON_EMPTY_STR = h("nonEmptyStr", methodType(boolean.class, Object.class));
  static final MethodHandle H_NON_EMPTY_BYTES = h("nonEmptyBytes", methodType(boolean.class, ByteString.class));
  static final MethodHandle H_NON_EMPTY = h("nonEmpty", methodType(boolean.class, List.class));
  static final MethodHandle H_IADD = h("iadd", methodType(int.class, int.class, int.class));
  static final MethodHandle H_EQ_I = h("eqI", methodType(boolean.class, int.class, int.class));
  static final MethodHandle H_EQ_J = h("eqJ", methodType(boolean.class, long.class, long.class));
  static final MethodHandle H_EQ_Z = h("eqZ", methodType(boolean.class, boolean.class, boolean.class));
  static final MethodHandle H_EQ_O = h("eqO", methodType(boolean.class, Object.class, Object.class));
  static final MethodHandle H_HASH_O = h("hashO", methodType(int.class, Object.class));
  static final MethodHandle H_MIX = h("mix", methodType(int.class, int.class, int.class, int.class));
  static final MethodHandle H_MIX_UFS = h("mixUfs", methodType(int.class, int.class, int.class));
  static final MethodHandle H_WRITE_I = h("writeI", methodType(void.class, COS, int.class, int.class, int.class));
  static final MethodHandle H_WRITE_J = h("writeJ", methodType(void.class, COS, int.class, int.class, long.class));
  static final MethodHandle H_WRITE_F = h("writeF", methodType(void.class, COS, int.class, int.class, float.class));
  static final MethodHandle H_WRITE_D = h("writeD", methodType(void.class, COS, int.class, int.class, double.class));
  static final MethodHandle H_WRITE_Z = h("writeZ", methodType(void.class, COS, int.class, int.class, boolean.class));
  static final MethodHandle H_WRITE_O = h("writeO", methodType(void.class, COS, int.class, int.class, Object.class));
  static final MethodHandle H_WRITE_LIST = h("writeList", methodType(void.class, COS, int.class, int.class, List.class));
  static final MethodHandle H_WRITE_PACKED = h("writePacked", methodType(void.class, COS, int.class, int.class, List.class, int.class));
  static final MethodHandle H_WRITE_UFS = h("writeUfs", methodType(void.class, UnknownFieldSet.class, COS));
  static final MethodHandle H_SIZE_I = h("sizeI", methodType(int.class, int.class, int.class, int.class));
  static final MethodHandle H_SIZE_J = h("sizeJ", methodType(int.class, int.class, int.class, long.class));
  static final MethodHandle H_SIZE_F = h("sizeF", methodType(int.class, int.class, int.class, float.class));
  static final MethodHandle H_SIZE_D = h("sizeD", methodType(int.class, int.class, int.class, double.class));
  static final MethodHandle H_SIZE_Z = h("sizeZ", methodType(int.class, int.class, int.class, boolean.class));
  static final MethodHandle H_SIZE_O = h("sizeO", methodType(int.class, int.class, int.class, Object.class));
  static final MethodHandle H_SIZE_LIST = h("sizeList", methodType(int.class, int.class, int.class, List.class));
  static final MethodHandle H_SIZE_LIST_NOTAG = h("sizeListNoTag", methodType(int.class, int.class, List.class));
  static final MethodHandle H_PACKED_TOTAL = h("packedTotal", methodType(int.class, int.class, int.class));
  static final MethodHandle H_UFS_SIZE = h("ufsSize", methodType(int.class, UnknownFieldSet.class));
  static final MethodHandle H_FLOAT_BITS = h("floatBits", methodType(int.class, float.class));
  static final MethodHandle H_DOUBLE_BITS = h("doubleBits", methodType(long.class, double.class));
  static final MethodHandle H_HASH_LONG = h("hashLong", methodType(int.class, long.class));
  static final MethodHandle H_HASH_BOOL = h("hashBoolean", methodType(int.class, boolean.class));

  static int floatBits(float v) { return Float.floatToIntBits(v); }
  static long doubleBits(double v) { return Double.doubleToLongBits(v); }
  static int hashLong(long v) { return Internal.hashLong(v); }
  static int hashBoolean(boolean v) { return Internal.hashBoolean(v); }
}
