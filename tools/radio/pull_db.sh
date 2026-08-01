#!/usr/bin/env bash
# Pulls the live database off the device for offline inspection.
#
# Three files, not one. Room runs SQLite in WAL mode, so recent writes live in
# `-wal` until a checkpoint folds them into the main file. Copying only
# `music-pim-db` gets you a database that is internally consistent and missing
# however many minutes of indexing happened since the last checkpoint — which is
# exactly the part you wanted to look at.
#
# Usage:  tools/radio/pull_db.sh [destination-dir]

set -euo pipefail

PKG=com.visibeat.app
DB=music-pim-db
DEST="${1:-./dbdump}"
ADB="${ADB:-$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe}"

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "No device. Plug in with USB debugging on, or 'adb connect <ip>' for wireless." >&2
    exit 1
fi

mkdir -p "$DEST"

# run-as works because the app is debuggable. On an OEM build that blocks it,
# use Android Studio's Device Explorer against /data/data/$PKG/databases instead.
for f in "$DB" "$DB-wal" "$DB-shm"; do
    if "$ADB" exec-out run-as "$PKG" test -f "databases/$f" 2>/dev/null; then
        "$ADB" exec-out run-as "$PKG" cat "databases/$f" > "$DEST/$f"
        printf '  %-20s %s bytes\n' "$f" "$(stat -c%s "$DEST/$f" 2>/dev/null || wc -c < "$DEST/$f")"
    else
        echo "  $f — absent (fine; means it was checkpointed)"
    fi
done

echo
echo "Pulled to $DEST/"
echo "Open $DEST/$DB in DB Browser for SQLite, or run:"
echo "  python tools/radio/inspect_vectors.py $DEST/$DB"
