package neokapi.bridge;

import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.encoder.EncoderManager;
import net.sf.okapi.common.filters.IFilter;
import net.sf.okapi.common.filterwriter.GenericFilterWriter;
import net.sf.okapi.common.filterwriter.IFilterWriter;
import net.sf.okapi.common.pipeline.BasePipelineStep;
import net.sf.okapi.common.resource.StartDocument;

/**
 * Pipeline step that guarantees every {@link StartDocument} event carries an
 * {@link IFilterWriter}. Some Okapi filters (notably {@code XLIFF2Filter})
 * never call {@code StartDocument.setFilterWriter(...)} during read, so the
 * stock {@link net.sf.okapi.steps.common.FilterEventsToRawDocumentStep}
 * NPEs when it dereferences {@code startDoc.getFilterWriter()} to call
 * {@code setOptions(...)}.
 *
 * <p>This step sits between the read step and the FE2RD step, intercepts
 * {@code START_DOCUMENT} events, and falls back to
 * {@code filter.createFilterWriter()} when the StartDocument hasn't already
 * supplied one. The newly-created writer inherits the filter's parameters
 * so e.g. an XLIFF 2 writer respects the same options the reader saw.
 *
 * <p>Additionally, when running in batch mode (multiple documents through
 * the same filter instance), the filter's {@link EncoderManager} retains
 * state (MIME type, charset encoder, line-break setting) from the previous
 * document. Okapi's {@link EncoderManager#updateEncoder(String)} short-circuits
 * when the MIME type hasn't changed, so
 * {@link GenericFilterWriter#processStartDocument processStartDocument}'s
 * call never re-creates the encoder — leaving stale encoding/line-break
 * settings from file N on file N+1. This step forces the encoder manager
 * to a dummy MIME type so the next {@code updateEncoder} in
 * {@code processStartDocument} runs afresh and picks up the correct output
 * encoding ({@code "UTF-8"}) and line break for the <em>current</em> document.
 */
public final class EnsureFilterWriterStep extends BasePipelineStep {

    private final IFilter filter;

    public EnsureFilterWriterStep(IFilter filter) {
        this.filter = filter;
    }

    @Override
    public String getName() {
        return "Ensure Filter Writer";
    }

    @Override
    public String getDescription() {
        return "Attaches a default IFilterWriter to StartDocument events that lack one (e.g. XLIFF 2).";
    }

    @Override
    public Event handleEvent(Event event) {
        if (event.getEventType() == EventType.START_DOCUMENT) {
            StartDocument sd = event.getStartDocument();
            if (sd != null && sd.getFilterWriter() == null) {
                IFilterWriter w = filter.createFilterWriter();
                if (w != null) {
                    w.setParameters(filter.getParameters());
                    sd.setFilterWriter(w);
                }
            }
            // Force the writer's encoder manager to re-initialise on the
            // next updateEncoder() call in processStartDocument.  Without
            // this, EncoderManager.updateEncoder short-circuits when the
            // MIME type matches the value cached from a previous batch
            // item (or from filter.open()), preserving stale charset-
            // encoder and line-break settings.  Passing a dummy MIME type
            // resets the cached value so the real call in processStart-
            // Document creates a fresh encoder with the correct output
            // encoding (UTF-8) and the current document's line break.
            if (sd != null) {
                resetEncoderManager(sd.getFilterWriter());
            }
        }
        return super.handleEvent(event);
    }

    /**
     * Resets the encoder manager's cached MIME type by calling
     * {@link EncoderManager#updateEncoder(String)} with a sentinel value
     * that will never match a real MIME type.
     */
    private static void resetEncoderManager(IFilterWriter writer) {
        if (!(writer instanceof GenericFilterWriter)) {
            return;
        }
        EncoderManager em = ((GenericFilterWriter) writer).getEncoderManager();
        if (em != null) {
            em.updateEncoder("application/x-force-reinit");
        }
    }
}
