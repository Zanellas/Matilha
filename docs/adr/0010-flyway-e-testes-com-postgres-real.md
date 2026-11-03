<!-- RASCUNHO: reescrever com as suas palavras antes de commitar -->
# ADR-0010: Schema com Flyway e testes contra PostgreSQL real

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

O projeto usava H2 em memória nos testes de contexto, com o Flyway desligado e o schema criado pelo Hibernate. Ou seja, esses testes rodavam contra um banco diferente e um schema diferente dos de produção. Várias partes importantes do projeto são específicas do PostgreSQL: `TIMESTAMPTZ`, `JSONB`, índices parciais e `FOR UPDATE SKIP LOCKED`.

## Decisão

- **O schema é controlado só pelo Flyway**, com migrações versionadas. O Hibernate roda com `ddl-auto: validate`: a aplicação nem sobe se as entidades não baterem com o schema.
- As regras importantes também existem como constraints no banco (`CHECK`, `UNIQUE`), além das validações no Java.
- **Os testes de contexto e de integração rodam contra um PostgreSQL real** com Testcontainers (`postgres:17`) e `@ServiceConnection`. O H2 foi removido.
- Os testes de unidade (entidades, services com Mockito e controllers com MockMvc) continuam sem banco.

## Alternativas consideradas

- **H2 em modo de compatibilidade com o PostgreSQL.** Não suporta tudo, e um teste verde não garante que funciona em produção.
- **Um banco de teste fixo e compartilhado.** Os testes passariam a depender do estado do ambiente.

## Consequências

- Os testes provam de verdade as migrações, as constraints, os locks e o `SKIP LOCKED`.
- É preciso ter o Docker rodando para executar a suíte.
- Os testes ficam mais lentos, porque sobem containers.
