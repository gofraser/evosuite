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
package org.evosuite.seeding;

import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the draw sequence, the contents and the eviction order of both bounded queues for a fixed seed.
 */
public class RandomAccessQueueSequenceTest {

    private static String trace(ConstantBag<Integer> queue) {
        Randomness.setSeed(42);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            queue.add(i % 7);
            queue.add(i % 3);
        }
        sb.append(queue).append(' ').append(queue.snapshot()).append('\n');
        for (int i = 0; i < 10; i++) {
            sb.append(queue.random()).append(' ');
        }
        sb.append('\n');
        queue.add(null);
        sb.append("removed ").append(queue.removeIf(v -> v % 2 == 0)).append(' ')
                .append(queue).append(' ').append(queue.snapshot()).append(' ').append(queue.isEmpty()).append('\n');
        for (int i = 0; i < 10; i++) {
            sb.append(queue.random()).append(' ');
        }
        sb.append('\n');
        for (int i = 20; i < 30; i++) {
            queue.add(i);
        }
        sb.append(queue).append(' ').append(queue.snapshot()).append('\n');
        sb.append("removed ").append(queue.removeIf(v -> true)).append(' ').append(queue).append(' ')
                .append(queue.snapshot()).append(' ').append(queue.isEmpty()).append('\n');
        return sb.toString();
    }

    @Test
    public void defaultQueueSequence() {
        assertEquals(
                "[ 6 0 1 2 3 4 ] [6, 0, 1, 2, 3, 4]\n"
                + "3 6 2 6 3 6 2 4 6 2 \n"
                + "removed 4 [ 1 3 ] [1, 3] false\n"
                + "1 1 1 1 3 1 3 1 3 3 \n"
                + "[ 24 25 26 27 28 29 ] [24, 25, 26, 27, 28, 29]\n"
                + "removed 6 [ ] [] true\n",
                trace(new DefaultRandomAccessQueue<>(6)));
    }

    @Test
    public void frequencyQueueSequence() {
        assertEquals(
                "[ 2 0 3 1 4 2 ] [0, 1, 2, 3, 4]\n"
                + "2 4 3 2 0 0 0 4 2 3 \n"
                + "removed 4 [ 3 1 ] [1, 3] false\n"
                + "1 3 3 1 1 1 1 3 1 1 \n"
                + "[ 24 25 26 27 28 29 ] [24, 25, 26, 27, 28, 29]\n"
                + "removed 6 [ ] [] true\n",
                trace(new FrequencyBasedRandomAccessQueue<>(6)));
    }
}
