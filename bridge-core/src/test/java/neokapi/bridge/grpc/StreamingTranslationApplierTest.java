package neokapi.bridge.grpc;

import neokapi.bridge.model.FragmentDTO;
import neokapi.bridge.model.SegmentDTO;
import neokapi.bridge.model.SpanDTO;
import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.resource.Code;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.TextContainer;
import net.sf.okapi.common.resource.TextFragment;
import net.sf.okapi.common.resource.TextUnit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StreamingTranslationApplier}.
 *
 * <p>The marquee regression test here is {@link #ttxStyleUnpairedClosingCode_preservesOuterData()}:
 * it reproduces the exact shape that broke unsegmented-TTX parity (okapi-bridge#11
 * follow-up) — a source TextUnit whose single segment contains a CLOSING-tagType
 * Code with no matching OPENING, so {@link TextFragment#balanceMarkers()}
 * downgrades its marker to MARKER_ISOLATED and rebalances Code.id values
 * during {@code createJoinedContent}. The applier must look up source codes
 * against the original (pre-join) view that {@code EventConverter} saw on the
 * outbound wire — otherwise the id-mismatched span hits {@code buildFreshCode}
 * and the writer (here exercised indirectly) loses the code's {@code outerData}.
 */
class StreamingTranslationApplierTest {

    private static final LocaleId TARGET = LocaleId.fromString("fr-FR");

    @Test
    void ttxStyleUnpairedClosingCode_preservesOuterData() {
        // Source: simulates TTXFilter's per-Raw TextUnit for an unsegmented TTX
        // fixture. Reproduces the exact code mix from the noseg failing
        // segment — `<a>`...`</a>` (unpaired in Okapi terms because the
        // opener is a PLACEHOLDER, not an OPENING) followed by a balanced
        // `<code>`...`</code>` pair. The OPENING/CLOSING pair pushes
        // lastCodeID past the unpaired-CLOSING's original id; when the
        // applier rejoins via getUnSegmentedContentCopy and rebalances, the
        // unpaired CLOSING gets a NEW id (above the original lastCodeID),
        // diverging from the outbound id Go saw.
        TextFragment srcFrag = new TextFragment();
        Code ph = srcFrag.append(TextFragment.TagType.PLACEHOLDER, "ph",
                "<a href=\"http://example/\">");
        ph.setOuterData("<ut DisplayText=\"a\">&lt;a href=\"http://example/\"&gt;</ut>");
        srcFrag.append("anchor element");
        Code closingNoOpening = srcFrag.append(TextFragment.TagType.CLOSING, "a", "</a>");
        closingNoOpening.setOuterData(
                "<ut Type=\"end\" LeftEdge=\"angle\" DisplayText=\"a\">&lt;/a&gt;</ut>");
        srcFrag.append(" demonstrates that ");
        // Balanced OPENING/CLOSING pair after the unpaired closing. This is
        // what makes the rejoin renumber the unpaired CLOSING to a NEW id.
        Code openCode = srcFrag.append(TextFragment.TagType.OPENING, "code", "<code>");
        openCode.setOuterData(
                "<ut Type=\"start\" RightEdge=\"angle\" DisplayText=\"code\">&lt;code&gt;</ut>");
        srcFrag.append("/>");
        Code closeCode = srcFrag.append(TextFragment.TagType.CLOSING, "code", "</code>");
        closeCode.setOuterData(
                "<ut Type=\"end\" LeftEdge=\"angle\" DisplayText=\"code\">&lt;/code&gt;</ut>");

        ITextUnit tu = new TextUnit("tu1");
        tu.setSource(new TextContainer(srcFrag));

        // Outbound spans: this is what EventConverter would have emitted to
        // Go — id and spanType come from the source's POST-BALANCE coded text
        // and lastCodeID. balanceMarkers downgrades the unpaired CLOSING to
        // MARKER_ISOLATED (spanType=PLACEHOLDER on the wire) AND renumbers
        // ids — but Code.tagType stays CLOSING.
        //
        // We use the same OkapiCodeConverter.toFragmentDTO that the live wire
        // uses, against the SAME view the live EventConverter uses
        // (TextContainer.getFirstContent()), so the ids in the SegmentDTO
        // match what Go would have received in production.
        FragmentDTO outboundDTO = neokapi.bridge.util.OkapiCodeConverter.toFragmentDTO(
                tu.getSource().getFirstContent());

        // Sanity: the wire-side spanType for the unpaired CLOSING code is
        // PLACEHOLDER (because the codedText marker was downgraded by
        // balanceMarkers). This is the upstream-Okapi behavior the applier
        // must tolerate.
        assertEquals(4, outboundDTO.getSpans().size());
        assertEquals(2, outboundDTO.getSpans().get(1).getSpanType(),
                "the unpaired CLOSING code's wire-side spanType should be PLACEHOLDER (2)");

        // Echo the DTO straight back as the "translation" (pseudo passthrough).
        SegmentDTO seg = new SegmentDTO();
        seg.setId("0");
        seg.setContent(outboundDTO);

        BlockingQueue<TranslationEntry> queue = new LinkedBlockingQueue<>();
        queue.offer(new TranslationEntry("tu1", java.util.Collections.singletonList(seg)));
        queue.offer(TranslationEntry.END);

        StreamingTranslationApplier applier = new StreamingTranslationApplier(queue, TARGET, 5);
        Event ev = new Event(EventType.TEXT_UNIT, tu);
        applier.applyTranslations(ev);

        // The applier must have hydrated the target's codes from the source
        // so outerData survives the round-trip — that's what TTXSkeletonWriter
        // (and any other writer using code.getOuterData()) needs to emit
        // the right tag instead of falling back to raw Code.data.
        TextContainer tgt = tu.getTarget(TARGET);
        assertNotNull(tgt, "target should have been set");
        List<Code> tgtCodes = tgt.getFirstContent().getCodes();
        assertEquals(4, tgtCodes.size(), "all four source codes should round-trip");

        // Direct regression assertion for the fix: the source codes the
        // applier looks up against must be the SAME identity (by reference)
        // as what EventConverter saw, i.e. tu.getSource().getFirstContent()
        // — NOT a re-joined view that goes through TextFragment.insert and
        // re-runs balanceMarkers, which can reassign Code.id values for
        // unpaired CLOSING codes and break the id-based span↔code lookup
        // in OkapiCodeConverter.findUnusedSourceCode. The simplest invariant
        // for the single-segment branch is that we never re-create the
        // source TextFragment behind the converter's back.
        List<Code> firstViewCodes = tu.getSource().getFirstContent().getCodes();
        List<Code> joinedViewCodes = tu.getSource().getUnSegmentedContentCopy().getCodes();
        // Different Code instances after rejoin (Code.clone()): a useful
        // tripwire if a future refactor reintroduces the join.
        for (int j = 0; j < firstViewCodes.size(); j++) {
            assertNotSame(firstViewCodes.get(j), joinedViewCodes.get(j),
                    "createJoinedContent clones codes; applier must read getFirstContent() " +
                            "to align with EventConverter's outbound view.");
        }

        // Critical assertion: outerData hydrated for the unpaired CLOSING.
        // If the applier joins-and-renumbers via getUnSegmentedContentCopy
        // before lookup, the wire-side span (id=2, PLACEHOLDER) misses the
        // renumbered source code (now id=4 after rebalance bumps it past the
        // OPENING/CLOSING pair's lastCodeID) and falls through to
        // buildFreshCode, which produces outerData=null. With outerData=null,
        // getOuterData() returns the raw Code.data ("</a>") and
        // TTXSkeletonWriter writes literal `</a>` into the output — the
        // noseg parity bug.
        assertTrue(tgtCodes.get(1).hasOuterData(),
                "unpaired CLOSING code must hydrate outerData from source — " +
                        "if this fails, the writer will emit Code.data ('</a>') instead of the full ut tag");
        assertEquals("<ut Type=\"end\" LeftEdge=\"angle\" DisplayText=\"a\">&lt;/a&gt;</ut>",
                tgtCodes.get(1).getOuterData());

        // Other codes must also survive (regression guard).
        assertTrue(tgtCodes.get(0).hasOuterData());
        assertEquals("<ut DisplayText=\"a\">&lt;a href=\"http://example/\"&gt;</ut>",
                tgtCodes.get(0).getOuterData());
        assertTrue(tgtCodes.get(2).hasOuterData());
        assertEquals("<ut Type=\"start\" RightEdge=\"angle\" DisplayText=\"code\">&lt;code&gt;</ut>",
                tgtCodes.get(2).getOuterData());
        assertTrue(tgtCodes.get(3).hasOuterData());
        assertEquals("<ut Type=\"end\" LeftEdge=\"angle\" DisplayText=\"code\">&lt;/code&gt;</ut>",
                tgtCodes.get(3).getOuterData());
    }
}
