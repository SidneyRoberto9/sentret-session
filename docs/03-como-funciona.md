# Spring Session Lite — Como Funciona

Arquitetura interna: auto-configuração, login, validação por requisição e proteção dos dados.

---

## 1. Visão geral

A biblioteca substitui o JWT por **sessão persistida em banco**. O cliente carrega apenas um
**NanoID** (21 caracteres, ≈128 bits) num cookie `SLSID`. O estado real (usuário, e-mail, roles,
IP, datas) vive na tabela `spring_session_lite_sessions` da própria aplicação.

```
Cliente ──POST /login──► SpringSessionLiteService.login() ──► spring_session_lite_sessions
   │  ◄──Set-Cookie: SLSID──
   │
   └─GET /me (Cookie: SLSID) ─► SpringSessionLiteAuthenticationFilter ─► .validate()
                                         │
                                         ▼ popula SecurityContext → @SpringSessionLiteCurrentSession
```

---

## 2. Estrutura de pacotes

```
io.github.sidneyroberto9.spring_session_lite
├── autoconfigure/  SpringSessionLiteAutoConfiguration, ...EndpointsAutoConfiguration (opt-in),
│                   ...SseAutoConfiguration (opt-in), SpringSessionLiteWebMvcConfiguration
├── config/         SpringSessionLiteProperties, SpringSessionLiteSecurityValidator
├── domain/         SpringSessionLiteSession, SpringSessionLiteSessionRepository
├── store/          SpringSessionLiteSessionStore (interface), JpaSpringSessionLiteSessionStore
├── security/       SpringSessionLiteUser (record), SpringSessionLiteAuthenticationFilter
├── service/        SpringSessionLiteService, ...CookieManager, ...IpResolver, ...IpHasher, ...UserService,
│                   SpringSessionLiteSessionRemaining (record — snapshot de tempo restante)
├── event/          SpringSessionLiteSessionCreatedEvent, ...DestroyedEvent, ...RenewedEvent
├── web/            @SpringSessionLiteCurrentSession, ...ArgumentResolver
│   ├── controller/ SpringSessionLiteSessionController — /session/status|heartbeat|renew|logout (opt-in)
│   └── sse/        SpringSessionLiteSseController (GET /session/stream, opt-in), ...SseRegistry,
│                   SessionEventBroadcaster (interface) + InMemorySessionEventBroadcaster (default),
│                   SpringSessionLiteIdleWatchTask (varredura @Scheduled), ...SseSessionEventListener,
│                   payloads: ...SseLogoutEvent, ...SseRenewEvent, ...SseWarningEvent
├── scheduler/      SpringSessionLiteCleanupTask
└── util/           NanoId
```

---

## 3. Auto-configuração

Ponto de entrada: `SpringSessionLiteAutoConfiguration`, listada em
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

```java
@AutoConfiguration(before = JpaRepositoriesAutoConfiguration.class, after = HibernateJpaAutoConfiguration.class)
@AutoConfigurationPackage(basePackageClasses = SpringSessionLiteSession.class)
@ConditionalOnClass({ EntityManagerFactory.class, SecurityFilterChain.class })
@ConditionalOnProperty(prefix = "spring-session-lite", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SpringSessionLiteProperties.class)
```

### 3.1. Por que `@AutoConfigurationPackage` e não `@EntityScan`?

A entidade `SpringSessionLiteSession` e o repositório ficam num pacote **diferente** do
`@SpringBootApplication` consumidor. `@EntityScan`/`@EnableJpaRepositories` **substituiriam** o
scan da aplicação (descartando entidades/repos dela). `@AutoConfigurationPackage(basePackageClasses = SpringSessionLiteSession.class)`
**adiciona** o pacote `domain` ao conjunto `AutoConfigurationPackages` de forma aditiva.

> Se a aplicação declarar `@EntityScan`/`@EnableJpaRepositories` explícitos, inclua
> `io.github.sidneyroberto9.spring_session_lite.domain` na lista.

---

## 4. A entidade `SpringSessionLiteSession`

Tabela `spring_session_lite_sessions`: `id` (UUID interno, PK), `session_id` (NanoID público,
único/indexado), `user_id` (indexado), `email`, `roles` (CSV), `ip_hash` (HMAC-SHA256 do IP),
`created_at`, `expires_at` (indexado), `last_accessed_at`.

Dois identificadores propositais: `id` (UUID interno) e `session_id` (NanoID público que vai no
cookie).

---

## 5. Login — `SpringSessionLiteService.login(...)`

1. **NanoID** — `SecureRandom` sobre alfabeto URL-safe de 64 chars; `byte & 63` é uniforme.
2. **Roles** — lista opcional, persistida como CSV.
3. **Hash do IP** — IP resolvido (`IpResolver`) e passado por HMAC-SHA256 (`IpHasher`); só o hash
   é persistido.
