#!/usr/bin/env bash
set -euo pipefail

# Reapply fork-owned branding after syncing from upstream.
# This deliberately rewrites only the original repository owner/repository
# references. Third-party GitHub URLs (OpenCode, Anthropic, Google, etc.) stay
# untouched.

ORIGINAL_OWNER="${ORIGINAL_OWNER:-yuga-hashimoto}"
ORIGINAL_REPO="${ORIGINAL_REPO:-and-code}"
FORK_OWNER="${FORK_OWNER:-nastechresearch}"
FORK_REPO="${FORK_REPO:-and-code}"
DRY_RUN="${DRY_RUN:-false}"

if [[ ! "$ORIGINAL_OWNER" =~ ^[A-Za-z0-9_.-]+$ || ! "$ORIGINAL_REPO" =~ ^[A-Za-z0-9_.-]+$ || ! "$FORK_OWNER" =~ ^[A-Za-z0-9_.-]+$ || ! "$FORK_REPO" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "Invalid owner or repository name" >&2
  exit 2
fi

# Keep the replacement set intentionally narrow. In particular, do not replace
# every occurrence of github.com: dependency and integration links must remain
# pointed at their real upstream projects.
declare -a REPLACEMENTS=(
  "https://github.com/${ORIGINAL_OWNER}/${ORIGINAL_REPO}|https://github.com/${FORK_OWNER}/${FORK_REPO}"
  "http://github.com/${ORIGINAL_OWNER}/${ORIGINAL_REPO}|http://github.com/${FORK_OWNER}/${FORK_REPO}"
  "github.com/${ORIGINAL_OWNER}/${ORIGINAL_REPO}|github.com/${FORK_OWNER}/${FORK_REPO}"
  "github.com/${ORIGINAL_OWNER}|github.com/${FORK_OWNER}"
  "github.com/${ORIGINAL_OWNER,,}/${ORIGINAL_REPO}|github.com/${FORK_OWNER,,}/${FORK_REPO}"
  "${ORIGINAL_OWNER}/${ORIGINAL_REPO}|${FORK_OWNER}/${FORK_REPO}"
  "${ORIGINAL_OWNER}|${FORK_OWNER}"
)

# Text-like source/config/document files. Binary assets and generated/build
# output are excluded both for speed and to avoid corrupting files.
mapfile -d '' FILES < <(git ls-files -z --cached --others --exclude-standard \
  ':!scripts/rebrand_fork.sh' ':!.github/workflows/sync-upstream-rebrand.yml' ':!docs/UPSTREAM_SYNC.md' \
  ':!*.png' ':!*.jpg' ':!*.jpeg' ':!*.gif' ':!*.webp' ':!*.mp4' ':!*.wav' \
  ':!*.mp3' ':!*.zip' ':!*.tar' ':!*.gz' ':!*.so' ':!*.a' ':!*.jar' \
  ':!*.keystore' ':!*.jks' ':!build/' ':!**/build/' ':!**/.gradle/' ':!**/generated/')

changed=0
for file in "${FILES[@]}"; do
  [[ -f "$file" ]] || continue
  # Skip files that contain NUL bytes (binary, even if extension is unknown).
  if LC_ALL=C grep -Iq . "$file" 2>/dev/null; then
    tmp="${file}.rebrand.$$"
    cp "$file" "$tmp"
    for replacement in "${REPLACEMENTS[@]}"; do
      old="${replacement%%|*}"
      new="${replacement#*|}"
      OLD="$old" NEW="$new" perl -0pi -e 's/\Q$ENV{OLD}\E/$ENV{NEW}/g' "$tmp"
    done
    if ! cmp -s "$file" "$tmp"; then
      changed=$((changed + 1))
      if [[ "$DRY_RUN" == "true" ]]; then
        echo "would rebrand: $file"
        rm -f "$tmp"
      else
        mv "$tmp" "$file"
        echo "rebranded: $file"
      fi
    else
      rm -f "$tmp"
    fi
  fi
done

echo "Rebrand complete: ${changed} file(s) changed."
