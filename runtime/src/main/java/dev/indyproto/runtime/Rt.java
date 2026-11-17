package dev.indyproto.runtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.Internal;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MapEntry;
import com.google.protobuf.MapField;
import com.google.protobuf.MapFieldBuilder;
import com.google.protobuf.MessageOrBuilder;
import java.io.IOException;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Public static helpers called from generated stubs and from link-time spun code.
 *
 * <p>Generated code (and hidden classes spun at link time) is not a subclass of the protobuf
 * runtime classes in the JVM's sense, so it cannot reach the {@code protected static} helpers of
 * {@link GeneratedMessage} that protoc gencode uses. This class is an abstract subclass of
 * {@code GeneratedMessage} purely so that it may re-export those helpers publicly.
 */
public abstract class Rt extends GeneratedMessage {
  private Rt() {}

  // ---- strings stored as Object (String or ByteString), like protoc gencode ----

  public static boolean isStringEmpty(Object value) {
    return GeneratedMessage.isStringEmpty(value);
  }

  public static void writeString(CodedOutputStream out, int number, Object value)
      throws IOException {
    GeneratedMessage.writeString(out, number, value);
  }

  public static void writeStringNoTag(CodedOutputStream out, Object value) throws IOException {
    GeneratedMessage.writeStringNoTag(out, value);
  }

  public static int computeStringSize(int number, Object value) {
    return GeneratedMessage.computeStringSize(number, value);
  }

  public static int computeStringSizeNoTag(Object value) {
    return GeneratedMessage.computeStringSizeNoTag(value);
  }

  public static void checkUtf8(ByteString value) {
    if (!value.isValidUtf8()) {
      throw new IllegalArgumentException("Byte string is not UTF-8.");
    }
  }

  /** Lazily converts a String/ByteString slot to String, as protoc's getFoo() does. */
  public static String toStringValue(Object ref) {
    if (ref instanceof String s) return s;
    return ((ByteString) ref).toStringUtf8();
  }

  public static ByteString toBytesValue(Object ref) {
    if (ref instanceof String s) return ByteString.copyFromUtf8(s);
    return (ByteString) ref;
  }

  // ---- empty / mutable lists ----

  public static Internal.IntList emptyIntList() {
    return GeneratedMessage.emptyIntList();
  }

  public static Internal.LongList emptyLongList() {
    return GeneratedMessage.emptyLongList();
  }

  public static Internal.FloatList emptyFloatList() {
    return GeneratedMessage.emptyFloatList();
  }

  public static Internal.DoubleList emptyDoubleList() {
    return GeneratedMessage.emptyDoubleList();
  }

  public static Internal.BooleanList emptyBooleanList() {
    return GeneratedMessage.emptyBooleanList();
  }

  public static <L extends Internal.ProtobufList<?>> L mutableCopy(L list) {
    return GeneratedMessage.makeMutableCopy(list);
  }

  /** For message / bytes lists: an ArrayList is the builder's own mutable list. */
  public static <T> List<T> ensureMutable(List<T> list) {
    return list instanceof ArrayList<T> ? list : new ArrayList<>(list);
  }

  public static <T> List<T> makeImmutable(List<T> list) {
    return list instanceof ArrayList<T> ? Collections.unmodifiableList(list) : list;
  }

  public static void addAllInts(Iterable<? extends Integer> values, Internal.IntList list) {
    for (Integer v : values) list.addInt(v);
  }

  public static void addAllEnums(
      Iterable<? extends com.google.protobuf.ProtocolMessageEnum> values, Internal.IntList list) {
    for (com.google.protobuf.ProtocolMessageEnum v : values) list.addInt(v.getNumber());
  }

  /** {@code a.toBuilder().mergeFrom(b).buildPartial()}. */
  public static Message mergeMessage(Message a, MessageLite b) {
    return a.toBuilder().mergeFrom((Message) b).buildPartial();
  }

  // ---- write helpers used by the MethodHandle-combinator strategy ----

  public static int hashLong(long v) {
    return Internal.hashLong(v);
  }

  public static int hashBoolean(boolean v) {
    return Internal.hashBoolean(v);
  }

  public static int floatBits(float v) {
    return Float.floatToIntBits(v);
  }

  public static long doubleBits(double v) {
    return Double.doubleToLongBits(v);
  }

  // ---- oneof members: the value lives boxed in a shared Object slot ----------------------------
  // The kind is a link-time constant at every call site, so these switches fold after inlining.

  private static final Schema.Kind[] KINDS = wireKinds();

