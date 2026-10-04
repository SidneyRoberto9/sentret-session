# Sentret — Publicar no Maven Central

Guia completo para publicar a biblioteca no **Maven Central** pela primeira vez,
usando o **Central Portal** da Sonatype (`central.sonatype.com`) — o fluxo novo,
que substitui o antigo OSSRH (`oss.sonatype.org`).

---

## 1. Visão geral

A publicação tem três peças que já estão configuradas neste projeto:

| Peça | Onde | Função |
|------|------|--------|
| `central-publishing-maven-plugin` | `pom.xml` | Envia os artefatos para o Central Portal |
| `maven-gpg-plugin` | `pom.xml` | Assina cada artefato com GPG (exigência do Central) |
| `maven-source-plugin` + `maven-javadoc-plugin` | `pom.xml` | Geram os `-sources.jar` e `-javadoc.jar` (obrigatórios) |
| Servidor `central` | `~/.m2/settings.xml` | Credenciais (token) do Central Portal |
| Chave GPG | chaveiro local | Par de chaves usado na assinatura |

O Central Portal **exige**, para todo artefato publicado:

- JAR principal + `-sources.jar` + `-javadoc.jar`
- Assinaturas `.asc` (GPG) de **todos** os arquivos acima + do `.pom`
- POM com `name`, `description`, `url`, `licenses`, `developers` e `scm` — já presentes
- `groupId` cujo namespace você **comprovou ser dono** no Central Portal

---

## 2. Pré-requisitos (feitos uma única vez)

### 2.1. Conta e namespace no Central Portal

1. Crie conta em **https://central.sonatype.com** (pode entrar com o GitHub).
2. O `groupId` é `io.github.sidneyroberto9`. Para namespaces `io.github.<user>`,
   a verificação é automática: o Central confirma que você é dono da conta
   GitHub `sidneyroberto9`. Confirme em **Namespaces** que ele aparece como
   _Verified_.

> Se um dia usar um domínio próprio (ex.: `com.media4all`), o Central pede um
> registro TXT no DNS para comprovar a posse.

### 2.2. Token de publicação

1. No Central Portal: **Account → Generate User Token**.
2. Ele gera um par `username` / `password` (um token, **não** sua senha de login).
3. Esses valores vão para o servidor `central` no `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>SEU_TOKEN_USERNAME</username>
      <password>SEU_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

> O `id` **tem que ser** `central` — é o `publishingServerId` declarado no
> `central-publishing-maven-plugin` do `pom.xml`.

✅ **Neste ambiente já está configurado.**

### 2.3. Chave GPG

O Central só aceita artefatos assinados, e a **chave pública** precisa estar em
um keyserver público para a validação.

Verifique se já existe uma chave:

```bash
gpg --list-secret-keys --keyid-format=long
```

✅ **Neste ambiente já existe:** `rsa4096/C4D3857BBE0E26A8`
(`Sidney Roberto <sidneyrpsilva@gmail.com>`).

Se precisar criar do zero:

```bash
gpg --full-generate-key      # RSA 4096, sem expiração ou prazo longo
```

**Publique a chave pública** em um keyserver (faça em mais de um por garantia):

```bash
gpg --keyserver keyserver.ubuntu.com  --send-keys C4D3857BBE0E26A8
gpg --keyserver keys.openpgp.org      --send-keys C4D3857BBE0E26A8
```

> Troque `C4D3857BBE0E26A8` pelo ID da sua chave caso seja diferente.
> Sem isso, o Central rejeita o deploy com erro de assinatura inválida.

---

## 3. Antes de publicar — checklist

```bash
cd /home/sid/www/personal/spring-lite-session
```

1. **Versão definida** no `pom.xml`. Para um release a versão **não** pode ser
   `-SNAPSHOT` (o Central recusa snapshots). Hoje está `2.0.0`:

   ```xml
   <version>2.0.0</version>
   ```

2. **Testes verdes:**

   ```bash
   rtk mvn clean test
   ```

3. **Build completo gera os 3 jars + assinaturas** sem subir nada:

   ```bash
   rtk mvn clean verify
   rtk mvn clean verify -Pboot4   # mesmo build contra o Spring Boot 4 (o clean é obrigatório ao trocar de profile)
   ```

   Confira em `target/` que existem:
   - `sentret-session-1.0.0.jar`
   - `sentret-session-1.0.0-sources.jar`
   - `sentret-session-1.0.0-javadoc.jar`
   - um `.asc` para cada um deles e para o `.pom`

   > A assinatura roda na fase `verify`. Se pedir a _passphrase_ da chave GPG e
   > travar, use o modo loopback (já configurado no plugin) e, se necessário,
   > exporte `GPG_TTY`:
   > ```bash
   > export GPG_TTY=$(tty)
   > ```

---

## 4. Publicar

```bash
rtk mvn clean deploy
```

O que acontece:

1. Compila, testa, gera sources + javadoc.
2. Assina tudo com GPG (fase `verify`).
3. O `central-publishing-maven-plugin` empacota e **envia** o _deployment_ para
   o Central Portal.
4. O Central roda a **validação** automática (estrutura, assinaturas, POM).

> ⚠️ **`autoPublish` está `false`** no `pom.xml`. Isso é proposital: o deploy
> sobe para o Portal como um _deployment_ em estado **VALIDATED**, mas **não**
> vai para o Maven Central até você confirmar manualmente. É a rede de
> segurança para conferir antes de soltar (uma vez publicado, **não dá para
> apagar** uma versão do Central).

### 4.1. Confirmar a publicação (passo manual)

1. Acesse **https://central.sonatype.com → Deployments**.
2. Encontre o deployment recém-enviado. Estados possíveis:
   - `PENDING` / `VALIDATING` — aguarde.
   - `VALIDATED` — passou em tudo, pronto para publicar. Clique em **Publish**.
   - `FAILED` — veja o motivo (assinatura, javadoc ausente, POM incompleto),
     corrija e rode `rtk mvn clean deploy` de novo.
3. Após **Publish**, o estado vai para `PUBLISHING` e depois `PUBLISHED`.

### 4.2. (Opcional) Publicar automático

Se um dia confiar no fluxo e quiser pular o passo manual, mude no `pom.xml`:

```xml
<configuration>
    <publishingServerId>central</publishingServerId>
    <autoPublish>true</autoPublish>
