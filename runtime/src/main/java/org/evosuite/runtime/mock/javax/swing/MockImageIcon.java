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
import java.net.URL;

/**
 * Headless-safe override mock for {@link ImageIcon}.
 * The URL constructor can throw an NPE when URL is null.
 */
public class MockImageIcon extends ImageIcon implements OverrideMock {

    private static final long serialVersionUID = 1L;

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
}
