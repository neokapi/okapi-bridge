package neokapi.bridge.util;

/**
 * Metadata about a discovered Okapi pipeline step.
 */
public class StepInfo {
    private final String className;
    private final String name;
    private final String description;
    private final Class<?> parametersClass; // from @UsingParameters

    public StepInfo(String className, String name, String description, Class<?> parametersClass) {
        this.className = className;
        this.name = name;
        this.description = description;
        this.parametersClass = parametersClass;
    }

    public String getClassName() {
        return className;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Class<?> getParametersClass() {
        return parametersClass;
    }

    /**
     * Get the step ID derived from the class name.
     * E.g., "net.sf.okapi.steps.searchandreplace.SearchAndReplaceStep" -> "search-and-replace"
     */
    public String getStepId() {
        return deriveStepId();
    }

    /**
     * Derive a step ID from the class name.
     * E.g., "SearchAndReplaceStep" -> "search-and-replace"
     */
    public String deriveStepId() {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        if (simpleName.endsWith("Step")) {
            simpleName = simpleName.substring(0, simpleName.length() - 4);
        }
        // Convert CamelCase to kebab-case
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
