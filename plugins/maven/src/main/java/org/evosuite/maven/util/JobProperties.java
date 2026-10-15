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

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The EvoSuite properties the plugin passes to every test generation job, through the file CTG reads
 * with {@code ctg_extra_properties_file}. A file, unlike {@code ctg_extra_args}, keeps values with spaces intact.
 */
public final class JobProperties {

    /**
     * Where EvoSuite writes LLM traces, which contain prompts built from the project's code.
     */
    public static final String LLM_TRACE_DIR = "llm_trace_dir";

    /**
     * The key EvoSuite reads from {@code EVOSUITE_LLM_API_KEY} when this property is empty.
     */
    public static final String LLM_API_KEY = "llm_api_key";

    /**
     * The environment variable EvoSuite reads the LLM API key from.
     */
    public static final String LLM_API_KEY_VARIABLE = "EVOSUITE_LLM_API_KEY";

    private JobProperties() {
    }

    /**
     * The properties for the jobs: the plugin's defaults, overridden by the configured ones.
     *
     * @param ctgFolder  EvoSuite's folder in the module, {@code .evosuite}
     * @param configured the properties configured in the pom, possibly {@code null}
     * @return the properties
     */
    public static Properties forJobs(File ctgFolder, Map<String, String> configured) {
        Properties properties = new Properties();
        // EvoSuite's own default is evosuite-report/llm-traces in the module, where it would be committed
        properties.setProperty(LLM_TRACE_DIR, new File(ctgFolder, "llm-traces").getAbsolutePath());
        if (configured != null) {
            for (Map.Entry<String, String> entry : configured.entrySet()) {
                properties.setProperty(entry.getKey().trim(),
                        entry.getValue() == null ? "" : entry.getValue().trim());
            }
        }
        return properties;
    }

    /**
     * The names among the configured properties that EvoSuite does not have, eg because of a typo.
     *
     * @param configured the properties configured in the pom, possibly {@code null}
     * @return the unknown names, sorted
     */
    public static List<String> unknownNames(Map<String, String> configured) {
        List<String> unknown = new ArrayList<>();
        if (configured != null) {
            Set<String> known = org.evosuite.Properties.getParameters();
            for (String name : new TreeSet<>(configured.keySet())) {
                if (!known.contains(name.trim())) {
                    unknown.add(name);
                }
            }
        }
        return unknown;
    }

    /**
     * Write the properties to a temporary file, deleted when Maven exits.
     *
     * @param properties the properties
     * @return the file
     * @throws IOException if it cannot be written
     */
    public static File write(Properties properties) throws IOException {
        File file = File.createTempFile("EvoSuite_properties", ".properties");
        file.deleteOnExit();
        try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            properties.store(out, "EvoSuite properties for test generation jobs");
        }
        return file;
    }
}