  /** Indexed by {@link Schema.Field#wireKind()}: the kinds, then MESSAGE again for groups. */
  static Schema.Kind[] wireKinds() {
    Schema.Kind[] k = java.util.Arrays.copyOf(Schema.Kind.values(), Schema.Kind.values().length + 1);
    k[k.length - 1] = Schema.Kind.MESSAGE;
    return k;
  }

  public static void writeOneof(CodedOutputStream out, int n, int kind, Object v)
      throws IOException {
    if (kind == Schema.Field.GROUP_WIRE_KIND) {
      out.writeGroup(n, (MessageLite) v);
      return;
    }
    switch (KINDS[kind]) {
      case INT32 -> out.writeInt32(n, (Integer) v);
      case INT64 -> out.writeInt64(n, (Long) v);
      case UINT32 -> out.writeUInt32(n, (Integer) v);
      case UINT64 -> out.writeUInt64(n, (Long) v);
      case SINT32 -> out.writeSInt32(n, (Integer) v);
      case SINT64 -> out.writeSInt64(n, (Long) v);
      case FIXED32 -> out.writeFixed32(n, (Integer) v);
      case FIXED64 -> out.writeFixed64(n, (Long) v);
      case SFIXED32 -> out.writeSFixed32(n, (Integer) v);
      case SFIXED64 -> out.writeSFixed64(n, (Long) v);
      case FLOAT -> out.writeFloat(n, (Float) v);
      case DOUBLE -> out.writeDouble(n, (Double) v);
      case BOOL -> out.writeBool(n, (Boolean) v);
      case ENUM -> out.writeEnum(n, (Integer) v);
      case STRING -> GeneratedMessage.writeString(out, n, v);
      case BYTES -> out.writeBytes(n, (ByteString) v);
      case MESSAGE -> out.writeMessage(n, (MessageLite) v);
      case MAP -> throw new AssertionError();
    }
  }

  public static int sizeOneof(int n, int kind, Object v) {
    if (kind == Schema.Field.GROUP_WIRE_KIND) return CodedOutputStream.computeGroupSize(n, (MessageLite) v);
    return switch (KINDS[kind]) {
      case INT32 -> CodedOutputStream.computeInt32Size(n, (Integer) v);
      case INT64 -> CodedOutputStream.computeInt64Size(n, (Long) v);
      case UINT32 -> CodedOutputStream.computeUInt32Size(n, (Integer) v);
      case UINT64 -> CodedOutputStream.computeUInt64Size(n, (Long) v);
      case SINT32 -> CodedOutputStream.computeSInt32Size(n, (Integer) v);
      case SINT64 -> CodedOutputStream.computeSInt64Size(n, (Long) v);
      case FIXED32 -> CodedOutputStream.computeFixed32Size(n, (Integer) v);
      case FIXED64 -> CodedOutputStream.computeFixed64Size(n, (Long) v);
      case SFIXED32 -> CodedOutputStream.computeSFixed32Size(n, (Integer) v);
      case SFIXED64 -> CodedOutputStream.computeSFixed64Size(n, (Long) v);
      case FLOAT -> CodedOutputStream.computeFloatSize(n, (Float) v);
      case DOUBLE -> CodedOutputStream.computeDoubleSize(n, (Double) v);
      case BOOL -> CodedOutputStream.computeBoolSize(n, (Boolean) v);
      case ENUM -> CodedOutputStream.computeEnumSize(n, (Integer) v);
      case STRING -> GeneratedMessage.computeStringSize(n, v);
      case BYTES -> CodedOutputStream.computeBytesSize(n, (ByteString) v);
      case MESSAGE -> CodedOutputStream.computeMessageSize(n, (MessageLite) v);
      case MAP -> throw new AssertionError();
    };
  }

  /** Values of the same member; strings compare as Java strings (String or ByteString slot). */
  public static boolean eqOneof(int kind, Object a, Object b) {
    if (KINDS[kind] == Schema.Kind.STRING) return toStringValue(a).equals(toStringValue(b));
    return a.equals(b); // boxed float/double equals compare bits, like protoc's generated code
  }

  /** protoc's per-value hash for a oneof member. */
  public static int hashOneof(int kind, Object v) {
    return switch (KINDS[kind]) {
      case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> Internal.hashLong((Long) v);
      case DOUBLE -> Internal.hashLong(Double.doubleToLongBits((Double) v));
      case FLOAT -> Float.floatToIntBits((Float) v);
      case BOOL -> Internal.hashBoolean((Boolean) v);
      case STRING -> toStringValue(v).hashCode();
      case MESSAGE, BYTES -> v.hashCode();
      default -> (Integer) v;
    };
  }

