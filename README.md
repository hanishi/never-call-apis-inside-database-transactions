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

## A Note on the Actor Model Usage

This project uses Pekko Typed actors for the `OutboxProcessor` and `DLQProcessor`. Before treating this as a reference for how to use actors in Play, it is worth being direct about a design problem.

**The `OutboxProcessor` does not need to be an actor.** Its responsibilities are polling a database, making HTTP calls, and writing results back. A simple scheduler with `Future` handles this cleanly. The actor adds nothing here: there is no meaningful state to encapsulate, no supervision hierarchy being leveraged, and no location transparency needed. The self-sent `ProcessUnhandledEvent` message is just a while loop in disguise.

The Play integration compounds this. Because Play exposes only `ActorSystem[Nothing]`, there is no access to the user guardian. The only option is `systemActorOf`, an API intended for infrastructure-level actors, not business logic. This is a structural workaround for Play's constraints, not idiomatic Pekko Typed usage.

This pattern — reaching for actors because the framework happens to include an `ActorSystem` — is widespread in Play codebases, and it consistently creates bottlenecks. Placing an actor in the request path and waiting on it with `ask` serializes work through a single mailbox that `Future`-based code would handle in parallel. Under load, that mailbox becomes the bottleneck.

**The right question before using an actor is always: does this problem actually require the Actor model?** Actors earn their place when you need to encapsulate long-lived mutable state, when you need location transparency across a cluster, or when the supervision hierarchy gives you something a try/catch cannot. A background worker that polls a database and calls HTTP endpoints does not clear that bar.

The Outbox, Result Table, and Saga Compensation patterns in this project are sound. The actor usage is not something to copy.

---
## Tech Stack

- **Scala 3**
- **Play Framework** — HTTP layer and application lifecycle
- **Slick** — type-safe database access (`DBIO[T]` for composable transactions)
- **Apache Pekko** — actor-based background processing
- **PostgreSQL** — `FOR UPDATE SKIP LOCKED`, `LISTEN/NOTIFY`, JSONB, partial indexes



# データベーストランザクション内でAPIを呼ぶな

> **要約:** データベーストランザクション内で外部APIを呼んではいけない。外部呼び出しが成功してDBのコミットが失敗した場合、ロールバックできない不整合な状態が残る。本プロジェクトは **Transactional Outbox + Result Table + Saga Compensation** の完全な動作実装を、Play Framework・Slick・Pekkoを使ったScalaで提供する。

---

## クイックスタート

```bash
git clone https://github.com/hanishi/never-call-apis-inside-database-transactions
cd never-call-apis-inside-database-transactions
docker-compose up -d postgres
sbt run
# http://localhost:9000 をブラウザで開く
```

プロジェクトには、失敗率を設定できるシミュレートされた外部サービス（在庫・請求・配送・不正チェック）、注文の作成や障害の再現ができるインタラクティブなUI、すべてのAPI呼び出しの完全な監査ログが含まれている。

---

## このプロジェクトについて

