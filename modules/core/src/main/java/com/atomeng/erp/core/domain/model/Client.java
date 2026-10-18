package com.atomeng.erp.core.domain.model;

import com.atomeng.common.core.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/** 고객(발주처) — core.client (설계 7.1). 공간 컬럼 geom 은 postgis 프로필 마이그레이션에서 추가. */
@Entity
@Table(schema = "core", name = "client")
@SQLRestriction("deleted_at IS NULL")
public class Client extends BaseEntity {

    @Column(name = "client_no", nullable = false, unique = true, length = 20)
    private String clientNo;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "biz_reg_no", length = 12)
    private String bizRegNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 20)
    private ClientType clientType;

    @Column(length = 300)
    private String address;

    @Column(columnDefinition = "text")
    private String note;

    protected Client() {}

    public static Client create(String clientNo, String name, ClientType type, String bizRegNo, String address) {
        var c = new Client();
        c.clientNo = clientNo;
        c.name = requireText(name, "name");
        c.clientType = type;
        c.bizRegNo = bizRegNo;
        c.address = address;
        return c;
    }

    /** 부분 수정(PATCH) — null 은 변경 없음 */
    public void change(String name, ClientType type, String bizRegNo, String address, String note) {
        if (name != null) {
            this.name = requireText(name, "name");
        }
        if (type != null) {
            this.clientType = type;
        }
        if (bizRegNo != null) {
            this.bizRegNo = bizRegNo;
        }
        if (address != null) {
            this.address = address;
        }
        if (note != null) {
            this.note = note;
        }
    }

    private static String requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
        return v.trim();
    }

    public String getClientNo() {
        return clientNo;
    }

    public String getName() {
        return name;
    }

    public String getBizRegNo() {
        return bizRegNo;
    }

    public ClientType getClientType() {
        return clientType;
    }

    public String getAddress() {
        return address;
    }

    public String getNote() {
        return note;
    }
}
