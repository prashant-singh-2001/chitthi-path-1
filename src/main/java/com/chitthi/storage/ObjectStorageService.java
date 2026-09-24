package com.chitthi.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Thin wrapper around the MinIO client. Object keys are opaque strings the
 * caller controls (e.g. {@code documents/{docId}/pages/{n}.png}); this class
 * only knows how to move bytes in and out of the configured bucket.
 */
@Service
public class ObjectStorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public ObjectStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @PostConstruct
    void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.bucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
            }
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to ensure bucket exists: " + properties.bucket(), e);
        }
    }

    public void putObject(String key, byte[] content, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(key)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to store object: " + key, e);
        }
    }

    public byte[] getObject(String key) {
        try (var stream = minioClient.getObject(io.minio.GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(key)
                .build())) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new ObjectStorageException("Failed to read object: " + key, e);
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to fetch object: " + key, e);
        }
    }

    /**
     * Used by the assembler to skip a page's track file that was never
     * written - e.g. the {@code orig} track for a page whose source language
     * Bulbul doesn't support, or a page's track that never finished before it
     * was marked FAILED.
     */
    public boolean exists(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(key)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new ObjectStorageException("Failed to check object existence: " + key, e);
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to check object existence: " + key, e);
        }
    }
}
