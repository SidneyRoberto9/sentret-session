# Changelog

Todas as mudanças notáveis deste projeto são documentadas neste arquivo. O formato segue,
livremente, o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/); o versionamento segue
[SemVer](https://semver.org/lang/pt-BR/).

---

## [1.0.1]

### Adicionado
- A tabela `sentret_sessions` é criada no startup quando não existe, com os dois índices e a collation
  case-sensitive do banco (`ascii_bin` no MySQL/MariaDB, `Latin1_General_BIN2` no SQL Server). Não
  precisa mais de Flyway/Liquibase nem de SQL à mão. Tabela existente não é tocada; duas instâncias
  subindo juntas não conflitam.
- `sentret.create-table` (padrão `true`): `false` volta ao comportamento da 1.0.0 (só loga ERROR se a
  tabela faltar), para quem controla o schema.

### Alterado
- Sem permissão de `CREATE TABLE`, a lib loga um ERROR e o startup segue (nada quebra que já não
  estivesse quebrado: sem a tabela todo login falhava).

---

## [1.0.0] - Sentret

Primeira versão com o nome **Sentret** (artefato novo: `io.github.sidneyroberto9:sentret-session`).
Sucede o `spring-session-lite` 3.0.0. Guia completo em `MIGRATION.md`.

### Alterado
- Renomeada para Sentret: pacote `io.github.sidneyroberto9.sentret`, classes `Sentret*`, propriedades `sentret.*`, cookie `SENTRETSID`.
- Persistência com `JdbcTemplate` (SQL puro, tabela `sentret_sessions`, tempos em epoch millis). Sem JPA/Hibernate.
- Heartbeat grava com um único `UPDATE`; o principal carrega os prazos e o status não relê a sessão.
- Session ID com `SecureRandom` + Base64 URL (20 caracteres).
- 401 pelo `HttpStatusEntryPoint`, sem corpo.
- Propriedades do hub agrupadas em `sentret.hub.*`; CORS inferido de `cors-allowed-origins`; `max-idle` padrão `30m`.
- Hub com Controller só HTTP, `SentretHubStatusService` e DTOs em `hub.dto.response`.
- Compatível com Spring Boot 3.3+ e 4.x (verificado no 3.3.3, 3.5.15 e 4.1.1; profile `boot4`).

### Segurança e robustez
- `SentretUser#sessionId` nunca é serializado (é o valor do cookie `HttpOnly`).
- Cookie fora do formato (20 caracteres Base64 URL) é recusado sem consulta ao banco.
- O ID precisa bater exatamente com o do banco, mesmo em collations que ignoram maiúsculas/minúsculas.
- `max-idle` só é aplicado com o hub ligado; sem hub vale apenas o `ttl`.
- Heartbeat e renew sem sessão respondem 401 (nunca 500), inclusive com `SecurityFilterChain` própria.
- Falha na limpeza de expiradas não derruba o login (só loga um aviso).
- Hub não impede o startup quando `sentret.enabled=false`.
- `renew` do hub parte do principal já validado, sem reler a sessão.
- `csrf-enabled=true` funciona com SPA: o cookie `XSRF-TOKEN` sai em toda resposta e o valor puro em `X-XSRF-TOKEN` é aceito.
- A auto-configuração só sobe em aplicação web servlet, e o bean do cookie se chama `sentretCookieManager` (sem colidir com um `cookieManager` da aplicação).
- `logout(sessionId)` usa a mesma busca exata do `validate`.
- Aviso no startup para `cookie-same-site=None` com `cookie-secure=false`.
- Erros (400/404/500) e respostas assíncronas de usuários logados não viram mais 401: o filtro guarda o contexto de segurança na request, e a cadeia padrão libera o dispatch de ERROR.
- Com `csrf-enabled=true`, `heartbeat` e `renew` do hub ficam isentos de CSRF, e o cookie `XSRF-TOKEN` segue o domínio, o SameSite e o Secure do cookie de sessão.
- `heartbeat` e `renew` de uma sessão apagada no meio da request respondem 401 (sem cookie novo nem evento).
- ID de sessão nulo é tratado como sessão desconhecida.
- Aviso no startup quando um cookie `__Host-`/`__Secure-` não cumpre as regras do prefixo.
- `config.loginUrl` não definido é omitido do status.
- `spring-boot-starter-web` passou a ser dependência opcional (a lib não impõe o Tomcat).
- Nova propriedade `csrf-ignored-paths` (ex.: o logout que o client npm chama sem token).
- `cors-allowed-origins` aceita padrões (`https://*.dominio.com`); `*` não derruba mais toda request com 500.
- `/actuator/health/**` entra nas rotas públicas padrão (probes não recebem 401).
- ERROR no startup quando a tabela `sentret_sessions` não existe.
- A cadeia padrão é ordenada explicitamente antes das cadeias padrão do Boot 3 e 4.

### Removido
- Vínculo com IP (`ip-hash-salt`, `trust-forwarded-for`, `trusted-proxy-count`).
- Roles na sessão.
- `NanoId`, task agendada de limpeza e `@EnableScheduling`.
- Endpoint `POST /session/logout` e os campos `ttlMs`, `maxIdleMs`, `logoutUrl`, `redirectAfterExpiryUrl` do status.
- `@SpringSessionLiteCurrentSession` (use `@AuthenticationPrincipal`).
- Propriedades `cookie-prefix`, `cookie-path`, `session-id-length`, `update-last-accessed`, `sliding-expiration`, `last-accessed-throttle`, `cors-enabled`, `cors-allowed-methods`, `cors-allow-credentials`, `cleanup-enabled`, `cleanup-cron`.

---

## [3.0.0] - 2026-07-29

Bump **MAJOR**. **Toda a pilha SSE foi removida.** O push por servidor era, na prática, a única coisa
na biblioteca que custava uma conexão por aba e impedia rodar mais de uma réplica — e nunca foi fonte
de verdade para nada.

### Removido

- **`GET <endpoints-base-path>/stream` e todo o pacote `web.sse`** — `SpringSessionLiteSseController`,
  `SpringSessionLiteSseRegistry`, `SessionEventBroadcaster` /
  `InMemorySessionEventBroadcaster`, `SpringSessionLiteIdleWatchTask`,
  `SpringSessionLiteSseSessionEventListener` e os eventos
  `SpringSessionLiteSseWarningEvent` / `SseLogoutEvent` / `SseRenewEvent`, mais
  `SpringSessionLiteSseAutoConfiguration`.
- **Propriedades `sse-enabled` e `idle-watch-interval`.** Deixe de configurá-las; o Boot ignora
  propriedades desconhecidas, então nada quebra no startup se elas ficarem para trás.
- **`SpringSessionLiteSessionStore.findActive(Instant)`** (e o `findByExpiresAtAfter` do
  repositório). Existia só para a varredura idle-watch. Um store customizado que a implementava
  pode simplesmente apagar o método.
- No cliente (`@media4all/session-lite` 1.0.0): `EventSourceLike`, `MessageEventLike`,
  `SessionLogoutEvent`, o `eventSourceFactory` do ambiente injetável, o listener `onStreamGaveUp` e
  o campo `sessionId` de `SessionWarningEvent`/`SessionRenewEvent` (sempre vazio fora do SSE).

### Por quê

Um `SseEmitter` é criado sem timeout e vive enquanto a aba viver: **uma conexão TCP permanente por
aba aberta**. Recarregar uma janela abre a nova antes de a antiga ser detectada como morta — o
container só percebe o cliente sumido quando uma escrita falha —, então a contagem de sockets
acompanha o número de abas, não o de requisições. Pior: o registro de emitters é um mapa em memória,
o que prende o hub a **uma única réplica** e fecha a porta para escalar horizontalmente, que é
justamente o que uma implantação grande precisa fazer.

Em troca disso, o push entregava apenas latência. O `warning` já era derivável do
`GET /session/status` — ele carrega `idleRemainingMs`/`absoluteRemainingMs`, e o cliente levanta o
card sozinho em `applyStatus`. O `logout` já era coberto pelo mesmo poll: `validate()` aplica
`max-idle` e devolve `authenticated:false`. Nada do que o stream fazia deixou de acontecer; só passa
a acontecer em até `status-poll-interval` em vez de instantaneamente.

### Migração

1. Remova `spring-session-lite.sse-enabled` e `spring-session-lite.idle-watch-interval` do
   `application.properties`.
2. Garanta `status-poll-interval` **confortavelmente abaixo** de `warning-before` — agora o poll é o
   único caminho do aviso de inatividade. A lib passa a avisar no startup quando não está (o aviso
   equivalente sobre `idle-watch-interval` saiu junto com a varredura).
3. Atualize o cliente para `@media4all/session-lite` 1.0.0.
4. Se você implementava `SpringSessionLiteSessionStore`, apague o `findActive`.
5. Com o hub agora stateless, `replicas` pode passar de 1.

---

## [2.3.0] - 2026-07-29

Bump **MINOR**. Sob carga, a varredura de inatividade era o maior consumidor de banco do hub, e um
único cliente travado podia congelar todos os `@Scheduled` da aplicação hospedeira — e prender uma
conexão do pool. Nenhuma mudança de comportamento visível ao usuário final; nenhuma migração
necessária, exceto para quem constrói `InMemorySessionEventBroadcaster` na mão ou implementa
`SessionEventBroadcaster` (ver abaixo).

### Corrigido

- **A varredura de inatividade fazia N+1 consultas por tick.** `SpringSessionLiteIdleWatchTask`
  carregava todas as sessões vivas com `store.findActive()` e então chamava
  `sessionService.remaining(sessionId)` **por linha** — cada chamada uma transação read-only própria
  que relia a linha que a task já tinha em mãos. Com N sessões vivas eram N+1 transações a cada 10s,
  numa única thread, contra o pool de conexões da aplicação. Como o `ttl` padrão é de 8h, N é "logins
  nas últimas 8 horas", não "usuários online agora" — então o custo crescia ao longo do dia mesmo com
  a concorrência estável. A conta de tempo restante usa apenas `expiresAt` e `lastAccessedAt`, ambos
  já presentes na linha carregada, e agora é feita em memória (via o novo
  `SpringSessionLiteService.remainingOf(session)`) para **triar** cada sessão. **Sessão longe de
  qualquer prazo — a esmagadora maioria, na maior parte do tempo — não custa consulta nenhuma: 1 por
  tick, independente de N.** Só as poucas que a triagem marca para ação (aviso ou destruição) são
  relidas, e isso é de propósito — ver o item seguinte.
- **A varredura podia deslogar quem estava trabalhando.** `findActive()` fotografa todas as linhas no
  início do tick; numa varredura longa, um `POST /session/heartbeat` (ou um logout explícito) chega
  enquanto ela ainda está rodando. Decidir pela foto envelhecida destruiria a sessão de quem acabou de
  mexer o mouse, e mandaria `warning` para uma sessão que já não existe. Antes da 2.3.0 a releitura
  por linha fechava essa janela por acidente; agora ela é explícita e só para as sessões que a triagem
  marcou — a janela volta a ser de microssegundos, sem voltar a ser N+1.
- **Um cliente travado parava a varredura, todo o resto do agendador e uma transação aberta.**
  `SseEmitter#send` é uma escrita bloqueante no output stream do container. Um cliente com a janela
  TCP cheia — ou uma conexão meio-aberta — bloqueia o escritor até o socket expirar, e o emitter é
  criado sem timeout. Isso rodava na thread do `TaskScheduler` compartilhado da aplicação hospedeira
  (pool padrão: 1 thread) no caso da varredura, e **dentro da transação aberta** de
  `SpringSessionLiteService.logout/renew` no caso do listener de eventos (dispatch síncrono do
  Spring), segurando também a conexão JDBC. Nenhum envio roda mais na thread de quem chamou: o
  broadcaster despacha `logout`/`renew`/`warning`/`ping` para filas próprias, de threads daemon,
  particionadas por `sessionId` — os eventos de uma sessão continuam em ordem (um `warning` depois do
  `logout` mostraria contagem regressiva numa sessão morta) e um cliente travado só atrasa a fatia de
  sessões que caiu na mesma fila. Fila cheia descarta com log, em vez de crescer sem limite.
- **`idle-watch-interval` inválido quebrava o startup sem dizer por quê.** `0s` estourava um
  `IllegalArgumentException` de dentro do `ThreadPoolTaskScheduler` e um valor vazio virava `null` e
  um NPE no registrar. Agora falha com mensagem nomeando a propriedade, e o validador de configuração
  avisa quando a cadência é frouxa demais para o `warning-before` configurado (a varredura é a única
  coisa que empurra o aviso de inatividade).

### Adicionado

- `spring-session-lite.idle-watch-interval` (default `10s`) — a cadência da varredura, antes fixa em
  código.
- `SpringSessionLiteService.remainingOf(SpringSessionLiteSession)` — mesma conta de
  `remaining(String)`, sobre uma sessão que o chamador já tem. Sem consulta, sem transação.
- `SessionEventBroadcaster.close()` — gancho de shutdown, com implementação default vazia. Declarado
  na interface, e não só na implementação padrão, porque o Spring infere o destroy method da classe do
  **bean registrado**: quem decora ou substitui o broadcaster (o ponto de extensão documentado) vazava
  as threads do objeto embrulhado a cada shutdown de contexto. **Um decorator precisa sobrescrever e
  delegar.**
- `InMemorySessionEventBroadcaster(registry, Executor)` — sobrecarga para quem quer controlar a
  thread de envio. O executor passado aqui **não** é encerrado por `close()`: ele é de quem o passou,
  que pode estar compartilhando-o com outro trabalho. Testes passam `Runnable::run` para manter os
  envios síncronos.
- `SpringSessionLiteSessionRenewedEvent` passa a carregar `absoluteRemainingMs`/`idleRemainingMs`. O
  publicador acabou de carregar e salvar a linha, então calcular ali não custa nada; sem isso todo
  listener que queira reportar os novos prazos relê a linha que o publicador tinha em mãos um quadro
  antes (era exatamente o que a ponte SSE fazia).

### Migração

- Quem instancia `InMemorySessionEventBroadcaster` diretamente: o construtor de um argumento continua
  existindo e agora cria as filas daemon internas, encerradas em `close()`.
- Quem **implementa** `SessionEventBroadcaster`: nada a fazer — `close()` tem default vazio. Quem
  **decora** o broadcaster padrão deve sobrescrever `close()` e delegar, ou as threads do objeto
  interno sobrevivem ao contexto.
- Quem constrói `SpringSessionLiteSessionRenewedEvent`: o construtor de três argumentos continua
  existindo e deixa o snapshot nulo (listeners caem na releitura). O construtor canônico agora tem
  cinco componentes — relevante só para deconstruction patterns.

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
