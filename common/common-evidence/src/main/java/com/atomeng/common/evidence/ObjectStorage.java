package com.atomeng.common.evidence;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * 객체 저장소 포트 (설계 11.1·11.2). 운영 어댑터 = MinIO(S3 API, Object Lock), 테스트·로컬 = {@link LocalFsObjectStorage}.
 * 업로드는 서버를 경유하지 않는다: presign → 클라이언트 PUT → 서버 :complete 에서 해시 재계산.
 */
public interface ObjectStorage {

    /** 업로드용 서명 URL (temp-upload 버킷, 설계 11.2-2: 15분) */
    PresignedUpload presignPut(String bucket, String key, Duration ttl);

    /** 서버측 쓰기(파생본·로컬 PUT 모사). WORM 버킷의 기존 키 덮어쓰기는 {@link ObjectLockedException}. */
    void put(String bucket, String key, InputStream content, String contentType);

    InputStream get(String bucket, String key);

    boolean exists(String bucket, String key);

    long size(String bucket, String key);

    /** 서버측 복사 (temp-upload → field-original). 대상 버킷이 WORM 이면 이후 덮어쓰기·삭제 불가. */
    void copy(String srcBucket, String srcKey, String dstBucket, String dstKey);

    /** 삭제. WORM(Object Lock) 버킷이면 {@link ObjectLockedException}. 없는 키는 무시. */
    void delete(String bucket, String key);

    record PresignedUpload(URI url, Instant expiresAt) {}
}
