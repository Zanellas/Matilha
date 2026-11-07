# matilha-transfer

Serviço dono dos pedidos de transferência. Recebe o pedido do cliente, registra, pede para o [matilha-account](../matilha-account/) mover o dinheiro e guarda o resultado. Ele nunca altera saldos: quem faz isso é o account.

Faz parte do [Matilha](../README.md).

## Como rodar

Pré-requisitos: Docker. Para rodar fora do container, também JDK 25. O account precisa estar rodando para as transferências serem processadas. Sem ele, os pedidos ficam `PENDING`.

**1. Configure as variáveis de ambiente**

```bash
cd matilha-transfer
cp .env.example .env
```

Edite o `.env` e troque usuário e senha do banco:

```properties
TRANSFER_DB_NAME=matilha_transfer
TRANSFER_DB_USERNAME=seu-usuario
TRANSFER_DB_PASSWORD=sua-senha
TRANSFER_DB_URL=jdbc:postgresql://localhost:5433/matilha_transfer
```

O `.env` está no `.gitignore` e nunca deve ser commitado.

**2a. Sistema inteiro:** `docker compose up --build` na raiz do repositório.

**2b. Banco e Kafka no Docker, serviço na máquina (bom para desenvolver)**

```bash
# na raiz do repositório
docker compose up -d transfer-db kafka

cd matilha-transfer
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

O `docker compose` roda **na raiz**, e não dentro de `matilha-transfer/`. O transfer depende do Kafka, e o Kafka é definido em `infra/compose.yaml`, que só a raiz inclui. Rodado sozinho, o compose do transfer não encontra o serviço `kafka`. As aspas no `-D...` são necessárias no PowerShell, que quebraria o argumento no ponto.

O serviço sobe em `http://localhost:8082`, com o banco na porta `5433` para não conflitar com o do account. As duas portas ficam ligadas só a `127.0.0.1`.

A 8082 serve para depuração e para o Swagger. A entrada oficial da API é o [matilha-gateway](../matilha-gateway/), em `http://localhost:8080`, que repassa `/transfers/**` para cá com os headers intactos: o `Idempotency-Key` chega, e o `Location` volta.

| Variável | Padrão | Uso |
|---|---|---|
| `ACCOUNT_SERVICE_URL` | `http://localhost:8081` | Endereço do account. No Docker, o `compose.yaml` aponta para `http://account:8081`. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Endereço do Kafka. No Docker, o `compose.yaml` aponta para `kafka:29092`, o listener interno do broker. |
| `TRANSFER_DB_POOL_SIZE` | `10` | Tamanho do pool de conexões |

## Perfis

Iguais aos do account: o padrão exige as variáveis `TRANSFER_DB_*` do ambiente, e o `dev` lê o `.env`, liga o Swagger, mostra o SQL e os detalhes do health. O transfer não tem dados de exemplo.

## Documentação da API

Com o perfil `dev` ativo:

- Swagger UI: http://localhost:8082/swagger-ui.html
- OpenAPI (JSON): http://localhost:8082/v3/api-docs

Health check: http://localhost:8082/actuator/health.

## Endpoints

| Método | Rota | Descrição | Sucesso |
|---|---|---|---|
| `POST` | `/transfers` | Pede uma transferência (exige o header `Idempotency-Key`) | `201`, `202` ou `422` |
| `GET` | `/transfers/{id}` | Consulta uma transferência | `200` |

O status HTTP do `POST` depende do resultado:

| Resultado | HTTP | Corpo |
|---|---|---|
| `COMPLETED` | `201 Created` | A transferência. O dinheiro foi movido. |
| `PENDING` | `202 Accepted` | A transferência. Ainda não se sabe se o dinheiro foi movido (o account não respondeu). |
| `FAILED` | `422 Unprocessable Content` | Um Problem Details com o código em `code`, o motivo em `detail` e o id em `transferId` |

Em `201` e `202`, o header `Location` aponta para `/transfers/{id}`. A transferência recusada fica salva e pode ser consultada pelo `transferId`.

Transferência recusada:

```json
{
  "type": "about:blank",
  "title": "Transfer failed",
  "status": 422,
  "detail": "Insufficient balance",
  "instance": "/transfers",
  "transferId": "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d",
  "code": "INSUFFICIENT_BALANCE"
}
```

