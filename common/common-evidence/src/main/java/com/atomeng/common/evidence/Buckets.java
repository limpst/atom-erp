package com.atomeng.common.evidence;

import java.util.Set;

/** MinIO 버킷 (설계 11.1) */
public final class Buckets {
    private Buckets() {}

    public static final String FIELD_ORIGINAL = "field-original";
    public static final String FIELD_DERIVED = "field-derived";
    public static final String DRAWINGS = "drawings";
    public static final String REPORTS = "reports";
    public static final String AI_DATASETS = "ai-datasets";
    public static final String TEMP_UPLOAD = "temp-upload";

    /** Object Lock(Compliance/Governance) 대상 */
    public static final Set<String> WORM = Set.of(FIELD_ORIGINAL, DRAWINGS, REPORTS);
}
