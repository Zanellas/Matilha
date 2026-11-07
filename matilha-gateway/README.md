# matilha-gateway

Porta de entrada da API do Matilha. Recebe as requisições dos clientes na porta 8080 e as repassa para o [matilha-account](../matilha-account/), o [matilha-transfer](../matilha-transfer/) e o [matilha-notification](../matilha-notification/). Não tem banco nem regra de negócio: é quase todo configuração.

Faz parte do [Matilha](../README.md).

Usa o Spring Cloud Gateway **Server Web MVC** (linha 2025.1.x, Oakwood), e não a versão WebFlux: é o mesmo modelo servlet dos outros três serviços. A escolha está no [ADR-0012](../docs/adr/0012-gateway-webmvc-e-rotas-liberadas.md).

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

Rodando na máquina, o devtools reinicia o gateway sozinho quando o `application.yml` muda. As rotas são lidas na subida, então sem o reinício uma rota nova não vale.

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

As rotas ficam no `application.yml`, em `spring.cloud.gateway.server.webmvc.routes`. Os tutoriais antigos usam `spring.cloud.gateway.routes`, que não funciona com o artefato `spring-cloud-starter-gateway-server-webmvc`.

Detalhes e alternativas no [ADR-0012](../docs/adr/0012-gateway-webmvc-e-rotas-liberadas.md).

## Timeouts

O gateway não tem propriedade de timeout própria: ele usa o cliente HTTP do Spring Boot, configurado em `spring.http.clients`.

| Propriedade | Valor | Por quê |
|---|---|---|
| `connect-timeout` | `2s` | O mesmo do `AccountClient` do transfer. Se o serviço nem aceita a conexão em 2 segundos, ele está fora do ar. |
| `read-timeout` | `10s` | Maior que os 5s do `AccountClient`. Um `POST /transfers` passa pelo gateway, pelo transfer e pelo account. Se o gateway desistisse antes do transfer, o cliente receberia erro enquanto a transferência ainda era concluída lá dentro. |

Mesmo nesse caso, nada se perde: o cliente reenvia com o mesmo `Idempotency-Key` e recebe o resultado certo.

## Erros

Os erros dos serviços passam sem alteração: um `422` do transfer chega ao cliente com o mesmo Problem Details.

Os erros do próprio gateway seguem o mesmo formato dos outros serviços:

| Status | `code` | Quando |
|---|---|---|
| `404` | `ROUTE_NOT_FOUND` | O caminho não está na lista de rotas |
| `503` | `SERVICE_UNAVAILABLE` | O serviço de destino não responde: conexão recusada ou timeout |
| `500` | sem `code` | Erro inesperado no próprio gateway (o detalhe vai só para o log) |

```json
{
  "title": "Service Unavailable",
  "status": 503,
  "detail": "The service is temporarily unavailable. Please try again later.",
  "instance": "/accounts",
  "code": "SERVICE_UNAVAILABLE"
}
```

- **Rota inexistente:** o Spring lança `NoResourceFoundException`, e o `GlobalExceptionHandler` acrescenta o `code` ao Problem Details padrão.
- **Serviço fora do ar:** o gateway chama os serviços com o `RestClient`, que embrulha a falha de rede numa `ResourceAccessException`. Sem tratamento, ela viraria um `500`, e o cliente acharia que o defeito é do gateway.
- **Log do 503:** é um `WARN` de uma linha, com o caminho e a causa (`java.net.ConnectException`, por exemplo), sem stack trace. Serviço fora do ar é uma falha esperada, não um bug. O corpo da resposta não mostra endereço interno nem a causa.
- **Erros dos serviços:** para o gateway, eles são respostas normais, e não exceções, por isso não passam pelo handler. O `404` do transfer ("transferência não existe") chega ao cliente como o transfer mandou, e não vira `ROUTE_NOT_FOUND`.

O health do gateway não depende dos serviços: ele fica `UP` mesmo com o account fora do ar. Se ficasse `DOWN`, o Kubernetes tiraria o gateway do balanceamento, e todas as rotas parariam por causa de um serviço só.

## Testes

```bash
cd matilha-gateway
./mvnw verify
```

Não precisa do Docker: o gateway não tem banco nem Kafka. Os testes sobem o gateway de verdade numa porta aleatória (`@SpringBootTest` com `RANDOM_PORT`), e cada serviço de destino é um servidor HTTP do JDK, a mesma técnica do `AccountClientTest` do transfer. Os endereços falsos entram pelo `@DynamicPropertySource`.

- **Rotas** (`GatewayRoutingTest`): cada caminho chega ao serviço certo, e só a ele. A query string chega intacta, e o corpo da resposta volta igual.
- **Bloqueio:** `/internal/transfers`, `/internal/deposits`, `/admin/accounts` e `/accountsx` recebem `404` com `ROUTE_NOT_FOUND`, e nenhum serviço falso recebe a requisição. O `/accountsx` prova que `/accounts/**` não casa com prefixos parecidos.
- **Headers:** em `201` e `202`, o `Idempotency-Key`, o `Content-Type` e o corpo chegam ao transfer, e o `Location` volta ao cliente.
- **Erros dos serviços:** um `422` em Problem Details chega byte a byte igual, e o `404` do transfer não vira `ROUTE_NOT_FOUND`.
- **Serviço fora do ar** (`GatewayServiceUnavailableTest`): com os três serviços apontando para portas fechadas, as três rotas devolvem `503` com `SERVICE_UNAVAILABLE`, sem vazar endereço nem causa, e o `/actuator/health/readiness` continua `UP`.

A [collection do Postman](../postman/matilha.postman_collection.json) roda o fluxo inteiro pelo gateway (`baseUrl` na 8080), e a pasta "0. Gateway" confere o bloqueio do `/internal/**`.

## CI

O workflow [gateway-ci.yml](../.github/workflows/gateway-ci.yml) roda em push para `main` e em pull requests que mexem em `matilha-gateway/`:

1. **test**: `./mvnw -B verify` com Java 25
2. **docker**: build da imagem, só se os testes passarem

## Estrutura

```
matilha-gateway/
├── src/main/java/com/matilha/gateway/
│   ├── exception/           # GlobalExceptionHandler (404 e 503 em Problem Details) e ErrorCode
│   └── GatewayApplication.java
├── src/main/resources/
│   ├── application.yml      # Rotas, timeouts, Actuator e endereços dos serviços
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
