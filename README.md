# Matilha

Sistema bancário em microsserviços construído com Spring Boot. Cada serviço vive na sua própria pasta deste monorepo, com build, testes, Dockerfile e pipeline independentes.

## Serviços

| Serviço | Responsabilidade | Status |
|---|---|---|
| [matilha-account](matilha-account/) | Contas, saldo e movimentações (depósitos e transferências) | ✅ Pronto |
| matilha-transfer | Orquestração de transferências entre contas | 🚧 Planejado |
| matilha-gateway | Porta de entrada da API e roteamento | 🚧 Planejado |
| matilha-notification | Envio de notificações | 🚧 Planejado |
| keycloak | Autenticação e autorização | 🚧 Planejado |
| k8s | Manifests de deploy no Kubernetes | 🚧 Planejado |

## Stack

- Java 25 e Spring Boot 4.1
- Spring Web MVC, Spring Data JPA e Bean Validation
- PostgreSQL 17 com migrações Flyway
- Lombok
- springdoc-openapi (Swagger UI)
- Spring Boot Actuator (health checks e probes)
- JUnit 5, Mockito, MockMvc e H2 nos testes
- Docker e Docker Compose
- GitHub Actions

---

## matilha-account

Serviço dono das contas. Guarda os dados do titular, o saldo e o histórico de movimentações, e é o único que altera saldos.

### Como rodar

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

O [docker-compose.yml](docker-compose.yml) da raiz só inclui o `compose.yaml` de cada serviço. Cada serviço continua com o próprio `.env` na sua pasta.

**2b. Só o account (banco e serviço no Docker)**

```bash
cd matilha-account
docker compose up --build
```

**2c. Banco no Docker e serviço na máquina (bom para desenvolver)**

```bash
cd matilha-account
docker compose up -d account-db
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

O serviço sobe em `http://localhost:8081`. As duas portas (8081 e 5432) ficam ligadas só a `127.0.0.1`. Use um modo de cada vez, porque os nomes dos containers e as portas são fixos.

### Perfis

| Perfil | O que muda |
|---|---|
| padrão | Swagger desligado, SQL não aparece no log, health sem detalhes. Todas as variáveis `ACCOUNT_DB_*` precisam vir do ambiente. |
| `dev` | Lê o `.env`, liga o Swagger, mostra o SQL formatado, mostra os detalhes do health e carrega as contas de exemplo. |

O tamanho do pool de conexões pode ser ajustado com `ACCOUNT_DB_POOL_SIZE` (padrão `10`).

### Documentação da API

Com o perfil `dev` ativo:

- Swagger UI: http://localhost:8081/swagger-ui.html
- OpenAPI (JSON): http://localhost:8081/v3/api-docs

Health check: http://localhost:8081/actuator/health (probes em `/actuator/health/liveness` e `/actuator/health/readiness`).

### Endpoints

**Públicos**

| Método | Rota | Descrição | Sucesso |
|---|---|---|---|
| `POST` | `/accounts` | Abre uma conta | `201` |
| `GET` | `/accounts` | Lista contas com paginação | `200` |
| `GET` | `/accounts/{accountNumber}` | Busca uma conta pelo número | `200` |
| `PATCH` | `/accounts/{accountNumber}` | Atualiza nome, e-mail ou telefone do titular | `200` |
| `DELETE` | `/accounts/{accountNumber}` | Encerra a conta | `204` |
| `POST` | `/accounts/{accountNumber}/block` | Bloqueia a conta | `200` |
| `POST` | `/accounts/{accountNumber}/unblock` | Desbloqueia a conta | `200` |

**Internos** (para outros serviços, como o matilha-transfer; ficam fora do Swagger e não devem ser expostos pelo gateway)

| Método | Rota | Descrição | Sucesso |
|---|---|---|---|
| `POST` | `/internal/deposits` | Credita um valor em uma conta | `200` |
| `POST` | `/internal/transfers` | Transfere um valor entre duas contas | `200` |

#### Exemplos

Abrir conta:

