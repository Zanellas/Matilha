# Matilha

Sistema bancário em microsserviços com Java 25 e Spring Boot 4.1. Abre contas, faz depósitos e transfere dinheiro entre contas, com foco no que costuma dar errado em sistemas distribuídos: requisições repetidas, timeouts, concorrência e falhas parciais.

Cada serviço vive na sua própria pasta deste monorepo, com banco, build, testes, Dockerfile e pipeline independentes.

## Arquitetura

```mermaid
flowchart LR
    client([Cliente])
    payments([Sistema de pagamentos<br/>ex.: PIX recebido])
    gateway["matilha-gateway<br/>:8080"]
    transfer["matilha-transfer<br/>:8082"]
    account["matilha-account<br/>:8081"]
    tdb[("PostgreSQL<br/>transfer")]
    adb[("PostgreSQL<br/>account")]
    kafka{{"Kafka"}}
    notification["matilha-notification<br/>:8083"]
    ndb[("PostgreSQL<br/>notification")]

    client -->|"/accounts, /transfers, /notifications"| gateway
    gateway -->|"/accounts/**"| account
    gateway -->|"/transfers/**"| transfer
    gateway -->|"/notifications/**"| notification
    payments -.->|"POST /internal/deposits<br/>(simulado)"| account
    transfer -->|"POST /internal/transfers<br/>(REST, idempotente)"| account
    transfer --- tdb
    account --- adb
    notification --- ndb
    transfer -->|"eventos via outbox"| kafka
    kafka -->|"consumo idempotente<br/>(DLT para mensagens inválidas)"| notification
```

O cliente só fala com o gateway. Ele conhece três rotas, e qualquer outro caminho, inclusive `/internal/**`, recebe `404`. O transfer chama o account por dentro, e o depósito chega direto no account, como faria um sistema de pagamentos na rede interna.

| Serviço | Responsabilidade | Status |
|---|---|---|
| [matilha-gateway](matilha-gateway/) | Porta de entrada da API: roteia as três rotas públicas e bloqueia o resto | ✅ Pronto |
| [matilha-account](matilha-account/) | Contas, saldo e movimentações. É o único serviço que altera saldos. | ✅ Pronto |
| [matilha-transfer](matilha-transfer/) | Recebe pedidos de transferência, pede ao account para mover o dinheiro, guarda o resultado e publica o evento | ✅ Pronto |
| [matilha-notification](matilha-notification/) | Consome os eventos de transferência, registra a notificação de cada conta envolvida e permite consultá-las | ✅ Pronto |

## Como rodar

Pré-requisito: Docker.

```bash
cp matilha-account/.env.example matilha-account/.env
cp matilha-transfer/.env.example matilha-transfer/.env
cp matilha-notification/.env.example matilha-notification/.env
# edite os três .env e defina usuário e senha dos bancos

docker compose up --build
```

**A porta oficial é a 8080, do gateway.** As portas 8081, 8082 e 8083 continuam publicadas em `127.0.0.1` para depuração e para o Swagger de cada serviço, mas não são a entrada da API. No Kubernetes, só o gateway vai ficar exposto.

| O quê | Endereço |
|---|---|
| **matilha-gateway (API)** | **http://localhost:8080** |
| matilha-account (Swagger) | http://localhost:8081/swagger-ui.html |
| matilha-transfer (Swagger) | http://localhost:8082/swagger-ui.html |
| matilha-notification (Swagger) | http://localhost:8083/swagger-ui.html |
| Kafka UI | http://localhost:8090 |

Os serviços sobem no perfil `dev`, que carrega contas de exemplo (`10001` a `10005`). Para testar uma transferência:

```bash
curl -i -X POST http://localhost:8080/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 3f1c2a9e-7b4d-4c1a-9e2f-5a6b7c8d9e01" \
  -d '{"sourceAccountNumber": "10001", "targetAccountNumber": "10002", "amount": 250.50}'
```

Rode o mesmo comando de novo: a resposta é a mesma e o dinheiro só sai da conta uma vez.

Em seguida, veja as notificações geradas pelo evento da transferência, uma para cada conta:

```bash
curl "http://localhost:8080/notifications?accountNumber=10001"   # TRANSFER_SENT
curl "http://localhost:8080/notifications?accountNumber=10002"   # TRANSFER_RECEIVED
```

E veja o gateway bloqueando um endpoint interno, sem que a requisição chegue ao account:

```bash
curl -i -X POST http://localhost:8080/internal/deposits   # 404, code ROUTE_NOT_FOUND
```

O `compose.yaml` da raiz inclui a infraestrutura (`infra/compose.yaml`: Kafka e Kafka UI) e o compose de cada serviço. Para subir só uma parte, rode na raiz com o nome dos serviços, por exemplo `docker compose up -d transfer-db kafka`.

Outros modos de rodar, endpoints, regras e testes estão no README de cada serviço. A [collection do Postman](postman/matilha-transfer.postman_collection.json) testa o fluxo inteiro pelo gateway: depósito (direto no account, porque é interno), transferência e notificações.

## Decisões principais

