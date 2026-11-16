#!/usr/bin/env bash
# Steady-state JMH: protoc vs indy (hidden-class strategy) vs indy (MethodHandle strategy).
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVA=${JAVA_HOME:-/usr}/bin/java
CP=$(./gradlew -q :bench:printClasspath 2>/dev/null | tail -1):bench/build/classes/java/main
OUT=bench/build/jmh
mkdir -p "$OUT"
"$JAVA" -cp "$CP" org.openjdk.jmh.Main ProtoBench -p impl=stock,indy \
  -jvmArgsAppend -Dindyproto.strategy=hidden -rf json -rff "$OUT/hidden.json" "$@" | tee "$OUT/hidden.txt"
"$JAVA" -cp "$CP" org.openjdk.jmh.Main ProtoBench -p impl=indy \
  -jvmArgsAppend -Dindyproto.strategy=mh -rf json -rff "$OUT/mh.json" "$@" | tee "$OUT/mh.txt"
