package repositories

import com.google.inject.{ Inject, Singleton }
import models.AggregateResult
import play.api.libs.json.*
import repositories.OutboxEventsPostgresProfile.api.*

import java.time.Instant
import scala.concurrent.ExecutionContext

enum Result:
  case Success, Failed, All

class AggregateResultTable(tag: Tag) extends Table[AggregateResult](tag, "aggregate_results") {
  import OutboxEventsPostgresProfile.OutboxEvents.playJsonTypeMapper

  def * = (
    id,
    aggregateId,
    eventType,
    destination,
    endpointUrl,
    httpMethod,
    requestPayload,
    responseStatus,
    responsePayload,
    publishedAt,
    durationMs,
    success,
    errorMessage,
    fanoutOrder
  ).mapTo[AggregateResult]

  def id              = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def aggregateId     = column[String]("aggregate_id")
  def eventType       = column[String]("event_type")
  def destination     = column[String]("destination")
  def endpointUrl     = column[String]("endpoint_url")
  def httpMethod      = column[String]("http_method")
  def requestPayload  = column[Option[JsValue]]("request_payload")
  def responseStatus  = column[Option[Int]]("response_status")
  def responsePayload = column[Option[JsValue]]("response_payload")
  def publishedAt     = column[Instant]("published_at")
  def durationMs      = column[Option[Int]]("duration_ms")
  def success         = column[Boolean]("success")
  def errorMessage    = column[Option[String]]("error_message")
  def fanoutOrder     = column[Int]("fanout_order")
}

/** Repository for tracking API call results in the aggregate_results table.
  *
  * == Purpose ==
  * Maintains a complete audit log of all HTTP calls to external APIs, supporting:
  * 1. '''Saga Compensation''': Queries successful forward calls to build revert operations
  * 2. '''Audit Trail''': Complete history of all API interactions with full request/response data
  * 3. '''Idempotency''': Prevents duplicate compensation via uniqueness checks
  * 4. '''LIFO Ordering''': fanout_order field enables correct reverse order for compensation
  *
  * == Table Structure ==
  * {{{
  * CREATE TABLE aggregate_results (
  *   id BIGSERIAL PRIMARY KEY,
  *   aggregate_id VARCHAR NOT NULL,           -- e.g., "123" (order ID)
  *   event_type VARCHAR NOT NULL,             -- e.g., "OrderCreated", "!OrderCreated"
  *   destination VARCHAR NOT NULL,            -- e.g., "billing", "billing.revert"
  *   endpoint_url VARCHAR NOT NULL,           -- e.g., "http://localhost:9000/api/billing"
  *   http_method VARCHAR NOT NULL,            -- e.g., "POST", "DELETE"
  *   request_payload JSONB,                   -- Forward request data
  *   response_status INT,                     -- HTTP status code
  *   response_payload JSONB,                  -- Forward response data (contains IDs for revert)
  *   published_at TIMESTAMP NOT NULL,         -- When API was called
  *   duration_ms INT,                         -- API call duration
  *   success BOOLEAN NOT NULL,                -- Whether call succeeded
  *   error_message TEXT,                      -- Error details if failed
  *   fanout_order INT NOT NULL,               -- Position in fanout sequence (0, 1, 2, 3)
  *   UNIQUE (aggregate_id, destination, event_type)
  * );
  * }}}
  *
  * == Example Data Flow ==
  *
  * '''Forward Event (OrderCreated):'''
  * {{{
  * # Fanout: inventory → fraudCheck → shipping → billing
  * INSERT aggregate_results VALUES (
  *   aggregate_id='123', event_type='OrderCreated', destination='inventory',
  *   endpoint_url='http://localhost:9000/api/inventory/reserve',
  *   request_payload='{"orderId": 123, "totalAmount": 99.99}',
  *   response_payload='{"reservationId": "RES-456"}',
  *   fanout_order=0, success=true
  * );
  * INSERT ... destination='fraudCheck', fanout_order=1 ...
  * INSERT ... destination='shipping', fanout_order=2 ...
  * INSERT ... destination='billing', fanout_order=3, success=false ...  # FAILS
  * }}}
  *
  * '''Revert Event (!OrderCreated - automatic compensation):'''
  * {{{
  * # DLQProcessor queries: SELECT * WHERE aggregate_id='123' AND success=true ORDER BY fanout_order DESC
  * # Returns: [shipping(2), fraudCheck(1), inventory(0)]  -- LIFO order
  * # For each result, calls findSuccessfulForward to get forward data, then builds revert endpoint
  *
  * # Revert results saved:
  * INSERT aggregate_results VALUES (
  *   aggregate_id='123', event_type='!OrderCreated', destination='shipping.revert',
  *   endpoint_url='http://localhost:9000/api/domestic-shipping/SHIP-123/cancel',
  *   request_payload='{"reason": "payment_failed", "shipmentId": "SHIP-123"}',
  *   fanout_order=0, success=true
  * );
  * INSERT ... destination='inventory.revert', fanout_order=1 ...
  * # fraudCheck skipped (no revert config, read-only)
  * }}}
  *
  * == Key Queries ==
  *
  * - '''findByAggregateId''': Get all results for an order (for UI display)
  * - '''findSuccessfulForward''': Get forward result to build revert (for Saga compensation)
  * - '''findSuccessfulRevert''': Check if already compensated (for idempotency)
  * - '''upsert''': Save new result or update existing (unique constraint on aggregate+destination+event)
  */
