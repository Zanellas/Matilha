INSERT INTO accounts (id, account_number, holder_name, holder_document, holder_email, holder_phone,
                      balance, status, created_at, version)
VALUES ('00000000-0000-0000-0000-000000010001', '10001', 'Ana Silva',   '52998224725', 'ana.silva@example.com',   '11987654321', 1500.00, 'ACTIVE',  now(), 0),
       ('00000000-0000-0000-0000-000000010002', '10002', 'Bruno Costa', '11144477735', 'bruno.costa@example.com', '21987654321',  250.00, 'ACTIVE',  now(), 0),
       ('00000000-0000-0000-0000-000000010003', '10003', 'Carla Souza', '28371946546', 'carla.souza@example.com', '3134567890',     0.00, 'ACTIVE',  now(), 0),
       ('00000000-0000-0000-0000-000000010004', '10004', 'Diego Lima',  '40617293813', 'diego.lima@example.com',  '41987654321',  800.00, 'BLOCKED', now(), 0),
       ('00000000-0000-0000-0000-000000010005', '10005', 'Elisa Rocha', '73501826435', 'elisa.rocha@example.com', '51987654321',    0.00, 'CLOSED',  now(), 0)
ON CONFLICT (account_number) DO NOTHING;

INSERT INTO account_movements (id, account_id, operation_id, type, operation, amount, balance_after, created_at)
SELECT seed.id, seed.account_id, seed.operation_id, 'CREDIT', 'DEPOSIT', seed.amount, seed.amount, now()
FROM (VALUES ('00000000-0000-0000-0001-000000010001'::uuid, '00000000-0000-0000-0000-000000010001'::uuid, '00000000-0000-0000-0002-000000010001'::uuid, 1500.00),
             ('00000000-0000-0000-0001-000000010002'::uuid, '00000000-0000-0000-0000-000000010002'::uuid, '00000000-0000-0000-0002-000000010002'::uuid,  250.00),
             ('00000000-0000-0000-0001-000000010004'::uuid, '00000000-0000-0000-0000-000000010004'::uuid, '00000000-0000-0000-0002-000000010004'::uuid,  800.00))
     AS seed (id, account_id, operation_id, amount)
JOIN accounts a ON a.id = seed.account_id
ON CONFLICT (operation_id, type) DO NOTHING;

UPDATE accounts SET owner_id = '00000000-0000-0000-0003-000000000001'
WHERE account_number = '10001' AND owner_id IS NULL;

UPDATE accounts SET owner_id = '00000000-0000-0000-0003-000000000002'
WHERE account_number = '10002' AND owner_id IS NULL;
