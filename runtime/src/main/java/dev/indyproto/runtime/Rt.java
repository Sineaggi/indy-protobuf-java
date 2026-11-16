package dev.indyproto.runtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.Internal;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import java.io.IOException;
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
}
