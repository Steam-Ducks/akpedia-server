-- Version 1.0 of the terms of use, so every user is asked to accept terms from the first deploy
-- on, instead of only after an admin publishes some.
--
-- PLACEHOLDER: the legal text is not written yet. When it is, an admin publishes it from the app
-- as a newer version (say "1.1"); this row stays, as published versions are never changed.
--
-- Seeded only on a database with no terms at all: where an admin already published some, this
-- version (published now) would otherwise become the current one and replace them.

INSERT INTO terms_versions (version, title, content, published_by_id)
SELECT '1.0',
       'Termos de uso do Akpedia',
       'Texto provisório: os termos de uso definitivos ainda serão publicados pela equipe jurídica.

Ao usar o Akpedia, você se compromete a usar o sistema apenas para fins de trabalho, a não compartilhar seu acesso e a respeitar a confidencialidade dos documentos que consultar ou enviar.

As suas ações no sistema ficam registradas para auditoria.

Quando uma nova versão destes termos for publicada, será preciso aceitá-la para continuar usando o Akpedia.',
       NULL
WHERE NOT EXISTS (SELECT 1 FROM terms_versions);
