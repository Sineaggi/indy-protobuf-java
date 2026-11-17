package compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binary/source API parity: every public or protected member protoc generates (including the
 * covariant bridge methods javac emits, generic signatures and checked exceptions) must exist on
 * the indy class with the same shape.
 */
class PublicApiTest {

  static Stream<String> classes() {
    List<String> out = new ArrayList<>();
    for (String m : Pairs.MESSAGES) {
      out.add(m);
      out.add(m + "$Builder");
      out.add(m + "OrBuilder");
    }
    out.addAll(List.of("sample.Kind", "sample.legacy.Legacy$Event$Level", "many.Color",
        "sample.SampleProto", "sample.legacy.Legacy", "bench.Bench", "wide.Wide$Builder",
        "shapes.Shape$KindCase", "shapes.Shape$SecondCase", "shapes.Shapes2$Legacy$PickCase",
        "shapes.Shapes2", "shapes.Unit", "shapes.Shapes2$Level", "shapes.Shapes",
        "shapes.full.Full2", "shapes.full.Full2$Color", "shapes.full.Full2$Full$PickCase"));
    return out.stream().distinct();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("classes")
  void sameApi(String name) throws Exception {
    Class<?> stock = Pairs.stock(name), indy = Pairs.indy(name);
    String want = describe(stock), got = describe(indy);
    java.nio.file.Path dir = java.nio.file.Path.of("build", "api-diff");
    java.nio.file.Files.createDirectories(dir);
    java.nio.file.Files.writeString(dir.resolve(name + ".stock.txt"), want);
    java.nio.file.Files.writeString(dir.resolve(name + ".indy.txt"), got);
    assertEquals(want, got);
  }

  static String describe(Class<?> c) {
    int keep = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL
        | Modifier.ABSTRACT | Modifier.INTERFACE;
    List<String> lines = new ArrayList<>();
    lines.add("class " + Modifier.toString(c.getModifiers() & keep) + " " + c.getName()
        + " extends " + c.getGenericSuperclass()
        + " implements " + Arrays.toString(c.getGenericInterfaces()));
    TreeSet<String> members = new TreeSet<>();
    for (Method m : c.getDeclaredMethods()) {
      int mod = m.getModifiers();
      if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue;
      // 'final' on methods of a final class has no effect on callers; protoc is inconsistent.
      int shown = mod & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.ABSTRACT);
      String params = Arrays.stream(m.getGenericParameterTypes()).map(Object::toString)
          .collect(Collectors.joining(", "));
      String ex = Arrays.stream(m.getExceptionTypes()).map(Class::getName).sorted()
          .collect(Collectors.joining(", "));
      members.add((m.isBridge() ? "bridge " : "") + Modifier.toString(shown) + " "
          + m.getGenericReturnType().getTypeName() + " " + m.getName() + "(" + params + ")"
          + (ex.isEmpty() ? "" : " throws " + ex));
    }
    for (Field f : c.getDeclaredFields()) {
      int mod = f.getModifiers();
      if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue;
      members.add("field " + Modifier.toString(mod) + " " + f.getGenericType().getTypeName() + " " + f.getName());
    }
    for (Class<?> n : c.getDeclaredClasses()) {
      if (Modifier.isPublic(n.getModifiers())) members.add("nested " + n.getName());
    }
    for (String mbr : members) lines.add(mbr);
    lines.replaceAll(l -> l.replace("indy.", ""));
    java.util.Collections.sort(lines.subList(1, lines.size()));
    return String.join("\n", lines);
  }
}
