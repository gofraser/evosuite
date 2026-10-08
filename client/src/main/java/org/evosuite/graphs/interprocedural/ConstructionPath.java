package org.evosuite.graphs.interprocedural;

import org.evosuite.graphs.interprocedural.var.DepVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * A chain of dependency variables describing how an object is constructed, together with the
 * positions at which each variable is used by its successor.
 */
public class ConstructionPath {

    private List<DepVariable> path = new ArrayList<>();
    /**
     * the position[i] indicates the position of path[i] is used for path[i+1],
     * the invariant is that path.length = positions.length + 1
     */
    private List<Integer> positions = new ArrayList<>();

    /**
     * Creates a construction path.
     *
     * @param path      the variables on the path
     * @param positions the positions at which each variable is used by the next one
     */
    public ConstructionPath(List<DepVariable> path, List<Integer> positions) {
        super();
        this.path = path;
        this.positions = positions;
    }

    public List<DepVariable> getPath() {
        return path;
    }

    public void setPath(List<DepVariable> path) {
        this.path = path;
    }

    /**
     * Two paths are equal if they contain equal variables in the same order.
     *
     * @param obj the object to compare to
     * @return true if the other object is an equal construction path
     */
    public boolean equals(Object obj) {
        if (obj instanceof ConstructionPath) {
            ConstructionPath otherPath = (ConstructionPath) obj;

            if (otherPath.size() == path.size()) {
                for (int i = 0; i < otherPath.size(); i++) {
                    if (!otherPath.getPath().get(i).equals(this.path.get(i))) {
                        return false;
                    }
                }

                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether the path is hard to construct, because it involves a static field or is longer than two
     * variables.
     *
     * @return true if the path is difficult
     */
    public boolean isDifficult() {
        for (DepVariable var : path) {
            if (var.isStaticField()) {
                return true;
            }
        }

        return this.path.size() > 2;
    }

    public int size() {
        return path.size();
    }

    public String toString() {
        return path.toString();
    }

    public List<Integer> getPosition() {
        return positions;
    }

    public void setPosition(List<Integer> position) {
        this.positions = position;
    }

    /**
     * Checks whether the root of the path belongs to the active class or is a static def-use.
     *
     * @return true if the root is valid
     */
    public boolean hasValidRoot() {
        if (this.getPath().isEmpty()) {
            return false;
        }

        DepVariable root = this.getPath().get(0);
        if (root.getInstruction().getClassName()
                .equals(org.evosuite.testcase.synthesizer.TargetContext.activeClassName())) {
            return true;
        }

        if (root.getInstruction().isStaticDefUse()) {
            return true;
        }

        return false;
    }

    public DepVariable get(int i) {
        return this.path.get(i);
    }
}
