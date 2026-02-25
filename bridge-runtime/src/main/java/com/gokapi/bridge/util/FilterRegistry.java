package com.gokapi.bridge.util;

import com.gokapi.bridge.model.FilterInfo;
import net.sf.okapi.common.filters.IFilter;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
            String mimeType = filter.getMimeType();

            // Derive format ID from class name (e.g., "HTMLFilter" -> "html")
            String formatId = deriveFormatId(clazz.getSimpleName());

            List<String> mimeTypes = mimeType != null && !mimeType.isEmpty()
                    ? Collections.singletonList(mimeType)
                    : Collections.emptyList();

            return new FilterInfo(
                    filterClass,
                    formatId,
                    displayName != null ? displayName : name,
                    mimeTypes,
                    Collections.emptyList() // Extensions will be empty - not critical for schema generation
            );
        } catch (Exception e) {
            System.err.println("[bridge] Could not create FilterInfo for " + filterClass + ": " + e.getMessage());
            return null;
        }
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
}
