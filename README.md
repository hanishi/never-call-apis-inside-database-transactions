# Never Call APIs Inside Database Transactions

> **TL;DR:** Never call external APIs inside a database transaction. If the external call succeeds but the DB commit fails, you end up with an inconsistent state you cannot roll back. This project is a complete, working implementation of **Transactional Outbox + Result Table + Saga Compensation** in Scala with Play Framework, Slick, and Pekko.

---

## Quick Start

```bash
git clone https://github.com/hanishi/never-call-apis-inside-database-transactions
cd never-call-apis-inside-database-transactions
docker-compose up -d postgres
sbt run
# Open http://localhost:9000
```

The project includes simulated external services (inventory, billing, shipping, fraud check) with configurable failure rates, an interactive UI for creating orders and triggering failures, and a complete audit trail of every API call.

---

## What This Is

If your application saves to a database and calls external APIs, you have a consistency problem, whether you know it yet or not. The [Transactional Outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html) and the [Saga pattern](https://microservices.io/patterns/data/saga.html) are well-documented solutions, but most explanations stop at the theory. This project doesn't.

This is a **complete, working implementation** of three patterns that work together:

1. **Transactional Outbox** — guarantee that every event gets processed, even if the app crashes mid-request
2. **Result Table** — record every API call, its response, and the IDs you'll need to undo it
3. **Saga Compensation** — when step 4 of 4 fails, automatically undo steps 1–3 in the right order

The implementation uses Play Framework, Scala 3, PostgreSQL, Slick, and Pekko actors, but the patterns are **language-agnostic and framework-agnostic**. Swap Slick for JPA, Pekko for a background job framework, Play for whatever you use. The database schema, the event lifecycle, the LIFO compensation logic: all of it translates directly.

---

## Why You Might Want This

If your system does any of the following, you have the dual-write problem:

- Saves to a database and then calls an external API that changes state in another system (charging a payment gateway, reserving inventory, scheduling a shipment, publishing to Kafka). Read-only calls like fetching a credit score don't cause dual-write issues, since there's nothing to roll back on the other side.
- Coordinates multiple services for a single business operation (e.g., reserve inventory → check fraud → schedule shipping → charge payment)
- Needs to undo partial work when one step in a multi-step process fails

Without these patterns, you'll eventually hit scenarios where money is charged but no order exists, inventory is reserved for ghost orders, or shipments are scheduled for cancelled transactions. The outbox pattern eliminates these inconsistencies by design, not by hoping your network stays up.

---

## The Problem: Why This Breaks

Imagine you're building an e-commerce system. When a customer places an order, you need to:

1. Save the order in your database
2. Reserve inventory
3. Schedule shipping
4. Charge the customer

Slick provides a convenient escape hatch: `DBIO.from()` lifts any `Future` into a `DBIO`, so it can be chained alongside database operations in a `for` comprehension. Since external API calls in Scala typically return `Future[T]`, it's tempting to wrap those HTTP calls and include them inside a `.transactionally` block. The code compiles, the types line up, and it *looks* like everything is safely transactional:

```scala
db.run {
  (for {
    orderId <- orders += order                              // DB write
    _       <- DBIO.from(inventoryApi.reserve(orderId))     // HTTP call wrapped in DBIO
    _       <- DBIO.from(shippingApi.schedule(orderId))     // HTTP call wrapped in DBIO
    _       <- DBIO.from(billingApi.charge(orderId))        // HTTP call wrapped in DBIO
    _       <- shipments += Shipment(orderId, shippingRes)  // DB write
  } yield orderId).transactionally
}
```

But it isn't. The database transaction only controls *database* operations. The HTTP calls inside `DBIO.from()` are fire-and-forget from the transaction's perspective: they execute, their side effects happen immediately, and the database has no way to roll them back. Here's what happens when that last DB write fails:

1. Insert order → succeeds
2. Call inventory API → 200 OK (inventory reserved)
3. Call shipping API → 200 OK (shipment scheduled)
4. Call billing API → 200 OK (customer charged)
5. Insert shipment record → **fails** (deadlock, network error, doesn't matter)
6. Transaction rolls back

**Result:** The customer is charged, a shipment is scheduled, inventory is reserved... but no order exists in your database. The external APIs don't know your transaction failed. They already did their work.

Separating the calls doesn't fix it either:

```scala
// Step 1: Save to database
val orderId = db.run(orders += order).transactionally  // ✅ Succeeds

// Step 2: Call external services
inventoryApi.reserve(orderId)   // ✅ Succeeds
shippingApi.schedule(orderId)   // ❌ App crashes here; shipping never called
billingApi.charge(orderId)      // ❌ Never executed
```

If the app crashes between step 1 and step 2, the order is saved but nothing else happens. No retry will ever fire because the `orderId` was lost from memory.

**The fundamental problem:** There is no transaction that spans distributed systems. Your database can guarantee atomicity for its own rows, but it has no authority over an HTTP API, a Kafka broker, or a third-party payment gateway. Each system commits independently, fails independently, and has no idea what the others are doing.

---

## The Solution: Transactional Outbox + Result Table + Saga Compensation

The fix is simple: **don't call external APIs during the request. Instead, record what needs to happen in your database, and let a background worker make the calls later.**

Instead of calling external APIs directly, you insert a row into an `outbox_events` table in the same database transaction as the order itself:

```sql
BEGIN;
  INSERT INTO orders (customer_id, total_amount, ...) VALUES ('C-123', 99.99, ...);
  INSERT INTO outbox_events (event_type, payloads, status) VALUES ('OrderCreated', '{...}', 'PENDING');
COMMIT;
```

Either both rows are saved, or neither is. No API calls are made during this transaction.

A background Pekko actor (`OutboxProcessor`) continuously watches this table. When it finds a `PENDING` event, it claims it, makes the actual HTTP calls, and marks it as `PROCESSED`. If the app crashes before the worker gets to it, the event is still sitting in the database. The worker picks it up when it restarts.

| Pattern                  | Problem it solves     | How                                            |
|--------------------------|-----------------------|------------------------------------------------|
| **Transactional Outbox** | Dual-write problem    | Write order + event to DB atomically           |
| **Result Table**         | "What do I undo?"     | Track every API call and its response          |
| **Saga Compensation**    | Partial failures      | Undo successful calls in LIFO order            |

---

## Database Schema

### `orders` — your business data

```sql
CREATE TABLE orders (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   VARCHAR(255)   NOT NULL,
    total_amount  DECIMAL(10, 2) NOT NULL,
    shipping_type VARCHAR(20)    NOT NULL DEFAULT 'domestic',
    order_status  VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN        NOT NULL DEFAULT FALSE
);
```

### `outbox_events` — the to-do list

```sql
CREATE TABLE outbox_events (
    id                BIGSERIAL PRIMARY KEY,
    aggregate_id      VARCHAR(255)  NOT NULL,
    event_type        VARCHAR(255)  NOT NULL,
    payloads          JSONB         NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    retry_count       INT           NOT NULL DEFAULT 0,
    idempotency_key   VARCHAR(512)  NOT NULL,
    next_retry_at     TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX idx_outbox_idempotency
    ON outbox_events (idempotency_key)
    WHERE status != 'PROCESSED';
```

### `aggregate_results` — the audit log

```sql
CREATE TABLE aggregate_results (
    id               BIGSERIAL PRIMARY KEY,
    aggregate_id     VARCHAR(255)  NOT NULL,
    destination      VARCHAR(255)  NOT NULL,
    request_payload  JSONB,
    response_payload JSONB,
    success          BOOLEAN       NOT NULL,
    fanout_order     INT           NOT NULL DEFAULT 0
);
```

### `dead_letter_events` — failed events awaiting compensation

```sql
CREATE TABLE dead_letter_events (
    id                 BIGSERIAL PRIMARY KEY,
    original_event_id  BIGINT        NOT NULL,
    aggregate_id       VARCHAR(255)  NOT NULL,
    event_type         VARCHAR(255)  NOT NULL,
    payloads           JSONB         NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    reason             VARCHAR(1024) NOT NULL
);
```

---

## Writing Events Atomically

Each business operation produces a domain event that knows which destinations it needs to reach and what data each one requires:

```scala
case class OrderCreatedEvent(
    orderId: Long, customerId: String,
    totalAmount: BigDecimal, shippingType: String,
    timestamp: Instant = Instant.now()
) extends DomainEvent {
  override def aggregateId = orderId.toString
  override def eventType   = "OrderCreated"

  override def toPayloads = Map(
    "inventory" -> DestinationConfig(payload = Some(Json.obj(
      "orderId" -> orderId, "totalAmount" -> totalAmount, "shippingType" -> shippingType
    ))),
    "fraudCheck" -> DestinationConfig(payload = Some(Json.obj(
      "orderId" -> orderId, "customerId" -> customerId, "totalAmount" -> totalAmount
    ))),
    "shipping" -> DestinationConfig(payload = Some(Json.obj(
      "customerId" -> customerId, "shippingType" -> shippingType, "totalAmount" -> totalAmount
    ))),
    "billing" -> DestinationConfig(payload = Some(Json.obj(
      "amount" -> totalAmount, "currency" -> "USD"
    )))
  )
}
```

The `OutboxHelper` trait wraps your business action and the event insert in `.transactionally`:

```scala
trait OutboxHelper {
  protected def withEventFactory[T](action: DBIO[T])(eventFactory: T => DomainEvent)
      (using ec: ExecutionContext): DBIO[T] =
    (for {
      result <- action
      _      <- saveEvent(eventFactory(result))
    } yield result).transactionally
}
```

The `OrderRepository` mixes this in:

```scala
def createWithEvent(order: Order): DBIO[Long] =
  withEventFactory((orders returning orders.map(_.id)) += order) { orderId =>
    OrderCreatedEvent(orderId, order.customerId, order.totalAmount, order.shippingType)
  }
```

At the SQL level, this produces:

```sql
BEGIN;
  INSERT INTO orders (...) RETURNING id;  -- Returns 123
  INSERT INTO outbox_events (aggregate_id, event_type, payloads, status)
    VALUES ('123', 'OrderCreated', '{"inventory": {...}, ...}', 'PENDING');
COMMIT;
-- PostgreSQL trigger fires: pg_notify('outbox_events_channel', '456')
```

---

## Processing Events: The OutboxProcessor

The `OutboxProcessor` uses `FOR UPDATE SKIP LOCKED` to claim events without overlap, making it safe to run multiple workers concurrently:

```scala
def findAndClaimUnprocessed(limit: Int = 100): DBIO[Seq[OutboxEvent]] = {
  sql"""
    WITH claimed AS (
      SELECT id FROM outbox_events
      WHERE status = 'PENDING'
        AND (next_retry_at IS NULL OR next_retry_at <= NOW())
      ORDER BY created_at
      LIMIT $limit
      FOR UPDATE SKIP LOCKED
    )
    UPDATE outbox_events e
    SET status = 'PROCESSING', status_changed_at = NOW()
    FROM claimed
    WHERE e.id = claimed.id
    RETURNING e.*
  """.as[OutboxEvent]
}
```

Failed calls retry with exponential backoff (2s, 4s, 8s). After max retries, the event moves to `dead_letter_events` for compensation.

Rather than polling on a fixed interval, a PostgreSQL trigger notifies the processor immediately when a new event is inserted:

```sql
CREATE OR REPLACE FUNCTION notify_new_outbox_event() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'PENDING' THEN
        PERFORM pg_notify('outbox_events_channel', NEW.id::text);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

---

## Fan-Out and Conditional Routing

Fan-out order and destination URLs are declared entirely in configuration — no recompile needed to add a destination or rewire the sequence:

```hocon
outbox.http.fanout {
  OrderCreated       = ["inventory", "fraudCheck", "shipping", "billing"]
  OrderStatusUpdated = ["notifications"]
}

outbox.http.routes {
  inventory {
    url    = "http://localhost:9000/api/inventory/reserve"
    method = "POST"
  }

  shipping {
    method = "POST"
    routes = [{
      url = "http://localhost:9000/api/domestic-shipping"
      condition { jsonPath = "$.shippingType", operator = "eq", value = "domestic" }
    }, {
      url = "http://localhost:9000/api/international-shipping"
      condition { jsonPath = "$.shippingType", operator = "eq", value = "international" }
    }]
  }
}
```

Conditions support `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `contains`, and `exists`.

### Decision chaining

Routing can depend on a *previous* destination's response. The fraud check runs before billing, so billing can route based on the risk score it returned:

```hocon
billing {
  method = "POST"
  routes = [{
    url = "http://localhost:9000/api/billing"
    condition {
      jsonPath = "$.riskScore"
      operator = "lt"
      value    = "50"
      previousDestination = "fraudCheck"
    }
  }, {
    url = "http://localhost:9000/api/high-value-processing"
    condition {
      jsonPath = "$.riskScore"
      operator = "gte"
      value    = "50"
      previousDestination = "fraudCheck"
    }
  }]
}
```

The publisher maintains a `RoutingContext` that accumulates responses as each destination is called, making them available to subsequent routing decisions.

---

## Configuration-Driven Revert Endpoints

Each destination can declare a `revert` block that tells the system how to undo the forward call. Values are extracted from the saved request or response using JSONPath, then substituted into the revert URL and payload:

```hocon
inventory {
  url    = "http://localhost:9000/api/inventory/reserve"
  method = "POST"

  revert {
    url    = "http://localhost:9000/api/inventory/{reservationId}/release"
    method = "DELETE"
    extract {
      reservationId = "response:$.reservationId"
    }
  }
}

shipping {
  routes = [{
    url = "http://localhost:9000/api/domestic-shipping"
    condition { jsonPath = "$.shippingType", operator = "eq", value = "domestic" }

    revert {
      url    = "http://localhost:9000/api/domestic-shipping/{shipmentId}/cancel"
      method = "POST"
      extract {
        shipmentId = "response:$.shipmentId"
        orderId    = "response:$.orderId"
        customerId = "request:$.customerId"
      }
      payload = """{"reason": "payment_failed", "shipmentId": "{shipmentId}",
                    "orderId": "{orderId}", "customerId": "{customerId}"}"""
    }
  }]
}
```

If the forward response was `{"shipmentId": "SHIP-789", "orderId": "123"}` and the request included `{"customerId": "C-123"}`, the revert call becomes:

```
POST /api/domestic-shipping/SHIP-789/cancel
{"reason": "payment_failed", "shipmentId": "SHIP-789", "orderId": "123", "customerId": "C-123"}
```

Destinations without a `revert` block (like `fraudCheck`) are skipped during compensation.

---

## Automatic Compensation (The DLQ Processor)

When the `OutboxProcessor` exhausts retries, it moves the failed event to `dead_letter_events`. The `DLQProcessor` (spawned as a child actor of `OutboxProcessor`) picks it up and compensates in LIFO order:

```scala
private def revertDLQEvent(dlqEvent: DeadLetterEvent): Future[Boolean] = {
  db.run(
    resultRepo.findByAggregateId(dlqEvent.aggregateId, Result.Success, includeReverts = false)
  ).flatMap { successful =>
    if (successful.isEmpty) {
      db.run(dlqRepo.markProcessed(dlqEvent.id)).map(_ => true)
    } else {
      publishRevertEvent(dlqEvent, successful)  // LIFO order
    }
  }
}
```

Every revert call is recorded in `aggregate_results` with a `.revert` suffix on the destination name (`"shipping.revert"`). Before compensating, the processor checks whether that entry already exists — if so, it skips. This makes compensation naturally idempotent across restarts.

---

## Manual Cancellation

User-initiated cancellations reuse the same compensation engine. A `!` prefix on the event type tells the `OutboxProcessor` to run in compensation mode rather than forward mode:

```scala
case class OrderCancelledEvent(orderId: Long, reason: String, ...) extends DomainEvent {
  override def eventType: String = "!OrderCreated"  // ← triggers compensation
}
```

The repository writes the cancellation and the `!OrderCreated` event atomically:

```scala
def cancelWithEvent(orderId: Long, reason: String): DBIO[Int] =
  for {
    orderOpt <- findById(orderId)
    _        <- orderOpt match {
      case Some(_) => DBIO.successful(())
      case None    => DBIO.failed(new NoSuchElementException(s"Order $orderId not found"))
    }
    updated  <- withEvent(OrderCancelledEvent(orderId = orderId, reason = reason)) {
      orders.filter(_.id === orderId)
        .map(o => (o.orderStatus, o.updatedAt))
        .update(("CANCELLED", Instant.now()))
    }
  } yield updated
```

If the user clicks Cancel twice, the second request finds all destinations already compensated and becomes a no-op. If a revert event itself fails after max retries, it does not trigger further compensation — that would create infinite loops — and requires manual intervention instead.

| Aspect          | Manual (`!OrderCreated`)    | Automatic (DLQ)                     |
|-----------------|-----------------------------|-------------------------------------|
| **Trigger**     | User action                 | Forward API fails after max retries |
| **Table**       | `outbox_events`             | `dead_letter_events`                |
| **Timing**      | Immediate                   | After retry exhaustion              |
| **If it fails** | Manual intervention         | Manual intervention                 |

---

## Configuration

```hocon
outbox {
  pollInterval = 2 seconds
  batchSize    = 100
  poolSize     = 3
  maxRetries   = 3
  useListenNotify = true

  enableStaleEventCleanup  = true
  staleEventTimeoutMinutes = 5
  cleanupInterval          = 1 minute

  dlq {
    maxRetries   = 3
    pollInterval = 2 seconds
  }
}
```

With `poolSize = 3`, three `OutboxProcessor` actors run concurrently behind a pool router, each with its own child `DLQProcessor`. `FOR UPDATE SKIP LOCKED` ensures they never process the same event.

---

## Complete Flow: Billing Failure Scenario

```
POST /api/orders {"customerId": "C-123", "totalAmount": 99.99, "shippingType": "domestic"}

1. Atomic write
   INSERT INTO orders ...         → id=123
   INSERT INTO outbox_events ...  → id=456, status=PENDING
   pg_notify fires immediately

2. OutboxProcessor claims event
   UPDATE outbox_events SET status='PROCESSING' WHERE id=456

3. Fan-out calls
   POST /api/inventory/reserve       → 200 OK {"reservationId": "RES-456"}
   POST /api/fraud/check             → 200 OK {"riskScore": 25}
   POST /api/domestic-shipping       → 200 OK {"shipmentId": "SHIP-789"}
   POST /api/billing                 → 503 (retried 3×, all fail)

4. Move to DLQ
   INSERT INTO dead_letter_events (aggregate_id='123', reason='MAX_RETRIES_EXCEEDED')
   UPDATE outbox_events SET status='PROCESSED', moved_to_dlq=true

5. DLQProcessor compensates (LIFO)
   POST /api/domestic-shipping/SHIP-789/cancel  → 200 OK  → saved as "shipping.revert"
   Skip fraudCheck (no revert config)
   DELETE /api/inventory/RES-456/release        → 200 OK  → saved as "inventory.revert"
   UPDATE dead_letter_events SET status='PROCESSED'
```

The order exists in the database. All external side effects have been cleanly undone. `aggregate_results` has a full audit trail of every call, forward and revert.

---

## Try It Yourself

```bash
# Force billing to fail every time
# In application.conf:
service.failure.rates {
  billing.charge = 1.0
}
```

Scenarios worth trying:

- **Happy path** — create an order, watch `aggregate_results` fill with successful calls
- **Automatic compensation** — set billing failure rate to 100%, watch the DLQ processor undo shipping and inventory in LIFO order
- **Manual cancellation** — create a successful order, then cancel it; triggers `!OrderCreated` and reverts all forward calls
- **Idempotency** — cancel the same order twice; the second request is a no-op

---

## What This Doesn't Solve

These patterns don't make distributed systems simple. Nothing does. You'll still need monitoring to catch stuck events, alerting when the DLQ starts filling up, and runbooks for the cases where automatic compensation fails and someone needs to step in manually.

What they *do* is change the nature of your failures. Without them, you get **data inconsistencies**: money charged with no order, inventory reserved for ghost orders, shipments scheduled for cancelled transactions — the kind of bugs that require manual database surgery at 3 AM.

With them, those inconsistencies become structurally impossible. The failures you'll encounter instead — a DLQ event stuck in retry, a revert endpoint timing out — are all **observable, traceable, and recoverable**. The `aggregate_results` table gives you a complete audit trail. The `dead_letter_events` table tells you exactly what failed and why.

---

## Tech Stack

- **Scala 3**
- **Play Framework** — HTTP layer and application lifecycle
- **Slick** — type-safe database access (`DBIO[T]` for composable transactions)
- **Apache Pekko** — actor-based background processing
- **PostgreSQL** — `FOR UPDATE SKIP LOCKED`, `LISTEN/NOTIFY`, JSONB, partial indexes
