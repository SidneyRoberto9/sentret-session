# Spring Session Lite — Configuração

Todas as propriedades usam o prefixo **`spring-session-lite`** e têm valores padrão. Declare
apenas o que quiser sobrescrever.

---

## 1. Tabela de propriedades

| Propriedade | Tipo | Padrão | Descrição |
|-------------|------|--------|-----------|
| `enabled` | `boolean` | `true` | Liga/desliga toda a biblioteca sem remover a dependência. |
| `cookie-name` | `String` | `SLSID` | Nome do cookie que carrega o NanoID da sessão. |
| `cookie-prefix` | `String` | _(vazio)_ | Prefixo do cookie (`__Host-`/`__Secure-`) para endurecimento. `__Host-` exige `cookie-secure=true`, `cookie-path=/` e sem `cookie-domain`. |
| `ttl` | `Duration` | `8h` | Tempo de vida da sessão e `Max-Age` do cookie. |
| `cookie-secure` | `boolean` | `true` | Atributo `Secure` (só trafega via HTTPS). |
| `cookie-same-site` | `String` | `Lax` | `Lax`, `Strict` ou `None`. |
| `cookie-path` | `String` | `/` | Atributo `Path`. |
| `cookie-domain` | `String` | _(vazio)_ | Atributo `Domain`. Vazio = host-only. |
| `session-id-length` | `int` | `21` | Tamanho do NanoID (≈128 bits). |
| `ip-hash-salt` | `String` | _(default interno)_ | Salt do HMAC-SHA256 do IP. **Troque em produção** — aviso no startup se mantido com `cookie-secure=true`. |
| `trust-forwarded-for` | `boolean` | `false` | Usa `X-Forwarded-For`. Habilite só atrás de proxy confiável. |
| `trusted-proxy-count` | `int` | `1` | Nº de proxies confiáveis que acrescentam ao XFF. O IP do cliente é lido na posição `(total - count)` a partir da esquerda — nunca o left-most (falsificável). |
| `update-last-accessed` | `boolean` | `true` | Atualiza `last_accessed_at` em cada validação. |
| `last-accessed-throttle` | `Duration` | `5m` | Intervalo mínimo entre escritas de `last_accessed_at` (evita UPDATE por request). |
| `sliding-expiration` | `boolean` | `false` | Estende `expires_at` na atividade (limitado pelo mesmo throttle). |
| `max-idle` | `Duration` | `0` (desativado) | Janela máxima de inatividade, avaliada em `validate()` contra `last_accessed_at` (ou `created_at`, se nunca houve acesso). `0`/ausente desativa o idle-check, preservando o comportamento pré-2.1. Habilitado, força a atualização de `last_accessed_at` a cada `touch()` (mesmo com `update-last-accessed=false`) e limita o throttle efetivo a `max-idle / 2`, para a detecção de inatividade ficar precisa dentro de metade da janela. |
| `heartbeat-interval` | `Duration` | `60s` | Intervalo sugerido para o frontend enviar `POST /session/heartbeat`. Só eco de config — devolvido em `GET /session/status`, a lib não o impõe. |
| `status-poll-interval` | `Duration` | `30s` | Intervalo sugerido para o frontend consultar `GET /session/status`. Só eco de config. |
| `warning-before` | `Duration` | `60s` | Quanto tempo antes da expiração (idle ou absoluta) avisar o usuário. Ecoado em `/session/status`; com `sse-enabled=true`, também é o limiar que o idle-watch usa para empurrar o evento SSE `warning`. |
| `login-url` | `String` | _(vazio)_ | URL de (re)autenticação, ecoada em `/session/status`. Só eco de config — a lib não redireciona sozinha. |
| `logout-url` | `String` | _(vazio)_ | URL de logout explícito, ecoada em `/session/status`. Só eco de config. |
| `redirect-after-expiry-url` | `String` | _(vazio)_ | URL de redirecionamento após expiração (idle ou absoluta), ecoada em `/session/status`. Cai para `login-url` quando vazia — fallback de responsabilidade do client consumidor, não desta lib. |
| `csrf-enabled` | `boolean` | `false` | Habilita CSRF (token em cookie) no chain padrão. |
| `cors-enabled` | `boolean` | `false` | Habilita CORS no chain padrão (necessário p/ cookie cross-origin). |
| `cors-allowed-origins` | `List<String>` | _(vazio)_ | Origens permitidas. Com credenciais, não use `*`. |
| `cors-allowed-methods` | `List<String>` | `GET,POST,PUT,DELETE,PATCH,OPTIONS` | Métodos permitidos. |
| `cors-allow-credentials` | `boolean` | `true` | `Access-Control-Allow-Credentials`. |
| `cleanup-enabled` | `boolean` | `true` | Registra a task agendada de limpeza (e o `@EnableScheduling` interno). |
| `cleanup-cron` | `String` (cron) | `0 */30 * * * *` | Expressão cron da limpeza. |
| `permit-all-paths` | `List<String>` | `/login, /auth/**, /public/**` | Caminhos liberados (apenas no chain padrão da lib). |
| `endpoints-enabled` | `boolean` | `false` | Registra o controller opt-in `GET /status`, `POST /heartbeat`, `POST /renew`, `POST /logout` (base path em `endpoints-base-path`), via `SpringSessionLiteEndpointsAutoConfiguration`. Desligado por padrão — consumidores existentes não são afetados até habilitar explicitamente. |
| `endpoints-base-path` | `String` | `/session` | Base path dos endpoints opt-in — tanto o controller REST (`endpoints-enabled`) quanto o stream SSE (`sse-enabled`) usam esta mesma propriedade. |
| `sse-enabled` | `boolean` | `false` | Registra a pilha SSE opt-in via `SpringSessionLiteSseAutoConfiguration`: `GET <endpoints-base-path>/stream`, o registry/broadcaster em memória, a varredura idle-watch (`warning`/`logout`) e o listener que empurra `logout`/`renew` imediatamente. Independente de `endpoints-enabled`/`cleanup-enabled`. |

