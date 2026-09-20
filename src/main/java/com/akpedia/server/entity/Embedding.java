package com.akpedia.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Chunk de texto de um documento com seu vetor de embedding.
 * A dimensao e fixa em 1536 para permitir o indice HNSW criado na migration V2.
 */
@Entity
@Table(name = "embeddings")
public class Embedding {

    /** Dimensao do vetor, espelhando VECTOR(1536) na migration V2. */
    public static final int VECTOR_DIMENSIONS = 1536;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = VECTOR_DIMENSIONS)
    @Column(name = "vector", nullable = false)
    private float[] vector;

    @Column(name = "model_name", nullable = false, length = 150)
    private String modelName;

    @Column(name = "dimensions", nullable = false)
    private Integer dimensions;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Embedding() {
    }

    public Embedding(Document document, Integer chunkIndex, String content, float[] vector, String modelName, Integer dimensions) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.vector = vector;
        this.modelName = modelName;
        this.dimensions = dimensions;
    }

    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getVector() {
        return vector;
    }

    public void setVector(float[] vector) {
        this.vector = vector;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
