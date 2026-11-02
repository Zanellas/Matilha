# matilha-notification

Serviço que consome os eventos de transferência publicados pelo [matilha-transfer](../matilha-transfer/) e registra uma notificação para cada conta envolvida.

Faz parte do [Matilha](../README.md).

> **Status:** esqueleto. O serviço sobe, conecta no próprio banco e responde no health, mas ainda não consome eventos. O consumidor é o próximo passo (veja [O que ele vai fazer](#o-que-ele-vai-fazer)).

## Por que ele não manda e-mail

Os dados do titular (nome, e-mail, telefone) moram no matilha-account, e os eventos de transferência levam só números de conta, valor e código do resultado. Para mandar um e-mail de verdade, o notification teria que consultar o account de forma síncrona a cada evento, ou o evento teria que carregar dados pessoais. As duas opções anulam o motivo de usar eventos.

Por isso ele é mínimo: registra no banco e no log que a notificação foi gerada para a conta. Isso basta para mostrar o padrão que importa aqui, que é o consumo idempotente.

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

**2. Banco no Docker e serviço na máquina (bom para desenvolver)**

```bash
cd matilha-notification
docker compose up -d notification-db
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

As aspas no `-D...` são necessárias no PowerShell, que quebraria o argumento no ponto. No bash elas não atrapalham.

O serviço sobe em `http://localhost:8083`, com o banco na porta `5434`. As duas portas ficam ligadas só a `127.0.0.1`.

Ao rodar o compose de dentro desta pasta, o Docker avisa sobre "orphan containers" (o Kafka, o account e o transfer). É porque todos os compose usam o mesmo projeto `matilha`. O aviso é inofensivo. **Não use `--remove-orphans`**: ele apagaria os containers dos outros serviços.

| Variável | Padrão | Uso |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Endereço do Kafka. No Docker, o `compose.yaml` aponta para `kafka:29092`, o listener interno do broker. |
| `NOTIFICATION_DB_POOL_SIZE` | `5` | Tamanho do pool de conexões. É menor que nos outros serviços porque cada evento gera uma inserção só. |

## Perfis

Iguais aos dos outros serviços: o padrão exige as variáveis `NOTIFICATION_DB_*` do ambiente, e o `dev` lê o `.env` e mostra os detalhes do health.

## Health

O notification não tem API. Ele expõe só o Actuator:

- Health check: http://localhost:8083/actuator/health
- Probes: `/actuator/health/liveness` e `/actuator/health/readiness`

## O que ele vai fazer

```
matilha.transfer.events (Kafka)
  │
  ├─ consome TransferCompleted / TransferFailed     (group-id: matilha-notification)
  ├─ eventId já visto? ──sim──▶ descarta a duplicata
  ├─ grava uma notificação por conta envolvida      (tabela notifications)
  ├─ escreve no log: "notificação gerada para a conta X"
  └─ falhou depois das tentativas? ──▶ Dead Letter Topic
```

- **Pelo menos uma vez:** o publicador do transfer pode reenviar um evento (por exemplo, quando cai entre a confirmação do Kafka e a marcação de publicado). O notification descarta duplicatas pelo `eventId`, com uma restrição única no banco.
- **Dead Letter Topic:** uma mensagem que não pode ser processada (JSON inválido, por exemplo) vai para um tópico separado depois de algumas tentativas, em vez de travar a partição.
- **`auto-offset-reset: earliest`:** na primeira vez que o grupo conecta, ele lê o tópico desde o começo. Os eventos publicados antes de o notification existir também são consumidos.

## Testes

```bash
cd matilha-notification
./mvnw verify
```

O Docker precisa estar rodando, porque o teste de contexto sobe um PostgreSQL e um Kafka reais com Testcontainers, nas mesmas versões do compose (`postgres:17` e `apache/kafka:4.3.1`).

Para rodar o serviço na IDE sem o compose, use o `main` de `TestNotificationApplication` (em `src/test`). Ele sobe a aplicação com os mesmos containers dos testes.

## Estrutura

```
matilha-notification/
├── src/main/java/com/matilha/notification/
│   └── NotificationApplication.java
├── src/main/resources/
│   ├── application.yml
│   └── application-dev.yml
├── src/test/java/com/matilha/notification/
│   ├── NotificationApplicationTests.java     # teste de contexto
│   ├── TestcontainersConfiguration.java      # PostgreSQL e Kafka para os testes
│   └── TestNotificationApplication.java      # roda o serviço com os containers dos testes
├── Dockerfile
└── compose.yaml       # PostgreSQL + serviço
```
