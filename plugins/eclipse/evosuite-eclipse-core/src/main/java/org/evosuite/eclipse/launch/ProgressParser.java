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
package org.evosuite.eclipse.launch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits EvoSuite's console output into ordinary lines and progress updates.
 *
 * <p>With {@code show_progress} on, the master redraws a bar in place, each frame preceded by a
 * carriage return and none followed by a newline:
 * {@code [Progress:=====>   17%] [Cov:=======>    23%]}. Frames go to {@link Listener#progress};
 * everything else is passed on line by line, so the console shows the log without hundreds of
 * bar frames.
 */
public final class ProgressParser {

    public interface Listener {
        void line(String line);

        void progress(int searchPercent, int coveragePercent);
    }

    private static final Pattern BAR =
            Pattern.compile("\\[Progress:[^\\]]*?(\\d+)%\\]\\s*\\[Cov:[^\\]]*?(\\d+)%\\]");

    private final Listener listener;
    private final StringBuilder segment = new StringBuilder();
    /** A carriage return whose meaning depends on the next character: CRLF or a redraw. */
    private boolean pendingCarriageReturn;

    public ProgressParser(Listener listener) {
        this.listener = listener;
    }

    public void accept(CharSequence chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false;
                if (c == '\n') {
                    flush(true);
                    continue;
                }
                flush(false);
            }
            if (c == '\n') {
                flush(true);
            } else if (c == '\r') {
                pendingCarriageReturn = true;
            } else {
                segment.append(c);
            }
        }
    }

    /** Emits whatever is left once the stream has ended. */
    public void close() {
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false;
            flush(false);
        }
        if (segment.length() > 0) {
            flush(true);
        }
    }

    private void flush(boolean endOfLine) {
        String text = segment.toString();
        segment.setLength(0);
        Matcher bar = BAR.matcher(text);
        if (bar.find()) {
            listener.progress(Integer.parseInt(bar.group(1)), Integer.parseInt(bar.group(2)));
            String rest = (text.substring(0, bar.start()) + text.substring(bar.end())).strip();
            if (!rest.isEmpty()) {
                listener.line(rest);
            }
        } else if (endOfLine || !text.isBlank()) {
            // A carriage return with nothing before it is a redraw, not an empty line.
            listener.line(text);
        }
    }
}
