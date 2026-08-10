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
package com.examples.with.different.packagename.solver;

public class Maze {

    // Maze hard-coded dimensions
    private static final int H_SIZE = 7;
    private static final int W_SIZE = 11;


    public static int walk(char[] moves) {
        char[][] maze = new char[][]{
                // row 0
                "+-+---+---+".toCharArray(),
                // row 1
                "| |     |#|".toCharArray(),
                // row 2
                "| | --+ | |".toCharArray(),
                // row 3
                "| |   | | |".toCharArray(),
                // row 4
                "| +-- | | |".toCharArray(),
                // row 5
                "|     |   |".toCharArray(),
                // row 6
                "+-----+---+".toCharArray()};


        int x, y; // Player position
        int ox, oy; // Old player position
        int i = 0; // Iteration number

        // Initial position
        x = 1;
        y = 1;
        maze[y][y] = 'X';

        // Iterate and run 'program'
        while (i < moves.length) {
            ox = x;
            oy = y;
            switch (moves[i]) {
                case 'w':
                    y--;
                    break;
                case 's':
                    y++;
                    break;
                case 'a':
                    x--;
                    break;
                case 'd':
                    x++;
                    break;
                default: {
                    return 0;
                }
            }

            if (maze[y][x] == '#') {
                return 1;
            }

            if (maze[y][x] != ' ' && !((y == 2 && maze[y][x] == '|' && x > 0 && x < W_SIZE))) {
                x = ox;
                y = oy;
            }

            if (ox == x && oy == y) {
                return 2;
            }

            maze[y][x] = 'X';

            i++;
        }

        return 3;
    }

}
