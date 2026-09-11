#!/usr/bin/env bash
# Generates the values you paste into GitHub Actions secrets for release signing.
#
#   ./scripts/setup-signing-secrets.sh ~/.argos-signing/argos-release.keystore
#
# Then add these repository secrets (Settings -> Secrets and variables -> Actions):
#
#   ANDROID_KEYSTORE_BASE64      the printed base64 blob
#   ANDROID_KEYSTORE_PASSWORD    your keystore password
#   ANDROID_KEY_ALIAS            e.g. argos-key
#   ANDROID_KEY_ALIAS_PASSWORD   your key password
#
# The keystore itself is NEVER committed — only its base64 lives in GitHub's
# encrypted secret store.

set -euo pipefail

KEYSTORE="${1:-}"
OUT_FILE="${2:-argos-keystore.base64.txt}"

if [[ -z "$KEYSTORE" ]]; then
  echo "usage: $0 <path-to-keystore> [out-file]" >&2
  exit 1
fi

if [[ ! -f "$KEYSTORE" ]]; then
  echo "Keystore not found: $KEYSTORE" >&2
  exit 1
fi

# -w0 = no line wrapping (the workflow pipes this straight into `base64 -d`)
base64 -w0 "$KEYSTORE" > "$OUT_FILE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n' > "$OUT_FILE"

echo
echo "Keystore : $KEYSTORE ($(wc -c < "$KEYSTORE") bytes)"
echo "Base64   : written to $OUT_FILE ($(wc -c < "$OUT_FILE") chars)"
echo
echo "Next steps:"
echo "  1. GitHub repo -> Settings -> Secrets and variables -> Actions"
echo "  2. New repository secret: ANDROID_KEYSTORE_BASE64"
echo "     value = the full contents of $OUT_FILE"
echo "  3. Add ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_ALIAS_PASSWORD"
echo "  4. Delete $OUT_FILE once the secret is saved"
echo
echo "Then release with:  git tag v3.34.0 && git push origin v3.34.0"
