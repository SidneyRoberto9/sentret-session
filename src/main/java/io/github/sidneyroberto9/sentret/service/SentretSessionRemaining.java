package io.github.sidneyroberto9.sentret.service;

/**
 * Remaining-time snapshot for a session, computed by {@link SentretService#remaining} from the
 * principal. {@code idleRemainingMs} is {@code null} when idle enforcement is disabled.
 */
public record SentretSessionRemaining(long absoluteRemainingMs, Long idleRemainingMs) {
}
