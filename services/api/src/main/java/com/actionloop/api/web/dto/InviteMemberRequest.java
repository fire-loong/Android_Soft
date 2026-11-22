package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 邀请成员：按邮箱定位已注册用户，创建 PENDING 成员关系，由对方接受。 */
public record InviteMemberRequest(
        @NotBlank @Email String email,
        Role role
) {
}