O `code` é o contrato. O `detail` repete o texto do account e serve só para gente ler.

| `code` | Quando |
|---|---|
| `INSUFFICIENT_BALANCE` | A conta de origem não tem saldo |
| `ACCOUNT_NOT_FOUND` | Uma das contas não existe |
| `ACCOUNT_INACTIVE` | Uma das contas está bloqueada ou encerrada |
| `REJECTED_BY_ACCOUNT` | O account recusou por outro motivo, ou mandou um código que o transfer não conhece |
| `INVALID_REQUEST` | O account disse que o pedido do transfer era inválido. É bug do transfer e fica no log de erro. |

### Exemplo

```bash
curl -i -X POST http://localhost:8082/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 3f1c2a9e-7b4d-4c1a-9e2f-5a6b7c8d9e01" \
  -d '{
    "sourceAccountNumber": "10001",
    "targetAccountNumber": "10002",
    "amount": 250.50
  }'
```

```
HTTP/1.1 201
Location: /transfers/8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d
```

```json
{
  "id": "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d",
  "status": "COMPLETED",
  "sourceAccountNumber": "10001",
  "targetAccountNumber": "10002",
  "amount": 250.50,
  "failureCode": null,
  "failureReason": null,
  "createdAt": "2026-10-26T15:30:00Z"
}
```

## Como funciona

```
POST /transfers
  │
  ├─ Idempotency-Key já existe? ──sim──▶ mesmos dados: devolve a transferência existente
  │                                      dados diferentes: 422
  │
  ├─ grava PENDING                        (transação 1)
  ├─ chama POST /internal/transfers do account   (fora de transação)
  └─ grava COMPLETED / FAILED / mantém PENDING   (transação 2)
```

**Idempotência**

- Cada pedido traz uma chave no header `Idempotency-Key` (um UUID gerado pelo cliente). A chave é única no banco.
- Reenviar o mesmo pedido com a mesma chave devolve a transferência original, com o mesmo status HTTP, sem chamar o account de novo. Valores como `100.0` e `100.00` contam como o mesmo pedido.
- Reusar a chave com dados diferentes dá `422`.
- Se duas requisições com a mesma chave chegarem ao mesmo tempo, a constraint `UNIQUE` do banco deixa só uma criar a transferência. A outra recebe o resultado da vencedora.
- Para tentar de novo uma transferência `FAILED`, o cliente precisa de uma chave nova.

**Comunicação com o account**

O `id` da transferência vai como `transferId` para o account, que também é idempotente. Por isso um reenvio nunca move o dinheiro duas vezes.

| Resposta do account | Resultado |
|---|---|
| `200` | `COMPLETED` |
| `404` ou `422` | `FAILED`. O `code` do account vira o `failureCode` e o `detail` vira o `failureReason`. |
| `400` | `FAILED` com `INVALID_REQUEST` e log de erro: o transfer mandou um pedido inválido, o que é um bug |
| `409`, outros `4xx`, `5xx`, timeout, conexão recusada ou resposta ilegível | `PENDING` |

O transfer tem o próprio enum de códigos e converte os do account explicitamente. Um código que ele não conhece vira `REJECTED_BY_ACCOUNT` em vez de quebrar, então o account pode criar códigos novos sem coordenar a mudança com o transfer.

Um timeout fica `PENDING`, e não `FAILED`, porque o account pode ter movido o dinheiro e a resposta ter se perdido no caminho. Os timeouts são de 2 segundos para conectar e 5 segundos para ler.

A chamada ao account acontece fora de transação, para não prender uma conexão do banco enquanto espera a rede.

**Regras do pedido**

- As contas têm 5 dígitos e precisam ser diferentes.
- O valor precisa ser positivo, com no máximo 2 casas decimais.

**Reconciliador de pendentes**

Uma transferência fica `PENDING` quando o account não respondeu, ou quando ele moveu o dinheiro e a gravação do resultado falhou. O `TransferReconciler` resolve os dois casos: a cada minuto, ele pega as `PENDING` com mais de 30 segundos e reenvia ao account com o mesmo `transferId`. Como o account é idempotente, ele responde com o resultado original ou processa agora.

