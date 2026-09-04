#!/usr/bin/env bash
# Builds a Unity Catalog distribution tarball: jars + classpath + bin/ + etc/.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(sed -n '/<artifactId>unitycatalog<\/artifactId>/,/<version>/s,.*<version>\([^<]*\)</version>.*,\1,p' "$ROOT_DIR/pom.xml" | head -n 1)"
fi

OUT="$ROOT_DIR/target/dist"
JARS="$OUT/jars"
rm -rf "$OUT"
mkdir -p "$JARS"

copy_from_classpath_file() {
  local cp_file="$1"
  if [[ ! -f "$cp_file" ]]; then
    echo "Missing classpath file: $cp_file" >&2
    exit 1
  fi
  local old_ifs=$IFS
  IFS=':'
  # shellcheck disable=SC2086
  set -- $(cat "$cp_file")
  IFS=$old_ifs
  for entry in "$@"; do
    [[ -z "$entry" ]] && continue
    if [[ -f "$entry" ]]; then
      cp -n "$entry" "$JARS/$(basename "$entry")" 2>/dev/null || true
    fi
  done
}

copy_from_classpath_file "$ROOT_DIR/server/target/classpath"

python3 - "$JARS" <<'PY'
from pathlib import Path
import sys
jars = Path(sys.argv[1])
paths = sorted(p.resolve() for p in jars.glob("*.jar"))
(jars / "classpath").write_text(":".join(str(p) for p in paths))
PY

cp -R "$ROOT_DIR/bin" "$OUT/bin"
cp -R "$ROOT_DIR/etc" "$OUT/etc"

TARBALL="$ROOT_DIR/target/unitycatalog-${VERSION}.tar.gz"
tar -czf "$TARBALL" -C "$OUT" .
rm -rf "$OUT"
echo "Tarball created: $TARBALL"
