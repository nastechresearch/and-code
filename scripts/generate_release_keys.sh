#!/usr/bin/env bash
set -euo pipefail

# Generate a local Android release keystore without printing credentials.
# The output directory is ignored by Git and should be backed up securely.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT/.release-keys}"
KEYSTORE="$OUT_DIR/and-code-release.jks"
CREDENTIALS="$OUT_DIR/credentials.env"
ALIAS="${ANDROID_RELEASE_KEY_ALIAS:-andcode-release}"

command -v keytool >/dev/null || { echo "keytool is required (install a JDK)" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
[[ "$ALIAS" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Invalid key alias" >&2; exit 2; }

if [[ -e "$KEYSTORE" || -e "$CREDENTIALS" ]]; then
  echo "Refusing to overwrite existing release credentials at $OUT_DIR" >&2
  exit 1
fi

umask 077
mkdir -p "$OUT_DIR"
store_password="$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | cut -c1-32)"
key_password="$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | cut -c1-32)"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$store_password" \
  -keypass "$key_password" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=AndCode, OU=NastechResearch, O=NastechResearch, C=US" \
  -noprompt >/dev/null

cat > "$CREDENTIALS" <<EOF
# Keep this file private. Add each value to the matching GitHub Actions secret.
AND_CODE_STORE_FILE=$KEYSTORE
AND_CODE_STORE_PASSWORD=$store_password
AND_CODE_KEY_ALIAS=$ALIAS
AND_CODE_KEY_PASSWORD=$key_password
EOF
chmod 600 "$CREDENTIALS" "$KEYSTORE"
printf 'Generated release keystore: %s\n' "$KEYSTORE"
printf 'Credentials file (mode 600): %s\n' "$CREDENTIALS"
printf 'Back up both securely; never commit either file.\n'
