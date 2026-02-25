package com.gokapi.bridge.grpc;

import com.gokapi.bridge.model.*;
import com.gokapi.bridge.proto.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between existing DTO types and proto-generated message types.
 * This thin adapter layer allows the existing EventConverter/PartDTOConverter
 * to remain unchanged while the transport switches from NDJSON to gRPC.
 */
public class ProtoAdapter {

    // ── PartDTO ↔ PartMessage ───────────────────────────────────────────────

    public static PartMessage toProto(PartDTO dto) {
        PartMessage.Builder b = PartMessage.newBuilder()
                .setPartType(dto.getPartType());

        switch (dto.getPartType()) {
            case PartDTO.TYPE_LAYER_START:
            case PartDTO.TYPE_LAYER_END:
                if (dto.getLayer() != null) {
                    b.setLayer(toProto(dto.getLayer()));
                }
                break;
            case PartDTO.TYPE_GROUP_START:
                if (dto.getGroupStart() != null) {
                    b.setGroupStart(toProto(dto.getGroupStart()));
                }
                break;
            case PartDTO.TYPE_GROUP_END:
                if (dto.getGroupEnd() != null) {
                    b.setGroupEnd(toProto(dto.getGroupEnd()));
                }
                break;
            case PartDTO.TYPE_BLOCK:
                if (dto.getBlock() != null) {
                    b.setBlock(toProto(dto.getBlock()));
                }
                break;
            case PartDTO.TYPE_DATA:
                if (dto.getData() != null) {
                    b.setData(toProto(dto.getData()));
                }
                break;
            case PartDTO.TYPE_MEDIA:
                if (dto.getMedia() != null) {
                    b.setMedia(toProto(dto.getMedia()));
                }
                break;
        }

        return b.build();
    }

    public static PartDTO fromProto(PartMessage msg) {
        PartDTO dto = new PartDTO();
        dto.setPartType(msg.getPartType());

        switch (msg.getPartType()) {
            case PartDTO.TYPE_LAYER_START:
            case PartDTO.TYPE_LAYER_END:
                if (msg.hasLayer()) {
                    dto.setLayer(fromProto(msg.getLayer()));
                }
                break;
            case PartDTO.TYPE_GROUP_START:
                if (msg.hasGroupStart()) {
                    dto.setGroupStart(fromProto(msg.getGroupStart()));
                }
                break;
            case PartDTO.TYPE_GROUP_END:
                if (msg.hasGroupEnd()) {
                    dto.setGroupEnd(fromProto(msg.getGroupEnd()));
                }
                break;
            case PartDTO.TYPE_BLOCK:
                if (msg.hasBlock()) {
                    dto.setBlock(fromProto(msg.getBlock()));
                }
                break;
            case PartDTO.TYPE_DATA:
                if (msg.hasData()) {
                    dto.setData(fromProto(msg.getData()));
                }
                break;
            case PartDTO.TYPE_MEDIA:
                if (msg.hasMedia()) {
                    dto.setMedia(fromProto(msg.getMedia()));
                }
                break;
        }

        return dto;
    }

    // ── BlockDTO ↔ BlockMessage ─────────────────────────────────────────────

    public static BlockMessage toProto(BlockDTO dto) {
        BlockMessage.Builder b = BlockMessage.newBuilder()
                .setId(nullSafe(dto.getId()))
                .setName(nullSafe(dto.getName()))
                .setType(nullSafe(dto.getType()))
                .setMimeType(nullSafe(dto.getMimeType()))
                .setTranslatable(dto.isTranslatable());

        if (dto.getSource() != null) {
            for (SegmentDTO seg : dto.getSource()) {
                b.addSource(toProto(seg));
            }
        }

        if (dto.getTargets() != null) {
            for (TargetDTO target : dto.getTargets()) {
                TargetEntry.Builder te = TargetEntry.newBuilder()
                        .setLocale(nullSafe(target.getLocale()));
                if (target.getSegments() != null) {
                    for (SegmentDTO seg : target.getSegments()) {
                        te.addSegments(toProto(seg));
                    }
                }
                b.addTargets(te);
            }
        }

        if (dto.getProperties() != null) {
            b.putAllProperties(dto.getProperties());
        }

        return b.build();
    }

