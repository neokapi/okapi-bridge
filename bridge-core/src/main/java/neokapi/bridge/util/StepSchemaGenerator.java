package neokapi.bridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.sf.okapi.common.IParameters;
import net.sf.okapi.common.ParametersDescription;
import net.sf.okapi.common.pipeline.BasePipelineStep;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates JSON Schema from Okapi step parameters.
 * Introspects the step's @UsingParameters annotation, instantiates the
 * Parameters class, and parses the serialized ParametersString (#v1 format)
 * to discover parameter names and types.
 *
 * Also extracts step metadata via reflection:
 * - @StepParameterMapping annotations (parameter type mappings)
 * - Event handler methods (overridden handle* methods)
 * - Marker interfaces (e.g., ILoadsResources)
 * - I/O classification based on parameter mappings
 */
public class StepSchemaGenerator {

    /** Marker interfaces to check for on step classes. */
    private static final String[] MARKER_INTERFACES = {
            "net.sf.okapi.common.ILoadsResources"
    };

    /**
     * Generate a ComponentSchema-compatible JSON object for a step.
     *
     * @param info the StepInfo with step metadata
     * @return JSON Schema object, or null if schema cannot be generated
     */
    public static JsonObject generateSchema(StepInfo info) {
        if (info == null) {
            return null;
        }

        String stepId = info.deriveStepId();
        String displayName = info.getName() != null ? info.getName() : info.deriveStepId();

        JsonObject schema = new JsonObject();
        schema.addProperty("$id", stepId);
        schema.addProperty("title", displayName);
        schema.addProperty("type", "object");

        // x-component metadata
        JsonObject xComponent = new JsonObject();
        xComponent.addProperty("id", stepId);
        xComponent.addProperty("type", "step");
        xComponent.addProperty("displayName", displayName);
        if (info.getDescription() != null && !info.getDescription().isEmpty()) {
            xComponent.addProperty("description", info.getDescription());
        }
        schema.add("x-component", xComponent);

        // x-step metadata (parameter mappings, event handlers, interfaces, I/O classification)
        JsonObject xStep = generateStepMetadata(info);
        if (xStep != null) {
            schema.add("x-step", xStep);
        }

        // Generate properties from the Parameters class.
        JsonObject properties = generateProperties(info);
        if (properties != null && properties.size() > 0) {
            schema.add("properties", properties);
        }

        return schema;
    }

    /**
     * Generate x-step metadata by introspecting the step class via reflection.
     */
    private static JsonObject generateStepMetadata(StepInfo info) {
        JsonObject xStep = new JsonObject();
        xStep.addProperty("class", info.getClassName());

        try {
            Class<?> stepClass = Class.forName(info.getClassName());

            // Extract @StepParameterMapping annotations
            List<String> parameterMappings = extractParameterMappings(stepClass);
            JsonArray mappingsArray = new JsonArray();
            for (String mapping : parameterMappings) {
                mappingsArray.add(mapping);
            }
            xStep.add("parameterMappings", mappingsArray);

            // Extract overridden event handlers
            List<String> eventHandlers = extractEventHandlers(stepClass);
            JsonArray handlersArray = new JsonArray();
            for (String handler : eventHandlers) {
                handlersArray.add(handler);
            }
            xStep.add("eventHandlers", handlersArray);

            // Check marker interfaces
            List<String> interfaces = extractMarkerInterfaces(stepClass);
            JsonArray interfacesArray = new JsonArray();
            for (String iface : interfaces) {
                interfacesArray.add(iface);
            }
            xStep.add("interfaces", interfacesArray);

            // Classify I/O based on parameter mappings and event handlers
            classifyIO(xStep, parameterMappings, eventHandlers);

        } catch (ClassNotFoundException e) {
            System.err.println("[bridge] Could not load step class for metadata: " + info.getClassName());
        }

        return xStep;
    }

    /**
     * Extract @StepParameterMapping annotations from the step class and its hierarchy.
     * Uses reflection to avoid compile-time dependency on the annotation class.
     */
    private static List<String> extractParameterMappings(Class<?> stepClass) {
        List<String> mappings = new ArrayList<>();

        try {
            Class<? extends Annotation> spmAnnotation =
                    (Class<? extends Annotation>) Class.forName(
                            "net.sf.okapi.common.pipeline.annotations.StepParameterMapping");
            Method parameterTypeMethod = spmAnnotation.getMethod("parameterType");

            // Check all public methods (includes inherited) for the annotation
            for (Method method : stepClass.getMethods()) {
                Annotation ann = method.getAnnotation(spmAnnotation);
                if (ann != null) {
                    Object paramType = parameterTypeMethod.invoke(ann);
                    String typeName = ((Enum<?>) paramType).name();
                    if (!mappings.contains(typeName)) {
                        mappings.add(typeName);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            // @StepParameterMapping not available in this Okapi version
        } catch (Exception e) {
            System.err.println("[bridge] Error extracting parameter mappings from "
                    + stepClass.getName() + ": " + e.getMessage());
        }

        Collections.sort(mappings);
        return mappings;
    }

    /**
     * Extract overridden event handler methods.
     * Checks step.getClass().getDeclaredMethods() for methods starting with "handle"
     * that override the no-op implementations in BasePipelineStep.
     */
    private static List<String> extractEventHandlers(Class<?> stepClass) {
        List<String> handlers = new ArrayList<>();

        for (Method method : stepClass.getDeclaredMethods()) {
            String name = method.getName();
            if (name.startsWith("handle") && !name.equals("handleEvent")) {
                handlers.add(name);
            }
        }

        Collections.sort(handlers);
        return handlers;
    }

    /**
     * Check if the step implements any notable marker interfaces.
     */
    private static List<String> extractMarkerInterfaces(Class<?> stepClass) {
        List<String> interfaces = new ArrayList<>();

        for (String ifaceName : MARKER_INTERFACES) {
            try {
                Class<?> ifaceClass = Class.forName(ifaceName);
                if (ifaceClass.isAssignableFrom(stepClass)) {
                    // Use simple name for the interface
                    interfaces.add(ifaceClass.getSimpleName());
                }
            } catch (ClassNotFoundException e) {
                // Interface not available in this Okapi version
            }
        }

        return interfaces;
    }

    /**
     * Classify step I/O based on parameter mappings and event handlers.
     * - If step has INPUT_RAWDOC mapping: inputType = "raw-document"
     * - If step only has filter event handlers: inputType = "filter-events"
     * - If step has OUTPUT_URI mapping: outputType = "file"
     * - Otherwise: outputType = "filter-events"
     */
    private static void classifyIO(JsonObject xStep, List<String> mappings, List<String> handlers) {
        // Input classification
        if (mappings.contains("INPUT_RAWDOC")) {
            xStep.addProperty("inputType", "raw-document");
        } else {
            xStep.addProperty("inputType", "filter-events");
        }

        // Output classification
        if (mappings.contains("OUTPUT_URI")) {
            xStep.addProperty("outputType", "file");
        } else {
            xStep.addProperty("outputType", "filter-events");
        }
    }

    /**
     * Generate property schemas from the step's Parameters class.
     */
    private static JsonObject generateProperties(StepInfo info) {
        if (info.getParametersClass() == null) {
            return null;
        }

        try {
            // Instantiate the parameters class.
            Object paramsObj = info.getParametersClass().getDeclaredConstructor().newInstance();
            if (!(paramsObj instanceof IParameters)) {
                return null;
            }
            IParameters params = (IParameters) paramsObj;
            params.reset();

            // Try to get ParametersDescription from the step for display names.
            ParametersDescription paramsDesc = getParametersDescription(info);

            // Get the serialized form (ParametersString #v1 format).
            String serialized = params.toString();
            if (serialized == null || serialized.trim().isEmpty()) {
                return null;
            }

            return parseParametersString(serialized, paramsDesc);
        } catch (Exception e) {
            System.err.println("[bridge] Could not generate properties for step "
                    + info.getClassName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Try to get a ParametersDescription from the step's Parameters object.
     */
    private static ParametersDescription getParametersDescription(StepInfo info) {
        if (info.getParametersClass() == null) {
            return null;
        }
        try {
            Object paramsObj = info.getParametersClass().getDeclaredConstructor().newInstance();
            if (paramsObj instanceof net.sf.okapi.common.IParameters) {
                return ((net.sf.okapi.common.IParameters) paramsObj).getParametersDescription();
            }
        } catch (Exception e) {
            // Not all parameters provide descriptions
        }
        return null;
    }

    /**
     * Parse a ParametersString (#v1 format) to discover parameter names and types.
     * The #v1 format uses lines like:
     *   paramName.b=true     (boolean)
     *   paramName.i=42       (integer)
     *   paramName=value      (string)
     */
    private static JsonObject parseParametersString(String serialized, ParametersDescription paramsDesc) {
        JsonObject properties = new JsonObject();

        String[] lines = serialized.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }

            String rawKey = line.substring(0, eq).trim();
            String rawValue = line.substring(eq + 1).trim();

            // Determine type from suffix.
            String paramName;
            String paramType;
            Object defaultValue;

            if (rawKey.endsWith(".b")) {
                paramName = rawKey.substring(0, rawKey.length() - 2);
                paramType = "boolean";
                defaultValue = Boolean.parseBoolean(rawValue);
            } else if (rawKey.endsWith(".i")) {
                paramName = rawKey.substring(0, rawKey.length() - 2);
                paramType = "integer";
                try {
                    defaultValue = Integer.parseInt(rawValue);
                } catch (NumberFormatException e) {
                    defaultValue = 0;
                }
            } else {
                paramName = rawKey;
                paramType = "string";
                defaultValue = rawValue;
            }

            JsonObject prop = new JsonObject();
            prop.addProperty("type", paramType);

            // Set default value.
            if (defaultValue instanceof Boolean) {
                prop.add("default", new JsonPrimitive((Boolean) defaultValue));
            } else if (defaultValue instanceof Integer) {
                prop.add("default", new JsonPrimitive((Integer) defaultValue));
            } else if (defaultValue instanceof String) {
                prop.add("default", new JsonPrimitive((String) defaultValue));
            }

            // Add description from ParametersDescription if available.
            if (paramsDesc != null) {
                try {
                    net.sf.okapi.common.ParameterDescriptor pd = paramsDesc.get(paramName);
                    if (pd != null) {
                        String shortDesc = pd.getShortDescription();
                        if (shortDesc != null && !shortDesc.isEmpty()) {
                            prop.addProperty("description", shortDesc);
                        }
                        String displayName = pd.getDisplayName();
                        if (displayName != null && !displayName.isEmpty()) {
                            prop.addProperty("title", displayName);
                        }
                    }
                } catch (Exception e) {
                    // ignore — param may not have a description
                }
            }

            properties.add(paramName, prop);
        }

        return properties;
    }
}
