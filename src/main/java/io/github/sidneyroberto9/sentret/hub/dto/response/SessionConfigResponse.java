package io.github.sidneyroberto9.sentret.hub.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Client settings echoed by the hub, in milliseconds. Only what the @media4all/session-lite
 * client reads; an unset {@code loginUrl} is omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionConfigResponse(
        long heartbeatIntervalMs,
        long statusPollIntervalMs,
        long warningBeforeMs,
        String loginUrl
) {
}
