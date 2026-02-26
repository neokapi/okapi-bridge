package com.gokapi.bridge.util;

import com.gokapi.bridge.model.SkeletonDTO;
import com.gokapi.bridge.model.SkeletonPartDTO;

import net.sf.okapi.common.IResource;
import net.sf.okapi.common.ISkeleton;
import net.sf.okapi.common.resource.TextFragment;
import net.sf.okapi.common.skeleton.GenericSkeleton;
import net.sf.okapi.common.skeleton.GenericSkeletonPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts Okapi GenericSkeleton to SkeletonDTO for transport over gRPC.
 *
 * Skeleton parts are either:
 * - Text parts: literal markup/whitespace around translatable content
 * - Reference parts: placeholders referencing a resource's content or property
 *
 * Reference markers follow the format: [#$resourceId] or [#$resourceId@%property]
 */
public class SkeletonConverter {

    /**
     * Extract a skeleton from an Okapi resource.
     * Returns null if the resource has no skeleton.
     */
    public static SkeletonDTO convert(IResource resource) {
        if (resource == null) {
            return null;
        }
        ISkeleton skel = resource.getSkeleton();
        if (skel == null) {
            return null;
        }
        if (!(skel instanceof GenericSkeleton)) {
            return null;
        }

        GenericSkeleton gs = (GenericSkeleton) skel;
        List<GenericSkeletonPart> okapiParts = gs.getParts();
        if (okapiParts == null || okapiParts.isEmpty()) {
            return null;
        }

        SkeletonDTO dto = new SkeletonDTO();
        dto.setStrategy(SkeletonDTO.STRATEGY_FRAGMENT);

        List<SkeletonPartDTO> parts = new ArrayList<>();
        for (GenericSkeletonPart part : okapiParts) {
            String data = part.getData().toString();
            String locale = part.getLocale() != null ? part.getLocale().toString() : null;

            // Check for reference markers in the data.
            Object[] ref = TextFragment.getRefMarker(part.getData());
            if (ref != null) {
                String refId = (String) ref[0];
                int start = (Integer) ref[1];
                int end = (Integer) ref[2];
                String propName = (String) ref[3];

                // Text before the reference marker.
                if (start > 0) {
                    parts.add(SkeletonPartDTO.text(data.substring(0, start)));
                }

                // The reference itself.
                parts.add(SkeletonPartDTO.ref(refId, propName, locale));

                // Text after the reference marker.
                if (end < data.length()) {
                    parts.add(SkeletonPartDTO.text(data.substring(end)));
                }
            } else {
                // Pure text part.
                parts.add(SkeletonPartDTO.text(data));
            }
        }

        dto.setParts(parts);
        return dto;
    }
}
