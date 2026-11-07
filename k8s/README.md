# Matilha no Kubernetes

Roda o Matilha inteiro num cluster Kubernetes local, criado com o [kind](https://kind.sigs.k8s.io/), com o gateway em `http://localhost:8080` e o Keycloak em `http://localhost:8180`, os mesmos endereços do compose.

Faz parte do [Matilha](../README.md).

## O que sobe

| Recurso | Tipo | Réplicas | Acesso |
|---|---|---|---|
| gateway | Deployment + Service NodePort | 2 | `http://localhost:8080` |
| keycloak | Deployment + Service NodePort + ConfigMap do realm | 1 | `http://localhost:8180` |
| account, transfer, notification | Deployment + Service ClusterIP | 2 cada | só de dentro do cluster, ou por `port-forward` |
| account-db, transfer-db, notification-db | StatefulSet + Service + volume de 1Gi | 1 cada | só de dentro do cluster |
| kafka | StatefulSet + Service + volume de 1Gi | 1 | só de dentro do cluster |
| kafka-ui | Deployment + Service ClusterIP | 1 | por `port-forward`, em `http://localhost:8090` |

Só o gateway e o Keycloak ficam expostos: o gateway é a entrada da API, e o Keycloak precisa ser alcançado de fora para o login. Tudo fica no namespace `matilha`. Os Services têm os mesmos nomes do compose (`account`, `account-db`, `kafka`, `keycloak`...), então os endereços entre os serviços são os mesmos: `http://account:8081`, `jdbc:postgresql://account-db:5432/...`, `http://keycloak:8080/...`. A diferença é o Kafka, que no cluster tem um listener só: `kafka:9092`.

Os quatro ConfigMaps dos serviços têm o `KEYCLOAK_JWK_SET_URI` apontando para `http://keycloak:8080`, o endereço interno de onde vêm as chaves públicas. O `iss` esperado continua `http://localhost:8180/realms/matilha`, o padrão do `application.yml`, igual ao compose (veja o [keycloak/README.md](../keycloak/README.md#por-que-o-kc_hostname)).

Os serviços rodam **sem o perfil `dev`**, com a configuração de produção: Swagger desligado, health sem detalhes e erros sem mensagem interna. O account carrega as contas de exemplo (10001 a 10005) mesmo assim, pelo `SPRING_FLYWAY_LOCATIONS` do ConfigMap.

## Pré-requisitos

- **Docker Desktop** rodando, com o WSL atualizado (`wsl --update`). O Docker precisa estar em cgroup v2. Confira com:

  ```powershell
  docker info --format "cgroup={{.CgroupVersion}}"
  ```

  Tem que aparecer `cgroup=2`. Com cgroup v1, o `kind create cluster` falha no passo "Starting control-plane".
- **kind**: `winget install Kubernetes.kind`
- **kubectl**: já vem com o Docker Desktop

Todos os comandos abaixo rodam **na raiz do repositório**.

## Primeira vez

**1. Crie os Secrets dos bancos e do admin do Keycloak** a partir dos exemplos, e troque usuário e senha:

```powershell
Copy-Item k8s/infra/account-db-secret.example.yaml k8s/infra/account-db-secret.yaml
Copy-Item k8s/infra/transfer-db-secret.example.yaml k8s/infra/transfer-db-secret.yaml
Copy-Item k8s/infra/notification-db-secret.example.yaml k8s/infra/notification-db-secret.yaml
Copy-Item k8s/infra/keycloak-admin-secret.example.yaml k8s/infra/keycloak-admin-secret.yaml
```

O admin do Keycloak é quem entra no console (http://localhost:8180), e não um usuário do Matilha.

Os `*-secret.yaml` estão no `.gitignore` e nunca devem ser commitados. Só os `.example` vão para o Git.

**2. Rode o script de deploy:**

```powershell
.\k8s\deploy.ps1
```

Ele cria o cluster se ainda não existir, gera as quatro imagens, carrega tudo no kind (inclusive as imagens da Kafka UI e do Keycloak), cria o ConfigMap do realm, aplica os manifests e espera o Keycloak e os serviços ficarem prontos. Na primeira vez leva alguns minutos. No fim, aparece `Pronto: http://localhost:8080 (API) e http://localhost:8180 (Keycloak)`.

**Antes, pare o compose** (`docker compose stop`): os dois usam as portas 8080 e 8180 da máquina.

Os passos 3 a 5 abaixo são o que o script faz, para quem quiser rodar à mão ou entender cada parte.

**3. Crie o cluster:**

```powershell
kind create cluster --config k8s/kind-config.yaml
kubectl config set-context --current --namespace=matilha
```

O segundo comando deixa o `matilha` como namespace padrão, para não precisar de `-n matilha` em todo comando.

**4. Gere as imagens e carregue no cluster.** O kind roda dentro de um container e não enxerga as imagens do seu Docker sem o `kind load`:

```powershell
docker build -t matilha-account:dev matilha-account
docker build -t matilha-transfer:dev matilha-transfer
docker build -t matilha-notification:dev matilha-notification
docker build -t matilha-gateway:dev matilha-gateway
kind load docker-image matilha-account:dev matilha-transfer:dev matilha-notification:dev matilha-gateway:dev --name matilha
docker pull ghcr.io/kafbat/kafka-ui:v1.5.0
kind load docker-image ghcr.io/kafbat/kafka-ui:v1.5.0 --name matilha
docker pull quay.io/keycloak/keycloak:26.7.5
kind load docker-image quay.io/keycloak/keycloak:26.7.5 --name matilha
```

A tag é `dev`, e não `latest`, de propósito: com `latest`, o Kubernetes tentaria baixar a imagem da internet. Com uma tag fixa e `imagePullPolicy: IfNotPresent`, ele usa a que já está no nó.

**5. Crie o ConfigMap do realm e suba tudo:**

```powershell
kubectl apply -f k8s/namespace.yaml
kubectl create configmap keycloak-realm --from-file=keycloak/realm-matilha.json
kubectl apply -k k8s
kubectl get pods -w
```

O ConfigMap do realm fica fora do `kustomization.yaml` porque o Kustomize recusa arquivos fora da pasta `k8s/`, e o realm mora em `keycloak/`. Criá-lo com `--from-file` mantém uma fonte só.

Espere os 14 pods ficarem `1/1 Running` (de 1 a 2 minutos) e saia com `Ctrl+C`. Os serviços podem reiniciar uma ou duas vezes enquanto os bancos e o Kafka sobem. Isso é normal: no Kubernetes não existe `depends_on`, e o pod tenta de novo até conseguir. Eles não dependem do Keycloak para subir: o JWKS só é baixado na primeira requisição com token.

**6. Teste:**

```powershell
$ana = (Invoke-RestMethod -Method Post http://localhost:8180/realms/matilha/protocol/openid-connect/token `
  -Body @{ grant_type = "password"; client_id = "matilha-dev-cli"; username = "ana"; password = "ana123" }).access_token
curl.exe -i -H "Authorization: Bearer $ana" http://localhost:8080/accounts
```

Tem que aparecer só a conta 10001, a da Ana. Sem o header, a resposta é `401`.

## Dia a dia: pausar e religar

O cluster inteiro é um único container Docker, o `matilha-control-plane`. Parar esse container congela tudo, com os dados dos bancos e os tópicos.

```powershell
docker stop matilha-control-plane     # pausar
docker start matilha-control-plane    # religar
kubectl get pods -w                   # esperar tudo voltar
```

**O cluster volta a ligar sozinho quando o Docker Desktop reinicia.** O kind cria o container do nó com a política de reinício `on-failure`, e o Docker a reaplica a cada vez que sobe, mesmo depois de um `docker stop`. Se você alterna entre o cluster e o compose, isso causa conflito nas portas 8080 e 8180. Para o cluster só subir quando você mandar:

```powershell
docker update --restart=no matilha-control-plane
```

Um cluster recriado pelo `deploy.ps1` volta com a política do kind, e o comando precisa ser rodado de novo.

**Depois de religar, os tokens antigos não valem mais.** O Keycloak reinicia junto e nasce com chaves de assinatura novas. Pegue um token novo.

## Apagar e recriar do zero

Apaga o cluster com todos os dados (pede confirmação):

```powershell
.\k8s\destroy.ps1
```

Para recriar, rode o `.\k8s\deploy.ps1` de novo. Os Secrets do passo 1 continuam no disco.

## Depois de mudar o código de um serviço

```powershell
.\k8s\deploy.ps1 -Servicos transfer
```

O script gera a imagem só daquele serviço, carrega no kind e reinicia o Deployment. Dá para passar mais de um (`-Servicos account,transfer`). Depois de mudar só um YAML de `k8s/`, use `.\k8s\deploy.ps1 -SemBuild`.

À mão, são estes comandos:

```powershell
docker build -t matilha-transfer:dev matilha-transfer
kind load docker-image matilha-transfer:dev --name matilha
kubectl rollout restart deployment/transfer
kubectl rollout status deployment/transfer
```

O `rollout restart` é necessário porque a tag continua `dev`: para o Kubernetes, o Deployment não mudou. O restart cria pods novos, que pegam a imagem nova do nó, e troca um de cada vez, sem tirar o serviço do ar.

Depois de mudar um YAML de `k8s/`, basta `kubectl apply -k k8s`.

**Depois de mudar o realm** ([keycloak/realm-matilha.json](../keycloak/realm-matilha.json)): `.\k8s\deploy.ps1 -SemBuild`, que recria o ConfigMap, e `kubectl rollout restart deployment/keycloak`, porque o Keycloak só importa o realm na subida. O restart troca as chaves de assinatura, então os tokens antigos deixam de valer.

## Provas com duas réplicas

O motivo de rodar cada serviço com 2 réplicas é mostrar, e não só afirmar, que o que os ADRs prometem vale com vários processos. Cada prova tem um script em [provas/](provas/), que roda contra o cluster no ar e imprime o resultado.

| Prova | Script | Resultado obtido |
|---|---|---|
| As duas réplicas do notification dividem as 3 partições do tópico (ADR-0011) | `1-particoes-notification.ps1` | Partições 0 e 1 numa réplica, 2 na outra, com `LAG` 0 |
| Transferências simultâneas em sentidos opostos, com dois accounts disputando as mesmas linhas, sem deadlock (ADR-0003) | `2-transferencias-simultaneas.ps1 -ContarPorPod` | 40 de 40 `COMPLETED`, soma dos saldos igual antes e depois, chamadas divididas entre os dois pods do account |
| Com o account fora do ar, a transferência fica `PENDING`, e uma réplica só do transfer a completa quando ele volta (ADR-0005 e ADR-0006) | `3-reconciliador.ps1` | `202 PENDING`, depois `COMPLETED`, com o log do reconciliador em um pod só |
| `rollout restart` do account com transferências chegando a cada 200 ms | `4-rollout-sem-erro.ps1` | 253 `COMPLETED`, 1 `PENDING` que o reconciliador completou, nenhum erro, soma igual |

```powershell
.\k8s\provas\1-particoes-notification.ps1
.\k8s\provas\2-transferencias-simultaneas.ps1 -ContarPorPod
.\k8s\provas\3-reconciliador.ps1
.\k8s\provas\4-rollout-sem-erro.ps1
```

Os scripts usam as contas 10001 e 10002 e movem valores nos dois sentidos, então os saldos terminam onde começaram. Antes da prova 1, rode pelo menos uma transferência (ou a collection do Postman) para o tópico ter eventos.

**Os scripts rodam com a segurança ligada.** O [provas/comum.ps1](provas/comum.ps1), carregado pelas provas 2, 3 e 4, pega no Keycloak o token do dono de cada conta (a Ana para a 10001, o Bruno para a 10002) e o guarda até faltarem 30 segundos para vencer. Cada transferência vai com o token de quem é dono da conta de origem. Na prova 3, quem completa a transferência é o reconciliador, sem token nenhum: ele usa o dono gravado na transferência.

**Sobre o `PENDING` da prova 4:** ele não é falha. Uma chamada pegou uma réplica do account no meio do desligamento, por uma conexão reaproveitada. O transfer não sabia se o dinheiro tinha sido movido, então respondeu `202` em vez de errar, e o reconciliador terminou a transferência. É o "timeout não é falha" do ADR-0005 acontecendo durante uma operação de rotina.

## Acessar o que não é público

Só o gateway e o Keycloak ficam expostos. O console do Keycloak está em http://localhost:8180, com o admin do `keycloak-admin-secret.yaml`. Para o resto, use `port-forward`, que liga uma porta da sua máquina a um Service do cluster enquanto o comando estiver rodando:

```powershell
kubectl port-forward svc/account 8081:8081
```

Para abrir os três serviços de uma vez, em segundo plano no mesmo terminal:

```powershell
$tuneis = "account:8081", "transfer:8082", "notification:8083" | ForEach-Object {
    $servico, $porta = $_.Split(":")
    Start-Job { param($s, $p) kubectl port-forward "svc/$s" "${p}:${p}" -n matilha } -ArgumentList $servico, $porta
}
# ... e para fechar:
$tuneis | Stop-Job; $tuneis | Remove-Job
```

O `port-forward` liga a porta a **um** pod, e não ao Service balanceado. Para depuração e health check, isso não importa.

| Para quê | Comando |
|---|---|
| Depósito (`/internal/deposits`) e o `/internal/transfers` do Postman | `kubectl port-forward svc/account 8081:8081` |
| Health check do transfer | `kubectl port-forward svc/transfer 8082:8082` |
| Health check do notification | `kubectl port-forward svc/notification 8083:8083` |
| Um banco pelo DBeaver ou pelo psql da máquina | `kubectl port-forward svc/account-db 15432:5432` |
| Kafka UI (tópicos, mensagens e grupos de consumidores) | `kubectl port-forward svc/kafka-ui 8090:8080`, e abra http://localhost:8090 |

O Swagger não aparece no cluster, porque os serviços rodam sem o perfil `dev`.

**Postman:** as duas collections de [postman/](../postman/) funcionam sem mudança, porque os endereços do gateway e do Keycloak são os mesmos do compose. As chamadas que vão direto nos serviços (o depósito, o `/internal/transfers` e os health checks) precisam dos `port-forward` acima. Sem eles, o Postman mostra `ECONNREFUSED 127.0.0.1:8081`. Rode as pastas 0 a 6 do roteiro pelo Collection Runner.

## Comandos úteis

```powershell
kubectl get pods -o wide                           # pods, IPs e reinícios
kubectl logs deployment/transfer                   # log de uma réplica
kubectl logs -l app=transfer --prefix --tail=50    # log das duas réplicas
kubectl logs deployment/transfer --previous        # log do container que caiu
kubectl describe pod -l app=transfer               # eventos: probe falhando, OOMKilled, imagem não encontrada
kubectl scale deployment/account --replicas=0      # derrubar um serviço (e --replicas=2 para voltar)
```

**Dentro dos bancos:**

```powershell
kubectl exec -it account-db-0 -- psql -U SEU_USUARIO -d matilha_account
```

**Dentro do Kafka:**

```powershell
kubectl exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
kubectl exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group matilha-notification
```

O segundo mostra como as 3 partições do `matilha.transfer.events` estão divididas entre as duas réplicas do notification, e se ainda há eventos esperando (`LAG`).

## Problemas comuns

| Sintoma | Causa | O que fazer |
|---|---|---|
| `kind create cluster` falha em "Starting control-plane", com aviso de cgroup v1 | O Docker está em cgroup v1 (WSL antigo) | `wsl --update`, `wsl --shutdown`, abrir o Docker Desktop de novo e conferir `cgroup=2` |
| `kind` não é reconhecido no terminal do VS Code | O VS Code foi aberto antes da instalação e não tem o PATH novo | Feche e abra o VS Code inteiro |
| A porta 8080 ou 8180 já está em uso | O compose ou outro programa está nela | `docker compose stop` antes de usar o cluster, e `docker stop matilha-control-plane` antes de usar o compose |
| O compose não sobe depois de reiniciar o PC: porta 8080 em uso | O nó do kind religou sozinho com o Docker Desktop | `docker stop matilha-control-plane` e, para não acontecer de novo, `docker update --restart=no matilha-control-plane` |
| `401` em tudo, com um token que funcionava | O token venceu (5 minutos) ou o Keycloak reiniciou e trocou as chaves | Pegue um token novo |
| Postman com `ECONNREFUSED 127.0.0.1:8081` | A requisição vai direto no serviço, que no cluster não é exposto | Abra os `port-forward` de [Acessar o que não é público](#acessar-o-que-não-é-público) |
| Pod em `ErrImagePull` ou `ImagePullBackOff` | A imagem não foi carregada no kind, e o Kubernetes tentou baixá-la da internet | `kind load docker-image matilha-NOME:dev --name matilha` |
| Serviço não conecta no banco depois de trocar a senha no Secret | O Postgres só lê a senha na primeira vez que cria o banco | Apague o banco e o disco: `kubectl delete statefulset NOME-db` e `kubectl delete pvc data-NOME-db-0`, e rode `kubectl apply -k k8s` |
| `apply` mostra `configured` em Secrets e StatefulSets sem você mudar nada | Ruído conhecido do kubectl com `stringData` e `volumeClaimTemplates` | Nada: os pods não reiniciam |

## Estrutura

```
k8s/
├── deploy.ps1                # cria o cluster, gera as imagens, carrega no kind e aplica
├── destroy.ps1               # apaga o cluster
├── kind-config.yaml          # cluster de um nó; as portas 8080 e 8180 da máquina vão para os NodePorts 30080 e 30180
├── kustomization.yaml        # lista os arquivos e põe tudo no namespace matilha
├── namespace.yaml
├── infra/
│   ├── account-db.yaml       # StatefulSet + Service + volume (o mesmo para transfer-db e notification-db)
│   ├── *-secret.example.yaml # modelos dos Secrets dos bancos e do admin do Keycloak (os reais ficam fora do Git)
│   ├── kafka.yaml            # Kafka KRaft de nó único, com volume
│   ├── kafka-ui.yaml         # ferramenta de inspeção do Kafka, só por port-forward
│   └── keycloak.yaml         # Deployment de 1 réplica + Service NodePort; o realm vem do ConfigMap keycloak-realm
├── services/
│   ├── account.yaml          # ConfigMap + Service + Deployment (o mesmo para transfer e notification)
│   └── gateway.yaml          # o Service NodePort da API
└── provas/                   # um script por prova com duas réplicas, e o comum.ps1 com os tokens
```

**Decisões que valem para todos os pods:**

- `enableServiceLinks: false`: sem isso, o Kubernetes injeta variáveis como `KAFKA_PORT=tcp://...` para cada Service do namespace, e a imagem do Kafka lê toda variável `KAFKA_*` como configuração.
- **Probes do Actuator:** `startupProbe` com até 150 segundos para a JVM subir, `livenessProbe` em `/actuator/health/liveness` e `readinessProbe` em `/actuator/health/readiness`. A readiness não checa banco nem Kafka: um banco fora do ar não deve tirar o serviço do Service.
- **Desligamento gracioso:** o `preStop` espera 5 segundos para o Kubernetes tirar o pod do Service, depois o Spring tem 20 segundos para terminar as requisições. O `terminationGracePeriodSeconds: 40` cobre os dois.
- **Memória:** `limits` de 768Mi nos serviços com banco e 512Mi no gateway. O `-XX:MaxRAMPercentage=75` do Dockerfile calcula o heap a partir desse limite.

## Fora do escopo

Helm, operators (Strimzi, CloudNativePG), Ingress e Gateway API, autoscaling, deploy na nuvem, observabilidade e um Keycloak de produção (modo `start`, com banco próprio, TLS e várias réplicas). Um próximo passo interessante é a segurança entre serviços: uma NetworkPolicy e client credentials para que só o transfer consiga chamar o `/internal/transfers` do account. Hoje, qualquer pod do cluster consegue, e sem token.