4. **Persistência** — via `SpringSessionLiteSessionStore` (default `JpaSpringSessionLiteSessionStore`).
5. **Cookie** — `SpringSessionLiteCookieManager` escreve o `Set-Cookie`.
6. **Evento** — publica `SpringSessionLiteSessionCreatedEvent`.

### 5.1. Cookie — `CookieManager`

Usa `ResponseCookie` (única API que suporta `SameSite`). Sai sempre `HttpOnly`, com `Secure`,
`SameSite`, `Path`, `Max-Age` e `Domain` das propriedades. Suporta `cookie-prefix`
(`__Host-`/`__Secure-`).

```
Set-Cookie: SLSID=V1StGXR8_Z5jdHi6abc; Path=/; Max-Age=28800; Secure; HttpOnly; SameSite=Lax
```

---

## 6. Validação — `SpringSessionLiteAuthenticationFilter`

`OncePerRequestFilter` que roda **dentro** da cadeia do Security:

```
lê cookie SLSID
 ├─ ausente ─────► segue anônimo (/login acessível)
 └─ presente ─► SpringSessionLiteService.validate(sessionId, request)
       ├─ inválida/expirada/IP divergente ─► limpa contexto, APAGA o cookie morto, SEGUE anônimo
       │                                       (a autorização decide o 401; NÃO bloqueia permit-all)
       └─ válida ─► popula SecurityContext (roles → authorities) + touch() + segue
```

> **Decisão de design (correção):** o filtro **não** emite 401 diretamente. Um cookie expirado
> jamais bloqueia rotas `permit-all` (como o próprio `/login`), evitando lock-out de re-login.

### 6.1. Por que dentro da cadeia do Security?

No Spring Security 6 a cadeia começa com `SecurityContextHolderFilter`, que carrega/limpa o
contexto. Autenticar num filtro servlet **antes** da cadeia seria sobrescrito. Por isso o filtro
é adicionado via `addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)`.

### 6.2. `touch()` — escrita controlada

Por padrão `last_accessed_at` é atualizado, mas com **throttle** (`last-accessed-throttle`,
default 5 min) para não gerar um UPDATE por requisição. Com `sliding-expiration=true`, o
`expires_at` também é estendido (mesmo throttle). `update-last-accessed=false` torna a validação
uma leitura pura — **exceto** quando `max-idle` está habilitado (§10), caso em que
`last_accessed_at` continua sendo escrito porque o idle-check depende dele.

---

## 7. IP — `IpResolver` / `IpHasher`

- `IpResolver`: por padrão `getRemoteAddr()`. Com `trust-forwarded-for=true`, lê o
  `X-Forwarded-For` na posição `(total - trusted-proxy-count)` a partir da esquerda — **nunca** o
  left-most, que é declarado pelo cliente e falsificável.
- `IpHasher`: HMAC-SHA256 do IP usando `ip-hash-salt` (chave cacheada). Saída hex de 64 chars; o
  IP nunca é gravado em texto puro. Recalculado e comparado a cada requisição.

---

## 8. `@SpringSessionLiteCurrentSession` e `SpringSessionLiteUserService`

`SpringSessionLiteWebMvcConfiguration` registra o argument resolver que injeta o
`SpringSessionLiteUser` (record) lendo o principal do `SecurityContextHolder`. Fora de
controllers, `SpringSessionLiteUserService.currentUser()` lê da mesma fonte e devolve
`Optional<SpringSessionLiteUser>`.

---

## 9. Logout e revogação

- `logout(request, response)` — apaga a sessão pelo cookie e limpa o cookie; publica
  `SpringSessionLiteSessionDestroyedEvent`.
- `logout(sessionId)` — revogação programática de uma sessão.
- `logoutAll(userId)` — revoga todas as sessões do usuário (ex.: troca de senha).

---

## 10. Inatividade e renovação (`max-idle` / `renew()`)

### 10.1. Idle-check em `validate()`

Além da expiração absoluta (`expires_at`), `validate()` aplica um segundo corte quando
`max-idle` está habilitado (`Duration` diferente de `null`/zero/negativo):

```
reference = last_accessed_at (ou created_at, se nunca houve acesso)
reference + max-idle < agora  ─►  sessão tratada como inválida (Optional.empty()), igual à expiração absoluta
```

`0`/ausente (default) desativa o idle-check inteiramente, preservando o comportamento anterior à
2.1 — nenhum consumidor existente é afetado até configurar `max-idle` explicitamente.

**Throttle efetivo:** com o idle-check ligado, o intervalo mínimo entre escritas de
`last_accessed_at` deixa de ser só `last-accessed-throttle` — passa a ser
`min(last-accessed-throttle, max-idle / 2)`. Sem esse limite, um `last-accessed-throttle` maior
que a janela de `max-idle` atrasaria a própria detecção de inatividade. `touch()` também passa a
escrever `last_accessed_at` mesmo com `update-last-accessed=false`, porque o idle-check depende
desse timestamp para funcionar.

