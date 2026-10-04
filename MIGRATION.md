# Migração — spring-session-lite 3.x → Sentret 1.0.0

## 1. Dependência

```xml
<artifactId>sentret-session</artifactId>
<version>1.0.0</version>
```

A app precisa de `JdbcTemplate` (`spring-boot-starter-jdbc` ou `spring-boot-starter-data-jpa`).

## 2. Banco

Criar a tabela nova com `db/sentret-schema.sql` (ver README). A antiga pode ser apagada
(`DROP TABLE spring_session_lite_sessions`): todo usuário faz login de novo uma vez.

## 3. Propriedades

| Antes (`spring-session-lite.*`) | Agora (`sentret.*`) |
|---|---|
| `ttl`, `max-idle`, `cookie-name`, `cookie-secure`, `cookie-same-site`, `cookie-domain`, `csrf-enabled`, `cors-allowed-origins`, `permit-all-paths`, `enabled` | mesmo nome |
| `endpoints-enabled`, `endpoints-base-path` | `hub.enabled`, `hub.base-path` |
| `heartbeat-interval`, `status-poll-interval`, `warning-before`, `login-url` | `hub.heartbeat-interval`, `hub.status-poll-interval`, `hub.warning-before`, `hub.login-url` |
| `ip-hash-salt`, `trust-forwarded-for`, `trusted-proxy-count` | removidas (sem vínculo com IP) |
| `cookie-prefix` | removida: use `cookie-name=__Host-SID` |
| `cookie-path` | removida: sempre `/` |
| `session-id-length` | removida: ID fixo de 20 caracteres |
| `update-last-accessed`, `sliding-expiration`, `last-accessed-throttle` | removidas |
| `cors-enabled`, `cors-allowed-methods`, `cors-allow-credentials` | removidas: CORS liga sozinho com `cors-allowed-origins` |
| `cleanup-enabled`, `cleanup-cron` | removidas: limpeza acontece no login |
| `logout-url`, `redirect-after-expiry-url` | removidas: use `hub.login-url` |
| `max-idle` padrão `0` | padrão agora é `30m`, aplicado **só com `hub.enabled=true`**; sem hub vale apenas o `ttl` |

## 4. Código

| Antes | Agora |
|---|---|
| `io.github.sidneyroberto9.spring_session_lite.*` / `SpringSessionLite*` | `io.github.sidneyroberto9.sentret.*` / `Sentret*` |
| `login(userId, email, request, response)` / `login(userId, email, roles, request, response)` | `login(userId, email, response)` |
| `SpringSessionLiteUser.roles()` | removido: busque as roles na sua app pelo `userId` |
| `@SpringSessionLiteCurrentSession SpringSessionLiteUser user` | `@AuthenticationPrincipal SentretUser user` |
| `validate(sessionId, request)` | `validate(sessionId)` |
| `deleteExpired()`, `remaining(...)` | removidos |
| `SessionRenewedEvent(userId, sessionId, renewedAt, absoluteRemainingMs, idleRemainingMs)` | `SentretSessionRenewedEvent(userId, sessionId, renewedAt)` |
| `SessionDestroyedEvent(sessionId)` | `SentretSessionDestroyedEvent(userId, sessionId)` |
| `SpringSessionLiteSessionStore` (`save`, …) | `SentretSessionStore` (`insert`, `updateLastAccessedAt`, `updateExpiresAt`, …) |

## 5. Comportamento

- **Cookie padrão:** `SLSID` → `SENTRETSID` (quem já define `cookie-name` não é afetado).
- **Agendamento:** a lib não liga mais `@EnableScheduling`. Se sua app tem `@Scheduled` próprios e
  nunca declarou `@EnableScheduling`, eles param — adicione `@EnableScheduling` na sua app.
- **401:** sem corpo (antes `{"error":"unauthorized",...}`).
- **Troca de IP** não derruba mais a sessão.
- **Hub:** `POST /logout` não existe mais (o client nunca usou). O `config` do status traz só
  `heartbeatIntervalMs`, `statusPollIntervalMs`, `warningBeforeMs` e `loginUrl`.
- **`renew`** não ressuscita sessão expirada (nem por inatividade).

---


# Migração — 1.0.x → 2.0.0

A versão **2.0.0** contém mudanças **breaking** em relação a `1.0.0`/`1.0.1` (publicadas no
Maven Central). Este guia cobre o que muda e como migrar com segurança.

> Resumo: rename de classes/tabela/cookie, `SpringSessionLiteUser` virou `record`, e vários
> recursos novos (logout, roles, store, CORS/CSRF, sliding, eventos). A maioria dos recursos é
> aditiva; as quebras estão em **nomes** (classes, tabela, cookie) e no **principal**.

