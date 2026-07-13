package io.github.sidneyroberto9.spring_session_lite.web.sse;

/**
 * Payload of the SSE {@code logout} event: the session identified by {@code sessionId} was
 * destroyed. This is pushed for every path that ends in
 * {@code SpringSessionLiteService#logout(String)} — explicit {@code POST /session/logout}, any
 * other caller, and the idle-watch sweep discovering idle/absolute expiry — since all of them
 * funnel through the same {@code SpringSessionLiteSessionDestroyedEvent}. The payload
 * intentionally does not carry a cause: attributing "why" would require duplicating that
 * information across every call site instead of relying on the one shared event.
 */
public record SpringSessionLiteSseLogoutEvent(String sessionId) {
}
