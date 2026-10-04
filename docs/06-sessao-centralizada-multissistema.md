# Sentret — Sessão Centralizada Multissistema

Como usar a biblioteca como **hub de inatividade** de uma plataforma com várias aplicações (várias
UIs, um backend de sessão central): o modelo, o contrato dos endpoints, as propriedades e o
checklist de adesão.

> Esta lib documenta só o seu papel (o hub). O client npm `@media4all/session-lite` e as integrações
> nas outras aplicações vivem em outros repositórios.

---

## 1. O modelo: hub como autoridade de inatividade

Cada UI, sozinha, só sabe da atividade dela: o usuário pode estar ativo numa aplicação e expirar em
silêncio em outra. Com o hub, existe **um relógio de inatividade por sessão**, que todas as UIs
alimentam (heartbeat) e consultam (status).

```
UI A ──POST /heartbeat──►┐
UI B ──GET  /status─────►├─► hub (aplicação com sentret.hub.enabled=true) ─► sentret_sessions
UI C ──POST /renew──────►┘
```

- Atividade em **qualquer** UI integrada mantém a sessão viva — mas só o heartbeat (e o renew,
  quando o usuário pede para continuar) grava `last_accessed_at`. O poll de status e as demais
  requests nunca contam como atividade.
- Inatividade além de `max-idle` encerra a sessão para todas as UIs: a validação rejeita a sessão e
  cada UI descobre no próximo status.

### 1.1. Contrato dos endpoints (prefixo `sentret.hub.base-path`, padrão `/session`)

| Rota | Auth | Papel |
|---|---|---|
| `GET /status` | permit-all | Usuário (se autenticado), tempo restante absoluto e de inatividade, e a configuração do client. Não conta como atividade. |
| `POST /heartbeat` | sessão | Único sinal de atividade: um `UPDATE` de `last_accessed_at`. Responde o status atualizado. |
| `POST /renew` | sessão | "Continuar conectado": reinicia as duas janelas e reescreve o cookie. Responde o status. |

Sem sessão válida, `heartbeat` e `renew` respondem **401 sem corpo**. Não existe `logout` no hub:
cada aplicação faz logout pelo próprio endpoint, chamando `SentretService.logout(...)`.

### 1.2. Corpo da resposta

```json
{
  "authenticated": true,
  "userId": "42",
  "email": "ana@exemplo.com",
  "absoluteRemainingMs": 14280000,
  "idleRemainingMs": 3540000,
  "config": {
    "heartbeatIntervalMs": 60000,
    "statusPollIntervalMs": 30000,
    "warningBeforeMs": 300000,
    "loginUrl": "https://login.exemplo.com"
  }
}
```

- Anônimo: só `authenticated: false` e `config`.
- Com `max-idle=0`, `idleRemainingMs` não aparece.

---

## 2. Propriedades do hub

| Propriedade | Padrão | Uso |
|---|---|---|
| `sentret.hub.enabled` | `false` | Liga os três endpoints. |
| `sentret.hub.base-path` | `/session` | Prefixo dos endpoints. **Precisa terminar em `/session`**: o client monta `hubBase + "/session/status"`. |
| `sentret.hub.heartbeat-interval` | `60s` | Intervalo mínimo entre heartbeats do client. |
| `sentret.hub.status-poll-interval` | `30s` | Intervalo do poll de status do client. |
| `sentret.hub.warning-before` | `60s` | Quanto antes da expiração o client mostra o aviso. |
| `sentret.hub.login-url` | — | Para onde o client manda o usuário quando a sessão acaba. |
| `sentret.max-idle` | `30m` | Janela de inatividade (núcleo, ver [02](./02-configuracao-application-properties.md)). |

---

## 3. Checklist de adesão

### 3.1. Backend do hub

```properties
sentret.max-idle=1h
sentret.hub.enabled=true
sentret.hub.base-path=/api/lite/session
sentret.hub.warning-before=300s
sentret.hub.login-url=https://login.exemplo.com
```

Com a cadeia padrão da lib, `GET {base-path}/status` já fica liberado. Com `SecurityFilterChain`
própria, libere **só** o `status`; `heartbeat` e `renew` devem exigir autenticação (se ficarem
liberados, sem sessão válida eles respondem 401 do mesmo jeito).

### 3.2. Frontend (cada UI que reage à sessão do hub)

```tsx
<SessionProvider hubBase={HUB_API} appLogoutUrl="/auth/logout">
  <App />
</SessionProvider>
```

- O client consulta `GET <hubBase>/session/status` ao montar e a cada `statusPollIntervalMs`;
  mostra o aviso dentro de `warningBeforeMs` e, quando a sessão acaba, chama o `appLogoutUrl` e
  redireciona para `loginUrl`.
- Eventos reais do usuário disparam `POST <hubBase>/session/heartbeat` (no máximo um por
  `heartbeatIntervalMs`); com o aviso na tela, só o botão "Continuar conectado" (`renew()`)
  dispensa o aviso.
- Abas do mesmo navegador e da mesma origem são avisadas na hora via `BroadcastChannel`; as demais,
  no próximo status.

---

## 4. Ressalva conhecida — origem em dev vs. produção

- **Em produção**, as UIs e o hub costumam ficar atrás do mesmo proxy, numa só origem: o cookie do
  hub trafega sem configuração extra.
- **Em desenvolvimento local**, cada UI roda numa porta diferente do hub: as chamadas são
  cross-origin (exigem `sentret.cors-allowed-origins` no hub e credenciais no client), mas continuam
  **same-site** — portas diferentes de `localhost` são o mesmo site. O padrão `SameSite=Lax` basta;
  use `sentret.cookie-secure=false` em HTTP.
- **Domínios diferentes** (ex.: `app.empresa.com` e `api.outra.com`) exigem `SameSite=None` +
  `Secure`. Com `csrf-enabled`, o front só consegue ler o cookie `XSRF-TOKEN` se UI e API dividirem
  um domínio (`sentret.cookie-domain`).
- **CSRF com o hub:** com `csrf-enabled=true`, a cadeia padrão isenta `heartbeat` e `renew`, que o
  client envia sem header de CSRF. O client também chama o `appLogoutUrl` da app sem o token: isente
  esse caminho com `sentret.csrf-ignored-paths` (ou na sua cadeia), senão o logout dá 403 e a sessão
  da app sobrevive. Forjar um deles só mantém viva uma sessão que já existe.
