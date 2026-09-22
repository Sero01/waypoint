#!/usr/bin/env bash
# Reproduce every number in the README.
#
# Runs from the repository root. Works in bash, including Git Bash on Windows,
# which is where the committed results were produced. Every JVM flag the
# benchmark uses is either in this file or printed into results/coldstart.md.
set -euo pipefail

: "${JAVA_HOME:?set JAVA_HOME to a JDK 25 installation}"
JAVA="$JAVA_HOME/bin/java"
CORPUS="${CORPUS:-corpus/collection-1m.tsv}"
QUERIES="${QUERIES:-corpus/queries.dev.tsv}"
RUNS="${RUNS:-20}"
K="${K:-10}"

# Windows needs ';' between classpath entries and native path separators.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';'; win() { cygpath -w "$1"; } ;;
  *)                    SEP=':'; win() { printf '%s' "$1"; } ;;
esac

echo "== building =="
# `clean` is not optional here. Without it the shade plugin can leave a stale
# uber jar in bench/target that still contains an old copy of waypoint-core,
# and because that jar leads the benchmark classpath it silently shadows the
# freshly installed one. The failure mode is a benchmark that measures code
# you are no longer running, which is worse than a benchmark that fails.
mvn -B -q clean install -DskipTests
mvn -B -q -pl bench dependency:build-classpath -Dmdep.outputFile=target/cp.txt

M2="$(mvn -B -q help:evaluate -Dexpression=settings.localRepository -DforceStdout)"
LUCENE_VERSION="$(mvn -B -q help:evaluate -Dexpression=lucene.version -DforceStdout)"

# Waypoint runs from one self-contained jar. Lucene gets exactly the jars it
# needs -- lucene-core, lucene-analysis-common, waypoint-core for the shared
# tokenizer, and the bench classes -- and nothing else, so neither engine is
# charged for the other's classpath.
WP_CP="$(win "$PWD/cli/target/waypoint-cli.jar")"
LUCENE_CP="$(win "$PWD/bench/target/original-waypoint-bench.jar")$SEP"
LUCENE_CP+="$(win "$M2/io/waypoint/waypoint-core/1.0.0-SNAPSHOT/waypoint-core-1.0.0-SNAPSHOT.jar")$SEP"
LUCENE_CP+="$(win "$M2/org/apache/lucene/lucene-core/$LUCENE_VERSION/lucene-core-$LUCENE_VERSION.jar")$SEP"
LUCENE_CP+="$(win "$M2/org/apache/lucene/lucene-analysis-common/$LUCENE_VERSION/lucene-analysis-common-$LUCENE_VERSION.jar")"

WP_INDEX="indexes/msmarco-1m.wpt"
LUCENE_INDEX="indexes/msmarco-1m-lucene"

if [ ! -f "$WP_INDEX" ]; then
  echo "== building indexes from $CORPUS =="
  "$JAVA" -Xmx8g -cp "$LUCENE_CP" io.waypoint.bench.BenchMain prepare \
    --corpus "$CORPUS" --wp-out "$WP_INDEX" --lucene-out "$LUCENE_INDEX" \
    | tee results/index-build.txt
fi

echo "== classes loaded by one cold query =="
"$JAVA" -cp "$LUCENE_CP" io.waypoint.bench.BenchMain classload \
  --java "$(win "$JAVA")" --wp-cp "$WP_CP" --wp-index "$WP_INDEX" \
  --lucene-cp "$LUCENE_CP" --lucene-index "$LUCENE_INDEX" \
  --query "manhattan project" | tee results/classload.txt

for spec in "single:manhattan" "or:manhattan project physics" "and:blood pressure medication"; do
  mode="${spec%%:*}"
  query="${spec#*:}"
  echo "== cold start: $mode =="
  extra=()
  [ "$mode" = "and" ] && extra=(--and)
  "$JAVA" -cp "$LUCENE_CP" io.waypoint.bench.ColdStartSuite \
    --java "$(win "$JAVA")" \
    --wp-cp "$WP_CP" --wp-index "$WP_INDEX" \
    --lucene-cp "$LUCENE_CP" --lucene-index "$LUCENE_INDEX" \
    --query "$query" "${extra[@]}" --k "$K" --runs "$RUNS" \
    --out "results/$mode" --work "target/coldstart-$mode"
done

echo "== done; see results/ =="