---

## 2.1.x → 2.2.0

**Nada a fazer** se você só consome a lib (inclusive com `sse-enabled=true`): o roteamento dos
eventos SSE foi corrigido, sem mudança de configuração nem de banco. Você ganha de graça o fim do
modal de inatividade fantasma e do logout de usuário ativo quando o mesmo usuário tem mais de uma
sessão (dois dispositivos, dois browsers, ou uma sessão órfã).

**Só precisa agir quem implementa `SessionEventBroadcaster`** (o seam para um hub pub/sub
horizontal). O código não vai compilar, de propósito:

| Antes | Agora |
|---|---|
| `void logout(String userId, SpringSessionLiteSseLogoutEvent event)` | `void sendLogout(String sessionId, SpringSessionLiteSseLogoutEvent event)` |
| `void renew(String userId, SpringSessionLiteSseRenewEvent event)` | `void sendRenew(String sessionId, SpringSessionLiteSseRenewEvent event)` |
| `void warning(String userId, SpringSessionLiteSseWarningEvent event)` | `void sendWarning(String sessionId, SpringSessionLiteSseWarningEvent event)` |
| `registry.emittersFor(userId)` | `registry.emittersForSession(sessionId)` |
| `registry.connectedUserIds()` | `registry.connectedSessionIds()` |

> **Não renomeie seu override de volta.** A quebra de compilação é o aviso: o `String` mudou de
> **significado**, não só de nome. Ele agora é um `sessionId`, e sua implementação precisa entregar
> **apenas** às conexões autenticadas por aquela sessão exata. Publicar para todas as conexões do
> usuário é exatamente o bug que a 2.2.0 corrige — desloga quem está trabalhando. Se o seu
> broadcaster publica num tópico pub/sub, o tópico passa a ser por sessão.

`pingAll()` não mudou (assinatura nem semântica): cada emitter tem exatamente uma chave, então
re-chavear só reparticiona o mesmo conjunto — todo emitter vivo continua recebendo um ping por tick.

---

## 2.0.0 → 2.1.0 (opcional, sem quebras)

A versão **2.1.0** é um bump **MINOR**: tudo é aditivo e desligado por padrão
(`max-idle=0`/desativado, `endpoints-enabled=false`, `sse-enabled=false`). **Não há passo
obrigatório** — quem já usa `2.0.0` continua funcionando sem tocar em nada.

O que a 2.1.0 adiciona (todo opt-in): inatividade central (`max-idle`) com `renew()` no
`SpringSessionLiteService`; endpoints REST `/session/status|heartbeat|renew|logout`
(`endpoints-enabled=true`); stream SSE `/session/stream` (`sse-enabled=true`) com eventos
`warning`/`logout`/`renew`; e as propriedades de config-echo para o frontend
(`heartbeat-interval`, `status-poll-interval`, `warning-before`, `login-url`, `logout-url`,
`redirect-after-expiry-url`).

Para **optar** pelos novos recursos (uso como hub de sessão centralizado numa plataforma com
várias aplicações), siga o checklist completo de adesão — propriedades de backend e o contrato
esperado do lado frontend — em
[`docs/06-sessao-centralizada-multissistema.md`](docs/06-sessao-centralizada-multissistema.md).
Referência de todas as propriedades novas:
[`docs/02-configuracao-application-properties.md`](docs/02-configuracao-application-properties.md).

Ver também o [`CHANGELOG.md`](CHANGELOG.md) para a lista completa de mudanças da 2.1.0.

---

## 1. Banco de dados — rename da tabela e índices (obrigatório)

A tabela passou de `spring_lite_sessions` para `spring_session_lite_sessions`, os índices foram
renomeados e há uma nova coluna `roles`.

### MySQL / MariaDB
```sql
RENAME TABLE spring_lite_sessions TO spring_session_lite_sessions;

ALTER TABLE spring_session_lite_sessions ADD COLUMN roles VARCHAR(255) NULL;

ALTER TABLE spring_session_lite_sessions
    RENAME INDEX idx_spring_lite_sessions_session_id TO idx_spring_session_lite_sessions_session_id,
    RENAME INDEX idx_spring_lite_sessions_expires_at TO idx_spring_session_lite_sessions_expires_at;

-- índice novo em user_id (logoutAll / revogação por usuário)
CREATE INDEX idx_spring_session_lite_sessions_user_id ON spring_session_lite_sessions (user_id);
```

