package com.gokapi.bridge.util;

import com.gokapi.bridge.model.FilterConfigurationInfo;
import com.gokapi.bridge.model.FilterInfo;
import net.sf.okapi.common.filters.FilterConfiguration;
import net.sf.okapi.common.filters.IFilter;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Registry of Okapi filters.
 * Dynamically discovers filters by scanning okapi-filter-* JARs on the classpath
 * for classes ending in "Filter" that implement IFilter.

/**
 * Registry of Okapi filters.
 * Dynamically discovers filters by scanning okapi-filter-* JARs on the classpath
 * for classes ending in "Filter" that implement IFilter.
 */
public class FilterRegistry {

    private static final Map<String, FilterInfo> FILTERS = new LinkedHashMap<>();
    private static boolean initialized = false;

    /**
     * Discover all filters by scanning the classpath for okapi-filter-* JARs.
     * This approach requires no hardcoded filter lists - any filter JAR on the
     * classpath will be automatically discovered.
     */
    private static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        Set<String> filterClasses = new TreeSet<>();

        // Scan okapi-filter-* JARs from the classloader
        // Maven exec:java uses URLClassLoader, not system classpath
        ClassLoader cl = FilterRegistry.class.getClassLoader();
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader) cl).getURLs()) {
                String path = url.getPath();
                if (path.contains("okapi-filter-") && path.endsWith(".jar")) {
                    scanJarForFilters(path, filterClasses);
                }
            }
        }

        // Also check system classpath (for standalone Java runs)
        String classpath = System.getProperty("java.class.path");
        if (classpath != null) {
            for (String path : classpath.split(File.pathSeparator)) {
                if (path.contains("okapi-filter-") && path.endsWith(".jar")) {
                    scanJarForFilters(path, filterClasses);
                }
            }
        }

        // Fallback for shaded/uber JARs: scan the JAR containing this class.
        // In a shaded JAR, all okapi-filter classes are merged into the single
        // uber JAR, so the okapi-filter-* filename scan above finds nothing.
        if (filterClasses.isEmpty()) {
            try {
                java.security.CodeSource cs = FilterRegistry.class.getProtectionDomain().getCodeSource();
                if (cs != null) {
                    File jarFile = new File(cs.getLocation().toURI());
                    if (jarFile.isFile() && jarFile.getName().endsWith(".jar")) {
                        scanJarForFilters(jarFile.getPath(), filterClasses);
                    }
                }
            } catch (Exception e) {
                System.err.println("[bridge] Could not scan uber JAR for filters: " + e.getMessage());
            }
        }

        // Check availability and create FilterInfo for each
        for (String filterClass : filterClasses) {
            FilterInfo info = createFilterInfo(filterClass);
            if (info != null) {
                FILTERS.put(filterClass, info);
            }
        }

        System.err.println("[bridge] Discovered " + FILTERS.size() + " available filters from Okapi");
    }

    /**
     * Scan a JAR file for classes that look like Okapi filters.
     * Filters are classes ending in "Filter" in the net.sf.okapi.filters package.
     */
    private static void scanJarForFilters(String jarPath, Set<String> filterClasses) {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // Look for Filter classes in net/sf/okapi/filters (not inner classes)
                if (name.startsWith("net/sf/okapi/filters/") 
                        && name.endsWith("Filter.class") 
                        && !name.contains("$")) {
                    String className = name.replace('/', '.').replace(".class", "");
                    filterClasses.add(className);
                }
            }
        } catch (Exception e) {
            System.err.println("[bridge] Could not scan JAR " + jarPath + ": " + e.getMessage());
        }
    }

    /**
     * Create FilterInfo by instantiating the filter and extracting metadata.
     */
    private static FilterInfo createFilterInfo(String filterClass) {
        try {
            Class<?> clazz = Class.forName(filterClass);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof IFilter)) {
                return null;
            }

            IFilter filter = (IFilter) instance;
            String name = filter.getName();
            String displayName = filter.getDisplayName();

            // Derive format ID from class name (e.g., "HTMLFilter" -> "html")
            String formatId = deriveFormatId(clazz.getSimpleName());

            FilterInfo info = new FilterInfo(
                    filterClass,
                    formatId,
                    displayName != null ? displayName : name,
                    Collections.emptyList(),
                    Collections.emptyList()
            );

            // Extract filter configurations (presets/variants)
            // For compound filters, track which sibling filter handles each configuration
            Map<String, String> configToFilterClass = new HashMap<>();
            Map<String, String> configToSchemaRef = new HashMap<>();
            
            // Check if filter is a compound filter (may not exist in older Okapi versions)
            if (isCompoundFilter(filter)) {
                populateCompoundFilterConfigMappings(filter, configToFilterClass, configToSchemaRef);
            }
            
            List<FilterConfiguration> configs = filter.getConfigurations();
            if (configs != null && !configs.isEmpty()) {
                boolean firstConfig = true;
                for (FilterConfiguration config : configs) {
                    FilterConfigurationInfo configInfo = new FilterConfigurationInfo(
                            config.configId,
                            config.name,
                            config.description,
                            config.mimeType,
                            config.extensions,
                            config.parametersLocation,
                            firstConfig
                    );
                    
                    // Set the filter class that handles this configuration
                    String handlerClass = configToFilterClass.get(config.configId);
                    if (handlerClass != null) {
                        configInfo.setFilterClass(handlerClass);
                        configInfo.setSchemaRef(configToSchemaRef.get(config.configId));
                    } else {
                        // Regular filter - configuration is handled by the filter itself
                        configInfo.setFilterClass(filterClass);
                    }
                    
                    // Load parameters from file if available
                    if (config.parametersLocation != null && !config.parametersLocation.isEmpty()) {
                        loadParametersForConfig(filter, config.parametersLocation, configInfo);
                    }
                    
                    info.addConfiguration(configInfo);
                    firstConfig = false;
                }
            }

            // Aggregate unique MIME types and extensions from configurations
            Set<String> allMimeTypes = new LinkedHashSet<>();
            Set<String> allExtensions = new LinkedHashSet<>();
            for (FilterConfigurationInfo ci : info.getConfigurations()) {
                if (ci.getMimeType() != null && !ci.getMimeType().isEmpty()) {
                    allMimeTypes.add(ci.getMimeType());
                }
                if (ci.getExtensions() != null && !ci.getExtensions().isEmpty()) {
                    for (String ext : ci.getExtensions().split(";")) {
                        ext = ext.trim();
                        if (!ext.isEmpty()) {
                            allExtensions.add(ext);
                        }
                    }
                }
            }
            info.setMimeTypes(new ArrayList<>(allMimeTypes));
            info.setExtensions(new ArrayList<>(allExtensions));

            return info;
        } catch (Exception e) {
            System.err.println("[bridge] Could not create FilterInfo for " + filterClass + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if a filter is a compound filter (AbstractCompoundFilter may not exist in older Okapi versions).
     */
    private static boolean isCompoundFilter(IFilter filter) {
        try {
            Class<?> compoundClass = Class.forName("net.sf.okapi.common.filters.AbstractCompoundFilter");
            return compoundClass.isInstance(filter);
        } catch (ClassNotFoundException e) {
            // AbstractCompoundFilter doesn't exist in this Okapi version
            return false;
        }
    }
    
    /**
     * For compound filters, build a mapping from configId to the sibling filter that handles it.
     * This allows configuration screens to know which schema to use for each configuration.
     */
    private static void populateCompoundFilterConfigMappings(IFilter compoundFilter,
                                                              Map<String, String> configToFilterClass,
                                                              Map<String, String> configToSchemaRef) {
        try {
            Class<?> compoundClass = Class.forName("net.sf.okapi.common.filters.AbstractCompoundFilter");
            
            // Try getting the siblingFilters field directly
            java.lang.reflect.Field siblingField = null;
            try {
                siblingField = compoundClass.getDeclaredField("siblingFilters");
                siblingField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                // Field might have different name
                return;
            }
            
            LinkedList<?> siblings = (LinkedList<?>) siblingField.get(compoundFilter);
            
            if (siblings == null || siblings.isEmpty()) {
                return;
            }
            
            // Each sibling filter contributes its configurations
            for (Object siblingObj : siblings) {
                if (!(siblingObj instanceof IFilter)) continue;
                IFilter sibling = (IFilter) siblingObj;
                
                String siblingClass = sibling.getClass().getName();
                String siblingFormatId = deriveFormatId(sibling.getClass().getSimpleName());
                String schemaRef = "okf_" + siblingFormatId + ".schema.json";
                
                List<FilterConfiguration> siblingConfigs = sibling.getConfigurations();
                if (siblingConfigs != null) {
                    for (FilterConfiguration config : siblingConfigs) {
                        configToFilterClass.put(config.configId, siblingClass);
                        configToSchemaRef.put(config.configId, schemaRef);
                    }
                }
            }
        } catch (Exception e) {
            // Reflection failed - compound filter mappings won't be available
            System.err.println("[bridge] Could not extract sibling filters: " + e.getMessage());
        }
    }

    /**
     * Load parameters from a classpath resource file (.yml or .fprm format).
     * Tries multiple resolution strategies since parameter files may be in parent packages.
     */
    private static void loadParametersForConfig(IFilter filter, String parametersLocation, 
                                                 FilterConfigurationInfo configInfo) {
        try {
            InputStream is = null;
            
            // Strategy 1: Try relative to filter class (works for most filters)
            is = filter.getClass().getResourceAsStream(parametersLocation);
            
            // Strategy 2: Try from classloader root with absolute path
            if (is == null) {
                is = filter.getClass().getClassLoader().getResourceAsStream(parametersLocation);
            }
            
            // Strategy 3: Try to find in filter's package hierarchy
            // Some filters are in subpackages (e.g., plaintext.base) but reference files
            // in parent package (plaintext)
            if (is == null) {
                String packagePath = filter.getClass().getPackage().getName().replace('.', '/');
                // Try parent packages
                while (is == null && packagePath.contains("/")) {
                    packagePath = packagePath.substring(0, packagePath.lastIndexOf('/'));
                    String fullPath = packagePath + "/" + parametersLocation;
                    is = filter.getClass().getClassLoader().getResourceAsStream(fullPath);
                }
            }
            
            // Strategy 4: Search common filter resource locations
            if (is == null) {
                // Try net/sf/okapi/filters/{filtertype}/ paths
                String filterType = extractFilterType(filter.getClass().getName());
                if (filterType != null) {
                    String fullPath = "net/sf/okapi/filters/" + filterType + "/" + parametersLocation;
                    is = filter.getClass().getClassLoader().getResourceAsStream(fullPath);
                }
            }
            
            // Strategy 5: Try plaintext package for configs that reference okf_plaintext_*
            if (is == null && parametersLocation.startsWith("okf_plaintext")) {
                String fullPath = "net/sf/okapi/filters/plaintext/" + parametersLocation;
                is = filter.getClass().getClassLoader().getResourceAsStream(fullPath);
            }
            
            // Strategy 6: Try table package for configs that reference okf_table_*
            if (is == null && parametersLocation.startsWith("okf_table")) {
                String fullPath = "net/sf/okapi/filters/table/" + parametersLocation;
                is = filter.getClass().getClassLoader().getResourceAsStream(fullPath);
            }
            
            if (is == null) {
                return;
            }
            
            // Read the raw content
            String raw;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                raw = sb.toString();
            }
            
            configInfo.setParametersRaw(raw);
            
            // Parse based on file extension
            if (parametersLocation.endsWith(".yml") || parametersLocation.endsWith(".yaml")) {
                // Parse YAML with YAML 1.2 boolean rules (yes/no are strings, not booleans)
                Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.Constructor(
                        new org.yaml.snakeyaml.LoaderOptions()),
                    new org.yaml.snakeyaml.representer.Representer(
                        new org.yaml.snakeyaml.DumperOptions()),
                    new org.yaml.snakeyaml.DumperOptions(),
                    new Yaml12BoolResolver());
                Object parsed = yaml.load(raw);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = (Map<String, Object>) parsed;
                    configInfo.setParameters(params);
                }
            } else if (parametersLocation.endsWith(".fprm")) {
                // Parse .fprm (properties-like format)
                Map<String, Object> params = parseFprmFormat(raw);
                if (!params.isEmpty()) {
                    configInfo.setParameters(params);
                }
            }
        } catch (Exception e) {
            // Log but don't fail - parameters are optional enhancement
            System.err.println("[bridge] Could not load parameters from " + parametersLocation + ": " + e.getMessage());
        }
    }
    
    /**
     * Parse Okapi .fprm format (first line is parametersClass=..., rest are key=value).
     */
    private static Map<String, Object> parseFprmFormat(String content) {
        Map<String, Object> params = new LinkedHashMap<>();
        String[] lines = content.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                
                // Skip the parametersClass line - it's metadata, not a parameter
                if ("parametersClass".equals(key)) {
                    params.put("_parametersClass", value);
                    continue;
                }
                
                // Try to parse as boolean/number
                // Strip .fprm type suffixes (.b, .i) - type is inferred from value
                String cleanKey = key.replaceAll("\\.[bi]$", "");
                params.put(cleanKey, parseValue(value));
            }
        }
        return params;
    }
    
    /**
     * Parse a string value into appropriate type (boolean, int, double, or string).
     */
    private static Object parseValue(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e1) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e2) {
                return value;
            }
        }
    }
    
    /**
     * Extract filter type from class name (e.g., "plaintext" from 
     * "net.sf.okapi.filters.plaintext.base.BasePlainTextFilter").
     */
    private static String extractFilterType(String className) {
        // Pattern: net.sf.okapi.filters.{filtertype}...
        String prefix = "net.sf.okapi.filters.";
        if (!className.startsWith(prefix)) {
            return null;
        }
        String rest = className.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot > 0 ? rest.substring(0, dot) : rest;
    }

    /**
     * Derive a format ID from the filter class simple name.
     * E.g., "HTMLFilter" -> "html", "OpenXMLFilter" -> "openxml"
     */
    private static String deriveFormatId(String simpleName) {
        String id = simpleName;
        if (id.endsWith("Filter")) {
            id = id.substring(0, id.length() - 6);
        }
        return id.toLowerCase();
    }

    /**
     * Get metadata for a filter class.
     *
     * @param filterClass fully-qualified Java class name
     * @return FilterInfo or null if not found
     */
    public static FilterInfo getFilterInfo(String filterClass) {
        ensureInitialized();
        return FILTERS.get(filterClass);
    }

    /**
     * Create a new instance of the specified filter.
     *
     * @param filterClass fully-qualified Java class name
     * @return new IFilter instance or null
     */
    public static IFilter createFilter(String filterClass) {
        ensureInitialized();
        try {
            Class<?> clazz = Class.forName(filterClass);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (instance instanceof IFilter) {
                return (IFilter) instance;
            }
            return null;
        } catch (Exception e) {
            System.err.println("[bridge] Failed to instantiate filter " + filterClass + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * List all discovered and available filters.
     */
    public static List<FilterInfo> listFilters() {
        ensureInitialized();
        return new ArrayList<>(FILTERS.values());
    }

    /**
     * Get all discovered filter class names.
     */
    public static Set<String> getFilterClasses() {
        ensureInitialized();
        return new LinkedHashSet<>(FILTERS.keySet());
    }

    /**
     * Custom SnakeYAML Resolver that uses YAML 1.2 boolean rules.
     * In YAML 1.2 only "true" and "false" are booleans, not "yes"/"no"/"on"/"off".
     */
    private static class Yaml12BoolResolver extends Resolver {
        @Override
        public org.yaml.snakeyaml.nodes.Tag resolve(org.yaml.snakeyaml.nodes.NodeId kind,
                String value, boolean implicit) {
            if (implicit && kind == org.yaml.snakeyaml.nodes.NodeId.scalar && value != null) {
                String lower = value.toLowerCase();
                if (lower.equals("yes") || lower.equals("no") ||
                    lower.equals("on") || lower.equals("off") ||
                    lower.equals("y") || lower.equals("n")) {
                    return org.yaml.snakeyaml.nodes.Tag.STR;
                }
            }
            return super.resolve(kind, value, implicit);
        }
    }
}
