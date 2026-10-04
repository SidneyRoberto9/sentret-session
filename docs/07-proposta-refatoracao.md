# Proposta de refatoração — Spring Session Lite → Sentret

> **Status: implementado na `sentret-session` 1.0.0** (branch `refactor/sentret`). O estado final,
> as correções feitas nas revisões e as pendências estão em [Estado final da 1.0.0](#estado-final-da-100).
> O plano passo a passo está em `docs/superpowers/plans/2026-10-04-sentret-refatoracao.md`.

Análise dos 16 pontos pedidos. Para cada um: **situação atual**, **proposta**, **como fazer** e
**viabilidade**. As seções numeradas abaixo são a análise original, feita antes de qualquer código;
onde o resultado final diverge, valem as tabelas "Decisões finais" e "Estado final da 1.0.0".

---

## Resumo

| #  | Ponto                                  | Viável?          | Esforço   | Quebra consumidor?                     |
|----|----------------------------------------|------------------|-----------|----------------------------------------|
| 1  | Renomear para Sentret                  | Sim              | Médio     | Sim — artefato, pacote e prefixo novos |
| 2  | Remover IP / ipHash                    | Sim              | Baixo     | Sim — props e coluna somem             |
| 3  | Remover roles da sessão                | Sim              | Baixo     | Só quem usa `hasRole` via sessão       |
| 4  | Armazenamento sem JPA                  | Sim              | Médio     | Sim — tabela muda                      |
| 5  | Verificação de atividade               | Sim              | Médio     | Parcial — client npm                   |
| 6  | JSON inline na AutoConfiguration       | Sim              | Baixo     | Não (só o corpo do 401)                |
| 7  | Separar CleanupConfiguration           | Sim (ou apagar)  | Trivial   | Não                                    |
| 8  | Cleanup silencioso                     | Sim              | Trivial   | Não                                    |
| 9  | Endpoints de atividade                 | Manter 3, tirar 1 | Baixo    | Não — client e UIs seguem iguais       |
| 10 | Compatibilidade Boot 3 e 4             | Sim, após #4     | Médio     | Não                                    |
| 11 | Menos propriedades                     | Sim              | Baixo     | Sim                                    |
| 12 | Trocar NanoId pelo JDK                 | Sim              | Trivial   | Não                                    |
| 13 | `ResponseEntity.status(...).body(...)` | Sim              | Trivial   | Não                                    |
| 14 | Controller só HTTP                     | Sim              | Baixo     | Não                                    |
| 15 | Records em arquivos próprios           | Sim              | Trivial   | Não                                    |
| 16 | Arquitetura final                      | —                | —         | —                                      |

Tudo junto é uma versão **major**. Como o nome muda (#1), vira um artefato novo: cada consumidor
migra quando quiser.

**Ordem de implementação:** 1 → (testes estáveis) → 2 → 3 → 12 → 6 → 4+5 → 7/8 → 11 → 9 (+13, 14, 15) → 10 → 16.
O rename vem primeiro para que todo código novo já nasça com o nome final (continua num commit isolado).

---

## Decisões finais (2026-10-04)

Estas decisões valem por cima do texto dos pontos abaixo, onde houver diferença:

| Ponto | Decisão | Diferença em relação à proposta original |
|---|---|---|
| #1 | Nome **Sentret** | — |
| #4 | Só **`JdbcTemplate`** | Sem fallback em memória. Colunas de tempo em **BIGINT (epoch millis)** em vez de `TIMESTAMP`: a mesma DDL serve MySQL/PostgreSQL/SQL Server/H2, sem conversão de fuso nem limite de 2038. |
| #5 | Só o heartbeat (e o renew explícito) grava atividade, com **um `UPDATE`** e **sem throttle**; `max-idle` só vale com o hub ligado | Os itens 5.2 (qualquer request = atividade) e 5.3 (throttle de 60s) saíram: requests de fundo da própria app manteriam a sessão viva para sempre, e o throttle poderia atrasar o único sinal de atividade (o bug que já deslogou gente ativa). Ficam 5.1 (principal com os prazos, sem reler a linha) e 5.5. Como sem hub ninguém manda heartbeat, `max-idle` só é aplicado com `sentret.hub.enabled=true`; sem hub vale só o `ttl`. O 5.4 (poll adaptativo) é no client npm, fora deste repositório. |
| #6 | Opção **A** (`HttpStatusEntryPoint`) | — |
| #7 / #8 | **Apagar a task**; limpar expiradas no login | — |
| #9 | **Enxugar**: `status`, `heartbeat`, `renew` em `sentret.hub.*`; sem `logout` | Com `csrf-enabled=true`, `heartbeat` e `renew` ficam isentos de CSRF (o client npm não manda o token). |
| #10 | BOM do Boot importado + profile `boot4` | `spring-boot-starter-web` virou dependência opcional (a lib não impõe o Tomcat); as demais seguem com escopo normal, e o BOM da app consumidora decide as versões. Verificado no Boot 3.3.3, 3.5.15 e 4.1.1. |
| #11 | 10 propriedades no núcleo + 6 em `sentret.hub.*` | `SentretProperties` continua JavaBean com Lombok (padrão do código, menos retrabalho nos testes). O validator mantém as checagens de tempo do hub, que continuam úteis. |
| #12 | `SecureRandom` + Base64 URL, 15 bytes → 20 caracteres | — |

**Ordem e passos:** ver `docs/superpowers/plans/2026-10-04-sentret-refatoracao.md`.

---

## Estado final da 1.0.0

### O que a biblioteca é agora

| Item | Resultado |
|---|---|
| Artefato | `io.github.sidneyroberto9:sentret-session:1.0.0`, pacote `io.github.sidneyroberto9.sentret` |
| Código principal | 19 classes (antes 25), sem JPA, sem scheduler, sem utilitário próprio de ID |
| Spring Boot | 3.3+ e 4.x — verificado no 3.3.3 (JDK 21), 3.5.15 e 4.1.1; o jar compilado no Boot 3 passa os testes no Boot 4 |
| Bancos | Script único (`db/sentret-schema.sql`) testado em PostgreSQL 17, MySQL 8.4 e H2 |
| Propriedades | 10 no núcleo + 6 em `sentret.hub.*`, nenhuma obrigatória |
| Hub | `GET status` (público), `POST heartbeat`, `POST renew` |
| API pública | `SentretService`, `SentretUserService`, `SentretUser`, `SentretSessionStore`, 3 eventos |
| Testes | 132, incluindo testes com servidor real (Tomcat) e com `SecurityFilterChain` própria |

### Correções feitas nas revisões (depois do plano)

Três revisões independentes da branch encontraram problemas que a implementação do plano não
cobria. Todos foram corrigidos com um teste que falhava antes:

| Problema | Correção |
|---|---|
| `max-idle=30m` padrão derrubava, em 30 min, toda app sem hub (nada renovava a atividade) | `max-idle` só é aplicado com o hub ligado |
| Erros (400/404/500) e respostas assíncronas viravam 401 sem corpo (bug que vinha da 3.0.0) | O filtro guarda o usuário na request; a cadeia padrão libera o dispatch de ERROR |
| `csrf-enabled=true` não funcionava com SPA (cookie `XSRF-TOKEN` nunca era enviado) | Handler de CSRF no formato recomendado pelo Spring Security para SPA |
| Com CSRF, o hub dava 403 no heartbeat/renew do client | `heartbeat` e `renew` isentos; o cookie do token segue domínio, SameSite e Secure do cookie de sessão |
| Heartbeat anônimo dava 500 em cadeia própria | Responde 401 |
| Heartbeat/renew de sessão apagada no meio da request respondiam como renovada | Store informa se alterou a linha; responde 401 sem cookie nem evento |
| `sessionId` (valor do cookie `HttpOnly`) saía no JSON do `SentretUser` | `@JsonIgnore` |
| MySQL/MariaDB/SQL Server ignoram maiúsculas: ID parecido achava a sessão de outro | `validate` e `logout` exigem ID idêntico; collation binária documentada |
| Cookie malformado ia ao banco; ID nulo lançava NullPointerException | Recusados antes de consultar |
| Falha na limpeza de expiradas derrubava o login | Só loga um aviso |
| `renew` relia a sessão | Usa o usuário já validado pelo filtro |
| Hub ligado com a lib desligada impedia o startup | Hub só sobe com a lib |
| App não-web com a lib no classpath não subia; bean `cookieManager` colidia com o da app | Auto-configuração só em app servlet; bean renomeado para `sentretCookieManager` |
| Configurações de cookie que o navegador recusa passavam em silêncio | Avisos no startup: `SameSite=None` sem `Secure`, prefixos `__Host-`/`__Secure-` |
| `config.loginUrl` saía como `null` | Omitido quando não configurado |

### Pendências conhecidas (fora desta versão)

- Um novo `login` não revoga a sessão anterior do mesmo navegador (documentado: chamar `logout` antes).
- `logoutAll` não publica eventos por sessão.
- A lib não desliga sozinha o registro do filtro como filtro comum do servlet (inofensivo; as apps m4all já desligam).
- Poll adaptativo (5.4) e tipos atualizados no client npm `@media4all/session-lite`.
- Migração das apps consumidoras (`MIGRATION.md`), renomeação do repositório no GitHub e publicação no Maven Central.

---

## 1. Renomear para Sentret

**Decisão: Sentret.** O nome segue o mesmo conceito da outra aplicação (Rotom): um Pokémon com
relação direta com o que a lib faz.

**Por que Sentret:**
- O nome vem de *sentry* (sentinela). Na Pokédex, ele fica de pé sobre a cauda vigiando o
  território e dá o alarme quando algo se aproxima.
- É exatamente o papel do `SentretAuthenticationFilter`: ele olha cada request, deixa passar quem
  tem sessão válida e barra quem não tem.
- O nome é curto, incomum e fácil de usar como prefixo (`sentret.*`, `Sentret*`).

### Opções analisadas

O nome precisava lembrar o que a lib faz: **dar acesso** (cookie/chave), **vigiar** cada request,
**medir o tempo** (`ttl`/`max-idle`) e ser **leve**. Rotom ficou fora porque já é o nome da outra
aplicação.

| #  | Nome          | Ideia que passa      | Por que combina                                                                                                                        | Contra                                                        |
|----|---------------|----------------------|----------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| 1  | **Klefki**    | Chave de acesso      | É o Pokémon chaveiro: coleciona e guarda chaves. Uma sessão é exatamente isso, a chave que abre a aplicação.                           | Pouco conhecido fora de quem joga                             |
| 2  | **Hoothoot**  | Relógio              | A Pokédex diz que ele tem um órgão que sente a rotação da Terra e mantém a hora exata. Combina com `ttl` e `max-idle`.                  | Nome comprido e "fofo" para uma lib de segurança              |
| 3  | **Sentret**   | Sentinela            | Fica de pé sobre a cauda vigiando o território e avisa quando algo chega. É o papel do filtro, que olha cada request.                   | Pouco conhecido                                               |
| 4  | **Watchog**   | Vigia                | O "Lookout Pokémon", com *watch* no nome. Combina com o monitoramento de atividade e inatividade.                                      | A sonoridade lembra "watchdog" genérico                       |
| 5  | **Growlithe** | Cão de guarda        | Protege território, é leal ao dono e no anime trabalha com a polícia (Officer Jenny). Combina com a autenticação, que barra quem não tem sessão. | Comprido; mais ligado a "guarda" que a "sessão"            |
| 6  | **Xatu**      | Vê o tempo           | Passa o dia parado olhando o sol e enxerga passado e futuro. Combina com saber quando a sessão expira.                                  | Pronúncia ambígua em português                                |
| 7  | **Dialga**    | Senhor do tempo      | Lendário que controla o tempo, e a sessão é basicamente tempo (absoluto e de inatividade).                                             | Lendário "pesado" para uma lib que se vende como leve         |
| 8  | **Komala**    | Inatividade          | Vive dormindo. Representa a sessão ociosa que cai por `max-idle`.                                                                      | Passa a ideia de lentidão                                     |
| 9  | **Gastly**    | Leve e invisível     | Pesa 0,1 kg, é quase só gás e é do tipo fantasma, como o Rotom. A lib é leve e transparente para a app.                                | O nome não diz nada sobre sessão                              |
| 10 | **Joltik**    | Pequeno que se acopla | É o menor Pokémon elétrico e se gruda em Pokémon maiores para se alimentar. Uma lib leve que se acopla à app. Elétrico como o Rotom.   | A ligação com sessão é indireta                               |
| 11 | **Porygon**   | Software             | Primeiro Pokémon feito inteiramente de código; vive em espaço digital.                                                                 | Genérico: serviria para qualquer lib                          |
| 12 | **Lucario**   | Identidade           | Lê a aura e sabe quem cada ser é. Combina com identificar o usuário pelo cookie.                                                       | Muito popular; maior chance de nome já usado                  |

### O que muda

| Item                     | Hoje                                           | Novo                                            |
|--------------------------|------------------------------------------------|-------------------------------------------------|
| artifactId               | `spring-session-lite`                          | `sentret-session`                                |
| Pacote                   | `io.github.sidneyroberto9.spring_session_lite` | `io.github.sidneyroberto9.sentret`               |
| Prefixo das propriedades | `spring-session-lite.*`                        | `sentret.*`                                      |
| Prefixo das classes      | `SpringSessionLite*`                           | `Sentret*` (`SentretService`, `SentretProperties`) |
| Cookie padrão            | `SLSID`                                        | `SENTRETSID`                                     |
| Tabela                   | `spring_session_lite_sessions`                 | `sentret_sessions`                               |

**Como fazer:** refactor de pacote/classes pela IDE, `sed` em propriedades e docs, mesmo `groupId`,
versão `1.0.0` (artefato novo). O `MIGRATION.md` ganha a tabela acima.

**Viabilidade:** alta. Bônus: sai o nome "spring-session", que hoje se confunde com o projeto
oficial Spring Session.

**Atenção:**
- Com um artifactId novo, ninguém recebe atualização automática. Cada app troca a dependência e o
  prefixo das propriedades na mão.
- Antes de publicar, confirme que o nome está livre no npm (o pacote do client) e no GitHub. No
  Maven Central o `groupId` próprio (`io.github.sidneyroberto9`) já evita conflito de coordenadas.
- Nomes de Pokémon são marca da Nintendo/Game Freak. Em nome de lib open-source o risco é baixo,
  mas não use arte nem logo oficial.

---

## 2. Remover IP / ipHash / ip-hash-salt

**Hoje:** no login a lib grava `HMAC(ip, salt)`. A cada request, `validate()` recalcula o hash e
derruba a sessão se o IP mudou.

**O que sai:**
- Classes `SpringSessionLiteIpHasher` e `SpringSessionLiteIpResolver`, junto com os testes delas.
- Campo `ipHash` da entidade e coluna `ip_hash` do SQL.
- Propriedades `ip-hash-salt`, `trust-forwarded-for` e `trusted-proxy-count`.
- O warning do salt no `SpringSessionLiteSecurityValidator`.
- Os 2 beans de IP na AutoConfiguration.
- O parâmetro `HttpServletRequest` de `validate(...)` e `login(...)`, que só existia para pegar o IP.

**Viabilidade:** alta. São ~150 linhas a menos.

**Trade-off:** o vínculo com IP dificultava reaproveitar um cookie roubado. Sem ele, a proteção
fica em `HttpOnly` + `Secure` + `SameSite` + `max-idle`. Em troca, quem usa celular, VPN ou rede
corporativa (onde o IP troca no meio do uso) para de ser deslogado sem motivo.

---

## 3. Remover roles da sessão

**Hoje:** `login(..., roles, ...)` grava as roles em CSV, o filtro converte cada uma em `ROLE_*`,
e `SpringSessionLiteUser.roles()` e `/status` devolvem a lista.

**Como fazer:**
- Remover a coluna `roles`, os métodos `joinRoles` e `splitRoles`, e o overload de `login` que
  recebe roles.
- `SpringSessionLiteUser` fica `(userId, email, sessionId)`.
- No filtro: `new UsernamePasswordAuthenticationToken(user, null, List.of())`. O usuário continua
  autenticado.

**Viabilidade:** alta.

**Impacto:** quem usa `hasRole`/`@PreAuthorize` com roles vindas da sessão perde isso. A role
passa a ser buscada pela própria app (a partir do `userId`), que já é a fonte da verdade. Assim a
role também não fica "congelada" na sessão até o próximo login. Nos projetos m4all ativos não
achei uso de `hasRole` em cima da sessão.

---

## 4. Armazenamento sem JPA

**Hoje:** entidade JPA, repository do Spring Data, `@AutoConfigurationPackage` (gambiarra para
registrar a entidade no EntityManager da app) e `spring-boot-starter-data-jpa` obrigatório, que
puxa o Hibernate junto. É o maior acoplamento da lib e é o que quebra no Boot 4 (ver #10).

**O que a sessão realmente precisa:** buscar/salvar por `sessionId`, apagar por `userId` e
expirar. Nada disso exige ORM.

| Opção                         | Dependência             | Sobrevive a deploy | Várias instâncias | Expiração              | Veredito                                |
|-------------------------------|-------------------------|--------------------|-------------------|------------------------|-----------------------------------------|
| `ConcurrentHashMap`           | nenhuma (JDK)           | Não                | Não               | varredura manual       | Fallback para dev/teste/instância única |
| `JdbcTemplate`                | `spring-jdbc` (pequena) | Sim                | Sim               | `DELETE` de expiradas  | **Recomendado para produção**           |
| Redis (`StringRedisTemplate`) | `spring-data-redis`     | Sim                | Sim               | TTL nativo, sem task   | Só se a infra já tiver Redis            |
| Caffeine                      | nova dependência        | Não                | Não               | nativa                 | Não compensa: é um HashMap com uma dep a mais |
| Spring Cache (`@Cacheable`)   | —                       | depende            | depende           | sem TTL por entrada    | Não serve para sessão                   |

**Recomendação:**
- Manter a interface `SentretSessionStore`. Aqui ela se justifica, porque passa a ter 2
  implementações reais.
- Escolher a implementação sozinho, sem propriedade nova:
  ```java
  @Bean
  @ConditionalOnMissingBean
  SentretSessionStore sentretSessionStore(ObjectProvider<JdbcTemplate> jdbc) {
      JdbcTemplate template = jdbc.getIfAvailable();
      if (template == null) {
          return new InMemorySentretSessionStore();
      }
      return new JdbcSentretSessionStore(template);
  }
  ```
  Usa o `DataSource` que a app já tem, com SQL puro: sem entidade, sem Hibernate, sem ordenar
  autoconfigs.
- Redis só entra quando alguém precisar.
- `SentretSession` vira um `record` imutável e todo `@Transactional` sai (cada operação é um único
  statement).

**Tabela nova** (sem o `id` UUID artificial, sem `ip_hash`, sem `roles`):
```sql
CREATE TABLE sentret_sessions (
    session_id       VARCHAR(20)  PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    created_at       TIMESTAMP    NOT NULL,
    expires_at       TIMESTAMP    NOT NULL,
    last_accessed_at TIMESTAMP    NOT NULL
);
CREATE INDEX idx_sentret_sessions_user_id    ON sentret_sessions (user_id);
CREATE INDEX idx_sentret_sessions_expires_at ON sentret_sessions (expires_at);
```
A DDL fica com a app (Flyway ou execução manual), como já é hoje com `ddl-auto=none`. No MySQL,
prefira `DATETIME(6)`.

**Viabilidade:** alta. Saem a entidade, o repository, o `JpaSpringSessionLiteSessionStore`, o
`@AutoConfigurationPackage` e a dependência de data-jpa.

**Atenção:**
- O `InMemory` perde todas as sessões a cada deploy. Ele só é usado quando a app não tem
  `JdbcTemplate`.
- Migrar a tabela antiga obriga todo mundo a logar de novo uma vez.

---

## 5. Verificação de atividade (heartbeat / max-idle)

**Hoje (por usuário, por aba aberta):**

| Evento                         | Frequência        | Acessos ao banco                                       |
|--------------------------------|-------------------|--------------------------------------------------------|
| Qualquer request autenticada   | toda request      | 1 SELECT (filtro)                                      |
| `GET /status` (poll do client) | a cada 30s        | 2 SELECT (filtro + `remaining()` relê a mesma linha)   |
| `POST /heartbeat`              | até 1 por minuto  | 2 SELECT + 1 UPDATE (filtro + `touch()` relê a linha)  |

Uma aba parada gera ~120 polls por hora, ou seja ~240 SELECTs sem o usuário fazer nada. Além
disso, são 6 propriedades para um único conceito (`update-last-accessed`, `sliding-expiration`,
`last-accessed-throttle`, que já não faz nada, `heartbeat-interval`, `status-poll-interval` e
`warning-before`).

**Proposta:**
1. **Ler a sessão uma vez só por request.** O filtro já carrega a sessão; basta pôr
   `expiresAt`/`lastAccessedAt` no principal (`SentretUser`). O tempo restante passa a ser
   calculado sem um novo SELECT.
2. **Atividade = qualquer request autenticada, exceto `GET /status`.** Quem faz o `touch` é o
   filtro. Uma app de sistema único deixa de precisar de heartbeat; ele só continua existindo para
   UIs do hub que não chamam o backend dele.
3. **Throttle fixo de 60s na escrita (só no JDBC).** O `last_accessed_at` só é gravado se o valor
   atual tiver 60s ou mais. Isso dá no máximo 1 UPDATE por minuto por sessão, qualquer que seja o
   volume de requests. A escrita vira um único statement:
   `UPDATE sentret_sessions SET last_accessed_at=? WHERE session_id=?`. Na memória grava sempre, já
   que o custo é zero.
   - *Por que agora funciona:* o throttle antigo (até 5 min) foi removido porque atrasava o único
     sinal de atividade e deslogava gente ativa. Um atraso máximo de 60s não faz diferença com
     `max-idle` ≥ 10 min.
4. **Poll adaptativo no client npm.** Em vez de chamar a cada 30s, o client agenda o próximo
   `GET /status` para `idleRemainingMs − warningBefore`, que é o momento em que o aviso deveria
   aparecer, com um teto fixo de 5 min. O teto existe para perceber, sem demora demais, uma sessão
   encerrada em outro lugar (por exemplo, por `logoutAll`). Isso dá ~12 polls por hora em vez de
   120, e `status-poll-interval` some.
5. **Sai `sliding-expiration`.** `ttl` = limite absoluto e `max-idle` = inatividade: dois
   conceitos, duas propriedades. `update-last-accessed` e `last-accessed-throttle` também saem.

**Depois:**

| Evento                        | Acessos ao banco                                         |
|-------------------------------|----------------------------------------------------------|
| Request autenticada           | 1 SELECT (+ 1 UPDATE, no máximo 1 vez por minuto)        |
| `GET /status`                 | 1 SELECT, só perto do aviso                              |
| Heartbeat (apenas no hub)     | igual a uma request autenticada                          |

**Viabilidade:** alta no backend. O item 4 depende de mudar o pacote npm do client, que fica fora
deste repositório.

---

## 6. JSON inline na `SpringSessionLiteAutoConfiguration`

**Hoje:** `res.getWriter().write("{\"error\":\"unauthorized\",...}")` no `AuthenticationEntryPoint`.
O controller ainda tem um `ErrorResponse` repetindo o mesmo formato.

**Opção A (recomendada):** usar o `HttpStatusEntryPoint` nativo do Spring Security.
```java
.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
```
Responde 401 sem corpo, e o client só precisa do status. Não tem JSON nem classe nova, e funciona
igual no Boot 3 e no Boot 4.

**Opção B (se o corpo for obrigatório):** usar `ProblemDetail`, nativo do Spring 6/7 (RFC 9457).
Ele já é a estrutura genérica e reutilizável que o ponto pede, então não é preciso criar classe:
```java
ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
```
O problema é serializar: o Boot 3 usa Jackson 2 (`com.fasterxml…ObjectMapper`) e o Boot 4 usa
Jackson 3 (`tools.jackson…JsonMapper`), então a lib teria que tratar os dois. Por isso a A é
melhor.

**Viabilidade:** alta. Conferido no client npm: ele só verifica `response.status === 401` e nunca
lê o corpo, então a opção A não quebra nada.

---

## 7. Separar `CleanupConfiguration`

**Como fazer (se a task continuar existindo):** mover a classe interna para
`autoconfigure/SentretCleanupConfiguration.java` e trazê-la com `@Import` na autoconfig.

**Melhor ainda: apagar a task.**
- Limpar as expiradas **no login**:
  - JDBC: `DELETE FROM sentret_sessions WHERE expires_at < ?` (usa índice; login é raro, então o
    custo é desprezível).
  - Memória: `map.values().removeIf(expired)` (O(n); ok até dezenas de milhares de sessões).
- Sessão expirada que sobra até o próximo login não causa problema, porque `validate()` já a
  rejeita.
- Hoje o `@EnableScheduling` dentro da lib liga o agendamento **na app inteira**, um efeito
  colateral escondido. Isso some junto.
- Também somem `SpringSessionLiteCleanupTask`, `CleanupConfiguration`, `cleanup-enabled` e
  `cleanup-cron`.

**Viabilidade:** alta, nas duas opções.

---

## 8. Cleanup silencioso

**Hoje:** `log.info("Expired sessions cleanup executed")` a cada 30 min, sempre.

**Como fazer:** apagar a linha. Não precisa de try/catch: o Spring já loga em ERROR qualquer
exceção de um `@Scheduled` (pelo `TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER`).

Se o #7 apagar a task, este ponto se resolve sozinho.

**Viabilidade:** trivial.

---

## 9. Endpoints de atividade (`/session/status|heartbeat|renew|logout`)

**Para a lib autenticar, eles são necessários?** Não. Login, validação e logout funcionam sem eles,
e eles já vêm desligados por padrão (`endpoints-enabled=false`).

**O client (`@media4all/session-lite`) funciona sem eles?** **Não.** A inatividade compartilhada
depende deles. Hoje três UIs usam o client: eleva-docs-ui, eleva-messaging-ui e eleva-management-ui.
Todas apontam `NEXT_PUBLIC_SESSION_HUB_URL` para a API do eleva-docs (`/api/lite`), que funciona
como hub.

| Endpoint          | O client usa?                                                   | Para quê                                                                              |
|-------------------|-----------------------------------------------------------------|---------------------------------------------------------------------------------------|
| `GET /status`     | Sim: ao montar e depois a cada `statusPollInterval`             | Pega a configuração (tempos e URLs), calcula o tempo restante, mostra o aviso e detecta o fim da sessão |
| `POST /heartbeat` | Sim: em mousemove/keydown/scroll, no máximo 1 vez por `heartbeatInterval` | Avisa o hub que o usuário está ativo, venha de qual app vier                |
| `POST /renew`     | Sim: no botão "Continuar conectado" (e no item "hub" da sidebar das UIs) | Reinicia os prazos da sessão                                                 |
| `POST /logout`    | **Não.** O client chama o `appLogoutUrl` da própria app          | —                                                                                     |

**Daria para fazer a inatividade sem os endpoints?**

| Alternativa                                                      | Funciona?   | Por quê                                                                                                                       |
|------------------------------------------------------------------|-------------|-------------------------------------------------------------------------------------------------------------------------------|
| Timer só no browser (estilo `react-idle-timer`) + `BroadcastChannel` | Não     | O `BroadcastChannel` só conversa entre abas da **mesma origem**. Messaging, management e docs são origens diferentes, então cada app voltaria a ter sua própria inatividade, que é justamente o problema que o hub resolve. |
| Tempo restante num header de toda resposta                       | Não         | Cada UI só chama o próprio backend; a atividade no messaging nunca chegaria ao hub.                                            |
| Atividade = qualquer request (#5.2)                              | Parcial     | Só cobre a UI do próprio eleva-docs. As outras UIs continuam precisando do heartbeat.                                          |

**Conclusão:** `status`, `heartbeat` e `renew` são indispensáveis para a inatividade entre aplicações.
Só o `logout` é dispensável.

**Recomendação (substitui a ideia anterior de mover tudo para o eleva-docs):** manter os endpoints
na lib, ligados só por opção, e enxugar:
- **Mantém** `status`, `heartbeat` e `renew`.
- **Remove** `logout`. O client não usa, e cada app já tem o próprio logout chamando
  `SentretService.logout(req, res)`.
- **Por que ficar na lib e não no eleva-docs:** o contrato é definido em par com o client npm (mesmo
  autor, versões casadas). Se o controller fosse para o eleva-docs, o contrato ficaria separado do
  client, e qualquer outra app que virasse hub teria que copiar o controller.
- **Enxugar o `config` da resposta.** O client e as UIs leem só `heartbeatIntervalMs`,
  `statusPollIntervalMs`, `warningBeforeMs`, `loginUrl` e `redirectAfterExpiryUrl` (este último só
  como fallback de `loginUrl`).
  - `ttlMs`, `maxIdleMs` e `logoutUrl` nunca são lidos: saem.
  - `redirectAfterExpiryUrl` sai e fica só o `loginUrl`. O eleva-docs já define os dois com o mesmo
    papel.
- **Agrupar as propriedades do hub em `sentret.hub.*`**, que só valem quando o hub está ligado:

  | Propriedade                     | Padrão     | Uso                                                                                       |
  |---------------------------------|------------|-------------------------------------------------------------------------------------------|
  | `sentret.hub.enabled`           | `false`    | Liga os endpoints (antes `endpoints-enabled`)                                             |
  | `sentret.hub.base-path`         | `/session` | O eleva-docs usa `/api/lite/session`                                                      |
  | `sentret.hub.heartbeat-interval`| `60s`      | Lido pelo client                                                                          |
  | `sentret.hub.warning-before`    | `60s`      | Lido pelo client                                                                          |
  | `sentret.hub.login-url`         | —          | Para onde o client manda o usuário quando a sessão acaba                                  |

  O `status-poll-interval` sai quando o client adotar o poll adaptativo (#5.4). Até lá, continua.
- O `roles` some do `/status` (#3). O client só repassa esse campo, e nenhuma UI o lê, então nada
  quebra.

**Impacto:** nenhum para o client nem para as UIs: os 3 endpoints continuam no mesmo path e com o
mesmo formato. Os pontos 13, 14 e 15 se aplicam ao controller, que continua na lib.

---

## 10. Compatibilidade com Spring Boot 3 e 4

**Hoje a lib não sobe no Boot 4.** Conferido no jar 4.1.1:

| Classe referenciada                  | Boot 3                                              | Boot 4                                                                                         |
|--------------------------------------|-----------------------------------------------------|------------------------------------------------------------------------------------------------|
| `HibernateJpaAutoConfiguration`      | `org.springframework.boot.autoconfigure.orm.jpa`    | movida para `org.springframework.boot.hibernate.autoconfigure`                                 |
| `JpaRepositoriesAutoConfiguration`   | `org.springframework.boot.autoconfigure.data.jpa`   | renomeada para `DataJpaRepositoriesAutoConfiguration` (`org.springframework.boot.data.jpa.autoconfigure`) |

O `@AutoConfiguration(before/after = ...)` aponta para essas classes, por isso quebra.

**Com o #4 (sem JPA) o problema desaparece.** Todo o resto que a lib usa é igual nas duas versões
(também conferido): `@AutoConfiguration`, `@ConditionalOn*`, `HttpSecurity`,
`CookieCsrfTokenRepository`, `HttpStatusEntryPoint`, `ProblemDetail`, `JdbcTemplate` e
`ResponseCookie`. Java 17 é o mínimo nas duas.

**Regras para manter a compatibilidade:**
- Não referenciar autoconfigs do Boot como `Class`. Se for preciso ordenar, usar
  `afterName = {"nome.no.boot3", "nome.no.boot4"}`; o nome que não existir é ignorado.
- Não usar `ObjectMapper`, que é Jackson 2 num e Jackson 3 no outro (ver #6).
- Declarar as dependências do Boot como `provided`/`optional`: quem escolhe a versão é a app.
- Nos testes, preferir `ApplicationContextRunner` e `MockMvcBuilders`, que são estáveis.
  `@AutoConfigureMockMvc`/`@WebMvcTest` mudaram de módulo no Boot 4.

**Como validar:** trocar o `<parent>` por import do BOM com uma propriedade `spring-boot.version`,
criar o profile Maven `boot4` e rodar os dois no CI:
```bash
./mvnw verify           # Boot 3.5
./mvnw verify -Pboot4   # Boot 4.x
```

**Viabilidade:** alta, depende do #4.

---

## 11. Propriedades

Hoje são **32** propriedades. A proposta deixa **10 no núcleo** mais **5 em `sentret.hub.*`** (que só valem com o hub ligado), e **nenhuma é obrigatória**.

| Propriedade                  | Padrão                              | Por que fica                                            |
|------------------------------|-------------------------------------|---------------------------------------------------------|
| `sentret.enabled`              | `true`                              | Desligar a lib (padrão de qualquer starter)             |
| `sentret.ttl`                  | `8h`                                | Expiração absoluta                                      |
| `sentret.max-idle`             | `30m`                               | Inatividade. Hoje vem desligada; ligada é mais seguro   |
| `sentret.cookie-name`          | `SENTRETSID`                          | Cada sistema m4all usa um nome diferente                |
| `sentret.cookie-secure`        | `true`                              | Dev local em http precisa de `false`                    |
| `sentret.cookie-same-site`     | `Lax`                               | O eleva-docs usa `None`                                 |
| `sentret.cookie-domain`        | —                                   | Compartilhar o cookie entre subdomínios                 |
| `sentret.csrf-enabled`         | `false`                             | Opt-in (ligar sozinho quebraria SPAs sem token XSRF)    |
| `sentret.cors-allowed-origins` | vazio                               | O CORS liga sozinho quando a lista não está vazia       |
| `sentret.permit-all-paths`     | `/login`, `/auth/**`, `/public/**`  | Rotas públicas                                          |

**Removidas:**

| Propriedade                                                                                         | Motivo                                       |
|-----------------------------------------------------------------------------------------------------|----------------------------------------------|
| `ip-hash-salt`, `trust-forwarded-for`, `trusted-proxy-count`                                        | #2                                           |
| `cookie-prefix`                                                                                     | Redundante: basta `cookie-name=__Host-SID`   |
| `cookie-path`                                                                                       | Sempre `/` na prática                        |
| `session-id-length`                                                                                 | ID fixo (#12)                                |
| `update-last-accessed`, `sliding-expiration`, `last-accessed-throttle`                              | #5                                           |
| `logout-url`, `redirect-after-expiry-url`, `status-poll-interval`                                  | O client não lê ou é redundante (#9, #5.4)   |
| `endpoints-enabled`, `endpoints-base-path`, `heartbeat-interval`, `warning-before`, `login-url`     | Viram `sentret.hub.*` (#9)                   |
| `cors-enabled`                                                                                      | Inferido de `cors-allowed-origins`           |
| `cors-allowed-methods`                                                                              | Padrão fixo: GET/POST/PUT/PATCH/DELETE/OPTIONS |
| `cors-allow-credentials`                                                                            | Sempre `true`: auth por cookie exige         |
| `cleanup-enabled`, `cleanup-cron`                                                                   | #7 (limpeza no login)                        |

**O exemplo do pedido fica assim:**
```properties
sentret.ttl=4h
sentret.cors-allowed-origins=https://app.meusite.com
```
`max-idle=30m` e `cookie-secure=true` já viram padrão, IP e heartbeat saem, e endpoints e CORS
passam a ser inferidos.

**Extras:**
- `SentretProperties` vira um `record` com `@DefaultValue` (imutável, sem Lombok; funciona no Boot 3
  e no 4).
- O `SecurityValidator` fica com uma checagem só (`SameSite=None` sem CSRF), que cabe num `if` com
  `log.warn` dentro da autoconfig. A classe pode ser apagada.

**Viabilidade:** alta.

---

## 12. NanoId

**Requisito:** ID curto para o cookie (no máximo 21 caracteres), com chance de colisão desprezível.

**Hoje:** a classe própria `NanoId` gera 21 caracteres com ~126 bits, usando `SecureRandom`. Vem
junto um teste e a propriedade `session-id-length`.

**O que a `NanoId` faz por dentro:** sorteia bytes e transforma cada um em 1 caractere de um
alfabeto de 64 símbolos (`A-Z a-z 0-9 _ -`), aproveitando 6 bits de cada byte. Isso é exatamente o
que o **Base64 URL** do JDK faz, com o mesmo alfabeto, só que sem desperdiçar 2 bits por byte.

### Opções

| Opção                              | Tamanho       | Aleatoriedade | Código/dependência                    | Cabe em 21? |
|------------------------------------|---------------|---------------|---------------------------------------|-------------|
| `NanoId` própria (hoje)            | 21 caracteres | 126 bits      | ~25 linhas + teste + 1 propriedade    | Sim         |
| Lib `jnanoid`                      | 21 caracteres | 126 bits      | Dependência nova para ~5 linhas       | Sim         |
| **`SecureRandom` + Base64 URL (JDK)** | **20 caracteres** | **120 bits** | **~5 linhas, sem dependência**   | **Sim**     |
| `UUID.randomUUID()`                | 36 caracteres | 122 bits      | 1 linha                               | **Não**     |

O UUID foi descartado porque passa do limite de 21 caracteres.

**Recomendação: Base64 URL do JDK com 15 bytes.**
```java
private static final SecureRandom RANDOM = new SecureRandom();
private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

private static String newSessionId() {
    byte[] bytes = new byte[15];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
}
```

**Por que essa opção:**
- **Tamanho:** 15 bytes viram exatamente **20 caracteres**, sem padding `=`. Isso é 1 caractere a
  menos que o atual, e só usa caracteres seguros para cookie.
- **Colisão:** 120 bits aleatórios. Mesmo com 1 bilhão de sessões, a chance de duas iguais é de
  ~10⁻¹⁹, ou seja, nula na prática. O OWASP pede no mínimo 64 bits para session ID.
- **Segurança:** a mesma da atual. A fonte continua sendo o `SecureRandom`.
- **Menos código:** a classe `NanoId`, o `NanoIdTest` e a propriedade `session-id-length` são
  apagados. O código acima fica como método privado no `SentretService`.

**Por que não usar lib:** a `jnanoid` faria o mesmo que essas 5 linhas do JDK, só que com uma
dependência a mais para manter e atualizar.

**Viabilidade:** trivial.

---

## 13. Padronizar retornos dos controllers

| Hoje                                          | Novo                                                  |
|-----------------------------------------------|-------------------------------------------------------|
| `ResponseEntity.ok(buildStatus(user))` (3×)   | `ResponseEntity.status(HttpStatus.OK).body(...)`      |
| `ResponseEntity.noContent().build()`          | `ResponseEntity.status(HttpStatus.NO_CONTENT).build()`|
| `ResponseEntity.status(UNAUTHORIZED).body(...)` | já segue o padrão                                   |

A skill `spring-clean` já aplica essa regra automaticamente. O controller continua na lib (#9),
então a regra vale aqui.

**Viabilidade:** trivial.

---

## 14. Controller só com a camada HTTP

**Hoje:** o controller tem `buildStatus`, `authenticatedStatus`, `anonymousStatus` e
`buildConfig`, ou seja, monta a resposta e lê properties. Por isso depende de
`SpringSessionLiteProperties`.

**Como fazer:** mover essa montagem para um `SentretHubStatusService.status(SentretUser)`. O controller
fica assim:
```java
@GetMapping("/status")
public ResponseEntity<SessionStatusResponse> status(@AuthenticationPrincipal SentretUser user) {
    return ResponseEntity.status(HttpStatus.OK).body(hubStatusService.status(user));
}
```
Com o #5.1, o tempo restante vem do principal e o service nem precisa ir ao store.

**Viabilidade:** baixa complexidade.

---

## 15. Records internos do controller

| Hoje (aninhado no controller)    | Novo arquivo                                  |
|----------------------------------|-----------------------------------------------|
| `SessionStatusResponse`          | `hub/dto/response/SessionStatusResponse.java` |
| `SessionStatusResponse.Config`   | `hub/dto/response/SessionConfigResponse.java` |
| `ErrorResponse`                  | some (#6)                                     |

- Nenhum endpoint recebe body, então não existe pacote `request`.
- `SpringSessionLiteSessionRemaining` também pode sumir: o cálculo vem do principal (#5.1).

**Viabilidade:** trivial.

---

## 16. Arquitetura final

```
io.github.sidneyroberto9.sentret
├── SentretAutoConfiguration.java        beans + SecurityFilterChain padrão
├── SentretProperties.java               record, 10 props, todas com padrão
├── session/
│   ├── SentretSession.java              record (o que é persistido)
│   ├── SentretService.java              login · validate · touch · renew · logout · logoutAll
│   ├── SentretUserService.java          currentUser() (usado por consumidor m4all)
│   ├── SentretCookieManager.java
│   └── event/                         Created · Destroyed · Renewed (records)
├── store/
│   ├── SentretSessionStore.java         interface (2 implementações reais)
│   ├── JdbcSentretSessionStore.java
│   └── InMemorySentretSessionStore.java
├── security/
│   ├── SentretAuthenticationFilter.java
│   └── SentretUser.java                 principal (record)
└── hub/                                 opt-in (sentret.hub.enabled)
    ├── SentretHubAutoConfiguration.java
    ├── SentretHubController.java        status · heartbeat · renew (só HTTP)
    ├── SentretHubStatusService.java     monta a resposta (#14)
    └── dto/response/                    SessionStatusResponse · SessionConfigResponse (#15)
```
Hoje são **25 classes** em `main`; a proposta deixa **~19**.

**O que sai e o que entra no lugar (nativo primeiro):**

| Sai                                                          | No lugar                                                     |
|--------------------------------------------------------------|--------------------------------------------------------------|
| `NanoId`                                                     | `SecureRandom` + `Base64` URL (20 caracteres)                |
| `@SpringSessionLiteCurrentSession` + ArgumentResolver + `WebMvcConfiguration` | `@AuthenticationPrincipal SentretUser` (nativo do Spring Security; nenhum consumidor m4all usa a anotação atual) |
| JSON inline no entry point + `ErrorResponse`                 | `HttpStatusEntryPoint`                                       |
| Entidade JPA + repository + `@AutoConfigurationPackage`      | `JdbcTemplate` / `ConcurrentHashMap`                         |
| `CleanupTask` + `@EnableScheduling`                          | `DELETE` de expiradas no login                               |
| `SecurityValidator`                                          | 1 `if` + `log.warn` na autoconfig                            |
| IP hasher/resolver, roles, `SessionRemaining`                | —                                                            |
| Endpoint `/logout`                                           | `SentretService.logout()` chamado pelo logout da própria app |
| Lombok                                                       | records e construtores                                       |
| Construtores de compatibilidade dos eventos (pré-2.1/2.3) e Javadocs históricos ("since 2.1.1…") | Major nova não precisa deles; o histórico fica no CHANGELOG |

**Checagem contra os objetivos:**

| Objetivo                        | Como fica                                                              |
|---------------------------------|------------------------------------------------------------------------|
| Menor acoplamento               | Sem JPA/Hibernate, sem `@EnableScheduling` global, sem Jackson         |
| Menos configuração              | De 32 propriedades para 10 no núcleo + 5 do hub, nenhuma obrigatória   |
| Menos código                    | De 25 classes para ~19 em `main`                                       |
| API pública menor               | `SentretService`, `SentretUserService`, `SentretUser`, 3 eventos, `SentretSessionStore`; o hub fica com 3 endpoints (antes 4) |
| Boot 3 e 4                      | Nenhuma classe de autoconfig referenciada; CI com os 2 profiles       |
| Separação de camadas            | Config / Service / Store / Security / Hub (Controller → Service → DTO) |
| Nativo antes de utilitário próprio | `SecureRandom` + `Base64`, `HttpStatusEntryPoint`, `@AuthenticationPrincipal`, `JdbcTemplate` |
