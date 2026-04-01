package neokapi.bridge.util;

import net.sf.okapi.common.pipeline.BasePipelineStep;

import java.io.*;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Registry of Okapi pipeline steps.
 * Dynamically discovers steps by scanning okapi-step-* JARs on the classpath
 * (and the shaded uber-JAR) for classes extending BasePipelineStep.
 */
public class StepRegistry {

    private static final Map<String, StepInfo> STEPS = new LinkedHashMap<>();
    private static boolean initialized = false;

    /**
     * Discover all steps by scanning the classpath for okapi-step-* JARs.
     */
    private static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        Set<String> stepClasses = new TreeSet<>();

        // Scan okapi-step-* and okapi-lib JARs from the classloader.
        ClassLoader cl = StepRegistry.class.getClassLoader();
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader) cl).getURLs()) {
                String path = url.getPath();
                if ((path.contains("okapi-step-") || path.contains("okapi-lib")) && path.endsWith(".jar")) {
                    scanJarForSteps(path, stepClasses);
                }
            }
        }

        // Also check system classpath (for standalone Java runs).
        String classpath = System.getProperty("java.class.path");
        if (classpath != null) {
            for (String path : classpath.split(File.pathSeparator)) {
                if ((path.contains("okapi-step-") || path.contains("okapi-lib")) && path.endsWith(".jar")) {
                    scanJarForSteps(path, stepClasses);
                }
            }
        }

        // Fallback for shaded/uber JARs: scan the JAR containing this class.
        if (stepClasses.isEmpty()) {
            try {
                java.security.CodeSource cs = StepRegistry.class.getProtectionDomain().getCodeSource();
                if (cs != null) {
                    File jarFile = new File(cs.getLocation().toURI());
                    if (jarFile.isFile() && jarFile.getName().endsWith(".jar")) {
                        scanJarForSteps(jarFile.getPath(), stepClasses);
                    }
                }
            } catch (Exception e) {
                System.err.println("[bridge] Could not scan uber JAR for steps: " + e.getMessage());
            }
        }

        // Check availability and create StepInfo for each.
        for (String stepClass : stepClasses) {
            StepInfo info = createStepInfo(stepClass);
            if (info != null) {
                STEPS.put(stepClass, info);
            }
        }

        System.err.println("[bridge] Discovered " + STEPS.size() + " available steps from Okapi");
    }

    /**
     * Scan a JAR file for classes that look like Okapi pipeline steps.
     * Steps are classes ending in "Step" in the net.sf.okapi.steps package.
     */
    private static void scanJarForSteps(String jarPath, Set<String> stepClasses) {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // Look for Step classes in net/sf/okapi/steps (not inner classes)
                if (name.startsWith("net/sf/okapi/steps/")
                        && name.endsWith("Step.class")
                        && !name.contains("$")) {
                    String className = name.replace('/', '.').replace(".class", "");
                    stepClasses.add(className);
                }
            }
        } catch (Exception e) {
            System.err.println("[bridge] Could not scan JAR " + jarPath + ": " + e.getMessage());
        }
    }

    /**
     * Create StepInfo by instantiating the step and extracting metadata.
     */
    private static StepInfo createStepInfo(String stepClass) {
        try {
            Class<?> clazz = Class.forName(stepClass);

            // Verify it extends BasePipelineStep.
            if (!BasePipelineStep.class.isAssignableFrom(clazz)) {
                return null;
            }

            Object instance = clazz.getDeclaredConstructor().newInstance();
            BasePipelineStep step = (BasePipelineStep) instance;

            String name = step.getName();
            String description = step.getDescription();

            // Look for @UsingParameters annotation to find the Parameters class.
            Class<?> parametersClass = null;
            try {
                // UsingParameters is in net.sf.okapi.common.UsingParameters
                Class<? extends Annotation> usingParamsAnnotation =
                        (Class<? extends Annotation>) Class.forName("net.sf.okapi.common.UsingParameters");
                Annotation annotation = clazz.getAnnotation(usingParamsAnnotation);
                if (annotation != null) {
                    // The annotation has a value() method that returns the parameters class.
                    java.lang.reflect.Method valueMethod = usingParamsAnnotation.getMethod("value");
                    parametersClass = (Class<?>) valueMethod.invoke(annotation);
                }
            } catch (ClassNotFoundException e) {
                // @UsingParameters not available in this Okapi version
            } catch (Exception e) {
                System.err.println("[bridge] Could not read @UsingParameters for " + stepClass + ": " + e.getMessage());
            }

            return new StepInfo(stepClass, name, description, parametersClass);
        } catch (Exception e) {
            System.err.println("[bridge] Could not create StepInfo for " + stepClass + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Get metadata for a step class.
     */
    public static StepInfo getStepInfo(String stepClass) {
        ensureInitialized();
        return STEPS.get(stepClass);
    }

    /**
     * Create a new instance of the specified step.
     */
    public static BasePipelineStep createStep(String stepClass) {
        try {
            Class<?> clazz = Class.forName(stepClass);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (instance instanceof BasePipelineStep) {
                return (BasePipelineStep) instance;
            }
            return null;
        } catch (Exception e) {
            System.err.println("[bridge] Failed to instantiate step " + stepClass + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * List all discovered and available steps.
     */
    public static List<StepInfo> listSteps() {
        ensureInitialized();
        return new ArrayList<>(STEPS.values());
    }

    /**
     * Get all discovered step class names.
     */
    public static Set<String> getStepClasses() {
        ensureInitialized();
        return new LinkedHashSet<>(STEPS.keySet());
    }
}