### 10.2. `renew()` — "voltar para o hub"

`SpringSessionLiteService.renew(sessionId)` reinicia **as duas** janelas na mesma chamada:

- `expires_at = agora + ttl` (expiração absoluta);
- `last_accessed_at = agora` (janela de inatividade).

Publica `SpringSessionLiteSessionRenewedEvent(userId, sessionId, agora)`. A sobrecarga
`renew(request, response)` lê o `session_id` do cookie — sem cookie, devolve `Optional.empty()`
sem tocar o banco — e delega a `renew(sessionId)`, que só falha (`Optional.empty()`) se o registro
já não existir mais no store (ex.: apagado por um logout/idle-watch/limpeza concorrente); ao
contrário de `validate()`, não reavalia `expires_at`/idle antes de renovar. Em caso de sucesso,
reescreve o cookie (`cookieManager.write`, atualizando o `Max-Age`). Na prática, via o endpoint
`POST /session/renew` (§11), essa distinção raramente importa: o filtro de autenticação já exige
uma sessão válida (idle/absoluta) para a requisição chegar ao controller.

`SpringSessionLiteService.remaining(sessionId)` é uma leitura **sem** efeitos colaterais (não
chama `touch()`/`validate()`) que devolve `SpringSessionLiteSessionRemaining` (`absoluteRemainingMs`,
`idleRemainingMs` — este último `null` quando `max-idle` está desativado). É a base dos endpoints
de status/heartbeat/renew (§11) e dos eventos SSE (§12).

---

## 11. Endpoints opt-in — `/session/*`

`SpringSessionLiteEndpointsAutoConfiguration` registra o `SpringSessionLiteSessionController`
apenas com `endpoints-enabled=true` (default `false`) — uma `@AutoConfiguration` **separada** da
principal, para que consumidores existentes continuem sem nenhum endpoint novo até habilitarem a
propriedade explicitamente. O controller é um `@Bean` explícito (não `@ComponentScan`), porque o
scan da aplicação consumidora não alcança o pacote da lib — resultado prático: **zero código de
controller no consumidor**, só a flag de config.

Base path configurável via `endpoints-base-path` (default `/session`):

| Rota | Autenticação | O que faz |
|------|--------------|-----------|
| `GET /status` | permit-all | Devolve `{ authenticated, userId?, email?, roles?, absoluteRemainingMs?, idleRemainingMs?, config }`. `config` ecoa `ttl`, `max-idle`, `heartbeat-interval`, `status-poll-interval`, `warning-before`, `login-url`, `logout-url`, `redirect-after-expiry-url` — nenhuma UI precisa hardcodar esses tempos/URLs. |
| `POST /heartbeat` | requer sessão | Não chama `touch()` de novo — o filtro de autenticação já validou/tocou a sessão nesta mesma requisição; só reporta o status resultante. |
| `POST /renew` | requer sessão | Delega a `sessionService.renew(request, response)`; `401` (`{"error":"unauthorized",...}`) na rara corrida de a sessão ter sido apagada entre o filtro e o controller. |
| `POST /logout` | requer sessão | Delega a `sessionService.logout(request, response)`; `204 No Content`. |

> O `permit-all` de `/status` é ligado no chain de segurança já existente
> (`SpringSessionLiteAutoConfiguration`), não nesta autoconfiguração nova — mutar
> `permit-all-paths` a partir de uma segunda `@AutoConfiguration` dependeria de ordem de
> instanciação entre autoconfigurações distintas, o que `before=`/`after=` não garante.

---

## 12. Push SSE opt-in — `GET /stream`

`SpringSessionLiteSseAutoConfiguration` registra a pilha SSE apenas com `sse-enabled=true`
(default `false`), independente de `endpoints-enabled`/`cleanup-enabled`:

- **`SpringSessionLiteSseController`** — `GET <endpoints-base-path>/stream` (mesma propriedade de
  base path dos endpoints REST), exige sessão válida (não está em `permit-all-paths`), cria um
  `SseEmitter` sem timeout fixo (conexão de vida longa) e o registra por `userId` em
  `SpringSessionLiteSseRegistry`. Envia `X-Accel-Buffering: no` para reverse proxies não
  "bufferizarem" o stream.
- **`SessionEventBroadcaster`** — abstração por trás de **todo** envio SSE (`logout`, `renew`,
  `warning`, `pingAll`); nem o controller nem a idle-watch chamam `SseEmitter#send` diretamente.
  `InMemorySessionEventBroadcaster` (backed por `SpringSessionLiteSseRegistry`) é a implementação
  padrão, single-instance. **É o ponto de extensão** para um hub horizontal (múltiplas instâncias,
  backed por pub/sub — ex. Redis) — troque o bean (`@ConditionalOnMissingBean`) para que um push
  originado numa instância alcance emitters conectados a outra; essa implementação está fora do
  escopo desta fase, só o *seam* existe.
