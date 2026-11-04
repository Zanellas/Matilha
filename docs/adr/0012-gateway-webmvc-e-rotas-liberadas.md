# ADR-0012: Gateway Web MVC com rotas por lista de liberação

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Até a Fase 1, o cliente chamava cada serviço direto na porta dele (8081, 8082 e 8083). Isso traz três problemas:

- O cliente precisa conhecer o endereço de cada serviço, e qualquer mudança de porta ou de serviço quebra o cliente.
- Os endpoints internos ficam acessíveis de fora. O account tem `/internal/transfers` (usado só pelo transfer) e `/internal/deposits` (a entrada de dinheiro externo), e nenhum dos dois pode ser chamado por um cliente comum.
- Quando a autenticação chegar (Fase 4), ela teria que ser repetida em cada serviço.

O Spring Cloud Gateway existe em duas versões: a **WebFlux** (reativa, a mais antiga e a que aparece na maioria dos tutoriais) e a **Server Web MVC** (servlet). Os três serviços do Matilha usam Spring MVC.

## Decisão

- **Um serviço `matilha-gateway`**, na porta 8080, como a única porta de entrada oficial. Ele não tem banco nem regra de negócio, e é quase todo configuração.
- **Spring Cloud Gateway Server Web MVC** (`spring-cloud-starter-gateway-server-webmvc`), na linha 2025.1.x (Oakwood), compatível com o Spring Boot 4.1. O projeto inteiro fica com um modelo de programação só, e o gateway é testado com as mesmas ferramentas dos outros serviços.
- **Rotas por lista de liberação.** O gateway conhece só três rotas:

  | Caminho | Destino |
  |---|---|
  | `/accounts/**` | matilha-account |
  | `/transfers/**` | matilha-transfer |
  | `/notifications/**` | matilha-notification |

  Qualquer outro caminho, inclusive `/internal/**`, não encontra rota e recebe `404`. Não existe regra de bloqueio: o bloqueio é a ausência de rota.
- **Endereços por variável de ambiente**, com padrão local: `ACCOUNT_SERVICE_URL`, `TRANSFER_SERVICE_URL` e `NOTIFICATION_SERVICE_URL`. São os mesmos nomes que o transfer já usa para chamar o account.
- **Repasse transparente.** Headers (como `Idempotency-Key` e `Location`), status e corpo passam sem alteração. Um `422` do transfer chega ao cliente com o mesmo Problem Details.
- **Erros do próprio gateway em Problem Details**, no formato dos outros serviços: `404` com `code: ROUTE_NOT_FOUND` para rota inexistente e `503` com `code: SERVICE_UNAVAILABLE` quando o serviço de destino não responde.
- **Timeouts explícitos** no cliente HTTP do gateway, como no `AccountClient` do transfer.
- **O health do gateway não depende dos serviços.** Ele fica `UP` mesmo com o account fora do ar, e o compose não faz o gateway esperar os outros serviços. Um serviço fora do ar afeta só a rota dele.

## Alternativas consideradas

- **Gateway WebFlux:** é a versão mais documentada, mas traria um segundo modelo de programação (reativo) para um projeto todo servlet, com outras ferramentas de teste e outro jeito de depurar. Nenhuma necessidade do Matilha hoje exige o modelo reativo.
- **Liberar tudo e bloquear `/internal/**`:** funciona até alguém criar um endpoint interno com outro prefixo (`/admin`, `/jobs`). Nesse dia, o endpoint fica exposto sem ninguém perceber. Com a lista de liberação, o erro é o contrário: uma rota nova esquecida dá `404`, o que aparece no primeiro teste e não abre buraco.
- **Proteger os endpoints internos em cada serviço** (por IP ou por token): continua sendo uma boa camada extra, mas espalha a regra pelos serviços. Fica para depois da Fase 4.
- **Nginx ou outro proxy reverso:** resolve o roteamento, mas a autenticação com JWT da Fase 4 e o formato de erro em Problem Details ficariam fora do ecossistema Spring que o projeto usa para estudar.
- **Health `DOWN` quando um serviço cai:** no Kubernetes, um pod que não está pronto sai do balanceamento. Se o gateway ficasse `DOWN` por causa do account, **todas** as rotas parariam, inclusive as do transfer e do notification, que estariam funcionando.

## Consequências

- O cliente conhece um endereço só (`http://localhost:8080`). No Kubernetes, só o gateway vai ficar exposto.
- `/internal/**` fica inacessível pelo gateway. O depósito, que simula a entrada de dinheiro externo, continua sendo chamado direto no account, na porta 8081.
- Todo endpoint público novo precisa de uma rota no gateway. Esquecer a rota dá `404`, nunca exposição.
- As portas 8081, 8082 e 8083 continuam publicadas em `127.0.0.1` para depuração, mas não são a entrada oficial.
- A autenticação da Fase 4 tem um lugar único para morar.
- O gateway é mais um salto de rede em cada requisição, e mais um serviço para subir e monitorar.
- Ficam fora desta fase: rate limit, CORS, Swagger agregado, circuit breaker com Resilience4j e descoberta de serviço com Eureka (no Kubernetes, o DNS dos Services já resolve os endereços).
