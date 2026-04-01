package neokapi.bridge.util;

/**
 * Metadata about a discovered Okapi pipeline step.
 * Serialized to JSON via Gson for --list-capabilities output.
 * Uses pure Okapi vocabulary — neokapi-specific metadata (category, tags, etc.)
 * is added by the transformation script from tool-metadata.json.
 */
public class StepInfo {
    private final String className;
    private final String name;
    private final String description;
    private final transient Class<?> parametersClass; // from @UsingParameters, excluded from Gson
    private final String parametersClassName;
    private final String stepId;

    public StepInfo(String className, String name, String description, Class<?> parametersClass) {
        this.className = className;
        this.name = name;
        this.description = description;
        this.parametersClass = parametersClass;
        this.parametersClassName = parametersClass != null ? parametersClass.getName() : null;
        this.stepId = deriveStepId();
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

    public String getParametersClassName() {
        return parametersClassName;
    }

    /**
     * Get the step ID derived from the class name.
     * E.g., "net.sf.okapi.steps.searchandreplace.SearchAndReplaceStep" -> "search-and-replace"
     */
    public String getStepId() {
        return stepId;
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
