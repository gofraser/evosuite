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
package org.evosuite.llm.provider;

import java.io.IOException;

/**
 * Raised by {@link JsonHttpClient#postJson} when the {@link
 * RequestCancellation} guarding the call was already aborted before any
 * bytes were written to the connection. Distinguishing this from an ordinary
 * transport {@link IOException} lets a caller recognise that the request
 * never reached the server at all — it was abandoned because the caller
 * itself gave up (a phase deadline or an interruption), not because the
 * provider or network failed.
 */
public class RequestCancelledBeforeSendException extends IOException {

    public static final String MESSAGE = "request cancelled before it was sent";

    public RequestCancelledBeforeSendException() {
        super(MESSAGE);
    }
}
