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
 */
package com.examples.with.different.packagename.objectconstruction.tuning;

public class OpaqueInterfaceCallExample {

    public interface Headers {
        boolean contains(String name);
    }

    public interface Response {
        Headers headers();
    }

    public static class HeadersImpl implements Headers {
        @Override
        public boolean contains(String name) {
            return "x".equals(name);
        }
    }

    public static class ResponseImpl implements Response {
        @Override
        public Headers headers() {
            return new HeadersImpl();
        }
    }

    public boolean target(Response response) {
        if (response.headers().contains("x")) {
            return true;
        }
        return false;
    }
}
