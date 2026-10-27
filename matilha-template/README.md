# matilha-template

Ponto de partida para um novo serviço do Matilha. Tem a mesma base do `matilha-account` e do `matilha-transfer`: Spring Boot 4, Java 25, PostgreSQL com Flyway, Problem Details, Actuator, OpenAPI no perfil `dev`, Dockerfile e compose.

O recurso `Example` (`POST /examples`, `GET /examples/{id}`, `GET /examples`) existe só para mostrar cada camada funcionando. Troque pelo recurso do novo serviço.

O template não entra no `docker-compose.yml` da raiz nem tem workflow de CI. Isso é feito no serviço criado a partir dele.

## Criar um serviço a partir do template

Os exemplos usam `matilha-card` / `card` / `CARD` como nome do novo serviço.

1. **Copiar a pasta** `matilha-template` para `matilha-card` (sem a pasta `target/`).
2. **Pacote**: renomear `com.matilha.template` para `com.matilha.card` em `src/main` e `src/test`, e `TemplateApplication` para `CardApplication`.
3. **pom.xml**: trocar `artifactId` e `name` para `matilha-card`. Se o serviço chamar outro, adicionar `spring-boot-starter-restclient` e `spring-boot-starter-restclient-test` (como no transfer).
4. **Variáveis de ambiente**: trocar o prefixo `TEMPLATE_` por `CARD_` no `application.yml`, `compose.yaml` e `.env.example`.
5. **Portas**: escolher uma porta HTTP e uma porta de banco livres e atualizar `application.yml` (`server.port`), `Dockerfile` (`EXPOSE`) e `compose.yaml`.

   | Serviço | HTTP | Banco |
   |---|---|---|
   | account | 8081 | 5432 |
   | transfer | 8082 | 5433 |
   | template | 8083 | 5434 |

6. **Nomes**: no `application.yml` trocar `spring.application.name`, `hikari.pool-name` e `info.app`. No `compose.yaml` trocar os nomes dos serviços, dos containers e do volume. No `OpenApiConfig` trocar o nome do bean, o título e a descrição.
7. **Recurso**: substituir `Example` (entidade, DTOs, repository, service, controller, exceção de não encontrado e testes) e a migração `V1__create_examples.sql` pelo recurso real.
8. **Compose da raiz**: adicionar `matilha-card/compose.yaml` no `include` do `docker-compose.yml`.
9. **CI**: copiar `.github/workflows/transfer-ci.yml` para `card-ci.yml` e trocar `matilha-transfer` por `matilha-card`.
10. **README da raiz**: documentar o serviço novo.

## Rodando

```bash
cd matilha-template
cp .env.example .env   # e preencha usuário e senha
docker compose up -d template-db
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Ou tudo em container: `docker compose up --build`.

No perfil `dev`, o Swagger fica em http://localhost:8083/swagger-ui.html e o health em http://localhost:8083/actuator/health.

## Testes

```bash
./mvnw verify
```

O Docker precisa estar rodando, porque o `ExamplePersistenceTest` sobe um PostgreSQL com Testcontainers.

- **Entidade** (`ExampleTest`): validações do construtor
- **Persistência** (`ExamplePersistenceTest`): migração contra PostgreSQL real e constraints
- **Service** (`ExampleServiceTest`): regras com o repository mockado (Mockito)
- **Controller** (`ExampleControllerTest`): status HTTP, validação, paginação e erros (MockMvc)
- **Contexto** (`TemplateApplicationTests`): a aplicação sobe com H2 no perfil `test`

## Estrutura

```
matilha-template/
├── src/main/java/com/matilha/template/
│   ├── config/        # OpenAPI e paginação padrão
│   ├── controller/    # Endpoints REST
│   ├── dto/           # Records de request/response
│   ├── entity/        # Entidades JPA
│   ├── exception/     # Exceções e handler global (Problem Details)
│   ├── repository/    # Repositórios Spring Data
│   └── service/       # Regras de negócio
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   └── db/migration/  # Migrações versionadas do Flyway
├── Dockerfile
└── compose.yaml       # PostgreSQL + serviço
```
