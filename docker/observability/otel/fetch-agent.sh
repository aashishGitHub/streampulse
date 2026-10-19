#!/usr/bin/env bash
# Downloads the pinned OpenTelemetry Java agent from Maven Central into this directory and
# verifies it against the checksums Maven Central publishes next to it.
#
# A checksum fetched from the same host proves the file was not corrupted in transit. It does
# not prove who published it: the .asc PGP signature is not checked here.
set -euo pipefail

VERSION="2.31.1"
BASE="https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${VERSION}"
NAME="opentelemetry-javaagent-${VERSION}.jar"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

digest() { # digest <algo-bits> <file>
  if command -v shasum >/dev/null 2>&1; then shasum -a "$1" "$2" | cut -d' ' -f1
  else "sha${1}sum" "$2" | cut -d' ' -f1; fi
}

verify() { # verify <jar>
  local bits want got
  for bits in 1 256; do
    want="$(curl -fsSL "${BASE}/${NAME}.sha${bits}" | tr -d '[:space:]')"
    got="$(digest "$bits" "$1")"
    if [[ "$want" != "$got" ]]; then
      echo "FAIL sha${bits}: published ${want}, computed ${got}" >&2
      return 1
    fi
    echo "OK   sha${bits}: ${got}"
  done
}

if [[ -f "${DIR}/${NAME}" ]] && verify "${DIR}/${NAME}"; then
  echo "already present: ${DIR}/${NAME}"
else
  tmp="$(mktemp "${DIR}/.download.XXXXXX")"
  trap 'rm -f "$tmp"' EXIT
  curl -fsSL -o "$tmp" "${BASE}/${NAME}"
  verify "$tmp"
  chmod 644 "$tmp"
  mv "$tmp" "${DIR}/${NAME}"
  trap - EXIT
fi

ln -sf "${NAME}" "${DIR}/opentelemetry-javaagent.jar"
echo "agent: ${DIR}/opentelemetry-javaagent.jar -> ${NAME}"
