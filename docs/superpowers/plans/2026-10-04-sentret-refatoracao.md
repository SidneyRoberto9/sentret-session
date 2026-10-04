# Sentret — Plano de Implementação da Refatoração

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transformar a biblioteca `spring-session-lite` 3.0.0 na `sentret-session` 1.0.0: renomeada, sem IP nem roles, persistida via `JdbcTemplate` (sem JPA), com um modelo de atividade mais barato, menos propriedades, hub de inatividade enxuto e compatível com Spring Boot 3 e 4.

**Architecture:** O núcleo continua sendo um filtro de autenticação que lê um cookie opaco, valida a sessão no banco da aplicação (agora com SQL puro via `JdbcTemplate`) e publica um `SentretUser` como principal. A atividade do usuário continua vindo **apenas** do `POST /heartbeat` (agora um único `UPDATE`). O hub opcional (`sentret.hub.*`) expõe só `status`, `heartbeat` e `renew`, com Controller → Service → DTOs separados.

**Tech Stack:** Java 17, Spring Boot 3.5.15 (e 4.1.1 via profile `boot4`), Spring Security, Spring JDBC (`JdbcTemplate`), Lombok (mantido), JUnit 5, AssertJ, Mockito, MockMvc, H2 (testes), Maven Wrapper.

**Spec:** `docs/07-proposta-refatoracao.md` (pontos 1–16) + decisões do usuário em 2026-10-04:
- #1 nome **Sentret**; #4 **JdbcTemplate**; #6 **opção A** (`HttpStatusEntryPoint`); #7 **apagar a task** (limpeza no login); #9 **enxugar** (mantém `status`/`heartbeat`/`renew`, remove `logout`, props em `sentret.hub.*`); #12 **`SecureRandom` + Base64 URL (15 bytes → 20 caracteres)**.

## Ajustes em relação ao doc 07

O doc 07 foi atualizado com esta mesma tabela. Onde o texto antigo dos pontos diverge, vale esta:

| Ponto | Decisão | Diferença em relação à proposta original |
|---|---|---|
| #1 | Nome **Sentret** | — |
| #4 | Só **`JdbcTemplate`** | Sem fallback em memória. Colunas de tempo em **BIGINT (epoch millis)** em vez de `TIMESTAMP`: a mesma DDL serve MySQL/PostgreSQL/SQL Server/H2, sem conversão de fuso nem limite de 2038. |
| #5 | Só o heartbeat conta como atividade, com **um `UPDATE`** e **sem throttle** | Os itens 5.2 (qualquer request = atividade) e 5.3 (throttle de 60s) saíram: requests de fundo da própria app manteriam a sessão viva para sempre, e o throttle poderia atrasar o único sinal de atividade (o bug que já deslogou gente ativa). Ficam 5.1 (principal com os prazos, sem reler a linha) e 5.5. O 5.4 (poll adaptativo) é no client npm, fora deste repositório. |
| #6 | Opção **A** (`HttpStatusEntryPoint`) | — |
| #7 / #8 | **Apagar a task**; limpar expiradas no login | — |
| #9 | **Enxugar**: `status`, `heartbeat`, `renew` em `sentret.hub.*`; sem `logout` | — |
| #10 | BOM do Boot importado + profile `boot4` | As dependências do Boot continuam com escopo normal: o BOM da app consumidora já decide as versões. |
| #11 | 10 propriedades no núcleo + 6 em `sentret.hub.*` | `SentretProperties` continua JavaBean com Lombok (padrão do código, menos retrabalho nos testes). O validator mantém as checagens de tempo do hub, que continuam úteis. |
| #12 | `SecureRandom` + Base64 URL, 15 bytes → 20 caracteres | — |

## Ordem de implementação

| Tarefa | Pontos | Por que nesta posição |
|---|---|---|
| 1. Renomear para Sentret | #1 | Primeiro: todo código dos passos seguintes já nasce com o nome final (o rename continua isolado num commit próprio, só que sem retrabalho). |
| 2. Testes de integração com APIs estáveis | prepara #10 | Tira `TestRestTemplate`/`@AutoConfigureMockMvc` (mudaram de módulo no Boot 4) antes de os testes serem editados pelas próximas tarefas. |
| 3. Remover IP | #2 | Corte puro, sem dependências. |
| 4. Remover roles | #3 | Corte puro; simplifica o `SentretUser` antes do redesenho da tarefa 7. |
| 5. Session ID com o JDK | #12 | Isolado, toca só o `login`. |
| 6. 401 sem JSON inline | #6 | Isolado, toca só a cadeia de segurança. |
| 7. JDBC + modelo de atividade | #4, #5 | Os dois redesenham o mesmo par Service/Store; juntos evitam reescrever o `SentretServiceTest` duas vezes. |
| 8. Limpeza no login, sem task | #7, #8 | Depende do store novo (tarefa 7). |
| 9. Propriedades | #11 | Depois que IP/roles/ID/cleanup/sliding já saíram, sobra só reorganizar. |
| 10. Hub enxuto | #9, #13, #14, #15 | Usa o principal com prazos (tarefa 7) e as props `sentret.hub.*` (tarefa 9). |
| 11. Boot 3 + 4 | #10 | Só passa no Boot 4 depois que o JPA saiu (tarefa 7). |
| 12. Nativos, docs e revisão final | #16 | Fecha a arquitetura e a documentação. |

## Global Constraints

- Java **17** (`maven.compiler.release=17`); Spring Boot **3.5.x** (padrão) e **4.x** (profile `boot4`).
- Pacote `io.github.sidneyroberto9.sentret`; prefixo de classes `Sentret`; prefixo de propriedades `sentret`; artifactId `sentret-session`; versão `1.0.0`; cookie padrão `SENTRETSID`.
- Tabela `sentret_sessions`; colunas de tempo em **BIGINT (epoch millis)**; `session_id VARCHAR(20)`.
- Session ID: 15 bytes de `SecureRandom` → `Base64.getUrlEncoder().withoutPadding()` → **20 caracteres** `[A-Za-z0-9_-]`.
- Atividade: **só** `POST {base-path}/heartbeat` grava `last_accessed_at`, com **um único `UPDATE`** e **sem throttle**. Nenhuma outra request grava.
- 401 sempre por `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` — **sem corpo**.
- A lib **não** usa `@EnableScheduling` nem `@Transactional`.
- Hub: `GET status` (permit-all), `POST heartbeat`, `POST renew`. **Sem** `logout`. `config` só com `heartbeatIntervalMs`, `statusPollIntervalMs`, `warningBeforeMs`, `loginUrl`.
- Estilo m4all (skill `spring-clean`): injeção por construtor, `ResponseEntity.status(HttpStatus.X).body(...)`, `if` sempre com bloco, **sem ternário** em código novo.
- Lombok **continua** (`@RequiredArgsConstructor`, `@Getter`, `@Setter`, `@Slf4j`) — é o padrão do código existente.
- **Nunca rodar `git commit` direto.** Todo passo "Commit" invoca a skill `auto-commit` (regra do `~/.claude/CLAUDE.md`; um hook bloqueia commits fora do formato). A mensagem sugerida é só referência.
- Trabalhar na branch `refactor/sentret` (criada na Tarefa 1).
- Comando de teste: `./mvnw test` (os avisos do JaCoCo "Unsupported class file major version 69" no JDK 25 são ruído até a Tarefa 11; o que importa é `BUILD SUCCESS` e `Failures: 0, Errors: 0`).

## Review Focus

1. **Hub com `base-path` customizado** (o eleva-docs usa `/api/lite/session`): `status` precisa ser permit-all nesse caminho e `/session/*` não pode ficar exposto → teste `defaultBasePathIsNotExposed` + IT inteira com base-path customizado na **Tarefa 10**.
2. **`Instant` com precisão sub-milissegundo** (`Instant.now()` no Linux tem micro/nanos; o banco guarda millis): ida-e-volta deve truncar sem erro → teste `insertTruncatesToMilliseconds` na **Tarefa 7**.
3. **Cookie adulterado/gigante** (valor de 100 caracteres contra `VARCHAR(20)`): deve dar 401 limpo e apagar o cookie, nunca 500 → teste `meWithTamperedCookieReturns401AndClearsCookie` na **Tarefa 7**.
4. **`max-idle=0`** (inatividade desligada): heartbeat não grava nada e o status omite `idleRemainingMs` → `touchIsNoOpWhenIdleDisabled` (**Tarefa 7**) e `idleRemainingIsNullWhenMaxIdleDisabled` (**Tarefa 10**).
5. **App consumidora que dependia do `@EnableScheduling` da lib** para os próprios `@Scheduled`: depois da Tarefa 8 eles param em silêncio → teste `libraryDoesNotEnableSchedulingInTheHostApplication` (**Tarefa 8**) + aviso explícito no `MIGRATION.md` (**Tarefa 12**).

---

### Task 1: Renomear para Sentret (#1)

**Files:**
- Move: `src/main/java/io/github/sidneyroberto9/spring_session_lite/` → `src/main/java/io/github/sidneyroberto9/sentret/`
- Move: `src/test/java/io/github/sidneyroberto9/spring_session_lite/` → `src/test/java/io/github/sidneyroberto9/sentret/`
- Rename: todo arquivo `*SpringSessionLite*.java` → `*Sentret*.java`
- Modify: todos os `.java`, `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `src/test/resources/application.properties`, `pom.xml`

**Interfaces:**
- Consumes: nada.
- Produces: nomes finais usados por todas as tarefas: `SentretAutoConfiguration`, `SentretEndpointsAutoConfiguration`, `SentretWebMvcConfiguration`, `SentretProperties`, `SentretSecurityValidator`, `SentretSession` (entidade JPA, pacote `domain`), `SentretSessionRepository`, `SentretSessionCreatedEvent`, `SentretSessionDestroyedEvent`, `SentretSessionRenewedEvent`, `SentretCleanupTask`, `SentretAuthenticationFilter`, `SentretUser`, `SentretCookieManager`, `SentretIpHasher`, `SentretIpResolver`, `SentretService`, `SentretSessionRemaining`, `SentretUserService`, `JpaSentretSessionStore`, `SentretSessionStore`, `SentretCurrentSession`, `SentretCurrentSessionArgumentResolver`, `SentretSessionController`; testes `SentretApplicationTests`, `SentretServiceTest` etc. Beans: `sentretService`, `sentretSessionStore`, `sentretAuthenticationFilter`, `sentretSecurityFilterChain`, …

- [ ] **Step 1: Criar a branch**

```bash
git switch -c refactor/sentret
```

- [ ] **Step 2: Mover pacotes e renomear arquivos**

```bash
git mv src/main/java/io/github/sidneyroberto9/spring_session_lite src/main/java/io/github/sidneyroberto9/sentret
git mv src/test/java/io/github/sidneyroberto9/spring_session_lite src/test/java/io/github/sidneyroberto9/sentret
find src -name '*SpringSessionLite*' | while read -r f; do git mv "$f" "${f//SpringSessionLite/Sentret}"; done
```

- [ ] **Step 3: Renomear conteúdo (pacote, classes, beans, prefixo, cookie)**

```bash
grep -rlZ -e 'spring_session_lite' -e 'SpringSessionLite' -e 'springSessionLite' -e 'sessionLite' -e 'spring-session-lite' -e 'SLSID' src \
  | xargs -0 perl -pi -e '
      s/io\.github\.sidneyroberto9\.spring_session_lite/io.github.sidneyroberto9.sentret/g;
      s/SpringSessionLite/Sentret/g;
      s/springSessionLite/sentret/g;
      s/sessionLiteSecurityFilterChain/sentretSecurityFilterChain/g;
      s/spring-session-lite/sentret/g;
      s/SLSID/SENTRETSID/g'
```

Isso troca também o prefixo `@ConfigurationProperties(prefix = "sentret")`, os `@ConditionalOnProperty(prefix = "sentret")`, os placeholders `${sentret.cleanup-cron:...}` / `${sentret.endpoints-base-path:/session}`, as mensagens `[sentret]` do validator, os `@TestPropertySource` e o `src/test/resources/application.properties`. A tabela `spring_session_lite_sessions` **não** muda aqui (é recriada na Tarefa 7).

- [ ] **Step 4: Atualizar o `pom.xml`**

Trocar o bloco de identificação:

```xml
    <groupId>io.github.sidneyroberto9</groupId>
    <artifactId>sentret-session</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>Sentret</name>

    <description>
        Lightweight cookie-based session authentication starter for Spring Boot
    </description>
```

(`<url>`/`<scm>` continuam apontando para o repositório atual; renomear o repositório no GitHub é decisão fora deste plano.)

- [ ] **Step 5: Verificar que não sobrou nome antigo**

Run: `grep -rnE "spring-session-lite|SpringSessionLite|springSessionLite|sessionLite|io\.github\.sidneyroberto9\.spring_session_lite|SLSID" src`
Expected: nenhuma saída. (A tabela `spring_session_lite_sessions` usa underscore e não casa com o padrão; ela continua existindo até a Tarefa 7.)

- [ ] **Step 6: Rodar a suíte**

Run: `./mvnw test`
Expected: `Tests run: 130, Failures: 0, Errors: 0` e `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

Invocar a skill `auto-commit`. Mensagem sugerida: `refactor!: rename library to Sentret`.

---

### Task 2: Testes de integração só com APIs estáveis no Boot 3 e 4 (preparação do #10)

**Files:**
- Modify (reescrever): `src/test/java/io/github/sidneyroberto9/sentret/SentretApplicationTests.java`
- Modify: `src/test/java/io/github/sidneyroberto9/sentret/integration/SentretSessionControllerIntegrationTest.java`
- Modify: `src/test/java/io/github/sidneyroberto9/sentret/integration/SentretAutoConfigurationIntegrationTest.java`

**Interfaces:**
- Consumes: nomes da Tarefa 1.
- Produces: padrão de teste de integração usado daqui em diante — `@SpringBootTest(classes = SampleApplication.class)` + `MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()` num `@BeforeEach`; corpo JSON escrito à mão (sem `ObjectMapper`, que é Jackson 2 no Boot 3 e Jackson 3 no Boot 4).

- [ ] **Step 1: Reescrever `SentretApplicationTests` com MockMvc**

Conteúdo completo de `src/test/java/io/github/sidneyroberto9/sentret/SentretApplicationTests.java`:

```java
package io.github.sidneyroberto9.sentret;

import io.github.sidneyroberto9.sentret.domain.SentretSessionRepository;
import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end behaviour through the real security filter chain. Uses only test APIs that are the
 * same in Spring Boot 3 and 4 (no TestRestTemplate, no @AutoConfigureMockMvc).
 */
@SpringBootTest(classes = SampleApplication.class)
class SentretApplicationTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SentretSessionRepository sessionRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        sessionRepository.deleteAll();
    }

    private Cookie login(String userId, String email) throws Exception {
        return loginWithBody("{\"userId\":\"" + userId + "\",\"email\":\"" + email + "\"}");
    }

    private Cookie loginWithBody(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void expire(String sessionId) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setExpiresAt(Instant.now().minusSeconds(60));
            sessionRepository.save(session);
        });
    }

    @Test
    void contextLoads() {
    }

    @Test
    void loginWritesCookieWithCorrectAttributes() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"1\",\"email\":\"test@test.com\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("SENTRETSID=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
        assertThat(setCookie).containsIgnoringCase("Max-Age=28800");
    }

    @Test
    void meWithValidCookieReturns200() throws Exception {
        Cookie cookie = login("user1", "user1@test.com");

        mockMvc.perform(get("/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.email").value("user1@test.com"));
    }

    @Test
    void meWithNoCookieReturns401() throws Exception {
        mockMvc.perform(get("/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginEndpointReachableWithoutSession() throws Exception {
        login("x", "x@x.com");
    }

    @Test
    void meWithTamperedCookieReturns401() throws Exception {
        mockMvc.perform(get("/me").cookie(new Cookie("SENTRETSID", "tampered-session-id-that-does-not-exist")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithExpiredSessionReturns401() throws Exception {
        Cookie cookie = login("user2", "user2@test.com");
        expire(cookie.getValue());

        mockMvc.perform(get("/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void cleanupRemovesExpiredSessions() throws Exception {
        Cookie cookie = login("user3", "user3@test.com");
        expire(cookie.getValue());
        assertThat(sessionRepository.count()).isEqualTo(1);

        sessionRepository.deleteByExpiresAtBefore(Instant.now());

        assertThat(sessionRepository.count()).isZero();
    }

    @Test
    void ipMismatchReturns401() throws Exception {
        Cookie cookie = login("user4", "user4@test.com");
        sessionRepository.findBySessionId(cookie.getValue()).ifPresent(session -> {
            session.setIpHash("0000000000000000000000000000000000000000000000000000000000000000");
            sessionRepository.save(session);
        });

        mockMvc.perform(get("/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithDeadCookieStillReaches200() throws Exception {
        // A stale/expired cookie must NOT block re-login on a permit-all path.
        Cookie cookie = login("user5", "user5@test.com");
        expire(cookie.getValue());

        mockMvc.perform(post("/login").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user5\",\"email\":\"user5@test.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutClearsCookieAndSession() throws Exception {
        Cookie cookie = login("user6", "user6@test.com");
        assertThat(sessionRepository.findBySessionId(cookie.getValue())).isPresent();

        MvcResult result = mockMvc.perform(post("/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).containsIgnoringCase("Max-Age=0");
        assertThat(sessionRepository.findBySessionId(cookie.getValue())).isEmpty();
    }

    @Test
    void loginWithRolesExposesRolesInPrincipal() throws Exception {
        Cookie cookie = loginWithBody("{\"userId\":\"user7\",\"email\":\"user7@test.com\",\"roles\":[\"ADMIN\",\"USER\"]}");

        mockMvc.perform(get("/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ADMIN", "USER")));
    }
}
```

- [ ] **Step 2: Trocar `@AutoConfigureMockMvc` e `ObjectMapper` no `SentretSessionControllerIntegrationTest`**

1. Remover os imports `com.fasterxml.jackson.databind.ObjectMapper` e `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`, a anotação `@AutoConfigureMockMvc` e o campo `ObjectMapper objectMapper`.
2. Adicionar os imports:

