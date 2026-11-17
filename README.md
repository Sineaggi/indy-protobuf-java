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
| `generator` | `Gen` CLI: `--descriptor-set set.pb --out DIR [--rewrite from=to] [files...]` |
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

## Runtime flags

- `-Dindyproto.strategy=hidden|mh`: link by spinning hidden classes (the default), or by MethodHandle combinators for write, size, equals and hashCode
- `-Dindyproto.spin=per-class|per-op`: one hidden class per message or builder (the default), or one per call site
- `-Dindyproto.stats=true`: print bootstrap and spin timing at exit
- `-Dindyproto.trace=true`: log each bootstrap

`message_set_wire_format` messages hold only extensions, so their `writeTo` and `getSerializedSize` are emitted directly, as protoc does, with no call site to link.

## Not supported yet

protobuf-javalite/Android. With `-Dindyproto.strategy=mh`, `writeTo` of extendable messages is still spun as a hidden class, because the extension serializer is a cursor shared across field writes.
