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
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.mock.OverrideMock;

import javax.swing.ImageIcon;
import java.awt.Image;
import java.net.URL;

/**
 * Headless-safe override mock for {@link ImageIcon}.
 * The URL constructor can throw an NPE when URL is null.
 */
public class MockImageIcon extends ImageIcon implements OverrideMock {

    private static final long serialVersionUID = 1L;

    /**
     * Default constructor matching {@link ImageIcon#ImageIcon()}.
     */
    public MockImageIcon() {
        super();
    }

    /**
     * Creates a new MockImageIcon from a URL.
     * @param location the URL of the image
     */
    public MockImageIcon(URL location) {
        super();
        if (location != null) {
            setDescription(location.toExternalForm());
        }
    }

    public MockImageIcon(String filename) {
        super(filename);
    }

    public MockImageIcon(String filename, String description) {
        super(filename, description);
    }

    public MockImageIcon(byte[] imageData) {
        super(imageData);
    }

    public MockImageIcon(byte[] imageData, String description) {
        super(imageData, description);
    }

    public MockImageIcon(Image image) {
        super(image);
    }

    public MockImageIcon(Image image, String description) {
        super(image, description);
    }

    public MockImageIcon(URL location, String description) {
        this(location);
        setDescription(description);
    }
}