```java
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.stream.Collectors;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
```

3. Trocar o campo `@Autowired private MockMvc mockMvc;` por:

```java
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
```

4. Trocar o `cleanDb()` e o helper `login(...)` por:

```java
    @BeforeEach
    void cleanDb() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        sessionRepository.deleteAll();
    }

    private Cookie login(String userId, String email, List<String> roles) throws Exception {
        String rolesJson = roles.stream().map(role -> "\"" + role + "\"").collect(Collectors.joining(",", "[", "]"));
        String body = "{\"userId\":\"" + userId + "\",\"email\":\"" + email + "\",\"roles\":" + rolesJson + "}";

        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }
```

- [ ] **Step 3: Trocar `@AutoConfigureMockMvc` no `SentretAutoConfigurationIntegrationTest`**

Remover o import e a anotação `@AutoConfigureMockMvc`; trocar o campo `@Autowired private MockMvc mockMvc;` por:

```java
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
```

com os imports `org.junit.jupiter.api.BeforeEach`, `org.springframework.test.web.servlet.setup.MockMvcBuilders`, `org.springframework.web.context.WebApplicationContext` e `static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity`.

- [ ] **Step 4: Garantir que nenhum teste usa as APIs que mudaram no Boot 4**

Run: `grep -rnE "TestRestTemplate|AutoConfigureMockMvc|LocalServerPort|databind\.ObjectMapper" src/test`
Expected: nenhuma saída.

