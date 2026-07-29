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
│   └── controller/ SpringSessionLiteSessionController — /session/status|heartbeat|renew|logout (opt-in)
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
       └─ válida ─► popula SecurityContext (roles → authorities) + segue (SEM touch)
```

> **Decisão de design (correção):** o filtro **não** emite 401 diretamente. Um cookie expirado
> jamais bloqueia rotas `permit-all` (como o próprio `/login`), evitando lock-out de re-login.

### 6.1. Por que dentro da cadeia do Security?

No Spring Security 6 a cadeia começa com `SecurityContextHolderFilter`, que carrega/limpa o
contexto. Autenticar num filtro servlet **antes** da cadeia seria sobrescrito. Por isso o filtro
é adicionado via `addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)`.

### 6.2. `touch()` — escrita controlada

`validate()` **não** chama `touch()`: validar uma sessão não é atividade do usuário. O filtro roda
em toda requisição autenticada e não distingue um clique do usuário do `GET /session/status` que o
próprio client dispara a cada `status-poll-interval` (30s) — se validar contasse como atividade, o
poll renovaria `last_accessed_at` para sempre e `max-idle` (§10) nunca fecharia. Foi exatamente o
bug corrigido em 2.1.1.

A atividade é sinalizada de forma **explícita e exclusiva** por `POST /session/heartbeat`, que o
client dispara a partir de eventos reais de DOM. Só esse endpoint chama
`SpringSessionLiteService.touch(sessionId)`.

`touch()` **sempre** escreve `last_accessed_at` — sem throttle. O throttle
(`last-accessed-throttle`) existia quando toda requisição caía aqui e ele era o que evitava um
UPDATE por requisição; agora só o heartbeat chega, e o client já o limita a `heartbeat-interval`.
Throttlar de novo no servidor só descartava atividade real e deslogava usuário ativo (corrigido em
2.1.2; a propriedade virou no-op). Com `sliding-expiration=true`, o `expires_at` também é estendido.
`update-last-accessed=false` desliga a escrita — **exceto** quando `max-idle` está habilitado (§10),
caso em que `last_accessed_at` continua sendo escrito porque o idle-check depende dele.

> **Consequência:** habilitar `max-idle` sem um client enviando heartbeat faz a sessão expirar por
> inatividade mesmo com o usuário usando a aplicação. Use `@media4all/spring-session-lite-client`
> ou envie o heartbeat por conta própria.

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
- `logoutAll(userId)` — revoga todas as sessões do usuário (ex.: troca de senha). **Não publica
  eventos.** Abas abertas descobrem no próximo poll de status, como em qualquer outra revogação.

---

## 10. Inatividade e renovação (`max-idle` / `renew()`)

### 10.1. Idle-check em `validate()`

`validate()` **avalia** a inatividade mas não a reinicia — quem reinicia é `touch()`, chamado só
pelo `POST /session/heartbeat` (§6.2). Essa separação é o que torna a janela alcançável: o client
observa a sessão em background (poll de `/status`) e essas requisições não podem contar como
atividade.

Além da expiração absoluta (`expires_at`), `validate()` aplica um segundo corte quando
`max-idle` está habilitado (`Duration` diferente de `null`/zero/negativo):

```
reference = last_accessed_at (ou created_at, se nunca houve acesso)
reference + max-idle < agora  ─►  sessão tratada como inválida (Optional.empty()), igual à expiração absoluta
```

`0`/ausente (default) desativa o idle-check inteiramente, preservando o comportamento anterior à
2.1 — nenhum consumidor existente é afetado até configurar `max-idle` explicitamente.

`touch()` escreve `last_accessed_at` mesmo com `update-last-accessed=false`, porque o idle-check
depende desse timestamp para funcionar.

**Relação com `heartbeat-interval`:** o heartbeat é a única coisa que reinicia a janela, e o client
o limita a `heartbeat-interval`. Logo, um usuário ativo só consegue provar atividade uma vez por
intervalo — se `heartbeat-interval` chegar perto de `max-idle`, ele é deslogado mesmo trabalhando.
Mantenha `heartbeat-interval` bem abaixo de `max-idle` (um quarto ou menos); a lib avisa no startup
quando estão perto demais.

**Relação com `warning-before`:** precisa ser menor que `max-idle`. Igual ou maior faz a condição
do warning (`idleRemaining <= warning-before`) ser verdadeira desde o início da sessão, e o modal
aparece já no login. A lib também avisa nesse caso.

### 10.2. `renew()` — "voltar para o hub"

`SpringSessionLiteService.renew(sessionId)` reinicia **as duas** janelas na mesma chamada:

- `expires_at = agora + ttl` (expiração absoluta);
- `last_accessed_at = agora` (janela de inatividade).

Publica
`SpringSessionLiteSessionRenewedEvent(userId, sessionId, agora, absoluteRemainingMs, idleRemainingMs)`
— desde a 2.3.0 o evento carrega o tempo restante calculado aqui, sobre a linha que o serviço acabou
de salvar, para que nenhum listener precise reler a mesma linha um quadro depois (o construtor de
três argumentos continua existindo e deixa o snapshot nulo). A sobrecarga
`renew(request, response)` lê o `session_id` do cookie — sem cookie, devolve `Optional.empty()`
sem tocar o banco — e delega a `renew(sessionId)`, que só falha (`Optional.empty()`) se o registro
já não existir mais no store (ex.: apagado por um logout/limpeza concorrente); ao
contrário de `validate()`, não reavalia `expires_at`/idle antes de renovar. Em caso de sucesso,
reescreve o cookie (`cookieManager.write`, atualizando o `Max-Age`). Na prática, via o endpoint
`POST /session/renew` (§11), essa distinção raramente importa: o filtro de autenticação já exige
uma sessão válida (idle/absoluta) para a requisição chegar ao controller.

`SpringSessionLiteService.remaining(sessionId)` é uma leitura **sem** efeitos colaterais (não
chama `touch()`/`validate()`) que devolve `SpringSessionLiteSessionRemaining` (`absoluteRemainingMs`,
`idleRemainingMs` — este último `null` quando `max-idle` está desativado). É a base dos endpoints
de status/heartbeat/renew (§11).

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
| `POST /heartbeat` | requer sessão | **O único sinal de atividade da lib.** Chama `sessionService.touch(sessionId)` — reiniciando a janela de inatividade — e reporta o status resultante. O client dispara a partir de eventos reais de DOM, throttled em `heartbeat-interval`. |
| `POST /renew` | requer sessão | Delega a `sessionService.renew(request, response)`; `401` (`{"error":"unauthorized",...}`) na rara corrida de a sessão ter sido apagada entre o filtro e o controller. |
| `POST /logout` | requer sessão | Delega a `sessionService.logout(request, response)`; `204 No Content`. |

> O `permit-all` de `/status` é ligado no chain de segurança já existente
> (`SpringSessionLiteAutoConfiguration`), não nesta autoconfiguração nova — mutar
> `permit-all-paths` a partir de uma segunda `@AutoConfiguration` dependeria de ordem de
> instanciação entre autoconfigurações distintas, o que `before=`/`after=` não garante.

---

## 12. Limpeza de sessões expiradas

`SpringSessionLiteCleanupTask` (`@Scheduled`, cron configurável) faz
`DELETE WHERE expires_at < agora`. Registrada apenas com `cleanup-enabled=true` (default), que
também ativa o `@EnableScheduling` interno — sem forçar scheduling global quando desligado.

---

## 13. Extensibilidade

- **`SpringSessionLiteSessionStore`** — troque o backend (Redis/Mongo) fornecendo seu próprio
  bean (`@ConditionalOnMissingBean`).
- **Eventos** — consuma `@EventListener` para auditoria/observabilidade (inclui
  `SpringSessionLiteSessionRenewedEvent` desde a 2.1).
- **Beans condicionais** — todos os beans são `@ConditionalOnMissingBean`, então qualquer peça
  pode ser sobrescrita.

---

## 14. Decisões de projeto

| Decisão | Motivo |
|---------|--------|
| NanoID inline com `SecureRandom` | Zero dependências extras. |
| `@AutoConfigurationPackage` (não `@EntityScan`) | Scan aditivo, sem quebrar a aplicação. |
| Filtro dentro da cadeia do Security | Contexto antes da cadeia não sobrevive no Security 6. |
| Filtro não emite 401 | Evita lock-out de re-login em rotas permit-all. |
| `ResponseCookie` | Única API com suporte a `SameSite`. |
| HMAC-SHA256 do IP com salt | Não guardar IP em texto puro; resistir a rainbow tables. |
| `SessionStore` como interface | Backend de sessão substituível. |
| `validate()` não registra atividade | O client observa a sessão sozinho (poll de `/status`); se observar contasse como atividade, `max-idle` nunca fecharia. Só o heartbeat conta. |
| `touch()` sem throttle | O client já limita o heartbeat a `heartbeat-interval`; throttlar de novo descartava o único sinal de atividade e deslogava usuário ativo. |
| `max-idle` default `0` (desativado) | Recurso aditivo/opt-in — nenhum comportamento existente muda. |
| Throttle efetivo `min(last-accessed-throttle, max-idle / 2)` | Sem isso, um throttle alto atrasaria a própria detecção de inatividade. |
| Endpoints em `@AutoConfiguration` separada | Consumidor liga por property, sem escrever controller. |
| Sem push por servidor (SSE removido na 3.0.0) | Um `SseEmitter` sem timeout custa uma conexão TCP permanente por aba aberta, e seu registry em memória prende o hub a uma réplica só. O poll de `/status` já carrega `idle/absoluteRemainingMs`, então o aviso e o logout saem dele; o push só entregava latência. |
