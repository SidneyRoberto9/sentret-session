# Spring Session Lite — Instalação e Uso

Guia do zero até um endpoint autenticado funcionando.

---

## 1. O que é

Starter do Spring Boot 3 que fornece autenticação por **sessão persistida em banco**,
substituindo JWT.

- A sessão é gravada na própria base da aplicação (usa o `spring.datasource.*` existente).
- O identificador é um **NanoID** entregue num cookie **HttpOnly** chamado `SLSID` (configurável).
- Sem Redis nem infra externa.
- A aplicação faz **uma chamada** no login; cookie, filtro, contexto de segurança e limpeza são automáticos.

---

## 2. Requisitos

| Item | Versão mínima |
|------|---------------|
| Java | 17 |
| Spring Boot | 3.x |
| Banco | Qualquer um suportado pelo JPA/Hibernate |

---

## 3. Instalação

```xml
<dependency>
    <groupId>io.github.sidneyroberto9</groupId>
    <artifactId>spring-session-lite</artifactId>
    <version>2.0.0</version>
</dependency>
```

Traz transitivamente `spring-boot-starter-data-jpa`, `-security` e `-web`. Adicione o **driver
do seu banco** (`mysql-connector-j`, `postgresql`, …).

---

## 4. Configuração mínima

Basta ter um `DataSource` configurado:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/minha_app
spring.datasource.username=root
spring.datasource.password=secret
```

A tabela `spring_session_lite_sessions` é criada automaticamente com `ddl-auto=update`. Para
`none`/`validate`, use o DDL em
[`src/main/resources/db/spring-session-lite-schema.sql`](../src/main/resources/db/spring-session-lite-schema.sql).
Demais propriedades em [`02-configuracao-application-properties.md`](./02-configuracao-application-properties.md).

---

## 5. Uso

### 5.1. Login

Depois de validar as credenciais, chame `SpringSessionLiteService.login(...)`. A lib gera a
sessão, grava no banco e escreve o cookie `SLSID`.

```java
import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final SpringSessionLiteService sessionService;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<SpringSessionLiteUser> login(
            @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {

        User user = userService.authenticate(body.getEmail(), body.getPassword());

        SpringSessionLiteUser session = sessionService.login(
                String.valueOf(user.getId()),   // userId aceita Long, UUID, etc.
                user.getEmail(),
                List.of("ADMIN"),               // roles opcionais → authority ROLE_ADMIN
                request,
                response);

        return ResponseEntity.ok(session);
    }
}
```

> Sobrecarga sem roles: `sessionService.login(userId, email, request, response)`.

### 5.2. Logout

```java
@PostMapping("/logout")
public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    sessionService.logout(request, response);   // apaga a sessão + limpa o cookie
    return ResponseEntity.noContent().build();
}
```

Para revogar **todas** as sessões de um usuário (ex.: troca de senha):
```java
sessionService.logoutAll(userId);
```

### 5.3. Sessão atual no controller

```java
import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.web.SpringSessionLiteCurrentSession;

@GetMapping("/me")
public ResponseEntity<SpringSessionLiteUser> me(@SpringSessionLiteCurrentSession SpringSessionLiteUser user) {
    return ResponseEntity.ok(user);
}
```

O `SpringSessionLiteUser` é um `record` com: `userId()`, `email()`, `sessionId()`, `roles()`.

### 5.4. Sessão atual fora do controller

```java
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteUserService;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final SpringSessionLiteUserService sessionUserService;

    public Order createForCurrentUser(OrderRequest request) {
        SpringSessionLiteUser user = sessionUserService.currentUser()
                .orElseThrow(() -> new IllegalStateException("Sem sessão autenticada"));
        return repository.save(new Order(user.userId(), request));
    }
}
```

### 5.5. Requisições subsequentes

Nada a fazer — o filtro lê o cookie, valida e popula o `SecurityContext`. Em chamadas
`fetch`/`axios` de outra origem, habilite credenciais e configure CORS
(`spring-session-lite.cors-enabled=true`):

```javascript
fetch("/me", { credentials: "include" });
```

---

## 6. Integração com Spring Security

### 6.1. Sem `SecurityFilterChain` próprio (zero-config)

A lib registra um chain opinativo que: política `STATELESS`; libera `permit-all-paths`; exige
autenticação no resto; responde **401** sem sessão válida; CSRF/CORS conforme propriedades.

### 6.2. Com `SecurityFilterChain` próprio

O chain da lib desliga (`@ConditionalOnMissingBean`). Adicione **uma linha**:

```java
import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final SpringSessionLiteAuthenticationFilter sessionAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/public/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

> **CSRF & cookie:** autenticação por cookie é sensível a CSRF. Mantenha `SameSite=Lax`/`Strict`
> (padrão) ou habilite `csrf-enabled`. Evite `SameSite=None` sem CSRF.

---

## 7. Comportamento de expiração (401)

- **Sem cookie** → segue anônimo (`/login` acessível).
- **Cookie inválido/expirado/IP divergente** → o filtro **não** bloqueia rotas `permit-all`:
  limpa o cookie morto, segue anônimo, e a autorização decide. Em rota protegida → **401**:

```json
{ "error": "unauthorized", "message": "Authentication required" }
```

Assim, um cookie expirado **nunca** trava o re-login.

---

## 8. Limpeza automática

Task `@Scheduled` (padrão: a cada 30 min) remove sessões expiradas. Ajuste com `cleanup-cron`
ou desligue com `cleanup-enabled=false`.

---

## 9. Próximos passos

- [Configuração](./02-configuracao-application-properties.md)
- [Como funciona por dentro](./03-como-funciona.md)
