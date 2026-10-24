package com.gitstore.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Git Document entity.
 * Similar to {@code @Document} in Spring Data MongoDB.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GitDocument {

    /**
     * Folder/directory path inside the repository to store documents of this type.
     * E.g. "customers" -> "data/customers/{id}.json"
     */
    String path() default "";

    /**
     * File extension for stored documents. Defaults to ".json".
     */
    String extension() default ".json";
}
