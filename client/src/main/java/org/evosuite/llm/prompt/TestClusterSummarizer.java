package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces concise prompt context from the current test cluster.
 */
public class TestClusterSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(TestClusterSummarizer.class);

    public String summarize(TestCluster cluster) {
        if (cluster == null) {
            return "No test cluster available.";
        }

        Set<Class<?>> classes = collectAvailableClasses(cluster);
        StringBuilder builder = new StringBuilder();
        builder.append("Available classes:").append(System.lineSeparator());
        int shown = 0;
        for (Class<?> clazz : classes) {
            builder.append("- ").append(clazz.getName()).append(System.lineSeparator());
            shown++;
            if (shown >= 20) {
                builder.append("- ... (truncated)").append(System.lineSeparator());
                break;
            }
        }
        if (shown == 0) {
            builder.append("- (none discovered)").append(System.lineSeparator());
        }
        return builder.toString();
    }

    public String summarizeClass(GenericClass<?> clazz) {
        if (clazz == null || clazz.getRawClass() == null) {
            return "Unknown class";
        }

        Class<?> raw = clazz.getRawClass();
        StringBuilder builder = new StringBuilder();
        builder.append("Class: ").append(raw.getName()).append(System.lineSeparator());

        builder.append("Constructors:").append(System.lineSeparator());
        for (Constructor<?> constructor : raw.getConstructors()) {
            builder.append("  ").append(constructor.getName())
                    .append('(').append(parameterList(constructor.getParameterTypes())).append(')')
                    .append(System.lineSeparator());
        }

        builder.append("Public methods:").append(System.lineSeparator());
        for (Method method : raw.getMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }
            builder.append("  ").append(method.getReturnType().getSimpleName()).append(' ')
                    .append(method.getName())
                    .append('(').append(parameterList(method.getParameterTypes())).append(')')
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    public String summarizeGenerators(GenericClass<?> type) {
        if (type == null || type.getRawClass() == null) {
            return "";
        }
        Class<?> raw = type.getRawClass();
        List<String> lines = new ArrayList<>();
        for (Constructor<?> constructor : raw.getConstructors()) {
            lines.add(raw.getSimpleName() + "(" + parameterList(constructor.getParameterTypes()) + ")");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String parameterList(Class<?>[] parameterTypes) {
        List<String> names = new ArrayList<>();
        for (Class<?> parameterType : parameterTypes) {
            names.add(parameterType.getSimpleName());
        }
        return String.join(", ", names);
    }

    private Set<Class<?>> collectAvailableClasses(TestCluster cluster) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        try {
            classes.addAll(cluster.getAnalyzedClasses());
        } catch (Exception e) {
            logger.debug("Failed to collect analyzed classes from test cluster", e);
        }
        addFromCalls(classes, cluster.getGenerators());
        addFromCalls(classes, cluster.getModifiers());
        addFromCalls(classes, cluster.getTestCalls());
        return classes;
    }

    private void addFromCalls(Set<Class<?>> classes, Collection<GenericAccessibleObject<?>> calls) {
        if (calls == null) {
            return;
        }
        for (GenericAccessibleObject<?> call : calls) {
            if (call == null) {
                continue;
            }
            try {
                GenericClass<?> owner = call.getOwnerClass();
                if (owner != null && owner.getRawClass() != null) {
                    classes.add(owner.getRawClass());
                }
            } catch (Exception e) {
                logger.debug("Failed to collect owner class from cluster call {}", call, e);
            }
            try {
                GenericClass<?> generated = call.getGeneratedClass();
                if (generated != null && generated.getRawClass() != null) {
                    classes.add(generated.getRawClass());
                }
            } catch (Exception e) {
                logger.debug("Failed to collect generated class from cluster call {}", call, e);
            }
        }
    }
}
