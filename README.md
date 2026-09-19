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
- **Swagger UI**: <http://localhost:8080/swagger-ui.html> (OpenAPI cru em `/v3/api-docs`)

### Subindo tudo (server + ml)

O akpedia-ml sobe pelo compose do **próprio repositório**. Os dois containers se falam pela
porta publicada no host, então a ordem não importa — só que o ml esteja no Docker, e não em
um `uvicorn` escutando apenas `127.0.0.1` (aí o container do server não o alcança).

```bash
# no repositório akpedia-ml
docker compose up -d --build       # primeira vez baixa o modelo de embeddings (~alguns minutos)
curl localhost:8000/health         # espere {"status":"UP"} antes de seguir

# aqui, no akpedia-server
docker compose up -d --build
curl localhost:8080/health
```


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
    │   │   ├── client/ # clientes HTTP de serviços externos
    │   │   │   └── EmbeddingClient.java
    │   │   ├── config/ # beans de configuração e @ConfigurationProperties
    │   │   │   ├── EmbeddingClientConfig.java
    │   │   │   ├── EmbeddingProperties.java
    │   │   │   └── OpenApiConfig.java # título/descrição do Swagger
    │   │   ├── controller/
    │   │   │   ├── EmbeddingController.java # rotas de embedding
    │   │   │   └── HealthController.java # endpoint /health simples
    │   │   ├── dto/ # objetos de transporte
    │   │   │   ├── DocumentEmbeddingResponse.java
    │   │   │   ├── DocumentChunk.java
    │   │   │   ├── QueryEmbeddingRequest.java
    │   │   │   ├── QueryEmbeddingResponse.java
    │   │   │   ├── EmbeddingModelInfo.java
    │   │   │   ├── EmbeddingErrorResponse.java
    │   │   │   └── ApiErrorResponse.java
    │   │   └── exception/
    │   │       ├── ApiExceptionHandler.java # traduz falhas em status HTTP
    │   │       ├── EmbeddingException.java
    │   │       ├── EmbeddingUnavailableException.java
    │   │       └── EmbeddingRejectedException.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/V1__init.sql # migração inicial Flyway
    └── test/java/com/akpedia/server/ # testes
        ├── AkpediaServerApplicationTests.java
        ├── controller/
        │   └── EmbeddingControllerTest.java # tradução de falhas em status HTTP
        └── client/
            ├── EmbeddingClientTest.java # serviço mockado: contrato e erros
            ├── EmbeddingClientTimeoutTest.java # timeouts reais, sem mock de transporte
            └── EmbeddingClientManualIT.java # teste manual contra um ml de verdade
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

### Serviço de embeddings

| Variável                     | Default                  | Para que serve                    |
|------------------------------|--------------------------|-----------------------------------|
| `EMBEDDING_BASE_URL`         | `http://localhost:8000`  | URL base do serviço               |
| `EMBEDDING_CONNECT_TIMEOUT`  | `2s`                     | Tempo máximo para abrir a conexão |
| `EMBEDDING_READ_TIMEOUT`     | `30s`                    | Tempo máximo para a resposta      |

Quem implementa esse serviço hoje é o **akpedia-ml**. No `docker-compose.yml` o default aponta
para `http://host.docker.internal:8000`, porque ele sobe pelo compose do próprio repositório,
em outra rede Docker.

> Se o akpedia-ml estiver rodando direto na máquina (`uvicorn`) e escutando só em `127.0.0.1`,
> o container não o alcança. Suba-o pelo Docker ou publique-o em `0.0.0.0`.

---

## Embeddings

### Endpoints

Por enquanto as rotas repassam a resposta do serviço inteira — nada é persistido ainda.

O jeito mais rápido de experimentar é o **Swagger UI** em <http://localhost:8080/swagger-ui.html>:
o `POST /api/v1/embeddings/query` já vem com um texto de exemplo preenchido, e o
`POST /api/v1/documents/embed` mostra um seletor de arquivo. Use o botão **Try it out**.
Para testar sem navegador, os mesmos `curl`:

| Rota                            | Corpo                                  | Resposta                          |
|---------------------------------|----------------------------------------|-----------------------------------|
| `POST /api/v1/documents/embed`  | multipart, campo `file`                | chunks do documento, com um vetor cada |
| `POST /api/v1/embeddings/query` | JSON `{"text": "..."}`                 | vetor do texto buscado            |

```bash
# vetor de uma busca
curl -X POST localhost:8080/api/v1/embeddings/query \
  -H 'Content-Type: application/json' \
  -d '{"text":"qual e o prazo de garantia do equipamento?"}'

# documento em chunks, com vetores
curl -X POST localhost:8080/api/v1/documents/embed \
  -F "file=@src/test/resources/manual-sample.pdf;type=application/pdf"
```

#### Status de erro

| Situação                                  | Status | `code`                           |
|-------------------------------------------|--------|----------------------------------|
| texto em branco (barrado aqui)            | 400    | `invalid_request`                |
| arquivo acima de 25 MB (barrado aqui)     | 413    | `file_too_large`                 |
| formato não suportado pelo ml             | 415    | `unsupported_format`             |
| arquivo ilegível ou sem texto             | 422    | `unreadable_document`, ...       |
| **ml fora do ar ou além do timeout**      | 503    | `embedding_service_unavailable`  |
| ml respondeu 5xx ou algo ilegível         | 502    | `embedding_service_error`        |

