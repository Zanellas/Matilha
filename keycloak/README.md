# Keycloak

Servidor de identidade do Matilha: faz o login e emite os tokens JWT que o gateway e os serviços validam. O Matilha nunca vê a senha do usuário.

## Como subir

```powershell
Copy-Item keycloak\.env.example keycloak\.env   # defina o admin do console
docker compose up -d keycloak
```

Console: http://localhost:8180 (admin do `.env`, realm `master`). O realm do sistema é o `matilha`.

O realm vem do [realm-matilha.json](realm-matilha.json), importado na subida. O container não tem volume de dados de propósito: `stop`/`start` mantém o que foi mudado no console, mas `down`/`up` importa o JSON de novo. O JSON versionado é a fonte da verdade.

## O que vem no realm

| Item | Para quê |
|---|---|
| Papéis `customer` e `admin` | O cliente vê e movimenta só as próprias contas; o admin lista todas, bloqueia e desbloqueia |
| Client `matilha-web` | Authorization Code + PKCE, o login real, como um front-end faria. A senha é digitada só na tela do Keycloak. É o da pasta 8 da [collection do Postman](../postman/matilha.postman_collection.json) |
| Client `matilha-dev-cli` | **Só para desenvolvimento.** Aceita usuário e senha direto (password grant), para os scripts e o curl. Não existiria em produção |
| Audiência `matilha-api` | Todo token sai com `aud: matilha-api`, e os serviços recusam tokens emitidos para outro sistema |
| Token de 5 minutos | Um token não pode ser revogado: bloquear um usuário impede novos logins, mas o token atual vale até vencer |

## Usuários de demonstração

| Usuário | Senha | Papel | `sub` (id fixo) |
|---|---|---|---|
| ana | ana123 | customer | `00000000-0000-0000-0003-000000000001` |
| bruno | bruno123 | customer | `00000000-0000-0000-0003-000000000002` |
| suporte | suporte123 | admin | `00000000-0000-0000-0003-000000000003` |

**As senhas estão no Git porque são só para uso local**, numa instância `start-dev`, sem TLS e com banco H2 embutido. Os ids são fixos para o seed do account poder apontar as contas para os seus donos.

## Pegar um token pelo terminal

```powershell
$t = Invoke-RestMethod -Method Post http://localhost:8180/realms/matilha/protocol/openid-connect/token `
  -Body @{ grant_type = "password"; client_id = "matilha-dev-cli"; username = "ana"; password = "ana123" }
$t.access_token
```

## Login pelo navegador (Authorization Code + PKCE)

Na pasta "8. MANUAL - Login pelo navegador" da [collection do Postman](../postman/matilha.postman_collection.json): aba **Authorization** da pasta, **Get New Access Token**, login como `ana` / `ana123` na tela do Keycloak que abre no navegador, e **Use Token**. O Postman recebe o token pelo callback `https://oauth.pstmn.io/v1/callback`, o único `redirectUris` liberado no `matilha-web`.

## O que o Matilha lê do token

| Claim | Quem usa | Para quê |
|---|---|---|
| assinatura | gateway e os três serviços | provar que o token veio do Keycloak, pela chave pública do JWKS (`/realms/matilha/protocol/openid-connect/certs`) |
| `iss`, `aud`, `exp` | gateway e os três serviços | recusar token de outro realm, emitido para outro sistema ou vencido |
| `sub` | account, transfer e notification | quem é o usuário: o dono da conta, de quem pediu a transferência e de cada notificação |
| `realm_access.roles` | account | o papel `admin` vira a authority `ROLE_admin`, exigida no block e no unblock |

O gateway e os serviços baixam o JWKS uma vez e guardam em cache. Eles não chamam o Keycloak a cada requisição: se ele cair, os tokens já emitidos continuam valendo até vencer.

## Por que o `KC_HOSTNAME`

O Postman fala com o Keycloak por `localhost:8180`; o gateway, dentro do Docker, por `keycloak:8080`. O `KC_HOSTNAME` fixa o endereço público, então o `iss` do token é sempre `http://localhost:8180/realms/matilha`, por qualquer caminho que a requisição chegue. Os serviços buscam as chaves pelo endereço interno (`KEYCLOAK_JWK_SET_URI`) e conferem o `iss` pelo público (`issuer-uri` no `application.yml`).

## No Kubernetes

O `.\k8s\deploy.ps1` sobe o mesmo Keycloak no cluster ([k8s/infra/keycloak.yaml](../k8s/infra/keycloak.yaml)), no mesmo endereço: `http://localhost:8180`.

| No compose | No cluster |
|---|---|
| o realm montado como arquivo | o ConfigMap `keycloak-realm`, que o `deploy.ps1` cria a partir deste [realm-matilha.json](realm-matilha.json): continua existindo uma fonte só |
| o admin do console no `keycloak/.env` | o Secret `keycloak-admin`, em `k8s/infra/keycloak-admin-secret.yaml` (fora do Git, criado a partir do `.example`) |
| a porta `127.0.0.1:8180` | um Service NodePort na 30180, ligado à 8180 da máquina pelo `kind-config.yaml` |
| o healthcheck com `/dev/tcp` | probes `httpGet` na porta de gerenciamento, a 9000 (`/health/started`, `/health/live` e `/health/ready`) |

**Uma réplica só, e as chaves mudam a cada reinício.** O `start-dev` guarda usuários, sessões e chaves de assinatura num H2 dentro do container. Com duas réplicas, cada uma teria as próprias chaves. Quando o pod reinicia, nasce com chaves novas: os tokens emitidos antes deixam de valer, e os serviços baixam o JWKS de novo sozinhos, ao ver um `kid` desconhecido.

**Depois de mudar o realm:** `.\k8s\deploy.ps1 -SemBuild` (atualiza o ConfigMap) e `kubectl rollout restart deployment/keycloak` (o `--import-realm` só importa na subida).
