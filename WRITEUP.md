# Engineering Write-up: High-Concurrency Seat Reservation System

## 1. The Atomic Decision Mechanism
Under extreme concurrency (~20,000 bursts on hot seats), standard "check-then-act" patterns (`SELECT -> if available -> UPDATE`) suffer from race conditions, leading to double-allocation.

This service eliminates application-level race conditions by pushing the atomic decision directly into the database engine via a single conditional update statement:

```sql
UPDATE seats 
SET status = 'CONFIRMED', 
    reserved_by_user_id = :userId, 
    reservation_id = :reservationId, 
    updated_at = :now 
WHERE show_id = :showId 
  AND seat_number = :seatNumber 
  AND status = 'AVAILABLE';
Locking Scope: InnoDB locks only the specific index record because (show_id, seat_number) is protected by a unique composite index (uk_show_seat_number). Other seats remain unlocked and concurrently bookable.

Race Resolution: Exactly one transaction matches status = 'AVAILABLE' and updates 1 row (affected_rows == 1). Competing concurrent transactions match 0 rows and are immediately rejected with a clean 409 Conflict (SEAT_ALREADY_TAKEN), never throwing an unhandled 500 Server Error.

Deadlock Elimination for Multi-Seat Bookings
When a buyer requests multiple seats (e.g., ["B2", "A1"]), circular lock acquisition can trigger engine-level deadlocks if two transactions request the same seats in alternating order.
To prevent deadlocks, all requested seats are sorted deterministically in memory before any row locks or updates execute:

Java
List<String> sortedSeats = request.getSeats().stream().distinct().sorted().toList();
Acquiring row locks in identical alphabetical order globally eliminates circular wait dependencies. If any single seat in the batch is unavailable, the transaction rolls back cleanly, guaranteeing all-or-nothing atomicity.

2. Idempotency & Exactly-Once Semantics
Network retries during peak traffic must not trigger duplicate bookings or double-charges.

Storage: Persisted in the idempotency_records table with columns: idempotency_key (PK), user_id, request_hash, status_code, and response_body.

Payload Verification: A SHA-256 fingerprint of the request payload (showId:seat1,seat2...) is stored with the key:

Identical Replay: If the same key arrives with the exact same payload hash, the cached 201 Created response is returned immediately without altering inventory or charging again.

Tampered Replay: If the same key is reused with a different payload or different seats, the request is rejected with 409 Conflict (IDEMPOTENCY_PAYLOAD_MISMATCH).

3. Holds, Cancellations, and Expiry Model
Explicit Cancellation: Implemented via POST /reservations/{id}/cancel. A user can only cancel reservations linked to their token-derived identity.

Atomic Release: Cancelling resets the seats back to AVAILABLE and clears user bindings in a single transaction:

SQL
UPDATE seats 
SET status = 'AVAILABLE', reserved_by_user_id = NULL, reservation_id = NULL 
WHERE reservation_id = :resId;
Hold Expiry Design: To extend this to time-boxed holds (e.g., 5-minute checkout windows), seats can be transitioned to HELD with an expires_at timestamp. Reclaiming expired seats is handled via a lazy conditional claim during booking:

SQL
WHERE (status = 'AVAILABLE' OR (status = 'HELD' AND expires_at < :now))
This eliminates reliance on brittle background cron polling and reclaims seats at query time.

4. Per-User Limit Invariant
The system enforces per_user_limit (default: 4 seats per show) within the transaction boundary:

SQL
SELECT COUNT(*) FROM seats 
WHERE show_id = :showId 
  AND reserved_by_user_id = :userId 
  AND status = 'CONFIRMED';
If current_held + requested_seats > per_user_limit, the request is declined with 409 Conflict (USER_LIMIT_EXCEEDED).

5. Consistency vs. Availability (CAP Invariant under Partition)
For a seat reservation system, Consistency strictly trumps Availability.

A seat is an exclusive physical asset. In an inventory system, double-selling a ticket creates legal and operational liabilities that cannot be reconciled automatically.

Under a network partition or database leader failover, the system chooses Consistency: write requests fail closed with a clean 503 Service Unavailable or 409 Conflict rather than risking split-brain duplicate seat confirmations. Reads to the show status endpoint may operate under bounded staleness if read replicas are deployed.

6. Observability & 2 AM Paging Alerts
Observability is built directly on Spring Boot Actuator and Micrometer Prometheus:

Endpoints:

Health & Readiness: /actuator/health, /actuator/health/readiness

Prometheus Metrics: /actuator/prometheus

Key Metrics Tracked:

reservations_total{status="confirmed"} (Counter)

reservations_declined_total{reason="seat_taken|user_limit_exceeded|idempotent_replay"} (Counter)

What to Page for at 2 AM:

HikariCP Connection Pool Starvation: hikaricp_pending_threads > 10 for > 30 seconds.

Server Error Rate Spike: HTTP 5xx rate > 0.1% over a 1-minute rolling window.

Reconciliation Invariant Breach: Any state where available + held + confirmed != total_seats.

7. AI Usage Disclosure
Directed vs. Decided: AI (Gemini) was used as a rapid scaffolding assistant to set up initial Spring Initializr boilerplates, configure Docker buildfiles, and script the shell concurrency harness (burst.sh).

Architectural Decisions: The core concurrency primitives — including row-level conditional updates over application-level locks, SHA-256 idempotency caching, alphabetical lock ordering for deadlock prevention, and integer minor-unit paise representation — were chosen and verified intentionally to satisfy the assignment's correctness bar.

8. What I'd Do Next (Production Roadmap)
Redis Distributed Locks / Token Bucket: Introduce a Redis Lua script layer ahead of MySQL to shed 90% of excessive hot-seat traffic before it hits the relational database connection pool.

Read Replica Decoupling: Route GET /shows/{id} queries to read replicas while reserving the primary database instance strictly for transactional seat reservation writes.

Outbox Pattern for Event-Driven Notifications: Implement the transactional outbox pattern to emit Kafka events (e.g., ReservationConfirmed, PaymentDue) reliably after database commits.