</configuration>
```

Aí o `deploy` já solta direto no Central quando a validação passa.

---

## 5. Depois de publicar

- A sincronização para `repo1.maven.org` / `search.maven.org` leva de
  **alguns minutos a ~30 min**, às vezes mais para indexar na busca.
- Verifique em:
  `https://repo1.maven.org/maven2/io/github/sidneyroberto9/sentret-session/1.0.0/`
- A partir daí qualquer projeto pode declarar:

  ```xml
  <dependency>
      <groupId>io.github.sidneyroberto9</groupId>
      <artifactId>sentret-session</artifactId>
      <version>1.0.0</version>
  </dependency>
  ```

---

## 6. Problemas comuns

| Sintoma | Causa provável | Solução |
|---------|----------------|---------|
| `401 Unauthorized` no deploy | Token errado ou `id` diferente de `central` | Revise o servidor `central` no `settings.xml` |
| `gpg: signing failed: Inappropriate ioctl` | Terminal sem TTY para a passphrase | `export GPG_TTY=$(tty)` e repita |
| Central acusa assinatura inválida | Chave pública não está no keyserver | `gpg --send-keys <ID>` para um keyserver público |
| `FAILED` por javadoc/sources ausente | Build não anexou os jars | Rode `mvn clean verify` e confira o `target/` |
| Central recusa `-SNAPSHOT` | Versão de snapshot | Use versão de release (sem `-SNAPSHOT`) |
| Namespace não verificado | Conta GitHub não confirmada | Verifique o namespace em **Namespaces** no Portal |

---

## 7. Resumo (TL;DR)

```bash
# uma vez: settings.xml com server 'central' + chave GPG no keyserver (já feito)

cd /home/sid/www/personal/spring-lite-session
rtk mvn clean test            # testes verdes
rtk mvn clean verify          # confere jars + assinaturas em target/
rtk mvn clean verify -Pboot4   # mesmo build contra o Spring Boot 4 (o clean é obrigatório ao trocar de profile)
rtk mvn clean deploy          # sobe para o Central Portal (VALIDATED)
# → central.sonatype.com → Deployments → Publish
```

Para **lançar uma nova versão** depois, veja
[`05-atualizar-e-republicar.md`](./05-atualizar-e-republicar.md).
