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
package org.evosuite.statistics.backend;

import org.evosuite.Properties;
import org.evosuite.statistics.OutputVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CSVStatisticsBackendTest {

    private final String defaultReportDir = Properties.REPORT_DIR;

    @AfterEach
    public void reset() {
        Properties.REPORT_DIR = defaultReportDir;
    }

    @Test
    public void valuesWithCommasAndQuotesAreEscaped(@TempDir Path tmp) throws Exception {
        Properties.REPORT_DIR = tmp.toString();
        Map<String, OutputVariable<?>> data = new LinkedHashMap<>();
        data.put("TARGET_CLASS", new OutputVariable<>("TARGET_CLASS", "Foo"));
        data.put("Criteria", new OutputVariable<>("Criteria", "LINE,BRANCH"));
        data.put("Note", new OutputVariable<>("Note", "say \"hi\""));
        data.put("Coverage", new OutputVariable<>("Coverage", 0.5));

        new CSVStatisticsBackend().writeData(null, data);

        List<String> lines = Files.readAllLines(tmp.resolve("statistics.csv"), StandardCharsets.UTF_8);
        assertEquals("TARGET_CLASS,Criteria,Note,Coverage", lines.get(0));
        assertEquals("Foo,\"LINE,BRANCH\",\"say \"\"hi\"\"\",0.5", lines.get(1));
    }
}
