/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Utility class for registering signal handlers using reflection.
 * This avoids compile-time dependencies on sun.misc.Signal and sun.misc.SignalHandler,
 * which are internal JDK APIs not available in all JDK configurations.
 */
public class SignalUtils {

    private static final Logger logger = LoggerFactory.getLogger(SignalUtils.class);

    /**
     * Registers a handler for the specified signal using reflection.
     * If the signal handling classes are not available, this method fails silently.
     *
     * @param signalName the signal name (e.g., "INT" for SIGINT)
     * @param handler    a consumer that will be called when the signal is received.
     *                   The consumer receives the signal object (which can be ignored).
     * @return true if the handler was successfully registered, false otherwise
     */
    public static boolean registerSignalHandler(String signalName, Consumer<Object> handler) {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> signalHandlerClass = Class.forName("sun.misc.SignalHandler");

            // Create a Signal instance for the specified signal name
            Object signal = signalClass.getConstructor(String.class).newInstance(signalName);

            // Create a dynamic proxy that implements SignalHandler
            Object proxyHandler = Proxy.newProxyInstance(
                    signalHandlerClass.getClassLoader(),
                    new Class<?>[]{signalHandlerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (method.getName().equals("handle")) {
                                handler.accept(args != null && args.length > 0 ? args[0] : null);
                            }
                            return null;
                        }
                    }
            );

            // Register the handler: Signal.handle(signal, handler)
            signalClass.getMethod("handle", signalClass, signalHandlerClass)
                    .invoke(null, signal, proxyHandler);

            return true;
        } catch (Throwable e) {
            logger.debug("Could not register signal handler for {}: {}", signalName, e.getMessage());
            return false;
        }
    }

    /**
     * Registers a handler for SIGINT (Ctrl+C) using reflection.
     *
     * @param handler a consumer that will be called when SIGINT is received
     * @return true if the handler was successfully registered, false otherwise
     */
    public static boolean registerInterruptHandler(Consumer<Object> handler) {
        return registerSignalHandler("INT", handler);
    }
}
