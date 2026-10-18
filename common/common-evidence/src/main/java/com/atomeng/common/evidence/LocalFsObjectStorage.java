package com.atomeng.common.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 로컬 파일시스템 어댑터 — 테스트·개발 PC 용 (MinIO 대체). {root}/{bucket}/{key} 에 저장.
 * WORM 버킷은 기존 키 덮어쓰기·삭제를 거부해 Object Lock 을 모사한다.
 */
public class LocalFsObjectStorage implements ObjectStorage {

    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._\\-/]+$");

    private final Path root;
    private final Set<String> wormBuckets;
    private final Clock clock;

    public LocalFsObjectStorage(Path root, Set<String> wormBuckets, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.wormBuckets = Set.copyOf(wormBuckets);
        this.clock = clock;
    }

    @Override
    public PresignedUpload presignPut(String bucket, String key, Duration ttl) {
        Instant exp = clock.instant().plus(ttl);
        // 로컬 어댑터의 "서명 URL" 은 위치 표시용(local://). 실제 PUT 은 put() 으로 모사한다.
        URI url = URI.create("local://" + bucket + "/" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "?expires=" + exp.getEpochSecond());
        return new PresignedUpload(url, exp);
    }

    @Override
    public void put(String bucket, String key, InputStream content, String contentType) {
        Path p = path(bucket, key);
        try {
            Files.createDirectories(p.getParent());
            if (wormBuckets.contains(bucket)) {
                if (Files.exists(p)) {
                    throw new ObjectLockedException(bucket, key);
                }
                Files.copy(content, p);
            } else {
                Files.copy(content, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (FileAlreadyExistsException e) {
            throw new ObjectLockedException(bucket, key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream get(String bucket, String key) {
        try {
            return Files.newInputStream(path(bucket, key));
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("object not found: " + bucket + "/" + key, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        return Files.isRegularFile(path(bucket, key));
    }

    @Override
    public long size(String bucket, String key) {
        try {
            return Files.size(path(bucket, key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void copy(String srcBucket, String srcKey, String dstBucket, String dstKey) {
        try (InputStream in = get(srcBucket, srcKey)) {
            put(dstBucket, dstKey, in, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        if (wormBuckets.contains(bucket) && exists(bucket, key)) {
            throw new ObjectLockedException(bucket, key);
        }
        try {
            Files.deleteIfExists(path(bucket, key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(String bucket, String key) {
        if (!SAFE_KEY.matcher(bucket).matches() || !SAFE_KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("unsafe bucket/key: " + bucket + "/" + key);
        }
        Path p = root.resolve(bucket).resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("path traversal: " + key);
        }
        return p;
    }
}
