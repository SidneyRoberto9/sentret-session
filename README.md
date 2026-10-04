# Sentret

Lightweight cookie-based session authentication for Spring Boot 3 and 4. Sessions live in your
application's own database (plain JDBC, no JPA), the browser carries an opaque `HttpOnly` cookie,
and an optional hub serves the inactivity endpoints used by the `@media4all/session-lite` client.

> Named after Sentret, the lookout Pokémon that stands on its tail to watch over its territory —
> which is what the authentication filter does for every request.

## Install

```xml
<dependency>
    <groupId>io.github.sidneyroberto9</groupId>
    <artifactId>sentret-session</artifactId>
    <version>1.0.0</version>
</dependency>
```

Requirements: Java 17+; Spring Boot 3.3+ or 4.x (verified on 3.3.3, 3.5.15 and 4.1.1); a servlet web
application (`spring-boot-starter-web`, which the library does not pull in); a `DataSource` with
`JdbcTemplate` (`spring-boot-starter-jdbc` or `spring-boot-starter-data-jpa`).

## Database

Run once (Flyway, Liquibase or by hand). Portable as written across MySQL, MariaDB, PostgreSQL,
SQL Server and H2; the same script ships in the jar at `db/sentret-schema.sql`.

```sql
CREATE TABLE sentret_sessions (
    session_id       VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    created_at       BIGINT       NOT NULL,
    expires_at       BIGINT       NOT NULL,
    last_accessed_at BIGINT       NOT NULL
);

CREATE INDEX idx_sentret_sessions_user_id ON sentret_sessions (user_id);
CREATE INDEX idx_sentret_sessions_expires_at ON sentret_sessions (expires_at);
```

Session ids are case-sensitive. On MySQL/MariaDB declare `session_id` with
`CHARACTER SET ascii COLLATE ascii_bin`, on SQL Server with `COLLATE Latin1_General_BIN2`
(PostgreSQL and H2 are case-sensitive already). The library also rejects a row whose id differs only
in case, so a case-insensitive column is safe, just with fewer effective bits.

## Quickstart

```java
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final SentretService sentret;

    @PostMapping("/auth/login")
    public ResponseEntity<SentretUser> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        String userId = credentials.check(body); // your own authentication
        SentretUser user = sentret.login(userId, body.email(), response);
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sentret.logout(request, response);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/me")
    public ResponseEntity<SentretUser> me(@AuthenticationPrincipal SentretUser user) {
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }
}
```

`SentretUser` carries `userId`, `email`, `sessionId`, `expiresAt` and `lastAccessedAt`. Roles and
permissions stay in your application, looked up by `userId`.

## Properties (prefix `sentret`)

Nothing is required.

| Property | Default | |
|---|---|---|
| `enabled` | `true` | Turn the library off. |
| `ttl` | `8h` | Absolute session lifetime. |
| `max-idle` | `30m` | Inactivity window, reset only by the hub heartbeat. Enforced only with `hub.enabled=true`; `0` disables it. |
| `cookie-name` | `SENTRETSID` | Use `__Host-SID` to harden the cookie. |
| `cookie-secure` | `true` | `false` only for local HTTP. |
| `cookie-same-site` | `Lax` | |
| `cookie-domain` | — | Share the cookie across subdomains. |
| `csrf-enabled` | `false` | CSRF on the default chain, SPA style: read the `XSRF-TOKEN` cookie and send it back in the `X-XSRF-TOKEN` header. |
| `csrf-ignored-paths` | — | Paths exempt from CSRF, e.g. the logout URL the npm client calls without a token. |
| `cors-allowed-origins` | — | CORS (with credentials) is on when not empty. Patterns such as `https://*.example.com` work. |
| `permit-all-paths` | `/login`, `/auth/**`, `/public/**`, `/actuator/health/**` | Public paths of the default chain. |
| `hub.enabled` | `false` | Serve the inactivity hub. |
| `hub.base-path` | `/session` | |
| `hub.heartbeat-interval` | `60s` | Echoed to the client. |
| `hub.status-poll-interval` | `30s` | Echoed to the client. |
| `hub.warning-before` | `60s` | Echoed to the client. |
| `hub.login-url` | — | Echoed to the client. |

Typical production configuration:

```properties
sentret.ttl=4h
sentret.cors-allowed-origins=https://app.example.com
```

## Inactivity hub (optional)

| Endpoint | Auth | |
|---|---|---|
| `GET {base-path}/status` | permit-all | Remaining absolute/idle time + client config. Never counts as activity. |
| `POST {base-path}/heartbeat` | session | The only activity signal: one `UPDATE`. |
| `POST {base-path}/renew` | session | Resets both deadlines (one `UPDATE`, no re-read) and rewrites the cookie. |

## How it works

- The filter reads the cookie and validates the session with one `SELECT` (a malformed cookie is
  rejected without querying, and the stored id must match exactly); the principal carries both
  deadlines, so the hub never reads the row twice.
- Only the heartbeat (and an explicit renew) writes `last_accessed_at`. Polls and your own API calls
  never extend a session.
- Expired rows are purged on login (indexed `DELETE`); there is no scheduler.
- Unauthenticated requests get `401` with no body.

## Migrating from spring-session-lite

See [MIGRATION.md](MIGRATION.md).

## License

MIT
