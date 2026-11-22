package com.actionloop.api.service;

import com.actionloop.api.domain.ServerEvent;
import com.actionloop.api.repository.ServerEventRepository;
import com.actionloop.api.web.dto.ServerEventView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 增量事件查询，供客户端断点续传（指导书 11.4）。 */
@Service
public class EventStreamService {

    private final ServerEventRepository repository;
    private final PermissionService permissionService;

    public EventStreamService(ServerEventRepository repository, PermissionService permissionService) {
        this.repository = repository;
        this.permissionService = permissionService;
    }

    public List<ServerEventView> list(UUID workspaceId, UUID userId, long afterSeq, int limit) {
        permissionService.requireActiveMember(workspaceId, userId);
        int size = Math.min(Math.max(limit, 1), 200);
        List<ServerEvent> events = repository.findByWorkspaceIdAndSeqGreaterThanOrderBySeqAsc(
                workspaceId, Math.max(afterSeq, 0), PageRequest.of(0, size));
        return events.stream().map(e -> new ServerEventView(e.getSeq(), e.getAggregateId(),
                e.getAggregateType(), e.getEventType(), e.getVersion(), e.getPayloadMinimized(),
                e.getCreatedAt())).toList();
    }
}