    public static BlockDTO fromProto(BlockMessage msg) {
        BlockDTO dto = new BlockDTO();
        dto.setId(msg.getId());
        dto.setName(msg.getName());
        dto.setType(msg.getType());
        dto.setMimeType(msg.getMimeType());
        dto.setTranslatable(msg.getTranslatable());

        if (msg.getSourceCount() > 0) {
            List<SegmentDTO> source = new ArrayList<>();
            for (SegmentMessage seg : msg.getSourceList()) {
                source.add(fromProto(seg));
            }
            dto.setSource(source);
        }

        if (msg.getTargetsCount() > 0) {
            List<TargetDTO> targets = new ArrayList<>();
            for (TargetEntry te : msg.getTargetsList()) {
                TargetDTO target = new TargetDTO();
                target.setLocale(te.getLocale());
                List<SegmentDTO> segs = new ArrayList<>();
                for (SegmentMessage seg : te.getSegmentsList()) {
                    segs.add(fromProto(seg));
                }
                target.setSegments(segs);
                targets.add(target);
            }
            dto.setTargets(targets);
        }

        if (msg.getPropertiesCount() > 0) {
            dto.setProperties(new LinkedHashMap<>(msg.getPropertiesMap()));
        }

        return dto;
    }

    // ── LayerDTO ↔ LayerMessage ─────────────────────────────────────────────

    public static LayerMessage toProto(LayerDTO dto) {
        LayerMessage.Builder b = LayerMessage.newBuilder()
                .setId(nullSafe(dto.getId()))
                .setName(nullSafe(dto.getName()))
                .setFormat(nullSafe(dto.getFormat()))
                .setLocale(nullSafe(dto.getLocale()))
                .setEncoding(nullSafe(dto.getEncoding()))
                .setMimeType(nullSafe(dto.getMimeType()))
                .setLineBreak(nullSafe(dto.getLineBreak()))
                .setIsMultilingual(dto.isMultilingual())
                .setParentId(nullSafe(dto.getParentId()));

        if (dto.getProperties() != null) {
            b.putAllProperties(dto.getProperties());
        }

        return b.build();
    }

    public static LayerDTO fromProto(LayerMessage msg) {
        LayerDTO dto = new LayerDTO();
        dto.setId(msg.getId());
        dto.setName(msg.getName());
        dto.setFormat(msg.getFormat());
        dto.setLocale(msg.getLocale());
        dto.setEncoding(msg.getEncoding());
        dto.setMimeType(msg.getMimeType());
        dto.setLineBreak(msg.getLineBreak());
        dto.setMultilingual(msg.getIsMultilingual());
        dto.setParentId(msg.getParentId());
        return dto;
    }

    // ── DataDTO ↔ DataMessage ───────────────────────────────────────────────

    public static DataMessage toProto(DataDTO dto) {
        DataMessage.Builder b = DataMessage.newBuilder()
                .setId(nullSafe(dto.getId()))
                .setName(nullSafe(dto.getName()));

        if (dto.getProperties() != null) {
            b.putAllProperties(dto.getProperties());
        }

        return b.build();
    }

    public static DataDTO fromProto(DataMessage msg) {
        DataDTO dto = new DataDTO();
        dto.setId(msg.getId());
        dto.setName(msg.getName());
        if (msg.getPropertiesCount() > 0) {
            dto.setProperties(new LinkedHashMap<>(msg.getPropertiesMap()));
        }
        return dto;
    }

    // ── GroupStartDTO ↔ GroupStartMessage ────────────────────────────────────

    public static GroupStartMessage toProto(GroupStartDTO dto) {
        return GroupStartMessage.newBuilder()
                .setId(nullSafe(dto.getId()))
                .setName(nullSafe(dto.getName()))
                .setType(nullSafe(dto.getType()))
                .build();
    }

    public static GroupStartDTO fromProto(GroupStartMessage msg) {
        GroupStartDTO dto = new GroupStartDTO();
        dto.setId(msg.getId());
        dto.setName(msg.getName());
        dto.setType(msg.getType());
        return dto;
    }

