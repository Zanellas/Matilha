# ADR-0013: Kubernetes local com kind, sem Ingress e sem operators

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Até a Fase 2, o Matilha rodava só no Docker Compose, com uma instância de cada serviço. Vários ADRs prometem que o sistema funciona com mais de uma instância, mas isso nunca tinha sido testado:

- o lock pessimista em ordem fixa evita deadlock entre transações concorrentes (ADR-0003);
- uma transferência com resultado desconhecido fica `PENDING`, e não `FAILED` (ADR-0005);
- o reconciliador e o publicador do outbox usam `SKIP LOCKED` com lease, para várias réplicas não pegarem o mesmo registro (ADR-0006 e ADR-0008);
- o consumidor do notification é idempotente e divide as partições do Kafka com as outras réplicas do mesmo grupo (ADR-0011).

Com uma instância só, nenhuma dessas promessas é posta à prova. Era preciso um ambiente com várias réplicas, sem custo e que rodasse na máquina de desenvolvimento.

## Decisão

- **Cluster local com kind.** O kind roda o Kubernetes dentro de um container Docker, que faz o papel do nó. O cluster tem um nó só, o `matilha-control-plane`, e é criado a partir do `k8s/kind-config.yaml`.
- **Kustomize, sem Helm.** O Kustomize já vem no kubectl (`kubectl apply -k k8s`). Os manifests são YAML puro, sem templates, e o `kustomization.yaml` põe tudo no namespace `matilha`.
- **Só o gateway exposto, por NodePort, sem Ingress.** O Service do gateway é `NodePort` na 30080, e o `extraPortMappings` do kind liga essa porta à `127.0.0.1:8080` da máquina. Os outros Services são `ClusterIP`, acessíveis só de dentro do cluster ou por `port-forward`. A aplicação já tem um gateway (ADR-0012), então não precisa de outro na frente dele.
- **Infraestrutura em StatefulSets simples, sem operators.** Um Postgres por serviço e um Kafka KRaft de nó único, cada um com um volume persistente de 1Gi, como no compose. No compose, o Kafka não tinha volume; no cluster ele tem.
- **Os mesmos nomes do compose.** Os Services se chamam `account`, `account-db`, `kafka` e assim por diante, e as variáveis de ambiente são as mesmas (`ACCOUNT_DB_URL`, `ACCOUNT_SERVICE_URL`, `KAFKA_BOOTSTRAP_SERVERS`). Assim, as URLs entre os serviços não mudam: `http://account:8081`. A única diferença é o Kafka, que no cluster tem um listener só, `kafka:9092`, anunciado com o nome do Service.
- **Configuração de produção, sem o perfil `dev`.** No cluster, os serviços rodam com o `application.yml` padrão: Swagger desligado, health sem detalhes e erros sem mensagem interna. As contas de exemplo do account entram pelo `SPRING_FLYWAY_LOCATIONS` no ConfigMap, sem ligar o `dev`.
- **ConfigMap para o que não é segredo, Secret para usuário e senha.** Cada serviço lê usuário e senha do mesmo Secret do seu banco, por `secretKeyRef`, para existir uma fonte só. Os `*-secret.yaml` reais ficam fora do Git; só os `*-secret.example.yaml` são commitados, como o `.env` e o `.env.example`.
- **Imagens com tag fixa.** As imagens são geradas como `matilha-*:dev`, carregadas no nó com `kind load` e usadas com `imagePullPolicy: IfNotPresent`. Com `latest`, o Kubernetes tentaria baixá-las da internet.
- **`enableServiceLinks: false` em todos os pods.** Sem isso, o Kubernetes injeta variáveis como `KAFKA_PORT=tcp://...` para cada Service do namespace, e a imagem do Kafka lê toda variável `KAFKA_*` como configuração.
- **Probes do Actuator.** `startupProbe` em `/actuator/health/liveness` com até 150 segundos para a JVM subir; `livenessProbe` no mesmo caminho; `readinessProbe` em `/actuator/health/readiness`.
- **Desligamento gracioso.** Um `preStop` de 5 segundos dá tempo para o Kubernetes tirar o pod do Service antes de o Spring começar o shutdown, que tem 20 segundos para terminar as requisições. O `terminationGracePeriodSeconds: 40` cobre os dois.
- **Limites de memória.** 768Mi nos serviços com banco e 512Mi no gateway. O `-XX:MaxRAMPercentage=75` do Dockerfile calcula o heap a partir desse limite.
- **Duas réplicas de cada serviço.** É o motivo da fase: provar as promessas dos ADRs com processos diferentes.
- **Scripts para operar.** `k8s/deploy.ps1` cria o cluster, gera as imagens, carrega no kind e aplica os manifests. `k8s/destroy.ps1` apaga o cluster. Cada prova tem um script em `k8s/provas/`.

## Provas com duas réplicas

| Prova | Resultado |
|---|---|
| 40 transferências simultâneas em sentidos opostos (10001 → 10002 e 10002 → 10001), com dois pods do account processando (ADR-0003) | 40 de 40 `COMPLETED`, nenhum deadlock, e a soma dos saldos igual antes e depois. O access log do Tomcat confirmou chamadas nos dois pods do account. |
| Account com 0 réplicas, transferência criada, account de volta (ADR-0005 e ADR-0006) | `202 PENDING` com o account fora do ar, e `COMPLETED` depois que ele voltou. O log do reconciliador aparece em uma réplica do transfer só. |
| `rollout restart` do account com um `POST /transfers` a cada 200 ms durante 60 segundos | 253 `COMPLETED`, 1 `PENDING` que o reconciliador completou, nenhum erro para o cliente e a soma dos saldos igual |
| Duas réplicas do notification no grupo `matilha-notification` (ADR-0011) | As 3 partições divididas entre as duas réplicas, cada uma consumindo os eventos da sua parte, com `LAG` 0 |

