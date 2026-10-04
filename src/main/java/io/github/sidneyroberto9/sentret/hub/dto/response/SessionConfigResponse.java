package io.github.sidneyroberto9.sentret.hub.dto.response;

/**
 * Client settings echoed by the hub, in milliseconds. Only what the @media4all/session-lite
 * client reads.
 */
public record SessionConfigResponse(
        long heartbeatIntervalMs,
        long statusPollIntervalMs,
        long warningBeforeMs,
        String loginUrl
) {
}