---

## 2. Exemplo — `application.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/minha_app
    username: postgres
    password: secret
  jpa:
    hibernate:
      ddl-auto: update

spring-session-lite:
  cookie-name: MINHA_SESSAO
  ttl: 12h
  cookie-secure: true
  cookie-same-site: Lax
  ip-hash-salt: ${SESSION_IP_SALT}
  trust-forwarded-for: true
  trusted-proxy-count: 1
  sliding-expiration: true
  cleanup-cron: "0 0 * * * *"
  permit-all-paths:
    - /login
    - /auth/**
    - /health
    - /swagger-ui/**
```

---

## 3. Recomendações por ambiente

### Desenvolvimento (HTTP local)
```properties
spring-session-lite.cookie-secure=false
spring-session-lite.trust-forwarded-for=false
spring.jpa.hibernate.ddl-auto=update
```
> `cookie-secure=false` é necessário em `http://localhost`, senão o navegador descarta o cookie.

### Produção (HTTPS, atrás de nginx/load balancer)
```properties
spring-session-lite.cookie-secure=true
spring-session-lite.cookie-same-site=Lax
spring-session-lite.trust-forwarded-for=true
spring-session-lite.trusted-proxy-count=1
spring-session-lite.ip-hash-salt=${SESSION_IP_SALT}
spring.jpa.hibernate.ddl-auto=validate
```
> - **Sempre** defina `ip-hash-salt` forte via variável de ambiente.
> - `trusted-proxy-count` deve refletir quantos proxies confiáveis acrescentam ao `X-Forwarded-For`.
> - Com `ddl-auto=validate`, crie a tabela com o DDL em
>   [`src/main/resources/db/spring-session-lite-schema.sql`](../src/main/resources/db/spring-session-lite-schema.sql).
> - `cookie-same-site=None` exige `csrf-enabled=true` (aviso no startup caso contrário).

---

## 4. Desligar a biblioteca
```properties
spring-session-lite.enabled=false
```
Nenhum bean é registrado (filtro, chain, agendador). Para desligar **apenas** a limpeza:
```properties
spring-session-lite.cleanup-enabled=false
```
