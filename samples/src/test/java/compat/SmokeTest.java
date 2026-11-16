package compat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

class SmokeTest {
  @Test
  void roundTrip() throws Exception {
    indy.sample.Person p = indy.sample.Person.newBuilder()
        .setName("Ada").setId(42).setAge(36)
        .setAddress(indy.sample.Address.newBuilder().setStreet("Main").setZip(12345).addLines("a"))
        .addTags("x").addTags("y").addScores(1).addScores(2).addScores(300)
        .setKind(indy.sample.Kind.KIND_B).setBlob(ByteString.copyFromUtf8("hi"))
        .setWeight(1.5).setActive(true).addKinds(indy.sample.Kind.KIND_A)
        .build();
    byte[] bytes = p.toByteArray();
    sample.Person stock = sample.Person.parseFrom(bytes);
    assertEquals("Ada", stock.getName());
    assertEquals(36, stock.getAge());
    assertEquals(12345, stock.getAddress().getZip());
    assertArrayEquals(stock.toByteArray(), bytes);
    indy.sample.Person back = indy.sample.Person.parseFrom(stock.toByteArray());
    assertEquals(p, back);
    assertEquals(p.hashCode(), back.hashCode());
    assertEquals(stock.toString(), back.toString());
  }
}
