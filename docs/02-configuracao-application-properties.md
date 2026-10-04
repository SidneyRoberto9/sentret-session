# Sentret — Configuração (`application.properties`)

Todas as propriedades usam o prefixo `sentret` e **todas têm padrão**: a lib funciona sem nenhuma
configuração.

---

## 1. Núcleo

| Propriedade | Tipo | Padrão | Descrição |
|---|---|---|---|
| `sentret.enabled` | `boolean` | `true` | Desliga a lib inteira. |
| `sentret.ttl` | `Duration` | `8h` | Tempo de vida absoluto da sessão. |
| `sentret.max-idle` | `Duration` | `30m` | Janela de inatividade, renovada **só** pelo heartbeat do hub. `0` desliga. |
| `sentret.cookie-name` | `String` | `SENTRETSID` | Nome do cookie. Use `__Host-SID` para endurecer o cookie. |
| `sentret.cookie-secure` | `boolean` | `true` | Cookie só em HTTPS. `false` apenas em dev local sobre HTTP. |
| `sentret.cookie-same-site` | `String` | `Lax` | `Lax`, `Strict` ou `None`. |
| `sentret.cookie-domain` | `String` | — | Compartilha o cookie entre subdomínios (ex.: `meusite.com`). |
| `sentret.csrf-enabled` | `boolean` | `false` | CSRF na cadeia padrão (`CookieCsrfTokenRepository`). |
| `sentret.cors-allowed-origins` | `List<String>` | vazio | Origens liberadas, com credenciais. **O CORS liga sozinho quando a lista não está vazia.** |
| `sentret.permit-all-paths` | `List<String>` | `/login`, `/auth/**`, `/public/**` | Rotas públicas da cadeia padrão. |

## 2. Hub de inatividade (`sentret.hub.*`)

Só valem com `sentret.hub.enabled=true` (ver [06](./06-sessao-centralizada-multissistema.md)).

| Propriedade | Tipo | Padrão | Descrição |
|---|---|---|---|
| `sentret.hub.enabled` | `boolean` | `false` | Liga os endpoints `status`, `heartbeat` e `renew`. |
| `sentret.hub.base-path` | `String` | `/session` | Prefixo dos endpoints. O eleva-docs usa `/api/lite/session`. |
| `sentret.hub.heartbeat-interval` | `Duration` | `60s` | Repassado ao client: intervalo mínimo entre heartbeats. |
| `sentret.hub.status-poll-interval` | `Duration` | `30s` | Repassado ao client: intervalo do poll de status. |
| `sentret.hub.warning-before` | `Duration` | `60s` | Repassado ao client: quanto antes da expiração mostrar o aviso. |
| `sentret.hub.login-url` | `String` | — | Repassado ao client: para onde mandar o usuário quando a sessão acaba. |

## 3. Avisos no startup

A lib loga `WARN` (sem impedir o startup) quando:

- `cookie-same-site=None` com CSRF desligado;
- `hub.heartbeat-interval` ≥ metade de `max-idle` (usuário ativo pode ser deslogado);
- `hub.status-poll-interval` ≥ `hub.warning-before` (o aviso pode nunca aparecer);
- `hub.warning-before` ≥ `max-idle` (o aviso aparece logo no login).

## 4. Exemplos

**Produção, mesma origem:**

```properties
sentret.ttl=4h
```

**Produção, front em outro domínio:**

```properties
sentret.ttl=4h
sentret.cors-allowed-origins=https://app.meusite.com
```

**Desenvolvimento local (HTTP):**

```properties
sentret.cookie-secure=false
sentret.cors-allowed-origins=http://localhost:3000
```

**Hub de inatividade (eleva-docs):**

```properties
sentret.cookie-name=DOC_M4A_SESSIONID
sentret.cookie-same-site=None
sentret.max-idle=1h
sentret.hub.enabled=true
sentret.hub.base-path=/api/lite/session
sentret.hub.warning-before=300s
sentret.hub.login-url=https://login.exemplo.com
```

## 5. Propriedades removidas na 1.0.0

| Antes (`spring-session-lite.*`) | Agora |
|---|---|
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

Guia completo em [`../MIGRATION.md`](../MIGRATION.md).
