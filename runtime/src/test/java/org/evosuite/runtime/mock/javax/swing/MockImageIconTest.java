/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MockImageIconTest {

    private static final int IMAGE_WIDTH = 7;
    private static final int IMAGE_HEIGHT = 5;

    @TempDir
    Path tempDir;

    private boolean defaultMockGui;
    private boolean defaultMockJvmNonDeterminism;
    private boolean defaultMockFrameworkEnabled;

    @BeforeEach
    void rememberRuntimeState() {
        defaultMockGui = RuntimeSettings.mockGUI;
        defaultMockJvmNonDeterminism = RuntimeSettings.mockJVMNonDeterminism;
        defaultMockFrameworkEnabled = MockFramework.isEnabled();
    }

    @AfterEach
    void restoreRuntimeState() {
        RuntimeSettings.mockGUI = defaultMockGui;
        RuntimeSettings.mockJVMNonDeterminism = defaultMockJvmNonDeterminism;
        if (defaultMockFrameworkEnabled) {
            MockFramework.enable();
        } else {
            MockFramework.disable();
        }
    }

    @Test
    void constructorsAreHeadlessSafe() {
        assertDoesNotThrow(() -> new MockImageIcon());
        assertDoesNotThrow(() -> new MockImageIcon((String) null));
        assertDoesNotThrow(() -> new MockImageIcon("dummy/path/image.png"));
        assertDoesNotThrow(() -> new MockImageIcon("dummy/path/image.png", "desc"));
        assertDoesNotThrow(() -> new MockImageIcon(new byte[]{1, 2, 3}));
        assertDoesNotThrow(() -> new MockImageIcon(new byte[]{1, 2, 3}, "bytes"));
        assertDoesNotThrow(() -> new MockImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)));
        assertDoesNotThrow(() -> new MockImageIcon(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "img"));
        assertDoesNotThrow(() -> new MockImageIcon((java.net.URL) null));
        assertDoesNotThrow(() -> new MockImageIcon((java.net.URL) null, "url"));
        assertDoesNotThrow(() -> new MockImageIcon((byte[]) null));
    }

    @Test
    void urlConstructorLoadsTheImage() throws IOException {
        URL url = writeImageFile().toURI().toURL();

        MockImageIcon icon = new MockImageIcon(url);

        // The whole point of the mock: SUT code does getImage().getScaledInstance(..)
        // straight from its own constructor.
        assertNotNull(icon.getImage(), "image must be loaded from a real URL");
        assertEquals(IMAGE_WIDTH, icon.getIconWidth());
        assertEquals(IMAGE_HEIGHT, icon.getIconHeight());
        assertEquals(url.toExternalForm(), icon.getDescription());
    }

    @Test
    void urlConstructorWithDescriptionLoadsTheImage() throws IOException {
        URL url = writeImageFile().toURI().toURL();

        MockImageIcon icon = new MockImageIcon(url, "described");

        assertNotNull(icon.getImage());
        assertEquals("described", icon.getDescription());
    }

    @Test
    void fileNameConstructorLoadsTheImage() throws IOException {
        String fileName = writeImageFile().getAbsolutePath();

        MockImageIcon icon = new MockImageIcon(fileName);

        assertNotNull(icon.getImage(), "image must be loaded from a real file name");
        assertEquals(IMAGE_WIDTH, icon.getIconWidth());
        assertEquals(fileName, icon.getDescription());
    }

    @Test
    void byteArrayConstructorLoadsTheImage() throws IOException {
        MockImageIcon icon = new MockImageIcon(imageBytes(), "bytes");

        assertNotNull(icon.getImage(), "image must be decoded from real image data");
        assertEquals(IMAGE_WIDTH, icon.getIconWidth());
        assertEquals("bytes", icon.getDescription());
    }

    @Test
    void imageConstructorKeepsTheImage() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);

        MockImageIcon icon = new MockImageIcon(image, "img");

        assertNotNull(icon.getImage());
        assertEquals(3, icon.getIconWidth());
        assertEquals("img", icon.getDescription());
    }

    @Test
    void nullArgumentsProduceAnEmptyButUsableIcon() {
        // Real ImageIcon throws NPE here; the mock must degrade instead, and the
        // degraded icon still has to answer every accessor without failing.
        MockImageIcon fromUrl = new MockImageIcon((URL) null);
        assertNull(fromUrl.getImage());
        assertDoesNotThrow(fromUrl::getIconWidth);
        assertDoesNotThrow(fromUrl::getIconHeight);
        assertDoesNotThrow(fromUrl::getDescription);

        MockImageIcon fromName = new MockImageIcon((String) null);
        assertNull(fromName.getImage());
        assertDoesNotThrow(fromName::getIconWidth);

        MockImageIcon fromBytes = new MockImageIcon((byte[]) null, "d");
        assertNull(fromBytes.getImage());
        assertEquals("d", fromBytes.getDescription());
    }

    private File writeImageFile() throws IOException {
        File file = tempDir.resolve("probe.png").toFile();
        ImageIO.write(new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB),
                "png", file);
        return file;
    }

    private byte[] imageBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB),
                "png", out);
        return out.toByteArray();
    }
}
