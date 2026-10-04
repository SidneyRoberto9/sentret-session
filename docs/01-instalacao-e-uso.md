# Sentret — Instalação e Uso

Guia rápido para colocar a biblioteca para funcionar numa aplicação Spring Boot.

---

## 1. O que é

Autenticação por sessão com cookie, leve, para Spring Boot 3 e 4:

- O navegador carrega só um cookie opaco `HttpOnly` (`SENTRETSID`).
- A sessão fica no banco da **própria aplicação**, via `JdbcTemplate` (SQL puro, sem JPA).
- Um filtro valida o cookie a cada request e publica um `SentretUser` como principal do Spring
  Security.
- Opcionalmente, um **hub de inatividade** (`sentret.hub.*`) expõe os endpoints usados pelo client
  npm `@media4all/session-lite` (ver [06](./06-sessao-centralizada-multissistema.md)).

---

## 2. Requisitos

- Java 17+
- Spring Boot 3.5+ ou 4.x
- Um `DataSource` com `JdbcTemplate` — `spring-boot-starter-jdbc` ou `spring-boot-starter-data-jpa`
  (que já inclui o JDBC)
- Spring Security e Spring Web MVC (já vêm como dependências da lib)

---

## 3. Instalação

```xml
<dependency>
    <groupId>io.github.sidneyroberto9</groupId>
    <artifactId>sentret-session</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3.1. Tabela

Rode uma vez (Flyway, Liquibase ou à mão). O mesmo SQL serve MySQL, MariaDB, PostgreSQL,
SQL Server e H2, e vai dentro do jar em `db/sentret-schema.sql`:

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

Os tempos são epoch em milissegundos (`BIGINT`): sem conversão de fuso horário e sem o limite de
2038.

---

## 4. Configuração mínima

Nenhuma propriedade é obrigatória. Em produção, o comum é só:

```properties
sentret.ttl=4h
sentret.cors-allowed-origins=https://app.meusite.com
```

Em desenvolvimento local, sobre HTTP: `sentret.cookie-secure=false`. Lista completa em
[02](./02-configuracao-application-properties.md).

---

## 5. Uso

### 5.1. Login e logout

A lib não autentica credenciais: a sua aplicação faz isso e depois abre a sessão.

```java
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final SentretService sentret;

    @PostMapping("/auth/login")
    public ResponseEntity<SentretUser> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        String userId = credentials.check(body); // autenticação da sua aplicação
        SentretUser user = sentret.login(userId, body.email(), response);
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sentret.logout(request, response);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
```

- `login(...)` grava a sessão, escreve o cookie e publica `SentretSessionCreatedEvent`. Antes,
  apaga as sessões já expiradas.
- `logout(request, response)` apaga a sessão do cookie, limpa o cookie e publica
  `SentretSessionDestroyedEvent`.
- `logoutAll(userId)` derruba todas as sessões de um usuário.

### 5.2. Usuário atual no controller

```java
@GetMapping("/me")
public ResponseEntity<SentretUser> me(@AuthenticationPrincipal SentretUser user) {
    return ResponseEntity.status(HttpStatus.OK).body(user);
}
```

`SentretUser` traz `userId`, `email`, `sessionId`, `expiresAt` e `lastAccessedAt`. **Roles e
permissões ficam na sua aplicação**, buscadas pelo `userId`.

### 5.3. Usuário atual fora do controller

```java
private final SentretUserService userService;

public void algumMetodo() {
    userService.currentUser().ifPresent(user -> log.info("usuário {}", user.userId()));
}
```

### 5.4. Requisições seguintes

O navegador manda o cookie sozinho. Em chamadas cross-origin, o front precisa enviar credenciais
(`credentials: 'include'` / `withCredentials: true`) e a API precisa ter a origem em
`sentret.cors-allowed-origins`.

---

## 6. Integração com Spring Security

### 6.1. Sem `SecurityFilterChain` próprio

A lib registra uma cadeia padrão: `STATELESS`; libera `permit-all-paths`; exige autenticação no
resto; responde **401 sem corpo** sem sessão válida; CSRF conforme `csrf-enabled`; CORS ligado
quando `cors-allowed-origins` não está vazio.

### 6.2. Com `SecurityFilterChain` próprio

A cadeia da lib não é criada (`@ConditionalOnMissingBean`). Adicione o filtro na sua:

```java
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final SentretAuthenticationFilter sentretAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/public/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(sentretAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

> **CSRF e cookie:** autenticação por cookie é sensível a CSRF. Mantenha `SameSite=Lax`/`Strict`
> (padrão) ou ligue `csrf-enabled`. Evite `SameSite=None` sem CSRF — a lib avisa no startup.

---

## 7. Expiração e 401

- **Sem cookie** → segue anônimo (o login continua acessível).
- **Cookie inválido, expirado ou inativo há mais de `max-idle`** → o filtro **não** bloqueia rotas
  `permit-all`: apaga o cookie morto, segue anônimo e a autorização decide. Em rota protegida →
  **401 sem corpo**.

Assim, um cookie expirado nunca trava o re-login.

---

## 8. Limpeza das sessões expiradas

Não há tarefa agendada: cada `login` apaga as sessões já expiradas (um `DELETE` pelo índice de
`expires_at`). A lib também **não** liga `@EnableScheduling` na sua aplicação.

---

## 9. Próximos passos

- [Configuração](./02-configuracao-application-properties.md)
- [Como funciona por dentro](./03-como-funciona.md)
- [Hub de inatividade entre aplicações](./06-sessao-centralizada-multissistema.md)
