package io.github.sidneyroberto9.sentret.service;

/**
 * Remaining-time snapshot for a session, computed on demand by
 * {@link SentretService#remaining(String)} for the status/heartbeat/renew endpoints.
 *
 * <p>{@code idleRemainingMs} is {@code null} when idle enforcement is disabled
 * ({@code maxIdle} unset/zero/negative) — there is no idle deadline to report.
 */
public record SentretSessionRemaining(long absoluteRemainingMs, Long idleRemainingMs) {
}
