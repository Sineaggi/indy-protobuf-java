#!/usr/bin/env bash
# Startup / first-call benchmark: fresh JVM per run, median of RUNS runs.
# usage: bench/scripts/startup.sh [RUNS]
set -euo pipefail
cd "$(dirname "$0")/../.."
RUNS=${1:-7}
JAVA=${JAVA_HOME:-/usr}/bin/java
OUT=bench/build/startup
mkdir -p "$OUT"
# The AOT cache only accepts jars on the class path, so jar up every directory entry.
RAWCP=$(./gradlew -q :bench:printClasspath 2>/dev/null | tail -1):bench/build/classes/java/main
CP=""
n=0
IFS=: read -ra parts <<< "$RAWCP"
for p in "${parts[@]}"; do
  if [ -d "$p" ]; then
    n=$((n + 1)); j="$OUT/cp$n.jar"; rm -f "$j"
    "${JAVA_HOME}/bin/jar" --create --file "$j" -C "$p" . 2>/dev/null
    p="$j"
  fi
  CP="${CP:+$CP:}$p"
done

run() { # label, then java args
  local label=$1; shift
  for i in $(seq "$RUNS"); do
    local s=$(date +%s%N)
    local line=$("$JAVA" -cp "$CP" "$@" 2>/dev/null)
    local e=$(date +%s%N)
    echo "$line wall_ms=$(( (e - s) / 1000000 ))"
  done | python3 -c '
import sys, statistics
rows=[dict(kv.split("=",1) for kv in l.split()) for l in sys.stdin if l.strip()]
keys=["touch_ms","again_us","wall_ms","metaspace_kb","ccs_kb","classes"]
med={k: statistics.median(float(r[k]) for r in rows) for k in keys}
print(sys.argv[1].ljust(34), "  ".join(f"{k}={med[k]:.1f}" for k in keys))' "$label"
}

for N in 1 400; do
  run "stock n=$N"            bench.startup.Startup stock $N
  run "indy/hidden n=$N"      -Dindyproto.strategy=hidden bench.startup.Startup indy $N
  run "indy/mh n=$N"          -Dindyproto.strategy=mh bench.startup.Startup indy $N
done

# JDK 25 AOT cache (JEP 483/514/515): one training run, then runs that use the cache.
for impl in stock indy; do
  "$JAVA" -XX:AOTCacheOutput=$OUT/$impl.aot -cp "$CP" bench.startup.Startup $impl 400 >/dev/null 2>&1
  run "$impl n=400 +AOTCache"   -XX:AOTCache=$OUT/$impl.aot bench.startup.Startup $impl 400
done
