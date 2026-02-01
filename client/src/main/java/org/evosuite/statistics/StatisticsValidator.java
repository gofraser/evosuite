package org.evosuite.statistics;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Validates runtime variables.
 */
public class StatisticsValidator {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsValidator.class);

    /**
     * check if the variables do satisfy a set of predefined constraints: eg, the
     * number of covered targets cannot be higher than their total number
     *
     * @param map from (key->variable name) to (value -> output variable)
     * @return true if valid
     */
    public static boolean validateRuntimeVariables(Map<String, OutputVariable<?>> map) {
        if (!Properties.VALIDATE_RUNTIME_VARIABLES) {
            logger.warn("Not validating runtime variables");
            return true;
        }
        boolean valid = true;

        try {
            Integer totalBranches = getIntegerValue(map, RuntimeVariable.Total_Branches);
            Integer coveredBranches = getIntegerValue(map, RuntimeVariable.Covered_Branches);

            if (coveredBranches != null && totalBranches != null && coveredBranches > totalBranches) {
                logger.error("Obtained invalid branch count: covered " + coveredBranches + " out of " + totalBranches);
                valid = false;
            }

            Integer totalGoals = getIntegerValue(map, RuntimeVariable.Total_Goals);
            Integer coveredGoals = getIntegerValue(map, RuntimeVariable.Covered_Goals);

            if (coveredGoals != null && totalGoals != null && coveredGoals > totalGoals) {
                logger.error("Obtained invalid goal count: covered " + coveredGoals + " out of " + totalGoals);
                valid = false;
            }

            Integer totalMethods = getIntegerValue(map, RuntimeVariable.Total_Methods);
            Integer coveredMethods = getIntegerValue(map, RuntimeVariable.Covered_Methods);

            if (coveredMethods != null && totalMethods != null && coveredMethods > totalMethods) {
                logger.error("Obtained invalid method count: covered " + coveredMethods + " out of " + totalMethods);
                valid = false;
            }

            String[] criteria = null;
            if (map.containsKey("criterion")) {
                Object criterionVal = map.get("criterion").getValue();
                if (criterionVal != null) {
                    criteria = criterionVal.toString().split(":");
                }
            }

            Double coverage = getDoubleValue(map, RuntimeVariable.Coverage);
            Double branchCoverage = getDoubleValue(map, RuntimeVariable.BranchCoverage);

            if (criteria != null && criteria.length == 1 && criteria[0].equalsIgnoreCase(Criterion.BRANCH.toString())
                    && coverage != null && branchCoverage != null) {

                double diff = Math.abs(coverage - branchCoverage);
                if (diff > 0.001) {
                    logger.error("Targeting branch coverage, but Coverage is different " +
                            "from BranchCoverage: " + coverage + " != " + branchCoverage);
                    valid = false;
                }
            }


            /*
             * TODO there are more things we could check here
             */

        } catch (Exception e) {
            logger.error("Exception while validating runtime variables: " + e.getMessage(), e);
            valid = false;
        }

        return valid;
    }

    private static Integer getIntegerValue(Map<String, OutputVariable<?>> map, RuntimeVariable variable) {
        OutputVariable<?> out = map.get(variable.toString());
        if (out != null) {
            Object val = out.getValue();
            if (val instanceof Integer) {
                return (Integer) val;
            } else if (val instanceof Number) {
                return ((Number) val).intValue();
            }
        }
        return null;
    }

    private static Double getDoubleValue(Map<String, OutputVariable<?>> map, RuntimeVariable variable) {
        OutputVariable<?> out = map.get(variable.toString());
        if (out != null) {
             Object val = out.getValue();
            if (val instanceof Double) {
                return (Double) val;
            } else if (val instanceof Number) {
                return ((Number) val).doubleValue();
            }
        }
        return null;
    }
}