@Singleton
class DestinationResultRepository @Inject() ()(using ec: ExecutionContext) {
  private val results = TableQuery[AggregateResultTable]

  /** Upserts (insert or update) an aggregate result.
    *
    * Uses PostgreSQL ON CONFLICT to ensure only one result per (aggregate_id, destination, event_type).
    * If a result already exists, it will be updated with the latest data.
    *
    * == Unique Constraint Behavior ==
    * {{{
    * # First call
    * upsert(AggregateResult(
    *   aggregateId = "123",
    *   destination = "billing",
    *   eventType = "OrderCreated",
    *   success = false,
    *   errorMessage = Some("Connection timeout")
    * ))
    * # → INSERT new row
    *
    * # Retry after fixing the issue
    * upsert(AggregateResult(
    *   aggregateId = "123",
    *   destination = "billing",
    *   eventType = "OrderCreated",
    *   success = true,
    *   responsePayload = Some({"transaction": {"id": "tx-456"}})
    * ))
    * # → UPDATE existing row (same aggregate+destination+event)
    * # Only latest result is kept
    * }}}
    *
    * == Why This Matters ==
    * - Prevents duplicate audit entries when retrying
    * - Ensures compensation queries find the most recent forward result
    * - Maintains clean audit trail (one entry per destination per event)
    *
    * @param result The aggregate result to upsert
    * @return DBIO action that returns number of affected rows (1)
    */
  def upsert(result: AggregateResult): DBIO[Int] = {

    val requestJson        = result.requestPayload.map(_.toString).getOrElse("null")
    val responseJson       = result.responsePayload.map(_.toString).getOrElse("null")
    val publishedTimestamp = java.sql.Timestamp.from(result.publishedAt)

    val upsertQuery = sqlu"""
      INSERT INTO aggregate_results (
        aggregate_id, event_type, destination, endpoint_url, http_method,
        request_payload, response_status, response_payload,
        published_at, duration_ms, success, error_message, fanout_order
      ) VALUES (
        ${result.aggregateId}, ${result.eventType}, ${result.destination}, ${result.endpointUrl},
        ${result.httpMethod}, $requestJson::jsonb, ${result.responseStatus},
        $responseJson::jsonb, $publishedTimestamp, ${result.durationMs},
        ${result.success}, ${result.errorMessage}, ${result.fanoutOrder}
      )
      ON CONFLICT (aggregate_id, destination, event_type)
      DO UPDATE SET
        endpoint_url = EXCLUDED.endpoint_url,
        http_method = EXCLUDED.http_method,
        request_payload = EXCLUDED.request_payload,
        response_status = EXCLUDED.response_status,
        response_payload = EXCLUDED.response_payload,
        published_at = EXCLUDED.published_at,
        duration_ms = EXCLUDED.duration_ms,
        success = EXCLUDED.success,
        error_message = EXCLUDED.error_message,
        fanout_order = EXCLUDED.fanout_order
    """
    upsertQuery
  }

