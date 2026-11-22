-- The company's 7 sectors, the same the realm's user profile offers (attribute "sector"), and
-- the categories each sector sees by default. Admins change the access from the permissions
-- screen; this only sets where it starts.
--
-- Sectors named "Setor 1" to "Setor 7", created by logins before this list existed, are left
-- alone: their users move to a real sector the next time an admin or they themselves set one.

INSERT INTO sectors (name) VALUES
    ('Engenharia'),
    ('Qualidade'),
    ('Logística'),
    ('Manutenção'),
    ('Recursos Humanos'),
    ('Financeiro'),
    ('Jurídico')
ON CONFLICT (name) DO NOTHING;

INSERT INTO sector_categories (sector_id, category_id)
SELECT s.id, c.id
FROM sectors s
JOIN categories c ON c.name IN ('Tecnico', 'Regulatorio', 'Qualidade')
WHERE s.name IN ('Engenharia', 'Qualidade', 'Logística', 'Manutenção')
ON CONFLICT DO NOTHING;

INSERT INTO sector_categories (sector_id, category_id)
SELECT s.id, c.id
FROM sectors s
JOIN categories c ON c.name IN ('Regulatorio', 'Juridico')
WHERE s.name IN ('Recursos Humanos', 'Financeiro', 'Jurídico')
ON CONFLICT DO NOTHING;
