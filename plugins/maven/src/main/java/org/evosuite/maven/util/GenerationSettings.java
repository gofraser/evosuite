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
package org.evosuite.maven.util;

import org.apache.maven.artifact.Artifact;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defaults of the generate goal that depend on the rest of its configuration or on the project.
 */
public final class GenerationSettings {

    /**
     * Memory per core when none is configured. CTG needs at least 500 MB per job.
     */
    public static final int DEFAULT_MEMORY_PER_CORE_MB = 1000;

    /**
     * The smallest budget per class. A job splits half of its budget evenly between minimisation, assertion
     * generation, the check that the tests pass, and other phases; below a minute these get too little time,
     * and the tests can come out without assertions, and even failing on the code they were generated for.
     */
    public static final int MIN_SECONDS_PER_CLASS = 60;

    private static final Pattern DURATION = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?");

    private GenerationSettings() {
    }

    /**
     * The memory for all parallel jobs together.
     *
     * @param configured the configured memory, or {@code null}
     * @param cores      the number of parallel jobs
     * @return the configured memory, or the default per core times the cores
     */
    public static int memoryInMB(Integer configured, int cores) {
        return configured != null ? configured : DEFAULT_MEMORY_PER_CORE_MB * Math.max(1, cores);
    }

    /**
     * Parse a duration such as {@code 30s}, {@code 2m} or {@code 1m30s}.
     *
     * @param duration the duration
     * @return the duration in seconds
     * @throws IllegalArgumentException if it is not a duration with units
     */
    public static int parseSeconds(String duration) throws IllegalArgumentException {
        String value = duration == null ? "" : duration.trim().toLowerCase();
        Matcher m = DURATION.matcher(value);
        if (value.isEmpty() || !m.matches()) {
            throw new IllegalArgumentException("'" + duration + "' is not a duration; use eg 30s, 2m or 1m30s");
        }
        long seconds = 0;
        if (m.group(1) != null) {
            seconds += 3600L * Long.parseLong(m.group(1));
        }
        if (m.group(2) != null) {
            seconds += 60L * Long.parseLong(m.group(2));
        }
        if (m.group(3) != null) {
            seconds += Long.parseLong(m.group(3));
        }
        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("'" + duration + "' is too long");
        }
        return (int) seconds;
    }

    /**
     * The test format that fits the project's test dependencies: JUnit 4 if it has JUnit 4 but not
     * JUnit 5, which EvoSuite generates otherwise.
     *
     * @param testArtifacts the project's resolved test dependencies
     * @return {@code JUNIT4}, or {@code null} to keep EvoSuite's default
     */
    public static String testFormatFor(Collection<Artifact> testArtifacts) {
        boolean jupiter = false;
        boolean junit4 = false;
        for (Artifact artifact : testArtifacts) {
            jupiter |= "org.junit.jupiter".equals(artifact.getGroupId())
                    && "junit-jupiter-api".equals(artifact.getArtifactId());
            junit4 |= "junit".equals(artifact.getGroupId()) && "junit".equals(artifact.getArtifactId());
        }
        return junit4 && !jupiter ? "JUNIT4" : null;
    }
}
