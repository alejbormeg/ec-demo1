package io.mateu.ecdemo1.mapping.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WaiterRepository extends JpaRepository<Waiter, String> {

    @Query("""
            select w from Waiter w
            where w.status = 'WAITING'
              and exists (select 1 from WaiterCause l where l.processKey = w.processKey and l.causeKey = :cause)
            """)
    List<Waiter> waitingOn(@Param("cause") String causeKey);

    /**
     * Locks the waiter's row until the transaction ends, and says its status as last committed — empty
     * when there is none. Two causes of the same process resolved at once both lock it before counting
     * its open causes: whoever comes second counts after the first committed, and sees both resolved.
     */
    @Query(value = "select status from waiter where process_key = :key for update", nativeQuery = true)
    java.util.Optional<String> lockStatus(@Param("key") String processKey);

    /**
     * The processes a person can still discard from this cause: those waiting on it, and those it
     * released that have not answered — oldest first.
     */
    @Query("""
            select w from Waiter w
            where w.status in ('WAITING', 'RELEASED')
              and exists (select 1 from WaiterCause l where l.processKey = w.processKey and l.causeKey = :cause)
            order by w.createdAt
            """)
    List<Waiter> pendingOn(@Param("cause") String causeKey);

    /**
     * Released, not answered since {@code before}, and released after {@code releasedAfter}. Only
     * RELEASED ones: a DISCARDED process is never signalled again. And past {@code releasedAfter} not
     * either: a process that has not answered in all that time is not going to — the engine cancelled
     * or finished it — and it is left for a person to discard.
     */
    @Query("""
            select w from Waiter w
            where w.status = 'RELEASED' and w.lastSignalAt < :before and w.releasedAt > :releasedAfter
            """)
    List<Waiter> releasedAndSilentSince(@Param("before") Instant before, @Param("releasedAfter") Instant releasedAfter);

    @Query("""
            select count(w) from Waiter w
            where w.status = 'WAITING'
              and exists (select 1 from WaiterCause l where l.processKey = w.processKey and l.causeKey = :cause)
            """)
    long countWaitingOn(@Param("cause") String causeKey);
}
