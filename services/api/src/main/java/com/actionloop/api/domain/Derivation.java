package com.actionloop.api.domain;

/**
 * 字段推导来源（指导书 3.2）：用户修改后为 USER，保留原推导历史。
 */
public enum Derivation {
    AI,
    RULE,
    USER,
    OFFICIAL,
    USER_CONFIRMED_AI
}
