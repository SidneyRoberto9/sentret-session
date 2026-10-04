# Sentret — Atualizar a Lib e Republicar

Fluxo para lançar uma **nova versão** da biblioteca depois que a primeira já
está no Maven Central. Pressupõe que a configuração do
[`04-publicar-no-maven-central.md`](./04-publicar-no-maven-central.md)
(token no `settings.xml` + chave GPG no keyserver) já está pronta.

---

## 1. Regra de ouro do Maven Central

**Versão publicada é imutável.** Não dá para sobrescrever nem apagar
`1.0.0` depois de publicada. Toda mudança que vai para o mundo **precisa de uma
versão nova**. Por isso o ciclo é sempre: _altera o código → sobe a versão →
publica de novo_.

---

## 2. Versionamento (SemVer)

A versão segue `MAJOR.MINOR.PATCH`:

| Mudança | O que muda | Exemplo |
|---------|-----------|---------|
| **PATCH** | Correção de bug, sem mexer na API pública | `1.0.0 → 1.0.1` |
| **MINOR** | Recurso novo, **retrocompatível** | `1.0.0 → 1.1.0` |
| **MAJOR** | Quebra de compatibilidade (renomear/remover API pública) | `1.0.0 → 2.0.0` |

> Exemplo concreto: o rename para o prefixo `SpringSessionLite*`, a troca do cookie
> `M4SID → SLSID`, o rename da tabela e o `SpringSessionLiteUser` virar `record` **quebram**
> quem já usava `1.0.x` (publicada no Central). Por isso foi lançada como **`2.0.0`** (MAJOR),
> com guia de migração em [`../MIGRATION.md`](../MIGRATION.md).
>
> Já a renomeação para **Sentret** trocou o `artifactId` (`spring-session-lite` → `sentret-session`):
> é um artefato novo, que recomeça em **`1.0.0`**. Quem usa o antigo só migra trocando a dependência
> (ver [`../MIGRATION.md`](../MIGRATION.md)).

---

## 3. Passo a passo

```bash
cd /home/sid/www/personal/spring-lite-session
```

### 3.1. Faça e valide as alterações de código

```bash
rtk mvn clean test
```

### 3.2. Suba a versão no `pom.xml`

Edite o campo `<version>`:

```xml
<!-- antes -->
<version>1.0.0</version>

<!-- depois (ex.: correção de bug) -->
<version>1.0.1</version>
```

Ou faça pela linha de comando (atualiza o pom sozinho):

```bash
rtk mvn versions:set -DnewVersion=1.0.1
rtk mvn versions:commit      # remove o pom.xml.versionsBackup
```

> **Durante o desenvolvimento** entre releases, é boa prática manter a versão
> como snapshot (ex.: `1.1.0-SNAPSHOT`) e só remover o `-SNAPSHOT` na hora de
> publicar — o Central recusa snapshots, então a versão **final do deploy nunca
> pode** terminar em `-SNAPSHOT`.

### 3.3. Atualize a documentação e o histórico

- Ajuste exemplos/versões nos docs (`docs/01`…`03`) se a API mudou.
- Registre o que mudou (idealmente um `CHANGELOG.md`):

  ```markdown
  ## [1.0.1] - 2026-06-11
  ### Corrigido
  - <descrição da correção>
  ```

### 3.4. Confira o build assinado

```bash
rtk mvn clean verify
rtk mvn clean verify -Pboot4   # mesmo build contra o Spring Boot 4 (o clean é obrigatório ao trocar de profile)
```

Confirme em `target/` os três jars da **nova** versão + os `.asc`:

- `sentret-session-1.0.1.jar`
- `sentret-session-1.0.1-sources.jar`
- `sentret-session-1.0.1-javadoc.jar`

### 3.5. Publique

```bash
rtk mvn clean deploy
```

Sobe para o Central Portal no estado `VALIDATED` (lembre: `autoPublish=false`).

### 3.6. Confirme no Portal

**https://central.sonatype.com → Deployments → Publish** na nova versão.

### 3.7. Marque a versão no Git

```bash
rtk git add -A
rtk git commit -m "chore(release): bump version to 1.0.1"
rtk git tag -a sentret-v1.0.1 -m "sentret-session 1.0.1"
rtk git push origin master --tags
```

> Use o prefixo `sentret-v` nas tags: `v1.0.0` e `v1.0.1` já existem neste repositório e são do
> artefato antigo (`spring-session-lite`).

---

## 4. Atualizar nas aplicações que consomem a lib

Nos `pom.xml` dos apps (eleva-*, etc.), aponte para a nova versão:

```xml
<dependency>
    <groupId>io.github.sidneyroberto9</groupId>
    <artifactId>sentret-session</artifactId>
    <version>1.0.1</version>
</dependency>
```

Depois force a resolução da nova versão:

```bash
rtk mvn -U clean verify     # -U = força checar atualizações no remoto
```

> Se a nova versão ainda não indexou no Central (leva alguns minutos), o `-U`
> pode falhar — espere a sincronização e tente de novo.

### 4.1. Testar localmente antes de publicar (opcional)

Para validar a mudança em um app consumidor **sem** subir ao Central, instale a
versão (mesmo snapshot) no `~/.m2` local:

```bash
rtk mvn clean install         # vai para ~/.m2/repository
```

O app que depende dela, rodando na mesma máquina, já pega o artefato local.
Quando estiver tudo certo, aí sim faz o `deploy` para o Central.

---

## 5. Checklist rápido de release

- [ ] Testes verdes (`mvn clean test`)
- [ ] Versão nova no `pom.xml` (sem `-SNAPSHOT`, seguindo SemVer)
- [ ] Docs/exemplos e `CHANGELOG.md` atualizados
- [ ] `mvn clean verify` gera os 3 jars + `.asc`
- [ ] `mvn clean deploy` → estado `VALIDATED` no Portal
- [ ] **Publish** no Central Portal
- [ ] Tag Git `vX.Y.Z` criada e enviada
- [ ] Apps consumidores apontando para a versão nova

---

## 6. Resumo (TL;DR)

```bash
cd /home/sid/www/personal/spring-lite-session
rtk mvn clean test
rtk mvn versions:set -DnewVersion=1.0.1 && rtk mvn versions:commit
rtk mvn clean verify
rtk mvn clean verify -Pboot4   # mesmo build contra o Spring Boot 4 (o clean é obrigatório ao trocar de profile)
rtk mvn clean deploy
# → central.sonatype.com → Deployments → Publish
rtk git tag -a v1.0.1 -m "v1.0.1" && rtk git push origin main --tags
```
