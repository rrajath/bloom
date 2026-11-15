#!/usr/bin/env bash
# Run the :macrobenchmark suite against the Performance build
# (com.rrajath.grove.perf) on a connected device and store the results under
# internal/benchmarks/ (gitignored).
#
#   scripts/perf-benchmark.sh                  # benchmark the working copy, as-is
#   scripts/perf-benchmark.sh <commit>         # benchmark a git commit or jj revision
#   scripts/perf-benchmark.sh --class StartupBenchmark main
#
# A commit is exported into a temporary directory (git archive) and built there,
# so the working copy is never touched. Commits from before the Performance
# variant existed are refused: their benchmark build installs as
# com.rrajath.grove, the production app's ID.
#
# How to read the results: internal/perf-benchmarking.md.
set -euo pipefail

PERF_PACKAGE="com.rrajath.grove.perf"
PROD_PACKAGE="com.rrajath.grove"
BENCH_PKG="com.rrajath.grove.macrobenchmark"
VARIANT_FILES=(
  "app/build.gradle.kts"
  "macrobenchmark/src/main/java/com/rrajath/grove/macrobenchmark/BenchmarkVault.kt"
)

die() { echo "error: $*" >&2; exit 1; }
warn() { echo "warning: $*" >&2; }

usage() {
  cat <<'EOF'
Usage: scripts/perf-benchmark.sh [options] [commit]

  commit           git commit / jj revision to benchmark. Default: the current
                   working copy, uncommitted changes included.

Options:
  --class NAME     Run only this test class or method, for example
                   StartupBenchmark, ScrollBenchmark, or
                   StartupBenchmark#coldBaselineProfile. Default: every benchmark.
  --label TEXT     Short tag appended to the results folder name (e.g. before, after).
  --keep-traces    Also copy the Perfetto traces (one per iteration, large).
  -h, --help       Show this help.

Environment:
  ANDROID_SERIAL   Device to run on, required when more than one is connected.
  JAVA_HOME        JDK for Gradle. Defaults to Android Studio's bundled JBR.

Results: internal/benchmarks/<date>_<time>_<commit>[_<label>]/
EOF
}

CLASS_FILTER=""
LABEL=""
KEEP_TRACES=0
REV=""
while [ $# -gt 0 ]; do
  case "$1" in
    --class) [ $# -ge 2 ] || die "--class needs a value"; CLASS_FILTER="$2"; shift 2 ;;
    --label) [ $# -ge 2 ] || die "--label needs a value"; LABEL="$2"; shift 2 ;;
    --keep-traces) KEEP_TRACES=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) [ -z "$REV" ] || die "only one commit may be given"; REV="$1"; shift ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# ---------- tools ----------