データベースに書き込みつつ外部APIも呼び出すアプリケーションは、気づいていなくても整合性の問題を抱えている。[Transactional Outboxパターン](https://microservices.io/patterns/data/transactional-outbox.html)と[Sagaパターン](https://microservices.io/patterns/data/saga.html)はよく知られた解決策だが、理論で終わっている解説がほとんどだ。このプロジェクトはそこで止まらない。

連携して機能する3つのパターンの**完全な動作実装**を提供する:

1. **Transactional Outbox** — アプリがクラッシュしてもすべてのイベントが処理されることを保証する
2. **Result Table** — すべてのAPI呼び出し、そのレスポンス、取り消しに必要なIDを記録する
3. **Saga Compensation** — 4ステップ中の4番目が失敗したとき、1〜3を正しい順序で自動的に取り消す

実装にはPlay Framework・Scala 3・PostgreSQL・Slick・Pekkoアクターを使用しているが、パターン自体は**言語やフレームワークに依存しない**。SlickをJPAに、PekkoをバックグラウンドジョブフレームワークにPlayを何に置き換えても構わない。データベーススキーマ、イベントのライフサイクル、LIFOによる補償ロジック、すべてそのまま移植できる。

---

## なぜこれが必要か

以下のいずれかに該当するシステムは、デュアルライト問題を抱えている:

- データベースへの書き込みと同時に、別システムの状態を変える外部APIを呼び出している（決済ゲートウェイへの課金、在庫の確保、配送のスケジュール登録、Kafkaへのパブリッシュなど）。信用スコアの取得のような読み取り専用の呼び出しはデュアルライト問題を引き起こさない。取り消すべき相手側の状態がないからだ。
- 単一のビジネスオペレーションで複数サービスを連携させている（例: 在庫確保 → 不正チェック → 配送手配 → 課金）
- 複数ステップの処理の途中で失敗したとき、完了済みの処理を取り消す必要がある

これらのパターンがなければ、いずれ次のような事態に直面する: 課金は成功したが注文がDBに存在しない、幽霊注文のために在庫が確保されたまま、キャンセルされた取引の配送がスケジュールされている。Outboxパターンはこうした不整合を、運に頼るのではなく設計によって排除する。

---

## 問題: なぜ壊れるのか

ECシステムを構築しているとしよう。顧客が注文を確定したとき、次の処理が必要だ:

1. 注文をデータベースに保存する
2. 在庫を確保する
3. 配送を手配する
4. 顧客に課金する

Slickには便利な抜け道がある。`DBIO.from()`は任意の`Future`を`DBIO`に持ち上げるため、`for`内包表記でデータベース操作と並べてチェーンできる。ScalaのHTTPライブラリは通常`Future[T]`を返すため、HTTPコールをラップして`.transactionally`ブロック内に含めたくなる。コードはコンパイルでき、型も合い、すべてトランザクションで保護されているように*見える*:

```scala
db.run {
  (for {
    orderId <- orders += order                              // DB書き込み
    _       <- DBIO.from(inventoryApi.reserve(orderId))     // DBIOにラップしたHTTPコール
    _       <- DBIO.from(shippingApi.schedule(orderId))     // DBIOにラップしたHTTPコール
    _       <- DBIO.from(billingApi.charge(orderId))        // DBIOにラップしたHTTPコール
    _       <- shipments += Shipment(orderId, shippingRes)  // DB書き込み
  } yield orderId).transactionally
}
```

しかし実際はそうではない。データベーストランザクションが制御できるのは*データベース*操作だけだ。`DBIO.from()`内のHTTPコールはトランザクションの観点ではfire-and-forgetで、呼び出された瞬間に副作用が確定し、データベースにはそれを取り消す手段がない。最後のDB書き込みが失敗したとき何が起きるか:

1. 注文をINSERT → 成功
2. 在庫APIを呼び出す → 200 OK（在庫確保済み）
3. 配送APIを呼び出す → 200 OK（配送手配済み）
4. 課金APIを呼び出す → 200 OK（顧客への課金完了）
5. 配送レコードをINSERT → **失敗**（デッドロック、ネットワークエラー、原因は何でもいい）
6. トランザクションがロールバック

**結果:** 顧客は課金され、配送は手配され、在庫は確保されている……が、データベースに注文は存在しない。外部APIはトランザクションが失敗したことを知らない。すでに処理を完了してしまっている。

呼び出しを分離しても解決しない:

```scala
// ステップ1: データベースに保存
val orderId = db.run(orders += order).transactionally  // ✅ 成功

// ステップ2: 外部サービスを呼び出す
inventoryApi.reserve(orderId)   // ✅ 成功
shippingApi.schedule(orderId)   // ❌ ここでアプリがクラッシュ。配送は呼ばれない
billingApi.charge(orderId)      // ❌ 実行されない
```

ステップ1とステップ2の間でアプリがクラッシュすれば、注文は保存されているが他は何も起きない。`orderId`はメモリから失われているため、リトライは二度と実行されない。

**根本的な問題:** 分散システムをまたぐトランザクションは存在しない。データベースは自身の行に対して原子性を保証できるが、HTTP API・Kafkaブローカー・サードパーティ決済ゲートウェイには何の権限も持たない。各システムは独立してコミットし、独立して失敗し、他のシステムが何をしているか知る術がない。

---

## 解決策: Transactional Outbox + Result Table + Saga Compensation

修正はシンプルだ: **リクエスト中に外部APIを呼ぶのをやめる。代わりに、必要なことをデータベースに記録し、バックグラウンドワーカーに後で呼ばせる。**

外部APIを直接呼ぶ代わりに、注文と同じデータベーストランザクション内で`outbox_events`テーブルに1行INSERTする:

```sql
BEGIN;
  INSERT INTO orders (customer_id, total_amount, ...) VALUES ('C-123', 99.99, ...);
  INSERT INTO outbox_events (event_type, payloads, status) VALUES ('OrderCreated', '{...}', 'PENDING');
COMMIT;
```

両方の行が保存されるか、どちらも保存されないかのどちらかだ。このトランザクション中にAPIコールは一切行われない。

バックグラウンドのPekkoアクター（`OutboxProcessor`）がこのテーブルを監視し続ける。`PENDING`なイベントを見つけたら、それを取得して実際のHTTPコールを行い、`PROCESSED`としてマークする。ワーカーが処理する前にアプリがクラッシュしても、イベントはデータベースに残っている。再起動時にワーカーが拾い上げる。

| パターン                     | 解決する問題          | アプローチ                                     |
|------------------------------|-----------------------|------------------------------------------------|
| **Transactional Outbox**     | デュアルライト問題    | 注文とイベントを同一トランザクションでDB書き込み |
| **Result Table**             | 「何を取り消すか」    | すべてのAPI呼び出しとレスポンスを記録           |
| **Saga Compensation**        | 部分的な失敗          | 成功した呼び出しをLIFO順に取り消す             |

---

## データベーススキーマ

### `orders` — ビジネスデータ

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

### `outbox_events` — TODOリスト

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

### `aggregate_results` — 監査ログ

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

### `dead_letter_events` — 補償待ちの失敗イベント

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

## イベントのアトミックな書き込み

各ビジネスオペレーションはドメインイベントを生成し、そのイベントは宛先とそれぞれに必要なデータを知っている:

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

`OutboxHelper`トレイトがビジネスアクションとイベントのINSERTを`.transactionally`でラップする:

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

`OrderRepository`はこれをミックスインする:

```scala
def createWithEvent(order: Order): DBIO[Long] =
  withEventFactory((orders returning orders.map(_.id)) += order) { orderId =>
    OrderCreatedEvent(orderId, order.customerId, order.totalAmount, order.shippingType)
  }
```

SQLレベルでは次の処理が行われる:

```sql
BEGIN;
  INSERT INTO orders (...) RETURNING id;  -- 123が返る
  INSERT INTO outbox_events (aggregate_id, event_type, payloads, status)
    VALUES ('123', 'OrderCreated', '{"inventory": {...}, ...}', 'PENDING');
COMMIT;
-- PostgreSQLトリガーが発火: pg_notify('outbox_events_channel', '456')
```

---

## イベントの処理: OutboxProcessor

`OutboxProcessor`は`FOR UPDATE SKIP LOCKED`を使ってイベントを取得する。これにより、複数のワーカーを並列で動かしても同じイベントを二重処理しない:

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

失敗した呼び出しは指数バックオフ（2秒・4秒・8秒）でリトライされる。最大リトライ回数を超えると、イベントは補償処理のために`dead_letter_events`に移動する。

固定間隔のポーリングではなく、PostgreSQLトリガーが新しいイベントのINSERT直後にプロセッサへ通知する:

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

## ファンアウトと条件付きルーティング

ファンアウトの順序と宛先URLは設定ファイルで完全に定義される。宛先の追加やシーケンスの変更にリコンパイルは不要だ:

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

条件で使えるオペレーターは `eq`・`ne`・`gt`・`gte`・`lt`・`lte`・`contains`・`exists`。

### デシジョンチェーン

ルーティングを*前の*宛先のレスポンスに基づいて行うこともできる。不正チェックは請求より先に実行されるため、請求はそのリスクスコアに基づいてルーティングできる:

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

パブリッシャーは各宛先の呼び出し後にレスポンスを蓄積する`RoutingContext`を保持し、後続のルーティング判断で参照できるようにしている。

---

## 設定ドリブンなリバートエンドポイント

各宛先は、フォワード呼び出しの取り消し方法を宣言する`revert`ブロックを持てる。値はJSONPathを使って保存済みのリクエストまたはレスポンスから抽出され、リバートURLとペイロードのプレースホルダーに代入される:

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

フォワード呼び出しのレスポンスが`{"shipmentId": "SHIP-789", "orderId": "123"}`でリクエストに`{"customerId": "C-123"}`が含まれていれば、リバート呼び出しは次のようになる:

```
POST /api/domestic-shipping/SHIP-789/cancel
{"reason": "payment_failed", "shipmentId": "SHIP-789", "orderId": "123", "customerId": "C-123"}
```

`revert`ブロックを持たない宛先（`fraudCheck`など）は補償処理でスキップされる。

---

## 自動補償: DLQProcessor

`OutboxProcessor`がリトライを使い果たすと、失敗したイベントを`dead_letter_events`に移動する。`DLQProcessor`（`OutboxProcessor`の子アクターとしてスポーン）がそれを取得し、LIFO順で補償を実行する:

```scala
private def revertDLQEvent(dlqEvent: DeadLetterEvent): Future[Boolean] = {
  db.run(
    resultRepo.findByAggregateId(dlqEvent.aggregateId, Result.Success, includeReverts = false)
  ).flatMap { successful =>
    if (successful.isEmpty) {
      db.run(dlqRepo.markProcessed(dlqEvent.id)).map(_ => true)
    } else {
      publishRevertEvent(dlqEvent, successful)  // LIFO順
    }
  }
}
```

すべてのリバート呼び出しは`aggregate_results`に、宛先名に`.revert`サフィックスを付けて記録される（`"shipping.revert"`など）。補償前にそのエントリが既に存在するか確認し、存在すればスキップする。これにより再起動をまたいでも補償処理が冪等になる。

---

## 手動キャンセル

ユーザーが起点のキャンセルも同じ補償エンジンを再利用する。イベントタイプへの`!`プレフィックスが、`OutboxProcessor`にフォワードモードではなく補償モードで動作するよう伝える:

```scala
case class OrderCancelledEvent(orderId: Long, reason: String, ...) extends DomainEvent {
  override def eventType: String = "!OrderCreated"  // ← 補償をトリガー
}
```

リポジトリはキャンセルと`!OrderCreated`イベントをアトミックに書き込む:

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

ユーザーがキャンセルを2回クリックしても、2回目のリクエストはすべての宛先が既に補償済みであることを確認してno-opになる。リバートイベント自体が最大リトライ後に失敗した場合、さらなる補償はトリガーされない。無限ループになるからだ。代わりに手動対応が必要な状態としてマークされる。

| 観点               | 手動（`!OrderCreated`）     | 自動（DLQ）                          |
|--------------------|-----------------------------|--------------------------------------|
| **トリガー**       | ユーザーアクション          | フォワードAPIが最大リトライ後に失敗  |
| **テーブル**       | `outbox_events`             | `dead_letter_events`                 |
| **タイミング**     | 即時                        | リトライ枯渇後                       |
| **それ自体が失敗** | 手動対応が必要              | 手動対応が必要                       |

---

## 設定

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

`poolSize = 3`の場合、3つの`OutboxProcessor`アクターがプールルーターの後ろで並列動作し、それぞれが子`DLQProcessor`を持つ。`FOR UPDATE SKIP LOCKED`により、同じイベントが二重処理されることはない。

---

## 完全なフロー: 課金失敗シナリオ

```
POST /api/orders {"customerId": "C-123", "totalAmount": 99.99, "shippingType": "domestic"}

1. アトミックな書き込み
   INSERT INTO orders ...         → id=123
   INSERT INTO outbox_events ...  → id=456, status=PENDING
   pg_notifyが即座に発火

2. OutboxProcessorがイベントを取得
   UPDATE outbox_events SET status='PROCESSING' WHERE id=456

3. ファンアウト呼び出し
   POST /api/inventory/reserve       → 200 OK {"reservationId": "RES-456"}
   POST /api/fraud/check             → 200 OK {"riskScore": 25}
   POST /api/domestic-shipping       → 200 OK {"shipmentId": "SHIP-789"}
   POST /api/billing                 → 503（3回リトライ、すべて失敗）

4. DLQへ移動
   INSERT INTO dead_letter_events (aggregate_id='123', reason='MAX_RETRIES_EXCEEDED')
   UPDATE outbox_events SET status='PROCESSED', moved_to_dlq=true

5. DLQProcessorがLIFO順で補償
   POST /api/domestic-shipping/SHIP-789/cancel  → 200 OK  → "shipping.revert"として保存
   fraudCheckをスキップ（revert設定なし）
   DELETE /api/inventory/RES-456/release        → 200 OK  → "inventory.revert"として保存
   UPDATE dead_letter_events SET status='PROCESSED'
```

注文はデータベースに存在する。外部への副作用はすべてクリーンに取り消された。`aggregate_results`にはフォワードとリバートを含むすべてのAPI呼び出しの完全な監査ログが残っている。

---

## 試してみる

```bash
# 課金を常に失敗させる
# application.confで設定:
service.failure.rates {
  billing.charge = 1.0
}
```

試す価値があるシナリオ:

- **ハッピーパス** — 注文を作成し、`aggregate_results`に成功した呼び出しが記録されるのを確認する
- **自動補償** — 課金の失敗率を100%に設定して注文を作成し、DLQProcessorが配送と在庫をLIFO順に取り消すのを観察する
- **手動キャンセル** — 正常に完了した注文をキャンセルする。`!OrderCreated`がトリガーされ、すべてのフォワード呼び出しが取り消される
- **冪等性** — 同じ注文を2回キャンセルする。2回目はno-opになる

---

## 解決しないこと

これらのパターンは分散システムをシンプルにはしない。何もそれはできない。スタックしたイベントを検知するモニタリング、DLQが溜まり始めたときのアラート、自動補償が失敗して人間の介入が必要になるまれなケースへの対応手順は引き続き必要だ。

これらのパターンが変えるのは、障害の*性質*だ。パターンがなければ**データの不整合**が発生する: 課金されたのに注文がない、幽霊注文のために在庫が確保されたまま、キャンセルされた取引の配送が手配されている。こういったバグは深夜3時に叩き起こされ、手作業でのDB修復を要求する。

パターンがあれば、そうした不整合は構造的に起こりえなくなる。代わりに直面する障害——リトライ待ちのDLQイベント、タイムアウトするリバートエンドポイント——はすべて**観測可能で、追跡可能で、回復可能**だ。`aggregate_results`テーブルがすべてのAPI呼び出しの完全な監査ログを提供する。`dead_letter_events`テーブルが何がなぜ失敗したかを正確に教えてくれる。

---

## Actorモデルの使い方についての注記

このプロジェクトは`OutboxProcessor`と`DLQProcessor`にPekko Typedアクターを使っている。これをPlayでのActorの使い方の参考にする前に、設計上の問題点を率直に書いておく。

**`OutboxProcessor`はActorである必要がない。** その責務はDBのポーリング、HTTPコールの実行、結果の書き戻しだ。これはスケジューラーと`Future`の組み合わせで十分に実現できる。Actorにすることで得られるものは何もない。カプセル化すべき意味のある状態はなく、supervision階層も活用されておらず、場所透過性も不要だ。自分自身への`ProcessUnhandledEvent`メッセージ送信はwhileループを迂回して表現しているに過ぎない。

PlayのActorSystem統合がこれをさらに悪化させている。PlayはDIとして`ActorSystem[Nothing]`しか公開しないため、ユーザーガーディアンへのアクセス手段がない。使える選択肢は`systemActorOf`だけだが、これはビジネスロジックではなくインフラレベルのアクター向けのAPIだ。これはidiomatic Pekko Typedの使い方ではなく、Playの構造的制約に対する回避策だ。

このパターン——フレームワークにたまたま`ActorSystem`が含まれているからActorを使う——はPlayのコードベースに蔓延しており、いたるところにボトルネックを生んでいる。リクエストパスにActorを挟んで`ask`で結果を待つと、`Future`ベースのコードなら並列に処理できる仕事を単一のメールボックスが直列化してしまう。負荷が上がったとき、そのメールボックスがボトルネックになる。

**Actorを使う前に問うべき正しい問いは「この問題はActorモデルを本当に必要としているか」だ。** Actorが価値を発揮するのは、長期間保持される可変状態のカプセル化が必要なとき、クラスタをまたいだ場所透過性が必要なとき、supervision階層がtry/catchでは実現できない障害管理を提供するときだ。DBをポーリングしてHTTPを呼ぶバックグラウンドワーカーはその条件を満たさない。

このプロジェクトのOutbox・Result Table・Saga Compensationパターンは正しい。Actorの使い方はコピーすべきではない。

---

## 技術スタック

- **Scala 3**
- **Play Framework** — HTTPレイヤーとアプリケーションライフサイクル
- **Slick** — 型安全なデータベースアクセス（`DBIO[T]`によるトランザクション合成）
- **Apache Pekko** — アクターベースのバックグラウンド処理
- **PostgreSQL** — `FOR UPDATE SKIP LOCKED`・`LISTEN/NOTIFY`・JSONB・部分インデックス
