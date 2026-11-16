package dev.indyproto.runtime;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.GeneratedMessage.FieldAccessorTable;
import com.google.protobuf.Internal;
import com.google.protobuf.Parser;
import java.lang.classfile.CodeBuilder;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Bootstrap methods referenced from generated class files.
 *
 * <p>Two families:
 *
 * <ul>
 *   <li><b>condy</b> ({@code ldc} of a dynamic constant): file / message / enum descriptors,
 *       field accessor tables, default instances, parsers and enum value maps. They replace the
 *       {@code <clinit>} work and the anonymous classes of protoc gencode and are resolved lazily,
 *       once per constant-pool entry.
 *   <li><b>indy</b> ({@link #bootstrap}): the bodies of writeTo, getSerializedSize, equals,
 *       hashCode, mergeFrom(CodedInputStream), mergeFrom(Message), buildPartial and clear. The
 *       static argument is the message's {@link Schema}.
 * </ul>
 *
 * <p>Strategy for the indy bodies is chosen with {@code -Dindyproto.strategy=hidden|mh}
 * (default {@code hidden}): spin a hidden nestmate class with straight-line code, or compose
 * MethodHandle combinators (only for the four "object methods"; the rest always spin).
 */
public final class ProtoBootstraps {
  private ProtoBootstraps() {}

  static final String STRATEGY = System.getProperty("indyproto.strategy", "hidden");
  static final boolean TRACE = Boolean.getBoolean("indyproto.trace");

  static {
    // Same contract protoc gencode enforces in every class: refuse to run on an older runtime.
    com.google.protobuf.RuntimeVersion.validateProtobufGencodeVersion(
        com.google.protobuf.RuntimeVersion.RuntimeDomain.PUBLIC, 4, 36, 2, "", "indyproto");
  }

  // ==============================================================================================
  // condy

  /** {@code args} = descriptor data chunks (String, ISO-8859-1) followed by dependency classes. */
  public static FileDescriptor fileDescriptor(Lookup lookup, String name, Class<?> type,
      Object... args) throws Throwable {
    List<String> parts = new ArrayList<>();
    List<FileDescriptor> deps = new ArrayList<>();
    for (Object a : args) {
      if (a instanceof String s) {
        parts.add(s);
      } else {
        Class<?> dep = (Class<?>) a;
        MethodHandle get = MethodHandles.publicLookup().findStatic(dep, "getDescriptor",
            MethodType.methodType(FileDescriptor.class));
        deps.add((FileDescriptor) get.invoke());
      }
    }
    FileDescriptor fd = FileDescriptor.internalBuildGeneratedFileFrom(
        parts.toArray(String[]::new), deps.toArray(FileDescriptor[]::new));
    fd.resolveAllFeaturesImmutable();
    trace("condy fileDescriptor", lookup.lookupClass());
    return fd;
  }

  /** {@code path} = message index in file, then nested indexes. */
  public static Descriptor messageDescriptor(Lookup lookup, String name, Class<?> type,
      Class<?> outer, Object... path) throws Throwable {
    FileDescriptor fd = fileOf(outer);
    Descriptor d = fd.getMessageTypes().get((Integer) path[0]);
    for (int i = 1; i < path.length; i++) d = d.getNestedTypes().get((Integer) path[i]);
    return d;
  }

  /** {@code path} = containing message path (possibly empty), then the enum index. */
  public static EnumDescriptor enumDescriptor(Lookup lookup, String name, Class<?> type,
      Class<?> outer, Object... path) throws Throwable {
    FileDescriptor fd = fileOf(outer);
    int last = (Integer) path[path.length - 1];
    if (path.length == 1) return fd.getEnumTypes().get(last);
    Descriptor d = fd.getMessageTypes().get((Integer) path[0]);
    for (int i = 1; i < path.length - 1; i++) d = d.getNestedTypes().get((Integer) path[i]);
    return d.getEnumTypes().get(last);
  }

  private static FileDescriptor fileOf(Class<?> outer) throws Throwable {
    return (FileDescriptor) MethodHandles.publicLookup()
        .findStatic(outer, "getDescriptor", MethodType.methodType(FileDescriptor.class)).invoke();
  }

  private static final ConcurrentHashMap<Class<?>, FieldAccessorTable> TABLES =
      new ConcurrentHashMap<>();

  /** One table per message class, shared by the message's and the builder's constant pools. */
  public static FieldAccessorTable accessorTable(Lookup lookup, String name, Class<?> type,
      Class<?> msgClass, Class<?> builderClass, String camelNames) {
    return TABLES.computeIfAbsent(msgClass, c -> {
      try {
        Descriptor d = (Descriptor) MethodHandles.publicLookup()
            .findStatic(c, "getDescriptor", MethodType.methodType(Descriptor.class)).invoke();
        String[] names = camelNames.isEmpty() ? new String[0] : camelNames.split(",");
        trace("condy accessorTable", c);
        return ensure(new FieldAccessorTable(d, names), c, builderClass);
      } catch (Throwable t) {
        throw new IllegalStateException(t);
      }
    });
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static FieldAccessorTable ensure(FieldAccessorTable t, Class<?> msg, Class<?> builder) {
    return t.ensureFieldAccessorsInitialized((Class) msg, (Class) builder);
  }

  /** {@code new M()} through the message's private no-arg constructor. */
  public static Object defaultInstance(Lookup lookup, String name, Class<?> type) throws Throwable {
    trace("condy defaultInstance", type);
    return lookup.findConstructor(type, MethodType.methodType(void.class)).invoke();
  }

  public static Parser<?> parser(Lookup lookup, String name, Class<?> type, Class<?> msgClass)
      throws Throwable {
    trace("condy parser", msgClass);
    Lookup hidden = new Spinner(lookup, msgClass, Schema.decode("")).parserClass();
    return (Parser<?>) hidden.findConstructor(hidden.lookupClass(),
        MethodType.methodType(void.class)).invoke();
  }

  public static Internal.EnumLiteMap<?> enumValueMap(Lookup lookup, String name, Class<?> type,
      Class<?> enumClass) throws Throwable {
    MethodHandle forNumber = lookup.findStatic(enumClass, "forNumber",
        MethodType.methodType(enumClass, int.class));
    return new Internal.EnumLiteMap<Internal.EnumLite>() {
      @Override
      public Internal.EnumLite findValueByNumber(int number) {
        try {
          return (Internal.EnumLite) forNumber.invoke(number);
        } catch (Throwable t) {
          throw new IllegalStateException(t);
        }
      }
    };
  }

  /** Converter for repeated enum list views ({@code getFooList()} returning {@code List<Enum>}). */
  public static Internal.IntListAdapter.IntConverter<?> enumConverter(Lookup lookup, String name,
      Class<?> type, Class<?> enumClass, String unrecognized) throws Throwable {
    MethodHandle forNumber = lookup.findStatic(enumClass, "forNumber",
        MethodType.methodType(enumClass, int.class));
    Object fallback = unrecognized.isEmpty()
        ? null
        : lookup.findStaticGetter(enumClass, unrecognized, enumClass).invoke();
    return from -> {
      try {
        Object r = forNumber.invoke(from);
        return r == null ? fallback : r;
      } catch (Throwable t) {
        throw new IllegalStateException(t);
      }
    };
  }

  // ==============================================================================================
  // indy

  public static CallSite bootstrap(Lookup lookup, String name, MethodType type, String encoded)
      throws Throwable {
    Schema schema = Schema.decode(encoded);
    boolean builderSide = switch (name) {
      case "mergeFrom", "mergeFromMessage", "buildPartial", "clear" -> true;
      default -> false;
    };
    Class<?> msgClass = builderSide
        ? type.parameterType(0).getDeclaringClass()
        : type.parameterType(0);
    trace("indy " + name + " [" + STRATEGY + "]", msgClass);

    if (STRATEGY.equals("mh") && !builderSide) {
      MethodHandle mh = MhLinker.link(lookup, name, type, msgClass, schema);
      return new ConstantCallSite(mh.asType(type));
    }

    Spinner sp = new Spinner(lookup, msgClass, schema);
    BiConsumer<Spinner, CodeBuilder> gen = switch (name) {
      case "writeTo" -> Spinner::writeTo;
      case "serializedSize" -> Spinner::serializedSize;
      case "equals" -> Spinner::equals;
      case "hashCode" -> Spinner::hashCode;
      case "mergeFrom" -> Spinner::mergeFromStream;
      case "mergeFromMessage" -> Spinner::mergeFromMessage;
      case "buildPartial" -> Spinner::buildPartial;
      case "clear" -> Spinner::clear;
      default -> throw new IllegalArgumentException("unknown op " + name);
    };
    Lookup hidden = sp.spin(name, type, cb -> gen.accept(sp, cb));
    return new ConstantCallSite(hidden.findStatic(hidden.lookupClass(), Spinner.OP_METHOD, type));
  }

  static void trace(String what, Class<?> c) {
    if (TRACE) System.err.println("[indyproto] " + what + " " + c.getName());
  }
}
