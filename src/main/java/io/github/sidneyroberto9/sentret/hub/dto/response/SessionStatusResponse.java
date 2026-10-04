package io.github.sidneyroberto9.sentret.hub.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of every hub endpoint. Null fields are omitted, so an anonymous status carries only
 * {@code authenticated} and {@code config}; {@code idleRemainingMs} is absent when max-idle is off.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionStatusResponse(
        boolean authenticated,
        String userId,
        String email,
        Long absoluteRemainingMs,
        Long idleRemainingMs,
        SessionConfigResponse config
) {
}
