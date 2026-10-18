package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.evidence.Buckets;
import com.atomeng.common.evidence.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;

/**
 * RPT 객체 저장 (설계 06 B8 MinIO 버킷 → 이번 단계 common-evidence ObjectStorage, 로컬은 파일시스템 WORM 모사).
 * rpt-snapshots(동결 JSON) · rpt-renders(HWPX · PDF · DXF · PNG · anchors.json, 덮어쓰기 가능) ·
 * reports(발행본, WORM — Object Lock 모사: 덮어쓰기 · 삭제 불가, TC-RPT-007).
 */
@Component
public class RptStorage {

    public static final String SNAPSHOTS = "rpt-snapshots";
    public static final String RENDERS = "rpt-renders";
    public static final String PUBLISHED = Buckets.REPORTS;

    private final ObjectStorage storage;

    public RptStorage(ObjectStorage storage) {
        this.storage = storage;
    }

    public void put(String bucket, String key, byte[] bytes, String contentType) {
        storage.put(bucket, key, new ByteArrayInputStream(bytes), contentType);
    }

    /** WORM 버킷 쓰기 — 같은 키 · 같은 내용이 이미 있으면 통과(재시도 멱등), 다르면 ObjectLockedException */
    public void putWorm(String key, byte[] bytes, String contentType) {
        if (storage.exists(PUBLISHED, key) && Hashing.sha256Hex(get(PUBLISHED, key)).equals(Hashing.sha256Hex(bytes))) {
            return;
        }
        storage.put(PUBLISHED, key, new ByteArrayInputStream(bytes), contentType);
    }

    public byte[] get(String bucket, String key) {
        try (InputStream in = storage.get(bucket, key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String bucket, String key) {
        return storage.exists(bucket, key);
    }

    public void delete(String bucket, String key) {
        storage.delete(bucket, key);
    }
}
