# indy-protobuf-java

A prototype protobuf-java code generator. It writes class files directly with the JDK 25 ClassFile API (`java.lang.classfile`). The bodies of `writeTo`, `getSerializedSize`, `equals`, `hashCode`, `mergeFrom`, `buildPartial` and `clear` are `invokedynamic` call sites linked by a small runtime. Descriptors, default instances and parsers are `ldc` of dynamic constants.

The goal is drop-in compatibility with protoc 4.36.2 Java gencode on the full runtime. That means the same public API (`GeneratedMessage`, builders, `OrBuilder`s, enums), byte-identical wire output, and working `JsonFormat`, `TextFormat`, `DynamicMessage`, reflection and Java serialization.

## Requirements

- JDK 25
- Gradle 9.9.1 (wrapper included)
- protoc 4.36.2, which the build fetches from Maven Central

## Layout

| Module | Contents |
| --- | --- |
| `runtime` | `ProtoBootstraps` (indy and condy bootstraps), `Spinner` (hidden-class codegen), `MhLinker` (MethodHandle-combinator strategy), `Rt`, `Schema`, `Stats` |
| `generator` | `protoc-gen-indy` protoc plugin (`Plugin`), and the `Gen` CLI: `--descriptor-set set.pb --out DIR [--rewrite from=to] [files...]` |
| `samples` | Test protos, the protoc and indy generation pipeline, and the compatibility tests |
| `bench` | JMH, the startup harness, the size report |

## Commands

```
./gradlew :samples:test                  # compatibility tests (hidden-class strategy)
./gradlew :samples:test -Pstrategy=mh    # same tests, MH-combinator strategy
./gradlew :bench:sizeReport              # class / jar / method counts, protoc vs indy
bench/scripts/startup.sh 7               # startup, metaspace, AOT cache (median of 7 JVMs)
bench/scripts/jmh.sh                     # steady-state JMH -> bench/build/jmh/
```

## Running as a protoc plugin

`./gradlew :generator:protocGenJar` builds a self-contained `generator/build/protoc-gen/protoc-gen-indy.jar`. `./gradlew :generator:installDist` also puts `protoc-gen-indy` and `protoc-gen-indy.bat` launchers in `generator/build/install/generator/bin/`. Point protoc at either one:

```sh
protoc --plugin=protoc-gen-indy=generator/build/install/generator/bin/protoc-gen-indy \
       --indy_out=build/classes foo.proto            # .class files under build/classes
protoc --plugin=... --indy_out=build/foo-indy.jar foo.proto   # protoc writes the jar itself
protoc --plugin=... --indy_opt=rewrite=com.foo=indy.com.foo --indy_out=... foo.proto
```

The plugin writes compiled classes, not Java sources, so its output goes on the classpath next to `runtime` and protobuf-java, with no compile step. As with `--java_out`, it generates only the files named on the command line, not their imports. The only option is `rewrite=FROM=TO` (repeatable, comma separated), which moves a Java package. Editions files are refused by protoc, because the plugin doesn't declare editions support.

The samples build generates its indy classes this way, and `:samples:check` verifies that the `Gen` CLI produces byte-identical classes.

## Runtime flags

- `-Dindyproto.strategy=hidden|mh`: link by spinning hidden classes (the default), or by MethodHandle combinators for write, size, equals and hashCode
- `-Dindyproto.spin=per-class|per-op`: one hidden class per message or builder (the default), or one per call site
- `-Dindyproto.stats=true`: print bootstrap and spin timing at exit
- `-Dindyproto.trace=true`: log each bootstrap

### Developer diagnostics

These help diagnose the runtime itself, like the JDK's own `dumpProxyClassFiles` flags. They are not meant for production.

- `-Dindyproto.dump=DIR` writes every spun hidden class to `DIR/<binary name>.class` (for example `indy/shapes/Point$$Indy$Ops.class`) before defining it, so a class that fails verification is on disk too. Read the classes with `javap -c -p` or a decompiler.
- In spun code, line numbers are proto field numbers, and the source file names the message. The JVM leaves hidden-class frames out of stack traces by default. Add `-XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames` to see them:

  ```
  at indy.shapes.Point$$Indy$Ops/0x0000000046054000.writeTo(Point field:2)
  ```

  Line 0 is code for no single field, such as extensions or unknown fields. Field numbers above 65535 show as 65535. Debuggers and `StackWalker` with `SHOW_HIDDEN_FRAMES` see the same lines.

`message_set_wire_format` messages hold only extensions, so their `writeTo` and `getSerializedSize` are emitted directly, as protoc does, with no call site to link.

## Not supported

protobuf-javalite/Android is out of scope: the generated classes need JDK 25 (`java.lang.classfile` at link time, hidden classes), which Android doesn't provide. With `-Dindyproto.strategy=mh`, `writeTo` of extendable messages is still spun as a hidden class, because the extension serializer is a cursor shared across field writes.
