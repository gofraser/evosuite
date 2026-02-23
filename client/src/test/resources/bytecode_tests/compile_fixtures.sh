#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SOURCES_DIR="$DIR/sources"
BIN_DIR="$DIR/bin"

mkdir -p "$BIN_DIR/java8/bytecode_tests"
mkdir -p "$BIN_DIR/java11/bytecode_tests"
mkdir -p "$BIN_DIR/java17/bytecode_tests"
mkdir -p "$BIN_DIR/java24/bytecode_tests"

echo "Compiling for Java 8..."
javac -d "$BIN_DIR/java8" --release 8 "$SOURCES_DIR/CompatibilityFixture.java"

echo "Compiling for Java 11..."
javac -d "$BIN_DIR/java11" --release 11 "$SOURCES_DIR/CompatibilityFixture.java"

echo "Compiling for Java 17..."
javac -d "$BIN_DIR/java17" --release 17 "$SOURCES_DIR/CompatibilityFixture.java" "$SOURCES_DIR/SwitchExpressionFixture.java"

echo "Compiling for Java 24..."
javac -d "$BIN_DIR/java24" --release 24 "$SOURCES_DIR/CompatibilityFixture.java" "$SOURCES_DIR/SwitchExpressionFixture.java" "$SOURCES_DIR/RecordFixture.java"

echo "Done compiling fixtures."
