# matilha-notification

Serviço que consome os eventos de transferência publicados pelo [matilha-transfer](../matilha-transfer/), registra uma notificação para cada conta envolvida e permite consultar as notificações de uma conta.

Faz parte do [Matilha](../README.md).

## Por que ele não manda e-mail

Os dados do titular (nome, e-mail, telefone) moram no matilha-account, e os eventos de transferência levam só números de conta, valor e código do resultado. Para mandar um e-mail de verdade, o notification teria que consultar o account de forma síncrona a cada evento, ou o evento teria que carregar dados pessoais. As duas opções anulam o motivo de usar eventos.

Por isso ele é mínimo: registra no banco e no log que a notificação foi gerada para a conta. Isso basta para mostrar o padrão que importa aqui, que é o consumo idempotente com Dead Letter Topic.

## Como rodar

Pré-requisitos: Docker. Para rodar fora do container, também JDK 25.

**1. Configure as variáveis de ambiente**

```bash
cd matilha-notification
cp .env.example .env
```

Edite o `.env` e troque usuário e senha do banco:

```properties
NOTIFICATION_DB_NAME=matilha_notification
NOTIFICATION_DB_USERNAME=seu-usuario
NOTIFICATION_DB_PASSWORD=sua-senha
NOTIFICATION_DB_URL=jdbc:postgresql://localhost:5434/matilha_notification
```

O `.env` está no `.gitignore` e nunca deve ser commitado.

**2a. Sistema inteiro:** `docker compose up --build` na raiz do repositório.

**2b. Banco e Kafka no Docker, serviço na máquina (bom para desenvolver)**

```bash
# na raiz do repositório
docker compose up -d notification-db kafka

cd matilha-notification
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

O `docker compose` roda **na raiz**, e não dentro de `matilha-notification/`. O notification depende do Kafka, que é definido em `infra/compose.yaml`, e só a raiz inclui esse arquivo. As aspas no `-D...` são necessárias no PowerShell, que quebraria o argumento no ponto.

O serviço sobe em `http://localhost:8083`, com o banco na porta `5434`. As duas portas ficam ligadas só a `127.0.0.1`.

A 8083 serve para depuração e para o Swagger. A entrada oficial da API é o [matilha-gateway](../matilha-gateway/), em `http://localhost:8080`, que repassa `/notifications/**` para cá.

Para gerar notificações, o transfer (e o account) precisam estar rodando. Sem eles, o notification sobe e fica esperando eventos no tópico.

| Variável | Padrão | Uso |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Endereço do Kafka. No Docker, o `compose.yaml` aponta para `kafka:29092`, o listener interno do broker. |
| `NOTIFICATION_DB_POOL_SIZE` | `5` | Tamanho do pool de conexões. É menor que nos outros serviços porque o consumidor processa um evento por vez. |

## Perfis

Iguais aos dos outros serviços: o padrão exige as variáveis `NOTIFICATION_DB_*` do ambiente, e o `dev` lê o `.env`, liga o Swagger e mostra os detalhes do health.

## Documentação da API

Com o perfil `dev` ativo:

- Swagger UI: http://localhost:8083/swagger-ui.html
- OpenAPI (JSON): http://localhost:8083/v3/api-docs

Health check: http://localhost:8083/actuator/health. Probes em `/actuator/health/liveness` e `/actuator/health/readiness`.

## Endpoint

| Método | Rota | Descrição | Sucesso |
|---|---|---|---|
| `GET` | `/notifications?accountNumber=10001` | Notificações de uma conta, paginadas | `200` |

- **Conta:** 5 dígitos, como nos outros serviços. Fora disso, `400`.
- **Paginação:** `page` e `size`, no mesmo formato do account. Sem parâmetros, vale a primeira página com 20 itens, a mais recente primeiro. Um `size` acima de 100 é reduzido para 100.
- **Ordem:** por padrão `createdAt` decrescente, que é a ordem do índice `(account_number, created_at DESC)`. O cliente pode pedir outra com `?sort=`, como no account.
- **Conta sem notificações** (ou que não existe): página vazia, e não `404`. O notification não conhece as contas, só as notificações que gerou.

```bash
curl "http://localhost:8083/notifications?accountNumber=10001"
```

