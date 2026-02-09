/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga;

import org.evosuite.Properties;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Construction of a grid and the neighbourhood models
 *
 * @author Nasser Albunian
 */
public class Neighbourhood<T extends Chromosome<T>> implements NeighbourModels<T>, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The population size.
     **/
    private final int populationSize;

    /**
     * An array that represents the grid.
     **/
    int[][] neighbour;

    /**
     * Number of chromosomes per one row of a grid.
     **/
    int columns;

    public Neighbourhood(int populationSize) {

        this.populationSize = populationSize;

        neighbour = new int[this.populationSize][0];

        columns = (int) Math.sqrt(this.populationSize);

        constructNeighbour();
    }

    /**
     * Construct the grid and define positions of neighbours for each individual.
     */
    public void constructNeighbour() {

        for (int i = 0; i < populationSize; i++) {
            neighbour[i] = new int[8];
        }

        for (int i = 0; i < populationSize; i++) {

            //~~~~ NORTH ~~~~//
            if (i > columns - 1) {
                neighbour[i][Positions.N.ordinal()] = i - columns;
            } else {
                int mod = populationSize % columns;
                if (mod != 0) {
                    int thisPosition = ((i - columns + populationSize) % populationSize);
                    if (i == 0) {
                        neighbour[i][Positions.N.ordinal()] = populationSize - (mod);
                    } else {
                        if (mod > 1) {
                            if (i >= mod) {
                                neighbour[i][Positions.N.ordinal()] = thisPosition - mod;
                            } else {
                                neighbour[i][Positions.N.ordinal()] = thisPosition + 1;
                            }
                        } else {
                            neighbour[i][Positions.N.ordinal()] = thisPosition - 1;
                        }
                    }
                } else {
                    neighbour[i][Positions.N.ordinal()] = (i - columns + populationSize) % populationSize;
                }
            }

            //~~~~ SOUTH ~~~~//
            int thisPosition = (i + columns) % populationSize;
            if (populationSize % columns != 0 && i + columns >= populationSize) {
                neighbour[i][Positions.S.ordinal()] = i % columns;
            } else {
                neighbour[i][Positions.S.ordinal()] = thisPosition;
            }

            //~~~~ EAST ~~~~//
            if ((i + 1) % columns == 0) {
                neighbour[i][Positions.E.ordinal()] = i - (columns - 1);
            } else {
                if (populationSize % columns != 0 && i == populationSize - 1) {
                    neighbour[i][Positions.E.ordinal()] = (i % columns) + 1;
                } else {
                    neighbour[i][Positions.E.ordinal()] = i + 1;
                }
            }

            //~~~~ WEST ~~~~//
            if (i % columns == 0) {
                int westPosition = i + (columns - 1);
                if (westPosition >= populationSize) {
                    neighbour[i][Positions.W.ordinal()] = neighbour[i][Positions.E.ordinal()];
                } else {
                    neighbour[i][Positions.W.ordinal()] = westPosition;
                }
            } else {
                neighbour[i][Positions.W.ordinal()] = i - 1;
            }
        }

        //~~~~ NW, SW, NE, SE ~~~~//
        for (int i = 0; i < populationSize; i++) {
            neighbour[i][Positions.NW.ordinal()] = neighbour[neighbour[i][Positions.N.ordinal()]][Positions.W.ordinal()];

            neighbour[i][Positions.SW.ordinal()] = neighbour[neighbour[i][Positions.S.ordinal()]][Positions.W.ordinal()];

            neighbour[i][Positions.NE.ordinal()] = neighbour[neighbour[i][Positions.N.ordinal()]][Positions.E.ordinal()];

            neighbour[i][Positions.SE.ordinal()] = neighbour[neighbour[i][Positions.S.ordinal()]][Positions.E.ordinal()];
        }

    }

    /**
     * Retrieve neighbours of a chromosome according to the ring topology (i.e. 1D).
     *
     * @param collection The current collection of chromosomes
     * @param position   The position of a chromosome which its neighbours will be retrieved
     * @return collection of neighbours
     */
    public List<T> ringTopology(List<T> collection, int position) {
        List<T> chromosomes = new ArrayList<>();
        int left;
        if (position - 1 < 0) {
            left = collection.size() - 1;
        } else {
            left = position - 1;
        }

        int right;
        if (position + 1 > collection.size() - 1) {
            right = 0;
        } else {
            right = position + 1;
        }

        chromosomes.add(collection.get(left));
        chromosomes.add(collection.get(position));
        chromosomes.add(collection.get(right));

        return chromosomes;
    }

    /**
     * Retrieve neighbours of a chromosome according to the linear five model (i.e. L5).
     *
     * @param collection The current collection of chromosomes
     * @param position   The position of a chromosome which its neighbours will be retrieved
     * @return collection of neighbours
     */
    public List<T> linearFive(List<T> collection, int position) {
        List<T> chromosomes = new ArrayList<>();
        int north = neighbour[position][Positions.N.ordinal()];
        int south = neighbour[position][Positions.S.ordinal()];
        int east = neighbour[position][Positions.E.ordinal()];
        int west = neighbour[position][Positions.W.ordinal()];

        chromosomes.add(collection.get(north));
        chromosomes.add(collection.get(south));
        chromosomes.add(collection.get(east));
        chromosomes.add(collection.get(west));
        chromosomes.add(collection.get(position));

        return chromosomes;
    }

    /**
     * Retrieve neighbours of a chromosome according to the compact nine model (i.e. C9).
     *
     * @param collection The current collection of chromosomes
     * @param position   The position of a chromosome which its neighbours will be retrieved
     * @return collection of neighbours
     */
    public List<T> compactNine(List<T> collection, int position) {
        List<T> chromosomes = new ArrayList<>();
        int north = neighbour[position][Positions.N.ordinal()];
        int south = neighbour[position][Positions.S.ordinal()];
        int east = neighbour[position][Positions.E.ordinal()];
        int west = neighbour[position][Positions.W.ordinal()];
        int northWest = neighbour[neighbour[position][Positions.N.ordinal()]][Positions.W.ordinal()];
        int southWest = neighbour[neighbour[position][Positions.S.ordinal()]][Positions.W.ordinal()];
        int northEast = neighbour[neighbour[position][Positions.N.ordinal()]][Positions.E.ordinal()];
        int southEast = neighbour[neighbour[position][Positions.S.ordinal()]][Positions.E.ordinal()];

        chromosomes.add(collection.get(north));
        chromosomes.add(collection.get(south));
        chromosomes.add(collection.get(east));
        chromosomes.add(collection.get(west));
        chromosomes.add(collection.get(northWest));
        chromosomes.add(collection.get(southWest));
        chromosomes.add(collection.get(northEast));
        chromosomes.add(collection.get(southEast));
        chromosomes.add(collection.get(position));

        return chromosomes;
    }

    /**
     * Retrieve neighbours of a chromosome according to the linear compact thirteen (i.e. C13).
     *
     * @param collection The current collection of chromosomes
     * @param position   The position of a chromosome which its neighbours will be retrieved
     * @return collection of neighbours
     */
    public List<T> compactThirteen(List<T> collection, int position) {
        List<T> chromosomes = new ArrayList<>();
        int north = neighbour[position][Positions.N.ordinal()];
        int south = neighbour[position][Positions.S.ordinal()];
        int east = neighbour[position][Positions.E.ordinal()];
        int west = neighbour[position][Positions.W.ordinal()];
        int northWest = neighbour[neighbour[position][Positions.N.ordinal()]][Positions.W.ordinal()];
        int southWest = neighbour[neighbour[position][Positions.S.ordinal()]][Positions.W.ordinal()];
        int northEast = neighbour[neighbour[position][Positions.N.ordinal()]][Positions.E.ordinal()];
        int southEast = neighbour[neighbour[position][Positions.S.ordinal()]][Positions.E.ordinal()];
        int northNorth = neighbour[north][Positions.N.ordinal()];
        int southSouth = neighbour[south][Positions.S.ordinal()];
        int eastEast = neighbour[east][Positions.E.ordinal()];
        int westWest = neighbour[west][Positions.W.ordinal()];

        chromosomes.add(collection.get(north));
        chromosomes.add(collection.get(south));
        chromosomes.add(collection.get(east));
        chromosomes.add(collection.get(west));
        chromosomes.add(collection.get(northWest));
        chromosomes.add(collection.get(southWest));
        chromosomes.add(collection.get(northEast));
        chromosomes.add(collection.get(southEast));
        chromosomes.add(collection.get(northNorth));
        chromosomes.add(collection.get(southSouth));
        chromosomes.add(collection.get(eastEast));
        chromosomes.add(collection.get(westWest));
        chromosomes.add(collection.get(position));

        return chromosomes;
    }

    /**
     * Retrieve neighbours of a chromosome.
     *
     * @param currentPop The current population
     * @param chromosome  The chromosome which its neighbours will be retrieved
     * @return neighbours as a collection
     */
    public List<T> getNeighbors(List<T> currentPop, int chromosome) {

        switch (Properties.MODEL) {
            case ONE_DIMENSION:
                return this.ringTopology(currentPop, chromosome);
            case LINEAR_FIVE:
                return this.linearFive(currentPop, chromosome);
            case COMPACT_NINE:
                return this.compactNine(currentPop, chromosome);
            case COMPACT_THIRTEEN:
                return this.compactThirteen(currentPop, chromosome);
            default:
                return this.linearFive(currentPop, chromosome);
        }
    }

}
