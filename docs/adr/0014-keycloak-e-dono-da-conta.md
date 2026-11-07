# ADR-0014: Login no Keycloak, token validado em todos os serviços e regra de dono da conta

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Até a Fase 3, qualquer pessoa que alcançasse o gateway podia consultar qualquer conta, transferir de qualquer conta e ler as notificações de qualquer conta. Não existia usuário: o número da conta era a única coisa que a requisição precisava saber, e números de conta têm 5 dígitos, então dá para adivinhá-los.

Faltavam três coisas:

- **Quem é você** (autenticação): um jeito de o usuário provar quem é, sem que cada serviço guarde senhas.
- **O que você pode fazer** (autorização): uma conta tem dono, e só ele mexe nela. Algumas operações, como bloquear uma conta, são do suporte.
- **Quem pediu, depois que a requisição acabou**: a transferência continua depois da resposta. O reconciliador reenvia transferências `PENDING` sem nenhum usuário por perto (ADR-0006), e o notification recebe eventos pelo Kafka, também sem usuário (ADR-0011). Os dois precisam saber de quem é cada coisa sem receber um token.

## Decisão

### Login

- **Keycloak como servidor de identidade.** O login, as senhas, os papéis e a emissão dos tokens ficam no Keycloak 26. Nenhum serviço do Matilha tem tela de login, tabela de usuários ou senha. Os serviços só recebem o token pronto e conferem a assinatura.
- **Realm como código.** O realm `matilha` está em [keycloak/realm-matilha.json](../../keycloak/realm-matilha.json) e é importado na subida (`start-dev --import-realm`). Os papéis `customer` e `admin`, os clients e os usuários de exemplo (ana, bruno e suporte, com ids fixos) estão versionados. Quem clona o repositório tem o mesmo realm, sem clicar em nada no console.
- **Dois clients.** O `matilha-web` é público e usa Authorization Code com PKCE (S256), o fluxo certo para um navegador ou app: a senha é digitada na tela do Keycloak e nunca passa pelo cliente. O `matilha-dev-cli` usa o password grant, e existe só para os scripts e o curl do dia a dia. Ele é marcado como de desenvolvimento e não iria para produção.
- **`aud: matilha-api`.** Um mapper do realm coloca a audiência `matilha-api` no token, e todos os serviços exigem essa audiência. Um token do mesmo Keycloak emitido para outra aplicação não é aceito.
- **Token de 5 minutos.** O `accessTokenLifespan` é 300 segundos. Um token vazado vale pouco tempo, e o preço é o cliente renovar o token com frequência: os scripts das provas e a coleção do Postman renovam sozinhos antes de expirar.

### Validação

- **Todos os serviços validam o token, e não só o gateway.** O gateway confere o token e repassa o `Authorization` como recebeu. O account, o transfer e o notification conferem de novo. Nenhum serviço confia num cabeçalho como `X-User-Id` colocado pelo gateway: se confiasse, quem alcançasse o serviço por dentro (outro pod do cluster, um `port-forward`) poderia dizer que é qualquer pessoa. Cada serviço confia só na assinatura do Keycloak.
- **Resource server do Spring Security**, com o `spring-boot-starter-security-oauth2-resource-server`. A validação é toda da biblioteca: assinatura, expiração, `iss` e `aud`. O código do Matilha só diz quais rotas são públicas e como transformar o token em usuário.
- **`issuer-uri` público e `jwk-set-uri` interno.** O `iss` do token é `http://localhost:8180/realms/matilha`, porque o Keycloak usa o `KC_HOSTNAME` para montar o `iss`, e quem pede o token é a máquina do desenvolvedor. Mas, de dentro do Docker ou do cluster, `localhost` é o próprio container. Por isso cada serviço baixa as chaves por um endereço interno (`http://keycloak:8080/.../certs`) e compara o `iss` com o endereço público. Com só o `issuer-uri`, o Spring tentaria buscar a configuração em `localhost` na subida e falharia.
- **Seguro por padrão.** O `SecurityFilterChain` termina em `anyRequest().authenticated()`. Só o health e o info são públicos. Uma rota nova nasce protegida, e liberá-la é uma decisão explícita.
- **Sem sessão e sem CSRF.** A API é stateless: cada requisição traz o token. Sem cookie de sessão, o CSRF não tem o que proteger.
- **401 e 403 em Problem Details.** O `AuthenticationEntryPoint` e o `AccessDeniedHandler` repassam a exceção para o `@RestControllerAdvice`, que responde no mesmo formato dos outros erros (ADR-0007), com os códigos `UNAUTHENTICATED` e `ACCESS_DENIED` e o cabeçalho `WWW-Authenticate: Bearer` no 401.
- **Papéis do Keycloak como `ROLE_`.** O Keycloak coloca os papéis em `realm_access.roles`, onde o Spring não procura. Um `JwtAuthenticationConverter` no account os transforma em `ROLE_customer` e `ROLE_admin`. Bloquear e desbloquear conta exige `hasRole("admin")`. O transfer e o notification não usam papéis, então não têm o conversor.

