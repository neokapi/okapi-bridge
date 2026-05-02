package neokapi.bridge;

import neokapi.bridge.util.FilterRegistry;

import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.filters.FilterConfigurationMapper;
import net.sf.okapi.common.filters.IFilter;
import net.sf.okapi.common.pipelinedriver.PipelineDriver;
import net.sf.okapi.common.resource.RawDocument;
import net.sf.okapi.steps.common.FilterEventsToRawDocumentStep;
import net.sf.okapi.steps.common.RawDocumentToFilterEventsStep;
import net.sf.okapi.steps.textmodification.Parameters;

import java.io.File;
import java.net.URI;

/**
 * Implementation of the {@code pseudo} subcommand. Composes
 * {@code RawDocumentToFilterEventsStep → TextModificationStep → FilterEventsToRawDocumentStep}
 * to produce a merged document where every translatable text unit's target
 * is the source with each Latin letter substituted using Okapi's
 * {@code SCRIPT_EXT_LATIN} character map (e.g. {@code A → À}, {@code e → ē}).
 *
 * <p>Inline codes are preserved — {@code TextModificationStep} skips the
 * {@code TextFragment} marker chars during substitution.
 *
 * <p>This subcommand exists so the parity round-trip harness can use
 * upstream Okapi's own pseudo-translate semantics as the comparator
 * across all engines (native, bridge, okapi). Previously the harness
 * shelled out to {@code tikal -x}/{@code -m} and rewrote XLIFF targets
 * by hand, which mishandled inline placeholders.
 *
 * <p>CLI:
 * <pre>
 *   neokapi-bridge pseudo --filter okf_html --input in.html --output out.html
 *                         [--src-lang en --tgt-lang fr]
 *                         [--encoding UTF-8]
 * </pre>
 */
public final class PseudoCommand {

    private PseudoCommand() {}

    public static int run(String[] args) {
        String filterClass = null;
        String inputPath = null;
        String outputPath = null;
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
        if (filterClass == null || inputPath == null || outputPath == null) {
            System.err.println("[pseudo] usage: pseudo --filter <class> --input <path> --output <path> "
                    + "[--src-lang en --tgt-lang fr] [--encoding UTF-8] [--fprm <content>]");
            return 2;
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("[pseudo] input file not found: " + inputPath);
            return 2;
        }

        IFilter filter = FilterRegistry.createFilter(filterClass);
        if (filter == null) {
            System.err.println("[pseudo] cannot instantiate filter: " + filterClass);
            return 1;
        }

        if (fprm != null && !fprm.isEmpty()) {
            try {
                filter.getParameters().fromString(fprm);
            } catch (Exception e) {
                System.err.println("[pseudo] could not load --fprm: " + e.getMessage());
                return 1;
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

        LocaleId src = LocaleId.fromString(srcLang);
        LocaleId tgt = LocaleId.fromString(tgtLang);

        PipelineDriver driver = new PipelineDriver();
        driver.setFilterConfigurationMapper(mapper);

        // Pin the filter on the step (no filterConfigId resolution path).
        RawDocumentToFilterEventsStep rd2feStep = new RawDocumentToFilterEventsStep(filter);
        driver.addStep(rd2feStep);

        // Use the property-preserving subclass so PO `approved`/TS `approved`
        // (backing #, fuzzy and type="unfinished" attrs) survive the pseudo
        // pass — upstream TextModificationStep replaces the target container
        // when filling blank entries, dropping its property bag.
        PropertyPreservingTextModificationStep modStep = new PropertyPreservingTextModificationStep();
        Parameters params = (Parameters) modStep.getParameters();
        params.setType(Parameters.TYPE_EXTREPLACE);
        params.setScript(Parameters.SCRIPT_EXT_LATIN);
        params.setApplyToBlankEntries(true);
        params.setApplyToExistingTarget(true);
        driver.addStep(modStep);

        driver.addStep(new FilterEventsToRawDocumentStep());

        RawDocument rd = new RawDocument(inputFile.toURI(), encoding, src, tgt);
        URI outUri = new File(outputPath).toURI();
        driver.addBatchItem(rd, outUri, encoding);

        try {
            driver.processBatch();
        } catch (Exception e) {
            System.err.println("[pseudo] pipeline failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        } finally {
            try {
                rd.close();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
