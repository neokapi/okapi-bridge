package neokapi.bridge.model;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

/**
 * Wire representation of a segment (neokapi model.Segment).
 */
public class SegmentDTO {

    /**
     * Property key marking a part as a non-segment "ignorable" TextPart —
     * an xliff2 &lt;ignorable&gt;, inter-segment whitespace, an ICU plural
     * selector, etc. Set on the wire so the Go side preserves the part
     * verbatim (never translates it) and the write-path applier can tell
     * ignorables apart from translatable segments.
     */
    public static final String IGNORABLE_PROPERTY = "ignorable";

    @SerializedName("id")
    private String id;

    @SerializedName("content")
    private FragmentDTO content;

    @SerializedName("properties")
    private Map<String, String> properties;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public FragmentDTO getContent() {
        return content;
    }

    public void setContent(FragmentDTO content) {
        this.content = content;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    /** True when this DTO represents an ignorable (non-segment) TextPart. */
    public boolean isIgnorable() {
        return properties != null && "true".equals(properties.get(IGNORABLE_PROPERTY));
    }

    /**
     * Returns only the translatable (non-ignorable) DTOs, in order. Ignorable
     * TextParts are source-copied by the write-path applier's COPY_ALL clone,
     * so the appliers strip them before the positional segment↔DTO mapping.
     * Returns the input unchanged when it holds no ignorables (the common
     * case), avoiding an allocation.
     */
    public static java.util.List<SegmentDTO> translatable(java.util.List<SegmentDTO> dtos) {
        boolean hasIgnorable = false;
        for (SegmentDTO d : dtos) {
            if (d.isIgnorable()) {
                hasIgnorable = true;
                break;
            }
        }
        if (!hasIgnorable) {
            return dtos;
        }
        java.util.List<SegmentDTO> out = new java.util.ArrayList<>(dtos.size());
        for (SegmentDTO d : dtos) {
            if (!d.isIgnorable()) {
                out.add(d);
            }
        }
        return out;
    }
}
