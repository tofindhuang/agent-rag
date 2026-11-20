package com.agentcore.server.common.storage;

import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.config.KbS3Properties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3-compatible object store (Silo / MinIO / cloud S3) for KB originals.
 *
 * <p>The client is built lazily so the application starts even when object
 * storage is unreachable or credentials are not configured; failures surface
 * at the first use instead of boot.
 */
@Component
public class S3ObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);

    private final KbS3Properties properties;
    private final Object bucketLock = new Object();
    private volatile S3Client client;
    private volatile boolean bucketReady;

    public S3ObjectStore(KbS3Properties properties) {
        this.properties = properties;
    }

    @Override
    public String key(long userId, String documentId, String sha256, String filename) {
        return ObjectKey.of(userId, documentId, sha256, filename);
    }

    @Override
    public void put(String key, Path source, String contentType, String sha256) {
        long size;
        try {
            size = Files.size(source);
        } catch (IOException e) {
            throw KbStoreException.permanent("cannot read original file: " + e.getMessage(), e);
        }
        ensureBucket();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("sha256", sha256);
        metadata.put("size", Long.toString(size));
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .metadata(metadata);
        if (contentType != null && !contentType.isBlank()) {
            request.contentType(contentType);
        }
        try {
            client().putObject(request.build(), RequestBody.fromFile(source));
        } catch (S3Exception e) {
            throw mapS3Failure("put object " + key, e);
        } catch (KbStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw KbStoreException.retryable("object storage put failed: " + e.getMessage(), e);
        }
    }

    @Override
    public StoredObject get(String key) {
        try {
            var response = client().getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            var metadata = response.response();
            String sha = metadata.metadata() == null
                    ? null
                    : metadata.metadata().get("sha256");
            return new StoredObject(
                    response,
                    metadata.contentType(),
                    sha,
                    metadata.contentLength() == null ? 0L : metadata.contentLength());
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            throw KbStoreException.notFound("object not found: " + key, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw KbStoreException.notFound("object not found: " + key, e);
            }
            throw mapS3Failure("get object " + key, e);
        } catch (KbStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw KbStoreException.retryable("object storage get failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (NoSuchBucketException e) {
            log.debug("bucket {} already gone while deleting {}", properties.bucket(), key);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return;
            }
            throw mapS3Failure("delete object " + key, e);
        } catch (KbStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw KbStoreException.retryable("object storage delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client().headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw mapS3Failure("head object " + key, e);
        } catch (KbStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw KbStoreException.retryable("object storage head failed: " + e.getMessage(), e);
        }
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (bucketLock) {
            if (bucketReady) {
                return;
            }
            try {
                client().headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
                bucketReady = true;
                return;
            } catch (NoSuchBucketException e) {
                createBucket();
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw mapS3Failure("head bucket " + properties.bucket(), e);
                }
                createBucket();
            } catch (KbStoreException e) {
                throw e;
            } catch (RuntimeException e) {
                throw KbStoreException.retryable(
                        "object storage bucket check failed: " + e.getMessage(), e);
            }
        }
    }

    private void createBucket() {
        CreateBucketRequest.Builder request = CreateBucketRequest.builder().bucket(properties.bucket());
        // Cloud S3 requires an explicit location outside us-east-1; Silo/MinIO ignore it.
        if (!"us-east-1".equals(properties.region())) {
            request.createBucketConfiguration(CreateBucketConfiguration.builder()
                    .locationConstraint(properties.region())
                    .build());
        }
        try {
            client().createBucket(request.build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            log.debug("bucket {} already exists", properties.bucket());
        } catch (S3Exception e) {
            throw mapS3Failure("create bucket " + properties.bucket(), e);
        } catch (KbStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw KbStoreException.retryable(
                    "object storage bucket creation failed: " + e.getMessage(), e);
        }
        bucketReady = true;
    }

    private S3Client client() {
        S3Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client != null) {
                return client;
            }
            if (!properties.configured()) {
                throw KbStoreException.permanent(
                        "object storage credentials are not configured", null);
            }
            client = S3Client.builder()
                    .endpointOverride(URI.create(properties.endpoint()))
                    .region(Region.of(properties.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                    .forcePathStyle(properties.pathStyle())
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .build();
            return client;
        }
    }

    private static KbStoreException mapS3Failure(String action, S3Exception e) {
        int status = e.statusCode();
        boolean retryable = status == 408 || status == 429 || status >= 500;
        String message = "object storage " + action + " failed with status " + status;
        return retryable
                ? KbStoreException.retryable(message, e)
                : KbStoreException.permanent(message, e);
    }

    @PreDestroy
    void close() {
        S3Client current = client;
        if (current != null) {
            current.close();
        }
    }
}