- **Várias réplicas:** a busca usa `SELECT ... FOR UPDATE SKIP LOCKED` numa transação curta, que grava uma reserva em `claimed_until` (5 minutos). Cada pendente é pega por uma réplica só. Se a réplica cair no meio, a reserva expira e outra assume.
- **Lotes de 20:** uma transferência com problema é registrada no log e não impede as outras do lote.
- **`@Version`:** se o reconciliador e uma requisição terminarem a mesma transferência, só um grava o resultado.

| Propriedade | Padrão | Significado |
|---|---|---|
| `matilha.reconciler.interval` | `PT60S` | Intervalo entre as rodadas |
| `matilha.reconciler.min-age` | `PT30S` | Idade mínima de uma `PENDING` para ser reenviada. Precisa ser maior que os timeouts do account somados. |
| `matilha.reconciler.lease` | `PT5M` | Duração da reserva. Precisa cobrir um lote inteiro (20 × 7s no pior caso). |
| `matilha.reconciler.enabled` | `true` | Liga ou desliga **todo** o agendamento: o reconciliador e o publicador de eventos. Os testes usam `false`. |

**Eventos (transactional outbox)**

Quando uma transferência termina, o transfer publica `TransferCompleted` ou `TransferFailed` no tópico `matilha.transfer.events` do Kafka, com a conta de origem como chave. O evento leva contas, valor e o `failureCode`, mas não o `failureReason`: nenhum consumidor deve depender de um texto.

```
TransferFinisher.complete() / fail()      uma transação só
  ├─ grava o status da transferência
  └─ grava o evento em outbox_events

OutboxPublisher (a cada 1s)
  ├─ reserva até 50 eventos não publicados  transação curta: SKIP LOCKED + claimed_until
  └─ para cada um:
       ├─ envia ao Kafka e espera a confirmação   fora de transação
       ├─ ok   → marca published_at
       └─ erro → soma em attempts, guarda last_error e para o lote
```

- **Mesma transação:** se o evento não puder ser gravado, a mudança de status também volta. Não existe transferência terminada sem evento, nem evento de algo que não foi gravado. Se o reconciliador e uma requisição terminarem a mesma transferência, o `@Version` desfaz a transação de quem perdeu, junto com o evento dela.
- **Bean separado:** o `TransferFinisher` existe porque o `@Transactional` funciona por proxy. Um método chamado de dentro da própria classe não abriria a transação.
- **`published_at` só depois da confirmação:** o envio espera o broker gravar (`acks: all`). Se o processo cair entre a confirmação e a marcação, o evento sai de novo quando a reserva vencer. A entrega é "pelo menos uma vez", e o notification descarta duplicatas pelo `eventId`, que também é o `id` da linha no outbox.
- **Para na primeira falha:** se o Kafka caiu, os próximos envios também iam esperar o tempo limite. Os que sobraram voltam quando a reserva vencer.

| Propriedade | Padrão | Significado |
|---|---|---|
| `matilha.outbox.interval` | `PT1S` | Intervalo entre as rodadas do publicador |
| `matilha.outbox.lease` | `PT1M` | Duração da reserva de um lote |
| `matilha.outbox.send-timeout` | `PT15S` | Quanto o publicador espera a confirmação do Kafka. É maior que o `delivery.timeout.ms` do produtor (10s), para o erro registrado ser o do Kafka. |
| `spring.kafka.producer.properties.max.block.ms` | `5000` | Quanto o `send()` pode travar esperando o broker. O padrão do Kafka é 60s. |

## Erros

Todas as respostas de erro seguem o formato Problem Details, como no account, inclusive a transferência recusada. Só a transferência recusada traz `code`.

| Status | Quando |
|---|---|
| `400` | Corpo inválido (campos em `errors`), header `Idempotency-Key` ausente ou que não é UUID, id que não é UUID |
| `404` | Transferência não encontrada |
| `409` | Violação de integridade inesperada |
| `422` | Transferência recusada pelo account, contas iguais ou chave reusada com dados diferentes |
| `500` | Erro inesperado (o detalhe vai só para o log) |

## Banco de dados

As migrações Flyway ficam em `src/main/resources/db/migration`, e o Hibernate roda com `ddl-auto: validate`.

| Tabela | Conteúdo |
|---|---|
| `transfers` | Contas, valor, status, código e motivo da falha, `idempotency_key` (única), `claimed_until` (reserva do reconciliador) e `version` (lock otimista) |
| `outbox_events` | Eventos a publicar no Kafka: tipo, chave, payload em JSONB, `published_at`, `claimed_until` (reserva do publicador), tentativas e último erro. Um evento por tipo para cada transferência. |