- [ ] **Step 5: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS` (130 testes).

- [ ] **Step 6: Commit**

Invocar `auto-commit`. Sugestão: `test: drive integration tests through MockMvc built from the context`.

---

### Task 3: Remover IP / ipHash / ip-hash-salt (#2)

**Files:**
- Delete: `src/main/java/io/github/sidneyroberto9/sentret/service/SentretIpHasher.java`, `.../service/SentretIpResolver.java`, `src/test/java/io/github/sidneyroberto9/sentret/unit/SentretIpHasherTest.java`, `.../unit/SentretIpResolverTest.java`
- Modify: `config/SentretProperties.java`, `config/SentretSecurityValidator.java`, `domain/SentretSession.java`, `autoconfigure/SentretAutoConfiguration.java`, `service/SentretService.java`, `security/SentretAuthenticationFilter.java`, `src/main/resources/db/spring-session-lite-schema.sql`
- Modify (testes): `SentretApplicationTests.java`, `unit/SentretServiceTest.java`, `unit/SentretAuthenticationFilterTest.java`, `unit/SentretSecurityValidatorTest.java`, `sample/SampleController.java`, `src/test/resources/application.properties`

**Interfaces:**
- Consumes: Tarefa 2.
- Produces: `SentretService(SentretSessionStore store, SentretProperties properties, ApplicationEventPublisher eventPublisher, SentretCookieManager cookieManager)` (ordem dos campos para o `@RequiredArgsConstructor`); `Optional<SentretUser> validate(String sessionId)`; `SentretUser login(String userId, String email, HttpServletResponse response)` e `login(String userId, String email, List<String> roles, HttpServletResponse response)`.

- [ ] **Step 1: Escrever o teste que falha — sessão sobrevive à troca de IP**

Em `SentretApplicationTests`, **apagar** o teste `ipMismatchReturns401` e adicionar:

```java
    /**
     * Mobile, VPN and corporate networks change the client IP mid-session. The session is bound to
     * the cookie only, so a new IP must not log the user out.
     */
    @Test
    void sessionSurvivesClientIpChange() throws Exception {
        Cookie cookie = login("user4", "user4@test.com");

        mockMvc.perform(get("/me").cookie(cookie).with(request -> {
                    request.setRemoteAddr("198.51.100.77");
                    return request;
                }))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=SentretApplicationTests#sessionSurvivesClientIpChange`
Expected: FAIL — `Status expected:<200> but was:<401>`.

- [ ] **Step 3: Remover IP do código principal**

1. Apagar `service/SentretIpHasher.java` e `service/SentretIpResolver.java`.
2. `config/SentretProperties.java`: apagar a constante `DEFAULT_IP_HASH_SALT` e os campos `ipHashSalt`, `trustForwardedFor`, `trustedProxyCount`, cada um com seu bloco Javadoc.
3. `config/SentretSecurityValidator.java`: apagar o primeiro `if` (o que compara com `DEFAULT_IP_HASH_SALT`).
4. `domain/SentretSession.java`: apagar

```java
    @Column(name = "ip_hash", nullable = false, length = 64)
    private String ipHash;
```

5. `src/main/resources/db/spring-session-lite-schema.sql`: apagar as duas linhas `ip_hash ...` (variante MySQL e a comentada do PostgreSQL).
6. `autoconfigure/SentretAutoConfiguration.java`: apagar os beans `ipResolver(...)` e `ipHasher(...)` e seus imports; o bean do service fica:

```java
    @Bean
    @ConditionalOnMissingBean
    public SentretService sentretService(
            SentretProperties properties,
            SentretSessionStore store,
            SentretCookieManager cookieManager,
            ApplicationEventPublisher eventPublisher
    ) {
        return new SentretService(store, properties, eventPublisher, cookieManager);
    }
```

7. `service/SentretService.java`:
   - Apagar os campos `ipHasher` e `ipResolver` (ficam, nesta ordem: `store`, `properties`, `eventPublisher`, `cookieManager`).
   - Trocar os dois `login(...)` por:

```java
    @Transactional
    public SentretUser login(String userId, String email, HttpServletResponse response) {
        return this.login(userId, email, List.of(), response);
    }

    @Transactional
    public SentretUser login(String userId, String email, List<String> roles, HttpServletResponse response) {
```

     e, no corpo, apagar a linha `session.setIpHash(ipHasher.hash(ipResolver.resolve(request)));`.
   - Trocar a assinatura `validate(String sessionId, HttpServletRequest request)` por `validate(String sessionId)` e apagar o bloco:

```java
            String currentIpHash = ipHasher.hash(ipResolver.resolve(request));

            if (!session.getIpHash().equals(currentIpHash)) {
                return Optional.empty();
            }
```

   - No Javadoc de `remaining(String)`, trocar `{@link #validate(String, HttpServletRequest)}` por `{@link #validate(String)}`.
8. `security/SentretAuthenticationFilter.java`: trocar `sessionService.validate(sessionId, request)` por `sessionService.validate(sessionId)` e, no comentário, `Invalid/expired/IP-mismatch cookie` por `Invalid/expired cookie`.

- [ ] **Step 4: Ajustar o código de teste**

1. Apagar `unit/SentretIpHasherTest.java` e `unit/SentretIpResolverTest.java`.
2. `src/test/resources/application.properties`: apagar a linha `sentret.trust-forwarded-for=true`.
3. `sample/SampleController.java` — o login fica:

```java
    @PostMapping("/login")
    public ResponseEntity<SentretUser> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        List<String> roles = body.getRoles() == null ? List.of() : body.getRoles();
        SentretUser user = sessionService.login(body.getUserId(), body.getEmail(), roles, response);
        return ResponseEntity.ok(user);
    }
```

4. `unit/SentretServiceTest.java` — chamadas e helpers:

```bash
T=src/test/java/io/github/sidneyroberto9/sentret/unit/SentretServiceTest.java
perl -0pi -e '
  s/sessionFor\("[0-9.]+", /sessionFor(/g;
  s/service\.validate\("sid", request\("[0-9.]+"\)\)/service.validate("sid")/g;
  s/\n\s*session\.setIpHash\(ipHasher\.hash\(ip\)\);//g;
  s/private SentretSession sessionFor\(String ip, Instant now\)/private SentretSession sessionFor(Instant now)/;
  s/\n\s*MockHttpServletRequest request = request\("[0-9.]+"\);//g;
  s/, request, response\)/, response)/g;
  s/\n    private MockHttpServletRequest request\(String ip\) \{.*?\n    \}\n//s;
  s/\n    private SentretIpHasher ipHasher;//;
' "$T"
```

   e trocar o `setUp()` por:

```java
    @BeforeEach
    void setUp() {
        properties = new SentretProperties();
        store = mock(SentretSessionStore.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        cookieManager = new SentretCookieManager(properties);

        service = new SentretService(store, properties, eventPublisher, cookieManager);
    }
```

   Apagar os imports `SentretIpHasher` e `SentretIpResolver`.
5. `unit/SentretAuthenticationFilterTest.java`:

```bash
F=src/test/java/io/github/sidneyroberto9/sentret/unit/SentretAuthenticationFilterTest.java
perl -0pi -e '
  s/\n    private static final String IP = "203\.0\.113\.1";\n//;
  s/\n    private SentretIpHasher ipHasher;//;
  s/\n\s*session\.setIpHash\(ipHasher\.hash\(IP\)\);//g;
  s/\n\s*request\.setRemoteAddr\(IP\);//g;
' "$F"
```

   e trocar o `setUp()` por:

```java
    @BeforeEach
    void setUp() {
        properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(2));
        store = mock(SentretSessionStore.class);
        cookieManager = new SentretCookieManager(properties);

        SentretService service = new SentretService(store, properties, mock(ApplicationEventPublisher.class), cookieManager);

        filter = new SentretAuthenticationFilter(service, cookieManager);
    }
```

   Apagar os imports `SentretIpHasher` e `SentretIpResolver`.
6. `unit/SentretSecurityValidatorTest.java`: apagar o helper `saltWarnings()` e os testes `doesNotWarnAboutSaltWhenCookieIsNotSecure` e `doesNotWarnAboutSaltWhenCookieSecureWithCustomSalt`.

- [ ] **Step 5: Confirmar que nada de IP sobrou**

Run: `grep -rniE "ipHash|ip_hash|IpHasher|IpResolver|forwarded|trustedProxy|setRemoteAddr\(IP" src`
Expected: só o `request.setRemoteAddr("198.51.100.77")` do teste novo.

- [ ] **Step 6: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`; `sessionSurvivesClientIpChange` passa.

- [ ] **Step 7: Commit**

Invocar `auto-commit`. Sugestão: `feat!: drop client IP binding from sessions`.

---

### Task 4: Remover roles da sessão (#3)

**Files:**
- Modify (reescrever): `security/SentretUser.java`, `security/SentretAuthenticationFilter.java`, `src/test/java/.../sample/SampleController.java`, `unit/SentretUserTest.java`
- Modify: `domain/SentretSession.java`, `service/SentretService.java`, `web/controller/SentretSessionController.java`, `src/main/resources/db/spring-session-lite-schema.sql`
- Modify (testes): `SentretApplicationTests.java`, `unit/SentretServiceTest.java`, `unit/SentretAuthenticationFilterTest.java`, `unit/SentretSessionControllerTest.java`, `unit/SentretUserServiceTest.java`, `unit/SentretCurrentSessionArgumentResolverTest.java`, `integration/SentretSessionControllerIntegrationTest.java`

**Interfaces:**
- Consumes: Tarefa 3 (`login(userId, email, roles, response)`).
- Produces: `record SentretUser(String userId, String email, String sessionId)`; `SentretUser login(String userId, String email, HttpServletResponse response)` (único `login`); principal autenticado com `List.of()` de authorities; `SampleController.LoginRequest` vira `record LoginRequest(String userId, String email)`.

- [ ] **Step 1: Escrever os testes que falham**

Conteúdo completo de `unit/SentretUserTest.java`:

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

class SentretUserTest {

    /** Roles are the host application's concern; the principal only identifies the user. */
    @Test
    void principalCarriesOnlyIdentity() {
        assertThat(SentretUser.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "email", "sessionId");
    }

    @Test
    void accessorsReturnConstructorValues() {
        SentretUser user = new SentretUser("u1", "u1@example.com", "s1");

        assertThat(user.userId()).isEqualTo("u1");
        assertThat(user.email()).isEqualTo("u1@example.com");
        assertThat(user.sessionId()).isEqualTo("s1");
    }
}
```

Em `SentretApplicationTests`, apagar `loginWithRolesExposesRolesInPrincipal` (e o import `containsInAnyOrder`) e acrescentar no `meWithValidCookieReturns200`:

```java
                .andExpect(jsonPath("$.roles").doesNotExist());
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='SentretUserTest,SentretApplicationTests'`
Expected: FAIL — `principalCarriesOnlyIdentity` encontra o componente `roles` e `meWithValidCookieReturns200` encontra `$.roles` (`[]`). (O construtor de 3 argumentos ainda existe como secundário, então compila.)

- [ ] **Step 3: Implementar**

Conteúdo completo de `security/SentretUser.java`:

```java
package io.github.sidneyroberto9.sentret.security;

/**
 * Immutable authenticated principal exposed by the library. Populated by the
 * {@link SentretAuthenticationFilter}. Authorization data (roles, permissions) belongs to the host
 * application, looked up by {@link #userId()}.
 */
public record SentretUser(String userId, String email, String sessionId) {
}
```

Conteúdo completo de `security/SentretAuthenticationFilter.java`:

```java
package io.github.sidneyroberto9.sentret.security;

import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class SentretAuthenticationFilter extends OncePerRequestFilter {

    private final SentretService sessionService;
    private final SentretCookieManager cookieManager;

    public SentretAuthenticationFilter(SentretService sessionService, SentretCookieManager cookieManager) {
        this.sessionService = sessionService;
        this.cookieManager = cookieManager;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String sessionId = cookieManager.read(request);

        if (sessionId == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<SentretUser> user = sessionService.validate(sessionId);

        if (user.isEmpty()) {
            // Invalid/expired cookie: do NOT short-circuit with 401 here — that would also block
            // permit-all paths (e.g. re-login). Drop the dead cookie, stay anonymous, and let
            // authorization + the AuthenticationEntryPoint decide the response.
            SecurityContextHolder.clearContext();
            cookieManager.clear(response);
            chain.doFilter(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user.get(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }
}
```

`domain/SentretSession.java`: apagar

```java
    @Column(name = "roles")
    private String roles;
```

`src/main/resources/db/spring-session-lite-schema.sql`: apagar as duas linhas `roles ...`.

`service/SentretService.java`: trocar os dois `login(...)` por um único:

```java
    @Transactional
    public SentretUser login(String userId, String email, HttpServletResponse response) {
        Instant now = Instant.now();

        SentretSession session = new SentretSession();
        session.setSessionId(NanoId.generate(properties.getSessionIdLength()));
        session.setUserId(userId);
        session.setEmail(email);
        session.setCreatedAt(now);
        session.setLastAccessedAt(now);
        session.setExpiresAt(now.plus(properties.getTtl()));

        store.save(session);
        cookieManager.write(response, session.getSessionId());
        eventPublisher.publishEvent(new SentretSessionCreatedEvent(userId, session.getSessionId(), now));

        return toUser(session);
    }
```

trocar `toUser` por:

```java
    private SentretUser toUser(SentretSession session) {
        return new SentretUser(session.getUserId(), session.getEmail(), session.getSessionId());
    }
```

e apagar `joinRoles`, `splitRoles` e os imports `java.util.Arrays` e `java.util.List`.

`web/controller/SentretSessionController.java`: no record `SessionStatusResponse`, apagar o componente `List<String> roles`; em `authenticatedStatus(...)` apagar o argumento `user.roles(),`; em `anonymousStatus(...)` a linha vira `return new SessionStatusResponse(false, null, null, null, null, config);`; apagar o import `java.util.List`.

Conteúdo completo de `src/test/java/io/github/sidneyroberto9/sentret/sample/SampleController.java`:

```java
package io.github.sidneyroberto9.sentret.sample;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.web.SentretCurrentSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SampleController {

    private final SentretService sessionService;

    @PostMapping("/login")
    public ResponseEntity<SentretUser> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        SentretUser user = sessionService.login(body.userId(), body.email(), response);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionService.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<SentretUser> me(@SentretCurrentSession SentretUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(user);
    }

    public record LoginRequest(String userId, String email) {
    }
}
```

- [ ] **Step 4: Ajustar os demais testes**

```bash
grep -rlZ "new SentretUser(" src/test | xargs -0 perl -pi -e 's/new SentretUser\(("[^"]*"), ("[^"]*"), ("[^"]*"), (?:List\.of\([^)]*\)|null)\)/new SentretUser($1, $2, $3)/g'
```

Depois, à mão:
1. `unit/SentretServiceTest.java`: apagar os testes `loginWithoutRolesDelegatesToRolesOverloadWithEmptyRoles`, `loginWithNullRolesStoresNullRoles`, `validateSplitsBlankStoredRolesAsEmptyList`, `validateFiltersOutBlankEntriesFromStoredRoles`.
2. `unit/SentretAuthenticationFilterTest.java`: trocar `authenticatesWithBothBareAndPrefixedRoleNames` por

```java
    @Test
    void authenticatesWithTheUserAsPrincipalAndNoAuthorities() throws Exception {
        sessionIdleFor(Duration.ofSeconds(1), Instant.now());

        doFilter(request("GET", "/api/documents"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isInstanceOf(SentretUser.class);
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).isEmpty();
    }
```

   (imports `org.springframework.security.core.Authentication` e `io.github.sidneyroberto9.sentret.security.SentretUser`).
3. `unit/SentretSessionControllerTest.java`: apagar a linha `assertThat(response.getBody().roles()).containsExactly("ADMIN");`.
4. `unit/SentretUserServiceTest.java`: apagar a linha `assertThat(result.get().roles()).containsExactly("ROLE_USER");`.
5. `integration/SentretSessionControllerIntegrationTest.java`: o helper vira

```java
    private Cookie login(String userId, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }
```

   trocar todas as chamadas `login("x", "y", List.of(...))` por `login("x", "y")`, apagar a linha `.andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("ADMIN", "USER")))` e os imports `java.util.List` e `java.util.stream.Collectors`. Manter `.andExpect(jsonPath("$.roles").doesNotExist())` no teste anônimo.

- [ ] **Step 5: Confirmar que nada de roles sobrou no código principal**

Run: `grep -rniE "roles|ROLE_|GrantedAuthority" src/main`
Expected: nenhuma saída.

- [ ] **Step 6: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

Invocar `auto-commit`. Sugestão: `feat!: drop roles from the session principal`.

---

### Task 5: Session ID com `SecureRandom` + Base64 URL (#12)

**Files:**
- Delete: `src/main/java/io/github/sidneyroberto9/sentret/util/NanoId.java`, `src/test/java/io/github/sidneyroberto9/sentret/unit/NanoIdTest.java`
- Modify: `service/SentretService.java`, `config/SentretProperties.java`, `unit/SentretServiceTest.java`

**Interfaces:**
- Consumes: `login(String, String, HttpServletResponse)` da Tarefa 4.
- Produces: IDs de sessão de 20 caracteres `[A-Za-z0-9_-]`; `private static String newSessionId()` dentro do `SentretService` (reaproveitado na Tarefa 7).

- [ ] **Step 1: Escrever o teste que falha**

Adicionar em `unit/SentretServiceTest.java` (imports `java.util.HashSet`, `java.util.Set`, `org.springframework.mock.web.MockHttpServletResponse` se faltar):

```java
    @Test
    void loginGeneratesTwentyCharUrlSafeUniqueSessionIds() {
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            ids.add(service.login("user-1", "user@test.com", new MockHttpServletResponse()).sessionId());
        }

        assertThat(ids).hasSize(1_000).allMatch(id -> id.matches("[A-Za-z0-9_-]{20}"));
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=SentretServiceTest#loginGeneratesTwentyCharUrlSafeUniqueSessionIds`
Expected: FAIL — os IDs têm 21 caracteres (NanoId).

- [ ] **Step 3: Implementar**

Em `service/SentretService.java`, adicionar no topo da classe:

```java
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder SESSION_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** 15 bytes = 120 random bits = exactly 20 Base64 URL characters, no padding. */
    private static final int SESSION_ID_BYTES = 15;
```

no `login`, trocar `NanoId.generate(properties.getSessionIdLength())` por `newSessionId()`, e adicionar:

```java
    private static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return SESSION_ID_ENCODER.encodeToString(bytes);
    }
```

Imports: adicionar `java.security.SecureRandom` e `java.util.Base64`; remover `io.github.sidneyroberto9.sentret.util.NanoId`.

Apagar `util/NanoId.java` (o pacote `util` fica vazio e some) e `unit/NanoIdTest.java`. Em `config/SentretProperties.java`, apagar `private int sessionIdLength = 21;`.

- [ ] **Step 4: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

Invocar `auto-commit`. Sugestão: `refactor: generate session ids with SecureRandom and Base64`.

---

### Task 6: 401 pelo `HttpStatusEntryPoint`, sem JSON inline (#6)

**Files:**
- Modify: `autoconfigure/SentretAutoConfiguration.java`, `SentretApplicationTests.java`, `integration/SentretSessionControllerIntegrationTest.java`

**Interfaces:**
- Consumes: nada novo.
- Produces: toda resposta 401 da cadeia padrão sem corpo.

- [ ] **Step 1: Escrever os testes que falham**

Em `SentretApplicationTests`, trocar `meWithNoCookieReturns401` por:

```java
    @Test
    void meWithNoCookieReturns401WithoutBody() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }
```

(import `static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content`).

Em `integration/SentretSessionControllerIntegrationTest`, trocar `renewWithoutValidCookieReturns401WithStandardErrorBody` por:

```java
    @Test
    void renewWithoutValidCookieReturns401WithoutBody() throws Exception {
        mockMvc.perform(post("/session/renew"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }
```

(mesmo import).

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='SentretApplicationTests#meWithNoCookieReturns401WithoutBody,SentretSessionControllerIntegrationTest#renewWithoutValidCookieReturns401WithoutBody'`
Expected: FAIL — corpo `{"error":"unauthorized","message":"Authentication required"}`.

- [ ] **Step 3: Implementar**

Em `SentretAutoConfiguration#sentretSecurityFilterChain`, apagar o bloco `AuthenticationEntryPoint entryPoint = (req, res, ex) -> { ... };` e trocar a linha do `exceptionHandling` por:

```java
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
```

Imports: adicionar `org.springframework.security.web.authentication.HttpStatusEntryPoint`; remover `org.springframework.http.MediaType` e `org.springframework.security.web.AuthenticationEntryPoint`.

- [ ] **Step 4: Confirmar que não há JSON escrito à mão**

Run: `grep -rn "getWriter()" src/main`
Expected: nenhuma saída.

- [ ] **Step 5: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

Invocar `auto-commit`. Sugestão: `refactor: answer unauthenticated requests with HttpStatusEntryPoint`.

---

### Task 7: Store com `JdbcTemplate` + modelo de atividade (#4, #5)

**Files:**
- Delete: `domain/SentretSession.java`, `domain/SentretSessionRepository.java`, `store/JpaSentretSessionStore.java`, `src/main/resources/db/spring-session-lite-schema.sql`, `src/test/java/.../unit/JpaSentretSessionStoreTest.java`
- Create: `store/SentretSession.java`, `store/JdbcSentretSessionStore.java`, `src/main/resources/db/sentret-schema.sql`, `src/test/java/.../unit/JdbcSentretSessionStoreTest.java`
- Modify (reescrever): `store/SentretSessionStore.java`, `security/SentretUser.java`, `service/SentretService.java`, `service/SentretSessionRemaining.java`, `event/SentretSessionRenewedEvent.java`, `src/test/resources/application.properties`, `unit/SentretServiceTest.java`, `unit/SentretAuthenticationFilterTest.java`, `unit/SentretSessionControllerTest.java`, `unit/SentretUserTest.java`, `SentretApplicationTests.java`, `integration/SentretSessionControllerIntegrationTest.java`
- Modify: `pom.xml`, `config/SentretProperties.java`, `autoconfigure/SentretAutoConfiguration.java`, `web/controller/SentretSessionController.java`, `unit/SentretUserServiceTest.java`, `unit/SentretCurrentSessionArgumentResolverTest.java`

**Interfaces:**
- Consumes: `newSessionId()` (Tarefa 5); `validate(String)` (Tarefa 3).
- Produces:
  - `record SentretSession(String sessionId, String userId, String email, Instant createdAt, Instant expiresAt, Instant lastAccessedAt)` (pacote `store`).
  - `interface SentretSessionStore { void insert(SentretSession); Optional<SentretSession> findBySessionId(String); void updateLastAccessedAt(String sessionId, Instant lastAccessedAt); void updateExpiresAt(String sessionId, Instant expiresAt, Instant lastAccessedAt); void deleteBySessionId(String); void deleteByUserId(String); void deleteExpired(Instant now); }`
  - `class JdbcSentretSessionStore(JdbcTemplate)`.
  - `record SentretUser(String userId, String email, String sessionId, Instant expiresAt, Instant lastAccessedAt)`.
  - `SentretService`: `login(String, String, HttpServletResponse)`, `Optional<SentretUser> validate(String)`, `SentretUser touch(SentretUser)`, `void logout(HttpServletRequest, HttpServletResponse)`, `void logout(String)`, `void logoutAll(String)`, `void deleteExpired()` (removido na Tarefa 8), `Optional<SentretUser> renew(String)`, `Optional<SentretUser> renew(HttpServletRequest, HttpServletResponse)`, `SentretSessionRemaining remaining(SentretUser)` (removido na Tarefa 10).
  - `SentretProperties#isIdleEnabled()`.
  - `record SentretSessionRenewedEvent(String userId, String sessionId, Instant renewedAt)`.

- [ ] **Step 1: Trocar as dependências no `pom.xml`**

Apagar a dependência `spring-boot-starter-data-jpa` e adicionar:

```xml
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-jdbc</artifactId>
        </dependency>
```

e, junto das dependências de teste:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Criar o schema**

Apagar `src/main/resources/db/spring-session-lite-schema.sql`. Conteúdo completo de `src/main/resources/db/sentret-schema.sql`:

```sql
-- Sentret — run once (Flyway, Liquibase or by hand) before starting the application.
-- Portable as written: MySQL, MariaDB, PostgreSQL, SQL Server and H2.
-- Times are epoch milliseconds (BIGINT): no time-zone conversion, no 2038 limit.

CREATE TABLE sentret_sessions (
    session_id       VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    created_at       BIGINT       NOT NULL,
    expires_at       BIGINT       NOT NULL,
    last_accessed_at BIGINT       NOT NULL
);

CREATE INDEX idx_sentret_sessions_user_id ON sentret_sessions (user_id);
CREATE INDEX idx_sentret_sessions_expires_at ON sentret_sessions (expires_at);
```

- [ ] **Step 3: Escrever o teste do store (falha: classe não existe)**

Conteúdo completo de `src/test/java/io/github/sidneyroberto9/sentret/unit/JdbcSentretSessionStoreTest.java`:

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.store.JdbcSentretSessionStore;
import io.github.sidneyroberto9.sentret.store.SentretSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real SQL against H2 with the shipped schema, so a typo in a statement or in the DDL
 * fails here instead of in a consumer's database.
 */
class JdbcSentretSessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00.123Z");

    private EmbeddedDatabase database;
    private JdbcSentretSessionStore store;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:db/sentret-schema.sql")
                .build();
        store = new JdbcSentretSessionStore(new JdbcTemplate(database));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static SentretSession session(String sessionId, String userId, Instant expiresAt) {
        return new SentretSession(sessionId, userId, userId + "@test.com", T0, expiresAt, T0);
    }

    @Test
    void insertThenFindReturnsTheSameSession() {
        SentretSession session = session("sid-1", "user-1", T0.plusSeconds(3600));

        store.insert(session);

        assertThat(store.findBySessionId("sid-1")).contains(session);
    }

    @Test
    void findReturnsEmptyForUnknownSession() {
        assertThat(store.findBySessionId("nope")).isEmpty();
    }

    /** Instant.now() carries micro/nanoseconds; the column keeps milliseconds. */
    @Test
    void insertTruncatesToMilliseconds() {
        Instant precise = Instant.parse("2026-01-01T10:00:00.123456789Z");
        store.insert(new SentretSession("sid-1", "user-1", null, precise, precise, precise));

        SentretSession found = store.findBySessionId("sid-1").orElseThrow();

        assertThat(found.createdAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00.123Z"));
        assertThat(found.email()).isNull();
    }

    @Test
    void updateLastAccessedAtChangesOnlyThatColumn() {
        store.insert(session("sid-1", "user-1", T0.plusSeconds(3600)));

        store.updateLastAccessedAt("sid-1", T0.plusSeconds(60));

        SentretSession found = store.findBySessionId("sid-1").orElseThrow();
        assertThat(found.lastAccessedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(found.expiresAt()).isEqualTo(T0.plusSeconds(3600));
    }

    @Test
    void updateExpiresAtChangesExpiryAndLastAccess() {
        store.insert(session("sid-1", "user-1", T0.plusSeconds(60)));

        store.updateExpiresAt("sid-1", T0.plusSeconds(7200), T0.plusSeconds(30));

        SentretSession found = store.findBySessionId("sid-1").orElseThrow();
        assertThat(found.expiresAt()).isEqualTo(T0.plusSeconds(7200));
        assertThat(found.lastAccessedAt()).isEqualTo(T0.plusSeconds(30));
    }

    /** A heartbeat or renew racing a logout hits a row that is already gone. */
    @Test
    void updatesOnMissingSessionAreNoOps() {
        store.updateLastAccessedAt("gone", T0);
        store.updateExpiresAt("gone", T0, T0);

        assertThat(store.findBySessionId("gone")).isEmpty();
    }

    @Test
    void deleteBySessionIdRemovesOnlyThatSession() {
        store.insert(session("sid-1", "user-1", T0.plusSeconds(3600)));
        store.insert(session("sid-2", "user-1", T0.plusSeconds(3600)));

        store.deleteBySessionId("sid-1");

        assertThat(store.findBySessionId("sid-1")).isEmpty();
        assertThat(store.findBySessionId("sid-2")).isPresent();
    }

    @Test
    void deleteByUserIdRemovesEverySessionOfThatUser() {
        store.insert(session("sid-1", "user-1", T0.plusSeconds(3600)));
        store.insert(session("sid-2", "user-1", T0.plusSeconds(3600)));
        store.insert(session("sid-3", "user-2", T0.plusSeconds(3600)));

        store.deleteByUserId("user-1");

        assertThat(store.findBySessionId("sid-1")).isEmpty();
        assertThat(store.findBySessionId("sid-2")).isEmpty();
        assertThat(store.findBySessionId("sid-3")).isPresent();
    }

    @Test
    void deleteExpiredRemovesOnlySessionsPastTheCutoff() {
        store.insert(session("old", "user-1", T0.minusSeconds(1)));
        store.insert(session("live", "user-1", T0.plusSeconds(1)));

        store.deleteExpired(T0);

        assertThat(store.findBySessionId("old")).isEmpty();
        assertThat(store.findBySessionId("live")).isPresent();
    }
}
```

Run: `./mvnw test -Dtest=JdbcSentretSessionStoreTest`
Expected: FAIL — `cannot find symbol: class JdbcSentretSessionStore`.

- [ ] **Step 4: Implementar o store**

Apagar `domain/SentretSession.java`, `domain/SentretSessionRepository.java`, `store/JpaSentretSessionStore.java` e `unit/JpaSentretSessionStoreTest.java`.

Conteúdo completo de `store/SentretSession.java`:

```java
package io.github.sidneyroberto9.sentret.store;

import java.time.Instant;

/**
 * One persisted session row. Immutable: changes go through the targeted update methods of
 * {@link SentretSessionStore}, never through a read-modify-write of the whole row.
 */
public record SentretSession(
        String sessionId,
        String userId,
        String email,
        Instant createdAt,
        Instant expiresAt,
        Instant lastAccessedAt
) {
}
```

Conteúdo completo de `store/SentretSessionStore.java`:

```java
package io.github.sidneyroberto9.sentret.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage abstraction for sessions. The default implementation is {@link JdbcSentretSessionStore};
 * declare your own bean of this type to replace it.
 */
public interface SentretSessionStore {

    void insert(SentretSession session);

    Optional<SentretSession> findBySessionId(String sessionId);

    void updateLastAccessedAt(String sessionId, Instant lastAccessedAt);

    void updateExpiresAt(String sessionId, Instant expiresAt, Instant lastAccessedAt);

    void deleteBySessionId(String sessionId);

    void deleteByUserId(String userId);

    void deleteExpired(Instant now);
}
```

Conteúdo completo de `store/JdbcSentretSessionStore.java`:

```java
package io.github.sidneyroberto9.sentret.store;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.Optional;

/**
 * Keeps sessions in the host application's database through its {@link JdbcTemplate}. Every
 * operation is a single statement, so no transaction is needed. Times are stored as epoch
 * milliseconds (see {@code db/sentret-schema.sql}).
 */
@RequiredArgsConstructor
public class JdbcSentretSessionStore implements SentretSessionStore {

    private static final String COLUMNS = "session_id, user_id, email, created_at, expires_at, last_accessed_at";

    private static final RowMapper<SentretSession> ROW_MAPPER = (rs, rowNum) -> new SentretSession(
            rs.getString("session_id"),
            rs.getString("user_id"),
            rs.getString("email"),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            Instant.ofEpochMilli(rs.getLong("expires_at")),
            Instant.ofEpochMilli(rs.getLong("last_accessed_at")));

    private final JdbcTemplate jdbc;

    @Override
    public void insert(SentretSession session) {
        jdbc.update("INSERT INTO sentret_sessions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                session.sessionId(),
                session.userId(),
                session.email(),
                session.createdAt().toEpochMilli(),
                session.expiresAt().toEpochMilli(),
                session.lastAccessedAt().toEpochMilli());
    }

    @Override
    public Optional<SentretSession> findBySessionId(String sessionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sentret_sessions WHERE session_id = ?", ROW_MAPPER, sessionId)
                .stream()
                .findFirst();
    }

    @Override
    public void updateLastAccessedAt(String sessionId, Instant lastAccessedAt) {
        jdbc.update("UPDATE sentret_sessions SET last_accessed_at = ? WHERE session_id = ?",
                lastAccessedAt.toEpochMilli(), sessionId);
    }

    @Override
    public void updateExpiresAt(String sessionId, Instant expiresAt, Instant lastAccessedAt) {
        jdbc.update("UPDATE sentret_sessions SET expires_at = ?, last_accessed_at = ? WHERE session_id = ?",
                expiresAt.toEpochMilli(), lastAccessedAt.toEpochMilli(), sessionId);
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        jdbc.update("DELETE FROM sentret_sessions WHERE session_id = ?", sessionId);
    }

    @Override
    public void deleteByUserId(String userId) {
        jdbc.update("DELETE FROM sentret_sessions WHERE user_id = ?", userId);
    }

    @Override
    public void deleteExpired(Instant now) {
        jdbc.update("DELETE FROM sentret_sessions WHERE expires_at < ?", now.toEpochMilli());
    }
}
```

- [ ] **Step 5: Reescrever os testes de Service, Filter, Controller e User (falham: API nova)**

Conteúdo completo de `unit/SentretServiceTest.java`:

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.event.SentretSessionCreatedEvent;
import io.github.sidneyroberto9.sentret.event.SentretSessionDestroyedEvent;
import io.github.sidneyroberto9.sentret.event.SentretSessionRenewedEvent;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.service.SentretSessionRemaining;
import io.github.sidneyroberto9.sentret.store.SentretSession;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import jakarta.servlet.http.Cookie;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SentretServiceTest {

    private static final Offset<Long> FIVE_SECONDS = Offset.offset(5_000L);

    private SentretProperties properties;
    private SentretSessionStore store;
    private ApplicationEventPublisher eventPublisher;
    private SentretCookieManager cookieManager;
    private SentretService service;

    @BeforeEach
    void setUp() {
        properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));
        store = mock(SentretSessionStore.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        cookieManager = new SentretCookieManager(properties);
        service = new SentretService(store, properties, eventPublisher, cookieManager);
    }

    private void stored(Instant expiresAt, Instant lastAccessedAt) {
        SentretSession session = new SentretSession(
                "sid", "user-1", "user@test.com", lastAccessedAt.minus(Duration.ofHours(1)), expiresAt, lastAccessedAt);
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));
    }

    private static SentretUser user(Instant expiresAt, Instant lastAccessedAt) {
        return new SentretUser("user-1", "user@test.com", "sid", expiresAt, lastAccessedAt);
    }

    // --- login ---

    @Test
    void loginInsertsSessionWritesCookieAndPublishesEvent() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SentretUser user = service.login("user-1", "user@test.com", response);

        ArgumentCaptor<SentretSession> captor = ArgumentCaptor.forClass(SentretSession.class);
        verify(store).insert(captor.capture());
        SentretSession inserted = captor.getValue();
        assertThat(inserted.sessionId()).isEqualTo(user.sessionId());
        assertThat(inserted.userId()).isEqualTo("user-1");
        assertThat(inserted.expiresAt()).isEqualTo(inserted.createdAt().plus(properties.getTtl()));
        assertThat(inserted.lastAccessedAt()).isEqualTo(inserted.createdAt());
        assertThat(response.getHeader("Set-Cookie")).contains(user.sessionId());
        verify(eventPublisher).publishEvent(any(SentretSessionCreatedEvent.class));
    }

    @Test
    void loginGeneratesTwentyCharUrlSafeUniqueSessionIds() {
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            ids.add(service.login("user-1", "user@test.com", new MockHttpServletResponse()).sessionId());
        }

        assertThat(ids).hasSize(1_000).allMatch(id -> id.matches("[A-Za-z0-9_-]{20}"));
    }

    // --- validate ---

    @Test
    void validateReturnsUserCarryingTheSessionDeadlines() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(1)));

        SentretUser user = service.validate("sid").orElseThrow();

        assertThat(user.userId()).isEqualTo("user-1");
        assertThat(user.expiresAt()).isEqualTo(now.plus(Duration.ofHours(1)));
        assertThat(user.lastAccessedAt()).isEqualTo(now.minus(Duration.ofMinutes(1)));
    }

    @Test
    void validateReturnsEmptyForUnknownSession() {
        when(store.findBySessionId("gone")).thenReturn(Optional.empty());

        assertThat(service.validate("gone")).isEmpty();
    }

    @Test
    void validateReturnsEmptyPastAbsoluteExpiry() {
        Instant now = Instant.now();
        stored(now.minus(Duration.ofSeconds(1)), now);

        assertThat(service.validate("sid")).isEmpty();
    }

    @Test
    void validateReturnsEmptyWhenIdleExceedsMaxIdle() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(11)));

        assertThat(service.validate("sid")).isEmpty();
    }

    @Test
    void validateIgnoresIdleWhenMaxIdleIsZero() {
        properties.setMaxIdle(Duration.ZERO);
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofHours(5)));

        assertThat(service.validate("sid")).isPresent();
    }

    @Test
    void validateIgnoresIdleWhenMaxIdleIsNull() {
        properties.setMaxIdle(null);
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofHours(5)));

        assertThat(service.validate("sid")).isPresent();
    }

    /**
     * Validating is not activity. The client polls the status every 30s whether or not the user is
     * there; if validation wrote last_accessed_at, max-idle could never elapse.
     */
    @Test
    void repeatedValidationNeverWrites() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofSeconds(90)));

        for (int i = 0; i < 10; i++) {
            service.validate("sid");
        }

        verify(store, times(10)).findBySessionId("sid");
        verifyNoMoreInteractions(store);
    }

    // --- touch (heartbeat) ---

    @Test
    void touchWritesWithOneUpdateAndReturnsTheRefreshedUser() {
        Instant now = Instant.now();
        SentretUser user = user(now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(9)));

        SentretUser touched = service.touch(user);

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(store).updateLastAccessedAt(eq("sid"), at.capture());
        verifyNoMoreInteractions(store);
        assertThat(touched.lastAccessedAt()).isEqualTo(at.getValue()).isAfter(now.minusSeconds(1));
        assertThat(touched.expiresAt()).isEqualTo(user.expiresAt());
    }

    /**
     * Never throttled. The client already throttles heartbeats to heartbeat-interval; dropping one
     * here is the bug that logged active users out while the warning was on screen.
     */
    @Test
    void touchWritesEvenRightAfterAPreviousTouch() {
        Instant now = Instant.now();

        service.touch(user(now.plus(Duration.ofHours(1)), now.minusSeconds(1)));

        verify(store).updateLastAccessedAt(eq("sid"), any());
    }

    @Test
    void touchIsNoOpWhenIdleDisabled() {
        properties.setMaxIdle(Duration.ZERO);
        SentretUser user = user(Instant.now().plus(Duration.ofHours(1)), Instant.now());

        assertThat(service.touch(user)).isSameAs(user);
        verifyNoInteractions(store);
    }

    // --- remaining ---

    @Test
    void remainingComesFromThePrincipalWithoutStoreAccess() {
        Instant now = Instant.now();

        SentretSessionRemaining remaining = service.remaining(
                user(now.plus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(4))));

        assertThat(remaining.absoluteRemainingMs()).isCloseTo(Duration.ofMinutes(30).toMillis(), FIVE_SECONDS);
        assertThat(remaining.idleRemainingMs()).isCloseTo(Duration.ofMinutes(6).toMillis(), FIVE_SECONDS);
        verifyNoInteractions(store);
    }

    @Test
    void remainingHasNoIdleDeadlineWhenIdleDisabled() {
        properties.setMaxIdle(Duration.ZERO);
        Instant now = Instant.now();

        SentretSessionRemaining remaining = service.remaining(user(now.plus(Duration.ofMinutes(30)), now));

        assertThat(remaining.idleRemainingMs()).isNull();
    }

    @Test
    void remainingClampsToZeroPastBothDeadlines() {
        Instant now = Instant.now();

        SentretSessionRemaining remaining = service.remaining(
                user(now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofMinutes(20))));

        assertThat(remaining.absoluteRemainingMs()).isZero();
        assertThat(remaining.idleRemainingMs()).isZero();
    }

    // --- renew ---

    @Test
    void renewResetsBothDeadlinesAndPublishesEvent() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofMinutes(1)), now.minus(Duration.ofMinutes(5)));

        SentretUser renewed = service.renew("sid").orElseThrow();

        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> lastAccessedAt = ArgumentCaptor.forClass(Instant.class);
        verify(store).updateExpiresAt(eq("sid"), expiresAt.capture(), lastAccessedAt.capture());
        assertThat(expiresAt.getValue()).isEqualTo(lastAccessedAt.getValue().plus(properties.getTtl()));
        assertThat(renewed.expiresAt()).isEqualTo(expiresAt.getValue());
        assertThat(renewed.lastAccessedAt()).isEqualTo(lastAccessedAt.getValue());

        ArgumentCaptor<SentretSessionRenewedEvent> event = ArgumentCaptor.forClass(SentretSessionRenewedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo("user-1");
        assertThat(event.getValue().sessionId()).isEqualTo("sid");
    }

    @Test
    void renewOfUnknownSessionReturnsEmptyAndPublishesNothing() {
        when(store.findBySessionId("missing")).thenReturn(Optional.empty());

        assertThat(service.renew("missing")).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void renewDoesNotResurrectAnIdleExpiredSession() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(11)));

        assertThat(service.renew("sid")).isEmpty();
        verify(store, never()).updateExpiresAt(any(), any(), any());
    }

    @Test
    void renewWithRequestRewritesTheCookie() {
        properties.setTtl(Duration.ofMinutes(30));
        Instant now = Instant.now();
        stored(now.plus(Duration.ofMinutes(2)), now);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(cookieManager.cookieName(), "sid"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(service.renew(request, response)).isPresent();
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=1800");
    }

    @Test
    void renewWithRequestWithoutCookieDoesNothing() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(service.renew(new MockHttpServletRequest(), response)).isEmpty();
        verifyNoInteractions(store);
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    // --- logout ---

    @Test
    void logoutDeletesTheSessionAndPublishesEventWithUserId() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now);

        service.logout("sid");

        verify(store).deleteBySessionId("sid");
        ArgumentCaptor<SentretSessionDestroyedEvent> event = ArgumentCaptor.forClass(SentretSessionDestroyedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo("user-1");
        assertThat(event.getValue().sessionId()).isEqualTo("sid");
    }

    @Test
    void logoutOfUnknownSessionPublishesNothing() {
        when(store.findBySessionId("gone")).thenReturn(Optional.empty());

        service.logout("gone");

        verify(store, never()).deleteBySessionId(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void logoutWithRequestClearsTheCookieEvenWithoutSession() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.logout(new MockHttpServletRequest(), response);

        verify(store, never()).findBySessionId(any());
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void logoutAllDeletesEverySessionOfTheUser() {
        service.logoutAll("user-1");

        verify(store).deleteByUserId("user-1");
    }

    @Test
    void deleteExpiredDelegatesToStore() {
        service.deleteExpired();

        verify(store).deleteExpired(any());
    }
}
```

Conteúdo completo de `unit/SentretAuthenticationFilterTest.java`:

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.security.SentretAuthenticationFilter;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.store.SentretSession;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The filter authenticates and never writes. Activity is signalled only by the heartbeat endpoint,
 * so neither the client's status poll nor the host application's own background requests can keep
 * an idle session alive.
 */
class SentretAuthenticationFilterTest {

    private SentretSessionStore store;
    private SentretCookieManager cookieManager;
    private SentretAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(2));
        store = mock(SentretSessionStore.class);
        cookieManager = new SentretCookieManager(properties);
        SentretService service = new SentretService(store, properties, mock(ApplicationEventPublisher.class), cookieManager);
        filter = new SentretAuthenticationFilter(service, cookieManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void storedSessionIdleFor(Duration idle) {
        Instant now = Instant.now();
        SentretSession session = new SentretSession(
                "sid", "user-1", "user@test.com", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), now.minus(idle));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));
    }

    private MockHttpServletResponse doFilter(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setCookies(new Cookie(cookieManager.cookieName(), "sid"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void validSessionAuthenticatesTheUserWithNoAuthorities() throws Exception {
        storedSessionIdleFor(Duration.ofSeconds(1));

        doFilter("GET", "/api/documents");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isInstanceOf(SentretUser.class);
        assertThat(((SentretUser) auth.getPrincipal()).userId()).isEqualTo("user-1");
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void noRequestThroughTheFilterWritesToTheStore() throws Exception {
        storedSessionIdleFor(Duration.ofSeconds(90));

        doFilter("GET", "/session/status");
        doFilter("POST", "/session/heartbeat");
        doFilter("GET", "/api/documents");

        verify(store, times(3)).findBySessionId("sid");
        verifyNoMoreInteractions(store);
    }

    @Test
    void idleExpiredSessionStaysAnonymousAndTheCookieIsCleared() throws Exception {
        storedSessionIdleFor(Duration.ofMinutes(3));

        MockHttpServletResponse response = doFilter("GET", "/session/status");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void requestWithoutCookieNeverTouchesTheStore() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/documents"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(store);
    }
}
```

Conteúdo completo de `unit/SentretSessionControllerTest.java` (o controller antigo ainda existe até a Tarefa 10):

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.service.SentretSessionRemaining;
import io.github.sidneyroberto9.sentret.web.controller.SentretSessionController;
import io.github.sidneyroberto9.sentret.web.controller.SentretSessionController.SessionStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SentretSessionControllerTest {

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant LAST_ACCESSED_AT = Instant.parse("2029-12-31T23:00:00Z");

    private SentretService sessionService;
    private SentretSessionController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SentretService.class);
        controller = new SentretSessionController(sessionService, new SentretProperties());
    }

    private static SentretUser user() {
        return new SentretUser("user-1", "user@test.com", "sid-1", EXPIRES_AT, LAST_ACCESSED_AT);
    }

    @Test
    void statusIsComputedFromThePrincipal() {
        SentretUser user = user();
        when(sessionService.remaining(user)).thenReturn(new SentretSessionRemaining(60_000L, 30_000L));

        ResponseEntity<SessionStatusResponse> response = controller.status(user);

        assertThat(response.getBody().authenticated()).isTrue();
        assertThat(response.getBody().userId()).isEqualTo("user-1");
        assertThat(response.getBody().absoluteRemainingMs()).isEqualTo(60_000L);
        assertThat(response.getBody().idleRemainingMs()).isEqualTo(30_000L);
    }

    @Test
    void statusIsAnonymousWithoutPrincipal() {
        ResponseEntity<SessionStatusResponse> response = controller.status(null);

        assertThat(response.getBody().authenticated()).isFalse();
        verifyNoInteractions(sessionService);
    }

    @Test
    void heartbeatTouchesAndReportsTheRefreshedPrincipal() {
        SentretUser user = user();
        SentretUser touched = new SentretUser("user-1", "user@test.com", "sid-1", EXPIRES_AT, EXPIRES_AT.minusSeconds(60));
        when(sessionService.touch(user)).thenReturn(touched);
        when(sessionService.remaining(touched)).thenReturn(new SentretSessionRemaining(60_000L, 600_000L));

        ResponseEntity<SessionStatusResponse> response = controller.heartbeat(user);

        verify(sessionService).touch(user);
        assertThat(response.getBody().idleRemainingMs()).isEqualTo(600_000L);
    }

    @Test
    void heartbeatWithoutPrincipalIsAnonymousAndDoesNotTouch() {
        ResponseEntity<SessionStatusResponse> response = controller.heartbeat(null);

        assertThat(response.getBody().authenticated()).isFalse();
        verify(sessionService, never()).touch(any());
    }

    @Test
    void renewReturnsUnauthorizedWhenServiceReturnsEmpty() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        when(sessionService.renew(request, httpResponse)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.renew(request, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void renewReturnsStatusWhenServiceRenews() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        SentretUser user = user();
        when(sessionService.renew(request, httpResponse)).thenReturn(Optional.of(user));
        when(sessionService.remaining(user)).thenReturn(new SentretSessionRemaining(60_000L, null));

        ResponseEntity<?> response = controller.renew(request, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void statusConfigReportsZeroMaxIdleMsWhenMaxIdleIsNull() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(null);
        controller = new SentretSessionController(sessionService, properties);

        ResponseEntity<SessionStatusResponse> response = controller.status(null);

        assertThat(response.getBody().config().maxIdleMs()).isZero();
    }

    @Test
    void logoutDelegatesToServiceAndReturnsNoContent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);

        ResponseEntity<Void> response = controller.logout(request, httpResponse);

        verify(sessionService).logout(request, httpResponse);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
```

Conteúdo completo de `unit/SentretUserTest.java`:

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SentretUserTest {

    /**
     * Identity plus the two deadlines the filter already loaded, so status/heartbeat can report
     * remaining time without reading the row again. Roles stay with the host application.
     */
    @Test
    void principalCarriesIdentityAndDeadlines() {
        assertThat(SentretUser.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "email", "sessionId", "expiresAt", "lastAccessedAt");
    }

    @Test
    void accessorsReturnConstructorValues() {
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        Instant lastAccessedAt = Instant.parse("2029-12-31T23:00:00Z");

        SentretUser user = new SentretUser("u1", "u1@example.com", "s1", expiresAt, lastAccessedAt);

        assertThat(user.userId()).isEqualTo("u1");
        assertThat(user.email()).isEqualTo("u1@example.com");
        assertThat(user.sessionId()).isEqualTo("s1");
        assertThat(user.expiresAt()).isEqualTo(expiresAt);
        assertThat(user.lastAccessedAt()).isEqualTo(lastAccessedAt);
    }
}
```

Nos outros testes que constroem o principal:

```bash
grep -rlZ "new SentretUser(" src/test/java/io/github/sidneyroberto9/sentret/unit/SentretUserServiceTest.java src/test/java/io/github/sidneyroberto9/sentret/unit/SentretCurrentSessionArgumentResolverTest.java \
  | xargs -0 perl -pi -e 's/new SentretUser\(("[^"]*"), ("[^"]*"), ("[^"]*")\)/new SentretUser($1, $2, $3, null, null)/g'
```

Run: `./mvnw test-compile`
Expected: FAIL — `SentretService` ainda não tem `touch(SentretUser)`, `remaining(SentretUser)`, etc.

- [ ] **Step 6: Implementar principal, evento, properties e service**

Conteúdo completo de `security/SentretUser.java`:

```java
package io.github.sidneyroberto9.sentret.security;

import java.time.Instant;

/**
 * Immutable authenticated principal exposed by the library. Populated by the
 * {@link SentretAuthenticationFilter} from the row it just validated, so the two deadlines are
 * available without another read. Authorization data (roles, permissions) belongs to the host
 * application, looked up by {@link #userId()}.
 */
public record SentretUser(String userId, String email, String sessionId, Instant expiresAt, Instant lastAccessedAt) {
}
```

Conteúdo completo de `event/SentretSessionRenewedEvent.java`:

```java
package io.github.sidneyroberto9.sentret.event;

import java.time.Instant;

/**
 * Published after a session's absolute expiry is reset (renewal).
 */
public record SentretSessionRenewedEvent(String userId, String sessionId, Instant renewedAt) {
}
```

Conteúdo completo de `service/SentretSessionRemaining.java`:

```java
package io.github.sidneyroberto9.sentret.service;

/**
 * Remaining-time snapshot for a session, computed by {@link SentretService#remaining} from the
 * principal. {@code idleRemainingMs} is {@code null} when idle enforcement is disabled.
 */
public record SentretSessionRemaining(long absoluteRemainingMs, Long idleRemainingMs) {
}
```

`config/SentretProperties.java`: apagar os campos `updateLastAccessed`, `lastAccessedThrottle` (com o `@Deprecated`) e `slidingExpiration`, cada um com seu Javadoc; trocar o Javadoc de `maxIdle` por

```java
    /**
     * Inactivity window, measured from the last heartbeat. {@code null}, zero or negative disables
     * it. Keep {@link #heartbeatInterval} well below it.
     */
```

e adicionar ao final da classe:

```java
    /** Whether {@link #maxIdle} is enforced: {@code null}, zero or negative disables it. */
    public boolean isIdleEnabled() {
        return maxIdle != null && !maxIdle.isZero() && !maxIdle.isNegative();
    }
```

Conteúdo completo de `service/SentretService.java`:

```java
package io.github.sidneyroberto9.sentret.service;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.event.SentretSessionCreatedEvent;
import io.github.sidneyroberto9.sentret.event.SentretSessionDestroyedEvent;
import io.github.sidneyroberto9.sentret.event.SentretSessionRenewedEvent;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.store.SentretSession;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@RequiredArgsConstructor
public class SentretService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder SESSION_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** 15 bytes = 120 random bits = exactly 20 Base64 URL characters, no padding. */
    private static final int SESSION_ID_BYTES = 15;

    private final SentretSessionStore store;
    private final SentretProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final SentretCookieManager cookieManager;

    public SentretUser login(String userId, String email, HttpServletResponse response) {
        Instant now = Instant.now();
        SentretSession session = new SentretSession(newSessionId(), userId, email, now, now.plus(properties.getTtl()), now);

        store.insert(session);
        cookieManager.write(response, session.sessionId());
        eventPublisher.publishEvent(new SentretSessionCreatedEvent(userId, session.sessionId(), now));

        return toUser(session);
    }

    /**
     * Who the session belongs to; empty when it is unknown, past its absolute expiry, or idle for
     * longer than max-idle. Read-only on purpose: validating is not activity (see {@link #touch}).
     */
    public Optional<SentretUser> validate(String sessionId) {
        Instant now = Instant.now();

        return store.findBySessionId(sessionId)
                .filter(session -> !session.expiresAt().isBefore(now))
                .filter(session -> !isIdleExpired(session.lastAccessedAt(), now))
                .map(this::toUser);
    }

    /**
     * Records real user activity (the hub heartbeat). One UPDATE, no re-read, no throttle: the
     * client already throttles heartbeats to heartbeat-interval, and dropping one here would discard
     * the only activity signal there is.
     */
    public SentretUser touch(SentretUser user) {
        if (!properties.isIdleEnabled()) {
            return user;
        }

        Instant now = Instant.now();
        store.updateLastAccessedAt(user.sessionId(), now);

        return new SentretUser(user.userId(), user.email(), user.sessionId(), user.expiresAt(), now);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = cookieManager.read(request);

        if (sessionId != null) {
            logout(sessionId);
        }

        cookieManager.clear(response);
    }

    /**
     * Destroys the session and publishes {@link SentretSessionDestroyedEvent} with its userId. A
     * no-op, no-event call when the session is already gone.
     */
    public void logout(String sessionId) {
        store.findBySessionId(sessionId).ifPresent(session -> {
            store.deleteBySessionId(sessionId);
            eventPublisher.publishEvent(new SentretSessionDestroyedEvent(session.userId(), sessionId));
        });
    }

    public void logoutAll(String userId) {
        store.deleteByUserId(userId);
    }

    public void deleteExpired() {
        store.deleteExpired(Instant.now());
    }

    /** Resets both deadlines of a still-valid session. Never resurrects an expired one. */
    public Optional<SentretUser> renew(String sessionId) {
        Instant now = Instant.now();

        return validate(sessionId).map(user -> {
            Instant expiresAt = now.plus(properties.getTtl());
            store.updateExpiresAt(sessionId, expiresAt, now);
            eventPublisher.publishEvent(new SentretSessionRenewedEvent(user.userId(), sessionId, now));

            return new SentretUser(user.userId(), user.email(), sessionId, expiresAt, now);
        });
    }

    public Optional<SentretUser> renew(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = cookieManager.read(request);

        if (sessionId == null) {
            return Optional.empty();
        }

        Optional<SentretUser> renewed = renew(sessionId);
        renewed.ifPresent(user -> cookieManager.write(response, sessionId));

        return renewed;
    }

    /** Remaining time computed from the principal the filter already loaded: no store access. */
    public SentretSessionRemaining remaining(SentretUser user) {
        Instant now = Instant.now();
        long absoluteRemainingMs = remainingMs(now, user.expiresAt());

        if (!properties.isIdleEnabled()) {
            return new SentretSessionRemaining(absoluteRemainingMs, null);
        }

        long idleRemainingMs = remainingMs(now, user.lastAccessedAt().plus(properties.getMaxIdle()));
        return new SentretSessionRemaining(absoluteRemainingMs, idleRemainingMs);
    }

    private boolean isIdleExpired(Instant lastAccessedAt, Instant now) {
        return properties.isIdleEnabled() && lastAccessedAt.plus(properties.getMaxIdle()).isBefore(now);
    }

    private static long remainingMs(Instant now, Instant deadline) {
        return Math.max(0, Duration.between(now, deadline).toMillis());
    }

    private static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return SESSION_ID_ENCODER.encodeToString(bytes);
    }

    private SentretUser toUser(SentretSession session) {
        return new SentretUser(session.userId(), session.email(), session.sessionId(), session.expiresAt(), session.lastAccessedAt());
    }
}
```

- [ ] **Step 7: Ligar o store na autoconfiguração e ajustar o controller antigo**

`autoconfigure/SentretAutoConfiguration.java` — o cabeçalho da classe fica:

```java
@AutoConfiguration
@ConditionalOnClass({JdbcTemplate.class, SecurityFilterChain.class})
@ConditionalOnProperty(prefix = "sentret", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SentretProperties.class)
@Import(SentretWebMvcConfiguration.class)
public class SentretAutoConfiguration {
```

e o bean do store:

```java
    @Bean
    @ConditionalOnMissingBean
    public SentretSessionStore sentretSessionStore(JdbcTemplate jdbcTemplate) {
        return new JdbcSentretSessionStore(jdbcTemplate);
    }
```

Imports: remover `domain.SentretSession`, `domain.SentretSessionRepository`, `store.JpaSentretSessionStore`, `jakarta.persistence.EntityManagerFactory`, `AutoConfigurationPackage`, `JpaRepositoriesAutoConfiguration`, `HibernateJpaAutoConfiguration`; adicionar `org.springframework.jdbc.core.JdbcTemplate` e `io.github.sidneyroberto9.sentret.store.JdbcSentretSessionStore`.

`web/controller/SentretSessionController.java` — trocar `heartbeat(...)` e `buildStatus(...)` por:

```java
    @PostMapping("/heartbeat")
    public ResponseEntity<SessionStatusResponse> heartbeat(@SentretCurrentSession SentretUser user) {
        if (user == null) {
            return ResponseEntity.ok(buildStatus(null));
        }

        return ResponseEntity.ok(buildStatus(sessionService.touch(user)));
    }
```

```java
    private SessionStatusResponse buildStatus(SentretUser user) {
        SessionStatusResponse.Config config = buildConfig();

        if (user == null) {
            return anonymousStatus(config);
        }

        return authenticatedStatus(user, sessionService.remaining(user), config);
    }
```

- [ ] **Step 8: Trocar o banco dos testes de integração**

Conteúdo completo de `src/test/resources/application.properties` (sem URL → o Boot cria um H2 embutido com nome único por contexto, então cada contexto roda o DDL num banco limpo):

```properties
spring.sql.init.schema-locations=classpath:db/sentret-schema.sql

sentret.cookie-secure=false
```

Conteúdo completo de `SentretApplicationTests.java`:

```java
package io.github.sidneyroberto9.sentret;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end behaviour through the real security filter chain. Uses only test APIs that are the
 * same in Spring Boot 3 and 4 (no TestRestTemplate, no @AutoConfigureMockMvc).
 */
@SpringBootTest(classes = SampleApplication.class)
class SentretApplicationTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SentretService sentretService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM sentret_sessions");
    }

    private Cookie login(String userId, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void expire(String sessionId) {
        jdbc.update("UPDATE sentret_sessions SET expires_at = ? WHERE session_id = ?",
                Instant.now().minusSeconds(60).toEpochMilli(), sessionId);
    }

    private int sessionCount(String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sentret_sessions WHERE session_id = ?", Integer.class, sessionId);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void loginWritesCookieWithCorrectAttributes() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"1\",\"email\":\"test@test.com\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("SENTRETSID=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
        assertThat(setCookie).containsIgnoringCase("Max-Age=28800");
    }

    @Test
    void meWithValidCookieReturns200() throws Exception {
        Cookie cookie = login("user1", "user1@test.com");

        mockMvc.perform(get("/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.email").value("user1@test.com"))
                .andExpect(jsonPath("$.roles").doesNotExist());
    }

    @Test
    void meWithNoCookieReturns401WithoutBody() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void loginEndpointReachableWithoutSession() throws Exception {
        login("x", "x@x.com");
    }

    /** A forged or oversized cookie must end in a clean 401, never in a database error. */
    @Test
    void meWithTamperedCookieReturns401AndClearsCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/me").cookie(new Cookie("SENTRETSID", "x".repeat(100))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void meWithExpiredSessionReturns401() throws Exception {
        Cookie cookie = login("user2", "user2@test.com");
        expire(cookie.getValue());

        mockMvc.perform(get("/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void cleanupRemovesExpiredSessions() throws Exception {
        Cookie cookie = login("user3", "user3@test.com");
        expire(cookie.getValue());

        sentretService.deleteExpired();

        assertThat(sessionCount(cookie.getValue())).isZero();
    }

    /**
     * Mobile, VPN and corporate networks change the client IP mid-session. The session is bound to
     * the cookie only, so a new IP must not log the user out.
     */
    @Test
    void sessionSurvivesClientIpChange() throws Exception {
        Cookie cookie = login("user4", "user4@test.com");

        mockMvc.perform(get("/me").cookie(cookie).with(request -> {
                    request.setRemoteAddr("198.51.100.77");
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithDeadCookieStillReaches200() throws Exception {
        // A stale/expired cookie must NOT block re-login on a permit-all path.
        Cookie cookie = login("user5", "user5@test.com");
        expire(cookie.getValue());

        mockMvc.perform(post("/login").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user5\",\"email\":\"user5@test.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutClearsCookieAndSession() throws Exception {
        Cookie cookie = login("user6", "user6@test.com");
        assertThat(sessionCount(cookie.getValue())).isOne();

        MvcResult result = mockMvc.perform(post("/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).containsIgnoringCase("Max-Age=0");
        assertThat(sessionCount(cookie.getValue())).isZero();
    }
}
```

Conteúdo completo de `integration/SentretSessionControllerIntegrationTest.java` (vive até a Tarefa 10):

```java
package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SampleApplication.class)
@TestPropertySource(properties = {
        "sentret.endpoints-enabled=true",
        "sentret.max-idle=10m"
})
class SentretSessionControllerIntegrationTest {

    private static final long TTL_MS = 8 * 60 * 60 * 1000L;
    private static final long MAX_IDLE_MS = 10 * 60 * 1000L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM sentret_sessions");
    }

    private Cookie login(String userId, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void setColumn(String column, String sessionId, Instant value) {
        jdbc.update("UPDATE sentret_sessions SET " + column + " = ? WHERE session_id = ?", value.toEpochMilli(), sessionId);
    }

    private Instant column(String column, String sessionId) {
        Long millis = jdbc.queryForObject("SELECT " + column + " FROM sentret_sessions WHERE session_id = ?", Long.class, sessionId);
        return Instant.ofEpochMilli(millis);
    }

    @Test
    void statusIsPermitAllAndAnonymousReturnsConfigEcho() throws Exception {
        mockMvc.perform(get("/session/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.config.ttlMs").value(TTL_MS))
                .andExpect(jsonPath("$.config.maxIdleMs").value(MAX_IDLE_MS))
                .andExpect(jsonPath("$.config.heartbeatIntervalMs").value(60_000))
                .andExpect(jsonPath("$.config.statusPollIntervalMs").value(30_000))
                .andExpect(jsonPath("$.config.warningBeforeMs").value(60_000));
    }

    @Test
    void statusWithValidCookieReturnsUserAndRemainingTimes() throws Exception {
        Cookie cookie = login("user1", "user1@test.com");

        mockMvc.perform(get("/session/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.absoluteRemainingMs").value(greaterThan((int) (TTL_MS - 10_000))))
                .andExpect(jsonPath("$.idleRemainingMs").value(greaterThan((int) (MAX_IDLE_MS - 10_000))));
    }

    @Test
    void heartbeatResetsTheIdleClock() throws Exception {
        Cookie cookie = login("user2", "user2@test.com");
        setColumn("last_accessed_at", cookie.getValue(), Instant.now().minus(6, ChronoUnit.MINUTES));

        mockMvc.perform(post("/session/heartbeat").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idleRemainingMs").value(greaterThan((int) (MAX_IDLE_MS - 10_000))));

        assertThat(column("last_accessed_at", cookie.getValue())).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void heartbeatWithoutCookieReturns401() throws Exception {
        mockMvc.perform(post("/session/heartbeat")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusPollDoesNotAdvanceLastAccessedAt() throws Exception {
        Cookie cookie = login("user-poll", "poll@test.com");
        Instant stale = Instant.now().minus(6, ChronoUnit.MINUTES);
        setColumn("last_accessed_at", cookie.getValue(), stale);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/session/status").cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true));
        }

        assertThat(column("last_accessed_at", cookie.getValue())).isCloseTo(stale, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void statusPollDoesNotRescueIdleExpiredSession() throws Exception {
        Cookie cookie = login("user-idle", "idle@test.com");
        setColumn("last_accessed_at", cookie.getValue(), Instant.now().minus(11, ChronoUnit.MINUTES));

        mockMvc.perform(get("/session/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));

        mockMvc.perform(post("/session/heartbeat").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renewResetsAbsoluteExpiry() throws Exception {
        Cookie cookie = login("user3", "user3@test.com");
        Instant nearExpiry = Instant.now().plusSeconds(60);
        setColumn("expires_at", cookie.getValue(), nearExpiry);

        mockMvc.perform(post("/session/renew").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absoluteRemainingMs").value(greaterThan((int) (TTL_MS - 10_000))))
                .andExpect(header().exists("Set-Cookie"));

        assertThat(column("expires_at", cookie.getValue())).isAfter(nearExpiry);
    }

    @Test
    void renewWithoutValidCookieReturns401WithoutBody() throws Exception {
        mockMvc.perform(post("/session/renew"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void logoutClearsSessionThenNextRequestReturns401() throws Exception {
        Cookie cookie = login("user4", "user4@test.com");

        mockMvc.perform(post("/session/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        mockMvc.perform(post("/session/heartbeat").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 9: Confirmar que o JPA sumiu**

Run: `grep -rnE "jakarta\.persistence|data\.jpa|orm\.jpa|Hibernate|@Transactional|EntityManager|AutoConfigurationPackage" src pom.xml`
Expected: nenhuma saída.

- [ ] **Step 10: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 11: Commit**

Invocar `auto-commit` (a skill deve separar ao menos: store JDBC; modelo de atividade/principal; testes). Sugestão: `feat!: store sessions with JdbcTemplate and record activity with a single update`.

---

### Task 8: Limpeza de expiradas no login, sem task agendada (#7, #8)

**Files:**
- Delete: `scheduler/SentretCleanupTask.java`, `src/test/java/.../unit/SentretCleanupTaskTest.java`
- Modify: `autoconfigure/SentretAutoConfiguration.java`, `config/SentretProperties.java`, `service/SentretService.java`, `unit/SentretServiceTest.java`, `SentretApplicationTests.java`

**Interfaces:**
- Consumes: `SentretSessionStore#deleteExpired(Instant)` (Tarefa 7).
- Produces: `login(...)` apaga expiradas antes de inserir; `SentretService#deleteExpired()` **deixa de existir**; nenhuma propriedade `cleanup-*`.

- [ ] **Step 1: Escrever os testes que falham**

Em `unit/SentretServiceTest.java`, trocar `deleteExpiredDelegatesToStore` por (import `org.mockito.InOrder` e `static org.mockito.Mockito.inOrder`):

```java
    /** No scheduler: expired rows are purged on login, which is rare and hits the expires_at index. */
    @Test
    void loginPurgesExpiredSessionsBeforeInsertingTheNewOne() {
        service.login("user-1", "user@test.com", new MockHttpServletResponse());

        InOrder order = inOrder(store);
        order.verify(store).deleteExpired(any());
        order.verify(store).insert(any());
    }
```

Em `SentretApplicationTests.java`, trocar `cleanupRemovesExpiredSessions` (e remover o campo `sentretService` e seu import) por:

```java
    @Test
    void loginPurgesExpiredSessions() throws Exception {
        Cookie old = login("user3", "user3@test.com");
        expire(old.getValue());

        login("user3b", "user3b@test.com");

        assertThat(sessionCount(old.getValue())).isZero();
    }

    /**
     * The library used to put @EnableScheduling on the host application as a side effect. It must
     * not: scheduling is the host application's decision.
     */
    @Test
    void libraryDoesNotEnableSchedulingInTheHostApplication() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
```

(import `org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor`).

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='SentretServiceTest#loginPurgesExpiredSessionsBeforeInsertingTheNewOne,SentretApplicationTests#loginPurgesExpiredSessions+libraryDoesNotEnableSchedulingInTheHostApplication'`
Expected: FAIL nos três (`deleteExpired` não é chamado no login; o post-processor de agendamento existe).

- [ ] **Step 3: Implementar**

1. `service/SentretService.java`: no `login`, logo após `Instant now = Instant.now();`, adicionar `store.deleteExpired(now);`; apagar o método público `deleteExpired()`.
2. Apagar `scheduler/SentretCleanupTask.java` (o pacote `scheduler` some) e `unit/SentretCleanupTaskTest.java`.
3. `autoconfigure/SentretAutoConfiguration.java`: apagar a classe aninhada `CleanupConfiguration` inteira e os imports `org.springframework.context.annotation.Configuration`, `org.springframework.scheduling.annotation.EnableScheduling` e `io.github.sidneyroberto9.sentret.scheduler.SentretCleanupTask`.
4. `config/SentretProperties.java`: apagar `cleanupEnabled` (com o Javadoc) e `cleanupCron`.

- [ ] **Step 4: Confirmar que não há agendamento nem log de rotina**

Run: `grep -rnE "Scheduled|EnableScheduling|cleanup|log\.info" src/main`
Expected: nenhuma saída.

- [ ] **Step 5: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

Invocar `auto-commit`. Sugestão: `feat!: purge expired sessions on login instead of a scheduled task`.

---

### Task 9: Propriedades enxutas e agrupadas (#11)

**Files:**
- Create: `src/test/java/.../unit/SentretPropertiesTest.java`
- Modify (reescrever): `config/SentretProperties.java`, `config/SentretSecurityValidator.java`, `service/SentretCookieManager.java`, `autoconfigure/SentretAutoConfiguration.java`
- Modify: `autoconfigure/SentretEndpointsAutoConfiguration.java`, `web/controller/SentretSessionController.java`, `unit/SentretCookieManagerTest.java`, `unit/SentretSecurityValidatorTest.java`, `integration/SentretSessionControllerIntegrationTest.java`, `integration/SentretAutoConfigurationIntegrationTest.java`, `SentretApplicationTests.java`

**Interfaces:**
- Consumes: `isIdleEnabled()` (Tarefa 7).
- Produces: `SentretProperties` com exatamente os campos `enabled`, `ttl`, `maxIdle`, `cookieName`, `cookieSecure`, `cookieSameSite`, `cookieDomain`, `csrfEnabled`, `corsAllowedOrigins`, `permitAllPaths`, `hub`; `SentretProperties.Hub` com `enabled`, `basePath`, `heartbeatInterval`, `statusPollInterval`, `warningBefore`, `loginUrl`, `logoutUrl`, `redirectAfterExpiryUrl` (os dois últimos saem na Tarefa 10). Getters: `getHub().isEnabled()`, `getHub().getBasePath()`, etc. Propriedades `sentret.hub.*`.

- [ ] **Step 1: Escrever o teste que falha**

Conteúdo completo de `unit/SentretPropertiesTest.java`:

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SentretPropertiesTest {

    private static SentretProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("sentret", SentretProperties.class);
    }

    @Test
    void worksWithZeroConfiguration() {
        SentretProperties properties = bind(Map.of());

        assertThat(properties.getTtl()).isEqualTo(Duration.ofHours(8));
        assertThat(properties.getMaxIdle()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getCookieName()).isEqualTo("SENTRETSID");
        assertThat(properties.isCookieSecure()).isTrue();
        assertThat(properties.getCookieSameSite()).isEqualTo("Lax");
        assertThat(properties.isCsrfEnabled()).isFalse();
        assertThat(properties.getCorsAllowedOrigins()).isEmpty();
        assertThat(properties.getHub().isEnabled()).isFalse();
        assertThat(properties.getHub().getBasePath()).isEqualTo("/session");
    }

    @Test
    void hubPropertiesBindUnderTheHubPrefix() {
        SentretProperties properties = bind(Map.of(
                "sentret.hub.enabled", "true",
                "sentret.hub.base-path", "/api/lite/session",
                "sentret.hub.heartbeat-interval", "30s",
                "sentret.hub.login-url", "https://login.example.com"));

        assertThat(properties.getHub().isEnabled()).isTrue();
        assertThat(properties.getHub().getBasePath()).isEqualTo("/api/lite/session");
        assertThat(properties.getHub().getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getHub().getLoginUrl()).isEqualTo("https://login.example.com");
    }

    /** Pins the public configuration surface: anything new here is a deliberate decision. */
    @Test
    void coreConfigurationSurfaceIsExactlyTheseProperties() {
        // JaCoCo adds a synthetic $jacocoData field when coverage is on.
        assertThat(Arrays.stream(SentretProperties.class.getDeclaredFields()).filter(field -> !field.isSynthetic()).map(Field::getName))
                .containsExactlyInAnyOrder(
                        "enabled", "ttl", "maxIdle", "cookieName", "cookieSecure", "cookieSameSite",
                        "cookieDomain", "csrfEnabled", "corsAllowedOrigins", "permitAllPaths", "hub");
    }
}
```

Em `integration/SentretAutoConfigurationIntegrationTest`, apagar a linha `"sentret.cors-enabled=true",` (o CORS passa a ser inferido das origens). Em `SentretApplicationTests`, adicionar (imports `static ...MockMvcRequestBuilders.options` e `static ...MockMvcResultMatchers.header`):

```java
    @Test
    void corsIsOffWhenNoOriginIsConfigured() throws Exception {
        mockMvc.perform(options("/login")
                        .header("Origin", "http://example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='SentretPropertiesTest,SentretAutoConfigurationIntegrationTest'`
Expected: FAIL — `getHub()` não existe (compilação); depois, sem `cors-enabled`, o preflight não recebe `Access-Control-Allow-Origin`.

- [ ] **Step 3: Implementar**

Conteúdo completo de `config/SentretProperties.java`:

```java
package io.github.sidneyroberto9.sentret.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Every property has a safe default: the library works with no configuration at all.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "sentret")
public class SentretProperties {

    private boolean enabled = true;

    /** Absolute session lifetime. */
    private Duration ttl = Duration.ofHours(8);

    /**
     * Inactivity window, measured from the last heartbeat. {@code null}, zero or negative disables
     * it. Keep {@code hub.heartbeat-interval} well below it.
     */
    private Duration maxIdle = Duration.ofMinutes(30);

    /** Session cookie name. Prefix it with {@code __Host-} (e.g. {@code __Host-SID}) to harden it. */
    private String cookieName = "SENTRETSID";

    /** Send the cookie over HTTPS only. Set {@code false} for local development over plain HTTP. */
    private boolean cookieSecure = true;

    private String cookieSameSite = "Lax";

    /** Share the cookie across subdomains, e.g. {@code example.com}. */
    private String cookieDomain;

    /** CSRF protection on the default security chain. Keep SameSite=Lax/Strict when disabled. */
    private boolean csrfEnabled = false;

    /** Origins allowed to call the API with the session cookie. CORS is on when this is not empty. */
    private List<String> corsAllowedOrigins = new ArrayList<>();

    private List<String> permitAllPaths = new ArrayList<>(List.of("/login", "/auth/**", "/public/**"));

    private final Hub hub = new Hub();

    /** Whether {@link #maxIdle} is enforced: {@code null}, zero or negative disables it. */
    public boolean isIdleEnabled() {
        return maxIdle != null && !maxIdle.isZero() && !maxIdle.isNegative();
    }

    /**
     * Inactivity hub consumed by the {@code @media4all/session-lite} client. Only read when
     * {@code sentret.hub.enabled=true}.
     */
    @Getter
    @Setter
    public static class Hub {

        private boolean enabled = false;

        private String basePath = "/session";

        /** How often the client sends a heartbeat. Echoed to the client. */
        private Duration heartbeatInterval = Duration.ofSeconds(60);

        /** How often the client polls the status. Echoed to the client. */
        private Duration statusPollInterval = Duration.ofSeconds(30);

        /** How long before expiry the client shows the warning. Echoed to the client. */
        private Duration warningBefore = Duration.ofSeconds(60);

        /** Where the client sends the user when the session ends. Echoed to the client. */
        private String loginUrl;

        private String logoutUrl;

        private String redirectAfterExpiryUrl;
    }
}
```

Conteúdo completo de `service/SentretCookieManager.java`:

```java
package io.github.sidneyroberto9.sentret.service;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

@RequiredArgsConstructor
public class SentretCookieManager {

    private final SentretProperties properties;

    public String cookieName() {
        return properties.getCookieName();
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        String name = cookieName();

        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    public void write(HttpServletResponse response, String sessionId) {
        response.addHeader("Set-Cookie", build(sessionId, properties.getTtl()).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookieName(), value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path("/")
                .maxAge(maxAge);

        if (properties.getCookieDomain() != null) {
            builder.domain(properties.getCookieDomain());
        }

        return builder.build();
    }
}
```

Conteúdo completo de `config/SentretSecurityValidator.java`:

```java
package io.github.sidneyroberto9.sentret.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;

/** Warns at startup about configurations that work but log active users out or weaken CSRF. */
@Slf4j
@RequiredArgsConstructor
public class SentretSecurityValidator implements InitializingBean {

    private final SentretProperties properties;

    @Override
    public void afterPropertiesSet() {
        SentretProperties.Hub hub = properties.getHub();
        Duration maxIdle = properties.getMaxIdle();

        if ("None".equalsIgnoreCase(properties.getCookieSameSite()) && !properties.isCsrfEnabled()) {
            log.warn("[sentret] 'cookie-same-site=None' with CSRF disabled is unsafe for cookie-based auth. "
                    + "Enable 'sentret.csrf-enabled' or use SameSite=Lax/Strict.");
        }

        if (properties.isIdleEnabled() && hub.getHeartbeatInterval().compareTo(maxIdle.dividedBy(2)) >= 0) {
            log.warn("[sentret] 'heartbeat-interval' ({}) is >= half of 'max-idle' ({}). The heartbeat is the only "
                    + "thing that resets the idle window, so an active user can be logged out anyway. Set "
                    + "'sentret.hub.heartbeat-interval' to a quarter of 'max-idle' or less.", hub.getHeartbeatInterval(), maxIdle);
        }

        if (hub.getStatusPollInterval().compareTo(hub.getWarningBefore()) >= 0) {
            log.warn("[sentret] 'status-poll-interval' ({}) is >= 'warning-before' ({}). A session can go from outside "
                    + "the warning window straight to expired without the user ever seeing the warning. Set "
                    + "'sentret.hub.status-poll-interval' well below 'warning-before'.", hub.getStatusPollInterval(), hub.getWarningBefore());
        }

        if (properties.isIdleEnabled() && hub.getWarningBefore().compareTo(maxIdle) >= 0) {
            log.warn("[sentret] 'warning-before' ({}) is >= 'max-idle' ({}), so the client shows the inactivity "
                    + "warning as soon as the session starts. Set 'sentret.hub.warning-before' below 'max-idle'.",
                    hub.getWarningBefore(), maxIdle);
        }
    }
}
```

Conteúdo completo de `autoconfigure/SentretAutoConfiguration.java`:

```java
package io.github.sidneyroberto9.sentret.autoconfigure;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.config.SentretSecurityValidator;
import io.github.sidneyroberto9.sentret.security.SentretAuthenticationFilter;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.service.SentretUserService;
import io.github.sidneyroberto9.sentret.store.JdbcSentretSessionStore;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration
@ConditionalOnClass({JdbcTemplate.class, SecurityFilterChain.class})
@ConditionalOnProperty(prefix = "sentret", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SentretProperties.class)
@Import(SentretWebMvcConfiguration.class)
public class SentretAutoConfiguration {

    private static final List<String> CORS_ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Bean
    @ConditionalOnMissingBean
    public SentretSecurityValidator sentretSecurityValidator(SentretProperties properties) {
        return new SentretSecurityValidator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretCookieManager cookieManager(SentretProperties properties) {
        return new SentretCookieManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretSessionStore sentretSessionStore(JdbcTemplate jdbcTemplate) {
        return new JdbcSentretSessionStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretService sentretService(
            SentretProperties properties,
            SentretSessionStore store,
            SentretCookieManager cookieManager,
            ApplicationEventPublisher eventPublisher
    ) {
        return new SentretService(store, properties, eventPublisher, cookieManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretUserService sentretUserService() {
        return new SentretUserService();
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretAuthenticationFilter sentretAuthenticationFilter(
            SentretService sentretService,
            SentretCookieManager cookieManager
    ) {
        return new SentretAuthenticationFilter(sentretService, cookieManager);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain sentretSecurityFilterChain(
            HttpSecurity http,
            SentretAuthenticationFilter sentretAuthenticationFilter,
            SentretProperties properties
    ) throws Exception {
        List<String> permitAll = new ArrayList<>(properties.getPermitAllPaths());

        if (properties.getHub().isEnabled()) {
            permitAll.add(properties.getHub().getBasePath() + "/status");
        }

        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .logout(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(permitAll.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(sentretAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (properties.isCsrfEnabled()) {
            http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        } else {
            http.csrf(AbstractHttpConfigurer::disable);
        }

        if (properties.getCorsAllowedOrigins().isEmpty()) {
            http.cors(AbstractHttpConfigurer::disable);
        } else {
            http.cors(cors -> cors.configurationSource(corsConfigurationSource(properties)));
        }

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource(SentretProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCorsAllowedOrigins());
        config.setAllowedMethods(CORS_ALLOWED_METHODS);
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

`autoconfigure/SentretEndpointsAutoConfiguration.java`: trocar `@ConditionalOnProperty(prefix = "sentret", name = "endpoints-enabled")` por `@ConditionalOnProperty(prefix = "sentret.hub", name = "enabled")`.

`web/controller/SentretSessionController.java`: `@RequestMapping("${sentret.endpoints-base-path:/session}")` → `@RequestMapping("${sentret.hub.base-path:/session}")`; o `buildConfig()` fica:

```java
    private SessionStatusResponse.Config buildConfig() {
        SentretProperties.Hub hub = properties.getHub();

        return new SessionStatusResponse.Config(
                properties.getTtl().toMillis(),
                properties.getMaxIdle() == null ? 0L : properties.getMaxIdle().toMillis(),
                hub.getHeartbeatInterval().toMillis(),
                hub.getStatusPollInterval().toMillis(),
                hub.getWarningBefore().toMillis(),
                hub.getLoginUrl(),
                hub.getLogoutUrl(),
                hub.getRedirectAfterExpiryUrl());
    }
```

(o ternário herdado some junto com este controller na Tarefa 10).

- [ ] **Step 4: Ajustar os testes existentes**

```bash
V=src/test/java/io/github/sidneyroberto9/sentret/unit/SentretSecurityValidatorTest.java
perl -pi -e 's/properties\.set(HeartbeatInterval|StatusPollInterval|WarningBefore)\(/properties.getHub().set$1(/g' "$V"
perl -pi -e 's/"sentret\.endpoints-enabled=true"/"sentret.hub.enabled=true"/' src/test/java/io/github/sidneyroberto9/sentret/integration/SentretSessionControllerIntegrationTest.java
```

`max-idle` agora vem ligado (`30m`). Em `SentretSecurityValidatorTest#doesNotWarnWhenMaxIdleDisabled`, que contava com o padrão antigo (`ZERO`), trocar o comentário `// maxIdle = ZERO` por uma linha explícita:

```java
        properties.setMaxIdle(Duration.ZERO);
```

Se outro teste da suíte falhar por assumir `max-idle` desligado por padrão, aplicar a mesma correção nele (setar `Duration.ZERO` explicitamente), nunca mudar o padrão.

Em `unit/SentretCookieManagerTest.java`, trocar `cookieNameUsesDefaultPrefix` e `cookieNameAppliesCustomPrefix` por:

```java
    @Test
    void cookieNameDefaultsToSentretSid() {
        SentretCookieManager manager = new SentretCookieManager(properties());

        assertThat(manager.cookieName()).isEqualTo("SENTRETSID");
    }

    @Test
    void cookieNameCanCarryTheHostPrefix() {
        SentretProperties properties = properties();
        properties.setCookieName("__Host-SID");
        SentretCookieManager manager = new SentretCookieManager(properties);

        assertThat(manager.cookieName()).isEqualTo("__Host-SID");
    }
```

- [ ] **Step 5: Confirmar que as props removidas sumiram**

Run: `grep -rnE "cookie-?[Pp]refix|cookie-?[Pp]ath|cors-?[Ee]nabled|corsAllowedMethods|cors-allowed-methods|corsAllowCredentials|cors-allow-credentials|endpoints-?[Ee]nabled|endpoints-?[Bb]ase-?[Pp]ath|endpointsBasePath" src`
Expected: nenhuma saída.

- [ ] **Step 6: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

Invocar `auto-commit`. Sugestão: `feat!: trim configuration to safe defaults and group hub settings`.

---

### Task 10: Hub enxuto — Controller só HTTP, Service, DTOs (#9, #13, #14, #15)

**Files:**
- Delete: `web/controller/SentretSessionController.java`, `autoconfigure/SentretEndpointsAutoConfiguration.java`, `service/SentretSessionRemaining.java`, `src/test/java/.../unit/SentretSessionControllerTest.java`, `src/test/java/.../integration/SentretSessionControllerIntegrationTest.java`
- Create: `hub/SentretHubController.java`, `hub/SentretHubStatusService.java`, `hub/dto/response/SessionStatusResponse.java`, `hub/dto/response/SessionConfigResponse.java`, `autoconfigure/SentretHubAutoConfiguration.java`, `src/test/java/.../unit/SentretHubStatusServiceTest.java`, `src/test/java/.../unit/SentretHubControllerTest.java`, `src/test/java/.../integration/SentretHubIntegrationTest.java`
- Modify: `config/SentretProperties.java`, `service/SentretService.java`, `unit/SentretServiceTest.java`, `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Interfaces:**
- Consumes: `SentretService#touch(SentretUser)`, `renew(HttpServletRequest, HttpServletResponse)` (Tarefa 7); `SentretProperties.Hub` (Tarefa 9).
- Produces:
  - `record SessionStatusResponse(boolean authenticated, String userId, String email, Long absoluteRemainingMs, Long idleRemainingMs, SessionConfigResponse config)` (`@JsonInclude(NON_NULL)`).
  - `record SessionConfigResponse(long heartbeatIntervalMs, long statusPollIntervalMs, long warningBeforeMs, String loginUrl)`.
  - `SentretHubStatusService#status(SentretUser user)` (aceita `null` → anônimo).
  - `SentretHubController`: `status(SentretUser)`, `heartbeat(SentretUser)`, `renew(HttpServletRequest, HttpServletResponse)`.
  - `SentretHubAutoConfiguration` (condição `sentret.hub.enabled=true`).
  - `SentretService#remaining` e `SentretSessionRemaining` **deixam de existir**; `Hub` perde `logoutUrl` e `redirectAfterExpiryUrl`.

- [ ] **Step 1: Escrever os testes que falham**

Conteúdo completo de `unit/SentretHubStatusServiceTest.java`:

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.hub.SentretHubStatusService;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionConfigResponse;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionStatusResponse;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SentretHubStatusServiceTest {

    private static final Offset<Long> FIVE_SECONDS = Offset.offset(5_000L);

    private SentretProperties properties;
    private SentretHubStatusService service;

    @BeforeEach
    void setUp() {
        properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.getHub().setLoginUrl("https://login.example.com");
        service = new SentretHubStatusService(properties);
    }

    private static SentretUser user(Instant expiresAt, Instant lastAccessedAt) {
        return new SentretUser("user-1", "user@test.com", "sid", expiresAt, lastAccessedAt);
    }

    @Test
    void anonymousStatusCarriesOnlyTheConfig() {
        SessionStatusResponse status = service.status(null);

        assertThat(status.authenticated()).isFalse();
        assertThat(status.userId()).isNull();
        assertThat(status.absoluteRemainingMs()).isNull();
        assertThat(status.idleRemainingMs()).isNull();
        assertThat(status.config()).isNotNull();
    }

    @Test
    void authenticatedStatusComputesBothDeadlinesFromThePrincipal() {
        Instant now = Instant.now();

        SessionStatusResponse status = service.status(user(now.plus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(4))));

        assertThat(status.authenticated()).isTrue();
        assertThat(status.userId()).isEqualTo("user-1");
        assertThat(status.email()).isEqualTo("user@test.com");
        assertThat(status.absoluteRemainingMs()).isCloseTo(Duration.ofMinutes(30).toMillis(), FIVE_SECONDS);
        assertThat(status.idleRemainingMs()).isCloseTo(Duration.ofMinutes(6).toMillis(), FIVE_SECONDS);
    }

    @Test
    void idleRemainingIsNullWhenMaxIdleDisabled() {
        properties.setMaxIdle(Duration.ZERO);
        Instant now = Instant.now();

        SessionStatusResponse status = service.status(user(now.plus(Duration.ofMinutes(30)), now));

        assertThat(status.idleRemainingMs()).isNull();
    }

    @Test
    void remainingTimesNeverGoNegative() {
        Instant now = Instant.now();

        SessionStatusResponse status = service.status(user(now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofMinutes(20))));

        assertThat(status.absoluteRemainingMs()).isZero();
        assertThat(status.idleRemainingMs()).isZero();
    }

    /** Only what the npm client reads: ttlMs, maxIdleMs, logoutUrl and redirectAfterExpiryUrl are gone. */
    @Test
    void configEchoesOnlyWhatTheClientReads() {
        assertThat(service.status(null).config())
                .isEqualTo(new SessionConfigResponse(60_000L, 30_000L, 60_000L, "https://login.example.com"));
    }
}
```

Conteúdo completo de `unit/SentretHubControllerTest.java`:

```java
package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.hub.SentretHubController;
import io.github.sidneyroberto9.sentret.hub.SentretHubStatusService;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionConfigResponse;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionStatusResponse;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SentretHubControllerTest {

    private static final SessionConfigResponse CONFIG = new SessionConfigResponse(60_000L, 30_000L, 60_000L, null);
    private static final SentretUser USER = new SentretUser(
            "user-1", "user@test.com", "sid", Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2029-12-31T23:00:00Z"));

    private SentretService sessionService;
    private SentretHubStatusService statusService;
    private SentretHubController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SentretService.class);
        statusService = mock(SentretHubStatusService.class);
        controller = new SentretHubController(sessionService, statusService);
    }

    private static SessionStatusResponse authenticated() {
        return new SessionStatusResponse(true, "user-1", "user@test.com", 1_000L, 500L, CONFIG);
    }

    @Test
    void statusReturnsWhatTheStatusServiceBuilds() {
        when(statusService.status(USER)).thenReturn(authenticated());

        ResponseEntity<SessionStatusResponse> response = controller.status(USER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(authenticated());
    }

    @Test
    void heartbeatTouchesThenReportsTheRefreshedPrincipal() {
        SentretUser touched = new SentretUser("user-1", "user@test.com", "sid", USER.expiresAt(), Instant.parse("2030-01-01T00:00:00Z"));
        when(sessionService.touch(USER)).thenReturn(touched);
        when(statusService.status(touched)).thenReturn(authenticated());

        ResponseEntity<SessionStatusResponse> response = controller.heartbeat(USER);

        verify(sessionService).touch(USER);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(authenticated());
    }

    @Test
    void renewReturns401WithoutBodyWhenTheSessionIsGone() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        when(sessionService.renew(request, httpResponse)).thenReturn(Optional.empty());

        ResponseEntity<SessionStatusResponse> response = controller.renew(request, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void renewReturnsTheRenewedStatus() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        when(sessionService.renew(request, httpResponse)).thenReturn(Optional.of(USER));
        when(statusService.status(USER)).thenReturn(authenticated());

        ResponseEntity<SessionStatusResponse> response = controller.renew(request, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(authenticated());
    }
}
```

Conteúdo completo de `integration/SentretHubIntegrationTest.java` (com o base-path real do eleva-docs — Review Focus 1):

```java
package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hub as eleva-docs runs it: custom base path, idle enforcement on, login URL echoed. The
 * three endpoints the @media4all/session-lite client calls must keep their contract.
 */
@SpringBootTest(classes = SampleApplication.class)
@TestPropertySource(properties = {
        "sentret.hub.enabled=true",
        "sentret.hub.base-path=/api/lite/session",
        "sentret.hub.login-url=https://login.example.com",
        "sentret.max-idle=10m"
})
class SentretHubIntegrationTest {

    private static final String HUB = "/api/lite/session";
    private static final long TTL_MS = 8 * 60 * 60 * 1000L;
    private static final long MAX_IDLE_MS = 10 * 60 * 1000L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM sentret_sessions");
    }

    private Cookie login(String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"email\":\"" + userId + "@test.com\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void setColumn(String column, String sessionId, Instant value) {
        jdbc.update("UPDATE sentret_sessions SET " + column + " = ? WHERE session_id = ?", value.toEpochMilli(), sessionId);
    }

    private Instant column(String column, String sessionId) {
        Long millis = jdbc.queryForObject("SELECT " + column + " FROM sentret_sessions WHERE session_id = ?", Long.class, sessionId);
        return Instant.ofEpochMilli(millis);
    }

    @Test
    void statusIsPermitAllAndAnonymousCarriesOnlyTheConfig() throws Exception {
        mockMvc.perform(get(HUB + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.absoluteRemainingMs").doesNotExist())
                .andExpect(jsonPath("$.config.heartbeatIntervalMs").value(60_000))
                .andExpect(jsonPath("$.config.statusPollIntervalMs").value(30_000))
                .andExpect(jsonPath("$.config.warningBeforeMs").value(60_000))
                .andExpect(jsonPath("$.config.loginUrl").value("https://login.example.com"))
                .andExpect(jsonPath("$.config.ttlMs").doesNotExist())
                .andExpect(jsonPath("$.config.maxIdleMs").doesNotExist())
                .andExpect(jsonPath("$.config.logoutUrl").doesNotExist())
                .andExpect(jsonPath("$.config.redirectAfterExpiryUrl").doesNotExist());
    }

    @Test
    void defaultBasePathIsNotExposed() throws Exception {
        mockMvc.perform(get("/session/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusWithValidCookieReturnsUserAndRemainingTimes() throws Exception {
        Cookie cookie = login("user1");

        mockMvc.perform(get(HUB + "/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.email").value("user1@test.com"))
                .andExpect(jsonPath("$.absoluteRemainingMs").value(greaterThan((int) (TTL_MS - 10_000))))
                .andExpect(jsonPath("$.idleRemainingMs").value(greaterThan((int) (MAX_IDLE_MS - 10_000))));
    }

    @Test
    void heartbeatResetsTheIdleClock() throws Exception {
        Cookie cookie = login("user2");
        setColumn("last_accessed_at", cookie.getValue(), Instant.now().minus(6, ChronoUnit.MINUTES));

        mockMvc.perform(post(HUB + "/heartbeat").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idleRemainingMs").value(greaterThan((int) (MAX_IDLE_MS - 10_000))));

        assertThat(column("last_accessed_at", cookie.getValue())).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void heartbeatWithoutCookieReturns401() throws Exception {
        mockMvc.perform(post(HUB + "/heartbeat")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusPollDoesNotAdvanceLastAccessedAt() throws Exception {
        Cookie cookie = login("user-poll");
        Instant stale = Instant.now().minus(6, ChronoUnit.MINUTES);
        setColumn("last_accessed_at", cookie.getValue(), stale);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get(HUB + "/status").cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true));
        }

        assertThat(column("last_accessed_at", cookie.getValue())).isCloseTo(stale, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void statusPollDoesNotRescueAnIdleExpiredSession() throws Exception {
        Cookie cookie = login("user-idle");
        setColumn("last_accessed_at", cookie.getValue(), Instant.now().minus(11, ChronoUnit.MINUTES));

        mockMvc.perform(get(HUB + "/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));

        mockMvc.perform(post(HUB + "/heartbeat").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renewResetsAbsoluteExpiryAndRewritesTheCookie() throws Exception {
        Cookie cookie = login("user3");
        Instant nearExpiry = Instant.now().plusSeconds(60);
        setColumn("expires_at", cookie.getValue(), nearExpiry);

        mockMvc.perform(post(HUB + "/renew").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absoluteRemainingMs").value(greaterThan((int) (TTL_MS - 10_000))))
                .andExpect(header().exists("Set-Cookie"));

        assertThat(column("expires_at", cookie.getValue())).isAfter(nearExpiry);
    }

    @Test
    void renewWithoutCookieReturns401WithoutBody() throws Exception {
        mockMvc.perform(post(HUB + "/renew"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    /** The client never called it: every app logs out through its own endpoint. */
    @Test
    void logoutEndpointNoLongerExists() throws Exception {
        Cookie cookie = login("user4");

        // 404 (no handler) or 405 (static-resource fallback): either way, nothing answers it.
        mockMvc.perform(post(HUB + "/logout").cookie(cookie)).andExpect(status().is4xxClientError());

        mockMvc.perform(get(HUB + "/status").cookie(cookie)).andExpect(jsonPath("$.authenticated").value(true));
    }
}
```

Apagar `unit/SentretSessionControllerTest.java` e `integration/SentretSessionControllerIntegrationTest.java`. Em `unit/SentretServiceTest.java`, apagar os três testes `remaining…` e o import `SentretSessionRemaining` (a cobertura foi para `SentretHubStatusServiceTest`).

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test-compile`
Expected: FAIL — pacote `io.github.sidneyroberto9.sentret.hub` não existe.

- [ ] **Step 3: Implementar**

Conteúdo completo de `hub/dto/response/SessionConfigResponse.java`:

```java
package io.github.sidneyroberto9.sentret.hub.dto.response;

/**
 * Client settings echoed by the hub, in milliseconds. Only what the @media4all/session-lite
 * client reads.
 */
public record SessionConfigResponse(
        long heartbeatIntervalMs,
        long statusPollIntervalMs,
        long warningBeforeMs,
        String loginUrl
) {
}
```

Conteúdo completo de `hub/dto/response/SessionStatusResponse.java`:

```java
package io.github.sidneyroberto9.sentret.hub.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of every hub endpoint. Null fields are omitted, so an anonymous status carries only
 * {@code authenticated} and {@code config}; {@code idleRemainingMs} is absent when max-idle is off.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionStatusResponse(
        boolean authenticated,
        String userId,
        String email,
        Long absoluteRemainingMs,
        Long idleRemainingMs,
        SessionConfigResponse config
) {
}
```

Conteúdo completo de `hub/SentretHubStatusService.java`:

```java
package io.github.sidneyroberto9.sentret.hub;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionConfigResponse;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionStatusResponse;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * Builds the status body served by the hub. Remaining times come from the principal the
 * authentication filter already loaded, so no endpoint reads the session row twice.
 */
@RequiredArgsConstructor
public class SentretHubStatusService {

    private final SentretProperties properties;

    public SessionStatusResponse status(SentretUser user) {
        SessionConfigResponse config = config();

        if (user == null) {
            return new SessionStatusResponse(false, null, null, null, null, config);
        }

        Instant now = Instant.now();

        return new SessionStatusResponse(
                true,
                user.userId(),
                user.email(),
                remainingMs(now, user.expiresAt()),
                idleRemainingMs(user, now),
                config);
    }

    private Long idleRemainingMs(SentretUser user, Instant now) {
        if (!properties.isIdleEnabled()) {
            return null;
        }

        return remainingMs(now, user.lastAccessedAt().plus(properties.getMaxIdle()));
    }

    private static long remainingMs(Instant now, Instant deadline) {
        return Math.max(0, Duration.between(now, deadline).toMillis());
    }

    private SessionConfigResponse config() {
        SentretProperties.Hub hub = properties.getHub();

        return new SessionConfigResponse(
                hub.getHeartbeatInterval().toMillis(),
                hub.getStatusPollInterval().toMillis(),
                hub.getWarningBefore().toMillis(),
                hub.getLoginUrl());
    }
}
```

Conteúdo completo de `hub/SentretHubController.java`:

```java
package io.github.sidneyroberto9.sentret.hub;

import io.github.sidneyroberto9.sentret.hub.dto.response.SessionStatusResponse;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Inactivity hub endpoints consumed by the @media4all/session-lite client. HTTP only: the rules
 * live in {@link SentretService} and the body in {@link SentretHubStatusService}. {@code status} is
 * permit-all; {@code heartbeat} and {@code renew} require a session (enforced by the security chain).
 */
@RestController
@RequestMapping("${sentret.hub.base-path:/session}")
@RequiredArgsConstructor
public class SentretHubController {

    private final SentretService sessionService;
    private final SentretHubStatusService statusService;

    @GetMapping("/status")
    public ResponseEntity<SessionStatusResponse> status(@AuthenticationPrincipal SentretUser user) {
        return ResponseEntity.status(HttpStatus.OK).body(statusService.status(user));
    }

    /** The only user-activity signal: the client sends it from real DOM events. */
    @PostMapping("/heartbeat")
    public ResponseEntity<SessionStatusResponse> heartbeat(@AuthenticationPrincipal SentretUser user) {
        SentretUser touched = sessionService.touch(user);
        return ResponseEntity.status(HttpStatus.OK).body(statusService.status(touched));
    }

    @PostMapping("/renew")
    public ResponseEntity<SessionStatusResponse> renew(HttpServletRequest request, HttpServletResponse response) {
        Optional<SentretUser> renewed = sessionService.renew(request, response);

        if (renewed.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.status(HttpStatus.OK).body(statusService.status(renewed.get()));
    }
}
```

> Importante: o import é `org.springframework.security.core.annotation.AuthenticationPrincipal` (o de `security.web.bind.annotation` é o antigo, deprecated).

Conteúdo completo de `autoconfigure/SentretHubAutoConfiguration.java`:

```java
package io.github.sidneyroberto9.sentret.autoconfigure;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.hub.SentretHubController;
import io.github.sidneyroberto9.sentret.hub.SentretHubStatusService;
import io.github.sidneyroberto9.sentret.service.SentretService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the opt-in inactivity hub ({@code sentret.hub.enabled=true}). Explicit beans because a
 * consumer's component scan does not reach this package. The permit-all rule for {@code status}
 * lives in {@link SentretAutoConfiguration}, the one place that builds the default chain.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "sentret.hub", name = "enabled")
@EnableConfigurationProperties(SentretProperties.class)
public class SentretHubAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SentretHubStatusService sentretHubStatusService(SentretProperties properties) {
        return new SentretHubStatusService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretHubController sentretHubController(SentretService sentretService, SentretHubStatusService sentretHubStatusService) {
        return new SentretHubController(sentretService, sentretHubStatusService);
    }
}
```

Conteúdo completo de `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
io.github.sidneyroberto9.sentret.autoconfigure.SentretAutoConfiguration
io.github.sidneyroberto9.sentret.autoconfigure.SentretHubAutoConfiguration
```

Apagar `web/controller/SentretSessionController.java` (o pacote `web.controller` some), `autoconfigure/SentretEndpointsAutoConfiguration.java` e `service/SentretSessionRemaining.java`. Em `service/SentretService.java`, apagar o método `remaining(SentretUser)` (e o helper `remainingMs` + import `java.time.Duration`, se não restar uso). Em `config/SentretProperties.java`, apagar `logoutUrl` e `redirectAfterExpiryUrl` da classe `Hub`.

- [ ] **Step 4: Aplicar a skill `spring-clean` nos arquivos novos**

Invocar a skill `spring-clean` sobre `src/main/java/io/github/sidneyroberto9/sentret/hub/`. Expected: nenhuma correção (sem `@Autowired`, todo retorno em `ResponseEntity.status(...)`, `if` com bloco, sem ternário). Corrigir o que ela apontar.

- [ ] **Step 5: Confirmar o contrato**

Run: `grep -rnE "ResponseEntity\.(ok|noContent|notFound|badRequest)\(|ErrorResponse|SessionRemaining|endpoints|logoutUrl|redirectAfterExpiryUrl" src/main`
Expected: nenhuma saída.

- [ ] **Step 6: Rodar a suíte**

Run: `./mvnw test`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

Invocar `auto-commit`. Sugestão: `feat!: slim inactivity hub to status, heartbeat and renew`.

---

### Task 11: Compatibilidade Spring Boot 3 e 4 (#10)

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Consumes: biblioteca sem JPA (Tarefa 7) e testes com APIs estáveis (Tarefa 2).
- Produces: `./mvnw verify` (Boot 3.5.15) e `./mvnw verify -Pboot4` (Boot 4.1.1) verdes.

- [ ] **Step 1: Ver o profile falhar (ainda não existe)**

Run: `./mvnw -q help:active-profiles -Pboot4`
Expected: aviso `The requested profile "boot4" could not be activated because it does not exist.`

- [ ] **Step 2: Trocar o `<parent>` por import do BOM e criar o profile**

No `pom.xml`:

1. Apagar o bloco `<parent>…</parent>`.
2. Em `<properties>`, adicionar `<spring-boot.version>3.5.15</spring-boot.version>` e `<maven-surefire-plugin.version>3.5.6</maven-surefire-plugin.version>` (a mesma que o Boot 3.5.15 e o 4.1.1 gerenciam; roda o JUnit 6 do Boot 4), e trocar `<jacoco-plugin.version>0.8.12</jacoco-plugin.version>` por `<jacoco-plugin.version>0.8.14</jacoco-plugin.version>` (suporta o bytecode do JDK 25 e acaba com o ruído de instrumentação).
3. Logo antes de `<dependencies>`:

```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

4. No `maven-compiler-plugin`, dentro de `<configuration>`, adicionar `<parameters>true</parameters>` (o parent fazia isso).
5. Em `<plugins>`, adicionar:

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>${maven-surefire-plugin.version}</version>
            </plugin>
```

6. Antes de `</project>`:

```xml
    <profiles>
        <profile>
            <id>boot4</id>
            <properties>
                <spring-boot.version>4.1.1</spring-boot.version>
            </properties>
        </profile>
    </profiles>
```

- [ ] **Step 3: Rodar no Boot 3**

Run: `./mvnw verify -Dgpg.skip`
Expected: `BUILD SUCCESS`, `Failures: 0, Errors: 0`, sem os stack traces do JaCoCo. Se o compilador reclamar de versão vazia em `annotationProcessorPaths`, adicionar `<version>1.18.46</version>` ao Lombok (a versão gerenciada pelo Boot 3.5.15 e pelo 4.1.1) e `<version>${spring-boot.version}</version>` ao `spring-boot-configuration-processor`.

- [ ] **Step 4: Rodar no Boot 4**

Run: `./mvnw verify -Pboot4 -Dgpg.skip`
Expected: `BUILD SUCCESS`, `Failures: 0, Errors: 0`. Se algo quebrar, usar a skill `superpowers:systematic-debugging`; a correção tem que funcionar nas duas versões (nunca referenciar classe de autoconfig do Boot por `Class`; se precisar ordenar, `afterName = {"nome.boot3", "nome.boot4"}`).

- [ ] **Step 5: Confirmar que o código principal não depende de nada que mudou de lugar**

Run: `grep -rnE "org\.springframework\.boot\.autoconfigure\.(orm|data|jdbc|web\.servlet)|tools\.jackson|databind" src/main`
Expected: nenhuma saída.

- [ ] **Step 6: Commit**

Invocar `auto-commit`. Sugestão: `build: support Spring Boot 3 and 4 via BOM import and boot4 profile`.

---

### Task 12: Recursos nativos, documentação e revisão final (#16)

**Files:**
- Delete: `web/SentretCurrentSession.java`, `web/SentretCurrentSessionArgumentResolver.java`, `autoconfigure/SentretWebMvcConfiguration.java`, `src/test/java/.../unit/SentretCurrentSessionArgumentResolverTest.java`, `src/test/java/.../unit/SentretSessionDestroyedEventTest.java`
- Modify (reescrever): `src/test/java/.../sample/SampleController.java`, `event/SentretSessionDestroyedEvent.java`, `README.md`
- Modify: `autoconfigure/SentretAutoConfiguration.java`, `MIGRATION.md`, `CHANGELOG.md`, `docs/01-instalacao-e-uso.md` … `docs/07-proposta-refatoracao.md`

**Interfaces:**
- Consumes: tudo das tarefas anteriores.
- Produces: principal injetado com `@AuthenticationPrincipal SentretUser`; documentação da 1.0.0.

- [ ] **Step 1: Trocar a anotação própria pelo `@AuthenticationPrincipal`**

Conteúdo completo de `src/test/java/io/github/sidneyroberto9/sentret/sample/SampleController.java`:

```java
package io.github.sidneyroberto9.sentret.sample;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SampleController {

    private final SentretService sessionService;

    @PostMapping("/login")
    public ResponseEntity<SentretUser> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        SentretUser user = sessionService.login(body.userId(), body.email(), response);
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionService.logout(request, response);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /** Requires a session (not permit-all), so the security chain answers 401 before this runs. */
    @GetMapping("/me")
    public ResponseEntity<SentretUser> me(@AuthenticationPrincipal SentretUser user) {
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }

    public record LoginRequest(String userId, String email) {
    }
}
```

Apagar `web/SentretCurrentSession.java`, `web/SentretCurrentSessionArgumentResolver.java` (o pacote `web` some), `autoconfigure/SentretWebMvcConfiguration.java` e `unit/SentretCurrentSessionArgumentResolverTest.java`. Em `SentretAutoConfiguration`, apagar `@Import(SentretWebMvcConfiguration.class)` e o import `org.springframework.context.annotation.Import`.

Run: `./mvnw test -Dtest=SentretApplicationTests`
Expected: PASS (o `/me` continua devolvendo o usuário — prova de que o resolver nativo do Spring Security cobre o que a anotação própria fazia).

- [ ] **Step 2: Tirar a compatibilidade com versões antigas dos eventos**

Conteúdo completo de `event/SentretSessionDestroyedEvent.java`:

```java
package io.github.sidneyroberto9.sentret.event;

/**
 * Published after a session is destroyed (logout). Carries {@code userId} so listeners (auditing,
 * metrics) do not need to look the session up after it is gone. It describes exactly one session:
 * never use {@code userId} to act on the user's other sessions.
 */
public record SentretSessionDestroyedEvent(String userId, String sessionId) {
}
```

Apagar `unit/SentretSessionDestroyedEventTest.java` (só testava o construtor legado).

- [ ] **Step 3: Varrer comentários que falam do que não existe mais**

Run: `grep -rnE "SSE|/stream|EventSource|[^0-9.]2\.[0-3]\.[0-9]|ip-hash|ipHash|setRoles|sliding-expiration|last-accessed-throttle|endpoints-enabled|SpringSessionLite|@SentretCurrentSession" src/main`
Expected: nenhuma saída. Reescrever cada comentário encontrado em uma frase sobre o comportamento atual (sem histórico de versões — histórico vai no CHANGELOG).

- [ ] **Step 4: Reescrever o README**

Conteúdo completo de `README.md`:

````markdown
# Sentret

Lightweight cookie-based session authentication for Spring Boot 3 and 4. Sessions live in your
application's own database (plain JDBC, no JPA), the browser carries an opaque `HttpOnly` cookie,
and an optional hub serves the inactivity endpoints used by the `@media4all/session-lite` client.

> Named after Sentret, the lookout Pokémon that stands on its tail to watch over its territory —
> which is what the authentication filter does for every request.

## Install

```xml
<dependency>
    <groupId>io.github.sidneyroberto9</groupId>
    <artifactId>sentret-session</artifactId>
    <version>1.0.0</version>
</dependency>
```

Requirements: Java 17+, Spring Boot 3.5+ or 4.x, a `DataSource` with `JdbcTemplate`
(`spring-boot-starter-jdbc` or `spring-boot-starter-data-jpa`).

## Database

Run once (Flyway, Liquibase or by hand). Portable as written across MySQL, MariaDB, PostgreSQL,
SQL Server and H2; the same script ships in the jar at `db/sentret-schema.sql`.

```sql
CREATE TABLE sentret_sessions (
    session_id       VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    created_at       BIGINT       NOT NULL,
    expires_at       BIGINT       NOT NULL,
    last_accessed_at BIGINT       NOT NULL
);

CREATE INDEX idx_sentret_sessions_user_id ON sentret_sessions (user_id);
CREATE INDEX idx_sentret_sessions_expires_at ON sentret_sessions (expires_at);
```

## Quickstart

```java
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final SentretService sentret;

    @PostMapping("/auth/login")
    public ResponseEntity<SentretUser> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        String userId = credentials.check(body); // your own authentication
        SentretUser user = sentret.login(userId, body.email(), response);
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sentret.logout(request, response);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/me")
    public ResponseEntity<SentretUser> me(@AuthenticationPrincipal SentretUser user) {
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }
}
```

`SentretUser` carries `userId`, `email`, `sessionId`, `expiresAt` and `lastAccessedAt`. Roles and
permissions stay in your application, looked up by `userId`.

## Properties (prefix `sentret`)

Nothing is required.

| Property | Default | |
|---|---|---|
| `enabled` | `true` | Turn the library off. |
| `ttl` | `8h` | Absolute session lifetime. |
| `max-idle` | `30m` | Inactivity window, reset only by the hub heartbeat. `0` disables it. |
| `cookie-name` | `SENTRETSID` | Use `__Host-SID` to harden the cookie. |
| `cookie-secure` | `true` | `false` only for local HTTP. |
| `cookie-same-site` | `Lax` | |
| `cookie-domain` | — | Share the cookie across subdomains. |
| `csrf-enabled` | `false` | CSRF on the default chain. |
| `cors-allowed-origins` | — | CORS (with credentials) is on when not empty. |
| `permit-all-paths` | `/login`, `/auth/**`, `/public/**` | |
| `hub.enabled` | `false` | Serve the inactivity hub. |
| `hub.base-path` | `/session` | |
| `hub.heartbeat-interval` | `60s` | Echoed to the client. |
| `hub.status-poll-interval` | `30s` | Echoed to the client. |
| `hub.warning-before` | `60s` | Echoed to the client. |
| `hub.login-url` | — | Echoed to the client. |

Typical production configuration:

```properties
sentret.ttl=4h
sentret.cors-allowed-origins=https://app.example.com
```

## Inactivity hub (optional)

| Endpoint | Auth | |
|---|---|---|
| `GET {base-path}/status` | permit-all | Remaining absolute/idle time + client config. Never counts as activity. |
| `POST {base-path}/heartbeat` | session | The only activity signal: one `UPDATE`. |
| `POST {base-path}/renew` | session | Resets both deadlines and rewrites the cookie. |

## How it works

- The filter reads the cookie and validates the session with one `SELECT`; the principal carries
  both deadlines, so the hub never reads the row twice.
- Only the heartbeat writes `last_accessed_at`. Polls and your own API calls never extend a session.
- Expired rows are purged on login (indexed `DELETE`); there is no scheduler.
- Unauthenticated requests get `401` with no body.

## Migrating from spring-session-lite

See [MIGRATION.md](MIGRATION.md).

## License

MIT
````

- [ ] **Step 5: Escrever a migração e o changelog**

No topo de `MIGRATION.md` (antes do conteúdo atual), inserir:

````markdown
# Migração — spring-session-lite 3.x → Sentret 1.0.0

## 1. Dependência

```xml
<artifactId>sentret-session</artifactId>
<version>1.0.0</version>
```

A app precisa de `JdbcTemplate` (`spring-boot-starter-jdbc` ou `spring-boot-starter-data-jpa`).

## 2. Banco

Criar a tabela nova com `db/sentret-schema.sql` (ver README). A antiga pode ser apagada
(`DROP TABLE spring_session_lite_sessions`): todo usuário faz login de novo uma vez.

## 3. Propriedades

| Antes (`spring-session-lite.*`) | Agora (`sentret.*`) |
|---|---|
| `ttl`, `max-idle`, `cookie-name`, `cookie-secure`, `cookie-same-site`, `cookie-domain`, `csrf-enabled`, `cors-allowed-origins`, `permit-all-paths`, `enabled` | mesmo nome |
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
| `max-idle` padrão `0` | padrão agora é `30m` |

## 4. Código

| Antes | Agora |
|---|---|
| `io.github.sidneyroberto9.spring_session_lite.*` / `SpringSessionLite*` | `io.github.sidneyroberto9.sentret.*` / `Sentret*` |
| `login(userId, email, request, response)` / `login(userId, email, roles, request, response)` | `login(userId, email, response)` |
| `SpringSessionLiteUser.roles()` | removido: busque as roles na sua app pelo `userId` |
| `@SpringSessionLiteCurrentSession SpringSessionLiteUser user` | `@AuthenticationPrincipal SentretUser user` |
| `validate(sessionId, request)` | `validate(sessionId)` |
| `deleteExpired()`, `remaining(...)` | removidos |
| `SessionRenewedEvent(userId, sessionId, renewedAt, absoluteRemainingMs, idleRemainingMs)` | `SentretSessionRenewedEvent(userId, sessionId, renewedAt)` |
| `SessionDestroyedEvent(sessionId)` | `SentretSessionDestroyedEvent(userId, sessionId)` |
| `SpringSessionLiteSessionStore` (`save`, …) | `SentretSessionStore` (`insert`, `updateLastAccessedAt`, `updateExpiresAt`, …) |

## 5. Comportamento

- **Cookie padrão:** `SLSID` → `SENTRETSID` (quem já define `cookie-name` não é afetado).
- **Agendamento:** a lib não liga mais `@EnableScheduling`. Se sua app tem `@Scheduled` próprios e
  nunca declarou `@EnableScheduling`, eles param — adicione `@EnableScheduling` na sua app.
- **401:** sem corpo (antes `{"error":"unauthorized",...}`).
- **Troca de IP** não derruba mais a sessão.
- **Hub:** `POST /logout` não existe mais (o client nunca usou). O `config` do status traz só
  `heartbeatIntervalMs`, `statusPollIntervalMs`, `warningBeforeMs` e `loginUrl`.
- **`renew`** não ressuscita sessão expirada (nem por inatividade).

---

````

No topo de `CHANGELOG.md` (logo após o cabeçalho e antes de `## [3.0.0]`), inserir:

```markdown
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
- Compatível com Spring Boot 3.5 e 4.x (profile `boot4`).

### Removido
- Vínculo com IP (`ip-hash-salt`, `trust-forwarded-for`, `trusted-proxy-count`).
- Roles na sessão.
- `NanoId`, task agendada de limpeza e `@EnableScheduling`.
- Endpoint `POST /session/logout` e os campos `ttlMs`, `maxIdleMs`, `logoutUrl`, `redirectAfterExpiryUrl` do status.
- `@SpringSessionLiteCurrentSession` (use `@AuthenticationPrincipal`).
- Propriedades `cookie-prefix`, `cookie-path`, `session-id-length`, `update-last-accessed`, `sliding-expiration`, `last-accessed-throttle`, `cors-enabled`, `cors-allowed-methods`, `cors-allow-credentials`, `cleanup-enabled`, `cleanup-cron`.
```

- [ ] **Step 6: Atualizar os guias `docs/01`–`docs/07`**

Invocar a skill `post-docs` com o pedido abaixo, e conferir cada item ao final:
1. Todos: `spring-session-lite` → `sentret` (prefixo), `SpringSessionLite*` → `Sentret*`, artifactId `sentret-session`, cookie `SENTRETSID`.
2. `01-instalacao-e-uso.md`: exemplo de uso igual ao Quickstart do README; DDL de `sentret_sessions`; requisito de `JdbcTemplate`.
3. `02-configuracao-application-properties.md`: tabela de propriedades igual à do README; seção "removidas" igual à tabela do `MIGRATION.md`.
4. `03-como-funciona.md`: armazenamento JDBC (sem JPA), validação com um `SELECT`, heartbeat com um `UPDATE`, limpeza no login, 401 sem corpo; apagar as seções de IP, roles, NanoId, task de limpeza e throttle.
5. `04-publicar-no-maven-central.md` e `05-atualizar-e-republicar.md`: artifactId novo; build com `./mvnw verify` e `./mvnw verify -Pboot4`.
6. `06-sessao-centralizada-multissistema.md`: contrato com 3 endpoints (sem logout), `config` com 4 campos, propriedades `sentret.hub.*`; corrigir a frase "qualquer requisição autenticada já toca `last_accessed_at`" (só o heartbeat toca).
7. `07-proposta-refatoracao.md`: acrescentar no topo `> Status: implementado na 1.0.0 — ver docs/superpowers/plans/2026-10-04-sentret-refatoracao.md.`

Run: `grep -rnE "spring-session-lite\.|SpringSessionLite|SLSID|ip-hash-salt|endpoints-enabled" README.md docs/0[1-6]*.md`
Expected: nenhuma saída (o `MIGRATION.md` e o `CHANGELOG.md` citam os nomes antigos de propósito).

- [ ] **Step 7: Verificação final**

Run: `./mvnw verify -Dgpg.skip && ./mvnw verify -Pboot4 -Dgpg.skip`
Expected: dois `BUILD SUCCESS`, `Failures: 0, Errors: 0`.

Run: `find src/main/java -name '*.java' | wc -l`
Expected: `19` — `SentretAutoConfiguration`, `SentretHubAutoConfiguration`, `SentretProperties`, `SentretSecurityValidator`, 3 eventos, `SentretAuthenticationFilter`, `SentretUser`, `SentretService`, `SentretUserService`, `SentretCookieManager`, `SentretSession`, `SentretSessionStore`, `JdbcSentretSessionStore`, `SentretHubController`, `SentretHubStatusService`, `SessionStatusResponse`, `SessionConfigResponse` (antes: 25).

Invocar a skill `spring-clean` sobre `src/main/java` e corrigir o que apontar.

- [ ] **Step 8: Commit**

Invocar `auto-commit`. Sugestões: `refactor!: inject the principal with @AuthenticationPrincipal`, `docs: document Sentret 1.0.0`.

- [ ] **Step 9: Revisão de arquitetura da branch inteira (#16)**

Invocar `superpowers:requesting-code-review` sobre `master..refactor/sentret`, pedindo checagem explícita dos itens do #16: acoplamento (sem JPA, sem `@EnableScheduling`, sem Jackson no código principal), configuração (10 + 6 do hub, nenhuma obrigatória), API pública (`SentretService`, `SentretUserService`, `SentretUser`, 3 eventos, `SentretSessionStore`, 3 endpoints), Boot 3 e 4, separação Controller/Service/Config/Properties/DTO, nativo antes de utilitário próprio. Corrigir o que for confirmado.

---

## Fora do escopo (acompanhar à parte)

- **Client npm `@media4all/session-lite`** (repo `spring-session-lite-client`): poll adaptativo (#5.4 do doc), tipos sem `ttlMs`/`maxIdleMs`/`logoutUrl`/`redirectAfterExpiryUrl`/`roles`.
- **Consumidores** (eleva-docs, eleva-messaging, eleva-management, eleva-helpdesk): trocar dependência e propriedades conforme `MIGRATION.md`, criar `sentret_sessions`, ajustar chamadas de `login(...)`; no eleva-docs, `sentret.hub.*` com `base-path=/api/lite/session` e `login-url`.
- **Repositório no GitHub**: renomear e atualizar `<url>`/`<scm>` do `pom.xml`.
- **Publicação** no Maven Central do novo artefato (`docs/04`).