    // ── GroupEndDTO ↔ GroupEndMessage ────────────────────────────────────────

    public static GroupEndMessage toProto(GroupEndDTO dto) {
        return GroupEndMessage.newBuilder()
                .setId(nullSafe(dto.getId()))
                .build();
    }

    public static GroupEndDTO fromProto(GroupEndMessage msg) {
        GroupEndDTO dto = new GroupEndDTO();
        dto.setId(msg.getId());
        return dto;
    }

    // ── MediaDTO ↔ MediaMessage ─────────────────────────────────────────────

    public static MediaMessage toProto(MediaDTO dto) {
        MediaMessage.Builder b = MediaMessage.newBuilder()
                .setId(nullSafe(dto.getId()))
                .setMimeType(nullSafe(dto.getMimeType()))
                .setUri(nullSafe(dto.getUri()))
                .setAltText(nullSafe(dto.getAltText()));

        if (dto.getProperties() != null) {
            b.putAllProperties(dto.getProperties());
        }

        return b.build();
    }

    public static MediaDTO fromProto(MediaMessage msg) {
        MediaDTO dto = new MediaDTO();
        dto.setId(msg.getId());
        dto.setMimeType(msg.getMimeType());
        dto.setUri(msg.getUri());
        dto.setAltText(msg.getAltText());
        if (msg.getPropertiesCount() > 0) {
            dto.setProperties(new LinkedHashMap<>(msg.getPropertiesMap()));
        }
        return dto;
    }

    // ── SegmentDTO ↔ SegmentMessage ─────────────────────────────────────────

    public static SegmentMessage toProto(SegmentDTO dto) {
        SegmentMessage.Builder b = SegmentMessage.newBuilder()
                .setId(nullSafe(dto.getId()));

        if (dto.getContent() != null) {
            b.setContent(toProto(dto.getContent()));
        }

        return b.build();
    }

    public static SegmentDTO fromProto(SegmentMessage msg) {
        SegmentDTO dto = new SegmentDTO();
        dto.setId(msg.getId());
        if (msg.hasContent()) {
            dto.setContent(fromProto(msg.getContent()));
        }
        return dto;
    }

    // ── FragmentDTO ↔ FragmentMessage ───────────────────────────────────────

    public static FragmentMessage toProto(FragmentDTO dto) {
        FragmentMessage.Builder b = FragmentMessage.newBuilder()
                .setCodedText(nullSafe(dto.getCodedText()));

        if (dto.getSpans() != null) {
            for (SpanDTO span : dto.getSpans()) {
                b.addSpans(toProto(span));
            }
        }

        return b.build();
    }

    public static FragmentDTO fromProto(FragmentMessage msg) {
        FragmentDTO dto = new FragmentDTO();
        dto.setCodedText(msg.getCodedText());
        if (msg.getSpansCount() > 0) {
            List<SpanDTO> spans = new ArrayList<>();
            for (SpanMessage span : msg.getSpansList()) {
                spans.add(fromProto(span));
            }
            dto.setSpans(spans);
        }
        return dto;
    }

    // ── SpanDTO ↔ SpanMessage ───────────────────────────────────────────────

    public static SpanMessage toProto(SpanDTO dto) {
        return SpanMessage.newBuilder()
                .setSpanType(dto.getSpanType())
                .setType(nullSafe(dto.getType()))
                .setId(nullSafe(dto.getId()))
                .setData(nullSafe(dto.getData()))
                .setOuterData(nullSafe(dto.getOuterData()))
                .setDeletable(dto.isDeletable())
                .setCloneable(dto.isCloneable())
                .build();
    }

    public static SpanDTO fromProto(SpanMessage msg) {
        SpanDTO dto = new SpanDTO();
        dto.setSpanType(msg.getSpanType());
        dto.setType(msg.getType());
        dto.setId(msg.getId());
        dto.setData(msg.getData());
        dto.setOuterData(msg.getOuterData());
        dto.setDeletable(msg.getDeletable());
        dto.setCloneable(msg.getCloneable());
        return dto;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
