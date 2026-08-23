#!/usr/bin/env sh
# Project-local Maven launcher. macOS may not register Homebrew JDKs with
# /usr/libexec/java_home, so discover Java 25 before invoking Maven.
set -eu

if [ -z "${JAVA_HOME:-}" ]; then
  if JAVA_HOME_CANDIDATE=$(/usr/libexec/java_home -v 25 2>/dev/null); then
    export JAVA_HOME="$JAVA_HOME_CANDIDATE"
  elif command -v brew >/dev/null 2>&1 && [ -d "$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
  else
    echo "Java 25 is required. Set JAVA_HOME or install openjdk@25." >&2
    exit 1
  fi
fi

exec mvn "$@"
