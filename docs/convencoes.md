# Convenções do Matilha

Este documento registra **como** o código do Matilha é escrito. O **porquê** das decisões de arquitetura está nos [ADRs](adr/README.md). Quando as duas coisas se cruzam, a convenção aponta para o ADR.

Vale para qualquer pessoa que contribuir com o projeto. Antes de criar algo novo, procure um exemplo que já existe no mesmo serviço e siga o mesmo formato. Se uma convenção precisar mudar, mude aqui primeiro e depois no código.

## Mapa do projeto

| Pasta | O que é | Porta |
|---|---|---|
| [matilha-gateway](../matilha-gateway/) | Entrada da API, valida o token e roteia por lista de liberação | 8080 |
| [matilha-account](../matilha-account/) | Contas e saldo. Único dono do saldo | 8081 |
| [matilha-transfer](../matilha-transfer/) | Orquestra transferências, outbox e reconciliador | 8082 |
| [matilha-notification](../matilha-notification/) | Consome os eventos do Kafka e grava notificações | 8083 |
| [keycloak](../keycloak/) | Login e emissão de tokens, realm versionado | 8180 |
| [infra](../infra/) | Kafka e Kafka UI do compose | 9092, 8090 |
| [k8s](../k8s/) | Manifests do kind, scripts de deploy e provas | |
| [postman](../postman/) | Coleção com roteiro e coleção crua de endpoints | |
| [docs/adr](adr/) | Decisões de arquitetura | |

Cada serviço é um projeto Maven independente, com o próprio `pom.xml`, `mvnw`, `Dockerfile`, `compose.yaml`, banco e workflow de CI. Não existe pom pai nem biblioteca compartilhada ([ADR-0009](adr/0009-codigo-duplicado-sem-biblioteca-comum.md)): um código transversal, como o `GlobalExceptionHandler` ou o `SecurityConfig`, é duplicado em cada serviço e mantido igual à mão.

## Stack

Java 25, Spring Boot 4.1, Spring Security (OAuth2 Resource Server), Spring Data JPA, Flyway, PostgreSQL 17, Spring Kafka, RestClient, springdoc-openapi, Lombok, Spring Cloud Gateway Server Web MVC no gateway, Keycloak 26. Testes com JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers e Awaitility.

Tudo é servlet (Web MVC). Não use WebFlux nem tipos reativos.

## Idioma

| O quê | Idioma |
|---|---|
| Nomes no código (classes, métodos, variáveis, colunas, tabelas, rotas, campos do JSON) | Inglês |
| Mensagens de exceção, `detail` do Problem Details e logs | Inglês |
| Nomes dos métodos de teste | Inglês (`shouldRejectSameKeyFromAnotherUser`) |
| Comentários e Javadoc nos testes | Português |
| READMEs, ADRs, este documento, mensagens de commit, scripts `.ps1` e coleções do Postman | Português do Brasil |

## Código de produção (`src/main`)

### Comentários

Sem comentários no meio do código de produção. O nome da classe e do método diz **o que** faz. O **porquê** vai para o README do serviço ou para um ADR. Se um trecho parece precisar de um comentário para ser entendido, primeiro tente um nome melhor ou extrair um método.

### Pacotes

`com.matilha.<serviço>`, com as pastas:

| Pacote | O que fica nele |
|---|---|
| `config` | `@Configuration`: segurança, Kafka, OpenAPI, paginação, agendamento, e o `ProblemDetailSecurityHandler` |
| `controller` | `@RestController`, só HTTP: lê a requisição, chama o service e monta a resposta |
| `service` | Regras e transações |
| `entity` | Entidades JPA e os enums que elas usam (`TransferStatus`, `FailureCode`) |
| `repository` | Interfaces do Spring Data |
| `dto` | Records de entrada, saída e eventos |
| `exception` | Exceções, o `ErrorCode` e o `GlobalExceptionHandler` |
| `client` | Chamadas HTTP a outro serviço (`AccountClient`) |
| `component` | Tarefas agendadas e consumidores (`TransferReconciler`, `OutboxPublisher`, listener do Kafka) |

### Controllers

- `@RestController`, `@RequiredArgsConstructor` e `@RequestMapping` com o recurso no plural (`/transfers`).
- Recebem `@RequestBody @Valid` e, nas rotas autenticadas, um parâmetro `Authentication`. O usuário é `authentication.getName()`, que é o `sub` do token.
- Criação devolve o header `Location`.
- Nenhuma regra de negócio no controller. Ele só traduz o resultado do service para status HTTP.

### DTOs

