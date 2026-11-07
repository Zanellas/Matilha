# matilha-account

Serviço dono das contas. Guarda os dados do titular, o dono da conta (o usuário do Keycloak), o saldo e o histórico de movimentações, e é o único que altera saldos.

Faz parte do [Matilha](../README.md).

## Como rodar

Pré-requisitos: Docker. Para rodar fora do container, também JDK 25.

**1. Configure as variáveis de ambiente**

```bash
cd matilha-account
cp .env.example .env
```

Edite o `.env` e troque usuário e senha do banco:

```properties
ACCOUNT_DB_NAME=matilha_account
ACCOUNT_DB_USERNAME=seu-usuario
ACCOUNT_DB_PASSWORD=sua-senha
ACCOUNT_DB_URL=jdbc:postgresql://localhost:5432/matilha_account
```

O `.env` está no `.gitignore` e nunca deve ser commitado.

**2a. Sistema inteiro, a partir da raiz do repositório**

```bash
docker compose up --build
```

O compose da raiz inclui a infraestrutura (`infra/compose.yaml`, com o Kafka) e o `compose.yaml` de cada serviço. Cada serviço continua com o próprio `.env` na sua pasta.

**2b. Só o account (banco e serviço no Docker)**

```bash
cd matilha-account
docker compose up --build
```

**2c. Banco no Docker e serviço na máquina (bom para desenvolver)**

```bash
cd matilha-account
docker compose up -d account-db
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

As aspas no `-D...` são necessárias no PowerShell, que quebraria o argumento no ponto. No bash elas não atrapalham.

O serviço sobe em `http://localhost:8081`. As duas portas (8081 e 5432) ficam ligadas só a `127.0.0.1`. Use um modo de cada vez, porque os nomes dos containers e as portas são fixos.

A 8081 serve para depuração, para o Swagger e para os endpoints internos. A entrada oficial da API é o [matilha-gateway](../matilha-gateway/), em `http://localhost:8080`, que repassa `/accounts/**` para cá e não deixa passar o `/internal/**`.

Os endpoints públicos exigem um token do [Keycloak](../keycloak/), mesmo chamados direto na 8081. Nos modos 2b e 2c, suba o Keycloak também: `docker compose up -d keycloak`, na raiz. O serviço sobe sem ele, porque só baixa as chaves públicas na primeira requisição com token.

