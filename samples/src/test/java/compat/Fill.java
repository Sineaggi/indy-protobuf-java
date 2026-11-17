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
    for (FieldDescriptor f : b.getDescriptorForType().getFields()) {
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
      } else if (r.nextInt(4) != 0) {
        if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE && depth > 2) continue;
        b.setField(f, value(b, f, r, depth));
      }
    }
    return b;
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
        yield e.getValues().get(r.nextInt(e.getValues().size()));
      }
      case MESSAGE -> {
        Message.Builder sub = b.newBuilderForField(f);
        yield fill(sub, r, depth + 1).build();
      }
    };
  }

  private static String string(Random r) {
    String[] pool = {"", "a", "héllo", "日本語", "emoji 😀", "x".repeat(200), "line\nbreak"};
    return pool[r.nextInt(pool.length)];
  }
}
