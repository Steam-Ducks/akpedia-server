package com.akpedia.server.repository;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import java.util.UUID;

/**
 * Fabricas de dados para os testes de persistencia.
 * Os nomes recebem sufixo aleatorio porque name e email sao UNIQUE no schema.
 */
final class TestFixtures {

    private TestFixtures() {
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    static Sector sector() {
        return new Sector(unique("setor"), "setor de teste");
    }

    static Category category() {
        return new Category(unique("categoria"), "categoria de teste");
    }

    static User user(Sector sector) {
        return new User("Usuario de Teste", unique("email") + "@akpedia.test", "hash-de-teste", sector);
    }

    static Document document(Category category, User creator) {
        return new Document(unique("documento") + ".pdf", "application/pdf", 1024L, category, creator, DocumentStatus.DRAFT);
    }

    /** Vetor com a dimensao exata exigida pela coluna vector(1536). */
    static float[] vector(float seed) {
        float[] values = new float[com.akpedia.server.entity.Embedding.VECTOR_DIMENSIONS];
        for (int i = 0; i < values.length; i++) {
            values[i] = seed + i;
        }
        return values;
    }
}
