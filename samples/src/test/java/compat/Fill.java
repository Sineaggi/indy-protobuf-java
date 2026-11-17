package compat;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.Random;

/** Populates any message through the reflection API (exercises FieldAccessorTable, too). */
final class Fill {
  private Fill() {}

  static Message fill(Message.Builder b, long seed) {
    return fill(b, new Random(seed), 0).build();
  }

  private static Message.Builder fill(Message.Builder b, Random r, int depth) {
    String full = b.getDescriptorForType().getFullName();
    if (full.equals("google.protobuf.Timestamp") || full.equals("google.protobuf.Duration")) {
      var d = b.getDescriptorForType();
      b.setField(d.findFieldByName("seconds"), (long) r.nextInt(2_000_000_000));
      b.setField(d.findFieldByName("nanos"), r.nextInt(1_000_000_000));
      return b;
    }
    java.util.List<FieldDescriptor> fields = new java.util.ArrayList<>(b.getDescriptorForType().getFields());
    fields.addAll(extensionsOf(b.getDescriptorForType()));
    for (FieldDescriptor f : fields) {
      if (f.isRepeated()) {
        int n = depth > 2 ? 0 : r.nextInt(4);
        // map keys stay distinct: a reflection-built map with duplicate keys keeps them all on the
        // wire, while any parsed copy keeps only the last, so the two would not be comparable
        java.util.Set<Object> keys = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
          Object v = value(b, f, r, depth);
          if (f.isMapField() && !keys.add(((Message) v).getField(f.getMessageType().findFieldByNumber(1)))) continue;
          b.addRepeatedField(f, v);
        }
      } else if (mustSet(b, f) || r.nextInt(4) != 0) {
        if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE && depth > 2 && !mustSet(b, f)) continue;
        b.setField(f, value(b, f, r, depth));
      }
    }
    return b;
  }

  /**
   * Required fields, and map values (whose default may itself lack required fields), are always
   * set, so build() succeeds. Uninitialized messages are covered by RequiredTest.
   */
  /** Extensions of {@code d} declared in its own file (file and message scope). */
  static java.util.List<FieldDescriptor> extensionsOf(com.google.protobuf.Descriptors.Descriptor d) {
    java.util.List<FieldDescriptor> out = new java.util.ArrayList<>();
    if (d.toProto().getExtensionRangeCount() == 0) return out;
    for (FieldDescriptor e : d.getFile().getExtensions()) if (e.getContainingType() == d) out.add(e);
    for (var m : d.getFile().getMessageTypes()) {
      for (FieldDescriptor e : m.getExtensions()) if (e.getContainingType() == d) out.add(e);
    }
    return out;
  }

  private static Message.Builder generatedBuilder(Message.Builder b, FieldDescriptor ext) {
    try {
      return Pairs.registry(b.getClass()).findImmutableExtensionByName(ext.getFullName())
          .defaultInstance.newBuilderForType();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static boolean mustSet(Message.Builder b, FieldDescriptor f) {
    return f.isRequired() || b.getDescriptorForType().getOptions().getMapEntry();
  }

  private static Object value(Message.Builder b, FieldDescriptor f, Random r, int depth) {
    return switch (f.getJavaType()) {
      case INT -> switch (r.nextInt(4)) { case 0 -> 0; case 1 -> -1; case 2 -> Integer.MAX_VALUE; default -> r.nextInt(); };
      case LONG -> switch (r.nextInt(4)) { case 0 -> 0L; case 1 -> -1L; case 2 -> Long.MIN_VALUE; default -> r.nextLong(); };
      case FLOAT -> r.nextInt(5) == 0 ? -0.0f : r.nextFloat() * 1000 - 500;
      case DOUBLE -> r.nextInt(5) == 0 ? -0.0 : r.nextDouble() * 1e6;
      case BOOLEAN -> r.nextBoolean();
      case STRING -> string(r);
      case BYTE_STRING -> {
        byte[] bs = new byte[r.nextInt(6)];
        r.nextBytes(bs);
        yield ByteString.copyFrom(bs);
      }
      case ENUM -> {
        EnumDescriptor e = f.getEnumType();
        // the canonical value of a number (generated enums collapse aliases; DynamicMessage does not)
        yield e.findValueByNumber(e.getValues().get(r.nextInt(e.getValues().size())).getNumber());
      }
      case MESSAGE -> {
        // newBuilderForField gives a DynamicMessage builder for message-typed extensions; use the
        // generated type (as parsing with a registry would), so built and parsed copies compare
        Message.Builder sub = f.isExtension() ? generatedBuilder(b, f) : b.newBuilderForField(f);
        yield fill(sub, r, depth + 1).build();
      }
    };
  }

  private static String string(Random r) {
    String[] pool = {"", "a", "héllo", "日本語", "emoji 😀", "x".repeat(200), "line\nbreak"};
    return pool[r.nextInt(pool.length)];
  }
}
