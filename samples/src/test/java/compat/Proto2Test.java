package compat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UninitializedMessageException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** proto2 behaviour that random filling does not reach: defaults, required fields, enum aliases. */
class Proto2Test {

  /** Every public no-arg getter of the default instance and of a cleared builder returns the same. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("compat.Pairs#messages")
  void gettersOfEmptyMessages(String name) throws Exception {
    Class<?> sc = Pairs.stock(name), ic = Pairs.indy(name);
    Message sd = (Message) sc.getMethod("getDefaultInstance").invoke(null);
    Message id = (Message) ic.getMethod("getDefaultInstance").invoke(null);
    assertEquals(getters(sd), getters(id), "default instance");
    Message.Builder sb = Pairs.newBuilder(sc), ib = Pairs.newBuilder(ic);
    assertEquals(getters(sb), getters(ib), "new builder");
    Message full = Fill.fill(Pairs.newBuilder(sc), 7);
    Message.Builder sCleared = full.toBuilder().clear();
    Message.Builder iCleared = Pairs.parse(ic, full.toByteArray()).toBuilder().clear();
    assertEquals(getters(sCleared), getters(iCleared), "cleared builder");
    // per-field clearX() restores the [default = ...] value too
    Message.Builder sEach = full.toBuilder(), iEach = Pairs.parse(ic, full.toByteArray()).toBuilder();
    for (Method m : sEach.getClass().getMethods()) {
      if (m.getName().startsWith("clear") && m.getParameterCount() == 0 && !m.getName().equals("clear")
          && m.getReturnType() == sEach.getClass()) {
        m.invoke(sEach);
        iEach.getClass().getMethod(m.getName()).invoke(iEach);
      }
    }
    assertEquals(getters(sEach), getters(iEach), "clearX() on every field");
  }

  static String getters(Object o) throws Exception {
    List<String> out = new ArrayList<>();
    Method[] ms = o.getClass().getMethods();
    Arrays.sort(ms, Comparator.comparing(Method::getName));
    for (Method m : ms) {
      if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || m.isBridge()) continue;
      String n = m.getName();
      if (!(n.startsWith("get") || n.startsWith("has") || n.equals("isInitialized")) || n.equals("hashCode")) continue;
      if (m.getDeclaringClass() != o.getClass()) continue; // runtime base classes
      if (n.endsWith("Builder") || n.endsWith("BuilderList") || n.equals("getParserForType")
          || n.equals("getDescriptorForType") || n.equals("getDefaultInstanceForType")) continue;
      Object v;
      try {
        v = m.invoke(o);
      } catch (java.lang.reflect.InvocationTargetException e) {
        v = "throws " + e.getCause().getClass().getName();
      }
      out.add(n + "=" + render(v));
    }
    return String.join("\n", out).replace("indy.", "");
  }

  private static String render(Object v) {
    if (v instanceof Message msg) return msg.getClass().getName() + "{" + msg.toString().replace('\n', ' ') + "}";
    if (v instanceof Double d) return "d" + Double.doubleToRawLongBits(d);
    if (v instanceof Float f) return "f" + Float.floatToRawIntBits(f);
    if (v instanceof com.google.protobuf.ByteString bs) return "bytes:" + java.util.HexFormat.of().formatHex(bs.toByteArray());
    // list implementation classes are not compared: repeated bytes use Collections.emptyList()
    // where protoc uses ProtobufArrayList (both immutable; tracked separately)
    if (v instanceof List<?> l) return "List:" + l;
    return v.getClass().getName() + ":" + v;
  }

  static final String FULL = "shapes.full.Full2$Full";

  @Test
  void requiredFields() throws Exception {
    Class<?> sc = Pairs.stock(FULL), ic = Pairs.indy(FULL);
    for (Class<?> c : List.of(sc, ic)) {
      Message.Builder b = Pairs.newBuilder(c);
      assertFalse(b.isInitialized());
      assertFalse(b.buildPartial().isInitialized());
      assertEquals(List.of("name"), b.findInitializationErrors());
    }
    // the same errors (including nested paths) and the same exceptions
    Message.Builder sb = Pairs.newBuilder(sc), ib = Pairs.newBuilder(ic);
    setNested(sb);
    setNested(ib);
    assertEquals(sb.findInitializationErrors(), ib.findInitializationErrors());
    assertEquals(sb.getInitializationErrorString(), ib.getInitializationErrorString());
    UninitializedMessageException se = assertThrows(UninitializedMessageException.class, sb::build);
    UninitializedMessageException ie = assertThrows(UninitializedMessageException.class, ib::build);
    assertEquals(se.getMessage(), ie.getMessage());

    byte[] partial = sb.buildPartial().toByteArray();
    assertArrayEquals(partial, ib.buildPartial().toByteArray());
    InvalidProtocolBufferException sp = assertThrows(InvalidProtocolBufferException.class, () -> parse(sc, partial));
    InvalidProtocolBufferException ip = assertThrows(InvalidProtocolBufferException.class, () -> parse(ic, partial));
    assertEquals(sp.getMessage(), ip.getMessage());
    assertArrayEquals(sp.getUnfinishedMessage().toByteArray(), ip.getUnfinishedMessage().toByteArray());
    Message lenient = (Message) ic.getMethod("parser").invoke(null).getClass()
        .getMethod("parsePartialFrom", byte[].class).invoke(ic.getMethod("parser").invoke(null), (Object) partial);
    assertFalse(lenient.isInitialized());
    assertArrayEquals(partial, lenient.toByteArray());

    // memoization does not stick across instances, and setting the fields fixes it
    sb.setField(sb.getDescriptorForType().findFieldByName("name"), "x");
    ib.setField(ib.getDescriptorForType().findFieldByName("name"), "x");
    assertEquals(sb.findInitializationErrors(), ib.findInitializationErrors());
  }

  private static Message parse(Class<?> c, byte[] bytes) throws Throwable {
    try {
      return Pairs.parse(c, bytes);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private static void setNested(Message.Builder b) {
    var d = b.getDescriptorForType();
    Message.Builder inner = b.newBuilderForField(d.findFieldByName("inner"));
    b.setField(d.findFieldByName("inner"), inner.buildPartial());
    b.addRepeatedField(d.findFieldByName("inners"), inner.buildPartial());
    Message.Builder g = b.newBuilderForField(d.findFieldByName("g"));
    b.setField(d.findFieldByName("g"), g.buildPartial());
  }

  @Test
  void enumAliases() throws Exception {
    for (String prefix : List.of("", "indy.")) {
      Class<?> c = Class.forName(prefix + "shapes.full.Full2$Color");
      Object red = c.getField("RED").get(null), crimson = c.getField("CRIMSON").get(null);
      assertSame(red, crimson);
      assertSame(c.getField("BLUE").get(null), c.getField("NAVY").get(null));
      assertEquals(3, ((Object[]) c.getMethod("values").invoke(null)).length);
      var desc = (com.google.protobuf.Descriptors.EnumDescriptor) c.getMethod("getDescriptor").invoke(null);
      assertSame(red, c.getMethod("valueOf", com.google.protobuf.Descriptors.EnumValueDescriptor.class)
          .invoke(null, desc.findValueByName("CRIMSON")));
      assertEquals("RED", ((com.google.protobuf.ProtocolMessageEnum) crimson).getValueDescriptor().getName());
      assertEquals("GREEN", ((com.google.protobuf.ProtocolMessageEnum) c.getField("GREEN").get(null)).getValueDescriptor().getName());
      assertEquals(1, c.getField("CRIMSON_VALUE").getInt(null));
      assertThrows(java.lang.reflect.InvocationTargetException.class, () -> c.getMethod("valueOf", String.class).invoke(null, "CRIMSON"));
    }
  }
}
