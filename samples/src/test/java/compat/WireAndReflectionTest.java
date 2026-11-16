package compat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * For every sample message type and many random instances: the indy classes must be
 * byte-for-byte wire compatible with protoc's, and behave the same under the protobuf-java
 * runtime's reflective machinery (TextFormat, JsonFormat, DynamicMessage, Java serialization).
 */
class WireAndReflectionTest {

  static Stream<Arguments> cases() {
    return Pairs.messages().flatMap(n -> IntStream.range(0, 25).mapToObj(s -> Arguments.of(n, s)));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("cases")
  void sameBehaviourAsProtoc(String name, int seed) throws Exception {
    Class<?> sc = Pairs.stock(name), ic = Pairs.indy(name);
    Message stock = Fill.fill(Pairs.newBuilder(sc), seed);
    Message viaReflection = Fill.fill(Pairs.newBuilder(ic), seed);
    byte[] bytes = stock.toByteArray();

    // wire: protoc -> indy -> protoc, and reflection-built indy == parsed indy
    Message parsed = Pairs.parse(ic, bytes);
    assertArrayEquals(bytes, parsed.toByteArray(), "re-serialized bytes");
    assertArrayEquals(bytes, viaReflection.toByteArray(), "reflection-built bytes");
    assertEquals(stock.getSerializedSize(), parsed.getSerializedSize());
    assertEquals(parsed, viaReflection);
    assertEquals(parsed.hashCode(), viaReflection.hashCode());
    assertEquals(stock, Pairs.parse(sc, parsed.toByteArray()));

    // text + json through the runtime's reflection
    assertEquals(TextFormat.printer().printToString(stock), TextFormat.printer().printToString(parsed));
    String json = JsonFormat.printer().print(stock);
    assertEquals(json, JsonFormat.printer().print(parsed));
    Message.Builder jb = Pairs.newBuilder(ic);
    JsonFormat.parser().merge(json, jb);
    Message.Builder sjb = Pairs.newBuilder(sc);
    JsonFormat.parser().merge(json, sjb);
    // (JSON does not preserve -0.0 exactly, so compare against protoc's result, not the input)
    assertArrayEquals(sjb.build().toByteArray(), jb.build().toByteArray(), "json parse");
    Message.Builder tb = Pairs.newBuilder(ic);
    TextFormat.merge(TextFormat.printer().printToString(stock), tb);
    assertEquals(parsed, tb.build());

    // DynamicMessage built from the indy descriptor
    DynamicMessage dyn = DynamicMessage.parseFrom(parsed.getDescriptorForType(), bytes);
    assertArrayEquals(bytes, dyn.toByteArray());
    assertEquals(parsed, dyn);
    assertEquals(parsed, Pairs.newBuilder(ic).mergeFrom(dyn).build());

    // merge semantics: m.mergeFrom(m) == parse(bytes ++ bytes)
    byte[] twice = concat(bytes, bytes);
    Message mergedStock = stock.toBuilder().mergeFrom(stock).build();
    Message mergedIndy = parsed.toBuilder().mergeFrom(parsed).build();
    assertArrayEquals(mergedStock.toByteArray(), mergedIndy.toByteArray(), "mergeFrom(message)");
    assertArrayEquals(Pairs.parse(sc, twice).toByteArray(), Pairs.parse(ic, twice).toByteArray(), "parse concat");

    // builders: copy, clear, default instance
    assertEquals(parsed, parsed.toBuilder().build());
    assertEquals(parsed.getDefaultInstanceForType(), parsed.toBuilder().clear().build());
    assertSame(parsed.getDefaultInstanceForType(), Pairs.newBuilder(ic).getDefaultInstanceForType());

    // delimited streams
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    parsed.writeDelimitedTo(bos);
    parsed.writeDelimitedTo(bos);
    var in = new ByteArrayInputStream(bos.toByteArray());
    assertEquals(parsed, parsed.getParserForType().parseDelimitedFrom(in));
    assertEquals(parsed, parsed.getParserForType().parseDelimitedFrom(in));

    // java serialization (GeneratedMessage.writeReplace -> SerializedForm)
    ByteArrayOutputStream jos = new ByteArrayOutputStream();
    try (var oos = new ObjectOutputStream(jos)) {
      oos.writeObject(parsed);
    }
    try (var ois = new ObjectInputStream(new ByteArrayInputStream(jos.toByteArray()))) {
      assertEquals(parsed, ois.readObject());
    }
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] r = new byte[a.length + b.length];
    System.arraycopy(a, 0, r, 0, a.length);
    System.arraycopy(b, 0, r, a.length, b.length);
    return r;
  }
}
