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
| Client `matilha-web` | Authorization Code + PKCE, usado pelo Postman. A senha é digitada só na tela do Keycloak |
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

## Por que o `KC_HOSTNAME`

O Postman fala com o Keycloak por `localhost:8180`; o gateway, dentro do Docker, por `keycloak:8080`. O `KC_HOSTNAME` fixa o endereço público, então o `iss` do token é sempre `http://localhost:8180/realms/matilha`, por qualquer caminho que a requisição chegue.
