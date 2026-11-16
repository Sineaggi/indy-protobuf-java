package bench.jmh;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

/** Fixed benchmark payloads, built with the protoc API and re-parsed into either implementation. */
final class Messages {
  private Messages() {}

  static byte[] bytes(String which) {
    return switch (which) {
      case "order" -> order().toByteArray();
      case "person" -> person().toByteArray();
      case "wide" -> wide().toByteArray();
      default -> throw new IllegalArgumentException(which);
    };
  }

  static Message parse(String impl, String which, byte[] b) throws Exception {
    boolean stock = impl.equals("stock");
    return switch (which) {
      case "order" -> stock ? bench.Order.parseFrom(b) : indy.bench.Order.parseFrom(b);
      case "person" -> stock ? sample.Person.parseFrom(b) : indy.sample.Person.parseFrom(b);
      case "wide" -> stock ? wide.Wide.parseFrom(b) : indy.wide.Wide.parseFrom(b);
      default -> throw new IllegalArgumentException(which);
    };
  }

  static bench.Order order() {
    bench.Order.Builder o = bench.Order.newBuilder()
        .setId("ord-2026-11-16-000123").setCreatedAt(1_794_867_867_000L).setCustomer("customer-42")
        .setTotal(bench.Money.newBuilder().setCurrency("USD").setUnits(1234).setNanos(500_000_000))
        .addAllCouponIds(java.util.List.of(10, 20, 30)).setNote("leave at the door")
        .setSignature(ByteString.copyFrom(new byte[64])).setReferrer("newsletter").setStatus(3);
    for (int i = 0; i < 8; i++) {
      o.addItems(bench.LineItem.newBuilder().setSku("SKU-" + i).setTitle("Item number " + i)
          .setQuantity(i + 1).setPrice(bench.Money.newBuilder().setCurrency("USD").setUnits(10 + i))
          .addLabels("new").addLabels("sale").setGift(i % 3 == 0).setWeight(0.25 * i));
    }
    return o.build();
  }

  static sample.Person person() {
    return sample.Person.newBuilder().setName("Ada Lovelace").setId(1815).setAge(36)
        .setAddress(sample.Address.newBuilder().setStreet("St James's Square").setZip(12345).addLines("London"))
        .addTags("math").addTags("engines").addAllScores(java.util.List.of(1, 2, 3, 400, 50000))
        .setKind(sample.Kind.KIND_A).setBlob(ByteString.copyFromUtf8("note g")).setWeight(55.5)
        .setActive(true).addKinds(sample.Kind.KIND_B).setRatio(0.5f).setDelta(-7).setStamp(99)
        .addSamples(1.0).addSamples(2.0).setNickname("Enchantress of Numbers").setBig(1L << 40)
        .addNotes(sample.Person.Note.newBuilder().setText("first program")).build();
  }

  static wide.Wide wide() {
    wide.Wide.Builder b = wide.Wide.newBuilder();
    var d = wide.Wide.getDescriptor();
    for (var f : d.getFields()) {
      if (f.isRepeated()) {
        for (int i = 0; i < 4; i++) {
          b.addRepeatedField(f, f.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.STRING ? "s" + i : i * 7);
        }
        continue;
      }
      Object v = switch (f.getJavaType()) {
        case INT -> f.getNumber() * 3;
        case LONG -> f.getNumber() * 1_000_003L;
        case FLOAT -> f.getNumber() * 0.5f;
        case DOUBLE -> f.getNumber() * 0.25;
        case BOOLEAN -> true;
        case STRING -> "field-" + f.getNumber();
        case BYTE_STRING -> ByteString.copyFromUtf8("b" + f.getNumber());
        default -> throw new IllegalStateException();
      };
      b.setField(f, v);
    }
    return b.build();
  }
}
