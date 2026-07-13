# Changelog

Todas as mudanças notáveis deste projeto são documentadas neste arquivo. O formato segue,
livremente, o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/); o versionamento segue
[SemVer](https://semver.org/lang/pt-BR/).

---

## [2.1.0] - 2026-07-13

Bump **MINOR** — tudo aditivo e desligado por padrão, sem quebras. Ver
[`MIGRATION.md`](MIGRATION.md) (seção "2.0.0 → 2.1.0") e
[`docs/06-sessao-centralizada-multissistema.md`](docs/06-sessao-centralizada-multissistema.md)
para o checklist completo de adesão.

### Adicionado

- `max-idle` (`Duration`, default `0`/desativado): janela de inatividade avaliada em
  `SpringSessionLiteService.validate()`, além da expiração absoluta já existente.
- `SpringSessionLiteService.renew(sessionId)` / `renew(request, response)`: reinicia a expiração
  absoluta e a janela de inatividade da sessão ("voltar para o hub"); publica
  `SpringSessionLiteSessionRenewedEvent`.
- `SpringSessionLiteService.remaining(sessionId)` + `SpringSessionLiteSessionRemaining`: snapshot
  somente-leitura de tempo restante (absoluto/idle), usado pelos endpoints e eventos SSE abaixo.
- Endpoints opt-in `GET /session/status`, `POST /session/heartbeat`, `POST /session/renew`,
  `POST /session/logout` (`endpoints-enabled=false` por padrão), via
  `SpringSessionLiteEndpointsAutoConfiguration` — zero código de controller no consumidor.
- Stream SSE opt-in `GET /session/stream` (`sse-enabled=false` por padrão), via
  `SpringSessionLiteSseAutoConfiguration`: eventos `warning`/`logout`/`renew`, varredura
  idle-watch (`@Scheduled` de 10s), keep-alive ping, e a abstração `SessionEventBroadcaster`
  (extensível para um broadcaster horizontal baseado em pub/sub, ex. Redis — não implementado
  nesta versão).
- Propriedades de config-echo para o frontend: `heartbeat-interval` (`60s`),
  `status-poll-interval` (`30s`), `warning-before` (`60s`), `login-url`, `logout-url`,
  `redirect-after-expiry-url`; e `endpoints-base-path` (`/session`) para a base path dos
  endpoints/SSE acima.
- `docs/06-sessao-centralizada-multissistema.md`: modelo de hub de inatividade para plataformas
  com múltiplas aplicações, checklist de adesão (backend + contrato esperado do client), e a
  ressalva de origin em dev vs. produção.

### Alterado

- Nenhum comportamento existente muda — todos os recursos acima são opt-in. Ver detalhes de
  precisão do idle-check (throttle efetivo `min(last-accessed-throttle, max-idle / 2)`) em
  [`docs/03-como-funciona.md`](docs/03-como-funciona.md) §10.

---

## [2.0.0] - 2026-06-17

Bump **MAJOR** — mudanças breaking em relação a `1.0.x`. Resumo abaixo; guia completo em
[`MIGRATION.md`](MIGRATION.md).

### Alterado (breaking)

- Rename de classes: `SpringLiteSession` → `SpringSessionLiteSession`,
  `SpringLiteSessionRepository` → `SpringSessionLiteSessionRepository`.
- Tabela `spring_lite_sessions` → `spring_session_lite_sessions` (+ nova coluna `roles`), índices
  renomeados.
- Cookie padrão `M4SID` → `SLSID`.
- `SpringSessionLiteUser` virou `record` (acessores `userId()`/`email()`/`sessionId()`/`roles()`
  no lugar dos getters Lombok).
- O filtro de autenticação deixou de responder 401 diretamente em cookie inválido/expirado — agora
  limpa o cookie morto e segue anônimo, deixando a autorização decidir (evita lock-out de
  re-login em rotas `permit-all`).

### Adicionado

- Logout/revogação: `logout(request, response)`, `logout(sessionId)`, `logoutAll(userId)`.
- Roles opcionais no login → authorities `ROLE_*`.
- `SpringSessionLiteSessionStore` como interface plugável (default JPA).
- Eventos `SpringSessionLiteSessionCreatedEvent` / `...DestroyedEvent`.
- `csrf-enabled`, `cors-*`, `trusted-proxy-count` (XFF não-falsificável), `cookie-prefix`,
  validador de segurança no startup.
- `update-last-accessed` + `last-accessed-throttle`, `sliding-expiration`, `cleanup-enabled`.
- `session-id-length` default subiu de 16 para 21.

---

## [1.0.1] - 2026-06-12

Sem changelog detalhado registrado (anterior à criação deste arquivo). Consulte o histórico Git
(`9f486e6`) ou o artefato publicado no Maven Central.

## [1.0.0] - 2026-06-11

Versão inicial da biblioteca. Sem changelog detalhado registrado (anterior à criação deste
arquivo). Consulte o histórico Git (`8ba8b51`) ou o artefato publicado no Maven Central.
