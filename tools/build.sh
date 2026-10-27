#!/bin/bash
# Mizan Launcher rebuild — manual SDK-tools build, NO Gradle.
# Adapted from the v1.13 / v3.9 manual pipeline (aapt2 + kotlinc + d8
# + zipalign + apksigner). Every kotlinc invocation runs at -Xmx512m:
# this VM has ~382 MB actually available and zero swap, so a unit
# that OOMs must be SPLIT, never given a bigger heap.
#
# Usage: tools/build.sh [core|domain|units|apk]
#   core   — compile core/ only (the Phase 2A proof target)
#   domain — compile core/ + domain/
#   units  — compile every layer unit present (data/ui/app skipped
#            with a note while those workers have not landed them)
#   apk    — full pipeline; FAILS honestly if any layer is missing
#            (no partial APK is ever presented as a build)
#   (no arg defaults to core)
#
# Signing: the existing debug keystore is used ONLY for signing
# invocations and is NEVER copied into this tree. Its password is
# NEVER written in this file: apksigner reads it from the
# environment (KEYSTORE_PASS). Export it in your shell before
# running the apk target; the script fails if it is unset.
# The keystore path may be overridden with KEYSTORE.
set -e
cd "$(dirname "$0")/.."

export PATH="$HOME/workspace/android-build/jdk/bin:$PATH"
BT="$HOME/workspace/android-build/sdk/build-tools/34.0.0"
PLAT="$HOME/workspace/android-build/sdk/platforms/android-34/android.jar"
KOTLINC="$HOME/workspace/android-build/kotlin-1.9.24/bin/kotlinc"
KLIB="$HOME/workspace/android-build/kotlin-1.9.24/lib"
STDLIB="$KLIB/kotlin-stdlib.jar"
JDK7="$KLIB/kotlin-stdlib-jdk7.jar"
JDK8="$KLIB/kotlin-stdlib-jdk8.jar"
ANNOT="$KLIB/annotations-13.0.jar"
SRC="app/src/main"
OUT="out"
KEYSTORE="${KEYSTORE:-$HOME/workspace/.keystores/mizanlauncher-debug.keystore}"

MODE="${1:-core}"
mkdir -p "$OUT"

# compile_unit <name> <srcdir...> — one kotlinc JVM at -Xmx512m.
# $CP_EXTRA (accumulated earlier units) is prepended by callers.
compile_unit() {
    local name="$1"; shift
    local list="$OUT/sources-$name.txt"
    : > "$list"
    for d in "$@"; do
        [ -d "$d" ] || continue
        find "$d" -name "*.kt" >> "$list"
    done
    local n
    n=$(wc -l < "$list")
    if [ "$n" -eq 0 ]; then
        echo "== unit $name: no sources, skipped =="
        return 1
    fi
    echo "== kotlinc unit $name ($n files, -Xmx512m) =="
    local t0 t1
    t0=$(date +%s)
    # shellcheck disable=SC2086
    "$KOTLINC" -J-Xmx512m -jvm-target 17 -no-stdlib -nowarn \
        -cp "$PLAT:$OUT/classes:$STDLIB:$JDK7:$JDK8:$ANNOT" \
        -d "$OUT/classes" @"$list"
    t1=$(date +%s)
    echo "== unit $name: OK in $((t1 - t0))s; classes so far: $(find "$OUT/classes" -name '*.class' | wc -l) =="
    return 0
}

compile_core_domain() {
    rm -rf "$OUT/classes"
    mkdir -p "$OUT/classes"
    compile_unit core "$SRC/java/com/mizan/launcher/core"
    if [ "$MODE" != "core" ]; then
        compile_unit domain "$SRC/java/com/mizan/launcher/domain" || true
    fi
}