O banco também garante as regras com constraints: status válido, valor positivo, contas diferentes, código de falha válido e código presente só nas `FAILED`. Índices parciais sobre as `PENDING` e sobre os eventos não publicados deixam as buscas rápidas mesmo com as tabelas grandes.

## Testes

```bash
cd matilha-transfer
./mvnw verify
```

O Docker precisa estar rodando, porque os testes que sobem a aplicação usam um PostgreSQL real com Testcontainers. Os testes cobrem:

- **Contexto**: a aplicação sobe com as migrações do Flyway e o `ddl-auto: validate`
- **Entidades**: validações, transições de status, comparação de pedidos e o evento do outbox
- **Persistência**: as migrações contra o PostgreSQL, as constraints, a chave de idempotência única, a tabela do outbox e a busca do reconciliador. Um teste abre duas transações ao mesmo tempo e confere que o `SKIP LOCKED` dá lotes diferentes para cada uma, sem esperar.
- **Reconciliador**: o corte por idade, o lote, a reserva e a continuidade quando uma transferência falha (com Mockito)
- **Outbox na mesma transação** (`TransferFinisherTest`, contra o PostgreSQL): o status e o evento são gravados juntos. Se o evento não puder ser gravado, o status volta. Quando duas cópias terminam a mesma transferência, só o evento da vencedora fica.
- **Reserva do outbox** (`OutboxServiceTest`, contra o PostgreSQL): a busca com `SKIP LOCKED`, a reserva com prazo, a marcação de publicado e o registro de falhas
- **Publicador** (`OutboxPublisherTest`, com o Kafka simulado): marca como publicado só depois da confirmação, registra a falha e o tempo esgotado, e para o lote na primeira falha
- **AccountClient**: as respostas do account contra um servidor HTTP de verdade, incluindo conexão recusada e timeout. O teste de timeout leva uns 5 segundos.
- **Service**: sucesso, recusa, indisponibilidade, reenvio e corrida pela mesma chave (com Mockito)
- **Controller**: status HTTP de cada resultado, header de idempotência, validação e erros (com MockMvc)

### Postman

A collection [postman/matilha.postman_collection.json](../postman/matilha.postman_collection.json) testa o fluxo inteiro pelo gateway, com o sistema rodando no perfil `dev`. Ela inclui depósito, sucesso, idempotência (conferindo que o saldo muda uma vez só), recusas, consulta, validação e notificações. Rode as pastas 0 a 6 pelo Collection Runner. A pasta 7 é manual: você para o account, cria uma transferência (`202 PENDING`), sobe o account de novo e confere que o reconciliador a marcou `COMPLETED`.

Para rodar o serviço na IDE sem o banco do compose, use o `main` de `TestTransferApplication` (em `src/test`). Ele sobe a aplicação com o mesmo PostgreSQL dos testes. O account e o Kafka continuam vindo de fora (`docker compose up -d account kafka` na raiz). Sem o account, as transferências ficam `PENDING`. Sem o Kafka, os eventos esperam no outbox.

## CI

O workflow [transfer-ci.yml](../.github/workflows/transfer-ci.yml) roda em push para `main` e em pull requests que mexem em `matilha-transfer/`:

1. **test**: `./mvnw -B verify` com Java 25
2. **docker**: build da imagem, só se os testes passarem

## Estrutura

```
matilha-transfer/
├── src/main/java/com/matilha/transfer/
│   ├── client/        # AccountClient (chamadas ao matilha-account)
│   ├── component/     # TransferReconciler e OutboxPublisher (tarefas agendadas)
│   ├── config/        # OpenAPI, agendamento e tópico do Kafka
│   ├── controller/    # Endpoints de transferência
│   ├── dto/           # Records de request/response (próprios e do account) e eventos
│   ├── entity/        # Transfer, TransferStatus e OutboxEvent
│   ├── exception/     # Exceções e handler global
│   ├── repository/    # Repositórios Spring Data
│   └── service/       # TransferService, TransferFinisher (resultado + evento) e OutboxService
├── src/main/resources/
│   └── db/migration/  # Migrações versionadas
├── Dockerfile
└── compose.yaml       # PostgreSQL + serviço
```
