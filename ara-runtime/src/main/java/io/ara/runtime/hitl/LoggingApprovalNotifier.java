package io.ara.runtime.hitl;

import io.ara.core.hitl.ApprovalNotifier;
import io.ara.core.hitl.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ApprovalNotifier} that logs pending approval requests via SLF4J at WARN level.
 *
 * <p>Zero external dependencies — suitable for development, testing, and any
 * environment where a structured log entry is sufficient to alert operators.
 */
public class LoggingApprovalNotifier implements ApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger("ara.hitl");

    @Override
    public void notify(ApprovalRequest request) {
        log.warn("Approval required — requestId={}, agentId={}, action={}, expiresAt={}",
                request.requestId(),
                request.agentId(),
                request.action(),
                request.expiresAt());
    }
}