  // ---- map fields (storage is a MapField, null meaning empty) ----------------------------------

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static MapField mapOrEmpty(MapField f, MapEntry entry) {
    return f != null ? f : MapField.emptyMapField(entry);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static void writeMap(CodedOutputStream out, int n, int keyKind, MapField f,
      MapEntry entry) throws IOException {
    if (f == null) return;
    switch (KINDS[keyKind]) {
      case STRING -> GeneratedMessage.serializeStringMapTo(out, f, entry, n);
      case BOOL -> GeneratedMessage.serializeBooleanMapTo(out, f, entry, n);
      case INT64, UINT64, SINT64, FIXED64, SFIXED64 ->
          GeneratedMessage.serializeLongMapTo(out, f, entry, n);
      default -> GeneratedMessage.serializeIntegerMapTo(out, f, entry, n);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static int sizeMap(int n, MapField f, MapEntry entry) {
    if (f == null) return 0;
    int size = 0;
    for (Object o : f.getMap().entrySet()) {
      Map.Entry e = (Map.Entry) o;
      MapEntry m = entry.newBuilderForType().setKey(e.getKey()).setValue(e.getValue()).buildPartial();
      size += CodedOutputStream.computeMessageSize(n, m);
    }
    return size;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static boolean eqMap(MapField a, MapField b, MapEntry entry) {
    return mapOrEmpty(a, entry).equals(mapOrEmpty(b, entry));
  }

  /** {@code h} mixed with the map's hash, or {@code h} unchanged when the map is empty. */
  @SuppressWarnings("rawtypes")
  public static int mixMap(int h, int n, MapField f) {
    if (f == null || f.getMap().isEmpty()) return h;
    return 53 * (37 * h + n) + f.hashCode();
  }

  @SuppressWarnings("rawtypes")
  public static boolean mapNonEmpty(MapField f) {
    return f != null && !f.getMap().isEmpty();
  }

  /** Parses a closed-enum map entry; null when its value is not a known enum number. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static MapEntry parseClosedEnumEntry(ByteString bytes, MapEntry entry,
      Internal.EnumLiteMap<?> values) throws InvalidProtocolBufferException {
    MapEntry e = (MapEntry) entry.getParserForType().parseFrom(bytes);
    return values.findValueByNumber((Integer) e.getValue()) == null ? null : e;
  }

  /** Builder side of a map of messages: {@code putFooBuilderIfAbsent(key)}. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static Message.Builder builderIfAbsent(Map builderMap, Object key, Message prototype) {
    Object entry = builderMap.get(key);
    if (entry == null) {
      entry = prototype.newBuilderForType();
      builderMap.put(key, entry);
    }
    if (entry instanceof Message m) {
      entry = m.toBuilder();
      builderMap.put(key, entry);
    }
    return (Message.Builder) entry;
  }

  @SuppressWarnings("rawtypes")
  public static void requireEntriesNonNull(Map values) {
    for (Object o : values.entrySet()) {
      Map.Entry e = (Map.Entry) o;
      java.util.Objects.requireNonNull(e.getKey());
      java.util.Objects.requireNonNull(e.getValue());
    }
  }

  @SuppressWarnings("rawtypes")
  public static Object mapGetOrThrow(Map map, Object key) {
    if (!map.containsKey(key)) throw new IllegalArgumentException();
    return map.get(key);
  }

  @SuppressWarnings("rawtypes")
  public static Object mapGetOrDefault(Map map, Object key, Object dflt) {
    return map.containsKey(key) ? map.get(key) : dflt;
  }

  /** MapFieldBuilder.Converter for maps of messages: builders in the map are built on demand. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static final class MessageMapConverter implements MapFieldBuilder.Converter {
    private final MapEntry entry;

    public MessageMapConverter(MapEntry entry) {
      this.entry = entry;
    }

    @Override
    public MessageOrBuilder build(MessageOrBuilder val) {
      return val instanceof Message.Builder b ? b.build() : val;
    }

    @Override
    public MapEntry defaultEntry() {
      return entry;
    }
  }

  /** What protoc's internalGetMapFieldReflection throws for a number that is not a map field. */
  public static RuntimeException invalidMapField(int number) {
    return new RuntimeException("Invalid map field number: " + number);
  }

  /** Every element of a repeated message field / message-valued map is initialized. */
  public static boolean allInitialized(java.util.Collection<?> messages) {
    for (Object m : messages) {
      if (!((com.google.protobuf.MessageLiteOrBuilder) m).isInitialized()) return false;
    }
    return true;
  }
}
