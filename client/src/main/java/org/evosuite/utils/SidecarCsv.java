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
package org.evosuite.utils;

import org.evosuite.Properties;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * The conventions every per-run sidecar CSV shares: the file lives in a
 * report directory as {@code <prefix>_<sanitized target class>.csv}, and is
 * written in one go as a header line followed by one line per row.
 */
public final class SidecarCsv {

    private SidecarCsv() {
    }

    /** {@code <REPORT_DIR>/<prefix>[_<target class>].csv}. */
    public static String path(String prefix) {
        String tc = Properties.TARGET_CLASS;
        String suffix = (tc == null || tc.isEmpty()) ? "" : "_" + sanitize(tc);
        return Properties.REPORT_DIR + File.separator + prefix + suffix + ".csv";
    }

    /** Replaces every character outside {@code [A-Za-z0-9_.$-]} so a class name is a safe file-name component. */
    public static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.$-]", "_");
    }

    /**
     * Writes {@code header} and then each of {@code lines} to {@code path}, each followed by the
     * platform line separator, replacing any existing file and creating the parent directory if
     * needed. Failures are logged as warnings on {@code logger}, naming the file as {@code what}.
     *
     * @return true if the file was written completely
     */
    public static boolean write(String path, String header, Iterable<String> lines,
                                Logger logger, String what) {
        File file = new File(path);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            logger.warn("Could not create directory {} for {}", dir, file.getName());
            return false;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(header);
            writer.newLine();
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warn("Failed to write {}: {}", what, e.getMessage());
            return false;
        }
        return true;
    }
}
