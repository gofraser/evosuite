/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.search.operator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Streams {@link OperatorAttemptRecord} rows to the sidecar CSV for {@link OperatorAttemptLog}:
 * rows retire from memory throughout the run (in whatever order they go final) and are appended
 * as they do, with whatever is still open appended at {@link OperatorAttemptLog#flush()}. One
 * writer belongs to exactly one log instance and writes the header only on its first call;
 * every call after that appends. Rows therefore land in the file out of insertion order —
 * {@code generation} and {@code attempt_id} are in every row so analysis can re-sort them.
 */
final class OperatorAttemptCsvWriter {

    private boolean headerWritten;

    /**
     * Appends the rendered {@code rows} to {@code path}, creating the file and its parent directories and
     * writing the CSV header on this writer's first call, appending without a header afterwards.
     * No-op for an empty list. Throws on failure; the caller decides how to handle that (the rows
     * are considered spent by the log either way — a broken disk must not make it retain them).
     */
    void appendRows(String path, List<String> rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }
        File file = new File(path);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create directory " + dir);
        }
        boolean writeHeader = !headerWritten;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, headerWritten))) {
            if (writeHeader) {
                writer.write(OperatorAttemptRecord.CSV_HEADER);
                writer.newLine();
            }
            for (String row : rows) {
                writer.write(row);
                writer.newLine();
            }
        }
        headerWritten = true;
    }
}