| Variável | Padrão | No Docker |
|---|---|---|
| `KEYCLOAK_JWK_SET_URI` | `http://localhost:8180/realms/matilha/protocol/openid-connect/certs` | `http://keycloak:8080/realms/matilha/protocol/openid-connect/certs` |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8180/realms/matilha` | o mesmo: é o `iss` que o token traz |

## Perfis

| Perfil | O que muda |
|---|---|
| padrão | Swagger desligado, SQL não aparece no log, health sem detalhes. Todas as variáveis `ACCOUNT_DB_*` precisam vir do ambiente. |
| `dev` | Lê o `.env`, liga o Swagger, mostra o SQL formatado, mostra os detalhes do health e carrega as contas de exemplo. |

O tamanho do pool de conexões pode ser ajustado com `ACCOUNT_DB_POOL_SIZE` (padrão `10`).

## Documentação da API

Com o perfil `dev` ativo:

- Swagger UI: http://localhost:8081/swagger-ui.html
- OpenAPI (JSON): http://localhost:8081/v3/api-docs

Health check: http://localhost:8081/actuator/health, sem token (probes em `/actuator/health/liveness` e `/actuator/health/readiness`).

## Segurança

O account é um *resource server* do Spring Security: ele valida o token sozinho, pela chave pública do Keycloak, mesmo que a requisição já tenha passado pelo gateway. Assim, um pod de dentro do cluster não consegue pular o gateway chamando a 8081 direto.

- **Quem é o usuário:** o `sub` do token. A conta guarda esse valor em `owner_id` quando é aberta, e ele nunca muda. Ele nunca vem do corpo da requisição: se viesse, a Ana poderia abrir uma conta em nome do Bruno.
- **O papel:** o `realm_access.roles` do token vira authority do Spring (`admin` vira `ROLE_admin`), por um conversor no `SecurityConfig`. O token dos clientes traz o papel `customer`, e o do suporte, `admin`.
- **A conta de outra pessoa se comporta como se não existisse.** A busca já filtra pelo dono (`WHERE account_number = ? AND owner_id = ?`), então a conta de outra pessoa e a conta inexistente caem no mesmo `404` com `ACCOUNT_NOT_FOUND`. Quem tenta adivinhar números de conta não consegue diferenciar as duas.
- **Sem sessão:** a API é *stateless*, sem `JSESSIONID` e sem CSRF.

| Operação | Dono da conta | Admin | Outra pessoa |
|---|---|---|---|
| Abrir conta | a conta nasce com o `sub` do token | idem | idem |
| Listar contas | só as dele | todas | só as dele |
| Ler uma conta | ✅ | ✅ | `404` |
| Alterar dados do titular e encerrar | ✅ | `404`: o admin não altera dados pessoais | `404` |
| Bloquear e desbloquear | `403` | ✅ | `403` |

**O `/internal/**` não pede token nesta fase.** O transfer chama o `/internal/transfers` em nome do sistema, e o reconciliador reenvia transferências sem nenhum usuário logado. A proteção hoje é o gateway não ter rota para lá. Dentro do cluster, qualquer pod alcança esses endpoints. A solução é a segurança entre serviços (client credentials e NetworkPolicy), registrada como próximo passo no [ADR-0014](../docs/adr/0014-keycloak-e-dono-da-conta.md).

## Endpoints

**Públicos** (exigem token)

| Método | Rota | Descrição | Quem pode | Sucesso |
|---|---|---|---|---|
| `POST` | `/accounts` | Abre uma conta para o usuário do token | qualquer usuário | `201` |
| `GET` | `/accounts` | Lista as contas do usuário, com paginação (todas, para o admin) | qualquer usuário | `200` |
| `GET` | `/accounts/{accountNumber}` | Busca uma conta pelo número | o dono ou o admin | `200` |
| `PATCH` | `/accounts/{accountNumber}` | Atualiza nome, e-mail ou telefone do titular | o dono | `200` |
| `DELETE` | `/accounts/{accountNumber}` | Encerra a conta | o dono | `204` |
| `POST` | `/accounts/{accountNumber}/block` | Bloqueia a conta | o admin | `200` |
| `POST` | `/accounts/{accountNumber}/unblock` | Desbloqueia a conta | o admin | `200` |

**Internos** (para outros sistemas; ficam fora do Swagger, não são expostos pelo gateway e não pedem token)

| Método | Rota | Quem chama | Descrição | Sucesso |
|---|---|---|---|---|
| `POST` | `/internal/deposits` | Sistema de pagamentos (externo) | Credita um valor em uma conta | `200` |
| `POST` | `/internal/transfers` | matilha-transfer | Transfere um valor entre duas contas, se a conta de origem for de quem pediu (`ownerId`) | `200` |

**De onde vem o depósito**

O `/internal/deposits` representa uma entrada externa de dinheiro, como um PIX recebido. Quem chama é o sistema de pagamentos, que manda o próprio identificador da operação em `depositId`. Se ele reenviar o mesmo aviso, o depósito não é aplicado duas vezes.

O Matilha não tem esse sistema de pagamentos. No perfil `dev`, o dinheiro entra pelas contas de exemplo ou por uma chamada direta ao endpoint, que faz o papel do sistema externo. Não é uma operação administrativa: o pedido não diz quem fez a operação, e o endpoint não é exposto pelo gateway.

### Exemplos

Os exemplos públicos usam o `$TOKEN` da Ana. Como pegar: [README da raiz](../README.md#como-rodar).

Abrir conta:

```bash
curl -X POST http://localhost:8081/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "holderName": "Ana Silva",
    "holderDocument": "529.982.247-25",
    "holderEmail": "Ana.Silva@Example.com",
    "holderPhone": "(11) 98765-4321"
  }'
```

```json
{
  "accountNumber": "48213",
  "holderName": "Ana Silva",
  "holderPhone": "11987654321",
  "holderEmail": "ana.silva@example.com",
  "status": "ACTIVE",
  "balance": 0.00
}
```

A conta nasce com o `sub` do token como dono. A resposta não mostra o dono: o cliente já sabe quem ele é.

Listar as contas do usuário (padrão: página 0, 20 itens, mais recentes primeiro; no máximo 100 por página):

```bash
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8081/accounts?page=0&size=10&sort=holderName,asc"
```

Depósito:

```bash
curl -X POST http://localhost:8081/internal/deposits \
  -H "Content-Type: application/json" \
  -d '{
    "depositId": "3f1c2a9e-7b4d-4c1a-9e2f-5a6b7c8d9e01",
    "accountNumber": "10003",
    "amount": 100.00
  }'
```

Transferência:

```bash
curl -X POST http://localhost:8081/internal/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "transferId": "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d",
    "ownerId": "00000000-0000-0000-0003-000000000001",
    "sourceAccountNumber": "10001",
    "targetAccountNumber": "10002",
    "amount": 250.50
  }'
```

```json
{
  "transferId": "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d",
  "sourceAccountNumber": "10001",
  "sourceBalanceAfter": 1249.50,
  "targetAccountNumber": "10002",
  "targetBalanceAfter": 500.50,
  "amount": 250.50,
  "targetOwnerId": "00000000-0000-0000-0003-000000000002"
}
```

O `ownerId` é quem pediu a transferência, que o transfer leu do token. O `targetOwnerId` é o dono da conta de destino, que o transfer coloca no evento para o notification avisar a pessoa certa. Ele vem `null` se a conta de destino não tiver dono.

## Regras de negócio

**Conta**

- O número da conta tem 5 dígitos, é gerado aleatoriamente e é único.
- Toda conta nasce `ACTIVE` com saldo `0.00`.
- O CPF precisa ser válido e não pode ser alterado depois.
- Os dados são normalizados antes de salvar: CPF e telefone ficam só com dígitos e o e-mail fica em minúsculas.
- O telefone precisa ter 10 ou 11 dígitos (aceita formatos como `(11) 98765-4321`).
- No `PATCH`, campos omitidos não mudam. Contas encerradas não podem ser alteradas.
- Toda conta nova tem dono (o `sub` do token de quem a abriu), e o dono nunca muda (`updatable = false`). O titular (`holder*`) e o dono são coisas diferentes: o titular são os dados cadastrais, que o `PATCH` pode mudar; o dono é o login que pode operar a conta.

**Status**

```
ACTIVE ──block──▶ BLOCKED
   ▲                 │
   └─────unblock─────┘

ACTIVE / BLOCKED ──close (saldo = 0)──▶ CLOSED
```

- Só contas `ACTIVE` podem ser bloqueadas, e só contas `BLOCKED` podem ser desbloqueadas.
- Só dá para encerrar uma conta com saldo zero. O encerramento é definitivo.
- Só contas `ACTIVE` recebem ou enviam dinheiro.

**Valores**

- Os valores precisam ser positivos e ter no máximo 2 casas decimais.
- O saldo nunca fica negativo (a regra está na entidade e também numa constraint do banco).

**Depósitos e transferências**

- **Idempotência:** cada operação traz o próprio ID (`depositId` ou `transferId`). Repetir uma requisição com o mesmo ID devolve o resultado original e não move dinheiro de novo, então quem chama pode reenviar com segurança depois de um timeout.
- **Concorrência:** as contas envolvidas são travadas no banco (`SELECT ... FOR UPDATE`) durante a operação. Nas transferências, as duas contas são sempre travadas na mesma ordem (pelo número da conta), o que evita deadlock.
- A conta de origem e a de destino precisam ser diferentes.
- **Dono:** na transferência, a conta de origem precisa ser de quem pediu (`ownerId`). A conferência acontece depois de travar as contas e antes de mover o dinheiro. Se a conta não for dele, a resposta é `404` com `ACCOUNT_NOT_FOUND`, a mesma de uma conta inexistente. A conta de destino pode ser de qualquer pessoa, como num PIX. Uma conta sem dono (criada antes da Fase 4) não pode ser origem de transferência.
- Cada operação gera movimentações em `account_movements` (`DEBIT` / `CREDIT`) com o saldo resultante. Assim o extrato sempre bate com o saldo.

## Erros

As respostas de erro seguem o formato [Problem Details (RFC 9457)](https://www.rfc-editor.org/rfc/rfc9457):

```json
{
  "type": "about:blank",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Insufficient balance",
  "instance": "/internal/transfers",
  "code": "INSUFFICIENT_BALANCE"
}
```

O `detail` é texto para gente ler e pode mudar. Quem chama o account decide pelo `code`, que é o contrato. Ele vem em toda resposta `401`, `403`, `404` e `422`:

| `code` | Quando |
|---|---|
| `UNAUTHENTICATED` | Sem token, ou com token inválido (`401`, com o header `WWW-Authenticate: Bearer`) |
| `ACCESS_DENIED` | Token válido, mas sem o papel exigido, como um cliente bloqueando uma conta (`403`) |
| `ACCOUNT_NOT_FOUND` | A conta não existe, ou é de outra pessoa (`404`) |
| `ACCOUNT_INACTIVE` | A conta está bloqueada ou encerrada e não pode enviar nem receber dinheiro |
| `INSUFFICIENT_BALANCE` | O saldo não cobre o valor |
| `BUSINESS_RULE_VIOLATION` | Qualquer outra regra de negócio (valor inválido, transição de status inválida, operação repetida com dados diferentes...) |

| Status | Quando |
|---|---|
| `400` | Falha de validação do corpo da requisição. Os campos inválidos vêm em `errors`. |
| `401` | Sem token ou com token inválido. O motivo (vencido, malformado) vai só para o log |
| `403` | Token válido, sem o papel exigido |
| `404` | Conta não encontrada, ou de outra pessoa |
| `409` | Conflito de concorrência ou violação de integridade (por exemplo, dado duplicado) |
| `422` | Regra de negócio violada (saldo insuficiente, conta inativa, transição de status inválida...) |
| `500` | Erro inesperado (o detalhe vai só para o log, nunca para a resposta) |

## Banco de dados

As migrações Flyway ficam em `src/main/resources/db/migration`, e o Hibernate roda com `ddl-auto: validate`, então o schema é controlado só pelas migrações.

| Tabela | Conteúdo |
|---|---|
| `accounts` | Dados do titular, dono (`owner_id`, o `sub` do Keycloak), saldo, status e `version` (lock otimista) |
| `account_movements` | Débitos e créditos de cada operação (`TRANSFER` ou `DEPOSIT`), únicos por `(operation_id, type)` |

### Contas de exemplo (perfil `dev`)

O perfil `dev` carrega `db/seed/R__seed_dev_accounts.sql`:

| Conta | Titular | Saldo | Status | Dono |
|---|---|---|---|---|
| `10001` | Ana Silva | 1500.00 | ACTIVE | `ana` |
| `10002` | Bruno Costa | 250.00 | ACTIVE | `bruno` |
| `10003` | Carla Souza | 0.00 | ACTIVE | sem dono |
| `10004` | Diego Lima | 800.00 | BLOCKED | sem dono |
| `10005` | Elisa Rocha | 0.00 | CLOSED | sem dono |

Os donos são os ids fixos dos usuários de demonstração do [realm](../keycloak/realm-matilha.json). O seed os grava com `UPDATE ... WHERE owner_id IS NULL`, e não no `INSERT`, para funcionar também num banco que já tinha as contas antes da coluna existir. A coluna vem da V3, que roda em produção, por isso os ids de demonstração ficam só no seed. As contas sem dono só aparecem para o admin, e podem receber transferências, mas não enviar.

## Testes

```bash
cd matilha-account
./mvnw verify
```

O Docker precisa estar rodando, porque os testes que sobem a aplicação usam um PostgreSQL real com Testcontainers. Os testes cobrem:

- **Contexto**: a aplicação sobe com as migrações do Flyway e o `ddl-auto: validate`
- **Entidades**: regras de saldo, status, normalização e validação
- **Services**: criação de conta, depósito, transferência, idempotência e ordem de lock (com Mockito)
- **Controllers**: contratos HTTP, validação e respostas de erro (com MockMvc e o `SecurityConfig` de verdade)
- **Segurança**: `401` sem token, `403` para o cliente no block e no unblock, nenhuma sessão criada, e o conversor de papéis com tokens no formato do Keycloak (`realm_access.roles`). O dono vem do token mesmo com um `ownerId` no corpo (*mass assignment*)
- **Regra de dono**: conta de outra pessoa dá a mesma exceção de uma inexistente, o cliente lista só as suas e o admin todas, e a transferência a partir da conta de outra pessoa não move dinheiro
- **Concorrência**: transferências e depósitos simultâneos contra o PostgreSQL, com locks de verdade. O teste confere que o saldo nunca fica negativo, que não há deadlock, que uma operação repetida não move dinheiro duas vezes e que nenhuma atualização se perde.

Para rodar o serviço na IDE sem o compose, use o `main` de `TestAccountApplication` (em `src/test`). Ele sobe a aplicação com o mesmo PostgreSQL dos testes. Sem perfil, o banco começa vazio. Para ter as contas de exemplo e o Swagger, passe `--spring.profiles.active=dev` nos argumentos do programa.

## CI

O workflow [account-ci.yml](../.github/workflows/account-ci.yml) roda em push para `main` e em pull requests que mexem em `matilha-account/`:

1. **test**: `./mvnw -B verify` com Java 25
2. **docker**: build da imagem, só se os testes passarem

## Estrutura

```
matilha-account/
├── src/main/java/com/matilha/account/
│   ├── config/        # OpenAPI, paginação, SecurityConfig (regras e papéis do Keycloak) e o handler de 401/403
│   ├── controller/    # Endpoints públicos e internos
│   ├── dto/           # Records de request/response, e o RequesterDto (quem pede: sub e se é admin)
│   ├── entity/        # Account, AccountMovement e enums
│   ├── exception/     # Exceções e handler global
│   ├── repository/    # Repositórios Spring Data
│   └── service/       # Contas, depósitos e transferências
├── src/main/resources/
│   ├── db/migration/  # Migrações versionadas
│   └── db/seed/       # Dados de exemplo (só no dev)
├── Dockerfile         # Build multi-stage, roda como usuário não-root
└── compose.yaml       # PostgreSQL + serviço
```