- Sempre `record`, com o sufixo `Dto`: `CreateTransferRequestDto`, `TransferResponseDto`, `AccountTransferRequestDto`.
- Validação com Bean Validation no próprio record. Os formatos repetidos entre serviços são escritos do mesmo jeito:
  - número da conta: `@NotBlank @Pattern(regexp = "\\d{5}")`;
  - dinheiro: `BigDecimal` com `@NotNull @Positive @Digits(integer = 17, fraction = 2)`.
- O dono de um recurso **nunca** entra num DTO de entrada da API pública. Ele vem do token ([ADR-0014](adr/0014-keycloak-e-dono-da-conta.md)). Só os DTOs do `/internal/**` carregam `ownerId`, porque quem chama é outro serviço.
- Campo novo num DTO de resposta ou de evento entra **no fim** do record, para não trocar a ordem dos parâmetros posicionais nos testes.

### Entidades

- Lombok: `@Getter`, `@ToString` e `@NoArgsConstructor(access = AccessLevel.PROTECTED)`. **Sem `@Setter` e sem `@Data`.**
- O estado muda só por métodos com nome de negócio (`complete`, `fail`, `claimUntil`, `isOwnedBy`), que validam as regras e lançam `BusinessException`. O construtor público valida tudo o que é obrigatório.
- Ids `UUID` com `@GeneratedValue(strategy = GenerationType.UUID)`. Datas em `Instant`, com `@CreationTimestamp` e `@UpdateTimestamp`.
- Enums gravados com `@Enumerated(EnumType.STRING)`, nunca pela posição.
- Campos que não mudam depois de criados: `@Column(updatable = false)`. Vale para o dono (`ownerId`), contas e valor de uma transferência.
- Dinheiro em `BigDecimal`, `precision = 19, scale = 2`. Compare com `compareTo`, nunca com `equals`.
- `@Version` para lock otimista onde dois processos podem gravar a mesma linha.

### Erros

Formato Problem Details (RFC 9457) em todas as respostas de erro ([ADR-0007](adr/0007-codigos-de-erro-entre-servicos.md)).

- Cada serviço tem um `GlobalExceptionHandler` que estende `ResponseEntityExceptionHandler`, e um enum `ErrorCode`.
- Quando o cliente ou outro serviço precisa reagir ao erro, a resposta leva a propriedade `code` com um valor do `ErrorCode`. **O `code` é contrato**: pode-se acrescentar um valor novo, mas nunca renomear nem remover um. Ninguém decide nada pelo texto do `detail`.
- Status usados:

| Situação | Status |
|---|---|
| Validação do Bean Validation | `400`, com `errors` por campo |
| Sem token ou token inválido | `401`, `code: UNAUTHENTICATED`, header `WWW-Authenticate: Bearer` |
| Sem o papel necessário | `403`, `code: ACCESS_DENIED` |
| Recurso não existe **ou é de outra pessoa** | `404` (ou página vazia em listas) |
| Lock otimista ou violação de constraint | `409` |
| Regra de negócio | `422` (`HttpStatus.UNPROCESSABLE_CONTENT`) |
| Serviço chamado fora do ar (gateway) | `503` |
| Erro inesperado | `500` com `detail` genérico. O detalhe vai só para o log, com `log.error` |

- O 401 e o 403 do Spring Security passam pelo `ProblemDetailSecurityHandler`, que repassa a exceção ao `GlobalExceptionHandler` para sair no mesmo formato.

### Segurança

- Cada serviço tem o próprio `SecurityConfig` e valida o token sozinho. Nenhum serviço confia num header colocado pelo gateway ([ADR-0014](adr/0014-keycloak-e-dono-da-conta.md)).
- Stateless, sem CSRF, e a cadeia termina em `anyRequest().authenticated()`. Uma rota nova nasce protegida, e liberar uma rota é uma linha explícita no `SecurityConfig`.
- Públicos: `/actuator/health/**`, `/actuator/info` e as rotas do Swagger (que só existe com o perfil `dev`). No account, também o `/internal/**`, que é uma lacuna conhecida e está no roadmap.
- O usuário é sempre o `sub` do token. A regra de dono é aplicada **na consulta** (`findByIdAndOwnerId`, `findByAccountNumberAndOwnerId`), e não com um `if` depois de buscar. Assim o recurso de outra pessoa simplesmente não é encontrado.
- Papéis do Keycloak (`realm_access.roles`) viram `ROLE_<papel>` pelo conversor do account. Use `hasRole("admin")`.

### Configuração

