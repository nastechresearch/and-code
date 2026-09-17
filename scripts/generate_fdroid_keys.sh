#!/usr/bin/env bash
set -euo pipefail

# Generate the separate signing key used for the self-hosted F-Droid index.
# Never reuse the APK signing key for this purpose.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT/.release-keys}"
KEYSTORE="$OUT_DIR/fdroid-repo.keystore"
CREDENTIALS="$OUT_DIR/fdroid-credentials.env"
ALIAS="${FDROID_REPO_KEY_ALIAS:-andcode-fdroid}"

command -v keytool >/dev/null || { echo "keytool is required (install a JDK)" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
[[ "$ALIAS" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Invalid key alias" >&2; exit 2; }

if [[ -e "$KEYSTORE" || -e "$CREDENTIALS" ]]; then
  echo "Refusing to overwrite existing F-Droid credentials at $OUT_DIR" >&2
  exit 1
fi

umask 077
mkdir -p "$OUT_DIR"
password="$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | cut -c1-32)"
keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype JKS \
  -storepass "$password" \
  -keypass "$password" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=AndCode F-Droid Repository, OU=NastechResearch, O=NastechResearch, C=US" \
  -noprompt >/dev/null

cat > "$CREDENTIALS" <<EOF
# Keep this file private. Add each value to the matching GitHub Actions secret.
F_DROID_REPO_KEYSTORE_BASE64=$(base64 -w0 "$KEYSTORE")
F_DROID_REPO_KEYSTORE_PASSWORD=$password
F_DROID_REPO_KEY_ALIAS=$ALIAS
F_DROID_REPO_KEY_PASSWORD=$password
EOF
chmod 600 "$CREDENTIALS" "$KEYSTORE"
printf 'Generated F-Droid repository keystore: %s\n' "$KEYSTORE"
printf 'Credentials file (mode 600): %s\n' "$CREDENTIALS"
printf 'Back up both securely; never commit either file.\n'
