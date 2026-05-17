package neokapi.bridge;

import neokapi.bridge.util.FilterRegistry;

import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.filters.FilterConfigurationMapper;
import net.sf.okapi.common.filters.IFilter;
import net.sf.okapi.common.pipelinedriver.PipelineDriver;
import net.sf.okapi.common.resource.RawDocument;
import net.sf.okapi.steps.common.FilterEventsToRawDocumentStep;
import net.sf.okapi.steps.common.RawDocumentToFilterEventsStep;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Implementation of the {@code pseudo} subcommand. Composes
 * {@code RawDocumentToFilterEventsStep → PseudoTranslationStep → FilterEventsToRawDocumentStep}
 * to produce a merged document where every translatable text unit's target
 * is the source with each Latin letter substituted using Okapi's
 * {@code SCRIPT_EXT_LATIN} character map (e.g. {@code A → À}, {@code e → ē}).
 *
 * <p>Inline codes are preserved — {@code PseudoTranslationStep} skips the
 * {@code TextFragment} marker chars during substitution. Ignorable TextParts
 * are left untouched (unlike upstream's {@code TextModificationStep} which
 * transforms all parts indiscriminately).
 *
 * <p>This subcommand exists so the parity round-trip harness can use
 * consistent pseudo-translate semantics as the comparator
 * across all engines (native, bridge, okapi). Previously the harness
 * shelled out to {@code tikal -x}/{@code -m} and rewrote XLIFF targets
 * by hand, which mishandled inline placeholders.
 *
 * <p>CLI:
 * <pre>
 *   kapi-okapi-bridge pseudo --filter okf_html --input in.html --output out.html
 *                            [--src-lang en --tgt-lang fr]
 *                            [--encoding UTF-8]
 *                            [--fprm &lt;content&gt;]
 *
 *   kapi-okapi-bridge pseudo --filter okf_html --manifest pairs.tsv
 *                            [--src-lang en --tgt-lang fr]
 *                            [--encoding UTF-8]
 *                            [--fprm &lt;content&gt;]
 * </pre>
 *
 * <p>The {@code --manifest} variant amortises JVM startup across many
 * fixtures: each line is {@code <input_path>\t<output_path>}; comment
 * lines starting with {@code #} and blank lines are skipped. All items
 * share the {@code --filter}, {@code --src-lang}, {@code --tgt-lang},
 * {@code --encoding}, and {@code --fprm} configured for the run.
 */
public final class PseudoCommand {

    private PseudoCommand() {}

    public static int run(String[] args) {
        String filterClass = null;
        String inputPath = null;
        String outputPath = null;
        String manifestPath = null;
        String srcLang = "en";
        String tgtLang = "fr";
        String encoding = "UTF-8";
        String fprm = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--filter":
                    filterClass = args[++i];
                    break;
                case "--input":
                    inputPath = args[++i];
                    break;
                case "--output":
                    outputPath = args[++i];
                    break;
                case "--manifest":
                    manifestPath = args[++i];
                    break;
                case "--src-lang":
                    srcLang = args[++i];
                    break;
                case "--tgt-lang":
                    tgtLang = args[++i];
                    break;
                case "--encoding":
                    encoding = args[++i];
                    break;
                case "--fprm":
                    // Raw .fprm content (same shape Okapi writes to disk for filter
                    // parameter overrides, e.g. "#v1\nmergeCaptions.b=false\n").
                    // Loaded via IParameters.fromString().
                    fprm = args[++i];
                    break;
                default:
                    System.err.println("[pseudo] unknown arg: " + a);
                    return 2;
            }
        }
        if (filterClass == null) {
            System.err.println("[pseudo] --filter is required");
            return 2;
        }

        List<IOPair> items;
        if (manifestPath != null) {
            if (inputPath != null || outputPath != null) {
                System.err.println("[pseudo] --manifest is mutually exclusive with --input/--output");
                return 2;
            }
            try {
                items = readManifest(Path.of(manifestPath));
            } catch (Exception e) {
                System.err.println("[pseudo] could not read manifest " + manifestPath + ": " + e.getMessage());
                return 2;
            }
            if (items.isEmpty()) {
                System.err.println("[pseudo] manifest " + manifestPath + " has no entries");
                return 2;
            }
        } else {
            if (inputPath == null || outputPath == null) {
                System.err.println("[pseudo] usage: pseudo --filter <class> {--input <path> --output <path> | --manifest <path>} "
                        + "[--src-lang en --tgt-lang fr] [--encoding UTF-8] [--fprm <content>]");
                return 2;
            }
            File inputFile = new File(inputPath);
            if (!inputFile.exists()) {
                System.err.println("[pseudo] input file not found: " + inputPath);
                return 2;
            }
            items = Collections.singletonList(new IOPair(inputFile.toURI(), new File(outputPath).toURI()));
        }

        try {
            runBatch(filterClass, srcLang, tgtLang, encoding, fprm, items);
            return 0;
        } catch (PseudoFailure pf) {
            System.err.println("[pseudo] " + pf.getMessage());
            if (pf.getCause() != null) {
                pf.getCause().printStackTrace(System.err);
            }
            return pf.exitCode;
        }
    }

    /**
     * Single-item convenience around {@link #runBatch}. Kept for callers
     * that have just one document to pseudo-translate.
     */
    public static void runPipeline(String filterClass,
                                   URI inputUri,
                                   URI outputUri,
                                   String srcLang,
                                   String tgtLang,
                                   String encoding,
                                   String fprm) throws PseudoFailure {
        runBatch(filterClass, srcLang, tgtLang, encoding, fprm,
                Collections.singletonList(new IOPair(inputUri, outputUri)));
    }

    /**
     * Runs the pseudo pipeline against every (input, output) pair in
     * {@code items} in a single {@link PipelineDriver#processBatch()}
     * invocation. Filter, FilterConfigurationMapper, and step graph are
     * built once and reused across all items, so this amortises driver
     * setup across the whole batch — useful when callers want to avoid
     * paying JVM startup × N (the harness ships hundreds of fixtures
     * per format).
     */
    public static void runBatch(String filterClass,
                                String srcLang,
                                String tgtLang,
                                String encoding,
                                String fprm,
                                List<IOPair> items) throws PseudoFailure {
        if (filterClass == null || filterClass.isEmpty()) {
            throw new PseudoFailure(2, "filter_class is required");
        }
        if (items == null || items.isEmpty()) {
            throw new PseudoFailure(2, "at least one input/output pair is required");
        }

        // Promote inner-XML filters to their zip-wrapping siblings when the
        // input is a container the requested filter cannot read directly
        // (e.g. okf_odf → okf_openoffice for .odt). Spares neokapi callers
        // from knowing about Okapi's package-internal filter split. We
        // sniff the first item — runBatch instantiates one filter shared
        // by all items, so the batch must be homogeneous anyway.
        File firstInput = uriToFile(items.get(0).input);
        String effectiveFilterClass = FilterRegistry.promoteFilterForInput(filterClass, firstInput);
        if (!effectiveFilterClass.equals(filterClass)) {
            System.err.println("[pseudo] promoted filter " + filterClass + " → "
                    + effectiveFilterClass + " for container input "
                    + (firstInput == null ? "<unknown>" : firstInput.getName()));
        }

        IFilter filter = FilterRegistry.createFilter(effectiveFilterClass);
        if (filter == null) {
            throw new PseudoFailure(1, "cannot instantiate filter: " + effectiveFilterClass);
        }

        if (fprm != null && !fprm.isEmpty()) {
            try {
                filter.getParameters().fromString(fprm);
            } catch (Exception e) {
                throw new PseudoFailure(1, "could not load --fprm: " + e.getMessage(), e);
            }
        }

        // Container/delegating filters (Archive, AutoXLIFF, …) need a fully-populated
        // FilterConfigurationMapper so they can resolve sub-filter ids at runtime.
        FilterConfigurationMapper mapper = new FilterConfigurationMapper();
        for (String fc : FilterRegistry.scanFilterClassNames()) {
            try {
                mapper.addConfigurations(fc);
            } catch (Exception ignored) {
                // Filters with missing optional deps are harmless to skip here.
            }
        }
        try {
            filter.setFilterConfigurationMapper(mapper);
        } catch (Exception ignored) {
            // Non-container filters may not implement the setter — that's fine.
        }

        LocaleId src = LocaleId.fromString(srcLang == null || srcLang.isEmpty() ? "en" : srcLang);
        LocaleId tgt = LocaleId.fromString(tgtLang == null || tgtLang.isEmpty() ? "fr" : tgtLang);
        String enc = (encoding == null || encoding.isEmpty()) ? "UTF-8" : encoding;

        PipelineDriver driver = new PipelineDriver();
        driver.setFilterConfigurationMapper(mapper);

        // Pin the filter on the step (no filterConfigId resolution path).
        RawDocumentToFilterEventsStep rd2feStep = new RawDocumentToFilterEventsStep(filter);
        driver.addStep(rd2feStep);

        // XLIFF2Filter (and any other filter that never calls
        // StartDocument.setFilterWriter) leaves the FilterWriter null on the
        // event, which makes FilterEventsToRawDocumentStep.handleStartDocument
        // NPE when it dereferences startDoc.getFilterWriter().setOptions(...).
        // EnsureFilterWriterStep falls back to filter.createFilterWriter().
        driver.addStep(new EnsureFilterWriterStep(filter));

        // Our own PseudoTranslationStep: SCRIPT_EXT_LATIN replacement on
        // segments only (ignorables stay untouched, fixing upstream's bug of
        // pseudo-translating ignorable TextParts), with target property
        // preservation (PO approved/fuzzy, TS type="unfinished").
        driver.addStep(new PseudoTranslationStep());

        driver.addStep(new FilterEventsToRawDocumentStep());

        // Stage every batch item; the driver iterates through them in
        // processBatch(). Each RawDocument carries its own URIs but
        // shares the filter, locales, and encoding configured above.
        List<RawDocument> openDocs = new ArrayList<>(items.size());
        for (IOPair item : items) {
            if (item.input == null || item.output == null) {
                throw new PseudoFailure(2, "manifest entry missing input or output URI");
            }
            RawDocument rd = new RawDocument(item.input, enc, src, tgt);
            driver.addBatchItem(rd, item.output, enc);
            openDocs.add(rd);
        }

        try {
            driver.processBatch();
        } catch (Exception e) {
            throw new PseudoFailure(1, "pipeline failed: " + e.getMessage(), e);
        } finally {
            for (RawDocument rd : openDocs) {
                try {
                    rd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static List<IOPair> readManifest(Path manifest) throws Exception {
        List<IOPair> out = new ArrayList<>();
        int lineNo = 0;
        for (String raw : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) {
                throw new RuntimeException("manifest line " + lineNo + " missing TAB separator");
            }
            String in = line.substring(0, tab).strip();
            String outPath = line.substring(tab + 1).strip();
            if (in.isEmpty() || outPath.isEmpty()) {
                throw new RuntimeException("manifest line " + lineNo + " has empty input or output");
            }
            File inFile = new File(in);
            if (!inFile.exists()) {
                throw new RuntimeException("manifest line " + lineNo + ": input not found: " + in);
            }
            out.add(new IOPair(inFile.toURI(), new File(outPath).toURI()));
        }
        return out;
    }

    /** Best-effort URI → File conversion; null when the URI isn't a local file. */
    private static File uriToFile(URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            return new File(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A single (input, output) URI pair for batch processing. */
    public static final class IOPair {
        public final URI input;
        public final URI output;

        public IOPair(URI input, URI output) {
            this.input = input;
            this.output = output;
        }
    }

    /** Unrecoverable pseudo-pipeline error with an associated exit code. */
    public static final class PseudoFailure extends Exception {
        public final int exitCode;

        public PseudoFailure(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }

        public PseudoFailure(int exitCode, String message, Throwable cause) {
            super(message, cause);
            this.exitCode = exitCode;
        }
    }
}
