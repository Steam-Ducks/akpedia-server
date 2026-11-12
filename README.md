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
- Keycloak em `http://localhost/auth`, atrás do nginx (ver [Autenticação (Keycloak)](#autenticação-keycloak)).
- Mailpit em `http://localhost:8025`: a caixa de entrada de teste, com todo e-mail que o server e
  o Keycloak enviam (ver [E-mail (SMTP)](#e-mail-smtp)).
- nginx em `http://localhost`: o endereço único do app (ver [Entrada única (nginx)](#entrada-única-nginx)).
  Para ele mandar a API para o serviço `app` em vez do dev container, defina `SERVER_UPSTREAM=app:8080`.
  Ele **exige** o `KEYCLOAK_DEV_ADMIN_PASSWORD` no `.env`: sem ele, o container sai com erro.

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
├── keycloak/                      # imagem do Keycloak + realm akpedia (akpedia-realm.json) + tema de login (themes/akpedia)
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
    │   │   │   ├── GotenbergClient.java # conversão de documentos para PDF
    │   │   │   └── KeycloakAdminClient.java # Admin API do Keycloak, para a tela de usuários
    │   │   ├── config/ # beans de configuração e @ConfigurationProperties
    │   │   │   ├── EmbeddingClientConfig.java
    │   │   │   ├── EmbeddingProperties.java
    │   │   │   ├── GotenbergClientConfig.java
    │   │   │   ├── GotenbergProperties.java
    │   │   │   ├── MailProperties.java # akpedia.mail.*: remetente dos e-mails
    │   │   │   ├── MailConfig.java
    │   │   │   ├── KeycloakAdminClientConfig.java
    │   │   │   ├── KeycloakAdminProperties.java # akpedia.keycloak.admin.*: URL, realm e service account
    │   │   │   ├── SecurityConfig.java # token do Keycloak nas rotas /api, papéis da revisão
    │   │   │   ├── ProcessingProperties.java # akpedia.processing.*: stream, grupo e reenvio
    │   │   │   ├── ProcessingConfig.java
    │   │   │   ├── InternalApiProperties.java # akpedia.internal.token: segredo das rotas /internal
    │   │   │   ├── InternalApiConfig.java # registra o filtro só para /internal/*
    │   │   │   ├── InternalTokenFilter.java # 401 sem o X-Internal-Token certo
    │   │   │   └── OpenApiConfig.java # título/descrição do Swagger
    │   │   ├── controller/
    │   │   │   ├── DocumentController.java # upload, análise e listagem de documentos
    │   │   │   ├── DocumentReviewController.java # aprovar ou devolver um documento da fila
    │   │   │   ├── AdminUserController.java # usuários, papéis e ativação (ADMIN)
    │   │   │   ├── AuditController.java # auditoria (ADMIN)
    │   │   │   ├── InternalDocumentController.java # rotas /internal chamadas pelo worker do akpedia-ml
    │   │   │   ├── EmbeddingController.java # rotas de embedding
    │   │   │   └── HealthController.java # endpoint /health simples
    │   │   ├── dto/ # objetos de transporte
    │   │   │   ├── DocumentEmbeddingResponse.java
    │   │   │   ├── DocumentChunk.java
    │   │   │   ├── DocumentUploadResponse.java
    │   │   │   ├── DocumentAnalysisResponse.java # sugestão devolvida ao /analyze
    │   │   │   ├── DocumentClassification.java # o que o akpedia-ml responde ao classificar
    │   │   │   ├── SubmissionSummary.java # um envio do autor, com status derivado
    │   │   │   ├── SubmissionStatus.java # status do envio visto pelo autor (ver Minhas submissões)
    │   │   │   ├── PageResponse.java # envelope de paginacao (content + totais)
    │   │   │   ├── ApproveDocumentRequest.java # correções opcionais do aprovador
    │   │   │   ├── RejectDocumentRequest.java # motivo da devolução
    │   │   │   ├── DocumentReviewResponse.java # o documento depois da decisão
    │   │   │   ├── IndexedDocumentRequest.java # resultado do worker: chunks, vetores e messageId
    │   │   │   ├── ProcessingFailureRequest.java # falha do worker: code, message e messageId
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
    │   │   │   ├── DocumentNotPendingApprovalException.java # 409: já decidido ou nunca enviado
    │   │   │   ├── DocumentNotApprovedException.java # 409: embeddings de documento não aprovado
    │   │   │   ├── InvalidEmbeddingsException.java # 400: vetor que não cabe no índice
    │   │   │   ├── UnsupportedDocumentFormatException.java # 415 do /analyze
    │   │   │   ├── CategorySuggestionUnavailableException.java # 503: liberar escolha manual
    │   │   │   ├── UnknownSuggestedCategoryException.java # 502: categoria fora do catálogo
    │   │   │   ├── InvalidDocumentUploadException.java
    │   │   │   ├── PdfConversionException.java
    │   │   │   ├── PdfConversionUnavailableException.java
    │   │   │   └── PdfConversionRejectedException.java
    │   │   └── service/
    │   │       ├── DocumentUploadService.java # orquestra conversão + persistência (pendente de aprovação)
    │   │       ├── DocumentAnalysisService.java # sugere categoria sem persistir nada
    │   │       ├── DocumentFormats.java # formatos aceitos e detecção de PDF, compartilhados
    │   │       ├── DocumentSubmissionService.java # lista os envios do autor
    │   │       ├── DocumentReviewService.java # aprova (com correções) ou devolve
    │   │       ├── DocumentApprovedEvent.java # publicado na aprovação, tratado após o commit
    │   │       ├── ProcessingQueue.java # XADD em documents:processing depois do commit, e o XACK
    │   │       ├── DocumentIndexingService.java # grava o resultado ou a falha do worker e confirma a mensagem
    │   │       ├── ProcessingRetry.java # reenvia aprovados que nunca chegaram à fila
    │   │       ├── MailService.java # envia e-mail um a um e devolve o resultado de cada envio
    │   │       ├── OutgoingMail.java # destinatário, assunto e corpo em texto
    │   │       └── MailResult.java # Sent, ou Failed com o motivo
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init.sql # migração inicial Flyway
    │           ├── V2__create_core_schema.sql # schema core (setores, documentos, embeddings…)
    │           ├── V3__fix_embeddings_vector_dimensions.sql # corrige vector(1536) -> vector(384)
    │           ├── V4__document_review_fields.sql # campos de revisão + catálogo de categorias
    │           ├── V5__seed_default_approver.sql # usuário de antes do login (ver Configuração)
    │           ├── V6__document_processing_enqueued_at.sql # quando o aprovado entrou na fila (outbox)
    │           └── V7__users_from_keycloak.sql # users.keycloak_id; password_hash deixa de ser obrigatório
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

### Autenticação (Keycloak)

O login, as contas e os papéis dos usuários ficam no [Keycloak](https://www.keycloak.org/), que
sobe no `docker-compose.yml` como serviço `keycloak` (imagem `quay.io/keycloak/keycloak:26.7.4`,
em modo dev), servido em `/auth` (`KC_HTTP_RELATIVE_PATH`) atrás do nginx: `http://localhost/auth`.
Ele não publica porta própria; entre containers, responde em `keycloak:8080/auth`.

#### Entrada única (nginx)

O serviço `nginx` ([`nginx/`](nginx/)) deixa o front, a API e o Keycloak no mesmo endereço,
`http://localhost` (porta em `AKPEDIA_HTTP_PORT`), como na POC do Keycloak:

| Caminho              | Vai para                                | Variável            | Default                     |
|----------------------|-----------------------------------------|---------------------|-----------------------------|
| `/auth/`             | Keycloak                                | `KEYCLOAK_UPSTREAM` | `keycloak:8080`             |
| `/api/`, `/health`   | `akpedia-server`                        | `SERVER_UPSTREAM`   | `akpedia-devcontainer:8080` |
| `/`                  | `akpedia-client` (Vite, com HMR, em dev) | `CLIENT_UPSTREAM`   | `akpedia-devcontainer:5173` |

Os defaults servem para o dev container (`akpedia run`). O nginx resolve os nomes a cada
requisição, então sobe mesmo com o dev container parado (e responde 502 até ele subir). A
configuração vai copiada para a imagem, como a do Keycloak: depois de editá-la, rode
`docker compose up -d --build nginx`.

```bash
cp .env.example .env              # e preencha KEYCLOAK_DEV_ADMIN_PASSWORD
docker compose up -d keycloak     # sobe também o db e cria o banco keycloak
```

- **Banco**: o Keycloak guarda os dados no mesmo Postgres, num banco à parte (`keycloak`). Quem
  cria esse banco é o serviço `keycloak-db-init`, que roda uma vez a cada `up` e sai; ele funciona
  também num volume do Postgres que já existia.
- **Realm**: o realm `akpedia` é versionado em [`keycloak/akpedia-realm.json`](keycloak/akpedia-realm.json)
  e importado com `--import-realm` **só na primeira subida**. Se o realm já existe, o arquivo é
  ignorado. O arquivo vai copiado para dentro da imagem (`keycloak/Dockerfile`), e não montado
  como volume, porque o dev container usa o Docker do host, que não enxerga os caminhos de
  dentro dele. Para aplicar uma mudança no arquivo, apague o realm no console de administração
  (ou o banco `keycloak`) e suba de novo com `docker compose up -d --build keycloak`.
- **Console de administração**: `http://localhost/auth/admin`, com o admin do realm `master`
  (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`, `admin` / `admin` por padrão).

O que o realm `akpedia` traz:

| Item | Configuração |
|------|--------------|
| Cadastro | Só o admin cadastra usuários (tela Usuários do app, `POST /api/v1/admin/users`): o autocadastro fica desligado (`registrationAllowed: false`) e a tela de login não tem link de cadastro; o email é o nome de usuário |
| Atributos obrigatórios | Nome, sobrenome, email, `cpf` (11 dígitos, só números) e `sector` (lista com os 7 setores) |
| Papéis | `STANDARD`, `APPROVER` e `ADMIN`. Toda conta nova recebe `STANDARD` (papel padrão do realm) |
| `akpedia-client` | Cliente público do front: Authorization Code + PKCE (S256), redirect para `AKPEDIA_CLIENT_URL`. O access token leva `akpedia-server` no `aud` e o setor do usuário na claim `sector` |
| `akpedia-server` | Cliente confidencial do back, com service account (`view-users` e `manage-users` do `realm-management`) para usar a Admin API |
| Idioma | Telas em português (`pt-BR`), com inglês como opção |
| Tema | `akpedia` nas telas de login e senha (ver abaixo) |

Os setores e as regras do CPF estão no User Profile do realm (no arquivo, dentro de
`components` → `kc.user.profile.config`; no console, em *Realm settings* → *User profile*).

| Variável                        | Default                     | Para que serve |
|---------------------------------|-----------------------------|----------------|
| `KEYCLOAK_URL`                  | `http://localhost/auth`     | URL pública do Keycloak, que é também o emissor (`iss`) dos tokens. Entre containers ele responde em `keycloak:8080/auth` |
| `KEYCLOAK_ADMIN`                | `admin`                     | Admin do realm `master` (console de administração) |
| `KEYCLOAK_ADMIN_PASSWORD`       | `admin`                     | Senha desse admin |
| `KEYCLOAK_DB`                   | `keycloak`                  | Banco do Keycloak no Postgres |
| `AKPEDIA_CLIENT_URL`            | `http://localhost`          | URL do front, para onde o login e o logout podem voltar |
| `KEYCLOAK_SERVER_CLIENT_SECRET` | `akpedia-dev-server-secret` | Secret do cliente `akpedia-server` |
| `KEYCLOAK_DEV_ADMIN_EMAIL`      | `admin@akpedia.local`       | Email (e login) do usuário `ADMIN` inicial |
| `KEYCLOAK_DEV_ADMIN_PASSWORD`   | (sem default)               | Senha desse usuário. **Obrigatória**: sem ela o Keycloak não sobe |

As quatro últimas só são lidas quando o realm é importado, ou seja, na primeira subida.

#### Tema das telas de login

As telas do Keycloak (login, erros, troca de senha) usam o tema `akpedia`, em
[`keycloak/themes/akpedia`](keycloak/themes/akpedia), com as cores, a fonte (Manrope), o logo e os
campos e botões do akpedia-client. Ele herda o `keycloak.v2` e só troca o CSS
(`login/resources/css/akpedia.css`), então todos os fluxos do Keycloak continuam funcionando.

- Vai copiado para a imagem (`keycloak/Dockerfile`): depois de mudar o CSS, rode
  `docker compose up -d --build keycloak`. Em modo dev o Keycloak não guarda cache de tema, então
  basta recarregar a página.
- Realm novo já vem com `loginTheme: akpedia`. Num realm que já existia, escolha o tema em
  **Realm settings → Themes → Login theme** no console de administração.

#### Usuário ADMIN inicial (só para desenvolvimento)

O import cria um usuário com o papel `ADMIN`, para entrar no app antes de existir qualquer outro
administrador:

| Campo  | Valor |
|--------|-------|
| Login  | `KEYCLOAK_DEV_ADMIN_EMAIL` (`admin@akpedia.local` por padrão) |
| Senha  | `KEYCLOAK_DEV_ADMIN_PASSWORD`, do `.env` |
| Papéis | `ADMIN` e `STANDARD` |
| CPF / setor | `52998224725` / `Engenharia` |

Ele existe só para o ambiente local. Fora dele, não importe esse usuário: tire-o do arquivo ou
apague-o depois do import, e crie os administradores de verdade pelo console.

### Login nas rotas da API

Toda rota em `/api` exige o access token do Keycloak no header `Authorization: Bearer <token>`;
sem ele, ou com um token vencido, a resposta é **401** `unauthorized`. O token vem do login no front
(`akpedia-client`). O servidor confere a assinatura, o emissor (`iss`) e se o token foi emitido
para ele (`akpedia-server` no `aud`).

| Rota | Quem pode |
|------|-----------|
| `GET /health`, `/actuator/health`, Swagger (`/swagger-ui.html`, `/v3/api-docs`) | qualquer um, sem token |
| `/internal/**` | só o worker do akpedia-ml, com o `X-Internal-Token` (ver [Indexação pelo worker](#indexação-pelo-worker-rotas-internas)) |
| `GET /api/v1/documents/pending` e `/pending/categories`, `POST /api/v1/documents/{id}/approve` e `/reject` | papel `APPROVER` ou `ADMIN`; os outros recebem **403** |
| `/api/v1/admin/**` (termos de uso, usuários, permissões e auditoria) | papel `ADMIN`; os outros recebem **403** `forbidden` |
| o resto de `/api` | qualquer usuário logado |

Os **401** e **403** da checagem de login e papel vêm no formato de erro da API
(`{"code", "message"}`), como os demais erros. O token de uma conta desativada por um admin,
ainda válido por alguns minutos, recebe **401** `account_disabled`.

Os exemplos de `curl` deste README omitem o header. Para rodá-los, entre no front, copie o token
de uma requisição à API (aba Rede do navegador) e acrescente `-H "Authorization: Bearer $TOKEN"`.

| Variável               | Default                                                           | Para que serve |
|------------------------|-------------------------------------------------------------------|----------------|
| `KEYCLOAK_ISSUER_URI`  | `http://localhost/auth/realms/akpedia`                            | Emissor esperado no `iss` do token: a URL pública do Keycloak |
| `KEYCLOAK_JWK_SET_URI` | `http://localhost/auth/realms/akpedia/protocol/openid-connect/certs` | De onde o servidor baixa as chaves que assinam os tokens. Dentro de um container, aponte para o Keycloak pela rede Docker (`http://keycloak:8080/auth/...` no compose, `http://akpedia-keycloak:8080/auth/...` no dev container) |

#### Usuário no banco

O usuário de uma requisição é o dono do token. Na primeira requisição que precisa dele (enviar um
documento, listar as próprias submissões, aprovar), o servidor cria a linha em `users` a partir do
token: nome, email, setor (claim `sector`) e CPF (claim `cpf`), ligada à conta do Keycloak por
`keycloak_id` (o `sub` do token). Nas seguintes, copia o que tiver mudado no Keycloak. Um setor que
o banco ainda não tem é criado. Uma linha que já existia com o mesmo email é reaproveitada, e não
duplicada.

O CPF é conferido no primeiro acesso e sempre que muda no Keycloak: com dígitos verificadores
errados toda a API, leituras inclusive, responde **422** `invalid_cpf`, e se outro usuário já tem
esse CPF em `users.cpf` (único), **422** `cpf_taken` (`CpfGateFilter`, depois do filtro dos
termos). A linha é criada mesmo assim, sem o CPF (ou com o anterior), para o usuário poder pedir a
correção: continuam abertos `GET /me` (que traz o motivo em `cpf_problem`),
aceitar ou recusar os termos, excluir a conta e os pedidos de alteração (`/me/profile/requests`),
onde ele envia o CPF certo. Aprovado o pedido, o CPF vai para a conta e para a linha, e o acesso
volta no próximo token. O cadastro pela tela do Keycloak só confere o formato, por isso a
conferência fica aqui.

Uma conta sem email, setor ou CPF (só acontece com usuário criado à mão no console, sem esses
campos) recebe **403** `incomplete_account` nessas rotas.

Os claims `sector` e `cpf` vêm de mappers do client `akpedia-client` no realm. Um Keycloak que
importou o realm antes do mapper `cpf` existir precisa dele criado à mão (Clients › akpedia-client ›
Client scopes › Dedicated › Add mapper › User Attribute, atributo e claim `cpf`, só no access token).

O usuário `aprovador@akpedia.local`, criado pela migration `V5__seed_default_approver.sql` quando
ainda não havia login, continua no banco como autor e aprovador dos documentos daquela época.
Ninguém entra com ele, a não ser que um admin cadastre uma conta com esse email.

#### Termos de uso

Todo usuário aceita a versão atual dos termos de uso antes de usar a API, como na PoC do
Keycloak. Quem escreve e publica os termos é o admin, pelo front; o termo nativo do Keycloak
(*Terms and Conditions*) fica desligado.

| Rota | Quem pode | O que faz |
|------|-----------|-----------|
| `GET /api/v1/me` | qualquer usuário logado | `{id, name, email, sector, roles, terms_pending}`: quem é o usuário (a linha em `users` é criada aqui, se ainda não existir) e se ele precisa aceitar os termos atuais |
| `GET /api/v1/terms` | qualquer usuário logado | `{current, accepted}`: os termos atuais (`version`, `title`, `content`, `published_at`, `published_by`) e a última versão que o usuário aceitou (`version`, `accepted_at`). Cada um é `null` se não houver |
| `POST /api/v1/terms/accept` | qualquer usuário logado | `{"version": n}`, a versão que o usuário viu. **409** `terms_changed` se ela não for mais a atual |
| `POST /api/v1/terms/decline` | qualquer usuário logado | `{"version": n}`: registra na auditoria que o usuário recusou (**204**). Sem aceitar a versão atual ninguém usa o sistema (um aceite antigo não vale), então o front oferece só aceitar ou recusar e excluir a conta: depois desta rota, chama `DELETE /api/v1/me` e faz o logout |
| `GET /api/v1/admin/terms` | `ADMIN` | todas as versões publicadas, da mais nova para a mais antiga |
| `GET /api/v1/admin/terms/acceptances?version=&user=&page=0&size=20` | `ADMIN` | página de aceites, do mais novo para o mais antigo: `version`, `user_id`, `user_name`, `user_email`, `accepted_at`. `version` filtra uma versão; `user` procura no nome ou no e-mail |
| `POST /api/v1/admin/terms` | `ADMIN` | `{"title", "content"}` publica a próxima versão (**201**). `content` é texto puro, parágrafos separados por linha em branco; nunca é lido como HTML |

- **Bloqueio**: enquanto o usuário não aceitar a versão atual, toda rota de `/api`, exceto as
  quatro primeiras acima e `DELETE /api/v1/me` (excluir a conta), responde **403** `terms_pending`, inclusive as de admin: quem
  publica também aceita. A conferência vem depois do login e dos papéis, então sem token
  continua sendo **401**, e sem o papel, **403** `forbidden`. `/health`, Swagger e
  `/internal` não passam por ela.
- **Versão 1.0 provisória**: a migration `V16__seed_terms_version_1.sql` publica a versão
  `1.0` com um texto provisório, para que todos aceitem termos desde o primeiro deploy. O texto
  definitivo entra como uma versão nova, publicada pelo admin. A migration só insere em um banco
  sem nenhuma versão: onde o admin já publicou, nada muda.
- **Sem termos publicados, não há bloqueio**: num banco sem versão nenhuma, ninguém é
  perguntado.
- **Histórico**: `terms_versions` e `terms_acceptances` (migration `V8__terms_of_use.sql`) só
  recebem inserções. Uma versão publicada nunca muda (uma mudança é uma versão nova), e cada
  aceite fica guardado com o usuário, a versão e quando. Cada usuário aceita uma versão uma vez
  só (migration `V14`): aceitar de novo devolve o primeiro aceite.
  Aceite, recusa e publicação também vão para a [auditoria](#auditoria).

#### Usuários (admin)

A tela de usuários do admin lê e altera as contas no próprio Keycloak, pela Admin API, com a
service account do cliente `akpedia-server` (`view-users` e `manage-users`). O token dela fica
guardado até perto de vencer.

| Rota | O que faz |
|------|-----------|
| `GET /api/v1/admin/users?search=&sector=&role=&page=0&size=20` | Página de usuários na ordem do Keycloak (pelo username, que é o email). `search` procura no email e no nome; `sector` filtra um setor; `role` filtra quem tem `APPROVER` ou `ADMIN`, ou, com `STANDARD`, quem não tem nenhum dos dois (com `sector` ou `role`, o servidor lê o realm inteiro e filtra, já que o Keycloak não combina esses filtros). Cada um traz `id` (do Keycloak), `name`, `email`, `sector`, `enabled`, `roles` (entre `STANDARD`, `APPROVER` e `ADMIN`), `terms_accepted_version`, `current_terms_version` e `created_at` |
| `POST /api/v1/admin/users` | `{"first_name", "last_name", "email", "password", "cpf", "sector", "roles": []}` cria a conta no Keycloak (**201**, o usuário como na lista), com o e-mail já verificado, a senha definitiva e os papéis (`APPROVER`, `ADMIN`). `cpf` tem 11 dígitos com os dígitos verificadores certos (pontos e traço são ignorados) e `sector` é um dos setores do realm (`Engenharia`, `Qualidade`, `Logística`, `Manutenção`, `Recursos Humanos`, `Financeiro`, `Jurídico`). **400** `invalid_user` diz o que falta ou está errado (inclusive o que o Keycloak recusar, como a política de senha); **409** `user_exists` se o e-mail já tem conta, `cpf_taken` se o CPF já é de outra. Se um passo falha depois de a conta ser criada, ela é apagada de novo. A senha nunca vai para log nem auditoria |
| `GET /api/v1/admin/roles` | Os três papéis, com nome na tela, descrição, se é `assignable` e as permissões de cada um |
| `PUT /api/v1/admin/users/{id}/roles` | `{"roles": ["APPROVER", "ADMIN"]}`: o usuário fica com exatamente esses papéis atribuíveis. `STANDARD` vem com toda conta e não é mexido. **400** `invalid_role` para outro papel |
| `PUT /api/v1/admin/users/{id}/status` | `{"enabled": false}` desativa a conta (não entra mais, e as sessões dela são encerradas na hora); `true` reativa. Espelhado em `users.is_active` quando o usuário já tem linha, e o token ainda válido de uma conta desativada recebe **401** `account_disabled` |

- A mudança de papel chega ao usuário no próximo refresh do token (uns 5 minutos) ou no próximo login.
- O admin não tira o próprio `ADMIN` (**409** `cannot_change_own_admin`) nem desativa a própria
  conta (**409** `cannot_disable_self`), e ninguém desativa nem tira o `ADMIN` do único admin
  ativo (**409** `last_admin`), nem um admin que acabou de perder o papel e ainda tem o token antigo.
- O CPF, ao ser cadastrado ou trocado (pelo admin ou num pedido aprovado), precisa ter os dígitos
  verificadores certos e não pode ser de outra conta (**409** `cpf_taken`). O novo CPF vai também
  para `users.cpf`. No cadastro pela tela do Keycloak só o formato (11 dígitos) é conferido; o
  resto, no primeiro acesso (acima).
- **404** `user_not_found` para um id que o Keycloak não conhece; **502** `keycloak_unavailable`
  quando o Keycloak não responde ou recusa a service account.

#### Permissões por setor (admin)

Cada setor vê só as categorias liberadas para ele (tabela `sector_categories`). A migration
`V13__sectors_and_category_access.sql` cria os 7 setores do realm e a tabela combinada:
Engenharia, Qualidade, Logística e Manutenção veem `Tecnico`, `Regulatorio` e `Qualidade`;
Recursos Humanos, Financeiro e Jurídico veem `Regulatorio` e `Juridico`.

| Rota | O que faz |
|------|-----------|
| `GET /api/v1/admin/sector-categories` | `{categories, sectors}`: todas as categorias (as colunas, por nome) e os setores do realm (as linhas, na ordem do realm), cada um com os `category_ids` que vê |
| `PUT /api/v1/admin/sectors/{id}/categories` | `{"category_ids": [1, 3]}`: o setor passa a ver exatamente essas categorias. Vale a partir da próxima busca. Registrado como `SECTOR_CATEGORIES_CHANGED`, com as categorias antes e depois. **404** `sector_not_found` ou `category_not_found` |

**O que o funcionário vê.** Um documento de uma categoria que o setor do usuário não vê nunca
aparece para ele (`CategoryAccessService`). A regra vale para todos os papéis, inclusive ADMIN, e
é lida da tabela a cada requisição, sem cache: uma mudança na tela de permissões vale a partir da
próxima chamada.

| Rota | Com a regra |
|------|-------------|
| `GET /api/v1/search` | busca só nas categorias do setor; as de `categoryIds` que o setor não vê são descartadas (não dá erro). Se não sobrar nenhuma, responde `[]` sem gerar embedding |
| `GET /api/v1/documents` | lista só documentos das categorias do setor |
| `GET /api/v1/categories` | lista só as categorias do setor (o catálogo inteiro fica em `GET /api/v1/admin/sector-categories`) |
| `GET /api/v1/documents/{id}/file`, `/preview` e `/preview/pages/{page}` | **403** `category_forbidden`, checado antes do estado do documento (arquivado, indexando). Vale também na revalidação do cache do navegador |

Duas exceções, só para abrir um documento: o **autor** abre o próprio documento, mesmo que um
aprovador o tenha movido para uma categoria que o setor dele não vê; e **APPROVER/ADMIN** abrem um
documento **aguardando aprovação** de qualquer categoria, porque a fila de revisão lista todos.
Depois de aprovado, a regra do setor vale para eles também.

Para corrigir a categoria na revisão, o aprovador usa `GET /api/v1/documents/pending/categories`
(só `APPROVER` ou `ADMIN`), que lista o catálogo inteiro: a fila tem documentos de todas as
categorias, e `GET /categories` só mostra as do setor dele.

O envio (`POST /api/v1/documents`) não é restringido pela regra: enviar não é ver, a sugestão de
categoria do classificador pode ser de qualquer categoria, e o autor continua abrindo o próprio
documento.

As três queries de busca e listagem (`EmbeddingRepository.search`, `searchRecentByCategory` e
`DocumentRepository.findRecent`) exigem a lista de categorias: não existe chamada sem o filtro.

A lista de setores fica em três lugares, que andam juntos: o atributo `sector` do realm
(`keycloak/akpedia-realm.json`), `AdminUserService.SECTORS` e a migration V13. Um Keycloak que
já tinha o realm importado não relê o arquivo: para pegar os setores novos, troque as opções do
atributo `sector` no console (*Realm settings > User profile*) ou reimporte o realm (ver
[Reimportar o realm](#reimportar-o-realm)).

| Variável                        | Default                     | Para que serve |
|---------------------------------|-----------------------------|----------------|
| `KEYCLOAK_ADMIN_BASE_URL`       | `http://localhost/auth`     | Onde o servidor chama a Admin API. Dentro de um container, pela rede Docker (`http://keycloak:8080/auth` no compose, `http://akpedia-keycloak:8080/auth` no dev container) |
| `KEYCLOAK_REALM`                | `akpedia`                   | Realm dos usuários |
| `KEYCLOAK_SERVER_CLIENT_ID`     | `akpedia-server`            | Cliente confidencial cuja service account é usada |
| `KEYCLOAK_SERVER_CLIENT_SECRET` | `akpedia-dev-server-secret` | Secret desse cliente; o mesmo do import do realm |

#### Excluir a conta

`DELETE /api/v1/me` (**204**) exclui a conta de quem chama, mesmo sem ter aceitado os termos.
Nesta ordem:

1. Grava a exclusão no **log de exclusões**, antes de tudo, para ela nunca se perder.
2. Apaga a conta no Keycloak (se ela já não existe, segue).
3. Anonimiza a linha em `users` em vez de apagá-la (os documentos e os aceites dos termos
   apontam para ela): nome `Usuário removido`, e-mail `removido-<id>@removido.invalid`, sem
   `keycloak_id`, inativa, com `deleted_at`. O `id` da linha fica: é o identificador interno,
   imutável, do usuário excluído.
4. Pseudonimiza a auditoria: o que ele fez continua lá (a ação, o que foi afetado, as datas, o id
   do Keycloak e a linha em `users`), sem dado pessoal. Os registros dele perdem e-mail, IP e user
   agent; os que outros gravaram sobre ele (`USER_CREATED`, `USER_ROLES_CHANGED`, `USER_ENABLED`,
   `USER_DISABLED`) perdem o `target_email` e ficam com o `target_keycloak_id`; e as requisições
   que buscaram o e-mail dele (como `GET /api/v1/admin/users?search=`) perdem a query string. A
   tela de auditoria mostra `user_pseudonym` (`Usuário removido #<id>`) no lugar do nome.
5. Registra `ACCOUNT_DELETED`, também sem e-mail nem IP.

A conta some como se nunca tivesse existido: para voltar, um admin cadastra o usuário de novo
(o mesmo e-mail funciona), e ele ganha outra conta no Keycloak e outra linha em `users`.

**409** `last_admin` se ele é o único `ADMIN` ativo; **502** `keycloak_unavailable` se o Keycloak
falha (a exclusão já está no log: tentar de novo, ou o próximo replay, termina). O token do
usuário excluído ainda vale por alguns minutos: até vencer, toda rota responde **401**
`account_deleted`, e nada pessoal dele é gravado.

O log é um arquivo, uma linha JSON por exclusão, só com ids (é guardado para sempre):

```json
{"time":"2026-11-08T12:00:00Z","keycloak_id":"0db3d7d8-...","user_id":42,"deleted_by":"self"}
```

| Variável | Default | Para que serve |
|----------|---------|----------------|
| `ACCOUNT_DELETION_LOG_FILE` | `./data/account-deletions.jsonl` | O arquivo. No compose, `/app/data/account-deletions.jsonl`, no volume `account_deletions` |
| `ACCOUNT_DELETION_REPLAY_ON_STARTUP` | `true` | Refaz as exclusões a cada start |
| `ACCOUNT_DELETION_REPLAY_RETRY_DELAY` | `15s` | Espera entre tentativas, enquanto o Keycloak sobe (até 10) |

##### Restaurando um backup

O realm do Keycloak e o banco do app estão no mesmo Postgres, então restaurar um backup traz de
volta as contas excluídas depois dele. Por isso o log fica **fora do banco** (e fora dos backups
dele): não restaure o volume `account_deletions` junto com o Postgres.

Depois de restaurar, o servidor refaz o log sozinho ao subir, em segundo plano e tentando de novo
enquanto o Keycloak não responde: apaga no Keycloak cada conta que voltou e anonimiza de novo a
linha em `users` (a que ainda tem aquele `keycloak_id`, ou a mesma linha ainda não excluída), e
pseudonimiza de novo a auditoria. Contas que continuam excluídas ficam como estão. No log do
servidor sai um resumo: `N deletions replayed, M restored accounts deleted again`.

Para rodar na hora, sem reiniciar:

```bash
curl -X POST http://localhost:8080/api/v1/admin/account-deletions/replay -H "Authorization: Bearer $TOKEN"
# {"entries": 3, "deleted_again": 1}
```

`entries` é quantas exclusões o log tem; `deleted_again`, quantas tinham voltado (só `ADMIN`).

#### Auditoria

`audit_logs` guarda dois tipos de registro, com o usuário (id do Keycloak, email e a linha em
`users`, se houver), IP (o primeiro do `X-Forwarded-For`, se houver), user agent, método e caminho:

- **Requisições** (`action = HTTP_REQUEST`): uma por requisição a `/api`, gravada quando a resposta
  sai, com o status (inclusive **401** e **403**, sem usuário quando não há token válido), a
  duração e a query string em `details`. O corpo nunca é gravado. Ficam de fora `/internal`,
  `/health`, `/actuator`, o Swagger e a leitura da própria auditoria. Uma falha ao gravar só vai
  para o log, nunca para a resposta.
- **Eventos**: `TERMS_ACCEPTED`, `TERMS_DECLINED`, `TERMS_PUBLISHED`, `DOCUMENT_UPLOADED`,
  `DOCUMENT_APPROVED`, `DOCUMENT_REJECTED`, `USER_ROLES_CHANGED`, `USER_ENABLED`, `USER_DISABLED`,
  `USER_CREATED`, `ACCOUNT_DELETED` e `BREACH_NOTICE_SENT`,
  com o que foi afetado (`entity_type`, `entity_id`) e os detalhes. São gravados na mesma transação
  da ação: se ela falha, o evento não fica.

| Rota | O que faz |
|------|-----------|
| `GET /api/v1/admin/audit?kind=EVENT&action=&user=&from=&to=&page=0&size=50` | Página da auditoria, da mais nova para a mais antiga. `kind` é `EVENT` (padrão), `REQUEST` ou `ALL`; `user` é parte do email; `from` e `to` são data e hora ISO-8601, inclusivos. `size` até 200. Cada registro traz `user_email` e `user_name`, ou, se a conta foi excluída, os dois `null` e `user_pseudonym` (`Usuário removido #<id>`, o mesmo em todos os registros dela) |
| `GET /api/v1/admin/audit/actions` | Os eventos já registrados, em ordem alfabética, para o filtro |

#### Aviso de vazamento de dados (admin)

Quando há um incidente de segurança com dados pessoais (LGPD, art. 48), o admin envia por e-mail
(`MailService`) um aviso aos usuários afetados, dizendo que a ANPD foi comunicada e quais medidas
estão em andamento. O envio **só sai depois da confirmação de que a ANPD foi comunicada**
(`anpd_informed: true`); sem ela a rota responde `422` e nada é enviado nem gravado.

| Rota | O que faz |
|------|-----------|
| `GET /api/v1/admin/breach-notices/template` | Assunto e texto sugeridos (`subject`, `body`). O texto tem lacunas `[PREENCHER: ...]` para o que é próprio do incidente |
| `POST /api/v1/admin/breach-notices` | Envia o aviso. Responde `201` com quantos saíram e quais falharam |
| `GET /api/v1/admin/breach-notices?page=0&size=20` | Página dos avisos enviados, do mais novo para o mais antigo, com as contagens. `size` até 100 |
| `GET /api/v1/admin/breach-notices/{id}` | O aviso como foi enviado, com o status de cada destinatário |

```json
// POST /api/v1/admin/breach-notices
{
  "subject": "Comunicado sobre incidente de segurança envolvendo seus dados pessoais",
  "body": "Olá, ...",
  "all_users": true,            // ou "user_ids": [10, 11] -- um dos dois
  "anpd_informed": true
}

// 201
{
  "id": 5, "total": 120, "sent": 119, "failed": 1,
  "failures": [
    { "user_id": 11, "email": "bruno@akaer.com.br", "reason": "REJECTED", "error": "REJECTED: 550 no such user" }
  ]
}
```

- **Destinatários**: usuários de `users` ligados a uma conta do Keycloak e não excluídos, inclusive
  os desativados (os dados deles também estão no sistema). Com `user_ids`, um id que não existe,
  foi excluído ou não tem conta no Keycloak é recusado (`400`, com os ids na mensagem).
- **Texto**: assunto de uma linha só, até 200 caracteres; texto puro, até 50.000. Um aviso que
  ainda tem `[PREENCHER` no assunto ou no texto é recusado (`400`).
- **Registro**: o aviso (`breach_notices`) e uma linha por destinatário (`breach_notice_recipients`)
  são gravados como `PENDING` antes do envio; cada linha vira `SENT` ou `FAILED` (com o motivo em
  `error`) depois. Um envio interrompido no meio deixa as linhas restantes `PENDING`, visíveis no
  aviso. Um e-mail que falha não impede os outros. Na auditoria fica `BREACH_NOTICE_SENT`, com as
  contagens.
- **Sem envio duplicado**: enquanto um aviso está sendo enviado, outro envio responde `409
  breach_notice_in_progress`; o mesmo aviso (assunto e texto iguais) já entregue a algum destes
  usuários nos últimos 30 minutos responde `409 breach_notice_duplicate`. Isso cobre o segundo
  clique e a tela tentando de novo. Reenviar o mesmo texto só para quem falhou (`user_ids`) é
  permitido. Um envio interrompido no meio deixa de bloquear depois de 30 minutos.
- O envio é feito antes da resposta, que já traz o resultado de cada e-mail; por isso o nginx dá
  600s para `/api/v1/admin/breach-notices` (o resto de `/api/` tem 120s).
- Os avisos não são alterados nem apagados. Se o usuário excluir a conta, as linhas dele ficam
  (como registro de que foi avisado), mas com o e-mail anonimizado.

> **Texto provisório:** o modelo em `src/main/resources/breach-notice/` é um rascunho a partir do
> art. 48, §1º da LGPD, até o texto definitivo do aviso ser entregue pela documentação.

| Situação                                                         | Status | `code`                    |
|------------------------------------------------------------------|--------|---------------------------|
| `anpd_informed` diferente de `true`                              | 422    | `anpd_not_informed`       |
| assunto ou texto inválido, lacuna `[PREENCHER` sobrando, nenhum ou os dois entre `all_users` e `user_ids`, usuário que não pode ser avisado | 400 | `invalid_breach_notice` |
| outro aviso sendo enviado agora                                  | 409    | `breach_notice_in_progress` |
| o mesmo aviso já entregue a algum destes usuários nos últimos 30 min | 409 | `breach_notice_duplicate` |
| aviso inexistente                                                | 404    | `breach_notice_not_found` |

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

### Chat

O chat usa o mesmo akpedia-ml (`EMBEDDING_BASE_URL`), com timeouts próprios: o modelo de linguagem
leva bem mais que um vetor (o akpedia-ml espera até 120s por ele, `LLM_TIMEOUT_SECONDS`).

| Variável               | Default | Para que serve                                                         |
|------------------------|---------|------------------------------------------------------------------------|
| `CHAT_MINIMUM_SCORE`   | `0.80`  | Similaridade mínima para um trecho ir para o modelo                     |
| `CHAT_MAX_PASSAGES`    | `6`     | Quantos trechos, no máximo, vão para o modelo (o mais próximo primeiro) |
| `CHAT_CONNECT_TIMEOUT` | `2s`    | Tempo máximo para abrir a conexão com o akpedia-ml                      |
| `CHAT_READ_TIMEOUT`    | `150s`  | Tempo máximo para a resposta; acima do timeout do LLM no akpedia-ml     |

O nginx dá 180s para `/api/v1/chat` (o resto de `/api/` tem 120s), para não cortar uma resposta
que ainda está vindo.

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

### E-mail (SMTP)

O server envia e-mail pelo `MailService` (o aviso de vazamento de dados é o primeiro uso), e o
Keycloak usa o mesmo servidor SMTP para verificar e-mails e redefinir senhas.

| Variável                  | Default                       | Para que serve |
|---------------------------|-------------------------------|----------------|
| `MAIL_HOST`               | `localhost` (`mailpit` no compose) | Servidor SMTP |
| `MAIL_PORT`               | `1025`                        | Porta SMTP (num servidor real, em geral `587`) |
| `MAIL_USERNAME`           | vazio                         | Login no SMTP |
| `MAIL_PASSWORD`           | vazio                         | Senha no SMTP |
| `MAIL_SMTP_AUTH`          | `false`                       | `true` para fazer login com o usuário e a senha acima |
| `MAIL_SMTP_STARTTLS`      | `false`                       | `true` para exigir STARTTLS (servidor real na porta 587) |
| `MAIL_FROM`               | `nao-responda@akpedia.local`  | Remetente. Checado na subida: um endereço inválido impede o boot |
| `MAIL_FROM_NAME`          | `Akpedia`                     | Nome mostrado junto do remetente |
| `MAIL_CONNECT_TIMEOUT_MS` | `5000`                        | Tempo máximo para abrir a conexão |
| `MAIL_READ_TIMEOUT_MS`    | `10000`                       | Tempo máximo esperando uma resposta do servidor |
| `MAIL_WRITE_TIMEOUT_MS`   | `10000`                       | Tempo máximo para enviar a mensagem |
| `MAIL_SMTP_LOCALHOST`     | `akpedia.local`               | Nome com que o server se apresenta ao SMTP (`EHLO`). Em produção, o domínio real do server |
| `MAIL_BATCH_SIZE`         | `50`                          | Quantos e-mails o `sendAll` manda por conexão SMTP antes de abrir a próxima (1 a 1000) |

Os timeouts existem porque o Jakarta Mail espera para sempre por padrão: um servidor que aceita a
conexão e para de responder seguraria o envio, e todos os que vêm depois dele.

**Em desenvolvimento** o compose sobe o [Mailpit](https://mailpit.axllent.org/) (serviço `mailpit`,
SMTP na porta `1025`): ele guarda tudo o que recebe e não entrega nada. Os e-mails aparecem em
<http://localhost:8025>. Rodando o server fora do Docker, suba só ele:

```bash
docker compose up -d mailpit
./mvnw test -Dtest=MailServiceManualIT   # envia um lote de teste e confere que chegou no Mailpit
```

**Como o `MailService` responde.** `send(OutgoingMail)` envia um e-mail em texto (UTF-8) e
**nunca lança exceção**: devolve `Sent` (com o `Message-ID` enviado) ou `Failed` com o motivo.
`sendAll(...)` devolve um resultado por e-mail, na mesma ordem, e uma falha não impede os outros.

Ele manda até `MAIL_BATCH_SIZE` e-mails por conexão SMTP, em vez de abrir uma conexão (e fazer
login) para cada e-mail. Se o servidor derrubar a conexão no meio, ela é aberta de novo para os que
faltam, e só o e-mail que estava sendo enviado falha; um destinatário recusado também falha sozinho.
Os dois casos são testados contra um servidor que se comporta mal (`MailServiceConnectionTest`).

Cada e-mail sai assim (texto puro, UTF-8, com o `Message-ID` no domínio do remetente, para não
expor o nome da máquina):

```
From: Akpedia <nao-responda@akpedia.local>
To: ana@empresa.com
Message-ID: <0b6f...-...@akpedia.local>
Subject: =?UTF-8?Q?Aviso_de_incidente_de_seguran=C3=A7a?=
Content-Type: text/plain; charset=UTF-8
Content-Transfer-Encoding: quoted-printable
```

| Motivo (`Failed.reason`) | Quando | Vale tentar de novo? |
|--------------------------|--------|----------------------|
| `INVALID_MESSAGE` | sem destinatário, endereço inválido (ou mais de um), assunto vazio ou com quebra de linha, corpo ausente. Nada chega a ser enviado | não |
| `REJECTED` | o servidor recusou de vez, com resposta `5xx` (ex.: caixa inexistente) | não |
| `AUTHENTICATION` | o servidor recusou o usuário ou a senha: problema de configuração | não |
| `UNAVAILABLE` | servidor fora do ar, inalcançável, mais lento que os timeouts, conexão caiu no meio, ou recusou por enquanto com resposta `4xx` (ex.: caixa cheia) | sim |
| `FAILED` | qualquer outro erro do servidor ou da biblioteca | sim |

A classificação usa o **código de resposta SMTP**, não o tipo da exceção: o Angus Mail lança a mesma
`SMTPSendFailedException` para um `550` e para uma conexão que caiu (código `-1`).

Se todos os e-mails de um lote saírem e só o fechamento da conexão falhar, eles continuam `Sent`:
o servidor já os aceitou.

Nos logs os destinatários aparecem mascarados (`a***@empresa.com`), inclusive quando o servidor
cita o endereço na resposta: quem recebeu um aviso de vazamento já é dado pessoal.

O health (`/actuator/health`) não checa o SMTP (`management.health.mail.enabled: false`): sem e-mail
o resto do server funciona, e cada envio que falha já é reportado pelo `MailService`.

**No Keycloak** o realm importa o SMTP das mesmas variáveis (`smtpServer` em
`keycloak/akpedia-realm.json`). A verificação de e-mail e a redefinição de senha continuam
**desligadas** (`verifyEmail` e `resetPasswordAllowed`): ligá-las é decisão de produto, em
*Realm settings > Login*. Como o realm só é importado na primeira subida, um Keycloak que já existia
não recebe o SMTP: configure em *Realm settings > Email* (host `mailpit`, porta `1025`, remetente
`nao-responda@akpedia.local`) ou reimporte o realm.

#### Reimportar o realm

O Keycloak não tem volume próprio: guarda tudo no banco `keycloak` do mesmo Postgres do app, no
volume `postgres_data`. **Não apague esse volume**, que leva junto o banco `akpedia`. Para o realm
ser importado de novo do `keycloak/akpedia-realm.json`, apague só o banco do Keycloak:

```bash
docker compose stop keycloak
docker compose exec db dropdb -U akpedia keycloak
docker compose up -d --build keycloak
```

O `keycloak-db-init` recria o banco vazio, e o `--build` copia o arquivo do realm atual para a imagem.
Isso apaga tudo o que só existe no Keycloak: usuários, senhas e sessões do realm `akpedia`, e o admin
do realm `master`, recriado com o `KEYCLOAK_ADMIN_PASSWORD` do `.env`.

### Fila de indexação (Redis)

| Variável                      | Default                | Para que serve                                                       |
|-------------------------------|------------------------|----------------------------------------------------------------------|
| `REDIS_HOST`                  | `localhost`            | Host do Redis                                                        |
| `REDIS_PORT`                  | `6379`                 | Porta do Redis                                                       |
| `REDIS_PASSWORD`              | (vazio)                | Senha do Redis, se houver                                            |
| `REDIS_TIMEOUT`               | `2s`                   | Tempo máximo de um comando                                           |
| `DOCUMENTS_STREAM`            | `documents:processing` | Stream onde entra o id de cada documento aprovado                    |
| `WORKER_GROUP`                | `ml-workers`           | Consumer group dos workers do akpedia-ml                             |
| `PROCESSING_RETRY_INTERVAL`   | `30s`                  | De quanto em quanto tempo reenviar aprovados que não chegaram à fila |
| `PROCESSING_RETRY_GRACE`      | `1m`                   | Quanto esperar depois da aprovação antes de reenviar                 |
| `PROCESSING_RETRY_BATCH_SIZE` | `50`                   | Quantos documentos uma rodada reenvia no máximo                      |

`DOCUMENTS_STREAM` e `WORKER_GROUP` são um contrato com o worker do akpedia-ml, que usa as mesmas
variáveis: mudar só de um lado deixa mensagens que ninguém lê.

| Variável             | Default | Para que serve |
|----------------------|---------|----------------|
| `INTERNAL_API_TOKEN` | (vazio) | Segredo que o worker manda no header `X-Internal-Token` das [rotas internas](#indexação-pelo-worker-rotas-internas); o mesmo valor do `INTERNAL_API_TOKEN` do worker |

Vazio, toda chamada a `/internal` responde 401 e nenhum documento aprovado é indexado; o servidor
avisa disso no log ao subir. O `docker-compose.yml` e o `akpedia run` do dev container caem em
`akpedia-dev-internal-token`, o mesmo valor que o lado do akpedia-ml usa; fora do ambiente local,
defina um valor de verdade nos dois.

Um documento aprovado é entregue ao worker de indexação do akpedia-ml por uma Redis Stream (ver
[Aprovar ou devolver](#aprovar-ou-devolver)). No `docker-compose.yml` o Redis sobe como serviço
`redis` (imagem `redis:7-alpine`, com AOF ligado para a fila sobreviver a um restart), publicado
na porta 6379 e na rede `akpedia-net`, onde o compose do akpedia-ml o alcança como
`akpedia-redis`. Suba este compose antes do akpedia-ml, porque é ele que cria a rede. Rodando fora
do Docker:

```bash
docker run --rm -p 6379:6379 redis:7-alpine
```

**O servidor precisa do Redis para subir.** No startup ele cria a stream e o consumer group
(`XGROUP CREATE ... MKSTREAM`, idempotente). Se o Redis não responder, a aplicação não sobe: um
servidor que não consegue enfileirar documentos não deve parecer saudável. Se o Redis cair com o
servidor já no ar, as aprovações continuam sendo gravadas e entram na fila quando ele voltar.

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

Quem usa o `EmbeddingClient` hoje é a busca, que chama `embedQuery` para vetorizar o texto
pesquisado. O upload não o chama mais: o documento fica aguardando aprovação e só é indexado
(`embedDocument`) depois de aprovado — ver [Documentos](#documentos).

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

O documento é salvo **aguardando aprovação**: `status: PENDING_APPROVAL` e
`processing_status: PENDING`. O upload não chama o akpedia-ml e não grava nada em `embeddings` —
a indexação para busca só acontece depois que o documento é aprovado, para não gastar
processamento com o que ainda pode ser rejeitado. Enquanto isso o documento já pode ser aberto e
visualizado (é assim que o aprovador o lê), mas não aparece na busca nem na lista de recentes.
Se a conversão para PDF falhar, nada é salvo (ver status de erro abaixo). O autor é o usuário
logado.

| Campo (multipart) | Obrigatório | Descrição                                             |
|--------------------|:-----------:|--------------------------------------------------------|
| `file`             | sim         | arquivo a enviar                                        |
| `categoryId`       | sim         | id de uma categoria existente                            |
| `name`             | não         | nome do documento; default é o nome do arquivo com `.pdf` |
| `description`      | não         | descrição livre                                          |
| `suggestedCategoryId` | não      | id da categoria sugerida pelo classificador; precisa ser uma das categorias do catálogo |
| `categoryConfidence`  | não      | confiança da sugestão, de 0 a 1; só é aceita junto com `suggestedCategoryId` |

```bash
curl -X POST localhost:8080/api/v1/documents \
  -F "file=@relatorio.docx" \
  -F "categoryId=1"
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
  "status": "PENDING_APPROVAL",
  "processing_status": "PENDING",
  "processing_error": null,
  "suggested_category_id": 2,
  "category_confidence": 0.87,
  "created_at": "2026-09-21T20:24:00Z"
}
```

`suggested_category_id` e `category_confidence` vêm `null` quando o upload não trouxe sugestão.

#### Status de erro

| Situação                                  | Status | `code`                        |
|--------------------------------------------|--------|-------------------------------|
| arquivo vazio, ou parâmetro ausente/inválido | 400    | `invalid_request`             |
| `categoryConfidence` fora de 0 a 1, ou sem `suggestedCategoryId` | 400 | `invalid_request` |
| categoria informada ou sugerida não existe  | 404    | `category_not_found`          |
| usuário informado não existe                | 404    | `user_not_found`               |
| arquivo acima de 25 MB                      | 413    | `file_too_large`              |
| Gotenberg recebeu mas recusou o arquivo     | 422    | `pdf_conversion_rejected`     |
| Gotenberg respondeu algo ilegível           | 502    | `pdf_conversion_failed`       |
| **Gotenberg fora do ar ou além do timeout** | 503    | `pdf_conversion_unavailable`  |

### Minhas submissões

`GET /api/v1/documents/submissions` devolve os documentos que o usuário logado enviou, do mais recente
para o mais antigo, cada um com um status **derivado** para o autor em vez do status armazenado:

| `submission_status` | No banco                                          | O que significa para o autor                    |
|---------------------|---------------------------------------------------|-------------------------------------------------|
| `UNDER_REVIEW`      | `PENDING_APPROVAL`, em qualquer `processing_status` | esperando um aprovador                         |
| `PROCESSING`        | `APPROVED` com `processing_status` `PENDING` ou `PROCESSING` | aprovado, mas a busca ainda não o encontra |
| `PUBLISHED`         | `APPROVED` com `processing_status: COMPLETED`     | aprovado e indexado, os colegas já encontram    |
| `INDEXING_FAILED`   | `APPROVED` com `processing_status: FAILED`        | aprovado, mas a indexação falhou e a busca não vai encontrá-lo |
| `RETURNED`          | `REJECTED`                                        | devolvido; o `review_comment` diz o motivo      |

Aprovar não basta para o documento aparecer na busca: a indexação só começa depois da aprovação.
Por isso `APPROVED` se divide em três — chamar de `PUBLISHED` um documento que ainda está sendo
indexado faria o autor avisar os colegas de algo que eles ainda não encontram. E a falha tem
estado próprio porque `PROCESSING` prometeria uma coisa que não vai acontecer.

Diferente da busca e da listagem de recentes, esta rota **mostra documento não aprovado de
propósito** — são os do próprio autor, e dizer o que ainda está em revisão é o objetivo dela. O
que ela não mostra é documento de outra pessoa.

Documento em `DRAFT` nunca saiu das mãos do autor e `ARCHIVED` está fora de circulação: nenhum dos
dois é uma submissão, então não aparecem aqui.

| Parâmetro     | Obrigatório | Descrição                                                      |
|---------------|:-----------:|-----------------------------------------------------------------|
| `page`        | não         | página, começando em 0; default 0                               |
| `size`        | não         | resultados por página, de 1 a 50; default 10                    |
| `categoryIds` | não         | um ou mais ids de categoria (`categoryIds=1&categoryIds=3`, ou `categoryIds=1,3`); sem nenhum, lista todas as categorias |

```bash
curl "localhost:8080/api/v1/documents/submissions?page=0&size=10&categoryIds=1,3"
```

```json
{
  "content": [
    {
      "document_id": 7,
      "name": "contrato.pdf",
      "description": null,
      "category": "Juridico",
      "submission_status": "RETURNED",
      "review_comment": "Falta a secao de revisao A.",
      "reviewed_at": "2026-10-16T12:00:00Z",
      "created_at": "2026-10-16T11:20:00Z",
      "updated_at": null
    },
    {
      "document_id": 5,
      "name": "manual.pdf",
      "description": "manual tecnico",
      "category": "Manuais",
      "submission_status": "PUBLISHED",
      "created_at": "2026-10-15T09:00:00Z",
      "updated_at": "2026-10-15T18:30:00Z"
    }
  ],
  "page": 0,
  "size": 10,
  "total_elements": 23,
  "total_pages": 3
}
```

O `review_comment` e o `reviewed_at` só aparecem em documento devolvido — **ausentes**, não nulos.
Os outros campos vêm sempre, `null` quando não têm valor, então toda linha tem as mesmas chaves.
Um comentário que o aprovador deixou e aprovou o documento assim mesmo não vem: ao lado de
`PUBLISHED` ele se leria como se o documento ainda estivesse esperando correção.

#### Status de erro

| Situação                                  | Status | `code`            |
|--------------------------------------------|--------|-------------------|
| `page` negativo, ou `size` fora de 1 a 50  | 400    | `invalid_request` |

### Sugerir título, descrição e categoria (antes de enviar)

`POST /api/v1/documents/analyze` recebe o arquivo e devolve o título, a descrição e a categoria
que o akpedia-ml leu do documento, para a tela de upload já vir preenchida. **Nada é persistido**: o arquivo é lido,
convertido em memória e descartado, então uma análise abandonada não deixa documento nem linha
nenhuma atrás.

Pela arquitetura, a tela nunca fala com o akpedia-ml: ela posta aqui, e é o servidor que valida o
formato, converte e chama o classificador.

| Campo (multipart) | Obrigatório | Descrição                                   |
|--------------------|:-----------:|----------------------------------------------|
| `file`             | sim         | arquivo a analisar: PDF, DOC/DOCX ou XLS/XLSX |

A lista de formatos é **mais estreita que a do upload**, que aceita tudo que o Gotenberg
converte. Arquivos que não são PDF são convertidos antes de ir ao classificador.

```bash
curl -X POST localhost:8080/api/v1/documents/analyze \
  -F "file=@manual.docx"
```

```json
{
  "title": "Manual de Manutenção do Trem de Pouso",
  "description": "Este manual descreve os procedimentos de inspeção...",
  "suggested_category_id": 2,
  "confidence": 0.873
}
```

O `suggested_category_id` é exatamente o que o `POST /api/v1/documents` recebe como
`suggestedCategoryId`, e o `confidence` como `categoryConfidence` — a tela devolve os dois sem
precisar consultar nada.

O `title` vem dos metadados do PDF, da linha de maior fonte da primeira página ou, na falta dos
dois, do nome do arquivo. A `description` é o primeiro parágrafo com conteúdo do documento, ou
`null` quando não há nenhum.

**Sem sugestão de categoria** (o akpedia-ml ainda não tem a base de categorias), a resposta é
`200` com `suggested_category_id` e `confidence` `null`. Não é erro: o título e a descrição vêm do
mesmo jeito, e a tela deixa a categoria para o usuário escolher.

```json
{
  "title": "manual",
  "description": null,
  "suggested_category_id": null,
  "confidence": null
}
```

#### Status de erro

| Situação                                      | Status | `code`                            |
|------------------------------------------------|--------|-----------------------------------|
| arquivo vazio, ou parte `file` ausente          | 400    | `invalid_request`                 |
| arquivo acima de 25 MB                          | 413    | `file_too_large`                  |
| formato fora de PDF, DOC/DOCX e XLS/XLSX        | 415    | `unsupported_format`              |
| Gotenberg recebeu mas recusou o arquivo         | 422    | `pdf_conversion_rejected`         |
| categoria sugerida não existe no catálogo       | 502    | `unknown_suggested_category`      |
| **classificador fora do ar ou além do timeout** | 503    | `category_suggestion_unavailable` |

O 415 traz a lista aceita no corpo, para a tela não precisar manter uma cópia que divergiria da
do servidor:

```json
{
  "code": "unsupported_format",
  "message": "O formato de 'apresentacao.pptx' nao e aceito. Envie um arquivo PDF, DOC, DOCX, XLS ou XLSX.",
  "supported_extensions": ["pdf", "doc", "docx", "xls", "xlsx"]
}
```

> O 503 tem `code` próprio, diferente do `embedding_service_unavailable` das outras rotas. É de
> propósito: esse é o único caso em que a tela deve **liberar a escolha manual da categoria** e
> seguir com o envio, em vez de mostrar erro e parar. Já o 502 significa que o classificador e o
> catálogo divergiram — aí alguém precisa reconciliar os dois.

### Fila de aprovação

`GET /api/v1/documents/pending?page=&size=` lista os documentos em `PENDING_APPROVAL`, **o upload
mais antigo primeiro** — a fila é atendida na ordem de chegada. Empates no `created_at` são
desfeitos pelo `id`, para as páginas não trocarem itens entre si. `page` começa em 0 (padrão 0) e
`size` vai de 1 a 50 (padrão 20); fora disso, 400 `invalid_request`.

Cada documento leva quem enviou e de qual setor, quando, a `description` e a `category` que o
autor mandou — o que o formulário de revisão mostra preenchido —, e a categoria sugerida pelo
classificador com a confiança — os dois `null` quando o upload não trouxe sugestão. A
`description` é `null` quando o upload não trouxe nenhuma, e a `category` pode ser diferente da
sugestão, quando o autor a corrigiu. O arquivo
de um documento da fila abre normalmente por `/documents/{id}/file` (ver abaixo).

```bash
curl 'localhost:8080/api/v1/documents/pending?page=0&size=2'
```

```json
{
  "content": [
    {
      "id": 12,
      "name": "manual-vpn.pdf",
      "description": "Como configurar a VPN da empresa",
      "creator_name": "Mariana Costa",
      "sector_name": "Engenharia",
      "created_at": "2026-10-13T19:06:15.123Z",
      "category": { "id": 1, "name": "Tecnico" },
      "suggested_category": { "id": 1, "name": "Tecnico" },
      "confidence": 0.870
    },
    {
      "id": 15,
      "name": "politica-ferias.pdf",
      "description": null,
      "creator_name": "Ana Souza",
      "sector_name": "Administrativo",
      "created_at": "2026-10-14T11:20:02.481Z",
      "category": { "id": 3, "name": "Juridico" },
      "suggested_category": null,
      "confidence": null
    }
  ],
  "page": 0,
  "size": 2,
  "total_elements": 5,
  "total_pages": 3
}
```

### Aprovar ou devolver

É a decisão do aprovador sobre um documento da fila. As duas rotas só valem para documento em
`PENDING_APPROVAL`; em qualquer outro status respondem **409** `document_not_pending_approval` — na
prática, alguém já decidiu. O documento fica travado (`SELECT … FOR UPDATE`) enquanto a decisão é
gravada, então dois aprovadores ao mesmo tempo não aprovam duas vezes nem aprovam e devolvem o
mesmo documento.

O aprovador registrado é o usuário logado, que precisa do papel `APPROVER` ou `ADMIN`.

#### Aprovar

`POST /api/v1/documents/{id}/approve` aprova e, de quebra, deixa o aprovador corrigir o que o autor
mandou. O corpo é opcional, e cada campo também: o que vier substitui, o que faltar fica como está.

| Campo         | Descrição                                               |
|---------------|---------------------------------------------------------|
| `name`        | novo título; se vier, não pode ser em branco (até 255)  |
| `description` | nova descrição; em branco, remove a descrição           |
| `categoryId`  | categoria correta; precisa existir no catálogo          |

O documento vira `status: APPROVED` com `processing_status: PROCESSING`, e grava `approver_id`,
`approved_at` e `reviewed_at`. Depois que a transação **comita**, o id dele entra na Redis Stream
`DOCUMENTS_STREAM` (`documents:processing`, via `XADD`), de onde o worker de indexação do
akpedia-ml lê pelo consumer group `ml-workers`:

```
XADD documents:processing * documentId 7
```

Publicar só depois do commit é o que impede que o worker indexe uma aprovação que deu rollback.
Mas abre o caso contrário: a aprovação é gravada e o envio não acontece — o Redis estava fora do
ar, ou o servidor caiu entre o commit e o `XADD`. Para nenhum documento ficar preso em
`PROCESSING` esperando uma mensagem que nunca foi enviada, **a própria tabela `documents` serve de
outbox**:

- `processing_enqueued_at` só é preenchido depois que o Redis aceitou o id;
- a cada `PROCESSING_RETRY_INTERVAL` (30s), o servidor procura documentos `APPROVED` +
  `PROCESSING` com essa coluna vazia, aprovados há mais de `PROCESSING_RETRY_GRACE` (1min), e os
  reenvia, os mais antigos primeiro;
- a carência existe para não reenviar um documento cujo envio logo após o commit ainda está em
  andamento; e uma rodada para no primeiro documento que o Redis recusar, porque os outros seriam
  recusados também.

A aprovação nunca é desfeita por causa do Redis, e o aprovador recebe 200 mesmo que o envio
falhe: ele acontece depois, sozinho.

**A entrega é "pelo menos uma vez".** Se o servidor cair depois de o Redis aceitar o id e antes de
gravar `processing_enqueued_at`, o documento é enviado de novo, e o worker o indexa outra vez.
Por isso gravar o resultado da indexação é idempotente do lado do servidor (ver
[Indexação pelo worker](#indexação-pelo-worker-rotas-internas)).

> Enquanto está em `PROCESSING`, o `/file` do documento responde 409 (ver [Restrições](#restrições)),
> até o worker terminar a indexação.

```bash
# sem correções
curl -X POST localhost:8080/api/v1/documents/7/approve

# corrigindo título e categoria
curl -X POST localhost:8080/api/v1/documents/7/approve \
  -H 'Content-Type: application/json' \
  -d '{"name": "Manual de calibracao", "categoryId": 4}'
```

```json
{
  "id": 7,
  "name": "Manual de calibracao",
  "description": "manual tecnico",
  "category_id": 4,
  "status": "APPROVED",
  "processing_status": "PROCESSING",
  "approver_id": 1,
  "approved_at": "2026-10-20T14:00:00Z",
  "review_comment": null,
  "reviewed_at": "2026-10-20T14:00:00Z"
}
```

#### Devolver

`POST /api/v1/documents/{id}/reject` devolve o documento ao autor. O `comment` é obrigatório: é o
que o autor lê ao lado do documento em [Minhas submissões](#minhas-submissões).

O documento vira `status: REJECTED` com `review_comment` e `reviewed_at`. O **PDF armazenado é
apagado** (`document_files`), mas a linha do documento fica — é ela que mostra ao autor o que voltou
e por quê.

```bash
curl -X POST localhost:8080/api/v1/documents/7/reject \
  -H 'Content-Type: application/json' \
  -d '{"comment": "Falta a secao de revisao A."}'
```

```json
{
  "id": 7,
  "name": "manual.pdf",
  "description": null,
  "category_id": 3,
  "status": "REJECTED",
  "processing_status": "PENDING",
  "approver_id": null,
  "approved_at": null,
  "review_comment": "Falta a secao de revisao A.",
  "reviewed_at": "2026-10-20T14:00:00Z"
}
```

#### Status de erro

| Situação                                                     | Status | `code`                          |
|---------------------------------------------------------------|--------|---------------------------------|
| corpo malformado; `name` em branco ou longo demais            | 400    | `invalid_request`               |
| devolução sem corpo, sem `comment` ou com `comment` em branco | 400    | `invalid_request`               |
| documento não existe                                          | 404    | `document_not_found`            |
| `categoryId` da correção não existe                           | 404    | `category_not_found`            |
| documento não está em `PENDING_APPROVAL`                      | 409    | `document_not_pending_approval` |

### Indexação pelo worker (rotas internas)

O worker do akpedia-ml não acessa o banco: ele lê o id da Redis Stream, baixa o PDF daqui, gera os
embeddings e devolve o resultado (ou a falha) para cá. Quem grava, libera o documento para a busca
e confirma a mensagem (`XACK`) é o servidor.

Todas as rotas abaixo exigem o header `X-Internal-Token` igual ao
[`INTERNAL_API_TOKEN`](#fila-de-indexação-redis); sem ele, ou com outro valor, respondem **401**
`invalid_internal_token` antes de chegar ao controller.

| Rota | O que faz |
|------|-----------|
| `GET /internal/documents/{id}/file` | Devolve o PDF como anexo, com o nome no `Content-Disposition`. Diferente do `/api/v1/.../file`, responde com o documento em `PROCESSING`, que é justamente quando o worker o lê |
| `POST /internal/documents/{id}/embeddings` | Numa transação, apaga os embeddings do documento e grava os novos; marca `processing_status: COMPLETED`, `processed_at` e limpa `processing_error`. Depois do commit, dá `XACK` no `messageId` |
| `POST /internal/documents/{id}/processing-failure` | Marca `processing_status: FAILED` com `processing_error` = `code: message`, e dá `XACK` no `messageId` |

O corpo de `/embeddings` é a resposta do `POST /api/v1/documents/process` do akpedia-ml mais o
`messageId` da stream; o de `/processing-failure` é `{"code", "message", "messageId"}`.

**O `XACK` vem só depois do commit.** Se viesse antes, um rollback perderia o resultado: a mensagem
sumiria e o documento ficaria preso em `PROCESSING`. Ao contrário, um commit seguido de um `XACK`
que falhou deixa a mensagem pendente, e o worker manda o mesmo resultado de novo depois de
reiniciar. Por isso as duas rotas são **idempotentes**:

- documento já `COMPLETED` responde **200 sem regravar nada** (nem embeddings, nem
  `processed_at`), e confirma a mensagem de novo;
- uma falha que chega depois de um sucesso também é ignorada: o documento continua `COMPLETED`;
- um documento que deu `FAILED` e depois recebe embeddings passa para `COMPLETED`.

O documento fica travado (`SELECT … FOR UPDATE`) enquanto o resultado é gravado, então dois
callbacks ao mesmo tempo não gravam os embeddings duas vezes.

| Situação | Status | `code` | `XACK`? |
|----------|--------|--------|---------|
| sem token, ou token errado | 401 | `invalid_internal_token` | não |
| corpo malformado ou `messageId` fora do formato `<ms>-<seq>` | 400 | `invalid_request` | não |
| vetor que não tem 384 números, ou chunk sem texto | 400 | `invalid_embeddings` | não |
| documento não existe (mais) | 404 | `document_not_found` | sim: não há onde gravar, e pendente ela só voltaria a cada reinício do worker |
| embeddings de documento que não está `APPROVED` | 409 | `document_not_approved` | não |

Num 400 ou 409 o worker reporta a falha em seguida, e é essa chamada que marca `FAILED` e confirma
a mensagem.

```bash
TOKEN=akpedia-dev-internal-token
curl -H "X-Internal-Token: $TOKEN" -o doc.pdf localhost:8080/internal/documents/7/file
curl -X POST localhost:8080/internal/documents/7/processing-failure \
  -H "X-Internal-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"code": "no_extractable_text", "message": "O PDF nao tem texto.", "messageId": "1761234567890-0"}'
```

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
| `processing_status: PROCESSING`                  | 409      | o akpedia-ml está indexando o documento neste momento           |

Os outros `status` (`DRAFT`, `PENDING_APPROVAL`, `APPROVED`) abrem normalmente. Um documento
`REJECTED` não tem mais arquivo — a [devolução](#aprovar-ou-devolver) apaga o PDF —, então
responde 404 `document_file_not_found`.

O 409 é conflito de estado, não recusa: a indexação termina sozinha, então a mesma
requisição passa a funcionar — é o que separa esse caso do 403, que não muda.

**`processing_status: PENDING` abre.** É o estado em que o upload deixa o documento até ele ser
aprovado, e o aprovador precisa ler o documento para decidir.

**`processing_status: FAILED` abre.** O PDF é convertido e salvo no upload, bem antes da etapa
de embeddings — então o binário está íntegro e só a busca não o alcança. Como não existe rota de reindexação, recusar esse documento o trancaria
para sempre por causa de uma etapa que nem tocou no arquivo.

A busca (`GET /api/v1/search`) é mais restrita: além de `processing_status = 'COMPLETED'`, a query
exige `status = 'APPROVED'`. Só documento aprovado é visível — um em revisão ou recusado tem
vetores no banco, e sem esse filtro apareceria para qualquer um. Arquivado também fica de fora,
senão um resultado da pesquisa levaria a um 403 ao ser aberto.

A mesma regra vale para `GET /api/v1/documents`, a listagem recente. O status exigido está escrito
dentro das duas queries, não é parâmetro: numa listagem pública, quem chama poder escolher o que
aparece é exatamente como um documento em revisão chega a quem não devia vê-lo.

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
| documento existe, mas não há arquivo armazenado (ex.: devolvido) | 404 | `document_file_not_found` |
| indexação do akpedia-ml ainda não terminou         | 409    | `document_not_processed`   |
| `Range` pedido não cabe no arquivo                 | 416    | — (sem corpo)              |

---

## Busca

### Endpoint

`GET /api/v1/search?q=<texto>&categoryIds=<ids>&limit=<n>` faz busca semântica nos documentos
**aprovados e indexados**, com filtro opcional por categoria. O texto da consulta é vetorizado pelo
akpedia-ml e comparado por similaridade de cosseno com os `embeddings` salvos. Documento em revisão
(`PENDING_APPROVAL`), recusado (`REJECTED`) ou arquivado não aparece, mesmo tendo vetores — nem na
busca só por categoria.

| Parâmetro     | Obrigatório | Descrição                                                                 |
|---------------|:-----------:|---------------------------------------------------------------------------|
| `q`           | não\*       | palavra, frase ou pergunta                                                |
| `categoryIds` | não\*       | um ou mais ids de categoria (`categoryIds=1&categoryIds=3`, ou `categoryIds=1,3`); filtra os resultados a essas categorias |
| `limit`       | não         | quantos resultados no máximo; default `SEARCH_DEFAULT_LIMIT`, teto `SEARCH_MAX_LIMIT` |

\* Pelo menos um dos dois precisa vir na requisição; sem nenhum, a rota responde `400`.

Com `categoryIds` e sem `q`, a busca é só o filtro: os resultados vêm ordenados pela última
alteração do documento (mais recente primeiro), e `score` volta `null`, já que não há texto para
comparar. Com `q`, `categoryIds` só restringe quais documentos entram na comparação; os resultados
continuam ordenados por relevância.

Só entram documentos com `processing_status: COMPLETED` e `status: APPROVED` — sem vetores não há
o que comparar. Cada documento aparece **uma vez só**, representado pelo trecho que mais se
aproximou da consulta (ou, sem `q`, pelo primeiro trecho do documento, como excerto), e os
resultados vêm ordenados por relevância ou por data, conforme acima. Trechos abaixo de `SEARCH_MINIMUM_SCORE` não voltam quando
há `q`, então uma busca sem nada parecido responde `200` com lista vazia — não 404.

A lista de categorias para montar o filtro vem de `GET /api/v1/categories`, que responde o
catálogo inteiro, ordenado por nome:

```json
[
  { "id": 1, "name": "Engenharia", "description": "Documentos de engenharia" },
  { "id": 2, "name": "Normativa", "description": null }
]
```

### Formato do resultado

Cada item da lista tem estes sete campos, sempre presentes (`description` vem `null` quando o
documento foi enviado sem uma, e `score` vem `null` quando a busca não teve `q`):

| Campo           | Tipo    | O que é                                                                    |
|-----------------|---------|----------------------------------------------------------------------------|
| `document_id`   | número  | identificador do documento, usado nas outras rotas de documento             |
| `name`          | texto   | título do documento                                                        |
| `description`   | texto   | descrição informada no upload, ou `null`                                   |
| `mime_type`     | texto   | formato do arquivo armazenado; hoje sempre `application/pdf`                |
| `score`         | número  | similaridade entre a consulta e o trecho, de -1 a 1 (1 = idêntico); `null` sem `q` |
| `matched_chunk` | texto   | o trecho que casou com a busca, aparado (ver abaixo); o primeiro trecho do documento quando a busca não teve `q` |
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

| Situação                                                      | Status | `code`                     |
|----------------------------------------------------------------|--------|---------------------------|
| nem `q` nem `categoryIds`, ou `limit` fora do teto             | 400    | `invalid_request`          |
| akpedia-ml respondeu algo que não dá para usar                 | 502    | `embedding_service_error`  |
| akpedia-ml fora do ar ou além do timeout           | 503    | `embedding_service_unavailable` |

---

## Chat

`POST /api/v1/chat` responde uma pergunta com uma resposta curta e as fontes, escrita só a partir
dos documentos que o usuário pode ver.

```json
// POST /api/v1/chat
{ "question": "Qual o prazo de reembolso de despesas?" }

// 200
{
  "answer": "O reembolso é feito em até 7 dias corridos [1].",
  "found": true,
  "sources": [ { "document_id": 42, "name": "Manual de Reembolso", "category": "Financeiro" } ]
}
```

1. A pergunta vira vetor no akpedia-ml, como na busca.
2. O server procura os trechos mais próximos (`EmbeddingRepository.findTopChunks`) entre os
   documentos **aprovados e indexados** nas categorias que o setor do usuário vê, as mesmas da
   busca. Diferente da busca, vários trechos do mesmo documento podem entrar. Ficam até
   `CHAT_MAX_PASSAGES`, nenhum abaixo de `CHAT_MINIMUM_SCORE`. A comparação é exata, sem o índice
   HNSW: ele filtra depois de achar os vizinhos mais próximos, e trechos de documentos em revisão ou
   de categorias proibidas na frente esconderiam os válidos.
3. **Sem nenhum trecho acima do limite**, a resposta é `found: false` com `sources` vazio, **sem
   chamar o modelo**. Um usuário cujo setor não vê nenhuma categoria nem chega ao akpedia-ml.
4. Com trechos, o akpedia-ml (`POST /api/v1/chat/answer`) escreve a resposta, marcando cada
   afirmação com o número do trecho (`[1]`). Ele não lê o banco nem checa permissões: só vê o que
   o server mandou.
5. `sources` são os documentos citados, na ordem da primeira citação, montados a partir dos trechos
   enviados: um id que o akpedia-ml devolva e não estava entre eles é descartado.

`found` também volta `false` quando os trechos não respondem a pergunta. A pergunta não é gravada: a
auditoria guarda a requisição, nunca o corpo.

#### Status de erro

| Situação                                                       | Status | `code`             |
|----------------------------------------------------------------|--------|--------------------|
| `question` ausente, em branco ou com mais de 1000 caracteres   | 400    | `invalid_request`  |
| akpedia-ml fora do ar, além do timeout ou o modelo falhou      | 503    | `chat_unavailable` |

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
