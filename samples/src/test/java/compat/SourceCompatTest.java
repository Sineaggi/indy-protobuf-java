package compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The same user source compiled against protoc's classes and the indy classes behaves the same. */
class SourceCompatTest {
  @Test
  void sameBehaviour() throws Exception {
    List<String> stock = compat.stock.UserCode.run();
    List<String> indy = compat.indy.UserCode.run();
    stock.forEach(l -> System.out.println("stock: " + l));
    assertEquals(String.join("\n", stock), String.join("\n", indy));
  }
}
