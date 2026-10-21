package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.OutboxEvent;
import com.delivery.delivery_service.repository.OutboxEventRepository;
import com.delivery.observability.SafeLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Short DB transactions for Delivery outbox leases; Kafka I/O stays outside. */
@Service
public class OutboxLeaseService {
    private final OutboxEventRepository repository;

    public OutboxLeaseService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<OutboxEvent> claim(int batchSize, UUID leaseToken, long leaseSeconds) {
        LocalDateTime until = LocalDateTime.now().plusSeconds(Math.max(5, leaseSeconds));
        List<OutboxEvent> events = repository.lockNextClaimableBatch(Math.max(1, Math.min(batchSize, 500)));
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        for (OutboxEvent event : events) {
            event.setStatus(OutboxEvent.OutboxStatus.IN_FLIGHT);
            event.setLeaseToken(leaseToken);
            event.setLeaseUntil(until);
            repository.save(event);
        }
        return events;
    }

    @Transactional
    public boolean markSent(Long id, UUID token) {
        OutboxEvent event = owned(id, token);
        if (event == null) return false;
        event.setStatus(OutboxEvent.OutboxStatus.SENT);
        event.setSentAt(LocalDateTime.now());
        event.setLastError(null);
        clearLease(event);
        repository.save(event);
        return true;
    }

    @Transactional
    public boolean markFailure(Long id, UUID token, Throwable failure, int maxAttempts) {
        OutboxEvent event = owned(id, token);
        if (event == null) return false;
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(abbreviate(SafeLog.exceptionMessage(failure)));
        if (attempts >= Math.max(1, Math.min(maxAttempts, 100))) {
            event.setStatus(OutboxEvent.OutboxStatus.DEAD);
        } else {
            long delay = Math.min(300L, 1L << Math.min(Math.max(attempts - 1, 0), 8));
            event.setStatus(OutboxEvent.OutboxStatus.PENDING);
            event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delay));
        }
        clearLease(event);
        repository.save(event);
        return true;
    }

    private OutboxEvent owned(Long id, UUID token) {
        if (id == null || token == null) return null;
        OutboxEvent event = repository.findByIdForUpdate(id).orElse(null);
        return event != null && event.getStatus() == OutboxEvent.OutboxStatus.IN_FLIGHT
                && token.equals(event.getLeaseToken()) ? event : null;
    }

    private void clearLease(OutboxEvent event) {
        event.setLeaseToken(null);
        event.setLeaseUntil(null);
    }

    private String abbreviate(String message) {
        if (message == null) return "Unknown Kafka publish failure";
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
