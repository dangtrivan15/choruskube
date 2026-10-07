package com.choruskube.core.util;

import com.choruskube.core.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates a per-project memory request, a Kubernetes quantity string such as {@code 512Mi}. */
public final class MemoryQuantity {

    /** Matches the {@code agent_memory_request} / {@code dind_memory_request} column width. */
    public static final int MAX_LENGTH = 32;

    // Plain decimal with an optional binary or decimal SI suffix. Exponent forms (1e9) are legal in
    // Kubernetes but rejected here so the stored value reads the way an operator would type it.
    private static final Pattern QUANTITY = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(Ki|Mi|Gi|Ti|Pi|Ei|k|M|G|T|P|E)?");

    private MemoryQuantity() {}

    /**
     * Returns the trimmed quantity, or {@code null} for null/blank (meaning: the deployment default).
     *
     * <p>A value the Worker cannot parse would only fail at pod launch, after the run has started, so
     * it is rejected here instead.
     *
     * @throws BadRequestException naming {@code field} when the value is not a positive quantity
     */
    public static String normalize(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        Matcher m = QUANTITY.matcher(value);
        if (value.length() > MAX_LENGTH || !m.matches() || new BigDecimal(m.group(1)).signum() <= 0) {
            throw new BadRequestException(
                    field + " must be a positive Kubernetes memory quantity such as 512Mi or 4Gi, got '" + raw + "'");
        }
        return value;
    }
}