O `PENDING` do `rollout restart` não é falha. Uma chamada do transfer pegou uma réplica do account no meio do desligamento, por uma conexão reaproveitada. O transfer não sabia se o dinheiro tinha sido movido, então respondeu `202` em vez de errar, e o reconciliador terminou a transferência. É a decisão do ADR-0005 funcionando numa operação de rotina, e não só num teste que derruba o serviço de propósito.

## Três perguntas que o Kubernetes levanta

**O que acontece sem `depends_on`?** O Kubernetes não tem ordem de subida entre pods. Se o notification sobe antes do Kafka ou do banco, ele falha na inicialização, o container cai, e o Kubernetes o reinicia com espera crescente até dar certo. Isso aconteceu de verdade: depois de algumas pausas e retomadas do cluster, o notification tinha mais reinícios que a infraestrutura, e nenhum evento se perdeu. Aqui, isso é aceitável, porque os serviços são idempotentes e não fazem nada antes de estarem prontos: um pod que ainda não passou na readiness não recebe tráfego. Um initContainer esperando o banco deixaria o log mais limpo, mas esconderia a dependência em vez de tolerá-la, e o serviço precisa tolerar a queda do banco de qualquer jeito, não só na subida.

**Por que duas réplicas rodando o Flyway ao mesmo tempo não quebram o banco?** No PostgreSQL, o Flyway pega um *advisory lock* antes de migrar. A primeira réplica que pega o lock aplica as migrações; a outra espera, e quando consegue o lock encontra a tabela de histórico já atualizada e não tem nada a fazer.

**Por que a readiness não checa o banco nem o Kafka?** Pelo mesmo motivo do gateway no ADR-0012. Se a readiness dependesse do banco, uma queda do banco tiraria todas as réplicas do Service ao mesmo tempo. O cliente receberia erro de conexão em vez de uma resposta do serviço, e as rotas que não precisam do banco parariam junto. A readiness responde à pergunta "este pod consegue atender?", e a falha do banco é tratada pelo próprio serviço, que responde com o erro certo.

## Alternativas consideradas

- **minikube, k3d ou o Kubernetes do Docker Desktop:** resolvem o mesmo problema. O kind foi escolhido porque o cluster é só um container Docker, cria e apaga em segundos, e a configuração fica num arquivo versionado (`kind-config.yaml`), incluindo o mapeamento da porta 8080.
- **Helm:** traria templates e versionamento de pacotes, que só fazem sentido com vários ambientes. Com um ambiente só, YAML puro com Kustomize é mais fácil de ler e de aprender.
- **Ingress com ingress-nginx:** o ingress-nginx, o controlador mais usado nos tutoriais, foi aposentado pelo próprio projeto Kubernetes em março de 2026, e a recomendação oficial passou a ser o Gateway API. Para um cluster local com um gateway na aplicação, um NodePort resolve sem mais um componente.
- **Gateway API:** é o caminho para um cluster de verdade, mas acrescenta um controlador e novos tipos de recurso sem mudar nada do que esta fase quer provar.
- **Operators (Strimzi para o Kafka, CloudNativePG para o Postgres):** dariam replicação, backup e failover, mas cada um é um sistema inteiro para aprender. A fase é sobre os serviços com várias réplicas, não sobre a infraestrutura com alta disponibilidade.
- **Um initContainer esperando o banco:** discutido acima. Descartado porque o serviço já precisa tolerar a queda do banco depois de subir.
- **Um Secret próprio para cada serviço:** duplicaria usuário e senha. Ler do Secret do banco deixa uma fonte só.

## Consequências

- As promessas dos ADRs 0003, 0005, 0006 e 0011 foram testadas com processos diferentes, e cada prova pode ser repetida com um script.
- O `application.yml` de produção rodou pela primeira vez, num ambiente que se parece com o de verdade.
- O caminho para um cluster na nuvem é curto: os manifests são os mesmos, e mudam a StorageClass, o tipo do Service do gateway e a origem das imagens.
- **Um nó só:** não existe alta disponibilidade de verdade. Se o container do nó cai, tudo cai.
- **Banco e Kafka sem réplica:** são um ponto único de falha, como no compose.
- **`kind delete cluster` apaga os dados**, porque os volumes ficam dentro do container do nó. Para pausar sem perder nada, usa-se `docker stop matilha-control-plane`.
- **Sem `requests` de CPU:** num nó só, não faz diferença; num cluster com vários nós, o escalonador precisaria deles para decidir onde colocar os pods.
- **O nome do banco aparece em dois lugares**, no `POSTGRES_DB` do Secret e na URL do ConfigMap. Mudar um exige mudar o outro.
- **Qualquer pod do cluster consegue chamar o `/internal/**` do account.** O gateway bloqueia esses caminhos para fora, mas dentro do cluster não existe restrição. O próximo passo é uma NetworkPolicy que deixe só o transfer chamar o `/internal/transfers`.