- **Gateway com lista de liberação.** O matilha-gateway (Spring Cloud Gateway Server Web MVC, o mesmo modelo servlet dos outros serviços) conhece só `/accounts`, `/transfers` e `/notifications`. Não existe regra que bloqueia `/internal/**`: o bloqueio é a ausência de rota. Assim, um endpoint interno novo, com qualquer prefixo, nasce protegido. Serviço fora do ar vira `503` em Problem Details, e o health do gateway não depende dos serviços, para a queda de um não derrubar as rotas dos outros. Detalhes no [ADR-0012](docs/adr/0012-gateway-webmvc-e-rotas-liberadas.md).
- **Um banco por serviço.** O transfer nunca toca no banco do account. Ele pede a operação pela API interna, e o account é o único dono do saldo.
- **Idempotência ponta a ponta.** O cliente manda um `Idempotency-Key`. O transfer repassa o id da transferência ao account, que também é idempotente. Um reenvio, seja do cliente ou do próprio sistema, nunca move o dinheiro duas vezes.
- **Depósito é entrada externa.** O `/internal/deposits` representa dinheiro vindo de fora, como um PIX recebido, e é chamado pelo sistema de pagamentos com o ID da operação dele. Como o Matilha não tem esse sistema, ele é simulado por chamada direta. Não é uma operação administrativa e não passa pelo gateway.
- **Dados pessoais ficam no account.** Os eventos de transferência levam só números de conta, valor e código do resultado, nunca nome, e-mail ou telefone. Por isso o matilha-notification não manda e-mail: ele registra no banco e no log que a notificação foi gerada para a conta. Enviar e-mail de verdade exigiria que ele consultasse o account de forma síncrona ou que o evento carregasse dados pessoais, e os dois anulam o motivo de usar eventos. O que ele demonstra é o consumo idempotente: uma mensagem repetida não gera uma segunda notificação.
- **Timeout não é falha.** Se o account não responde, a transferência fica `PENDING`, não `FAILED`, porque o dinheiro pode ter sido movido e a resposta ter se perdido. Um reconciliador agendado reenvia as pendentes. Ele usa `FOR UPDATE SKIP LOCKED` com uma reserva com prazo, então funciona com várias réplicas.
- **Sem transação aberta durante chamada de rede.** O transfer grava `PENDING`, chama o account fora de transação e grava o resultado numa segunda transação.
- **Eventos pelo transactional outbox.** O evento é gravado na tabela `outbox_events` na mesma transação que muda o status da transferência, então não existe transferência terminada sem evento, nem evento de algo que não foi gravado. Um publicador agendado envia ao Kafka e só marca como publicado depois da confirmação do broker. Ele usa o mesmo `SKIP LOCKED` com reserva do reconciliador. A entrega é "pelo menos uma vez", e o consumidor descarta duplicatas pelo `eventId`.
- **Consumidor idempotente com Dead Letter Topic.** O notification grava o `eventId` em `processed_events` na mesma transação que as notificações, e só confirma o offset no Kafka depois do commit. Um evento repetido é ignorado. Uma mensagem que nunca vai funcionar (JSON inválido, versão ou tipo desconhecido) vai direto para a DLT. Uma falha de infraestrutura, como o banco fora do ar, é repetida sem limite com espera crescente, para não mandar mensagens boas para a DLT.
- **Concorrência resolvida no banco.** O account trava as contas com `SELECT ... FOR UPDATE`, sempre na mesma ordem, o que evita deadlock. O saldo não negativo é garantido na entidade e numa constraint.
- **Schema só pelo Flyway.** O Hibernate roda com `ddl-auto: validate`, e os testes sobem um PostgreSQL real com Testcontainers, então testam o mesmo schema de produção.
- **Erros no formato Problem Details (RFC 9457), com um `code` como contrato.** O account devolve códigos como `INSUFFICIENT_BALANCE`. O transfer converte esses códigos para um enum próprio, grava na transferência e repassa no evento. Nenhum serviço depende do texto em inglês do `detail`, e o detalhe interno de um erro inesperado fica só no log.

## Stack

Java 25, Spring Boot 4.1 (Web MVC, Data JPA, Validation, Actuator), Spring Cloud Gateway Server Web MVC (2025.1, Oakwood), PostgreSQL 17, Flyway, Kafka (Spring Kafka), RestClient, springdoc-openapi, JUnit 5, Mockito, MockMvc, Testcontainers, Awaitility, Docker Compose e GitHub Actions (um workflow por serviço: testes e build da imagem).

## Roadmap

- [x] matilha-account: contas, depósitos e transferências com lock e idempotência
- [x] matilha-transfer: orquestração síncrona com idempotência e reconciliador de pendentes
- [x] Códigos de erro no Problem Details, propagados do account até o evento
- [x] Eventos de transferência no Kafka com transactional outbox
- [x] matilha-notification consumindo os eventos (registro idempotente, sem envio real)
- [x] matilha-gateway como porta de entrada, com rotas por lista de liberação
- [ ] Autenticação com Keycloak
- [ ] Deploy no Kubernetes