if [ -z "${JAVA_HOME:-}" ]; then
  STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  [ -x "$STUDIO_JBR/bin/java" ] && export JAVA_HOME="$STUDIO_JBR"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  [ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME=$JAVA_HOME has no bin/java"
else
  command -v java >/dev/null || die "no JDK found; set JAVA_HOME"
fi

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
fi
[ -n "$SDK_DIR" ] || SDK_DIR="$HOME/Library/Android/sdk"
ADB="$(command -v adb || true)"
[ -n "$ADB" ] || ADB="$SDK_DIR/platform-tools/adb"
[ -x "$ADB" ] || die "adb not found (looked on PATH and in $SDK_DIR/platform-tools)"
command -v python3 >/dev/null || die "python3 is needed to write the summary"

# ---------- device ----------
DEVICES=()
while IFS= read -r serial; do DEVICES+=("$serial"); done \
  < <("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [ -n "${ANDROID_SERIAL:-}" ]; then
  printf '%s\n' "${DEVICES[@]:-}" | grep -qx "$ANDROID_SERIAL" \
    || die "ANDROID_SERIAL=$ANDROID_SERIAL is not a connected device"
elif [ "${#DEVICES[@]}" -eq 1 ]; then
  export ANDROID_SERIAL="${DEVICES[0]}"
elif [ "${#DEVICES[@]}" -eq 0 ]; then
  die "no device connected (adb devices shows none in 'device' state)"
else
  die "several devices connected; set ANDROID_SERIAL to one of: ${DEVICES[*]}"
fi
adbs() { "$ADB" -s "$ANDROID_SERIAL" "$@"; }

# ---------- revision ----------
in_jj() { command -v jj >/dev/null && [ -d "$REPO_ROOT/.jj" ]; }

if [ -z "$REV" ]; then
  MODE="working-copy"
  if in_jj; then
    # A plain `jj log` snapshots the working copy, so @ includes uncommitted edits.
    if [ "$(jj log -r @ --no-graph -T 'empty')" = "true" ]; then
      COMMIT="$(jj log -r @- --no-graph -T 'commit_id')"
      DESC="$(jj log -r @- --no-graph -T 'description.first_line()')"
    else
      COMMIT="$(jj log -r @ --no-graph -T 'commit_id')"
      DESC="$(jj log -r @ --no-graph -T 'if(description, description.first_line(), "(working copy, no description)")')"
    fi
  else
    COMMIT="$(git rev-parse HEAD)"
    DESC="$(git log -1 --format=%s HEAD)"
    [ -z "$(git status --porcelain)" ] || DESC="$DESC (plus uncommitted changes)"
  fi
  for f in "${VARIANT_FILES[@]}"; do
    grep -q "$PERF_PACKAGE" "$f" || die "$f does not target $PERF_PACKAGE; refusing to run (the benchmark build would install as $PROD_PACKAGE)"
  done
else
  MODE="commit"
  COMMIT="$(git rev-parse --verify --quiet "$REV^{commit}" || true)"
  if [ -z "$COMMIT" ] && in_jj; then
    COMMIT="$(jj log -r "$REV" --no-graph -T 'commit_id' 2>/dev/null | head -c 40 || true)"
  fi
  [ -n "$COMMIT" ] || die "cannot resolve '$REV' to a commit"
  DESC="$(git log -1 --format=%s "$COMMIT")"
  for f in "${VARIANT_FILES[@]}"; do
    git show "$COMMIT:$f" 2>/dev/null | grep -q "$PERF_PACKAGE" \
      || die "commit ${COMMIT:0:12} predates the Performance variant ($f does not target $PERF_PACKAGE); refusing to run, since its benchmark build would install as $PROD_PACKAGE"
  done
fi
SHORT="${COMMIT:0:10}"

# ---------- output folder ----------
STAMP="$(date +%Y-%m-%d_%H%M%S)"
SAFE_LABEL="$(printf '%s' "$LABEL" | tr -c 'A-Za-z0-9._-' '-')"
RUN_NAME="${STAMP}_${SHORT}${SAFE_LABEL:+_$SAFE_LABEL}"
OUT_DIR="$REPO_ROOT/internal/benchmarks/$RUN_NAME"
mkdir -p "$OUT_DIR"

# ---------- source tree ----------
if [ "$MODE" = "commit" ]; then
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/bloom-perf.XXXXXX")"
  trap 'rm -rf "$WORK"' EXIT
  SRC="$WORK/src"
  mkdir -p "$SRC"
  echo "Exporting ${SHORT} to $SRC"
  git archive "$COMMIT" | tar -x -C "$SRC"
  [ -f "$REPO_ROOT/local.properties" ] && cp "$REPO_ROOT/local.properties" "$SRC/"
  chmod +x "$SRC/gradlew"
else
  SRC="$REPO_ROOT"
fi

RESULTS_ROOT="$SRC/macrobenchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease"
rm -rf "$RESULTS_ROOT"

# ---------- device state (before) ----------
battery_field() { adbs shell dumpsys battery | awk -F': ' -v k="  $1" '$1 == k { print $2 }' | tr -d '\r'; }
thermal_status() { adbs shell dumpsys thermalservice 2>/dev/null | awk -F': ' '/^Thermal Status/ { print $2; exit }' | tr -d '\r'; }
prop() { adbs shell getprop "$1" | tr -d '\r'; }

MODEL="$(prop ro.product.model)"
SDK_INT="$(prop ro.build.version.sdk)"
BUILD_ID="$(prop ro.build.id)"
BATT_LEVEL_BEFORE="$(battery_field level)"
BATT_TEMP_BEFORE="$(battery_field temperature)"
THERMAL_BEFORE="$(thermal_status)"

if [ -n "$BATT_LEVEL_BEFORE" ] && [ "$BATT_LEVEL_BEFORE" -lt 50 ] 2>/dev/null; then
  warn "battery at ${BATT_LEVEL_BEFORE}%; some phones throttle when low, numbers may be slower"
fi
if [ -n "$THERMAL_BEFORE" ] && [ "$THERMAL_BEFORE" != "0" ]; then
  warn "thermal status is $THERMAL_BEFORE (0 = none); let the phone cool down for steadier numbers"
fi

echo "Benchmarking ${SHORT} (${DESC}) on ${MODEL} (API ${SDK_INT}, ${ANDROID_SERIAL})"
echo "Results: $OUT_DIR"

# ---------- run ----------
GRADLE_ARGS=(
  ":macrobenchmark:connectedBenchmarkReleaseAndroidTest"
  # Keep the Performance build installed afterwards so it can be used by hand.
  "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
)
if [ -n "$CLASS_FILTER" ]; then
  case "$CLASS_FILTER" in
    *.*) FQ="$CLASS_FILTER" ;;
    *) FQ="$BENCH_PKG.$CLASS_FILTER" ;;
  esac
  GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=$FQ")
fi

START_TS="$(date +%s)"
set +e
( cd "$SRC" && ./gradlew "${GRADLE_ARGS[@]}" ) 2>&1 | tee "$OUT_DIR/gradle.log"
GRADLE_STATUS="${PIPESTATUS[0]}"
set -e
DURATION=$(( $(date +%s) - START_TS ))

BATT_LEVEL_AFTER="$(battery_field level)"
BATT_TEMP_AFTER="$(battery_field temperature)"
THERMAL_AFTER="$(thermal_status)"