A recusa do ml chega ao chamador com o `code` original, em vez de virar um 500 genérico:

```console
$ curl -i -X POST localhost:8080/api/v1/documents/embed -F "file=@planilha.xyz"
HTTP/1.1 415
{"code":"unsupported_format","message":"Unsupported format for 'planilha.xyz'. Supported: .pdf.","supported_extensions":[".pdf"]}

$ docker compose stop        # no repositório akpedia-ml
$ curl -i -X POST localhost:8080/api/v1/embeddings/query \
    -H 'Content-Type: application/json' -d '{"text":"prazo de garantia"}'
HTTP/1.1 503
{"code":"embedding_service_unavailable","message":"The embedding service is unreachable at
http://host.docker.internal:8000/api/v1/embeddings/query, so the embedding of the search text
did not happen (connect timeout 2s, read timeout 30s). Check that the service is running and
reachable."}
```

### Por dentro

O [`EmbeddingClient`](src/main/java/com/akpedia/server/client/EmbeddingClient.java) é a única
porta de saída para o serviço de embeddings:

| Método                                                              | O que faz                                          |
|---------------------------------------------------------------------|----------------------------------------------------|
| `embedDocument(byte[] content, String filename, String contentType)` | extrai, divide em chunks e gera um vetor por chunk |
| `embedQuery(String text)`                                           | gera o vetor do texto buscado                      |

```java
@Service
public class IndexingService {

    private final EmbeddingClient embeddings;

    public IndexingService(EmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    public void index(byte[] pdf) {
        try {
            DocumentEmbeddingResponse processed = embeddings.embedDocument(pdf, "manual.pdf", "application/pdf");
            // processed.chunks() traz index, text e embedding de cada trecho
        } catch (EmbeddingUnavailableException e) {
            // não houve resposta: fora do ar ou além do timeout — vale repetir depois
        } catch (EmbeddingRejectedException e) {
            // houve recusa: e.getCode() é estável ("unsupported_format", ...)
        }
    }

}
```

Toda falha sai como `EmbeddingException`, com mensagem que nomeia a rota e o que estava sendo feito:

- **`EmbeddingUnavailableException`** — o serviço não respondeu (fora do ar, inalcançável ou mais
  lento que o `EMBEDDING_READ_TIMEOUT`). A mensagem traz a URL chamada e os dois timeouts
  configurados: `The embedding service is unreachable at http://localhost:8000/api/v1/embeddings/query,
  so the embedding of the search text did not happen (connect timeout 2s, read timeout 30s). Check
  that the service is running and reachable.`
- **`EmbeddingRejectedException`** — o serviço foi alcançado e recusou. Carrega o status HTTP e o
  corpo de erro (`getCode()`, `getError()`, `getSupportedExtensions()`), para o chamador decidir
  pelo código estável em vez do texto da mensagem.

---

### Testando manualmente contra um ml de verdade

Para exercitar o cliente sem passar pelo HTTP do server — útil para ver os erros de transporte
de perto — existe o
[`EmbeddingClientManualIT`](src/test/java/com/akpedia/server/client/EmbeddingClientManualIT.java).
O nome termina em `IT`, então `./mvnw test` **não** o executa: ele só roda quando pedido.

```bash
# 1. suba o akpedia-ml (no repositório dele)
docker compose up -d

# 2. aqui, aponte para ele e rode
EMBEDDING_BASE_URL=http://127.0.0.1:8000 \
  ./mvnw test -Dtest=EmbeddingClientManualIT
```

Ele imprime o que voltou, para conferir a olho:

```text
[manual-it] embedDocument      filename=manual.pdf model=intfloat/multilingual-e5-small dims=384 chunks=1
[manual-it]   chunk 0          384 dims | O prazo de garantia do equipamento e de doze meses...
[manual-it] embedQuery         model=intfloat/multilingual-e5-small dims=384 vector=384
[manual-it] refusedDocument    status=415 UNSUPPORTED_MEDIA_TYPE code=unsupported_format supported=[.pdf]
[manual-it] refusedQuery       code=empty_query
[manual-it] serviceDown        The embedding service is unreachable at http://127.0.0.1:59999/...
```

O caso `serviceDown` aponta para uma porta vazia de propósito: é a mensagem de serviço fora do
ar, e não precisa de nada rodando. Para ver o **timeout** em vez da conexão recusada, aponte o
`EMBEDDING_BASE_URL` para algo que aceite conexão e não responda.

> Este teste roda na sua máquina, então alcança o ml em `localhost` de qualquer jeito. Já o
> container `akpedia-app` só o alcança se o ml estiver publicado no Docker — ver
> [Subindo tudo](#subindo-tudo-server--ml).

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

Os testes do `EmbeddingClient` mockam o serviço e não dependem de nada externo. Já `AkpediaServerApplicationTests`
sobe o contexto Spring + Flyway, então a suíte completa **precisa de um Postgres ativo**. O jeito mais
simples é subir só o banco via Docker:

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
