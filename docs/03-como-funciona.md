# Sentret — Como Funciona

O que acontece por dentro, do login à expiração.

---

## 1. Visão geral

```
navegador ──cookie SENTRETSID──► SentretAuthenticationFilter ──► SentretService ──► SentretSessionStore ──► sentret_sessions
                                         │
                                         └──► SecurityContext (principal = SentretUser)
```

- **Uma linha por sessão** em `sentret_sessions`, no banco da aplicação.
- **Uma leitura por request** autenticada; **uma escrita** só no login, no heartbeat e no renew.
- Nenhuma tarefa agendada, nenhum `@Transactional`: cada operação do store é um único statement.

---

## 2. Estrutura de pacotes

```
io.github.sidneyroberto9.sentret
├── autoconfigure/   SentretAutoConfiguration (beans + cadeia padrão), SentretHubAutoConfiguration
├── config/          SentretProperties, SentretSecurityValidator (avisos de startup)
├── event/           SentretSessionCreatedEvent, SentretSessionDestroyedEvent, SentretSessionRenewedEvent
├── security/        SentretAuthenticationFilter, SentretUser (principal)
├── service/         SentretService, SentretUserService, SentretCookieManager
├── store/           SentretSession (linha), SentretSessionStore (interface), JdbcSentretSessionStore
└── hub/             SentretHubController, SentretHubStatusService, dto/response/*
```

---

## 3. Auto-configuração

`SentretAutoConfiguration` sobe em aplicação web servlet, com `JdbcTemplate` e Spring Security no
classpath (e `sentret.enabled` não é `false`); o store precisa de um **bean** `JdbcTemplate`
(o Boot cria um quando há `DataSource`). Ela é ordenada antes das cadeias padrão do próprio Boot.
No startup, se a tabela `sentret_sessions` não existir, cria a tabela e os índices
(`sentret.create-table=true`, o padrão) ou só loga um ERROR (`false`). Nunca impede o startup: sem
permissão de `CREATE TABLE` ou com o banco fora do ar, apenas loga. Registra, todos com `@ConditionalOnMissingBean`:

- `SentretSessionStore` → `JdbcSentretSessionStore(jdbcTemplate, createTable)`;
- `SentretService`, `SentretUserService`, `SentretCookieManager`, `SentretAuthenticationFilter`;
- `SentretSecurityValidator`;
- a `SecurityFilterChain` padrão, só se a aplicação não declarou a sua.

`SentretHubAutoConfiguration` registra o hub só com `sentret.hub.enabled=true`.

A lib não referencia nenhuma classe de auto-configuração do Spring Boot, por isso o mesmo jar
funciona no Boot 3 e no 4.

---

## 4. A linha da sessão

| Coluna | Conteúdo |
|---|---|
| `session_id` | ID do cookie: 15 bytes de `SecureRandom` em Base64 URL sem padding = 20 caracteres (120 bits). |
| `user_id`, `email` | Identidade passada no `login`. |
| `created_at` | Momento do login. |
| `expires_at` | `created_at + ttl` (ou `agora + ttl` após um `renew`). |
| `last_accessed_at` | Último heartbeat (ou login/renew). Base da inatividade. |

Tempos em epoch millis (`BIGINT`). No Java a linha é o record `SentretSession`.

---

## 5. Login — `SentretService.login(userId, email, response)`

1. Apaga as sessões com `expires_at` no passado (`DELETE` pelo índice).
2. Insere a sessão nova.
3. Escreve o cookie: `HttpOnly`, `Secure` (conforme `cookie-secure`), `SameSite`, `Path=/`,
   `Max-Age = ttl`, `Domain` se configurado.
4. Publica `SentretSessionCreatedEvent`.

---

## 6. Validação — `SentretAuthenticationFilter`

Para cada request com cookie:

1. Cookie fora do formato (20 caracteres Base64 URL) é recusado sem consultar o banco; senão, um
   `SELECT` pelo `session_id`, e o ID encontrado precisa ser idêntico ao do cookie (collations que
   ignoram maiúsculas/minúsculas não abrem brecha). O `logout(sessionId)` usa a mesma busca.
2. Sessão válida quando existe, `expires_at` não passou e — com o hub e `max-idle` ligados —
   `last_accessed_at + max-idle` não passou.
