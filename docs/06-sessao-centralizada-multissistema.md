# Spring Session Lite — Sessão Centralizada Multissistema

Como usar esta biblioteca como **hub de inatividade** de uma plataforma com várias aplicações
(múltiplas UIs, um único backend de sessão). Cobre o modelo de arquitetura, o contrato dos
endpoints/SSE (introduzidos na 2.1.0, ver [`03-como-funciona.md`](./03-como-funciona.md) §10-12),
o checklist de adesão do consumidor e a ressalva de origin em dev vs. produção.

> Esta lib documenta aqui **apenas o seu próprio papel** (o hub). O pacote npm cliente e as
> integrações em outras aplicações (ElevaDocs, messaging, etc.) vivem em outros repositórios e
> estão fora do escopo deste documento — o que segue é o contrato que eles consomem.

---

## 1. O modelo: hub como autoridade de inatividade

Numa plataforma com várias aplicações (várias UIs), cada uma tradicionalmente controla sua
própria inatividade (`react-idle-timer` ou similar) e nenhuma sabe da atividade nas outras — um
usuário pode ficar "ativo" numa aba enquanto expira silenciosamente em outra.

A partir da 2.1.0, esta biblioteca pode atuar como **hub único**: um relógio autoritativo de
inatividade e de expiração absoluta por sessão, que toda UI integrada alimenta (heartbeat) e
escuta (SSE).

```
UI A ──POST /session/heartbeat──►┐
UI B ──GET  /session/status─────►├─► hub (esta lib) ─► spring_session_lite_sessions
UI C ──POST /session/renew──────►┘        │
                                           └─SSE──► warning / logout / renew ──► todas as UIs conectadas
```

Atividade em **qualquer** sistema integrado mantém a sessão viva (qualquer requisição autenticada
já toca `last_accessed_at`; o heartbeat existe para UIs sem tráfego HTTP constante). Inatividade
em **qualquer lugar** encerra a sessão para todos (idle-watch server-side + push SSE) — não é
preciso que cada UI reimplemente sua própria janela de inatividade.

### 1.1. Contrato dos endpoints (base path `spring-session-lite.endpoints-base-path`, default `/session`)

| Rota | Habilitada por | Auth | Papel no hub |
|------|----------------|------|--------------|
| `GET /status` | `endpoints-enabled=true` | permit-all | Echo de configuração (tempos + URLs) + ms restantes (absoluto/idle) + dados do usuário, se autenticado. Nenhuma UI deve hardcodar 10/30 min — tudo vem daqui. |
| `POST /heartbeat` | `endpoints-enabled=true` | requer sessão | Atividade de qualquer UI conectada atualiza o `last_accessed_at` central (via o filtro de autenticação, que já roda antes do controller). |
| `POST /renew` | `endpoints-enabled=true` | requer sessão | "Voltar para o hub" — reinicia a janela absoluta **e** a de inatividade. |
| `POST /logout` | `endpoints-enabled=true` | requer sessão | Single Log-Out (SLO): encerra a sessão central; o listener SSE (abaixo) propaga `logout` para as demais UIs conectadas. |
| `GET /stream` (SSE) | `sse-enabled=true` | requer sessão | Empurra `warning`/`logout`/`renew` para todas as conexões abertas daquele usuário, assim que o idle-watch ou uma das rotas acima dispara o evento. |

Detalhes de implementação (fluxo interno, throttle, limitações da varredura idle-watch) estão em
[`03-como-funciona.md`](./03-como-funciona.md) §10-12 — este documento foca no contrato exposto ao
consumidor, não na implementação.

---

## 2. Propriedades relevantes

Subconjunto de [`02-configuracao-application-properties.md`](./02-configuracao-application-properties.md)
específico deste modelo (a lista completa de propriedades, incluindo as pré-existentes de cookie/
CORS/limpeza, está lá):

