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
package org.evosuite.rmi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.lang.reflect.Proxy;
import java.rmi.server.RMIClassLoader;
import java.rmi.server.RMIClassLoaderSpi;

/**
 * RMI classloader SPI that prioritizes the master-side project classloader.
 */
public class EvoSuiteRMIClassLoaderSpi extends RMIClassLoaderSpi {

    private static final Logger logger = LoggerFactory.getLogger(EvoSuiteRMIClassLoaderSpi.class);
    private final RMIClassLoaderSpi delegate = RMIClassLoader.getDefaultProviderInstance();

    @Override
    public Class<?> loadClass(String codebase, String name, ClassLoader defaultLoader)
            throws MalformedURLException, ClassNotFoundException {
        ClassLoader master = MasterClassLoader.getIfInitialized();
        if (master != null) {
            try {
                logger.debug("RMI SPI resolving {} via MasterClassLoader", name);
                return Class.forName(name, false, master);
            } catch (ClassNotFoundException ignored) {
                logger.debug("RMI SPI fallback for {} to default provider", name);
            }
        }
        return delegate.loadClass(codebase, name, defaultLoader);
    }

    @Override
    public Class<?> loadProxyClass(String codebase, String[] interfaces, ClassLoader defaultLoader)
            throws MalformedURLException, ClassNotFoundException {
        ClassLoader master = MasterClassLoader.getIfInitialized();
        if (master != null) {
            try {
                Class<?>[] loaded = new Class<?>[interfaces.length];
                for (int i = 0; i < interfaces.length; i++) {
                    loaded[i] = Class.forName(interfaces[i], false, master);
                }
                return Proxy.getProxyClass(master, loaded);
            } catch (ClassNotFoundException e) {
                logger.debug("RMI SPI proxy fallback to default provider", e);
            } catch (IllegalArgumentException e) {
                logger.debug("RMI SPI proxy fallback due to proxy construction error: {}", e.getMessage());
            }
        }
        return delegate.loadProxyClass(codebase, interfaces, defaultLoader);
    }

    @Override
    public ClassLoader getClassLoader(String codebase) throws MalformedURLException {
        ClassLoader master = MasterClassLoader.getIfInitialized();
        // In EvoSuite's in-process RMI setup, all relevant classes are local.
        // Prefer the explicit project-aware master loader over remote codebase loading.
        return master != null ? master : delegate.getClassLoader(codebase);
    }

    @Override
    public String getClassAnnotation(Class<?> cl) {
        return delegate.getClassAnnotation(cl);
    }
}
