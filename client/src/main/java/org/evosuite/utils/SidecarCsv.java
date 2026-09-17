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

import java.io.File;

/**
 * The conventions every per-run sidecar CSV shares: the file lives in a
 * report directory as {@code <prefix>_<sanitized target class>.csv}, and
 * free-text fields are quoted RFC-4180 style. Kept in one place so the
 * sidecars written by different recorders stay byte-compatible for offline
 * joins on their shared columns (goal descriptions, ids).
 */
public final class SidecarCsv {

    private SidecarCsv() {
    }

    /** {@code <REPORT_DIR>/<prefix>[_<target class>].csv}. */
    public static String path(String prefix) {
        return path(Properties.REPORT_DIR, prefix);
    }

    /** {@code <dir>/<prefix>[_<target class>].csv}, with {@code dir} falling back to {@code REPORT_DIR} when empty. */
    public static String path(String dir, String prefix) {
        String directory = dir == null || dir.isEmpty() ? Properties.REPORT_DIR : dir;
        String tc = Properties.TARGET_CLASS;
        String suffix = (tc == null || tc.isEmpty()) ? "" : "_" + sanitize(tc);
        return directory + File.separator + prefix + suffix + ".csv";
    }

    /** Replaces every character outside {@code [A-Za-z0-9_.$-]} so a class name is a safe file-name component. */
    public static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.$-]", "_");
    }

    /** Quotes {@code s} when it contains a comma, quote or line break; null becomes the empty field. */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** NaN renders as the empty field. */
    public static String formatDouble(double v) {
        return Double.isNaN(v) ? "" : String.valueOf(v);
    }
}
