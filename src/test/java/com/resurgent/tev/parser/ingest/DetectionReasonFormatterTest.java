package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DetectionReasonFormatterTest {

    @Test
    void rendersStructuredReasonAtReadTimeInStableParameterOrder() {
        DetectionReason reason = new DetectionReason(DetectionReason.Code.SERIAL_PATTERN, 3,
                Map.of("serial_pattern", 1L, "serial_count", 3L));

        assertThat(DetectionReasonFormatter.format(reason))
                .isEqualTo("serial pattern (weight +3; serial_count=3, serial_pattern=1)");
    }
}