### Regra de dono

- **A conta guarda o dono.** A tabela `accounts` ganhou `owner_id`, com o `sub` do token de quem a criou. O `sub` é um id opaco do Keycloak, e não o e-mail nem o nome: não muda se o usuário trocar de e-mail, e não expõe dado pessoal nos bancos e nos eventos.
- **O dono vem do token, e nunca do corpo.** O `CreateAccountRequestDto` não tem `ownerId`. Se tivesse, qualquer um criaria uma conta em nome de outra pessoa mandando o campo (mass assignment, o API3 do OWASP API Security Top 10). O `owner_id` também é `updatable = false`: nenhum `PATCH` muda o dono.
- **A conta de outra pessoa é tratada como se não existisse.** As consultas filtram pelo dono (`findByAccountNumberAndOwnerId`), então a conta de outra pessoa dá `404 ACCOUNT_NOT_FOUND`, e não `403`. Um `403` diria "essa conta existe, mas não é sua", e com números de 5 dígitos isso permitiria descobrir quais contas existem. Na transferência, a mesma regra: transferir da conta de outra pessoa termina `FAILED` com `ACCOUNT_NOT_FOUND`, o mesmo resultado de uma conta que não existe. No notification, a conta de outra pessoa dá uma página vazia. O admin enxerga todas as contas.
- **O transfer guarda quem pediu.** A tabela `transfers` ganhou `owner_id`, com o `sub` do token do `POST /transfers`. O transfer não decide se a conta de origem é de quem pediu, porque não conhece as contas (ADR-0002). Ele repassa o `ownerId` ao account no `/internal/transfers`, e o account confere o dono da origem depois do lock e antes do débito, na mesma transação. Assim, a conferência e o débito veem o mesmo estado da conta.
- **O reconciliador reenvia sem token.** O reconciliador não tem o token do usuário, e não precisa: o `owner_id` guardado na transferência é o que o account confere. A decisão de quem pediu foi tomada uma vez, quando o token era válido, e fica gravada.
- **O replay também confere o dono.** Uma segunda requisição com a mesma `Idempotency-Key` devolve a transferência original só se ela for de quem está pedindo (ADR-0004). Sem isso, quem descobrisse a chave de outra pessoa leria a transferência dela.

### Eventos

- **Os donos viajam no evento.** O `TransferEvent` ganhou `requestedBy` (quem pediu) e `targetOwnerId` (dono da conta de destino, que o account devolve na resposta do `/internal/transfers`). O notification usa esses campos para dar dono a cada notificação: `TRANSFER_SENT` e `TRANSFER_FAILED` para quem pediu, `TRANSFER_RECEIVED` para o dono do destino. A alternativa, o notification perguntar ao account de quem é cada conta, criaria a dependência síncrona que os eventos existem para evitar.
- **`requestedBy`, e não "dono da origem".** Os dois são a mesma pessoa numa transferência que deu certo. Numa transferência recusada porque a origem é de outra pessoa, não são: a notificação de falha vai para quem tentou, e o dono da conta não fica sabendo da tentativa.
- **Campos opcionais, e o evento continua na versão 1.** Os dois campos são novos e podem faltar. Um consumidor antigo ignora campos desconhecidos, e um evento antigo, publicado antes da mudança, chega com os dois `null` e é aceito. Como nenhum consumidor quebra, não há motivo para uma versão 2 (ADR-0011). Uma notificação sem dono é gravada, mas ninguém a vê. Mandá-la para a DLT seria tratar como erro um evento que é válido.

### Mudar o contrato sem parar o sistema

O `ownerId` no `/internal/transfers` entrou em duas etapas (expand e contract). Primeiro, opcional: o transfer passou a mandar, e o account aceitava com e sem. Depois, obrigatório. Antes da segunda etapa, foi conferido que não existia nenhuma transferência `PENDING`: uma transferência criada antes da mudança não tem `owner_id`, e o reconciliador a reenviaria para sempre a um account que passou a recusá-la.

### Kubernetes

