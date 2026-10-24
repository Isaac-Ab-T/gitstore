package com.gitstore.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gitstore.exception.DocumentNotFoundException;
import com.gitstore.exception.GitStoreException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level template for performing Object-Document Mapping (ODM) operations
 * backed by Git plumbing.
 * Similar to MongoTemplate in Spring Data MongoDB.
 */
public class GitDocumentTemplate {

    private final JGitPlumbingEngine plumbingEngine;
    private final ObjectMapper objectMapper;
    private final Map<Class<?>, DocumentMetadata<?>> metadataCache = new ConcurrentHashMap<>();

    public GitDocumentTemplate(JGitPlumbingEngine plumbingEngine) {
        this(plumbingEngine, createDefaultObjectMapper());
    }

    public GitDocumentTemplate(JGitPlumbingEngine plumbingEngine, ObjectMapper objectMapper) {
        this.plumbingEngine = plumbingEngine;
        this.objectMapper = objectMapper;
    }

    public static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    @SuppressWarnings("unchecked")
    private <T> DocumentMetadata<T> getMetadata(Class<T> entityClass) {
        return (DocumentMetadata<T>) metadataCache.computeIfAbsent(entityClass, DocumentMetadata::new);
    }

    /**
     * Saves or updates an entity document in the Git repository.
     * Updates the entity's @GitVersion with the new commit hash.
     *
     * @param entity the entity to save
     * @param <T>    entity type
     * @return the saved entity with updated version
     */
    public <T> T save(T entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity must not be null");
        }
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        DocumentMetadata<T> metadata = getMetadata(entityClass);

        String id = metadata.extractId(entity);
        String relativePath = metadata.resolveRelativePath(id);

        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(entity);
            String commitMessage = "gitstore: save " + entityClass.getSimpleName() + "[" + id + "]";
            String commitSha = plumbingEngine.writeBlob(relativePath, jsonBytes, commitMessage);

            metadata.setVersion(entity, commitSha);
            return entity;
        } catch (IOException e) {
            throw new GitStoreException("Failed to serialize entity of type " + entityClass.getName(), e);
        }
    }

    /**
     * Finds a document by its @GitId.
     *
     * @param id          the document ID
     * @param entityClass the entity class
     * @param <T>         entity type
     * @return Optional containing the entity if found
     */
    public <T> Optional<T> findById(Object id, Class<T> entityClass) {
        if (id == null) {
            return Optional.empty();
        }
        DocumentMetadata<T> metadata = getMetadata(entityClass);
        String relativePath = metadata.resolveRelativePath(id.toString());

        try {
            JGitPlumbingEngine.StoredBlob blob = plumbingEngine.readBlob(relativePath);
            T entity = objectMapper.readValue(blob.getContent(), entityClass);
            metadata.setVersion(entity, blob.getCommitSha());
            return Optional.of(entity);
        } catch (DocumentNotFoundException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new GitStoreException("Failed to deserialize entity of type " + entityClass.getName(), e);
        }
    }

    /**
     * Checks if a document exists by ID.
     */
    public <T> boolean existsById(Object id, Class<T> entityClass) {
        if (id == null) {
            return false;
        }
        DocumentMetadata<T> metadata = getMetadata(entityClass);
        String relativePath = metadata.resolveRelativePath(id.toString());
        return plumbingEngine.exists(relativePath);
    }

    /**
     * Deletes a document by its ID.
     */
    public <T> void deleteById(Object id, Class<T> entityClass) {
        if (id == null) {
            return;
        }
        DocumentMetadata<T> metadata = getMetadata(entityClass);
        String relativePath = metadata.resolveRelativePath(id.toString());
        String commitMessage = "gitstore: delete " + entityClass.getSimpleName() + "[" + id + "]";
        plumbingEngine.deleteBlob(relativePath, commitMessage);
    }

    /**
     * Finds all documents of a given entity type in the repository.
     */
    public <T> List<T> findAll(Class<T> entityClass) {
        DocumentMetadata<T> metadata = getMetadata(entityClass);
        List<String> paths = plumbingEngine.listPaths(metadata.getCollectionPath());
        List<T> results = new ArrayList<>();

        for (String path : paths) {
            if (path.endsWith(metadata.getExtension())) {
                try {
                    JGitPlumbingEngine.StoredBlob blob = plumbingEngine.readBlob(path);
                    T entity = objectMapper.readValue(blob.getContent(), entityClass);
                    metadata.setVersion(entity, blob.getCommitSha());
                    results.add(entity);
                } catch (Exception e) {
                    // Skip or log unparseable documents
                }
            }
        }
        return results;
    }

    public JGitPlumbingEngine getPlumbingEngine() {
        return plumbingEngine;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
