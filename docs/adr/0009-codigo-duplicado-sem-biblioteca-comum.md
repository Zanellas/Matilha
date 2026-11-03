# ADR-0009: Código transversal duplicado em vez de biblioteca compartilhada

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Algumas peças se repetem nos serviços: o `GlobalExceptionHandler`, a `BusinessException`, o `OpenApiConfig`, as validações de valores em dinheiro e a estrutura de configuração. A pergunta é se isso deveria virar uma biblioteca compartilhada.

## Decisão

- Cada serviço mantém a **própria cópia** dessas peças.
- A consistência vem de **convenções**, não de uma dependência comum: a mesma estrutura de pacotes, erros em Problem Details, perfis `dev` e `test`, variáveis com o prefixo do serviço, o mesmo formato de `Dockerfile`, compose e CI.

## Alternativas consideradas

- **Uma biblioteca `matilha-common`.** Todos os serviços passariam a depender da mesma versão, e uma mudança nela obrigaria a atualizar e publicar todos de novo. É exatamente o acoplamento que microsserviços tentam evitar.
- **Um template de projeto.** Chegou a existir, mas foi removido. Com só quatro serviços, e diferentes entre si (o gateway não tem banco e o notification é um consumidor), ele não compensava o custo de mantê-lo.

## Consequências

- Os serviços evoluem de forma independente.
- Uma correção numa peça repetida precisa ser feita em mais de um lugar, e as cópias podem divergir. Esse risco é controlado pelas convenções e pela revisão.
- A decisão deve ser revista se o número de serviços crescer muito.
