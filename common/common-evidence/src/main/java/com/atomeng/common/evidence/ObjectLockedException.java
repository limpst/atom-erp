package com.atomeng.common.evidence;

/** WORM(Object Lock) 보호 객체를 덮어쓰거나 삭제하려 할 때 (MinIO "Object is WORM protected" 대응) */
public class ObjectLockedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ObjectLockedException(String bucket, String key) {
        super("Object is WORM protected: " + bucket + "/" + key);
    }
}
