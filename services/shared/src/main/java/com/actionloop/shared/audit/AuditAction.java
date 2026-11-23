package com.actionloop.shared.audit;

/**
 * 审计动作常量，指导书 11.3/15：所有完成、改派、权限、协助停止操作写审计。
 * 审计追加写入，普通成员不可删除。
 */
public final class AuditAction {

    private AuditAction() {
    }

    public static final String AUTH_REGISTER = "auth.register";
    public static final String AUTH_LOGIN = "auth.login";
    public static final String AUTH_LOGOUT = "auth.logout";
    public static final String AUTH_REFRESH = "auth.refresh";
    public static final String DEVICE_REVOKE = "device.revoke";

    public static final String WORKSPACE_CREATE = "workspace.create";
    public static final String MEMBERSHIP_INVITE = "membership.invite";
    public static final String MEMBERSHIP_JOIN = "membership.join";
    public static final String MEMBERSHIP_DECLINE = "membership.decline";
    public static final String MEMBERSHIP_ROLE_CHANGE = "membership.role_change";
    public static final String MEMBERSHIP_REMOVE = "membership.remove";

    public static final String TASK_CREATE = "task.create";
    public static final String TASK_UPDATE = "task.update";
    public static final String TASK_TRANSITION = "task.transition";
    public static final String TASK_DELETE = "task.delete";
    public static final String STEP_CREATE = "step.create";
    public static final String STEP_UPDATE = "step.update";
    public static final String STEP_DELETE = "step.delete";
    public static final String STEP_REASSIGN = "step.reassign";
    public static final String MATERIAL_CREATE = "material.create";
    public static final String MATERIAL_UPDATE = "material.update";
    public static final String MATERIAL_DELETE = "material.delete";
    public static final String REMINDER_REPLACE = "reminder.replace";
    public static final String ASSET_UPLOAD = "asset.upload";
    public static final String ASSET_COMPLETE = "asset.complete";
    public static final String ASSET_OCR = "asset.ocr";
    public static final String COMPLETION_SUBMIT = "completion.submit";
    public static final String COMPLETION_REVIEW = "completion.review";

    public static final String SOURCE_SHARE = "source.share";
    public static final String SOURCE_DELETE = "source.delete";
    public static final String SOURCE_RECHECK = "source.recheck";
    public static final String CANDIDATE_CONFIRM = "candidate.confirm";
    public static final String CANDIDATE_DISMISS = "candidate.dismiss";
    public static final String MEMORY_SEARCH = "memory.search";
    public static final String MEMORY_CONTEXT = "memory.context";
    public static final String COMMENT_CREATE = "comment.create";
    public static final String COMMENT_DELETE = "comment.delete";
    public static final String PROCESS_CREATE = "process.create";
    public static final String PROCESS_VERSION_CREATE = "process.version.create";
    public static final String PROCESS_VERSION_PUBLISH = "process.version.publish";
    public static final String PROCESS_INSTANTIATE = "process.instantiate";
    public static final String ASSIST_CREATE = "assist.create";
    public static final String ASSIST_ACCEPT = "assist.accept";
    public static final String ASSIST_STOP = "assist.stop";
    public static final String ASSIST_SIGNAL = "assist.signal";
    public static final String ASSIST_SIGNAL_REJECTED = "assist.signal.rejected";

    public static final String ASSIST_START = "assist.start";

    public static final String EXPORT_CREATE = "export.create";
    public static final String ACCOUNT_DELETE = "account.delete";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILURE = "FAILURE";
    public static final String RESULT_DENIED = "DENIED";
}
