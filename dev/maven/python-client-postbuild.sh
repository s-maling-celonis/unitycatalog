#!/usr/bin/env bash
# Port of project/PythonClientPostBuild.scala for the Maven Python client module.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_DIR="$ROOT_DIR/clients/python"
OUTPUT_DIR="${1:-$BASE_DIR/target}"
VERSION_SCRIPT="$BASE_DIR/build/update-python-versions.sh"

mkdir -p "$OUTPUT_DIR"

IGNORE_SRC="$BASE_DIR/build/.openapi-generator-ignore"
if [[ -f "$IGNORE_SRC" ]]; then
  cp "$IGNORE_SRC" "$OUTPUT_DIR/.openapi-generator-ignore"
fi

if [[ ! -x "$VERSION_SCRIPT" ]] && [[ -f "$VERSION_SCRIPT" ]]; then
  :
fi
if [[ ! -f "$VERSION_SCRIPT" ]]; then
  echo "Version updating script not found at $VERSION_SCRIPT" >&2
  exit 1
fi

bash "$VERSION_SCRIPT"

for fileName in README.md pyproject.toml; do
  src="$BASE_DIR/build/$fileName"
  if [[ ! -f "$src" ]]; then
    echo "The file $fileName was not found. Expected at: $src" >&2
    exit 1
  fi
  cp "$src" "$OUTPUT_DIR/$fileName"
done

SOURCE_PKG="$OUTPUT_DIR/unitycatalog"
TARGET_PKG="$OUTPUT_DIR/src/unitycatalog"
if [[ ! -d "$SOURCE_PKG" ]]; then
  echo "Generated 'unitycatalog' directory not found at $SOURCE_PKG" >&2
  exit 1
fi
if [[ -d "$TARGET_PKG" ]]; then
  rm -rf "$TARGET_PKG"
fi
mkdir -p "$OUTPUT_DIR/src"
mv "$SOURCE_PKG" "$TARGET_PKG"

SRC_MODULE="$BASE_DIR/src/unitycatalog/delta/internal/serde/delta_data_type_module.py"
DELTA_DIR="$OUTPUT_DIR/src/unitycatalog/delta"
if [[ -f "$SRC_MODULE" && -d "$DELTA_DIR" ]]; then
  INTERNAL="$DELTA_DIR/internal"
  SERDE="$INTERNAL/serde"
  mkdir -p "$SERDE"
  cp "$SRC_MODULE" "$SERDE/delta_data_type_module.py"
  : > "$INTERNAL/__init__.py"
  : > "$SERDE/__init__.py"
  MODELS_INIT="$DELTA_DIR/models/__init__.py"
  if [[ -f "$MODELS_INIT" ]] && ! grep -q "delta_data_type_module" "$MODELS_INIT"; then
    printf '\n# Auto-import DeltaDataType string-or-object patch\nimport unitycatalog.delta.internal.serde.delta_data_type_module  # noqa: F401\n' >> "$MODELS_INIT"
  fi
fi

echo "OpenAPI Python client generation completed."