  /** Finds all results for a specific aggregate.
    *
    * Returns results in reverse chronological order (newest first) for proper LIFO revert order.
    * This ensures compensating transactions execute in reverse: if the flow is A→B→C,
    * revert happens as C→B→A (Saga pattern).
    *
    * == Usage Examples ==
    *
    * '''1. UI Display (show all results):'''
    * {{{
    * findByAggregateId("123", Result.All, includeReverts = true)
    * # Returns all forward and revert results for order 123
    * # Example output:
    * [
    *   AggregateResult(destination="inventory.revert", eventType="!OrderCreated", success=true),
    *   AggregateResult(destination="shipping.revert", eventType="!OrderCreated", success=true),
    *   AggregateResult(destination="billing", eventType="OrderCreated", success=false),
    *   AggregateResult(destination="shipping", eventType="OrderCreated", success=true),
    *   AggregateResult(destination="fraudCheck", eventType="OrderCreated", success=true),
    *   AggregateResult(destination="inventory", eventType="OrderCreated", success=true)
    * ]
    * # Sorted by publishedAt DESC
    * }}}
    *
    * '''2. DLQ Processing (forward results only, for LIFO compensation):'''
    * {{{
    * findByAggregateId("123", Result.Success, includeReverts = false)
    * # Returns only successful forward calls (excludes revert events)
    * # Used by DLQProcessor to determine which operations to undo
    * # Example output (in fanout_order DESC):
    * [
    *   AggregateResult(destination="shipping", fanout_order=2),
    *   AggregateResult(destination="fraudCheck", fanout_order=1),
    *   AggregateResult(destination="inventory", fanout_order=0)
    * ]
    * # billing excluded (failed), fraudCheck will be skipped (no revert config)
    * }}}
    *
    * '''3. Failure Analysis:'''
    * {{{
    * findByAggregateId("123", Result.Failed, includeReverts = true)
    * # Returns only failed results (both forward and revert failures)
    * # Example output:
    * [
    *   AggregateResult(destination="billing", success=false, errorMessage="Connection timeout"),
    *   AggregateResult(destination="shipping.revert", success=false, errorMessage="Already cancelled")
    * ]
    * }}}
    *
    * @param aggregateId The aggregate ID to filter by (e.g., "123" for order 123)
    * @param filter Filter for success status: Success (only successful), Failed (only failed), or All
    * @param includeReverts Whether to include revert events (true for UI display, false for DLQ processing)
    * @return Sequence of results sorted by publishedAt DESC (newest first)
    */
  def findByAggregateId(
      aggregateId: String,
      filter: Result          = Result.All,
      includeReverts: Boolean = true
  ): DBIO[Seq[AggregateResult]] = {
    val baseQuery = results.filter(_.aggregateId === aggregateId)

    // For DLQ revert processing, exclude revert events to only see forward flow results
    // For UI display, include both forward and revert events
    val revertFilteredQuery =
      if (includeReverts) baseQuery else baseQuery.filterNot(_.eventType like "%:REVERT")

    val filteredQuery = filter match {
      case Result.Success => revertFilteredQuery.filter(_.success === true)
      case Result.Failed => revertFilteredQuery.filter(_.success === false)
      case Result.All => revertFilteredQuery
    }
    filteredQuery.sortBy(_.publishedAt.desc).result
  }

