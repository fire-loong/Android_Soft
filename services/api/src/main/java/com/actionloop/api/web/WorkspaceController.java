package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.MembershipService;
import com.actionloop.api.service.WorkspaceService;
import com.actionloop.api.web.dto.ChangeRoleRequest;
import com.actionloop.api.web.dto.CreateWorkspaceRequest;
import com.actionloop.api.web.dto.InviteMemberRequest;
import com.actionloop.api.web.dto.InviteView;
import com.actionloop.api.web.dto.MemberView;
import com.actionloop.api.web.dto.WorkspaceDetailView;
import com.actionloop.api.web.dto.WorkspaceView;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 空间与成员端点（指导书 5.x / 13.x）。所有写操作都在服务层做权限校验并写审计，
 * 不依赖前端隐藏按钮。
 */
@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final MembershipService membershipService;

    public WorkspaceController(WorkspaceService workspaceService, MembershipService membershipService) {
        this.workspaceService = workspaceService;
        this.membershipService = membershipService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/workspaces")
    public ApiResponse<List<WorkspaceView>> list(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.of(workspaceService.listMyWorkspaces(me.userId()), reqId());
    }

    @PostMapping("/workspaces")
    public ApiResponse<WorkspaceView> create(@AuthenticationPrincipal UserPrincipal me,
                                             @Valid @RequestBody CreateWorkspaceRequest request) {
        return ApiResponse.of(workspaceService.createTeam(me.userId(), request), reqId());
    }

    @GetMapping("/workspaces/{id}")
    public ApiResponse<WorkspaceDetailView> detail(@AuthenticationPrincipal UserPrincipal me,
                                                   @PathVariable UUID id) {
        return ApiResponse.of(workspaceService.getDetail(id, me.userId()), reqId());
    }

    @GetMapping("/workspaces/{id}/members")
    public ApiResponse<List<MemberView>> members(@AuthenticationPrincipal UserPrincipal me,
                                                 @PathVariable UUID id) {
        return ApiResponse.of(membershipService.listMembers(id, me.userId()), reqId());
    }

    @PostMapping("/workspaces/{id}/members")
    public ApiResponse<MemberView> invite(@AuthenticationPrincipal UserPrincipal me,
                                          @PathVariable UUID id,
                                          @Valid @RequestBody InviteMemberRequest request) {
        return ApiResponse.of(membershipService.invite(id, me.userId(), request), reqId());
    }

    @PatchMapping("/workspaces/{id}/members/{userId}")
    public ApiResponse<MemberView> changeRole(@AuthenticationPrincipal UserPrincipal me,
                                              @PathVariable UUID id,
                                              @PathVariable UUID userId,
                                              @Valid @RequestBody ChangeRoleRequest request) {
        return ApiResponse.of(membershipService.changeRole(id, me.userId(), userId, request.role()), reqId());
    }

    @DeleteMapping("/workspaces/{id}/members/{userId}")
    public ApiResponse<Void> remove(@AuthenticationPrincipal UserPrincipal me,
                                    @PathVariable UUID id,
                                    @PathVariable UUID userId) {
        membershipService.removeMember(id, me.userId(), userId);
        return ApiResponse.of(null, reqId());
    }

    @GetMapping("/invitations")
    public ApiResponse<List<InviteView>> invitations(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.of(membershipService.listMyInvitations(me.userId()), reqId());
    }

    @PostMapping("/workspaces/{id}/invitations/accept")
    public ApiResponse<MemberView> accept(@AuthenticationPrincipal UserPrincipal me,
                                          @PathVariable UUID id) {
        return ApiResponse.of(membershipService.accept(id, me.userId()), reqId());
    }

    @PostMapping("/workspaces/{id}/invitations/decline")
    public ApiResponse<Void> decline(@AuthenticationPrincipal UserPrincipal me,
                                     @PathVariable UUID id) {
        membershipService.decline(id, me.userId());
        return ApiResponse.of(null, reqId());
    }
}
