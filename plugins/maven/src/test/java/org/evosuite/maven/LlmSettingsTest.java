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
package org.evosuite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.evosuite.continuous.job.JobHandler;
import org.evosuite.maven.util.JobProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LlmSettingsTest {

    @Test
    public void propertiesReachTheJobsIntactAndOverrideTheDefaults(@TempDir Path tmp) throws Exception {
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("llm_provider", "OLLAMA");
        configured.put("llm_model", "llama3.1 instruct"); // spaces would break extraArgs

        Properties properties = JobProperties.forJobs(tmp.resolve(".evosuite").toFile(), configured);
        List<String> arguments = JobHandler.readExtraProperties(JobProperties.write(properties).getAbsolutePath());

        assertTrue(arguments.contains("-Dllm_model=llama3.1 instruct"), arguments.toString());
        assertTrue(arguments.contains("-Dllm_provider=OLLAMA"), arguments.toString());
        String traces = new File(tmp.resolve(".evosuite").toFile(), "llm-traces").getAbsolutePath();
        assertTrue(arguments.contains("-Dllm_trace_dir=" + traces), "traces belong in .evosuite: " + arguments);

        Properties overridden = JobProperties.forJobs(tmp.toFile(),
                Collections.singletonMap("llm_trace_dir", "/elsewhere"));
        assertEquals("/elsewhere", overridden.getProperty("llm_trace_dir"));
    }

    @Test
    public void unknownPropertyNamesAreReported() {
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("llm_provider", "OLLAMA");
        configured.put("llm_modle", "typo");
        configured.put("search_budget", "30");

        assertEquals(Arrays.asList("llm_modle"), JobProperties.unknownNames(configured));
        assertTrue(JobProperties.unknownNames(null).isEmpty());
    }

    @Test
    public void retiredPropertyNamesAreIgnoredInsteadOfRejected() {
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("epsilon", "0.1");
        configured.put("search_budget", "30");
        configured.put("llm_modle", "typo");

        assertEquals(Arrays.asList("llm_modle"), JobProperties.unknownNames(configured));
        assertEquals(Arrays.asList("epsilon"), JobProperties.retiredNames(configured));
        assertTrue(JobProperties.retiredNames(null).isEmpty());

        Properties forJobs = JobProperties.forJobs(new File("."), configured);
        assertNull(forJobs.getProperty("epsilon"));
        assertEquals("30", forJobs.getProperty("search_budget"));
    }

    @Test
    public void apiKeyComesFromTheDecryptedServerPassword() throws Exception {
        Settings settings = new Settings();
        Server server = new Server();
        server.setId("openai");
        server.setPassword("{encrypted}");
        settings.addServer(server);

        Server decrypted = server.clone();
        decrypted.setPassword("sk-secret");
        SettingsDecryptionResult result = mock(SettingsDecryptionResult.class);
        when(result.getProblems()).thenReturn(Collections.emptyList());
        when(result.getServer()).thenReturn(decrypted);
        SettingsDecrypter decrypter = mock(SettingsDecrypter.class);
        when(decrypter.decrypt(any(SettingsDecryptionRequest.class))).thenReturn(result);

        assertEquals("sk-secret", GenerateMojo.resolveLlmApiKey(settings, decrypter, "openai"));
        assertNull(GenerateMojo.resolveLlmApiKey(settings, decrypter, null));
        MojoExecutionException missing = assertThrows(MojoExecutionException.class,
                () -> GenerateMojo.resolveLlmApiKey(settings, decrypter, "anthropic"));
        assertTrue(missing.getMessage().contains("no <server>"), missing.getMessage());
    }
}