3. **Válida:** coloca um `SentretUser` (com `expiresAt` e `lastAccessedAt`) no `SecurityContext`,
   sem authorities.
4. **Inválida:** limpa o contexto, apaga o cookie e segue anônimo. A autorização decide: rota
   `permit-all` passa (o re-login nunca trava), rota protegida recebe **401 sem corpo**
   (`HttpStatusEntryPoint`).

**Validar não é atividade.** Nenhuma request que passa pelo filtro grava nada — nem o poll de
status do client, nem chamadas de fundo da aplicação. Se gravassem, a sessão nunca ficaria inativa.

---

## 7. Atividade — heartbeat

O único sinal de atividade é o `POST {hub.base-path}/heartbeat`, enviado pelo client a partir de
eventos reais do usuário (mouse, teclado, scroll):

- `SentretService.touch(user)` faz **um** `UPDATE ... SET last_accessed_at = ?`, sem reler a linha.
- **Sem throttle no servidor:** o client já limita os heartbeats a `hub.heartbeat-interval`;
  descartar um aqui faria um usuário ativo ser deslogado.
- Com `max-idle=0`, o heartbeat não grava nada.
- Sem o hub ligado, ninguém manda heartbeat: por isso `max-idle` só é aplicado com
  `sentret.hub.enabled=true`, e sem hub vale apenas o `ttl`.

---

## 8. Renovação — `renew()`

`renew(sessionId)` só atua numa sessão **ainda válida**: um `UPDATE` volta `expires_at` para
`agora + ttl` e `last_accessed_at` para `agora`, e publica `SentretSessionRenewedEvent`.
`renew(user, response)` é o usado pelo hub: parte do principal que o filtro já validou (sem reler a
linha) e também reescreve o cookie. Sessão expirada (por tempo absoluto ou por
inatividade) **não** é ressuscitada.

---

## 9. Logout e revogação

- `logout(request, response)` / `logout(sessionId)`: apaga a linha e publica
  `SentretSessionDestroyedEvent(userId, sessionId)`; sessão já inexistente é ignorada sem evento.
- `logoutAll(userId)`: apaga todas as sessões do usuário.

---

## 10. Usuário atual

- Nos controllers: `@AuthenticationPrincipal SentretUser user` (resolver nativo do Spring Security).
- Fora deles: `SentretUserService.currentUser()`.

---

## 11. Hub de inatividade

Opcional, para várias aplicações compartilharem a mesma janela de inatividade. Endpoints, contrato
e propriedades em [06](./06-sessao-centralizada-multissistema.md). O tempo restante do `status` é
calculado a partir do principal que o filtro já carregou — nenhum endpoint lê a linha duas vezes.

---

## 12. Limpeza

Sem scheduler: o `login` apaga as sessões vencidas. Uma sessão expirada que espera o próximo login
não causa problema, porque a validação já a rejeita.

---

## 13. Extensibilidade

- **Outro armazenamento:** declare um bean `SentretSessionStore` (ex.: Redis); o JDBC sai de cena.
- **Cadeia de segurança própria:** declare sua `SecurityFilterChain` e adicione o
  `SentretAuthenticationFilter` (ver [01 §6.2](./01-instalacao-e-uso.md)).
- **Eventos:** escute `SentretSessionCreatedEvent`, `SentretSessionRenewedEvent` e
  `SentretSessionDestroyedEvent` com `@EventListener` (auditoria, métricas).
- Qualquer bean da lib pode ser substituído declarando um do mesmo tipo.

---

## 14. Decisões de projeto

| Decisão | Motivo |
|---|---|
| JDBC puro em vez de JPA | Sem Hibernate nem entidade registrada na aplicação; funciona no Boot 3 e 4. |
| Tempos em `BIGINT` | Uma DDL só para todos os bancos, sem fuso horário. |
| Sem vínculo com IP | IP muda em celular/VPN e derrubava usuários legítimos. |
| Sem roles na sessão | A aplicação é a fonte da verdade; role "congelada" na sessão fica desatualizada. |
| Só o heartbeat conta como atividade | Polls e requests de fundo não podem manter uma sessão viva para sempre. |
| Limpeza no login | Sem scheduler e sem `@EnableScheduling` imposto à aplicação. |
| 401 sem corpo | O client só olha o status; sem JSON escrito à mão. |