```bash
curl -X POST http://localhost:8081/accounts \
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

Listar contas (padrão: página 0, 20 itens, mais recentes primeiro; no máximo 100 por página):

```bash
curl "http://localhost:8081/accounts?page=0&size=10&sort=holderName,asc"
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
  "amount": 250.50
}
```

### Regras de negócio

**Conta**

- O número da conta tem 5 dígitos, é gerado aleatoriamente e é único.
- Toda conta nasce `ACTIVE` com saldo `0.00`.
- O CPF precisa ser válido e não pode ser alterado depois.
- Os dados são normalizados antes de salvar: CPF e telefone ficam só com dígitos e o e-mail fica em minúsculas.
- O telefone precisa ter 10 ou 11 dígitos (aceita formatos como `(11) 98765-4321`).
- No `PATCH`, campos omitidos não mudam. Contas encerradas não podem ser alteradas.

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
- Cada operação gera movimentações em `account_movements` (`DEBIT` / `CREDIT`) com o saldo resultante. Assim o extrato sempre bate com o saldo.

### Erros

As respostas de erro seguem o formato [Problem Details (RFC 9457)](https://www.rfc-editor.org/rfc/rfc9457):

```json
{
  "type": "about:blank",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Insufficient balance",
  "instance": "/internal/transfers"
}
```

| Status | Quando |
|---|---|
| `400` | Falha de validação do corpo da requisição. Os campos inválidos vêm em `errors`. |
| `404` | Conta não encontrada |
| `409` | Conflito de concorrência ou violação de integridade (por exemplo, dado duplicado) |
| `422` | Regra de negócio violada (saldo insuficiente, conta inativa, transição de status inválida...) |
| `500` | Erro inesperado (o detalhe vai só para o log, nunca para a resposta) |

### Banco de dados

As migrações Flyway ficam em `src/main/resources/db/migration`, e o Hibernate roda com `ddl-auto: validate`, então o schema é controlado só pelas migrações.

| Tabela | Conteúdo |
|---|---|
| `accounts` | Dados do titular, saldo, status e `version` (lock otimista) |
| `account_movements` | Débitos e créditos de cada operação (`TRANSFER` ou `DEPOSIT`), únicos por `(operation_id, type)` |

#### Contas de exemplo (perfil `dev`)

O perfil `dev` carrega `db/seed/R__seed_dev_accounts.sql`:

| Conta | Titular | Saldo | Status |
|---|---|---|---|
| `10001` | Ana Silva | 1500.00 | ACTIVE |
| `10002` | Bruno Costa | 250.00 | ACTIVE |
| `10003` | Carla Souza | 0.00 | ACTIVE |
| `10004` | Diego Lima | 800.00 | BLOCKED |
| `10005` | Elisa Rocha | 0.00 | CLOSED |

### Testes

```bash
cd matilha-account
./mvnw verify
```

O Docker precisa estar rodando, porque o teste de concorrência sobe um PostgreSQL com Testcontainers. Os testes cobrem:

- **Entidades**: regras de saldo, status, normalização e validação
- **Services**: criação de conta, depósito, transferência, idempotência e ordem de lock (com Mockito)
- **Controllers**: contratos HTTP, validação e respostas de erro (com MockMvc)
- **Concorrência**: transferências e depósitos simultâneos contra um PostgreSQL real, com Flyway e locks de verdade. O teste confere que o saldo nunca fica negativo, que não há deadlock, que uma operação repetida não move dinheiro duas vezes e que nenhuma atualização se perde.

O teste de contexto (`AccountApplicationTests`) usa o perfil `test` (`src/test/resources/application-test.yml`), com H2 em memória.

### Estrutura

```
matilha-account/
├── src/main/java/com/matilha/account/
│   ├── config/        # OpenAPI e paginação
│   ├── controller/    # Endpoints públicos e internos
│   ├── dto/           # Records de request/response
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

---

## CI

O workflow [account-ci.yml](.github/workflows/account-ci.yml) roda em push para `main` e em pull requests que mexem em `matilha-account/`:

1. **test**: `./mvnw -B verify` com Java 25
2. **docker**: build da imagem, só se os testes passarem
