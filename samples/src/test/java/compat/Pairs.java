package compat;

import com.google.protobuf.Message;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

/** Maps a stock (protoc) class to its indy counterpart: same name under the "indy." prefix. */
final class Pairs {
  private Pairs() {}

  static final List<String> MESSAGES = List.of(
      "sample.Person", "sample.Address", "sample.Person$Note",
      "sample.legacy.Legacy$Event", "sample.legacy.Legacy$Batch",
      "bench.Order", "bench.LineItem", "wide.Wide", "many.M0", "many.M399",
      "shapes.Shape", "shapes.Point", "shapes.Shapes2$Legacy",
      "shapes.full.Full2$Full", "shapes.full.Full2$Inner", "shapes.full.Full2$Plain",
      "shapes.full.Full2$Full$G", "shapes.full.Full2$Full$R",
      "shapes.ext.Ext2$Base", "shapes.ext.Ext2$Scope", "shapes.ext.Ext2$ReqExt");

  static Stream<String> messages() {
    return MESSAGES.stream();
  }

  static Class<?> stock(String name) throws ClassNotFoundException {
    return Class.forName(name);
  }

  static Class<?> indy(String name) throws ClassNotFoundException {
    return Class.forName("indy." + name);
  }

  static Message.Builder newBuilder(Class<?> c) throws Exception {
    Method m = c.getMethod("newBuilder");
    return (Message.Builder) m.invoke(null);
  }

  static Message parse(Class<?> c, byte[] bytes) throws Exception {
    return (Message) c.getMethod("parseFrom", byte[].class, com.google.protobuf.ExtensionRegistryLite.class)
        .invoke(null, (Object) bytes, registry(c));
  }

  /** The extensions of the message's file, registered through its outer class. */
  static com.google.protobuf.ExtensionRegistry registry(Class<?> c) throws Exception {
    var r = com.google.protobuf.ExtensionRegistry.newInstance();
    Class<?> top = c;
    while (top.getEnclosingClass() != null) top = top.getEnclosingClass();
    try {
      top.getMethod("registerAllExtensions", com.google.protobuf.ExtensionRegistry.class).invoke(null, r);
    } catch (NoSuchMethodException e) {
      // java_multiple_files: no outer class enclosing the message (none of those declare extensions)
    }
    return r;
  }
}
