# akpedia-server

Repositório destinado ao backend do projeto **Akpedia**, construído com **Java 17 + Spring Boot 3.3** e **PostgreSQL**.

Migrações de banco são versionadas com **Flyway**.

---

## Requisitos

- **Só Docker** (recomendado): [Docker](https://www.docker.com/) + Docker Compose.
- **Desenvolvimento local**: JDK 17. O Maven não precisa estar instalado (usar o `./mvnw` (Maven Wrapper))

---

## Rodando com Docker (qualquer máquina)

Subir o Postgres e a aplicação juntos:

```bash
docker compose up --build
```

- API disponível em `http://localhost:8080`
- Postgres exposto em `localhost:5432` (db `akpedia`, user/senha `akpedia`)

Para parar: `docker compose down` (ou `docker compose down -v` para apagar os dados do banco).

As credenciais devem ser alteradas copiando `.env.example` para `.env`.


### Endpoints de verificação

- `GET http://localhost:8080/health` deve retornar `{"status":"UP"}`


---

## Estrutura

```bash
akpedia-server/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── .dockerignore
├── .gitignore
├── .env.example
├── mvnw / mvnw.cmd / .mvn/wrapper/ # Maven Wrapper
├── README.md
└── src/
    ├── main/
    │   ├── java/com/akpedia/server/
    │   │   ├── AkpediaServerApplication.java # classe main @SpringBootApplication
    │   │   └── controller/HealthController.java # endpoint /health simples
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/V1__init.sql # migração inicial Flyway
    └── test/java/com/akpedia/server/ # testes
        └── AkpediaServerApplicationTests.java 
```

---

## Configuração

A aplicação lê as configurações de conexão a partir de variáveis de ambiente (com defaults para dev):

| Variável       | Default                                         |
|----------------|-------------------------------------------------|
| `DB_URL`       | `jdbc:postgresql://localhost:5432/akpedia`      |
| `DB_USER`      | `akpedia`                                        |
| `DB_PASSWORD`  | `akpedia`                                        |
| `SERVER_PORT`  | `8080`                                           |

---

## Comandos úteis

```bash
./mvnw clean package # gera o .jar em target/
./mvnw test # roda os testes (requer Postgres ativo)
```

> No Linux/macOS use `./mvnw`; no Windows use `mvnw.cmd`.

---

## Rodando testes e lint localmente

Antes de abrir um PR, rode localmente as mesmas checagens do CI.

### Testes de qualidade

Os testes sobem o contexto Spring + Flyway, então **precisam de um Postgres ativo**. O jeito mais simples é subir só o banco via Docker:

```bash
docker compose up -d db # sobe apenas o Postgres
./mvnw test # roda os testes
```

### Lint (Checkstyle)

O lint usa **Checkstyle** com as regras em `config/checkstyle/checkstyle.xml`:

```bash
./mvnw org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check \
  -Dcheckstyle.config.location=config/checkstyle/checkstyle.xml
```

> O Checkstyle apenas **aponta** as violações (não corrige automaticamente). Ajuste o código conforme o relatório ou use o "Reformat/Optimize imports" da IDE.

---

## Padrões de contribuição (CI)

Todo Pull Request com destino à branch `develop` passa por um pipeline no GitHub Actions
(`.github/workflows/ci.yml`). O merge só é liberado após o pipeline passar **e** a aprovação de outro membro da equipe.

### Nome da branch

Deve seguir o padrão `AKP-<número>`:

```text
AKP-12
```

### Mensagens de commit

Conventional Commits com o escopo do ticket — `type(AKP-<número>): descrição`:

```text
feat(AKP-12): adiciona endpoint de login
fix(AKP-15): corrige validação de e-mail
```

Tipos aceitos: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `style`, `perf`, `build`, `ci`, `revert`.

### O que o pipeline verifica

| Etapa | O que faz |
|-------|-----------|
| **Padrão de branch & commits** | Valida o nome da branch (`AKP-<número>`) e o padrão dos commits. Roda sempre. |
| **Lint Java (Checkstyle)** | Roda o Checkstyle. Executa apenas quando há mudanças em `src/**`. |
| **Testes de qualidade** | Roda `./mvnw test` contra um Postgres real. Executa apenas quando há mudanças em `src/**`. |
| **Solicitar aprovação da equipe** | Após tudo passar, solicita a revisão de um membro da equipe. |

> Commits que só alteram configuração/estrutura (fora de `src/**`) não disparam lint nem testes de qualidade.

### Abrindo o PR

A descrição do PR é pré-preenchida pelo template em `.github/PULL_REQUEST_TEMPLATE.md`.
Preencha os campos e marque o checklist antes de solicitar revisão.
