#!/usr/bin/env bash
# Requires JDK 17+ (no Maven, no external libraries).
set -e
cd "$(dirname "$0")"
mkdir -p out
if command -v javac >/dev/null; then javac -d out src/*.java
else java -m jdk.compiler/com.sun.tools.javac.Main -d out src/*.java; fi
PORT="${PORT:-8080}" java -cp out Main
