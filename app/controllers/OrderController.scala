package controllers

import models.Order
import play.api.libs.json.*
import play.api.mvc.*
import repositories.{ DeadLetterRepository, OutboxRepository }
import services.OrderService
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import javax.inject.*
import scala.concurrent.ExecutionContext

@Singleton
class OrderController @Inject() (
    cc: ControllerComponents,
    orderService: OrderService,
    outboxRepo: OutboxRepository,
    dlqRepo: DeadLetterRepository,
    db: Database // used with JSON API endpoints
)(using ec: ExecutionContext)
    extends AbstractController(cc) {

  def createOrder: Action[AnyContent] = Action.async { request =>
    val customerId = request.body.asFormUrlEncoded
      .flatMap(_.get("customerId").flatMap(_.headOption))
      .getOrElse("customer-123")
    val totalAmount = request.body.asFormUrlEncoded
      .flatMap(_.get("totalAmount").flatMap(_.headOption))
      .flatMap(s => scala.util.Try(BigDecimal(s)).toOption)
      .getOrElse(BigDecimal(99.99))
    val shippingType = request.body.asFormUrlEncoded
      .flatMap(_.get("shippingType").flatMap(_.headOption))
      .getOrElse("domestic")

    val order = Order(
      customerId   = customerId,
      totalAmount  = totalAmount,
      shippingType = shippingType,
      orderStatus  = "PENDING",
      createdAt    = Instant.now(),
      updatedAt    = Instant.now()
    )

    orderService
      .createOrder(order)
      .map { orderId =>
        Ok(
          s"""<div class="success">Order #$orderId created successfully! Event queued in outbox.</div>"""
        )
          .as("text/html")
      }
      .recover { case ex =>
        InternalServerError(s"""<div class="error">Error: ${ex.getMessage}</div>""")
          .as("text/html")
      }
  }

  def updateStatus(id: Long): Action[AnyContent] = Action.async { request =>
    val status = request.body.asFormUrlEncoded
      .flatMap(_.get("status").flatMap(_.headOption))
      .getOrElse("PROCESSING")

    orderService
      .updateOrderStatus(id, status)
      .flatMap { _ =>
        listOrders(request)
      }
      .recover {
        case _: NoSuchElementException =>
          Ok(s"""<div class="error">Order $id not found</div>""").as("text/html")
        case ex =>
          Ok(s"""<div class="error">Error: ${ex.getMessage}</div>""").as("text/html")
      }
  }

  def cancelOrder(id: Long): Action[AnyContent] = Action.async { request =>
    orderService
      .cancelOrder(id, "User requested via UI")
      .flatMap { _ =>
        listOrders.apply(request)
      }
      .recover {
        case _: NoSuchElementException =>
          Ok(s"""<div class="error">Order $id not found</div>""").as("text/html")
        case ex =>
          Ok(s"""<div class="error">Error: ${ex.getMessage}</div>""").as("text/html")
      }
  }

  // Pipeline visualization helpers
  private val fanoutDests    = Seq("inventory", "fraudCheck", "shipping", "billing")
  private val noRevertDests  = Set("fraudCheck")
  private val destIcons = Map(
    "inventory"  -> "🏭",
    "fraudCheck" -> "🔍",
    "shipping"   -> "🚚",
    "billing"    -> "💳"
  )

  private def pipelineNode(
      icon: String,
      label: String,
      state: String,
      sub: String,
      tooltip: Option[String] = None,
      style: String = ""
  ): String = {
    val styleAttr   = if (style.nonEmpty) s""" style="$style"""" else ""
    val tooltipHtml = tooltip
      .map(t => s"""<div class="node-tooltip">$t</div>""")
      .getOrElse("")
    s"""<div class="pipeline-node node-$state"$styleAttr>
      <div class="node-icon">$icon</div>
      <div class="node-label">$label</div>
      <div class="node-sub">$sub</div>
      $tooltipHtml
    </div>"""
  }

  def listOrders: Action[AnyContent] = Action.async {
    orderService
      .listOrdersWithResults(10, 0)
      .map { ordersWithResults =>
        if (ordersWithResults.isEmpty) {
          Ok(
            """<p style="color: #718096; text-align: center; padding: 2rem;">No orders yet. Create one to get started!</p>"""
          )
            .as("text/html")
        } else {
          val html = ordersWithResults
            .map { case models.OrderWithResults(order, results) =>
              // Separate forward and revert results
              val forwardResults = results
                .filter(r => !r.eventType.endsWith(":REVERT") && !r.destination.endsWith(".revert"))
              val revertResults = results
                .filter(r => r.eventType.endsWith(":REVERT") || r.destination.endsWith(".revert"))

              val allSucceeded = forwardResults.nonEmpty && forwardResults.forall(_.success)
              val hasReverts   = revertResults.nonEmpty
              val allFailed    = forwardResults.nonEmpty && forwardResults.forall(!_.success)

              // Deduplicate forward results by destination (keep latest)
              val forwardByDest = forwardResults
                .filter(r => fanoutDests.contains(r.destination))
                .groupBy(_.destination)
                .view
                .mapValues(_.maxBy(_.publishedAt))
                .toMap

              // Deduplicate revert results by base destination
              val revertByDest = revertResults
                .filter(r => fanoutDests.contains(r.destination.stripSuffix(".revert")))
                .groupBy(r => r.destination.stripSuffix(".revert"))
                .view
                .mapValues(_.maxBy(_.publishedAt))
                .toMap

              // --- Forward row node states ---
              val outboxState =
                if (forwardByDest.nonEmpty) "success" else "processing"

              val destStates = fanoutDests
                .foldLeft(
                  (Seq.empty[(String, String, Option[models.AggregateResult])], true)
                ) { case ((acc, canBeNext), dest) =>
                  forwardByDest.get(dest) match {
                    case Some(r) if r.success =>
                      (acc :+ ("success", dest, Some(r)), canBeNext)
                    case Some(r) =>
                      (acc :+ ("failed", dest, Some(r)), false)
                    case None if canBeNext && forwardByDest.nonEmpty =>
                      (acc :+ ("processing", dest, None), false)
                    case None =>
                      (acc :+ ("waiting", dest, None), false)
                  }
                }
                ._1

              // --- Build pipeline grid ---
              val gridItems = scala.collection.mutable.ArrayBuffer.empty[String]

              // Row 1: Forward flow
              gridItems += pipelineNode(
                "📦", "Order", "success", "✓",
                style = "grid-column:1;grid-row:1"
              )
              gridItems += s"""<div class="pipeline-connector connector-$outboxState" style="grid-column:2;grid-row:1"></div>"""
              gridItems += pipelineNode(
                "📤", "Outbox", outboxState,
                if (outboxState == "success") "✓" else "...",
                style = "grid-column:3;grid-row:1"
              )

              destStates.zipWithIndex.foreach { case ((state, dest, resultOpt), idx) =>
                val connCol = 2 * idx + 4
                val nodeCol = 2 * idx + 5
                val icon    = destIcons.getOrElse(dest, "📋")
                val sub = state match {
                  case "success" =>
                    s"✓ ${resultOpt.flatMap(_.responseStatus).map(_.toString).getOrElse("")}"
                  case "failed" =>
                    s"✗ ${resultOpt.flatMap(_.responseStatus).map(_.toString).getOrElse("")}"
                  case "processing" => "..."
                  case _            => ""
                }
                val tooltip = resultOpt.map { r =>
                  s"${r.httpMethod} ${r.endpointUrl}<br>${r.responseStatus.map(_.toString).getOrElse("...")} · ${r.durationMs.map(d => s"${d}ms").getOrElse("...")}"
                }
                gridItems += s"""<div class="pipeline-connector connector-$state" style="grid-column:$connCol;grid-row:1"></div>"""
                gridItems += pipelineNode(icon, dest, state, sub, tooltip,
                  style = s"grid-column:$nodeCol;grid-row:1")
              }

              // Compensation rows (if applicable)
              val hasFailed = destStates.exists(_._1 == "failed")

              if (hasFailed || hasReverts) {
                val succeededDests =
                  if (hasFailed)
                    destStates
                      .takeWhile(_._1 != "failed")
                      .filter(_._1 == "success")
                      .map(_._2)
                      .reverse
                  else
                    destStates
                      .filter(_._1 == "success")
                      .map(_._2)
                      .reverse

                val compLabel =
                  if (hasFailed) "Saga Compensation (LIFO)"
                  else "Saga Compensation — Cancel"

                val compRow = 3

                // Row 2: bridge drop + label
                if (hasFailed) {
                  val failedIdx = destStates.indexWhere(_._1 == "failed")
                  val failedCol = 2 * failedIdx + 5
                  gridItems += s"""<div class="comp-drop" style="grid-column:$failedCol;grid-row:2"></div>"""
                  gridItems += s"""<div class="compensation-label" style="grid-column:1/$failedCol;grid-row:2">$compLabel</div>"""
                } else {
                  gridItems += s"""<div class="compensation-label" style="grid-column:1/-1;grid-row:2">$compLabel</div>"""
                }

                // Row 3: compensation nodes aligned under their forward counterparts
                // compNodes: (gridCol, state, nodeHtml)
                val compNodes: Seq[(Int, String, String)] = {
                  val dlqPart =
                    if (hasFailed) {
                      val failedIdx = destStates.indexWhere(_._1 == "failed")
                      val failedCol = 2 * failedIdx + 5
                      Seq(
                        (
                          failedCol,
                          "failed",
                          pipelineNode("⚠️", "DLQ", "failed", "",
                            style = s"grid-column:$failedCol;grid-row:$compRow")
                        )
                      )
                    } else Seq.empty

                  val destPart = succeededDests.map { dest =>
                    val idx  = fanoutDests.indexOf(dest)
                    val col  = 2 * idx + 5
                    val icon = destIcons.getOrElse(dest, "📋")
                    val revertResult = revertByDest.get(dest)
                    val (state, sub) = revertResult match {
                      case Some(r) if r.success =>
                        ("reverted", "↩ reverted")
                      case Some(r) =>
                        ("failed", s"✗ ${r.responseStatus.map(_.toString).getOrElse("")}")
                      case None if noRevertDests.contains(dest) =>
                        ("skipped", "— skipped")
                      case None if hasReverts =>
                        ("processing", "...")
                      case None =>
                        ("waiting", "")
                    }
                    val tooltip = revertResult.map { r =>
                      s"${r.httpMethod} ${r.endpointUrl}<br>${r.responseStatus.map(_.toString).getOrElse("...")} · ${r.durationMs.map(d => s"${d}ms").getOrElse("...")}"
                    }
                    (
                      col,
                      state,
                      pipelineNode(icon, dest, state, sub, tooltip,
                        style = s"grid-column:$col;grid-row:$compRow")
                    )
                  }

                  dlqPart ++ destPart
                }

                compNodes.zipWithIndex.foreach { case ((col, state, nodeHtml), idx) =>
                  if (idx > 0) {
                    val prevCol = compNodes(idx - 1)._1
                    val connCol = (col + prevCol) / 2
                    gridItems += s"""<div class="pipeline-connector comp-connector connector-$state" style="grid-column:$connCol;grid-row:$compRow"></div>"""
                  }
                  gridItems += nodeHtml
                }
              }

              val gridHtml =
                s"""<div class="pipeline-grid">${gridItems.mkString("\n")}</div>"""

              // --- Status badge ---
              val statusBadge = order.orderStatus match {
                case "PENDING" => ""
                case status =>
                  s"""<span class="order-status status-${status.toLowerCase}">$status</span>"""
              }

              // --- Action buttons (same logic as before) ---
              val buttonsHtml = order.orderStatus match {
                case "DELIVERED" =>
                  s"""<div class="pipeline-actions">
                    <button class="btn-danger" hx-delete="/orders/${order.id}/delete" hx-target="#orders" hx-swap="innerHTML">Remove</button>
                  </div>"""
                case "SHIPPED" =>
                  s"""<div class="pipeline-actions">
                    <button class="btn-secondary" hx-put="/orders/${order.id}/status" hx-target="#orders" hx-swap="innerHTML" hx-vals='{"status":"DELIVERED"}'>📦 Mark as Delivered</button>
                    <button class="btn-danger" hx-delete="/orders/${order.id}/delete" hx-target="#orders" hx-swap="innerHTML">Remove</button>
                  </div>"""
                case "CANCELLED" =>
                  s"""<div class="pipeline-actions">
                    <button class="btn-danger" hx-delete="/orders/${order.id}/delete" hx-target="#orders" hx-swap="innerHTML">Remove</button>
                  </div>"""
                case "PENDING" if forwardResults.isEmpty =>
                  s"""<div class="pipeline-actions">
                    <div style="font-size: 0.875rem; color: #6B7280; font-style: italic;">Publishing in progress...</div>
                  </div>"""
                case "PENDING" if allSucceeded =>
                  s"""<div class="pipeline-actions">
                    <button class="btn-secondary" hx-put="/orders/${order.id}/status" hx-target="#orders" hx-swap="innerHTML" hx-vals='{"status":"SHIPPED"}'>🚚 Mark as Shipped</button>
                    <button class="btn-danger" hx-delete="/orders/${order.id}/cancel" hx-target="#orders" hx-swap="innerHTML">Cancel</button>
                  </div>"""
                case "PENDING" if allFailed =>
                  s"""<div class="pipeline-actions">
                    <button class="btn-danger" hx-delete="/orders/${order.id}/delete" hx-target="#orders" hx-swap="innerHTML">Remove</button>
                  </div>"""
                case "PENDING" if hasReverts =>
                  s"""<div class="pipeline-actions">
                    <button class="btn-danger" hx-delete="/orders/${order.id}/delete" hx-target="#orders" hx-swap="innerHTML">Remove</button>
                  </div>"""
                case _ =>
                  s"""<div class="pipeline-actions">
                    <div style="font-size: 0.875rem; color: #EF4444; font-style: italic;">Processing...</div>
                  </div>"""
              }

              // --- Assemble pipeline card ---
              s"""
              <div class="pipeline-card">
                <div class="pipeline-header">
                  <div>
                    <span class="order-id">Order #${order.id}</span>
                    <span class="pipeline-meta">${order.customerId} · $$${order.totalAmount} · ${order.shippingType.capitalize}</span>
                  </div>
                  $statusBadge
                </div>
                $gridHtml
                $buttonsHtml
              </div>"""
            }
            .mkString("\n")

          Ok(html).as("text/html")
        }
      }
      .recover { case ex =>
        Ok(s"""<div class="error">Error loading orders: ${ex.getMessage}</div>""").as("text/html")
      }
  }

  def deleteOrder(id: Long): Action[AnyContent] = Action.async { request =>
    orderService
      .deleteOrder(id)
      .flatMap { _ =>
        listOrders.apply(request)
      }
      .recover { case ex =>
        Ok(s"""<div class="error">Error: ${ex.getMessage}</div>""").as("text/html")
      }
  }

  def outboxStats: Action[AnyContent] = Action.async {
    for {
      pending <- db.run(outboxRepo.countPending)
      processing <- db.run(
        sql"SELECT COUNT(*) FROM outbox_events WHERE status = 'PROCESSING'".as[Int].head
      )
      successful <- db.run(outboxRepo.countSuccessfullyProcessed)
      dlqTotal <- db.run(dlqRepo.countAll)
      dlqPending <- db.run(dlqRepo.countPending)
      dlqMaxRetries <- db.run(dlqRepo.countByReason("MAX_RETRIES_EXCEEDED"))
      dlqNonRetryable <- db.run(dlqRepo.countByReason("NON_RETRYABLE_ERROR"))
    } yield {
      val totalPendingAndProcessing = pending + processing
      val html                      = s"""
      <div class="stat-grid">
        <div class="stat">
          <div class="stat-value">$totalPendingAndProcessing</div>
          <div class="stat-label">Outbox Queue</div>
          ${
          if (processing > 0)
            s"""<div style="font-size: 0.75rem; color: #4299e1; margin-top: 0.25rem;">Processing: $processing | Pending: $pending</div>"""
          else if (pending > 0)
            s"""<div style="font-size: 0.75rem; color: #6B7280; margin-top: 0.25rem;">Pending: $pending</div>"""
          else ""
        }
        </div>
        <div class="stat" style="background: ${
          if (successful > 0) "#F0FDF4" else "white"
        }; border-color: ${if (successful > 0) "#86EFAC" else "#E5E7EB"};">
          <div class="stat-value" style="color: ${
          if (successful > 0) "#16A34A" else "#1F2937"
        };">$successful</div>
          <div class="stat-label">Events Published</div>
        </div>
        <div class="stat" style="background: ${
          if (dlqTotal > 0) "#FEF2F2" else "white"
        }; border-color: ${if (dlqTotal > 0) "#FCA5A5" else "#E5E7EB"};">
          <div class="stat-value" style="color: ${
          if (dlqTotal > 0) "#DC2626" else "#1F2937"
        };">$dlqTotal</div>
          <div class="stat-label">Dead Letter Queue</div>
          ${
          if (dlqTotal > 0)
            s"""<div style="font-size: 0.75rem; color: #991B1B; margin-top: 0.25rem;">${
                if (dlqPending > 0) s"Pending: $dlqPending | " else ""
              }Failed: ${dlqMaxRetries + dlqNonRetryable}</div>"""
          else ""
        }
        </div>
      </div>
      """
      Ok(html).as("text/html")
    }
  }

  // JSON API endpoints

  def createOrderJson: Action[JsValue] = Action.async(parse.json) { request =>
    val customerId   = (request.body \ "customerId").asOpt[String].getOrElse("customer-123")
    val totalAmount  = (request.body \ "totalAmount").asOpt[BigDecimal].getOrElse(BigDecimal(99.99))
    val shippingType = (request.body \ "shippingType").asOpt[String].getOrElse("domestic")

    val order = Order(
      customerId   = customerId,
      totalAmount  = totalAmount,
      shippingType = shippingType,
      orderStatus  = "PENDING",
      createdAt    = Instant.now(),
      updatedAt    = Instant.now()
    )

    orderService
      .createOrder(order)
      .map { orderId =>
        Ok(
          Json.obj(
            "success" -> true,
            "orderId" -> orderId,
            "message" -> s"Order #$orderId created successfully! Event queued in outbox."
          )
        )
      }
      .recover { case ex =>
        InternalServerError(
          Json.obj(
            "success" -> false,
            "error" -> ex.getMessage
          )
        )
      }
  }

  def updateStatusJson(id: Long): Action[JsValue] = Action.async(parse.json) { request =>
    val status = (request.body \ "status").asOpt[String].getOrElse("PROCESSING")

    orderService
      .updateOrderStatus(id, status)
      .map { _ =>
        Ok(
          Json.obj(
            "success" -> true,
            "orderId" -> id,
            "status" -> status,
            "message" -> s"Order #$id status updated to $status"
          )
        )
      }
      .recover {
        case _: NoSuchElementException =>
          NotFound(
            Json.obj(
              "success" -> false,
              "error" -> s"Order $id not found"
            )
          )
        case ex =>
          InternalServerError(
            Json.obj(
              "success" -> false,
              "error" -> ex.getMessage
            )
          )
      }
  }

  def cancelOrderJson(id: Long): Action[AnyContent] = Action.async {
    orderService
      .cancelOrder(id, "User requested via API")
      .map { _ =>
        Ok(
          Json.obj(
            "success" -> true,
            "orderId" -> id,
            "message" -> s"Order #$id cancelled successfully"
          )
        )
      }
      .recover {
        case _: NoSuchElementException =>
          NotFound(
            Json.obj(
              "success" -> false,
              "error" -> s"Order $id not found"
            )
          )
        case ex =>
          InternalServerError(
            Json.obj(
              "success" -> false,
              "error" -> ex.getMessage
            )
          )
      }
  }

  def deleteOrderJson(id: Long): Action[AnyContent] = Action.async {
    orderService
      .cancelOrder(id, "Cancelled via API")
      .map { _ =>
        Ok(
          Json.obj(
            "success" -> true,
            "orderId" -> id,
            "message" -> s"Order #$id cancelled successfully"
          )
        )
      }
      .recover { case ex =>
        InternalServerError(
          Json.obj(
            "success" -> false,
            "error" -> ex.getMessage
          )
        )
      }
  }

  def listOrdersJson: Action[AnyContent] = Action.async {
    orderService
      .listOrders(10, 0)
      .map { orders =>
        Ok(
          Json.obj(
            "success" -> true,
            "orders" -> Json.toJson(orders)
          )
        )
      }
      .recover { case ex =>
        InternalServerError(
          Json.obj(
            "success" -> false,
            "error" -> ex.getMessage
          )
        )
      }
  }

  def outboxStatsJson: Action[AnyContent] = Action.async {
    for {
      pending <- db.run(outboxRepo.countPending)
      successful <- db.run(outboxRepo.countSuccessfullyProcessed)
      dlqTotal <- db.run(dlqRepo.countAll)
      dlqMaxRetries <- db.run(dlqRepo.countByReason("MAX_RETRIES_EXCEEDED"))
      dlqNonRetryable <- db.run(dlqRepo.countByReason("NON_RETRYABLE_ERROR"))
    } yield {
      Ok(
        Json.obj(
          "success" -> true,
          "stats" -> Json.obj(
            "pendingEvents" -> pending,
            "successfullyPublished" -> successful,
            "deadLetterQueue" -> Json.obj(
              "total" -> dlqTotal,
              "maxRetries" -> dlqMaxRetries,
              "nonRetryable" -> dlqNonRetryable
            )
          )
        )
      )
    }
  }

  def debugEventJson(id: Long): Action[AnyContent] = Action.async {
    db.run(outboxRepo.find(id))
      .map { event =>
        val now      = java.time.Instant.now()
        val readyNow = event.nextRetryAt.forall(!_.isAfter(now))
        Ok(
          Json.obj(
            "success" -> true,
            "event" -> Json.toJson(event),
            "debug" -> Json.obj(
              "currentTime" -> now.toString,
              "readyForRetry" -> readyNow,
              "secondsUntilRetry" -> event.nextRetryAt.map { next =>
                (next.toEpochMilli - now.toEpochMilli) / 1000.0
              }
            )
          )
        )
      }
      .recover {
        case ex: NoSuchElementException =>
          NotFound(Json.obj("success" -> false, "error" -> s"Event $id not found"))
        case ex =>
          InternalServerError(Json.obj("success" -> false, "error" -> ex.getMessage))
      }
  }

  def listAllEventsJson: Action[AnyContent] = Action.async {
    import slick.jdbc.PostgresProfile.api.*

    val query = sql"""
      SELECT id, aggregate_id, event_type, status, retry_count,
             created_at, next_retry_at, moved_to_dlq, last_error
      FROM outbox_events
      ORDER BY id DESC
      LIMIT 20
    """.as[
      (
          Long,
          String,
          String,
          String,
          Int,
          java.sql.Timestamp,
          Option[java.sql.Timestamp],
          Boolean,
          Option[String]
      )
    ]

    db.run(query)
      .map { rows =>
        val events = rows.map {
          case (id, aggId, evType, status, retryCount, createdAt, nextRetryAt, dlq, error) =>
            Json.obj(
              "id" -> id,
              "aggregateId" -> aggId,
              "eventType" -> evType,
              "status" -> status,
              "retryCount" -> retryCount,
              "createdAt" -> createdAt.toInstant.toString,
              "nextRetryAt" -> nextRetryAt.map(_.toInstant.toString),
              "movedToDlq" -> dlq,
              "lastError" -> error.map(_.take(100))
            )
        }
        Ok(Json.obj("success" -> true, "events" -> events, "count" -> rows.length))
      }
      .recover { case ex =>
        InternalServerError(Json.obj("success" -> false, "error" -> ex.getMessage))
      }
  }
}
