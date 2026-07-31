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
package org.evosuite.utils.generic;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.evosuite.rmi.MasterClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GenericMethodMasterDeserializationFallbackTest {

    @AfterEach
    public void tearDown() {
        MasterClassLoader.clearForTest();
    }

    @Test
    public void shouldUsePlaceholderMethodWhenSerializedMethodIsMissingOnMaster() throws Exception {
        Method toString = Object.class.getDeclaredMethod("toString");
        GenericMethod original = new GenericMethod(toString, Object.class);

        byte[] serialized = serialize(original);
        byte[] tampered = replaceAsciiToken(serialized, "toString", "missingX");

        MasterClassLoader.markMasterProcess();

        GenericMethod recovered = deserialize(tampered);

        assertEquals("unresolvedMemberPlaceholder", recovered.getMethod().getName());
        assertEquals(SerializationFallbackMember.class, recovered.getMethod().getDeclaringClass());
    }

    @Test
    public void shouldWarnOnlyOncePerUnresolvedMethodOnMaster() throws Exception {
        Method toString = Object.class.getDeclaredMethod("toString");
        GenericMethod original = new GenericMethod(toString, Object.class);

        byte[] serialized = serialize(original);
        byte[] tampered = replaceAsciiToken(serialized, "toString", "missingY");

        Logger logger = (Logger) LoggerFactory.getLogger(GenericMethod.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);

        MasterClassLoader.markMasterProcess();
        try {
            deserialize(tampered);
            deserialize(tampered);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }

        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage()
                        .contains("Falling back to placeholder method for unresolved java.lang.Object.missingY()Ljava/lang/String; on master side"))
                .collect(Collectors.toList());

        assertEquals(1, warnings.size(), "Repeated deserialization of the same unresolved method should warn only once");
    }

    private static byte[] serialize(GenericMethod value) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            return baos.toByteArray();
        }
    }

    private static GenericMethod deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (GenericMethod) ois.readObject();
        }
    }

    private static byte[] replaceAsciiToken(byte[] input, String from, String to) {
        byte[] source = from.getBytes(StandardCharsets.ISO_8859_1);
        byte[] target = to.getBytes(StandardCharsets.ISO_8859_1);
        if (source.length != target.length) {
            throw new IllegalArgumentException("Replacement token length must match");
        }

        byte[] out = input.clone();
        int index = indexOf(out, source);
        assertTrue(index >= 0, "Serialized payload did not contain expected token");
        System.arraycopy(target, 0, out, index, target.length);
        return out;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
