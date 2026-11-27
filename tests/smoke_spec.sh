#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
spec="$root/companion/spec.md"
android_spec="$root/android-spec.md"

test -s "$spec"
test -s "$android_spec"
grep -q "Protocolo de Comunicación Bidireccional" "$spec"
grep -q "Seguridad y Descubrimiento" "$spec"
grep -q "WebSocket" "$spec"
grep -q "AutomationService" "$spec"

printf 'hardline spec smoke: ok\n'
