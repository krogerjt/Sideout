#!/bin/sh
# Writes the git-ignored ios/Local.xcconfig from $SIDEOUT_SERVER_URL, for build machines that
# receive the server address as an environment variable instead of a checked-out file.
set -eu
: "${SIDEOUT_SERVER_URL:?Set SIDEOUT_SERVER_URL to the server's https:// address.}"
case "$SIDEOUT_SERVER_URL" in https://*|http://*) ;; *) echo "SIDEOUT_SERVER_URL must start with https://" >&2; exit 1 ;; esac
# xcconfig files treat // as a comment, so write :/$()/ instead.
printf 'SIDEOUT_SERVER_URL = %s\n' "$(printf '%s' "$SIDEOUT_SERVER_URL" | sed 's#://#:/$()/#')" > "$(dirname "$0")/Local.xcconfig"
