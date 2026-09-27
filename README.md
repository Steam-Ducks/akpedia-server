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
    │   │   ├── controller/HealthController.java # endpoint /health simples
    │   │   ├── entity/ # entidades JPA do schema core
    │   │   │   └── enums/ # DocumentStatus, ProcessingStatus
    │   │   └── repository/ # interfaces Spring Data JPA
    │   │   ├── client/ # clientes HTTP de serviços externos
    │   │   │   ├── EmbeddingClient.java
    │   │   │   └── GotenbergClient.java # conversão de documentos para PDF
    │   │   ├── config/ # beans de configuração e @ConfigurationProperties
    │   │   │   ├── EmbeddingClientConfig.java
    │   │   │   ├── EmbeddingProperties.java
    │   │   │   ├── GotenbergClientConfig.java
    │   │   │   ├── GotenbergProperties.java
    │   │   │   └── OpenApiConfig.java # título/descrição do Swagger
    │   │   ├── controller/
    │   │   │   ├── DocumentController.java # upload de documentos
    │   │   │   ├── EmbeddingController.java # rotas de embedding
    │   │   │   └── HealthController.java # endpoint /health simples
    │   │   ├── dto/ # objetos de transporte
    │   │   │   ├── DocumentEmbeddingResponse.java
    │   │   │   ├── DocumentChunk.java
    │   │   │   ├── DocumentUploadResponse.java
    │   │   │   ├── QueryEmbeddingRequest.java
    │   │   │   ├── QueryEmbeddingResponse.java
    │   │   │   ├── EmbeddingModelInfo.java
    │   │   │   ├── EmbeddingErrorResponse.java
    │   │   │   └── ApiErrorResponse.java
    │   │   ├── exception/
    │   │   │   ├── ApiExceptionHandler.java # traduz falhas em status HTTP
    │   │   │   ├── EmbeddingException.java
    │   │   │   ├── EmbeddingUnavailableException.java
    │   │   │   ├── EmbeddingRejectedException.java
    │   │   │   ├── CategoryNotFoundException.java
    │   │   │   ├── UserNotFoundException.java
    │   │   │   ├── InvalidDocumentUploadException.java
    │   │   │   ├── PdfConversionException.java
    │   │   │   ├── PdfConversionUnavailableException.java
    │   │   │   └── PdfConversionRejectedException.java
    │   │   └── service/
    │   │       └── DocumentUploadService.java # orquestra conversão + persistência + indexação
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init.sql # migração inicial Flyway
    │           ├── V2__create_core_schema.sql # schema core (setores, documentos, embeddings…)
    │           └── V3__fix_embeddings_vector_dimensions.sql # corrige vector(1536) -> vector(384)
    └── test/java/com/akpedia/server/ # testes
        ├── AkpediaServerApplicationTests.java
        ├── controller/
        │   ├── EmbeddingControllerTest.java # tradução de falhas em status HTTP
        │   └── DocumentControllerTest.java # idem, para o upload de documentos
        ├── service/
        │   └── DocumentUploadServiceTest.java # regras de upload com os colaboradores mockados
        └── client/
            ├── EmbeddingClientTest.java # serviço mockado: contrato e erros
            ├── EmbeddingClientTimeoutTest.java # timeouts reais, sem mock de transporte
            ├── EmbeddingClientManualIT.java # teste manual contra um ml de verdade
            └── GotenbergClientTest.java # serviço mockado: contrato e erros
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

### Limites e trecho da busca

