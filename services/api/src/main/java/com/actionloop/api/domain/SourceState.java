package com.actionloop.api.domain;

/**
 * 来源状态（指导书 3.4）。只有官方域名/账号、明确发布主体且可复核的来源可标 VERIFIED_OFFICIAL。
 */
public enum SourceState {
    VERIFIED_OFFICIAL,
    USER_PROVIDED,
    COMMUNITY_UNVERIFIED,
    EXPIRED,
    FETCH_FAILED
}
