# Spring Session Lite — Sessão Centralizada Multissistema

Como usar esta biblioteca como **hub de inatividade** de uma plataforma com várias aplicações
(múltiplas UIs, um único backend de sessão). Cobre o modelo de arquitetura, o contrato dos
endpoints (introduzidos na 2.1.0, ver [`03-como-funciona.md`](./03-como-funciona.md) §10-11),
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
consulta (poll de status).

```
UI A ──POST /session/heartbeat──►┐
UI B ──GET  /session/status─────►├─► hub (esta lib) ─► spring_session_lite_sessions
UI C ──POST /session/renew──────►┘
```

Atividade em **qualquer** sistema integrado mantém a sessão viva (qualquer requisição autenticada
já toca `last_accessed_at`; o heartbeat existe para UIs sem tráfego HTTP constante). Inatividade
em **qualquer lugar** encerra a sessão para todos: `validate()` aplica `max-idle` de forma
autoritativa, e cada UI descobre no próximo `GET /status`. Não é preciso que cada UI reimplemente
sua própria janela de inatividade.

### 1.1. Contrato dos endpoints (base path `spring-session-lite.endpoints-base-path`, default `/session`)

| Rota | Habilitada por | Auth | Papel no hub |
|------|----------------|------|--------------|
| `GET /status` | `endpoints-enabled=true` | permit-all | Echo de configuração (tempos + URLs) + ms restantes (absoluto/idle) + dados do usuário, se autenticado. Nenhuma UI deve hardcodar 10/30 min — tudo vem daqui. |
| `POST /heartbeat` | `endpoints-enabled=true` | requer sessão | Atividade de qualquer UI conectada atualiza o `last_accessed_at` central (via o filtro de autenticação, que já roda antes do controller). |
| `POST /renew` | `endpoints-enabled=true` | requer sessão | "Voltar para o hub" — reinicia a janela absoluta **e** a de inatividade. |
| `POST /logout` | `endpoints-enabled=true` | requer sessão | Single Log-Out (SLO): encerra a sessão central; as demais UIs descobrem no próximo `GET /status` (ou instantaneamente, se estiverem no mesmo browser — o client usa `BroadcastChannel`). |

Detalhes de implementação (fluxo interno, throttle) estão em
[`03-como-funciona.md`](./03-como-funciona.md) §10-11 — este documento foca no contrato exposto ao
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
| `status-poll-interval` | `Duration` | `30s` | Intervalo sugerido para o client consultar `/status`. É o único caminho do aviso e do logout, então mantenha abaixo de `warning-before`. |
| `warning-before` | `Duration` | `60s` | Quanto tempo antes da expiração avisar o usuário. O client compara com os `remainingMs` de cada `/status`. |
| `login-url` | `String` | _(vazio)_ | URL de (re)autenticação, ecoada no `/status`. |
| `logout-url` | `String` | _(vazio)_ | URL de logout explícito, ecoada no `/status`. |
| `redirect-after-expiry-url` | `String` | _(vazio)_ | URL de redirecionamento após expiração; cai para `login-url` quando vazia (fallback do client). |
| `endpoints-enabled` | `boolean` | `false` | Liga o controller `/session/status\|heartbeat\|renew\|logout`. |
| `endpoints-base-path` | `String` | `/session` | Base path dos endpoints REST. |

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

- Monta o componente uma vez por UI; ele consome `GET <hubBase>/session/status` no bootstrap e a
  cada `status-poll-interval`, e reage ao que a resposta diz: `remainingMs` dentro de
  `warning-before` mostra o aviso, `authenticated:false` redireciona para
  `appLogoutUrl`/`redirect-after-expiry-url`.
- Ligue o botão de "continuar conectado"/retorno ao `renew()` do client, que deve chamar
  `POST <hubBase>/session/renew` — é essa chamada que reinicia a janela no hub. Abas do mesmo
  browser são avisadas na hora via `BroadcastChannel`; outros browsers, no próximo poll.

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