- **O Keycloak sobe no cluster**, com o mesmo realm do repositório. O `deploy.ps1` cria o ConfigMap `keycloak-realm` a partir do [realm-matilha.json](../../keycloak/realm-matilha.json), e o pod o importa na subida. A senha do admin fica num Secret, fora do Git, como as dos bancos (ADR-0013).
- **NodePort 30180, ligado à `localhost:8180`.** O `iss` do token emitido no cluster é o mesmo do compose, e os scripts, o Postman e o curl funcionam igual nos dois ambientes.
- **Uma réplica só.** No `start-dev`, o Keycloak guarda tudo num banco H2 dentro do container, inclusive as chaves que assinam os tokens. Com duas réplicas, cada uma teria chaves diferentes, e um token assinado por uma não passaria na conferência das chaves da outra. Pelo mesmo motivo, um restart do pod gera chaves novas e invalida os tokens já emitidos. Os serviços não precisam reiniciar: quando chega um token com um `kid` que eles não conhecem, buscam as chaves de novo.

## Alternativas consideradas

- **Login escrito no Matilha** (um `POST /login` que confere a senha e devolve um JWT): seria um serviço a mais guardando senhas, com hash, bloqueio por tentativas, recuperação de senha e rotação de chaves para escrever e manter. É exatamente o tipo de código que não se escreve à mão num banco. O Keycloak entrega isso pronto, com OAuth 2 e OpenID Connect padrão.
- **Validar só no gateway e repassar um `X-User-Id`:** mais simples nos serviços, mas o cabeçalho é texto que qualquer um com acesso à rede interna escreve. Validar em todos é o que se chama de zero trust: a rede interna não é tratada como confiável.
- **Token opaco com introspecção:** cada requisição perguntaria ao Keycloak se o token vale. Daria para revogar na hora, mas o Keycloak entraria no caminho de toda requisição. Com JWT, cada serviço confere sozinho com as chaves em cache, e a revogação é coberta pelo token curto.
- **`403` para a conta de outra pessoa:** mais fácil de depurar, mas revela que a conta existe. Discutido acima.
- **Conferir o dono no transfer:** o transfer teria que consultar o account antes de cada transferência, e a conferência e o débito aconteceriam em momentos diferentes. Entre os dois, nada garante que a conta continua igual. No account, a conferência acontece com a conta travada.
- **Evento versão 2 com os donos obrigatórios:** obrigaria a publicar as duas versões durante a troca, ou a parar o consumidor. Campos opcionais resolvem sem nenhuma das duas coisas.
- **Keycloak com Postgres no cluster:** permitiria várias réplicas e chaves que sobrevivem ao restart. É o caminho de produção, mas acrescenta mais um banco e o modo `start` com hostname e TLS configurados. Para provar a regra de dono, uma réplica com o realm importado basta.

## Consequências

- Cada usuário só enxerga e movimenta as próprias contas, e isso foi testado nos três serviços: a conta de outra pessoa dá `404`, a transferência dela termina `FAILED`, e as notificações dela não aparecem. A mesma conta mostra notificações diferentes para pessoas diferentes.
- Os serviços continuam sem dado pessoal novo: o que circula nos bancos e nos eventos é o `sub`, um id opaco.
- Toda chamada à API precisa de token, inclusive as chamadas diretas a um serviço. Os testes de controller sobem o `SecurityConfig` de verdade, e as provas do Kubernetes pegam o token do dono de cada conta.
- **O Keycloak virou dependência.** Se ele cai, ninguém consegue token novo. Os tokens já emitidos continuam valendo até expirar, porque os serviços têm as chaves em cache.
- **O `/internal/**` do account continua sem autenticação.** O gateway bloqueia esses caminhos para fora (ADR-0012), mas, dentro da rede, quem chama o `/internal/transfers` diz qual é o `ownerId`, e o account acredita. É a mesma confiança no chamador que este ADR recusa para o `X-User-Id`, só que entre serviços. O mesmo vale para o Kafka: quem alcança o broker publica no tópico de eventos. Os três ficam para um pacote de segurança entre serviços: client credentials do Keycloak para o transfer se identificar no `/internal/**`, SASL/SCRAM com ACLs no Kafka e a NetworkPolicy prometida no ADR-0013.
- **O token não é revogado na hora.** Um usuário desativado no Keycloak continua com acesso por até 5 minutos.
- **No cluster, um restart do Keycloak invalida os tokens emitidos**, e os clientes precisam pegar um novo. Os scripts das provas pegam tokens novos a cada execução, então basta rodá-los de novo.
- **O `matilha-dev-cli` usa o password grant**, que a especificação do OAuth 2.1 removeu. Ele existe para scripts locais e não deve ir para um ambiente de verdade.
- **As senhas dos usuários de exemplo estão no realm versionado.** São de demonstração, só para uso local.
