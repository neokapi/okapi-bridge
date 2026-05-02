package neokapi.bridge.util;

import neokapi.bridge.model.FragmentDTO;
import neokapi.bridge.model.SpanDTO;
import net.sf.okapi.common.resource.Code;
import net.sf.okapi.common.resource.TextFragment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OkapiCodeConverter focusing on the source-hydrate path that
 * preserves Okapi-internal Code metadata across the gRPC wire (outerData,
 * originalId, referenceFlag) and the data-preservation behavior of the
 * fresh-build fallback.
 */
class OkapiCodeConverterTest {

    @Test
    void hydrateFromSource_preservesWireLossyMetadata() {
        // Source has a paired link code with outerData and originalId set.
        TextFragment source = new TextFragment("Hello ");
        Code openSrc = source.append(TextFragment.TagType.OPENING, "link", "<a href=\"x\">");
        openSrc.setId(1);
        openSrc.setOuterData("<a href=\"x\" data-internal=\"42\">");
        openSrc.setOriginalId("element-7");
        openSrc.setReferenceFlag(true);
        source.append("world");
        Code closeSrc = source.append(TextFragment.TagType.CLOSING, "link", "</a>");
        closeSrc.setId(1);
        closeSrc.setOuterData("</a>");

        // Target FragmentDTO (what Go would echo back) — same structure,
        // text rune-substituted. SpanDTO carries only wire-encoded fields.
        FragmentDTO dto = new FragmentDTO();
        dto.setCodedText("Ĥēĺĺō ŵōŕĺď");
        List<SpanDTO> spans = new ArrayList<>();
        SpanDTO openSpan = new SpanDTO();
        openSpan.setSpanType(0); // Opening
        openSpan.setId("1");
        openSpan.setType("link");
        openSpan.setData("<a href=\"x\">");
        spans.add(openSpan);
        SpanDTO closeSpan = new SpanDTO();
        closeSpan.setSpanType(1); // Closing
        closeSpan.setId("1");
        closeSpan.setType("link");
        closeSpan.setData("</a>");
        spans.add(closeSpan);
        dto.setSpans(spans);

        TextFragment merged = OkapiCodeConverter.toTextFragment(dto, source);

        List<Code> codes = merged.getCodes();
        assertEquals(2, codes.size());

        // Wire-lossy fields: hydrated from source
        assertEquals("<a href=\"x\" data-internal=\"42\">", codes.get(0).getOuterData());
        assertEquals("element-7", codes.get(0).getOriginalId());
        assertTrue(codes.get(0).hasReference());
        assertEquals("</a>", codes.get(1).getOuterData());

        // Wire-carried fields: from SpanDTO
        assertEquals("<a href=\"x\">", codes.get(0).getData());
        assertEquals("link", codes.get(0).getType());
        assertEquals(1, codes.get(0).getId());
        assertEquals(TextFragment.TagType.OPENING, codes.get(0).getTagType());
    }

    @Test
    void hydrateFromSource_respectsGoSideDataChange() {
        // Real translation: Go rewrote the alt attribute inside an <img/>
        // placeholder. The source had the original markup; Go should win on
        // the wire-carried `data` field while the hydrated outerData stays.
        TextFragment source = new TextFragment();
        Code phSrc = source.append(TextFragment.TagType.PLACEHOLDER, "image", "<img alt=\"hello\"/>");
        phSrc.setId(1);
        phSrc.setOuterData("<img alt=\"hello\" id=\"img-1\"/>");
        source.append(" ");

        FragmentDTO dto = new FragmentDTO();
        dto.setCodedText(" ");
        List<SpanDTO> spans = new ArrayList<>();
        SpanDTO span = new SpanDTO();
        span.setSpanType(2);
        span.setId("1");
        span.setType("image");
        span.setData("<img alt=\"hola\"/>"); // Go translated alt-text
        spans.add(span);
        dto.setSpans(spans);

        TextFragment merged = OkapiCodeConverter.toTextFragment(dto, source);

        Code code = merged.getCodes().get(0);
        // Go's data change wins
        assertEquals("<img alt=\"hola\"/>", code.getData());
        // Source's outerData (with id="img-1") survives
        assertEquals("<img alt=\"hello\" id=\"img-1\"/>", code.getOuterData());
    }

    @Test
    void buildFreshCode_preservesData() {
        // Regression for Okapi Code(TagType, String) ctor confusion: the
        // 2-arg form takes (tagType, type) NOT (tagType, data) — the 2nd
        // arg becomes `type` and `data` initializes to "". Without the
        // 3-arg constructor fix in buildFreshCode, this test would see
        // data="" instead of "]".
        FragmentDTO dto = new FragmentDTO();
        dto.setCodedText("text");
        List<SpanDTO> spans = new ArrayList<>();
        SpanDTO span = new SpanDTO();
        span.setSpanType(2);
        span.setId("99");
        span.setType("link");
        span.setData("]");
        spans.add(span);
        dto.setSpans(spans);

        // No source provided — forces buildFreshCode path.
        TextFragment merged = OkapiCodeConverter.toTextFragment(dto, null);

        Code code = merged.getCodes().get(0);
        assertEquals("]", code.getData(), "buildFreshCode must preserve span.data");
        assertEquals("link", code.getType());
        assertEquals(99, code.getId());
        assertEquals(TextFragment.TagType.PLACEHOLDER, code.getTagType());
    }