| Propriedade | Tipo | Padrão | Descrição |
|-------------|------|--------|-----------|
| `max-idle` | `Duration` | `0` (desativado) | Janela de inatividade. `0`/ausente preserva o comportamento pré-2.1 (só expiração absoluta). |
| `heartbeat-interval` | `Duration` | `60s` | Intervalo sugerido para o client enviar heartbeat. Config-echo — a lib não impõe. |
| `status-poll-interval` | `Duration` | `30s` | Intervalo sugerido para o client consultar `/status` (fallback quando SSE não está disponível). |
| `warning-before` | `Duration` | `60s` | Quanto tempo antes da expiração avisar o usuário — também o limiar que o idle-watch usa para o evento SSE `warning`. |
| `login-url` | `String` | _(vazio)_ | URL de (re)autenticação, ecoada no `/status`. |
| `logout-url` | `String` | _(vazio)_ | URL de logout explícito, ecoada no `/status`. |
| `redirect-after-expiry-url` | `String` | _(vazio)_ | URL de redirecionamento após expiração; cai para `login-url` quando vazia (fallback do client). |
| `endpoints-enabled` | `boolean` | `false` | Liga o controller `/session/status\|heartbeat\|renew\|logout`. |
| `endpoints-base-path` | `String` | `/session` | Base path dos endpoints (REST e SSE). |
| `sse-enabled` | `boolean` | `false` | Liga o stream `GET /session/stream`. |

Todas são **aditivas e desligadas por padrão** — é por isso que a 2.1.0 é um bump `MINOR`, não
`MAJOR` (ver [`MIGRATION.md`](../MIGRATION.md)).

---

## 3. Checklist de adesão do consumidor

### 3.1. Backend (aplicação que já usa `spring-session-lite`)

**Nenhuma mudança é obrigatória** — a aplicação continua com sua própria sessão exatamente como
antes. Para aderir ao modelo de hub centralizado, adicione (opt-in):

```properties
spring-session-lite.max-idle=15m
spring-session-lite.warning-before=60s
spring-session-lite.endpoints-enabled=true
spring-session-lite.sse-enabled=true
spring-session-lite.login-url=https://hub.exemplo.com/login
spring-session-lite.logout-url=https://hub.exemplo.com/logout
spring-session-lite.redirect-after-expiry-url=https://hub.exemplo.com/login?expired=1
```

> Valores acima são ilustrativos — ajuste `max-idle`/`warning-before` à política de inatividade
> da sua plataforma, e as três URLs ao domínio real do hub.

### 3.2. Frontend (cada UI que deve reagir à sessão do hub)

```bash
npm i @media4all/spring-session-lite-client
```

> **Este pacote é publicado em outro repositório e ainda não existe neste momento do rollout**
> (é a Fase 4 do plano, posterior a esta tarefa). O que segue documenta a **API esperada** — o
> contrato que o pacote deve expor quando publicado — não uma implementação incluída nesta lib.

```tsx
<SessionGuard hubBase={HUB_API} appLogoutUrl="/auth/logout" />
```

- Monta o componente uma vez por UI; ele deve consumir `GET <hubBase>/session/status` (bootstrap +
  fallback via `status-poll-interval`), abrir `GET <hubBase>/session/stream` (SSE) e reagir a
  `warning` (mostrar aviso), `logout` (redirecionar para `appLogoutUrl`/`redirect-after-expiry-url`)
  e `renew` (atualizar o countdown local).
- Ligue o botão de "continuar conectado"/retorno ao `renew()` do client, que deve chamar
  `POST <hubBase>/session/renew` — é essa chamada que reinicia a janela no hub e propaga `renew`
  via SSE para as demais UIs abertas do mesmo usuário.

---

## 4. Ressalva conhecida — origin em dev vs. produção

- **Em produção**, todas as UIs e o hub normalmente rodam atrás do **mesmo reverse proxy**
  (nginx/load balancer) como uma única origem. O heartbeat/status é *same-origin* e o cookie do
  hub (`SLSID`) trafega automaticamente em toda requisição, sem configuração extra de CORS.
- **Em desenvolvimento local**, cada UI costuma rodar numa porta diferente do hub
  (ex. `localhost:3000` vs. `localhost:8080`) — o heartbeat/status passam a ser *cross-origin*.
  Isso exige `cors-enabled=true` + `cors-allowed-origins` configurado no hub e
  `credentials: 'include'`/equivalente no client; e o cookie do hub cruzando origens exige
  `cookie-same-site=None`, que por sua vez **exige `Secure` (HTTPS)** — o que normalmente não
  existe em `localhost`. Na prática, o cookie do hub pode simplesmente não trafegar em dev local
  sem HTTPS.

Isto é documentado aqui como uma **ressalva conhecida**, não como algo resolvido nesta tarefa —
nenhuma mudança de código foi feita para contorná-la. Times integrando UIs locais devem levar isso
em conta (ex. HTTPS local via proxy de desenvolvimento, ou aceitar que o fluxo cross-origin só é
plenamente validado em produção/staging).
