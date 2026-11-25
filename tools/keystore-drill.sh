#!/usr/bin/env bash
# Restore drill for the SokeriPilot release signing key.
#
#   KEYSTORE_PASSPHRASE='…' bash tools/keystore-drill.sh
#
# Proves, without touching the live key and without building anything, that the
# encrypted backup in backups/ can still be restored: the archive opens with the
# passphrase you hold, the extracted keystore is byte-identical to the one used
# to sign the published APKs, and its certificate SHA-256 equals the one printed
# in landing/releases.json.
#
# The passphrase is read from the environment, never from a command line. The
# Android store password is read from android/keystore.properties and handed to
# keytool through its environment, so neither secret is echoed or written.
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ARCHIVE="$(ls backups/*.tar.gz.enc 2>/dev/null | head -1 || true)"
KEYSTORE="$(ls android/keystore/*.jks 2>/dev/null | head -1 || true)"
PROPS="android/keystore.properties"
TMP="dist/keystore-drill"

fail() { echo "FAIL  $1"; exit 1; }

[ -n "$ARCHIVE" ] || fail "no encrypted backup in backups/"
[ -n "$KEYSTORE" ] || fail "no keystore in android/keystore/"
[ -f "$PROPS" ] || fail "android/keystore.properties is missing"
[ -n "${KEYSTORE_PASSPHRASE:-}" ] || {
  echo "Set KEYSTORE_PASSPHRASE to the backup passphrase, e.g."
  echo "  KEYSTORE_PASSPHRASE='…' bash tools/keystore-drill.sh"
  exit 2
}

cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT
rm -rf "$TMP"
mkdir -p "$TMP/restore"

printf '%s\n' "$KEYSTORE_PASSPHRASE" |
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 300000 -in "$ARCHIVE" -out "$TMP/backup.tar.gz" -pass stdin 2>/dev/null ||
  fail "the archive does not open with this passphrase"
echo "PASS  backup decrypts with the passphrase"

tar -xzf "$TMP/backup.tar.gz" -C "$TMP/restore" || fail "the decrypted archive is not a tar file"
RESTORED="$(find "$TMP/restore" -name '*.jks' -print -quit)"
[ -n "$RESTORED" ] || fail "the archive contains no keystore"
echo "PASS  archive contains a keystore ($(basename "$RESTORED"))"

LIVE_SUM="$(sha256sum "$KEYSTORE" | cut -d' ' -f1)"
BACKUP_SUM="$(sha256sum "$RESTORED" | cut -d' ' -f1)"
if [ "$LIVE_SUM" != "$BACKUP_SUM" ]; then
  echo "FAIL  the backup keystore differs from the live one"
  echo "      live   $LIVE_SUM"
  echo "      backup $BACKUP_SUM"
  echo "      Re-run backups/reencrypt-key.sh so the backup matches the key that signs releases."
  exit 1
fi
echo "PASS  restored keystore is byte-identical to the live key (sha256 ${LIVE_SUM:0:12}…)"

ALIAS="$(grep -m1 '^keyAlias=' "$PROPS" | cut -d= -f2- || true)"
export KS_PASS="$(grep -m1 '^storePassword=' "$PROPS" | cut -d= -f2- || true)"
[ -n "$ALIAS" ] || ALIAS="$(basename "$RESTORED" .jks)"
[ -n "$KS_PASS" ] || fail "no storePassword in android/keystore.properties"

KEYTOOL="keytool"
if [ -x ".toolchain/jdk/bin/keytool" ] || [ -x ".toolchain/jdk/bin/keytool.exe" ]; then
  KEYTOOL="$(ls .toolchain/jdk/bin/keytool* | head -1)"
fi

"$KEYTOOL" -list -v -keystore "$RESTORED" -alias "$ALIAS" -storepass:env KS_PASS 2>/dev/null |
  grep -m1 '^[[:space:]]*SHA256:' >"$TMP/fingerprint" || fail "keytool could not read alias $ALIAS"
FINGERPRINT="$(sed 's/.*SHA256://; s/[[:space:]:]//g' "$TMP/fingerprint" | tr 'A-F' 'a-f')"
EXPECTED="$(node -p "require('./landing/releases.json').releases[0].certSha256")"
[ "$FINGERPRINT" = "$EXPECTED" ] ||
  fail "certificate ${FINGERPRINT:0:12}… does not match releases.json (${EXPECTED:0:12}…)"
echo "PASS  certificate matches the published APKs (${FINGERPRINT:0:12}…)"

echo
echo "Drill complete. A restore from $ARCHIVE reproduces the signing identity the site already serves."
echo "Keep an off-machine copy of the archive and the passphrase in a password manager."