    @Test
    void hydrateFromSource_idOnlyFallback_handlesTagTypeMismatch() {
        // Markdown ref-links produce a Code with tagType=CLOSING but a
        // PLACEHOLDER marker in the codedText (the trailing `]` of a
        // [text][ref] construct). Outbound conversion drives spanType from
        // the marker, so on the way back we see (PLACEHOLDER, id=4) but
        // the source has (CLOSING, id=4). The id-only fallback must still
        // hydrate from source so the writer sees data="]".
        TextFragment source = new TextFragment();
        // Hand-build a source TextFragment with a CLOSING Code carrying id=4.
        Code mismatchedSrc = new Code(TextFragment.TagType.CLOSING, "link", "]");
        mismatchedSrc.setId(4);
        mismatchedSrc.setOuterData("]");
        List<Code> srcCodes = new ArrayList<>();
        srcCodes.add(mismatchedSrc);
        source.setCodedText("text", srcCodes); // PLACEHOLDER marker + idx 0

        FragmentDTO dto = new FragmentDTO();
        // Go's echo: PLACEHOLDER marker (driven by source codedText), id=4, data="]"
        dto.setCodedText("text");
        List<SpanDTO> spans = new ArrayList<>();
        SpanDTO span = new SpanDTO();
        span.setSpanType(2); // PLACEHOLDER
        span.setId("4");
        span.setType("link");
        span.setData("]");
        spans.add(span);
        dto.setSpans(spans);

        TextFragment merged = OkapiCodeConverter.toTextFragment(dto, source);

        Code code = merged.getCodes().get(0);
        assertEquals("]", code.getData(), "id-only fallback must hydrate data");
        assertEquals("]", code.getOuterData(), "id-only fallback must preserve source outerData");
        assertEquals(4, code.getId());
    }

    @Test
    void hydrateFromSource_reorderedSpansMatchById() {
        // Go reordered <b><i>...</i></b> → <i><b>...</b></i>. Each span
        // must still find its source Code by id, not by position.
        TextFragment source = new TextFragment();
        Code bOpen = source.append(TextFragment.TagType.OPENING, "bold", "<b>");
        bOpen.setId(1);
        bOpen.setOuterData("<b class=\"b-style\">");
        Code iOpen = source.append(TextFragment.TagType.OPENING, "italic", "<i>");
        iOpen.setId(2);
        iOpen.setOuterData("<i class=\"i-style\">");
        source.append("text");
        Code iClose = source.append(TextFragment.TagType.CLOSING, "italic", "</i>");
        iClose.setId(2);
        Code bClose = source.append(TextFragment.TagType.CLOSING, "bold", "</b>");
        bClose.setId(1);

        FragmentDTO dto = new FragmentDTO();
        // Reordered: i-open b-open text b-close i-close
        dto.setCodedText("text");
        List<SpanDTO> spans = new ArrayList<>();
        spans.add(spanOf(0, "2", "italic", "<i>"));   // i-open
        spans.add(spanOf(0, "1", "bold", "<b>"));     // b-open
        spans.add(spanOf(1, "1", "bold", "</b>"));    // b-close
        spans.add(spanOf(1, "2", "italic", "</i>"));  // i-close
        dto.setSpans(spans);

        TextFragment merged = OkapiCodeConverter.toTextFragment(dto, source);

        List<Code> codes = merged.getCodes();
        assertEquals(4, codes.size());
        // codes[0] is i-open: id=2, outerData should come from source iOpen
        assertEquals(2, codes.get(0).getId());
        assertEquals("<i class=\"i-style\">", codes.get(0).getOuterData());
        // codes[1] is b-open: id=1, outerData should come from source bOpen
        assertEquals(1, codes.get(1).getId());
        assertEquals("<b class=\"b-style\">", codes.get(1).getOuterData());
    }

    @Test
    void hydrateFromSource_pairedCodesSameId_distinguishedByTagType() {
        // Source has two cloned bold pairs: id=1 OPEN/CLOSE and id=1
        // OPEN/CLOSE again (a Go-cloned construct). Each closing must
        // hydrate from the right source slot — the (id, tagType) preference
        // pairs them up consistently before the id-only fallback fires.
        TextFragment source = new TextFragment();
        Code o1 = source.append(TextFragment.TagType.OPENING, "bold", "<b>");
        o1.setId(1);
        o1.setOuterData("<b id=\"first\">");
        source.append("a");
        Code c1 = source.append(TextFragment.TagType.CLOSING, "bold", "</b>");
        c1.setId(1);
        c1.setOuterData("</b><!--first-->");

        FragmentDTO dto = new FragmentDTO();
        dto.setCodedText("a");
        List<SpanDTO> spans = new ArrayList<>();
        spans.add(spanOf(0, "1", "bold", "<b>"));
        spans.add(spanOf(1, "1", "bold", "</b>"));
        dto.setSpans(spans);

        TextFragment merged = OkapiCodeConverter.toTextFragment(dto, source);
        assertEquals("<b id=\"first\">", merged.getCodes().get(0).getOuterData());
        assertEquals("</b><!--first-->", merged.getCodes().get(1).getOuterData());
    }

    private static SpanDTO spanOf(int spanType, String id, String type, String data) {
        SpanDTO span = new SpanDTO();
        span.setSpanType(spanType);
        span.setId(id);
        span.setType(type);
        span.setData(data);
        return span;
    }
}