  /** Finds the most recent successful forward result for a specific aggregate and destination.
    *
    * Critical for Saga compensation: queries the successful forward call to extract IDs and values
    * needed to build the revert endpoint. This is the first step in the compensation flow.
    *
    * == Usage in Compensation Flow ==
    * {{{
    * # Step 1: DLQProcessor processes Revert(billing)
    * # Calls: findSuccessfulForward("123", "billing")
    *
    * # Query:
    * SELECT * FROM aggregate_results
    * WHERE aggregate_id = '123'
    *   AND destination = 'billing'
    *   AND success = true
    * ORDER BY published_at DESC
    * LIMIT 1
    *
    * # Returns (if found):
    * AggregateResult(
    *   aggregateId = "123",
    *   destination = "billing",
    *   eventType = "OrderCreated",
    *   endpointUrl = "http://localhost:9000/api/billing",
    *   requestPayload = {"amount": 99.99, "currency": "USD"},
    *   responsePayload = {"transaction": {"id": "tx-456"}},
    *   success = true
    * )
    *
    * # Step 2: RevertEndpointBuilder uses this result to extract values
    * # extract { transactionId = "response:$.transaction.id" }
    * # → "tx-456"
    *
    * # Step 3: Build revert endpoint
    * # Template: "http://localhost:9000/api/billing/{transactionId}/refund"
    * # → "http://localhost:9000/api/billing/tx-456/refund"
    *
    * # Step 4: Call revert API
    * # POST http://localhost:9000/api/billing/tx-456/refund
    * }}}
    *
    * == Why publishedAt DESC ==
    * Handles edge case where the same destination was called multiple times (rare but possible).
    * Most recent successful call is the one to revert.
    *
    * @param aggregateId The aggregate ID (e.g., "123" for order 123)
    * @param destination The base destination name (e.g., "billing", NOT "billing.revert")
    * @return The most recent successful forward result, or None if not found
    */
  def findSuccessfulForward(
      aggregateId: String,
      destination: String
  ): DBIO[Option[AggregateResult]] =
    results
      .filter(r =>
        r.aggregateId === aggregateId &&
          r.destination === destination &&
          r.success === true
      )
      .sortBy(_.publishedAt.desc)
      .take(1)
      .result
      .headOption

  /** Finds if a revert has already been performed for a specific aggregate and destination.
    *
    * Idempotency check to prevent duplicate compensation. Called before executing a revert operation
    * to ensure we don't undo the same operation twice.
    *
    * == Usage in Compensation Flow ==
    * {{{
    * # Before reverting inventory, check if already done
    * findSuccessfulRevert("123", "inventory")
    *
    * # Query:
    * SELECT * FROM aggregate_results
    * WHERE aggregate_id = '123'
    *   AND destination = 'inventory.revert'
    *   AND success = true
    * ORDER BY published_at DESC
    * LIMIT 1
    *
    * # Case 1: Already compensated
    * # Returns: Some(AggregateResult(destination="inventory.revert", success=true, publishedAt=...))
    * # Action: Skip revert, log "already compensated at {publishedAt}"
    *
    * # Case 2: Not yet compensated
    * # Returns: None
    * # Action: Proceed with revert operation
    * }}}
    *
    * == Real-World Scenario ==
    * {{{
    * # Timeline:
    * T1: Order 123 created, inventory reserved
    * T2: Billing fails, DLQ triggers compensation
    * T3: Inventory revert succeeds, saved as "inventory.revert"
    * T4: DLQ processor restarts (crash recovery)
    * T5: Processes same order again, calls findSuccessfulRevert("123", "inventory")
    * T6: Finds existing revert from T3, skips duplicate compensation
    *
    * # Prevents:
    * - Releasing the same inventory twice
    * - Refunding the same transaction twice
    * - Cancelling the same shipment twice
    * }}}
    *
    * @param aggregateId The aggregate ID (e.g., "123" for order 123)
    * @param baseDestination The base destination name (e.g., "billing", NOT "billing.revert")
    * @return The revert result if already compensated, or None if not yet compensated
    */
  def findSuccessfulRevert(
      aggregateId: String,
      baseDestination: String
  ): DBIO[Option[AggregateResult]] =
    results
      .filter(r =>
        r.aggregateId === aggregateId &&
          r.destination === s"$baseDestination.revert" &&
          r.success === true
      )
      .sortBy(_.publishedAt.desc)
      .take(1)
      .result
      .headOption

  /** Finds all successful forward results for a specific aggregate and event type.
    *
    * Used during retry to identify destinations that already succeeded, so they can be skipped.
    * The RoutingContext is reconstructed from saved response_payloads to support conditional routing
    * even when prior destinations are skipped.
    *
    * @param aggregateId The aggregate ID (e.g., "123" for order 123)
    * @param eventType   The event type to filter by (e.g., "OrderCreated")
    * @return Successful forward results ordered by fanout_order
    */
  def findSuccessfulForwardsByEventType(
      aggregateId: String,
      eventType: String
  ): DBIO[Seq[AggregateResult]] =
    results
      .filter(r =>
        r.aggregateId === aggregateId &&
          r.eventType === eventType &&
          r.success === true
      )
      .sortBy(_.fanoutOrder)
      .result
}
