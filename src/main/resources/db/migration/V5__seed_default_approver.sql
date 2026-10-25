-- TEMPORARY: this sprint ships no user registration and no login, but every document still
-- needs an author and every approval has to record who approved it. So the single user that
-- uploads and approves is seeded here, and the server resolves it through
-- akpedia.default-user-email at startup. Drop this seed once the user CRUD and authentication
-- exist (see README, "Usuario padrao").

----------------------------------------------------------------------
-- DEFAULT SECTOR
----------------------------------------------------------------------

-- users.sector_id is NOT NULL, so the user needs a sector to belong to.
INSERT INTO sectors (name, description) VALUES
    ('Engenharia', 'Setor padrao enquanto nao existe cadastro de setores')
ON CONFLICT (name) DO NOTHING;

----------------------------------------------------------------------
-- DEFAULT USER (author and approver)
----------------------------------------------------------------------

-- SELECT instead of VALUES so the sector id comes from the row above rather than being
-- guessed: on a database where sectors already exist, 'Engenharia' is not necessarily id 1.
INSERT INTO users (name, email, password_hash, sector_id, is_active)
SELECT 'Aprovador Padrao',
       'aprovador@akpedia.local',
       -- Placeholder, never a usable credential: nothing authenticates yet, and the column
       -- is NOT NULL. Whatever replaces it has to come from the real password flow.
       'placeholder-sem-login',
       sectors.id,
       TRUE
FROM sectors
WHERE sectors.name = 'Engenharia'
ON CONFLICT (email) DO NOTHING;
