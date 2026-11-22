package com.actionloop.api.domain;

/**
 * 时间精度（指导书 3.2）：只有日期没有时刻时保存 DATE，不可擅自补成 23:59。
 */
public enum TimePrecision {
    DATE,
    MINUTE,
    NONE
}