| Variável                  | Default | Para que serve                                                  |
|---------------------------|---------|-----------------------------------------------------------------|
| `SEARCH_DEFAULT_LIMIT`    | `10`    | Quantos resultados voltam quando o `limit` não é informado       |
| `SEARCH_MAX_LIMIT`        | `50`    | Maior `limit` que a rota aceita (acima disso, 400)               |
| `SEARCH_MINIMUM_SCORE`    | `0.85`  | Similaridade mínima para um trecho valer um resultado            |
| `SEARCH_SNIPPET_LENGTH`   | `300`   | Tamanho máximo do trecho devolvido, em caracteres (ver [Busca](#busca)) |

### Conversão de documentos (Gotenberg)

| Variável                     | Default                  | Para que serve                       |
|-------------------------------|--------------------------|--------------------------------------|
| `GOTENBERG_BASE_URL`         | `http://localhost:3000`  | URL base do Gotenberg                |
| `GOTENBERG_CONNECT_TIMEOUT`  | `5s`                     | Tempo máximo para abrir a conexão    |
| `GOTENBERG_READ_TIMEOUT`     | `90s`                    | Tempo máximo para a conversão voltar |

O `POST /api/v1/documents` converte o arquivo enviado para PDF através do
[Gotenberg](https://gotenberg.dev/) (que usa um LibreOffice headless por baixo) antes de
salvar. No `docker-compose.yml` ele sobe como serviço `gotenberg` (imagem `gotenberg/gotenberg:8`)
e o default já aponta para `http://gotenberg:3000`. Rodando fora do Docker, suba-o à parte:

```bash
docker run --rm -p 3000:3000 gotenberg/gotenberg:8
```

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

Quem usa o `EmbeddingClient` hoje é o
[`DocumentUploadService`](src/main/java/com/akpedia/server/service/DocumentUploadService.java),
logo depois de salvar o documento e o PDF: para cada `DocumentChunk` da resposta, salva um
`Embedding`; se o cliente lançar `EmbeddingException` (fora do ar, recusado, ou algo ilegível),
o `catch` marca o documento como `processing_status: FAILED` com o motivo em `processing_error`
em vez de desfazer o upload — ver [Documentos](#documentos) para a rota completa.

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

## Documentos

### Endpoint

`POST /api/v1/documents` recebe um arquivo em qualquer formato que o Gotenberg saiba abrir
(docx, xlsx, pptx, odt, rtf, imagens, texto puro...), converte para PDF e salva. Um PDF
enviado passa direto, sem reconversão — o binário salvo é sempre o PDF resultante, o formato
original não é retido.

Depois de salvo, o PDF é enviado ao **akpedia-ml** (mesma rota que `POST /api/v1/documents/embed`
chama) para virar chunks com vetores, que ficam em `embeddings`. Essa etapa não é obrigatória
para a resposta ser 201: uma falha nela (akpedia-ml fora do ar, por exemplo) só deixa o
documento com `processing_status: FAILED` e o motivo em `processing_error` — o documento e o
PDF já estavam salvos antes dela rodar, então nada se perde e dá para reindexar depois. Só a
conversão para PDF é obrigatória; se ela falhar, nada é salvo (ver status de erro abaixo).

| Campo (multipart) | Obrigatório | Descrição                                             |
|--------------------|:-----------:|--------------------------------------------------------|
| `file`             | sim         | arquivo a enviar                                        |
| `categoryId`       | sim         | id de uma categoria existente                            |
| `creatorId`        | sim         | id de um usuário existente                               |
| `name`             | não         | nome do documento; default é o nome do arquivo com `.pdf` |
| `description`      | não         | descrição livre                                          |

```bash
curl -X POST localhost:8080/api/v1/documents \
  -F "file=@relatorio.docx" \
  -F "categoryId=1" \
  -F "creatorId=1"
```

A resposta traz só metadados — o PDF em si não volta no corpo:

```json
{
  "id": 1,
  "name": "relatorio.pdf",
  "description": null,
  "mime_type": "application/pdf",
  "file_size": 48213,
  "category_id": 1,
  "creator_id": 1,
  "status": "DRAFT",
  "processing_status": "COMPLETED",
  "processing_error": null,
  "created_at": "2026-09-21T20:24:00Z"
}
```

`processing_status` vem `COMPLETED` quando a indexação no akpedia-ml deu certo, ou `FAILED`
(com o motivo em `processing_error`) quando não deu — nos dois casos a resposta é 201, porque
o documento e o PDF já foram salvos antes dessa etapa rodar.

#### Status de erro

| Situação                                  | Status | `code`                        |
|--------------------------------------------|--------|-------------------------------|
| arquivo vazio, ou parâmetro ausente/inválido | 400    | `invalid_request`             |
| categoria informada não existe              | 404    | `category_not_found`          |
| usuário informado não existe                | 404    | `user_not_found`               |
| arquivo acima de 25 MB                      | 413    | `file_too_large`              |
| Gotenberg recebeu mas recusou o arquivo     | 422    | `pdf_conversion_rejected`     |
| Gotenberg respondeu algo ilegível           | 502    | `pdf_conversion_failed`       |
| **Gotenberg fora do ar ou além do timeout** | 503    | `pdf_conversion_unavailable`  |

### Abrir o arquivo

`GET /api/v1/documents/{id}/file` devolve o binário armazenado do documento. A resposta vem com
o `Content-Type` registrado no documento (sempre `application/pdf`, já que o upload converte
tudo) e `Content-Disposition: inline`, então apontar o navegador para a URL **abre** o documento
em vez de baixá-lo.

O nome oferecido é o `name` do documento, sempre com a extensão `.pdf` — um documento salvo com
nome sem extensão ainda desce como `.pdf`, senão o arquivo baixado chega ao disco como algo que
o sistema não sabe mais abrir. Barras e caracteres de controle no nome são trocados por `_`.

A resposta leva `X-Content-Type-Options: nosniff`, para o navegador ficar no tipo declarado em vez
de adivinhar outro olhando os bytes. `inline` só vale para PDF: qualquer outro tipo desce como
`attachment`, para a origem da API nunca renderizar conteúdo que não foi ela que escreveu.

#### Cache e `Range`

A resposta é `Cache-Control: private, no-cache` com `ETag` e `Last-Modified`. `no-cache` não é
"não guarde": é "guarde, mas confirme antes de usar". Então abrir o mesmo documento de novo manda
um `If-None-Match`, e a resposta é **304 sem corpo** — o PDF não desce duas vezes, e o binário nem
é lido do banco. A confirmação passa pelas restrições antes de qualquer coisa, então um documento
que foi arquivado nesse meio-tempo recebe 403 em vez de ter a cópia do navegador liberada.

A rota anuncia `Accept-Ranges: bytes` e responde `Range` com **206** e só aquele trecho, que é como
o visualizador de PDF do navegador carrega um arquivo grande por partes em vez de esperar o todo.
Um range fora do arquivo responde **416** com o tamanho real. Vários ranges numa requisição viram
`multipart/byteranges`, montado pelo próprio Spring.

#### Restrições

Não há checagem de usuário — a rota é aberta, como o resto da API, até a autenticação entrar.
O que restringe é o estado do próprio documento:

| Estado                                          | Resposta | Por quê                                                        |
|--------------------------------------------------|----------|----------------------------------------------------------------|
| `status: ARCHIVED`                               | 403      | documento fora de circulação; arquivar é definitivo             |
| `processing_status: PENDING` ou `PROCESSING`     | 409      | a indexação no akpedia-ml ainda não terminou                    |

Os outros `status` (`DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `REJECTED`) abrem normalmente.

O 409 é conflito de estado, não recusa: os dois viram `COMPLETED` sozinhos, então a mesma
requisição passa a funcionar — é o que separa esse caso do 403, que não muda.

**`processing_status: FAILED` abre.** O PDF é convertido e salvo *antes* da etapa de embeddings,
que é justamente por isso que uma falha nela não desfaz o upload — então o binário está íntegro e
só a busca não o alcança. Como não existe rota de reindexação, recusar esse documento o trancaria
para sempre por causa de uma etapa que nem tocou no arquivo.

A busca (`GET /api/v1/search`) usa o mesmo critério: a query já filtrava `processing_status =
'COMPLETED'` e agora também deixa `ARCHIVED` de fora, senão um resultado da pesquisa levaria a um
403 ao ser aberto.

```bash
# no navegador, basta abrir a URL
curl -i localhost:8080/api/v1/documents/1/file
```

```
HTTP/1.1 200
Content-Type: application/pdf
Content-Disposition: inline; filename*=UTF-8''relatorio.pdf
Content-Length: 48213
Accept-Ranges: bytes
Cache-Control: private, no-cache
ETag: "1-48213-1790634665000"
Last-Modified: Thu, 24 Sep 2026 22:31:05 GMT
X-Content-Type-Options: nosniff
```

```bash
# segunda abertura: o navegador manda o validador e não baixa de novo
curl -i localhost:8080/api/v1/documents/1/file \
  -H 'If-None-Match: "1-48213-1790634665000"'   # -> HTTP/1.1 304, sem corpo

# um trecho, como o visualizador de PDF pede
curl -i localhost:8080/api/v1/documents/1/file -H 'Range: bytes=0-1023'
# -> HTTP/1.1 206, Content-Range: bytes 0-1023/48213
```

#### Status de erro

| Situação                                          | Status | `code`                     |
|----------------------------------------------------|--------|----------------------------|
| `id` não numérico                                  | 400    | `invalid_request`          |
| documento arquivado                                | 403    | `document_archived`        |
| documento não existe                               | 404    | `document_not_found`       |
| documento existe, mas não há arquivo armazenado    | 404    | `document_file_not_found`  |
| indexação do akpedia-ml ainda não terminou         | 409    | `document_not_processed`   |
| `Range` pedido não cabe no arquivo                 | 416    | — (sem corpo)              |

---

## Busca

### Endpoint

`GET /api/v1/search?q=<texto>&limit=<n>` faz busca semântica nos documentos indexados. O texto da
consulta é vetorizado pelo akpedia-ml e comparado por similaridade de cosseno com os `embeddings`
salvos.

| Parâmetro | Obrigatório | Descrição                                                                 |
|-----------|:-----------:|---------------------------------------------------------------------------|
| `q`       | sim         | palavra, frase ou pergunta                                                 |
| `limit`   | não         | quantos resultados no máximo; default `SEARCH_DEFAULT_LIMIT`, teto `SEARCH_MAX_LIMIT` |

Só entram documentos com `processing_status: COMPLETED` — sem vetores não há o que comparar. Cada
documento aparece **uma vez só**, representado pelo trecho que mais se aproximou da consulta, e os
resultados vêm ordenados por relevância. Trechos abaixo de `SEARCH_MINIMUM_SCORE` não voltam, então
uma busca sem nada parecido responde `200` com lista vazia — não 404.

### Formato do resultado

Cada item da lista tem estes sete campos, sempre presentes (`description` vem `null` quando o
documento foi enviado sem uma):

| Campo           | Tipo    | O que é                                                                    |
|-----------------|---------|----------------------------------------------------------------------------|
| `document_id`   | número  | identificador do documento, usado nas outras rotas de documento             |
| `name`          | texto   | título do documento                                                        |
| `description`   | texto   | descrição informada no upload, ou `null`                                   |
| `mime_type`     | texto   | formato do arquivo armazenado; hoje sempre `application/pdf`                |
| `score`         | número  | similaridade entre a consulta e o trecho, de -1 a 1 (1 = idêntico)         |
| `matched_chunk` | texto   | o trecho que casou com a busca, aparado (ver abaixo)                       |
| `chunk_index`   | número  | posição desse trecho no documento, contando de 0                           |

```bash
curl "localhost:8080/api/v1/search?q=politica%20de%20ferias&limit=2"
```

```json
[
  {
    "document_id": 7,
    "name": "manual-de-integracao.pdf",
    "description": "manual tecnico do time",
    "mime_type": "application/pdf",
    "score": 0.913,
    "matched_chunk": "As ferias sao solicitadas pelo portal com trinta dias de antecedencia…",
    "chunk_index": 3
  },
  {
    "document_id": 12,
    "name": "politica-de-rh.pdf",
    "description": null,
    "mime_type": "application/pdf",
    "score": 0.874,
    "matched_chunk": "O periodo aquisitivo comeca na data de admissao…",
    "chunk_index": 0
  }
]
```

O `chunk_index` é a posição do trecho na sequência de pedaços em que o akpedia-ml dividiu o
documento, contando de 0 — `0` significa que o casamento foi no começo do documento, um índice alto
que foi mais para o fim. É o que permite mostrar *onde* o documento responde a pergunta em vez de só
*que* ele responde.

### Como o trecho é aparado

O trecho que volta em `matched_chunk` tem no máximo **300 caracteres** (`SEARCH_SNIPPET_LENGTH`),
contando o `…` que marca o corte. As regras:

- **Espaços em sequência e quebras de linha viram um espaço só.** O trecho vem de um PDF e chega com
  as quebras da página; numa lista de resultados isso só gastaria o limite com diagramação.
- **O corte cai na última palavra inteira que cabe**, e o `…` sinaliza que o texto continua. Nenhuma
  palavra é partida no meio.
- **Uma palavra sozinha maior que metade do limite é cortada no meio mesmo**, senão o trecho viraria
  só um `…`.
- Trecho que já cabe volta inteiro, sem `…`.

Como o limite conta o `…`, a interface pode dimensionar o card pelo valor configurado, sem precisar
de folga.

#### Status de erro

| Situação                                          | Status | `code`                          |
|----------------------------------------------------|--------|---------------------------------|
| `q` ausente ou em branco, ou `limit` fora do teto  | 400    | `invalid_request`               |
| akpedia-ml respondeu algo que não dá para usar     | 502    | `embedding_service_error`       |
| akpedia-ml fora do ar ou além do timeout           | 503    | `embedding_service_unavailable` |

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

> O schema usa a extensão **pgvector** (tabela `embeddings`, coluna `vector(384)`, dimensão do
> modelo `intfloat/multilingual-e5-small` do akpedia-ml). A imagem
> `pgvector/pgvector:pg16` do `docker-compose.yml` já a disponibiliza, e a migration `V2` roda
> `CREATE EXTENSION IF NOT EXISTS vector`. Se o banco local for anterior à `V2`, recrie-o com
> `docker compose down -v && docker compose up -d db`.

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