```json
{
  "content": [
    {
      "id": "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f",
      "transferId": "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d",
      "accountNumber": "10001",
      "type": "TRANSFER_SENT",
      "message": "Você enviou R$ 250,50 para a conta 10002",
      "failureCode": null,
      "createdAt": "2026-11-02T15:30:00Z"
    }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

## Como funciona

```
matilha.transfer.events (Kafka)                     group-id: matilha-notification
  │
  ├─ lê o JSON como texto e converte                 (TransferEventMessageDto)
  ├─ monta as notificações                           (NotificationFactory, sem Spring)
  │     JSON inválido, versão nova, tipo desconhecido,
  │     conta ou valor inválido ─────────────────────▶ DLT na hora
  ├─ eventId já em processed_events? ──sim──▶ ignora a duplicata
  ├─ grava processed_events + notificações           uma transação só
  └─ confirma o offset no Kafka                      só depois do commit
```

**Regras de notificação**

| Evento | Notificações |
|---|---|
| `TransferCompleted` | `TRANSFER_SENT` para a conta de origem e `TRANSFER_RECEIVED` para a de destino |
| `TransferFailed` | `TRANSFER_FAILED` para a conta de origem, com o `failureCode` |

O valor aparece em reais, no formato brasileiro: o `amount` `"1234567.89"` do evento vira `R$ 1.234.567,89` na mensagem. A formatação usa `DecimalFormat` com os símbolos de `pt-BR`, e não `NumberFormat.getCurrencyInstance`, que poria um espaço não separável (U+00A0) depois do "R$": na tela fica igual, mas quebra comparações de texto e buscas no log.

**Contrato do evento**

- O `TransferEventMessageDto` é uma cópia própria do evento do transfer. A classe do transfer não é importada (ADR-0009).
- Campos desconhecidos são ignorados, então o transfer pode acrescentar campos sem quebrar o consumidor.
- Um `eventVersion` maior que o suportado (1) vai para a DLT: é uma estrutura que este consumidor não sabe ler.
- O `failureCode` chega como texto e é convertido por `FailureCode.fromCode()`. Um código desconhecido, ou ausente, vira `REJECTED_BY_ACCOUNT`, a mesma regra do transfer com os códigos do account (ADR-0007). O cliente é avisado da falha mesmo que o transfer crie um código novo.

**Idempotência**

- A entrega do transfer é "pelo menos uma vez" (outbox), então o mesmo evento pode chegar mais de uma vez.
- A tabela `processed_events` (com `event_id` como chave primária) é gravada na mesma transação que as notificações. Se o `eventId` já está lá, o evento é ignorado.
- O `ProcessedEvent` implementa `Persistable`, para o `save()` fazer um `INSERT` direto, e não um merge (SELECT seguido de INSERT). Se dois consumidores processarem o mesmo evento ao mesmo tempo, a chave primária rejeita o segundo e a transação dele inteira volta. Na nova tentativa, ele encontra o evento e o ignora.
- Uma tabela separada, e não `UNIQUE (event_id)` em `notifications`, porque um evento gera de 0 a 2 notificações.
- O transfer usa a conta de origem como chave da mensagem. Eventos da mesma conta caem na mesma partição e chegam em ordem.

**Erros e Dead Letter Topic**

| Tipo de erro | Exemplos | O que acontece |
|---|---|---|
| Mensagem envenenada | JSON inválido, `eventVersion` maior que 1, `eventType` desconhecido, conta vazia ou sem 5 caracteres, `amount` vazio ou que não é número | Vai **direto** para `matilha.transfer.events.DLT`, sem novas tentativas |
| Falha de infraestrutura | Banco fora do ar | Tenta de novo **sem limite**, com espera de 1s, 2s, 4s... até 30s |

- **Mensagem envenenada** é aquela em que o defeito está na própria mensagem: ela falha sempre. **Falha de infraestrutura** é aquela em que o defeito está no ambiente: todas as mensagens falham, e todas passariam se o ambiente voltasse. Mandar essas para a DLT encheria o tópico de mensagens boas. Esperar preserva a ordem e não perde nada.
- A DLT é criada pelo notification com 3 partições. A mensagem vai para a mesma partição da original, por isso a DLT precisa de pelo menos tantas partições quanto o tópico.
- Os cabeçalhos da mensagem na DLT guardam a exceção, a mensagem de erro e a origem (tópico, partição e offset).
- O que o banco recusaria (conta com tamanho errado, por exemplo) é recusado antes, no construtor da `Notification`, como `BusinessException`. Sem isso, o erro chegaria como violação de constraint, seria repetido para sempre e travaria a partição.
- Pelo mesmo motivo, um `amount` inválido vira `UnsupportedEventException` na `NotificationFactory`. Um `NumberFormatException` ou `NullPointerException` cru seria tratado como falha passageira e repetido sem fim.
- O tópico `matilha.transfer.events` é do transfer, que o cria. O notification cria só a DLT.

Detalhes e alternativas no [ADR-0011](../docs/adr/0011-consumidor-idempotente-e-dlt.md).

## Erros da API

Todas as respostas de erro seguem o formato Problem Details, como nos outros serviços.

| Status | Quando |
|---|---|
| `400` | `accountNumber` ausente ou sem 5 dígitos |
| `500` | Erro inesperado (o detalhe vai só para o log) |

## Banco de dados

As migrações Flyway ficam em `src/main/resources/db/migration`, e o Hibernate roda com `ddl-auto: validate`.

| Tabela | Conteúdo |
|---|---|
| `notifications` | Evento e transferência de origem, conta, tipo, mensagem, código da falha e data. Índice por `(account_number, created_at DESC)` para a consulta. |
| `processed_events` | Os `eventId` já tratados, com a data. É a garantia de idempotência. |

As constraints espelham as regras: tipo válido, código de falha válido e código presente só em `TRANSFER_FAILED`.

## Testes

```bash
cd matilha-notification
./mvnw verify
```

O Docker precisa estar rodando, porque os testes de integração sobem um PostgreSQL e um Kafka reais com Testcontainers, nas mesmas versões do compose (`postgres:17` e `apache/kafka:4.3.1`). Os containers ficam no `TestcontainersConfiguration`, importado com `@Import` e reaproveitado entre as classes de teste. O account e o transfer seguem o mesmo padrão, só com o PostgreSQL.

- **Montagem das notificações** (`NotificationFactoryTest`): completed gera 2, failed gera 1 com o código, código desconhecido vira o genérico, versão e tipo não suportados são recusados, o valor sai como `R$ 1.234.567,89` (com espaço comum) e um `amount` vazio ou que não é número é recusado
- **Entidades**: as regras do construtor da `Notification`, a conversão do `FailureCode` e a chave primária de `processed_events` contra o PostgreSQL (o segundo `save` com o mesmo id precisa falhar)
- **Consumidor** (`TransferEventListenerTest`, Kafka real): completed, failed, código desconhecido, campos desconhecidos e o mesmo evento enviado duas vezes. Para provar que a cópia repetida já foi consumida sem usar `sleep`, o teste envia um evento "marcador" com a mesma chave logo depois: quando o marcador aparece, a cópia já passou.
- **DLT** (`TransferEventDeadLetterTest`, Kafka real): JSON inválido, versão nova, tipo desconhecido, conta vazia e valor inválido chegam à DLT, na mesma partição e com os cabeçalhos do erro. Uma mensagem envenenada não trava as seguintes.
- **Consulta** (`NotificationQueryTest`, PostgreSQL real): filtro por conta, ordem, paginação e mapeamento
- **Controller** (`NotificationControllerTest`, MockMvc): página, ordem padrão e limite de 100 do `PageableConfig`, `400` sem `accountNumber` ou com conta inválida e `500` sem vazar detalhes

O consumidor é esperado com o Awaitility, nunca com `Thread.sleep`.

Para rodar o serviço na IDE sem o compose, use o `main` de `TestNotificationApplication` (em `src/test`). Ele sobe a aplicação com os mesmos containers dos testes.

## CI

O workflow [notification-ci.yml](../.github/workflows/notification-ci.yml) roda em push para `main` e em pull requests que mexem em `matilha-notification/`:

1. **test**: `./mvnw -B verify` com Java 25
2. **docker**: build da imagem, só se os testes passarem

## Estrutura

```
matilha-notification/
├── src/main/java/com/matilha/notification/
│   ├── component/     # TransferEventListener (consumidor do Kafka)
│   ├── config/        # Kafka (DLT e error handler), OpenAPI e paginação
│   ├── controller/    # GET /notifications
│   ├── dto/           # Cópia do evento do transfer e resposta da API
│   ├── entity/        # Notification, ProcessedEvent, NotificationType e FailureCode
│   ├── exception/     # Exceções e handler global
│   ├── repository/    # Repositórios Spring Data
│   └── service/       # NotificationService e NotificationFactory
├── src/main/resources/
│   └── db/migration/  # Migrações versionadas
├── Dockerfile
└── compose.yaml       # PostgreSQL + serviço
```

## Próximos passos

Fora do escopo desta fase, de propósito:

- E-mail, SMS ou push de verdade, templates de mensagem e preferências do usuário
- `@RetryableTopic` (tópicos de retry com atraso, que não travam a partição)
- Schema Registry com Avro, para validar o contrato na publicação
- Uma ferramenta para reprocessar a DLT
- Limpeza dos registros antigos de `processed_events`
- `?sort=` com campo inexistente devolvendo `400`, e não `500` (vale para o account também)
