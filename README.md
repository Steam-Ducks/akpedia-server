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
