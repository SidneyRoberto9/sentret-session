# Sentret — Configuração (`application.properties`)

Todas as propriedades usam o prefixo `sentret` (12 no núcleo, 6 do hub) e **todas têm padrão**: a lib funciona sem nenhuma
configuração.

---

## 1. Núcleo

| Propriedade | Tipo | Padrão | Descrição |
|---|---|---|---|
| `sentret.enabled` | `boolean` | `true` | Desliga a lib inteira. |
| `sentret.create-table` | `boolean` | `true` | Cria a tabela `sentret_sessions` no startup quando ela não existe (com os índices e a collation case-sensitive do banco). `false` quando o schema é do Flyway/Liquibase ou o usuário do banco não tem `CREATE TABLE`: aí a falta da tabela só gera um ERROR no log. |
| `sentret.ttl` | `Duration` | `8h` | Tempo de vida absoluto da sessão. |
| `sentret.max-idle` | `Duration` | `30m` | Janela de inatividade, renovada **só** pelo heartbeat do hub. **Só é aplicada com `hub.enabled=true`** (sem hub ninguém manda heartbeat; vale só o `ttl`). `0` desliga. |
| `sentret.cookie-name` | `String` | `SENTRETSID` | Nome do cookie. Use `__Host-SID` para endurecer o cookie. |
| `sentret.cookie-secure` | `boolean` | `true` | Cookie só em HTTPS. `false` apenas em dev local sobre HTTP. |
| `sentret.cookie-same-site` | `String` | `Lax` | `Lax`, `Strict` ou `None`. |
| `sentret.cookie-domain` | `String` | — | Compartilha o cookie entre subdomínios (ex.: `meusite.com`). |
| `sentret.csrf-enabled` | `boolean` | `false` | CSRF na cadeia padrão, no formato de SPA: o front lê o cookie `XSRF-TOKEN` e devolve o valor no header `X-XSRF-TOKEN`. |
| `sentret.csrf-ignored-paths` | `List<String>` | vazio | Caminhos isentos de CSRF com `csrf-enabled=true` — tipicamente o logout da app que o client npm chama sem o token. `heartbeat`/`renew` do hub já são isentos. |
| `sentret.cors-allowed-origins` | `List<String>` | vazio | Origens liberadas, com credenciais; aceita padrões como `https://*.meusite.com`. **O CORS liga sozinho quando a lista não está vazia.** |
| `sentret.permit-all-paths` | `List<String>` | `/login`, `/auth/**`, `/public/**`, `/actuator/health/**` | Rotas públicas da cadeia padrão (o health fica público para os probes). |

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
- `cookie-same-site=None` com `cookie-secure=false` (o navegador descarta o cookie e nenhuma sessão chega);
- `hub.heartbeat-interval` ≥ metade de `max-idle` (usuário ativo pode ser deslogado);
- `hub.status-poll-interval` ≥ `hub.warning-before` (o aviso pode nunca aparecer);
- `hub.warning-before` ≥ `max-idle` (o aviso aparece logo no login).

## 4. Exemplos

**Produção, mesma origem:**

```properties
sentret.ttl=4h
```

**Produção, front em outro subdomínio do mesmo site** (`app.meusite.com` → `api.meusite.com`):

```properties
sentret.ttl=4h
sentret.cors-allowed-origins=https://app.meusite.com
```

Subdomínios do mesmo domínio são o mesmo *site*, então o `SameSite=Lax` padrão basta.

**Produção, front em outro site** (`app.empresa.com` → `api.outra.com`):

```properties
sentret.cookie-same-site=None
sentret.cors-allowed-origins=https://app.empresa.com
```

`SameSite=None` exige `Secure` (padrão) e deixa o cookie ir em requests de qualquer site, por isso
a lib avisa no startup sem CSRF. Com `csrf-enabled=true`, o front só lê o cookie `XSRF-TOKEN` se UI e
API dividirem um domínio (`cookie-domain`); entre sites diferentes, proteja as rotas de escrita de
outra forma.

**Desenvolvimento local (HTTP):**

```properties
sentret.cookie-secure=false
sentret.cors-allowed-origins=http://localhost:3000
```

**Hub de inatividade (eleva-docs):** (o eleva-docs usa `SameSite=None` sem `csrf-enabled`, então a
lib loga o aviso de CSRF no startup — é esperado nessa configuração)

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
