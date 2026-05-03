package neokapi.bridge;

import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.filters.IFilter;
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
        }
        return super.handleEvent(event);
    }
}
