보안 검토(docs/SECURITY_REVIEW_2026-10-19.md §2) 정책 결정으로 설계 §8.2 표와 달라진 권한.
파일 하나 = 결정 하나(<ID>.csv). 줄 형식: ROLE,module:resource:action,SCOPE(ALL/PROJECT/CLIENT/SELF) 또는 -(삭제),근거 ID
PermissionMatrixTest 가 표 해석 결과에 이 목록을 덮어쓴 뒤 DB 와 비교합니다.