- `application.yml` é a configuração de **produção**: Swagger desligado, health sem detalhes, erros sem mensagem interna.
- `application-dev.yml` liga Swagger, detalhes do health, SQL no log e o seed. O compose sobe os serviços com `SPRING_PROFILES_ACTIVE: dev`; o Kubernetes, não.
- Tudo o que muda por ambiente é variável de ambiente com padrão local: `${ACCOUNT_SERVICE_URL:http://localhost:8081}`. O nome segue `<SERVIÇO>_<COISA>` (`TRANSFER_DB_URL`, `KEYCLOAK_JWK_SET_URI`).
- Usuário e senha nunca têm padrão no `application.yml` e nunca são commitados. No compose, vêm do `.env` de cada serviço, com um `.env.example` versionado. No Kubernetes, vêm de `k8s/infra/*-secret.yaml`, com um `*-secret.example.yaml` versionado. O `.gitignore` já bloqueia os dois.
- Exceção consciente: os usuários de demonstração do realm ([keycloak/realm-matilha.json](../keycloak/realm-matilha.json)) têm senha no Git, porque existem só para uso local, numa instância `start-dev` sem TLS. Veja [Usuários de demonstração](../keycloak/README.md#usuários-de-demonstração) no README do Keycloak. A senha do admin do console do Keycloak não entra nessa exceção: vem do `.env` e do `keycloak-admin-secret.yaml`, como as dos bancos.

### Banco e migrações

- Um banco por serviço. Um serviço nunca lê o banco de outro: pede pela API ([ADR-0001](adr/0001-monorepo-e-banco-por-servico.md)).
- O schema é só do Flyway. O Hibernate roda com `ddl-auto: validate` ([ADR-0010](adr/0010-flyway-e-testes-com-postgres-real.md)).
- Migrações em `src/main/resources/db/migration`, com o nome `V<n>__<verbo>_<coisa>.sql` em inglês e snake_case: `V7__add_transfer_owner.sql`, `V4__create_outbox_events.sql`.
- **Uma migração que já rodou nunca é editada.** Para mudar, crie a próxima versão.
- Dados de exemplo ficam em `db/seed`, numa migração repetível (`R__seed_dev_accounts.sql`), que só entra pelo perfil `dev` ou pelo `SPRING_FLYWAY_LOCATIONS` do Kubernetes. O seed precisa poder rodar de novo sem duplicar nada.
- Tabelas e colunas em snake_case, tabelas no plural (`transfers`, `outbox_events`). Índices com prefixo `idx_` ou `ix_`, seguindo o que o serviço já usa.
- Mudança de contrato que quebraria dados antigos é feita em duas etapas (expand e contract): primeiro opcional, depois obrigatória, conferindo antes que não sobrou nenhum registro no formato antigo.

### Comunicação entre serviços

- Chamada síncrona com `RestClient`, dentro de uma classe em `client`, com timeout configurado. Nunca com uma transação aberta durante a chamada ([ADR-0005](adr/0005-chamada-sincrona-e-pending.md)).
- Resultado desconhecido (timeout, conexão caída) não é falha: a transferência fica `PENDING` e o reconciliador resolve.
- Toda operação que move dinheiro é idempotente, com um id que o chamador escolhe ([ADR-0004](adr/0004-idempotencia-ponta-a-ponta.md)).
- Rotas chamadas só por outros serviços ficam em `/internal/**`, e o gateway não as conhece ([ADR-0012](adr/0012-gateway-webmvc-e-rotas-liberadas.md)). Uma rota pública nova precisa entrar na lista de rotas do gateway.

### Eventos

- Publicados só pelo transactional outbox, na mesma transação da mudança de estado ([ADR-0008](adr/0008-transactional-outbox.md)). Nunca chame o `KafkaTemplate` direto de um service.
- Todo evento tem `eventId`, `eventType`, `eventVersion` e `occurredAt` (veja o [TransferEvent](../matilha-transfer/src/main/java/com/matilha/transfer/dto/TransferEvent.java)). O consumidor é idempotente pelo `eventId` ([ADR-0011](adr/0011-consumidor-idempotente-e-dlt.md)).
- Valores em dinheiro viajam como texto (`amount.toPlainString()`), para nenhum consumidor perder casas decimais num `double`.
- Campo novo é **opcional** e mantém a versão. O consumidor ignora campos desconhecidos e aceita o campo novo ausente. Só um campo removido ou com significado mudado justifica uma versão nova.
- Eventos levam ids opacos (número da conta, `sub` do Keycloak), nunca nome, e-mail ou telefone.

## Testes (`src/test`)

- JUnit 5, AssertJ (`assertThat`) e Mockito. Uma classe de teste por classe testada, com o mesmo nome e o sufixo `Test`, no mesmo pacote.
- Métodos `shouldXxx` em inglês, descrevendo o comportamento. Agrupe cenários com `@Nested`. Use `@ParameterizedTest` quando só o dado muda.
- Constantes no topo para os dados repetidos. Os usuários usam os ids fixos do realm: `ANA = "00000000-0000-0000-0003-000000000001"`, `BRUNO = "...0002"`, `SUPORTE = "...0003"`. Contas `10001` (Ana) e `10002` (Bruno).
- Comentários e Javadoc em português são bem-vindos nos testes, para explicar o cenário ou por que um mock se comporta de certo jeito.
- Tipos de teste:

| Tipo | Como | Exemplo |
|---|---|---|
| Entidade | Sem Spring, só `new` e asserções | `TransferTest` |
| Service | `@ExtendWith(MockitoExtension.class)` com `@Mock` e `@InjectMocks` | `TransferServiceTest` |
| Persistência e consultas | PostgreSQL real com Testcontainers, nunca H2 | `TransferPersistenceTest` |
| Controller | `@WebMvcTest` com `@Import({ SecurityConfig.class, ProblemDetailSecurityHandler.class })`, `JwtDecoder` mockado e `jwt()` do `spring-security-test` | `TransferControllerTest` |
| Cliente HTTP | `MockRestServiceServer` | `AccountClientTest` |
| Kafka | Broker real com Testcontainers e Awaitility | `TransferEventListenerTest` |

- Toda rota protegida tem pelo menos um teste de `401` sem token. Toda regra de dono tem um teste com o usuário errado.
- Contrato de evento tem teste nos dois sentidos: evento com campo a mais e evento sem o campo novo.
- O CI roda `./mvnw -B verify` em cada serviço e depois o build da imagem. `-DskipTests` ainda compila os testes, então mudar uma assinatura quebra o build até os testes serem atualizados.

## Documentação

- **README de cada serviço**, nesta ordem de seções: Como rodar, Perfis, Documentação da API, Segurança, Endpoints, Regras de negócio, Erros, Banco de dados, Testes, CI, Estrutura. Um serviço sem um dos assuntos pula a seção, sem mudar a ordem das outras.
- **README da raiz**: arquitetura (diagrama mermaid), como rodar, decisões principais (um parágrafo por decisão, com link para o ADR), stack e roadmap. Uma fase entregue vira `[x]` no roadmap.
- **ADRs** em `docs/adr/NNNN-titulo-em-kebab-case.md`, com Status, Data (`AAAA-MM`), Contexto, Decisão, Alternativas consideradas e Consequências. Toda decisão nova entra também na tabela de [adr/README.md](adr/README.md). Um ADR aceito não é editado; uma mudança de decisão é um ADR novo que substitui o antigo.
- Referências a arquivos são links relativos. Exemplos de chamada usam `curl` com `Authorization: Bearer $TOKEN`.
- Mudou comportamento, endpoint, variável, erro ou tabela? O README do serviço muda no mesmo trabalho.

## Commits

Conventional Commits em português, verbo no infinitivo, sem ponto final:

```
<tipo>(<escopo>): <o que o commit faz>
```

- **Tipos:** `feat`, `fix`, `test`, `docs`, `refactor`, `style`, `chore`.
- **Escopos:** `account`, `transfer`, `notification`, `gateway`, `keycloak`, `k8s`, `postman`, `adr`. Mais de um serviço: `account,transfer`. Mudança no projeto todo: sem escopo.
- Um commit por passo lógico, compilando e com os testes passando. Exemplos reais:
  - `feat(account): gravar o dono da conta a partir do sub do token`
  - `test(transfer): exigir token nos testes do controller e cobrir 401 sem token`
  - `docs(adr): registrar o Kubernetes local com kind e as provas com duas réplicas`

## Scripts PowerShell

Os scripts de [k8s](../k8s/) rodam no Windows PowerShell 5.1:

- Sem `&&`, `||`, `?:` e `??`. Use `; if ($?) { ... }`.
- Arquivos com acento salvos em UTF-8 **com BOM**, ou o 5.1 lê os acentos errado.
- JSON com espaço não vai como argumento para executáveis nativos (o 5.1 remove as aspas). Use `Invoke-RestMethod` com `ConvertTo-Json`.
- Use `curl.exe`, e não `curl`, que no 5.1 é um alias do `Invoke-WebRequest`.
- As provas carregam [k8s/provas/comum.ps1](../k8s/provas/comum.ps1), que pega e renova o token do dono de cada conta.

## Antes de dar um trabalho por terminado

- [ ] Compila e `./mvnw verify` passa no serviço alterado
- [ ] Teste novo para o comportamento novo, incluindo `401` e o caso de outro usuário se a rota é protegida
- [ ] Migração nova em vez de editar uma antiga
- [ ] Nenhum segredo, `.env` ou `*-secret.yaml` no commit
- [ ] README do serviço atualizado; ADR novo se a decisão muda a arquitetura
- [ ] Postman e provas do k8s ainda funcionam, se a rota ou o contrato mudou
