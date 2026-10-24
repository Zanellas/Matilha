-- Contas de exemplo para desenvolvimento. Carregado apenas com o perfil "dev" (application-dev.yml).
-- Migração repetível: roda de novo sempre que este arquivo mudar, por isso os INSERTs ignoram o que já existe.

INSERT INTO accounts (id, account_number, holder_name, holder_document, holder_email, holder_phone,
                      balance, status, created_at, version)
VALUES ('00000000-0000-0000-0000-000000010001', '10001', 'Ana Silva',   '52998224725', 'ana.silva@example.com',   '11987654321', 1500.00, 'ACTIVE',  now(), 0),
       ('00000000-0000-0000-0000-000000010002', '10002', 'Bruno Costa', '11144477735', 'bruno.costa@example.com', '21987654321',  250.00, 'ACTIVE',  now(), 0),
       ('00000000-0000-0000-0000-000000010003', '10003', 'Carla Souza', '28371946546', 'carla.souza@example.com', '3134567890',     0.00, 'ACTIVE',  now(), 0),
       ('00000000-0000-0000-0000-000000010004', '10004', 'Diego Lima',  '40617293813', 'diego.lima@example.com',  '41987654321',  800.00, 'BLOCKED', now(), 0),
       ('00000000-0000-0000-0000-000000010005', '10005', 'Elisa Rocha', '73501826435', 'elisa.rocha@example.com', '51987654321',    0.00, 'CLOSED',  now(), 0)
ON CONFLICT (account_number) DO NOTHING;

-- Um depósito para cada conta com saldo, para que a soma dos movimentos bata com o saldo.
-- O JOIN garante que o movimento só é criado se a conta de exemplo existir
-- (se o número já estava em uso por outra conta, o INSERT acima foi ignorado).
INSERT INTO account_movements (id, account_id, operation_id, type, operation, amount, balance_after, created_at)
SELECT seed.id, seed.account_id, seed.operation_id, 'CREDIT', 'DEPOSIT', seed.amount, seed.amount, now()
FROM (VALUES ('00000000-0000-0000-0001-000000010001'::uuid, '00000000-0000-0000-0000-000000010001'::uuid, '00000000-0000-0000-0002-000000010001'::uuid, 1500.00),
             ('00000000-0000-0000-0001-000000010002'::uuid, '00000000-0000-0000-0000-000000010002'::uuid, '00000000-0000-0000-0002-000000010002'::uuid,  250.00),
             ('00000000-0000-0000-0001-000000010004'::uuid, '00000000-0000-0000-0000-000000010004'::uuid, '00000000-0000-0000-0002-000000010004'::uuid,  800.00))
     AS seed (id, account_id, operation_id, amount)
JOIN accounts a ON a.id = seed.account_id
ON CONFLICT (operation_id, type) DO NOTHING;
