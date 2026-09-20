package com.akpedia.server.repository;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import java.util.UUID;

/**
 * Data factories for the persistence tests.
 * Names get a random suffix because name and email are UNIQUE in the schema.
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

    /** Vector with the exact dimension required by the vector(1536) column. */
    static float[] vector(float seed) {
        float[] values = new float[com.akpedia.server.entity.Embedding.VECTOR_DIMENSIONS];
        for (int i = 0; i < values.length; i++) {
            values[i] = seed + i;
        }
        return values;
    }
}
