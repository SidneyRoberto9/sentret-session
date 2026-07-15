# Changelog

Todas as mudanças notáveis deste projeto são documentadas neste arquivo. O formato segue,
livremente, o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/); o versionamento segue
[SemVer](https://semver.org/lang/pt-BR/).

---

## [2.2.0] - 2026-07-15

Bump **MINOR**. Todo o SSE mis-roteava eventos: `warning` e `logout` de uma sessão chegavam nas
outras sessões do mesmo usuário. Ver [`MIGRATION.md`](MIGRATION.md) (seção "2.1.x → 2.2.0") se você
implementa `SessionEventBroadcaster` por conta própria.

### Corrigido

- **Uma sessão expirando deslogava o usuário de todas as outras — inclusive de onde ele estava
  trabalhando.** O `SpringSessionLiteSseRegistry` guardava os emitters em
  `Map<String, List<SseEmitter>> emittersByUserId`, e todos os três eventos eram endereçados ao
  `userId`. Com duas sessões (dois dispositivos, dois browsers, ou uma sessão órfã deixada por um
  duplo-login), a sessão A recebia os eventos da sessão B.

  O sintoma no navegador: um modal de inatividade sobre uma sessão que não é a da aba, re-emitido a
  cada 10s pela varredura idle-watch, e **impossível de dispensar** — o "Continuar conectado" renova
  a sessão do cookie, não a que gerou o aviso. Quando a outra sessão cruzava o `max-idle`, o
  `logout` dela terminava a aba viva. Pior: como o client congela o heartbeat enquanto há aviso na
  tela (0.1.3), o aviso falso fazia a sessão boa ficar idle de verdade e morrer — o aviso espúrio
  causava o logout real.

  A aba não tinha como se defender: o payload nomeia um `sessionId`, mas o browser não consegue
  saber o seu (não é devolvido por `GET /session/status`, e devolver seria um downgrade contra o
  cookie `httpOnly`). Só o servidor pode responder "esse evento é meu?" — e o `sessionId` já estava
  disponível em todas as camadas, sendo descartado apenas na fronteira do registry. O roteamento
  agora é por `sessionId`.
- **NPE em `@EventListener` síncrono.** `SpringSessionLiteSessionDestroyedEvent` mantém um construtor
  de 1 argumento (compat pré-2.1) que deixa `userId` **null**. Rotear por ele significava
  `ConcurrentHashMap.get(null)` → `NullPointerException` lançada dentro do listener, propagando de
  volta para a transação de quem publicou o evento. O `sessionId` nunca é null.

### Alterado (breaking)

- `SessionEventBroadcaster` — os três métodos roteados foram **renomeados**, e o `String` agora é um
  `sessionId`, não um `userId`:
  - `logout(String userId, ...)` → `sendLogout(String sessionId, ...)`
  - `renew(String userId, ...)` → `sendRenew(String sessionId, ...)`
  - `warning(String userId, ...)` → `sendWarning(String sessionId, ...)`

  Renomeamos de propósito, sem `default` shims. Nome de parâmetro não faz parte da assinatura em
  Java: manter os nomes antigos deixaria um `@Override logout(String userId, ...)` externo compilar
  e passar a receber `sessionId` **em silêncio** — publicando no tópico errado e matando sessão
  viva. Ou seja, este mesmo bug, realocado no código de quem integra. Quebra de compilação é melhor
  que quebra silenciosa. `pingAll()` não muda.
- `SpringSessionLiteSseRegistry` — `emittersFor(userId)` → `emittersForSession(sessionId)`;
  `connectedUserIds()` → `connectedSessionIds()`. `add`/`remove` mantêm o nome (só o parâmetro muda
  de significado).
- **Comportamento:** deslogar no dispositivo A não termina mais o dispositivo B. Isso nunca foi
  projetado — era artefato da chave do mapa; nenhuma doc, changelog ou teste declarava isso como
  requisito. Abas do mesmo browser continuam cobertas: elas compartilham o cookie, logo a mesma
  chave, e o `BroadcastChannel` do client já as sincroniza. Para terminar todas as sessões de um
  usuário existe `logoutAll(userId)` — que não empurra SSE hoje, nem antes nem depois desta versão.

---

## [2.1.2] - 2026-07-15

Bump **PATCH** — completa o 2.1.1: a janela de inatividade agora fecha, mas atividade real do
usuário voltava a ser descartada, dessa vez pelo throttle de escrita.

### Corrigido

- **Usuário ativo era deslogado: `touch()` descartava o heartbeat.** Com o modal de inatividade na
  tela, o usuário mexia o mouse, o client enviava `POST /session/heartbeat`, e o servidor
  silenciosamente ignorava a escrita porque o último `touch()` estava dentro do throttle efetivo
  (`min(last-accessed-throttle, max-idle / 2)`). `lastAccessedAt` não andava, o idle-watch
  continuava emitindo `warning` a cada 10s, e a contagem ia até zero com o usuário ali na frente.

  O throttle fazia sentido em 2.1.0, quando toda requisição autenticada chamava `touch()` e ele era
  o que evitava um UPDATE por requisição. Depois do 2.1.1, só o heartbeat chega ali — e o client já
  o limita a `heartbeat-interval`, então a escrita já está limitada na origem. Throttlar de novo
  aqui é perda pura: descarta o único sinal de atividade que a lib tem. `touch()` agora **sempre**
  escreve.
- `last-accessed-throttle` deixa de ter efeito sobre `touch()` (o throttle efetivo e o cap em
  `max-idle / 2` foram removidos). A propriedade continua existindo para compatibilidade de
  configuração, mas não influencia mais o registro de atividade.

### Alterado

- `SpringSessionLiteSecurityValidator`: o aviso sobre `last-accessed-throttle >= max-idle / 2` saiu
  (não descreve mais nada real). No lugar entraram dois avisos que importam:
  - `heartbeat-interval >= max-idle / 2` — o heartbeat é a única coisa que reinicia a janela e o
    client o limita a esse intervalo, então um usuário ativo pode ser deslogado assim mesmo.
    Mantenha `heartbeat-interval` bem abaixo de `max-idle` (um quarto ou menos).
  - `warning-before >= max-idle` — a janela de warning cobre a sessão inteira e o modal aparece
    imediatamente ao logar.

---

## [2.1.1] - 2026-07-15

Bump **PATCH** — corrige o `max-idle` introduzido em 2.1.0, que na prática nunca disparava.

### Corrigido

- **`max-idle` era inalcançável: a sessão nunca expirava por inatividade e o `warning` nunca era
  emitido.** `SpringSessionLiteService.validate()` chamava `touch()` em toda requisição
  autenticada, e o `SpringSessionLiteAuthenticationFilter` roda em todas — inclusive no
  `GET /session/status` que o client dispara a cada `status-poll-interval` (30s por padrão) e no
  `GET /session/stream`. Como o throttle efetivo é limitado a `max-idle / 2`, qualquer poll mais
  frequente que isso renovava `lastAccessedAt` para sempre. Com `max-idle=2m` o `idleRemainingMs`
  observado oscilava entre ~120s e ~80s e nunca cruzava o limiar de `warning-before=60s`. Valia
  para **qualquer** configuração: com o `max-idle=15m` sugerido na documentação, o poll de 30s
  renovava a cada 7m30 e a janela de 15m nunca fechava.

  `validate()` agora apenas valida (virou `@Transactional(readOnly = true)`) e **não** registra
  atividade. A atividade passou a ser sinalizada de forma explícita e exclusiva por
  `POST /session/heartbeat`, que o client dispara a partir de eventos reais de DOM
  (`mousedown`/`mousemove`/`keydown`/`touchstart`/`scroll`/`wheel`), throttled em
  `heartbeat-interval`.
- `SpringSessionLiteSseAutoConfiguration` não compilava: a ordem dos argumentos no
  `new SpringSessionLiteIdleWatchTask(...)` divergia da ordem dos campos que o
  `@RequiredArgsConstructor` usa para gerar o construtor.

### Adicionado

- `SpringSessionLiteService.touch(String sessionId)`: registra atividade real do usuário,
  reiniciando a janela de inatividade (e deslizando a expiração absoluta quando
  `sliding-expiration` está ligado). Continua limitado pelo throttle efetivo
  `min(last-accessed-throttle, max-idle / 2)`, então um client que bata mais rápido que o throttle
  não gera escrita extra no banco. No-op se o `sessionId` não existir.

### Alterado

- **Mudança de comportamento:** `lastAccessedAt` deixa de ser atualizado a cada requisição
  autenticada; passa a refletir apenas atividade explícita (`POST /session/heartbeat`), `renew()`
  e `login()`. Afeta `update-last-accessed` e `sliding-expiration`, que agora deslizam na
  atividade em vez de em qualquer requisição. Quem usa `max-idle` **precisa** ter o client
  (`@media4all/spring-session-lite-client`) montado, ou enviar o heartbeat por conta própria — sem
  heartbeat, a sessão expira por inatividade mesmo com o usuário usando a aplicação.

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
