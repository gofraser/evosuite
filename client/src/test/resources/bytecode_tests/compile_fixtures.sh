#!/bin/bash
#
# Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
# contributors
#
# This file is part of EvoSuite.
#
# EvoSuite is free software: you can redistribute it and/or modify it
# under the terms of the GNU Lesser General Public License as published
# by the Free Software Foundation, either version 3.0 of the License, or
# (at your option) any later version.
#
# EvoSuite is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
# Lesser Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
#

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