case "$MODE" in
    core)
        compile_core_domain
        echo "CORE BUILD OK (core only, -Xmx512m)"
        ;;
    domain)
        compile_core_domain
        echo "DOMAIN BUILD OK (core + domain, -Xmx512m per unit)"
        ;;
    units)
        rm -rf "$OUT/classes"
        mkdir -p "$OUT/classes"
        compile_unit core "$SRC/java/com/mizan/launcher/core"
        compile_unit domain "$SRC/java/com/mizan/launcher/domain" || true
        compile_unit data "$SRC/java/com/mizan/launcher/data" || echo "unit data: not landed yet"
        compile_unit ui "$SRC/java/com/mizan/launcher/ui" || echo "unit ui: not landed yet"
        compile_unit app "$SRC/java/com/mizan/launcher/app" || echo "unit app: not landed yet"
        echo "UNITS BUILD DONE (see skipped units above)"
        ;;
    apk)
        for layer in core domain data ui app; do
            if ! find "$SRC/java/com/mizan/launcher/$layer" -name "*.kt" -print -quit 2>/dev/null | grep -q .; then
                echo "APK BUILD REFUSED: layer $layer has no Kotlin sources yet (later worker)."
                echo "Present units still compile via: tools/build.sh units"
                exit 2
            fi
        done
        [ -n "${KEYSTORE_PASS:-}" ] || {
            echo "APK BUILD REFUSED: KEYSTORE_PASS is not set in the environment."
            echo "Export it in your shell (never write it to a file) and retry."
            exit 2
        }
        [ -f "$KEYSTORE" ] || { echo "APK BUILD REFUSED: keystore not found at $KEYSTORE"; exit 2; }
        rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
        mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
        echo "== aapt2 compile =="
        "$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled-res.zip"
        echo "== manifest (inject package) =="
        sed 's|<manifest |<manifest package="com.mizan.launcher" |' "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
        echo "== aapt2 link =="
        "$BT/aapt2" link -o "$OUT/base.apk" \
            -I "$PLAT" \
            --manifest "$OUT/AndroidManifest.xml" \
            --java "$OUT/gen" \
            "$OUT/compiled-res.zip" \
            --min-sdk-version 26 \
            --target-sdk-version 34 \
            --version-code 33 \
            --version-name 3.10
        echo "== javac (R.java) =="
        find "$OUT/gen" -name "*.java" > "$OUT/sources-java.txt"
        javac -encoding UTF-8 -source 17 -target 17 -nowarn -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources-java.txt"
        compile_unit core "$SRC/java/com/mizan/launcher/core"
        compile_unit domain "$SRC/java/com/mizan/launcher/domain"
        compile_unit data "$SRC/java/com/mizan/launcher/data"
        compile_unit ui "$SRC/java/com/mizan/launcher/ui"
        compile_unit app "$SRC/java/com/mizan/launcher/app"
        echo "== d8 (multi-dex) =="
        "$BT/d8" --lib "$PLAT" --min-api 26 --output "$OUT/dex" \
            $(find "$OUT/classes" -name "*.class") "$STDLIB" "$JDK7" "$JDK8" "$ANNOT"
        cp "$OUT/base.apk" "$OUT/app.apk"
        zip -q -j "$OUT/app.apk" "$OUT/dex"/classes*.dex
        echo "== zipalign =="
        "$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/app-aligned.apk"
        echo "== apksigner =="
        "$BT/apksigner" sign \
            --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASS --key-pass env:KEYSTORE_PASS \
            --ks-key-alias mizanlauncher \
            --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
            --out "$OUT/Mizan-Launcher-rebuild.apk" "$OUT/app-aligned.apk"
        "$BT/apksigner" verify --print-certs "$OUT/Mizan-Launcher-rebuild.apk"
        "$BT/zipalign" -c 4 "$OUT/Mizan-Launcher-rebuild.apk" && echo "zipalign: OK"
        echo "BUILD OK: $OUT/Mizan-Launcher-rebuild.apk"
        ;;
    *)
        echo "usage: $0 [core|domain|units|apk]" >&2
        exit 2
        ;;
esac
