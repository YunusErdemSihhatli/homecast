#!/bin/sh
# Regenerates app/src/main/assets/blocklist.txt from the per-source hosts files
# kept in the StevenBlack/hosts repository, using only the sources whose
# licences allow redistribution in a GPL-3.0 app (see THIRD_PARTY_NOTICES.md).
# The non-commercial (mvps.org, someonewhocares.org) and unlicensed (yoyo.org)
# sources of the unified file are deliberately left out.
#
# Usage: scripts/update-blocklist.sh [<StevenBlack/hosts commit>]
set -eu

REF="${1:-82f0b5086df733a5591fc3f3b44d4990d0c54e97}"
BASE_URL="https://raw.githubusercontent.com/StevenBlack/hosts/$REF/data"
SOURCES="
StevenBlack
adaway.org
add.2o7Net
add.Dead
add.Risk
add.Spam
Badd-Boyz-Hosts
hostsVN
KADhosts
tiuxo
UncheckyAds
URLHaus
"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/blocklist.txt"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

for source in $SOURCES; do
  curl --fail --silent --show-error --location "$BASE_URL/$source/hosts" >> "$TMP"
  echo >> "$TMP"
done

{
  echo "# Host blocklist derived from StevenBlack/hosts per-source lists"
  echo "# Source: https://github.com/StevenBlack/hosts/tree/$REF/data"
  echo "# Lists:" $SOURCES
  echo "# Generated: $(date -u +%Y-%m-%d)"
  echo "# Licences: MIT, CC BY 3.0, CC BY 4.0, CC BY-SA 4.0, CC0 - see THIRD_PARTY_NOTICES.md"
  # Keep "0.0.0.0 <host>" / "127.0.0.1 <host>" lines only; drop comments, CRs,
  # localhost-style entries and placeholders; lowercase; dedupe.
  tr -d '\r' < "$TMP" \
    | sed -e 's/#.*$//' \
    | awk '($1 == "0.0.0.0" || $1 == "127.0.0.1") && NF >= 2 { print tolower($2) }' \
    | grep -v -E '^(0\.0\.0\.0|127\.0\.0\.1|localhost|localhost\.localdomain|local|broadcasthost|ip6-[a-z]+|ip6-allnodes|ip6-allrouters)$' \
    | grep -E '^[a-z0-9_-]+(\.[a-z0-9_-]+)+$' \
    | LC_ALL=C sort -u
} > "$OUT"

echo "Wrote $(grep -vc '^#' "$OUT") hosts to $OUT"
