package com.choruskube.core.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;

/** Deletes every object under a key prefix in the artifact bucket, and tells whether any remain. */
@Component
public class ObjectTreeDeleter {

    private static final int DELETE_BATCH = 1000; // the most keys one DeleteObjects request may name

    private final S3Client s3Client;
    private final String bucket;

    public ObjectTreeDeleter(S3Client s3Client, @Value("${objectstore.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    /** Throws if the store refuses any key, so a caller never records a tree it did not empty. */
    public void deleteAll(String prefix) {
        requireTree(prefix);
        List<ObjectIdentifier> keys =
                s3Client.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
        for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
            List<ObjectIdentifier> batch = keys.subList(from, Math.min(from + DELETE_BATCH, keys.size()));
            DeleteObjectsResponse response = s3Client.deleteObjects(
                    b -> b.bucket(bucket).delete(d -> d.objects(batch).quiet(true)));
            if (response.hasErrors() && !response.errors().isEmpty()) {
                S3Error first = response.errors().getFirst();
                throw new IllegalStateException(response.errors().size() + " of " + batch.size() + " objects under "
                        + prefix + " were not deleted; first: " + first.code() + " " + first.message());
            }
        }
    }

    public boolean isEmpty(String prefix) {
        requireTree(prefix);
        return s3Client.listObjectsV2(b -> b.bucket(bucket).prefix(prefix).maxKeys(1))
                .contents()
                .isEmpty();
    }

    // A blank prefix names the whole bucket, and one without its trailing slash also names its siblings.
    private static void requireTree(String prefix) {
        if (prefix == null || prefix.isBlank() || prefix.equals("/") || !prefix.endsWith("/")) {
            throw new IllegalArgumentException("Not a storage tree prefix: '" + prefix + "'");
        }
    }
}
