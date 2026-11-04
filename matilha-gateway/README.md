# matilha-gateway

Porta de entrada da API do Matilha. Recebe as requisições dos clientes na porta 8080 e as repassa para o [matilha-account](../matilha-account/), o [matilha-transfer](../matilha-transfer/) e o [matilha-notification](../matilha-notification/). Não tem banco nem regra de negócio: é quase todo configuração.

Faz parte do [Matilha](../README.md).

> 🚧 **Em construção.** A estrutura do serviço está pronta. As rotas e o tratamento de erro são as próximas etapas.

## Como rodar

Pré-requisitos: Docker. Para rodar fora do container, também JDK 25.

O gateway não tem `.env` nem `.env.example`, porque não tem segredos. Os endereços dos serviços têm padrão local.

**Sistema inteiro:** `docker compose up --build` na raiz do repositório.

**Gateway na máquina (bom para desenvolver)**

```bash
cd matilha-gateway
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

O gateway sobe em `http://localhost:8080`, ligado só a `127.0.0.1`. Ele sobe mesmo com os outros serviços fora do ar: um serviço parado afeta só a rota dele.

| Variável | Padrão | No Docker |
|---|---|---|
| `ACCOUNT_SERVICE_URL` | `http://localhost:8081` | `http://account:8081` |
| `TRANSFER_SERVICE_URL` | `http://localhost:8082` | `http://transfer:8082` |
| `NOTIFICATION_SERVICE_URL` | `http://localhost:8083` | `http://notification:8083` |

## Perfis

O padrão vale para produção: health sem detalhes e erros sem mensagem nem stack trace. O `dev` só mostra os detalhes do health. Diferente dos outros serviços, o `dev` não importa `.env` e não liga Swagger, porque o gateway não tem segredos nem API própria.

Health check: http://localhost:8080/actuator/health. Probes em `/actuator/health/liveness` e `/actuator/health/readiness`.

## Rotas

**A porta oficial é a 8080.** As portas 8081, 8082 e 8083 continuam publicadas em `127.0.0.1` só para depuração. No Kubernetes, só o gateway vai ficar exposto.

| Caminho | Destino |
|---|---|
| `/accounts/**` | matilha-account |
| `/transfers/**` | matilha-transfer |
| `/notifications/**` | matilha-notification |

```bash
curl http://localhost:8080/accounts/10001
```

- **Lista de liberação:** o gateway conhece só essas três rotas. Qualquer outro caminho recebe `404`, inclusive `/internal/transfers` e `/internal/deposits`. Não existe regra de bloqueio: o bloqueio é a ausência de rota.
- **Depósito:** o `/internal/deposits` representa dinheiro vindo de fora (um PIX recebido, por exemplo) e não passa pelo gateway. Ele continua sendo chamado direto no account, em `http://localhost:8081`.
- **Repasse transparente:** headers, status e corpo passam sem alteração. O `Idempotency-Key` chega ao transfer, e o `Location` dos `201` e `202` volta ao cliente.
- **Rota nova:** todo endpoint público novo precisa de uma rota aqui. Esquecer a rota dá `404`, nunca exposição.

Detalhes e alternativas no [ADR-0012](../docs/adr/0012-gateway-webmvc-e-rotas-liberadas.md).

## Erros

Os erros dos serviços passam sem alteração: um `422` do transfer chega ao cliente com o mesmo Problem Details.

Os erros do próprio gateway seguem o mesmo formato dos outros serviços:

| Status | `code` | Quando |
|---|---|---|
| `404` | `ROUTE_NOT_FOUND` | O caminho não está na lista de rotas |
| `503` | `SERVICE_UNAVAILABLE` | O serviço de destino não responde |

O health do gateway não depende dos serviços: ele fica `UP` mesmo com o account fora do ar. Se ficasse `DOWN`, o Kubernetes tiraria o gateway do balanceamento, e todas as rotas parariam por causa de um serviço só.

## Testes

```bash
cd matilha-gateway
./mvnw verify
```

Não precisa do Docker: o gateway não tem banco nem Kafka. Os serviços de destino são simulados com o servidor HTTP do JDK, a mesma técnica do `AccountClientTest` do transfer.

## CI

O workflow [gateway-ci.yml](../.github/workflows/gateway-ci.yml) roda em push para `main` e em pull requests que mexem em `matilha-gateway/`:

1. **test**: `./mvnw -B verify` com Java 25
2. **docker**: build da imagem, só se os testes passarem

## Estrutura

```
matilha-gateway/
├── src/main/java/com/matilha/gateway/
│   └── GatewayApplication.java
├── src/main/resources/
│   ├── application.yml      # Porta, Actuator e endereços dos serviços
│   └── application-dev.yml  # Detalhes do health
├── Dockerfile
└── compose.yaml             # Só o gateway, com os endereços do Docker
```

## Próximos passos

Fora do escopo desta fase, de propósito:

- Autenticação com JWT e Keycloak (Fase 4)
- Rate limit e CORS
- Swagger agregado dos três serviços
- Circuit breaker com Resilience4j
- Descoberta de serviço com Eureka (no Kubernetes, o DNS dos Services já resolve os endereços)
