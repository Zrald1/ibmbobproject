package com.argos.argos_backend.dto;

import java.util.List;

/**
 * Metadata describing one AI model for {@code GET /api/models}.
 *
 * @param id          wire identifier used by clients (e.g. {@code gpt-6-astra})
 * @param name        human readable display name
 * @param version     model version string
 * @param description short capability summary
 * @param status      {@code online} (live key configured) or {@code simulation}
 * @param features    headline capability tags
 * @param requests    number of requests served by this model since startup
 * @param isDefault   whether this is the configured default model
 */
public record ModelInfoDto(
        String id,
        String name,
        String version,
        String description,
        String status,
        List<String> features,
        long requests,
        boolean isDefault
) {
}
