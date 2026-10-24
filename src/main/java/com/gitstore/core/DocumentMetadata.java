package com.gitstore.core;

import com.gitstore.annotation.GitDocument;
import com.gitstore.annotation.GitId;
import com.gitstore.annotation.GitVersion;
import com.gitstore.exception.GitStoreException;

import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Extracts and inspects entity metadata using reflection.
 */
public class DocumentMetadata<T> {

    private final Class<T> entityClass;
    private final String collectionPath;
    private final String extension;
    private final Field idField;
    private final Field versionField;

    public DocumentMetadata(Class<T> entityClass) {
        this.entityClass = Objects.requireNonNull(entityClass, "entityClass must not be null");

        GitDocument doc = entityClass.getAnnotation(GitDocument.class);
        if (doc == null) {
            throw new GitStoreException("Class " + entityClass.getName() + " must be annotated with @GitDocument");
        }

        String path = doc.path().trim();
        if (path.isEmpty()) {
            path = entityClass.getSimpleName().toLowerCase();
        }
        this.collectionPath = path.replaceAll("^/+|/+$", "");
        this.extension = doc.extension().startsWith(".") ? doc.extension() : "." + doc.extension();

        Field foundId = null;
        Field foundVersion = null;

        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(GitId.class)) {
                    if (foundId != null) {
                        throw new GitStoreException("Multiple @GitId fields found in " + entityClass.getName());
                    }
                    field.setAccessible(true);
                    foundId = field;
                }
                if (field.isAnnotationPresent(GitVersion.class)) {
                    if (foundVersion != null) {
                        throw new GitStoreException("Multiple @GitVersion fields found in " + entityClass.getName());
                    }
                    field.setAccessible(true);
                    foundVersion = field;
                }
            }
            current = current.getSuperclass();
        }

        if (foundId == null) {
            throw new GitStoreException("No @GitId field found in " + entityClass.getName());
        }

        this.idField = foundId;
        this.versionField = foundVersion;
    }

    public String getCollectionPath() {
        return collectionPath;
    }

    public String getExtension() {
        return extension;
    }

    public String resolveRelativePath(String id) {
        return collectionPath + "/" + id + extension;
    }

    public String extractId(Object entity) {
        try {
            Object idVal = idField.get(entity);
            if (idVal == null) {
                throw new GitStoreException("@GitId field is null on entity " + entity);
            }
            return idVal.toString();
        } catch (IllegalAccessException e) {
            throw new GitStoreException("Unable to access @GitId field on " + entity.getClass().getName(), e);
        }
    }

    public void setVersion(Object entity, String version) {
        if (versionField != null) {
            try {
                versionField.set(entity, version);
            } catch (IllegalAccessException e) {
                throw new GitStoreException("Unable to set @GitVersion field on " + entity.getClass().getName(), e);
            }
        }
    }

    public String extractVersion(Object entity) {
        if (versionField == null) {
            return null;
        }
        try {
            Object val = versionField.get(entity);
            return val != null ? val.toString() : null;
        } catch (IllegalAccessException e) {
            throw new GitStoreException("Unable to access @GitVersion field on " + entity.getClass().getName(), e);
        }
    }
}
