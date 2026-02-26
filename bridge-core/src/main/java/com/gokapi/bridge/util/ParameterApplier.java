package com.gokapi.bridge.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.sf.okapi.common.IParameters;
import net.sf.okapi.common.StringParameters;
import net.sf.okapi.common.filters.InlineCodeFinder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Applies filter parameters from JSON to Okapi IParameters objects.
 * 
 * This class handles:
 * 1. Applying simple parameters via setString/setBoolean/setInteger
 * 2. Applying complex parameters like InlineCodeFinder
 * 3. Using reflection for parameters with dedicated setters
 */
public class ParameterApplier {

    /**
     * Apply parameters from JSON to an IParameters object.
     * 
     * @param params The Okapi IParameters object to configure
     * @param filterParams JSON object containing parameter values
     * @return true if all parameters were applied successfully
     */
    public static boolean applyParameters(IParameters params, JsonObject filterParams) {
        if (params == null || filterParams == null) {
            return false;
        }

        boolean allSuccess = true;
        
        for (Map.Entry<String, JsonElement> entry : filterParams.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            
            try {
                boolean applied = applyParameter(params, key, value);
                if (!applied) {
                    System.err.println("[bridge] Warning: Could not apply parameter: " + key);
                    allSuccess = false;
                }
            } catch (Exception e) {
                System.err.println("[bridge] Error applying parameter " + key + ": " + e.getMessage());
                allSuccess = false;
            }
        }
        
        return allSuccess;
    }

    /**
     * Apply a single parameter to an IParameters object.
     */
    private static boolean applyParameter(IParameters params, String key, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return true; // Null values are OK, just skip
        }

        // Handle codeFinderRules specially
        if ("codeFinderRules".equals(key)) {
            return applyCodeFinderRules(params, value);
        }

        // Handle primitive types via IParameters interface
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isBoolean()) {
                params.setBoolean(key, value.getAsBoolean());
                return true;
            } else if (value.getAsJsonPrimitive().isNumber()) {
                // Try integer first, then fall back to setting as string
                try {
                    params.setInteger(key, value.getAsInt());
                    return true;
                } catch (Exception e) {
                    // Some parameters might be stored as strings even if they look like numbers
                    params.setString(key, value.getAsString());
                    return true;
                }
            } else {
                params.setString(key, value.getAsString());
                return true;
            }
        }

        // For complex objects, try reflection to find a setter
        return applyViaReflection(params, key, value);
    }

    /**
     * Apply codeFinderRules to the filter parameters.
     */
    private static boolean applyCodeFinderRules(IParameters params, JsonElement value) {
        String okapiFormat;
        
        if (value.isJsonObject()) {
            // Convert from clean JSON to Okapi format
            okapiFormat = ParameterConverter.convertCodeFinderRules(value.getAsJsonObject());
        } else if (value.isJsonPrimitive()) {
            // Already in Okapi format (or a simple string)
            okapiFormat = value.getAsString();
        } else {
            return false;
        }

        // Try to set via setCodeFinderData method (used by JSON filter)
        try {
            Method setter = params.getClass().getMethod("setCodeFinderData", String.class);
            setter.invoke(params, okapiFormat);
            return true;
        } catch (NoSuchMethodException e) {
            // Try direct field access
        } catch (Exception e) {
            System.err.println("[bridge] Error setting codeFinderData: " + e.getMessage());
        }

        // Try to access the codeFinder field directly
        try {
            Field codeFinderField = findField(params.getClass(), "codeFinder");
            if (codeFinderField != null) {
                codeFinderField.setAccessible(true);
                InlineCodeFinder codeFinder = (InlineCodeFinder) codeFinderField.get(params);
                if (codeFinder != null) {
                    codeFinder.fromString(okapiFormat);
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("[bridge] Error accessing codeFinder field: " + e.getMessage());
        }

        // Fall back to setting as a string parameter
        if (params instanceof StringParameters) {
            ((StringParameters) params).setString("codeFinderRules", okapiFormat);
            return true;
        }

        return false;
    }

    /**
     * Try to apply a parameter value using reflection.
     */
    private static boolean applyViaReflection(IParameters params, String key, JsonElement value) {
        // Build setter name
        String setterName = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        
        Class<?> paramsClass = params.getClass();
        
        // Try to find a matching setter
        for (Method method : paramsClass.getMethods()) {
            if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                Class<?> paramType = method.getParameterTypes()[0];
                
                try {
                    Object convertedValue = convertToType(value, paramType);
                    if (convertedValue != null) {
                        method.invoke(params, convertedValue);
                        return true;
                    }
                } catch (Exception e) {
                    // Try next method signature
                }
            }
        }
        
        return false;
    }

    /**
     * Convert a JSON element to the specified Java type.
     */
    private static Object convertToType(JsonElement value, Class<?> targetType) {
        if (value.isJsonPrimitive()) {
            if (targetType == boolean.class || targetType == Boolean.class) {
                return value.getAsBoolean();
            } else if (targetType == int.class || targetType == Integer.class) {
                return value.getAsInt();
            } else if (targetType == long.class || targetType == Long.class) {
                return value.getAsLong();
            } else if (targetType == double.class || targetType == Double.class) {
                return value.getAsDouble();
            } else if (targetType == float.class || targetType == Float.class) {
                return value.getAsFloat();
            } else if (targetType == String.class) {
                return value.getAsString();
            }
        }
        
        return null;
    }

    /**
     * Find a field in a class or its superclasses.
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
