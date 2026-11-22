package com.actionloop.api.domain;

/**
 * 数据分级（指导书 15.1）：S0 公开、S1 内部、S2 敏感默认、S3 高敏。
 */
public enum PrivacyLevel {
    S0,
    S1,
    S2,
    S3
}