# ---------- collect ----------
JSON="$(find "$RESULTS_ROOT" -name '*benchmarkData.json' 2>/dev/null | head -1)"
if [ -n "$JSON" ]; then
  cp "$JSON" "$OUT_DIR/benchmarkData.json"
  if [ "$KEEP_TRACES" -eq 1 ]; then
    mkdir -p "$OUT_DIR/traces"
    find "$(dirname "$JSON")" -name '*.perfetto-trace' -exec cp {} "$OUT_DIR/traces/" \;
  fi
fi

python3 -I - "$OUT_DIR" \
  "$COMMIT" "$DESC" "$MODE" "$LABEL" "$CLASS_FILTER" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "$DURATION" "$GRADLE_STATUS" "$ANDROID_SERIAL" "$MODEL" "$SDK_INT" "$BUILD_ID" \
  "$BATT_LEVEL_BEFORE" "$BATT_TEMP_BEFORE" "$THERMAL_BEFORE" \
  "$BATT_LEVEL_AFTER" "$BATT_TEMP_AFTER" "$THERMAL_AFTER" <<'PY_RUN'
import json, sys
(out, commit, desc, mode, label, cls, date, duration, status, serial, model, sdk, build_id,
 lvl_b, temp_b, th_b, lvl_a, temp_a, th_a) = sys.argv[1:]
json.dump({
    "commit": commit, "description": desc, "mode": mode, "label": label, "classFilter": cls,
    "date": date, "durationSeconds": int(duration), "gradleExitStatus": int(status),
    "device": {"serial": serial, "model": model, "sdk": sdk, "buildId": build_id},
    "before": {"batteryLevel": lvl_b, "batteryTempTenthsC": temp_b, "thermalStatus": th_b},
    "after": {"batteryLevel": lvl_a, "batteryTempTenthsC": temp_a, "thermalStatus": th_a},
}, open(out + "/run.json", "w"), indent=2)
PY_RUN

python3 -I - "$OUT_DIR" <<'PY'
import json, os, sys

out = sys.argv[1]
run = json.load(open(os.path.join(out, "run.json")))
data_path = os.path.join(out, "benchmarkData.json")
data = json.load(open(data_path)) if os.path.exists(data_path) else None

def fmt(v):
    return "" if v is None else f"{v:,.1f}"

secs = run["durationSeconds"]
lines = [
    f"# Benchmark run {os.path.basename(out)}",
    "",
    "- [Run info](#run-info)",
    "- [Single-value metrics](#single-value-metrics)",
    "- [Per-frame metrics](#per-frame-metrics)",
    "",
    "## Run info",
    "",
    f"- Commit: `{run['commit'][:12]}` {run['description']}",
    f"- Source: {run['mode']}" + (f", label `{run['label']}`" if run["label"] else ""),
    f"- Device: {run['device']['model']} (API {run['device']['sdk']}, build {run['device']['buildId']})",
    f"- Battery: {run['before']['batteryLevel']}% to {run['after']['batteryLevel']}%; "
    f"thermal status {run['before']['thermalStatus'] or '?'} to {run['after']['thermalStatus'] or '?'}",
    f"- Duration: {secs // 60} min {secs % 60} s; Gradle exit status {run['gradleExitStatus']}",
]
if data:
    ctx = data.get("context", {})
    lines.append(f"- CPU locked: {ctx.get('cpuLocked')}; compilation: {ctx.get('compilationMode')}")

single, sampled = [], []
for b in (data or {}).get("benchmarks", []):
    name = f"{b['className'].split('.')[-1]}.{b['name']}"
    for metric, m in sorted(b.get("metrics", {}).items()):
        cv = m.get("coefficientOfVariation")
        cv_s = "" if cv is None else f"{cv * 100:.1f}%"
        single.append(f"| {name} | {metric} | {fmt(m.get('median'))} | {fmt(m.get('minimum'))} | "
                      f"{fmt(m.get('maximum'))} | {cv_s} | {len(m.get('runs', []))} |")
    for metric, m in sorted(b.get("sampledMetrics", {}).items()):
        sampled.append(f"| {name} | {metric} | {fmt(m.get('P50'))} | {fmt(m.get('P90'))} | "
                       f"{fmt(m.get('P95'))} | {fmt(m.get('P99'))} |")

lines += ["", "## Single-value metrics", ""]
if single:
    lines += ["| Benchmark | Metric | Median | Min | Max | CV | Runs |",
              "|---|---|---:|---:|---:|---:|---:|", *single]
else:
    lines.append("No results (see gradle.log).")
lines += ["", "## Per-frame metrics", ""]
if sampled:
    lines += ["| Benchmark | Metric | P50 | P90 | P95 | P99 |",
              "|---|---|---:|---:|---:|---:|", *sampled]
else:
    lines.append("None in this run.")
open(os.path.join(out, "summary.md"), "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
PY

echo
echo "Saved to $OUT_DIR"
[ "$GRADLE_STATUS" -eq 0 ] || die "Gradle exited with status $GRADLE_STATUS (see $OUT_DIR/gradle.log)"
[ -n "$JSON" ] || die "no benchmarkData.json was produced"