- **`SpringSessionLiteIdleWatchTask`** — `@Scheduled(fixedDelay = 10_000)` (fixo, sem property
  própria), varre `store.findActive(agora)` e, por sessão: destrói via
  `sessionService.logout(sessionId)` quando idle **ou** absoluto expirou (reaproveitando o método
  existente, então o mesmo listener que empurra `logout` no `POST /session/logout` cobre também as
  sessões descobertas aqui); senão empurra `warning` quando dentro de `warning-before` de qualquer
  um dos dois prazos (não existe evento de domínio para "prestes a expirar", então é o único caso
  em que a task empurra diretamente). Todo tick também chama `broadcaster.pingAll()` — keep-alive
  para as conexões não caírem por proxy/load balancer.
  > Limitação conhecida: a varredura só enxerga expiração absoluta sendo cruzada na janela entre
  > dois ticks (candidatos vêm de `expires_at > agora`); em escala, quem garante a limpeza é a
  > `SpringSessionLiteCleanupTask` já existente (`cleanup-enabled`), que **não** empurra SSE — uma
  > sessão varrida por ela em vez da idle-watch não é notificada. Aceito para esta fase (hub de
  > instância única).
- **`SpringSessionLiteSseSessionEventListener`** — ponte entre os eventos de domínio
  (`SessionDestroyedEvent`/`SessionRenewedEvent`) e o broadcaster, empurrando `logout`/`renew`
  **imediatamente** (não só no próximo tick da idle-watch), para `POST /session/logout|renew`, a
  própria idle-watch, ou qualquer chamador futuro — sem que o código que dispara o evento precise
  saber que SSE existe.

Payloads: `SpringSessionLiteSseLogoutEvent(sessionId)`,
`SpringSessionLiteSseRenewEvent(sessionId, absoluteRemainingMs, idleRemainingMs)`,
`SpringSessionLiteSseWarningEvent(sessionId, remainingMs, absoluteRemainingMs, idleRemainingMs, cause)`
(`cause` = `"idle"` ou `"absolute"`, o prazo mais próximo).

---

## 13. Limpeza de sessões expiradas

`SpringSessionLiteCleanupTask` (`@Scheduled`, cron configurável) faz
`DELETE WHERE expires_at < agora`. Registrada apenas com `cleanup-enabled=true` (default), que
também ativa o `@EnableScheduling` interno — sem forçar scheduling global quando desligado.

---

## 14. Extensibilidade

- **`SpringSessionLiteSessionStore`** — troque o backend (Redis/Mongo) fornecendo seu próprio
  bean (`@ConditionalOnMissingBean`).
- **Eventos** — consuma `@EventListener` para auditoria/observabilidade (inclui
  `SpringSessionLiteSessionRenewedEvent` desde a 2.1).
- **`SessionEventBroadcaster`** — ponto de extensão para push SSE horizontal (pub/sub), ver §12.
- **Beans condicionais** — todos os beans são `@ConditionalOnMissingBean`, então qualquer peça
  pode ser sobrescrita.

---

## 15. Decisões de projeto

| Decisão | Motivo |
|---------|--------|
| NanoID inline com `SecureRandom` | Zero dependências extras. |
| `@AutoConfigurationPackage` (não `@EntityScan`) | Scan aditivo, sem quebrar a aplicação. |
| Filtro dentro da cadeia do Security | Contexto antes da cadeia não sobrevive no Security 6. |
| Filtro não emite 401 | Evita lock-out de re-login em rotas permit-all. |
| `ResponseCookie` | Única API com suporte a `SameSite`. |
| HMAC-SHA256 do IP com salt | Não guardar IP em texto puro; resistir a rainbow tables. |
| `SessionStore` como interface | Backend de sessão substituível. |
| Throttle de `last_accessed_at` | Evita escrita no banco a cada requisição. |
| `max-idle` default `0` (desativado) | Recurso aditivo/opt-in — nenhum comportamento existente muda. |
| Throttle efetivo `min(last-accessed-throttle, max-idle / 2)` | Sem isso, um throttle alto atrasaria a própria detecção de inatividade. |
| Endpoints/SSE em `@AutoConfiguration` separadas | Consumidor liga por property, sem escrever controller. |
| `SessionEventBroadcaster` como interface | Push SSE trocável por implementação horizontal (pub/sub). |
| Idle-watch com cadência fixa de 10s (sem property) | `warning-before`/`max-idle` são medidos em minutos; 10s é granularidade suficiente. |