### PostgreSQL
```sql
ALTER TABLE spring_lite_sessions RENAME TO spring_session_lite_sessions;
ALTER TABLE spring_session_lite_sessions ADD COLUMN roles VARCHAR(255);

ALTER INDEX idx_spring_lite_sessions_session_id RENAME TO idx_spring_session_lite_sessions_session_id;
ALTER INDEX idx_spring_lite_sessions_expires_at RENAME TO idx_spring_session_lite_sessions_expires_at;

CREATE INDEX idx_spring_session_lite_sessions_user_id ON spring_session_lite_sessions (user_id);
```

> Com `ddl-auto=update`, o Hibernate cria a tabela/coluna nova, mas **não** migra dados da
> tabela antiga nem remove a velha. Para preservar sessões ativas, rode o `RENAME` acima.
> O DDL completo do zero está em
> [`src/main/resources/db/spring-session-lite-schema.sql`](src/main/resources/db/spring-session-lite-schema.sql).

---

## 2. Cookie — `M4SID` → `SLSID`

O nome padrão do cookie mudou. Sessões com o cookie antigo deixam de ser reconhecidas e os
usuários precisam refazer login.

- **Para evitar logout em massa**, mantenha o nome antigo via configuração:
  ```properties
  spring-session-lite.cookie-name=M4SID
  ```
- Caso aceite o logout único, nada a fazer — os usuários reautenticam.

---

## 3. Rename de classes/anotações (atualize imports)

| 1.0.x | 2.0.0 |
|-------|-------|
| `SpringLiteSession` | `SpringSessionLiteSession` |
| `SpringLiteSessionRepository` | `SpringSessionLiteSessionRepository` |

Os demais nomes (`SpringSessionLiteService`, `SpringSessionLiteUser`,
`@SpringSessionLiteCurrentSession`, `SpringSessionLiteUserService`,
`SpringSessionLiteAuthenticationFilter`) **não mudaram** em relação a `1.0.1`. Se você importava
a entidade/repositório diretamente, ajuste os imports.

---

## 4. `SpringSessionLiteUser` virou `record`

Os acessores mudaram de getters Lombok para componentes de record:

| 1.0.x | 2.0.0 |
|-------|-------|
| `user.getUserId()` | `user.userId()` |
| `user.getEmail()` | `user.email()` |
| `user.getSessionId()` | `user.sessionId()` |
| — | `user.roles()` (novo) |

A serialização JSON (chaves `userId`, `email`, `sessionId`, `roles`) é equivalente — clientes
HTTP não são afetados; apenas código Java que chamava os getters.

---

## 5. Recursos novos (aditivos, sem quebra)

- **Logout/revogação:** `sessionService.logout(req, res)`, `logout(sessionId)`, `logoutAll(userId)`.
- **Roles:** `login(userId, email, roles, req, res)` → authorities `ROLE_*`. A sobrecarga sem
  roles continua válida.
- **Store plugável:** interface `SpringSessionLiteSessionStore` (default JPA); forneça seu bean
  para trocar o backend.
- **Eventos:** `SpringSessionLiteSessionCreatedEvent` / `...DestroyedEvent` via `@EventListener`.
- **Segurança:** `csrf-enabled`, `cors-*`, `trusted-proxy-count` (XFF não-falsificável),
  validador de startup (avisa salt default / `SameSite=None` sem CSRF), `cookie-prefix`.
- **Performance:** `update-last-accessed` + `last-accessed-throttle` (fim do write-por-request),
  `sliding-expiration`.
- **Operacional:** `cleanup-enabled` para desligar só a limpeza.
- `session-id-length` default subiu de **16** para **21** (sessões existentes seguem válidas).

Veja todas as propriedades em
[`docs/02-configuracao-application-properties.md`](docs/02-configuracao-application-properties.md).

---

## 6. Mudança de comportamento — 401 do filtro

Em `1.0.x`, um cookie inválido/expirado fazia o filtro responder **401 e encerrar** a requisição
— bloqueando inclusive rotas `permit-all` (lock-out de re-login). Em `2.0.0` o filtro limpa o
cookie morto e segue anônimo; a autorização decide o 401. Rotas protegidas continuam retornando
401; rotas abertas (como `/login`) passam a funcionar mesmo com cookie morto.

---

## 7. Checklist de migração

- [ ] Rodar o SQL de rename de tabela/índices + coluna `roles` (seção 1).
- [ ] Decidir cookie: aceitar re-login ou fixar `cookie-name=M4SID` (seção 2).
- [ ] Atualizar imports de `SpringLiteSession*` (seção 3).
- [ ] Trocar getters de `SpringSessionLiteUser` por acessores de record (seção 4).
- [ ] Revisar `ip-hash-salt` (aviso de startup se default).
- [ ] `./mvnw clean test` / subir a app e validar login + endpoint protegido.
