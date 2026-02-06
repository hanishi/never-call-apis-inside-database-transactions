package publishers

import com.jayway.*
import models.*
import play.api.Logger
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyReadables.readableAsString
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.libs.ws.{ WSClient, WSRequest, WSResponse }
import repositories.{ DestinationResultRepository, OutboxEventsPostgresProfile }
import slick.jdbc.JdbcBackend.Database

import java.net.*
import java.time.Instant
import java.util.concurrent.*
import javax.inject.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.*

/** HTTP-based event publisher supporting fan-out, conditional routing, and automatic compensation.
  *
  * Responsible for executing the actual HTTP calls to external APIs based on routing decisions
  * from EventRouter. Handles both forward event publishing and Saga-style compensation.
  *
  * == Forward Flow (e.g., OrderCreated) ==
  *
  * {{{
  * # Configuration
  * fanout {
  *   OrderCreated = ["inventory", "fraudCheck", "shipping", "billing"]
  * }
  *
  * # Execution
  * 1. Router returns: [Forward(inventory), Forward(fraudCheck), Forward(shipping), Forward(billing)]
  * 2. Calls each API sequentially: POST /inventory/reserve → POST /fraud/check → POST /shipping → POST /billing
  * 3. Collects responses in RoutingContext (fraudCheck response used by billing for conditional routing)
  * 4. Saves all results with fanout_order (0, 1, 2, 3) and sequential timestamps
  * 5. If billing fails → returns failure, triggers automatic compensation via DLQ
  * }}}
  *
  * == Revert Flow (Saga Compensation) ==
  *
  * {{{
  * # Triggered by: DLQ processor (automatic) OR !OrderCreated event (manual cancellation)
  *
  * # Execution
  * 1. Router returns LIFO order: [Revert("billing"), Revert("shipping"), Revert("inventory")]
  * 2. For each Revert destination:
  *    a. Query aggregate_results for successful forward call
  *    b. Extract values from forward request/response using JSONPath
  *    c. Build revert endpoint: POST /billing/{transactionId}/refund
  *    d. Call revert API with extracted data
  *    e. Gracefully skip if no revert config (fraudCheck is read-only, no compensation needed)
  * 3. LIFO ensures proper compensation: billing undone before shipping
  * }}}
  *
  * == Conditional Routing ==
  *
  * {{{
  * # Example: Billing routes based on fraudCheck.riskScore
  * billing {
  *   routes = [{
  *     url = "http://localhost:9000/api/billing"
  *     condition { jsonPath = "$.riskScore", operator = "lt", value = "50", previousDestination = "fraudCheck" }
  *   }, {
  *     url = "http://localhost:9000/api/high-value-processing"
  *     condition { jsonPath = "$.riskScore", operator = "gte", value = "50", previousDestination = "fraudCheck" }
  *   }]
  * }
  *
  * # Flow
  * 1. inventory → fraudCheck (returns {"riskScore": 75})
  * 2. RoutingContext updated with fraudCheck response
  * 3. Billing evaluates conditions against fraudCheck.riskScore
  * 4. Second route matches (75 >= 50) → calls high-value-processing endpoint
  * }}}
  *
  * == Result Ordering (Critical for Saga) ==
  *
  * Results are saved with both fanout_order index and sequential timestamps:
  * {{{
  * # Forward order
  * inventory:  fanout_order=0, timestamp=T+0ms
  * fraudCheck: fanout_order=1, timestamp=T+1ms
  * shipping:   fanout_order=2, timestamp=T+2ms
  * billing:    fanout_order=3, timestamp=T+3ms
  *
  * # DLQProcessor queries: ORDER BY fanout_order DESC → [billing, shipping, fraudCheck, inventory]
  * # Ensures LIFO compensation: last operation undone first
  * }}}
  *
  * == Error Handling ==
  *
  * - '''2xx''': Success
  * - '''404 + DELETE''': Success (idempotent delete)
  * - '''429/503''': Retryable with Retry-After header parsing
  * - '''4xx''': Non-retryable (client error, no point retrying)
  * - '''5xx''': Retryable (server error, may recover)
  */
@Singleton
class HttpEventPublisher @Inject() (
    ws: WSClient,
    eventRouter: EventRouter,
    resultRepo: DestinationResultRepository,
    dbConfigProvider: DatabaseConfigProvider
)(using ec: ExecutionContext)
    extends EventPublisher {

  private val logger  = Logger(this.getClass)
  private val db      = dbConfigProvider.get[OutboxEventsPostgresProfile].db.asInstanceOf[Database]
  private val allowed = Set("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

  /** Main entry point for publishing an event.
    * Routes the event to destinations (forward or revert) and publishes sequentially.
    *
    * Flow:
    * 1. Router determines destinations based on event type
    *    - Forward event (OrderCreated) → [Forward(billing), Forward(shipping)]
    *    - Revert event (!OrderCreated) → [Revert(shipping), Revert(billing)] (reversed for LIFO)
    * 2. Get configured fanout order for saving results in correct sequence
    * 3. Publish to each destination sequentially, collecting results
    * 4. Save all results with proper ordering (fanout_order and timestamps)
    */
  override def publish(event: OutboxEvent): Future[PublishResult] = {
    // 1. Route event to destinations (handles both forward and revert)
    val destinations = eventRouter.routeEventToDestinations(event)

    // 2. Get configured fanout order for result persistence
    //    This order determines timestamps and fanout_order indices
    val configuredOrder = eventRouter match {
      case router: ConfigurationEventRouter =>
        router
          .getFanoutOrder(event.eventType)
          .getOrElse(
            destinations.collect {
              case RoutedDestination.Forward(endpoint) => endpoint.destinationName
              case RoutedDestination.Revert(baseDest) => s"$baseDest.revert"
            }
          )
      case _ =>
        // Fallback: extract from destinations
        destinations.collect {
          case RoutedDestination.Forward(endpoint) => endpoint.destinationName
          case RoutedDestination.Revert(baseDest) => s"$baseDest.revert"
        }
    }

    // 3. Query for prior successful forward results to support resume-from-failure
    //    On retry, skip destinations that already succeeded and reconstruct RoutingContext
    //    from saved response payloads so conditional routing still works
    db.run(resultRepo.findSuccessfulForwardsByEventType(event.aggregateId, event.eventType)).flatMap { priorResults =>
      if (priorResults.isEmpty) {
        // First attempt or no prior successes — proceed normally
        publishWithDestinations(event, destinations, RoutingContext(), List.empty, configuredOrder)
      } else {
        // Reconstruct RoutingContext from saved response payloads
        val routingContext = RoutingContext(
          priorResults.flatMap(r => r.responsePayload.map(r.destination -> _)).toMap
        )

        // Collect the set of already-succeeded destination names
        val succeededDestinations = priorResults.map(_.destination).toSet

        logger.info(
          s"Resuming event ${event.eventType} for aggregate ${event.aggregateId} — " +
            s"skipping already-succeeded destinations: [${succeededDestinations.mkString(", ")}]"
        )

        // Filter out Forward destinations that already succeeded; always keep Revert destinations
        val remainingDestinations = destinations.filter {
          case RoutedDestination.Forward(endpoint) =>
            !succeededDestinations.contains(endpoint.destinationName)
          case RoutedDestination.Revert(_) => true
        }

        publishWithDestinations(event, remainingDestinations, routingContext, List.empty, configuredOrder)
      }
    }
  }

  /** Publishes to specific endpoints (used for revert operations where endpoints are pre-determined).
    */
  def publishToEndpoints(
      event: OutboxEvent,
      endpoints: List[HttpEndpointConfig]
  ): Future[PublishResult] =
    publishSequentially(event, endpoints, RoutingContext())

  /** Publishes to an endpoint and returns the result, response payload, and aggregate result.
    * This is used for conditional routing to capture responses for condition evaluation.
    */
  private def publishToEndpointWithContext(
      event: OutboxEvent,
      endpoint: HttpEndpointConfig
  ): Future[(PublishResult, Option[JsValue], Option[AggregateResult])] =
    endpoint.url match {
      case None =>
        Future.successful(
          (
            PublishResult.NonRetryable(
              s"No URL configured for endpoint: ${endpoint.destinationName}"
            ),
            None,
            None
          )
        )

      case Some(_) =>
        val payloadOpt             = event.getPayloadFor(endpoint.destinationName)
        val (request, resolvedUrl) = buildRequest(event, endpoint, payloadOpt)
        val methodUpper            = endpoint.method.toUpperCase

        if (!allowed(methodUpper))
          Future.successful(
            (PublishResult.NonRetryable(s"Unsupported HTTP method: $methodUpper"), None, None)
          )
        else
          timedEither(httpCall(request, methodUpper, payloadOpt)).map {
            case (Right(resp), duration) =>
              val result       = validateResponse(resp, endpoint)
              val responseJson = scala.util.Try(resp.json).toOption

              val aggregateResult = createResult(
                event.aggregateId,
                event.eventType,
                endpoint.withResolvedUrl(resolvedUrl),
                payloadOpt,
                Some(resp.status),
                responseJson,
                duration,
                result == PublishResult.Success,
                None
              )

              (result, responseJson, Some(aggregateResult))

            case (Left(ex), duration) =>
              val errorType = ex match {
                case _: ConnectException => "Connection failed"
                case _: TimeoutException => "Timeout"
                case _ => "Error calling"
              }
              val errorMsg = s"$errorType ${endpoint.url}: ${ex.getMessage}"

              val aggregateResult = createResult(
                event.aggregateId,
                event.eventType,
                endpoint,
                payloadOpt,
                None,
                None,
                duration,
                success = false,
                Some(errorMsg)
              )

              (PublishResult.Retryable(errorMsg), None, Some(aggregateResult))
          }
    }

  private def validateResponse(response: WSResponse, endpoint: HttpEndpointConfig): PublishResult =
    response.status match {
      case status if status >= 200 && status < 300 =>
        PublishResult.Success

      // DELETE + 404 = Success (idempotent: resource already deleted)
      case 404 if endpoint.method.toUpperCase == "DELETE" =>
        PublishResult.Success

      // 429 Too Many Requests - respect Retry-After header
      case 429 =>
        val retryAfter = parseRetryAfter(response, endpoint.retryAfter)
        PublishResult.Retryable(
          s"Rate limited (429): ${response.body[String].take(500)}",
          retryAfter
        )

      // 503 Service Unavailable - often includes Retry-After
      case 503 =>
        val retryAfter = parseRetryAfter(response, endpoint.retryAfter)
        PublishResult.Retryable(
          s"Service unavailable (503): ${response.body[String].take(500)}",
          retryAfter
        )

      case status if status >= 400 && status < 500 =>
        PublishResult.NonRetryable(
          s"HTTP $status: ${response.body[String].take(500)}"
        )

      case status =>
        val retryAfter = parseRetryAfter(response, endpoint.retryAfter)
        PublishResult.Retryable(
          s"HTTP $status: ${response.body[String].take(500)}",
          retryAfter
        )
    }

  /** Parses Retry-After header/body based on configuration.
    */
  private def parseRetryAfter(
      response: WSResponse,
      config: RetryAfterConfig
  ): Option[Instant] = {
    val parsedOpt = if (config.fromBody) {
      parseRetryAfterFromBody(response, config)
    } else {
      parseRetryAfterFromHeader(response, config)
    }

    parsedOpt.orElse(config.fallbackSeconds.map(s => Instant.now().plusSeconds(s)))
  }

  private def parseRetryAfterFromHeader(
      response: WSResponse,
      config: RetryAfterConfig
  ): Option[Instant] =
    response.header(config.headerName).flatMap(value => parseHeaderValue(value, config.format))

  private def parseRetryAfterFromBody(
      response: WSResponse,
      config: RetryAfterConfig
  ): Option[Instant] = for {
    jsonPath <- config.jsonPath
    value <- Try(
      jsonpath.JsonPath.read[Any](response.json.toString, jsonPath).toString
    ).toOption
    instant <- parseHeaderValue(value, config.format)
  } yield instant

  private def parseHeaderValue(value: String, format: RetryAfterFormat): Option[Instant] =
    format match {
      case RetryAfterFormat.Auto =>
        parseAsSeconds(value)
          .orElse(parseAsUnix(value))
          .orElse(parseAsHttpDate(value))
          .orElse(parseAsIso8601(value))
      case RetryAfterFormat.Seconds => parseAsSeconds(value)
      case RetryAfterFormat.Milliseconds => parseAsMilliseconds(value)
      case RetryAfterFormat.Unix => parseAsUnix(value)
      case RetryAfterFormat.HttpDate => parseAsHttpDate(value)
      case RetryAfterFormat.Iso8601 => parseAsIso8601(value)
    }

  private inline def parseAsSeconds(value: String): Option[Instant] =
    Try(value.toLong).toOption.map(seconds => Instant.now().plusSeconds(seconds))

  private inline def parseAsMilliseconds(value: String): Option[Instant] =
    Try(value.toLong).toOption.map(millis => Instant.now().plusMillis(millis))

  private inline def parseAsUnix(value: String): Option[Instant] =
    Try(Instant.ofEpochSecond(value.toLong)).toOption

  private def parseAsHttpDate(value: String): Option[Instant] =
    Try {
      import java.time.ZonedDateTime
      import java.time.format.DateTimeFormatter
      ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant
    }.toOption

  private inline def parseAsIso8601(value: String): Option[Instant] =
    Try(Instant.parse(value)).toOption

  /** Creates an aggregate result without saving it. */
  private def createResult(
      aggregateId: String,
      eventType: String,
      endpoint: HttpEndpointConfig,
      requestPayload: Option[JsValue],
      responseStatus: Option[Int],
      responsePayload: Option[JsValue],
      durationMs: Int,
      success: Boolean,
      errorMessage: Option[String]
  ): AggregateResult =
    AggregateResult(
      aggregateId     = aggregateId,
      eventType       = eventType,
      destination     = endpoint.destinationName,
      endpointUrl     = endpoint.url.getOrElse(""),
      httpMethod      = endpoint.method,
      requestPayload  = requestPayload,
      responseStatus  = responseStatus,
      responsePayload = responsePayload,
      publishedAt     = Instant.now(), // Will be overridden when saved in order
      durationMs      = Some(durationMs),
      success         = success,
      errorMessage    = errorMessage
    )

  /** Replaces placeholders in URL template with actual path parameter values.
    * Example: "/orders/{orderId}" + Map("orderId" -> "123") => "/orders/123"
    */
  private inline def replaceUrlPlaceholders(
      urlTemplate: String,
      pathParams: Map[String, String]
  ): String = pathParams.replacePlaceholders(urlTemplate)

  private def buildRequest(
      event: OutboxEvent,
      endpoint: HttpEndpointConfig,
      payloadOpt: Option[JsValue]
  ): (WSRequest, String) = {
    val pathParams  = event.getPathParamsFor(endpoint.destinationName)
    val resolvedUrl = endpoint.url match {
      case Some(url) => replaceUrlPlaceholders(url, pathParams)
      case None =>
        throw new IllegalStateException(
          s"buildRequest called with endpoint without URL: ${endpoint.destinationName}"
        )
    }

    val baseHeaders = Seq(
      "X-Event-Id" -> event.id.toString,
      "X-Event-Type" -> event.eventType,
      "X-Aggregate-Id" -> event.aggregateId,
      "X-Destination" -> endpoint.destinationName,
      "X-Created-At" -> event.createdAt.toString
    )

    val headers = payloadOpt match {
      case Some(_) => ("Content-Type" -> "application/json") +: baseHeaders
      case None => baseHeaders
    }

    val request = ws
      .url(resolvedUrl)
      .addHttpHeaders(headers: _*)
      .addHttpHeaders(endpoint.headers.toSeq: _*)
      .withRequestTimeout(endpoint.timeout)

    (request, resolvedUrl)
  }

  private def httpCall(
      request: WSRequest,
      methodUpper: String,
      payloadOpt: Option[JsValue]
  ): Future[WSResponse] =
    methodUpper match {
      case "GET" => request.get()
      case "POST" =>
        payloadOpt match {
          case Some(payload) => request.post(payload)
          case None => request.post(Json.obj())
        }
      case "PUT" =>
        payloadOpt match {
          case Some(payload) => request.put(payload)
          case None => request.put(Json.obj())
        }
      case "PATCH" =>
        payloadOpt match {
          case Some(payload) => request.patch(payload)
          case None => request.patch(Json.obj())
        }
      case "DELETE" => request.delete()
      case "HEAD" => request.head()
    }

  /** Capture either success or failure with elapsed ms. */
  private def timedEither[T](
      f: => Future[T],
      start: Long = System.currentTimeMillis()
  ): Future[(Either[Throwable, T], Int)] =
    f.map(Right(_))
      .recover { case e => Left(e) }
      .map(res => (res, (System.currentTimeMillis() - start).toInt))

  /** Resolves an endpoint by evaluating multi-route conditions or single-route conditions.
    *
    * == Multi-Route Resolution ==
    * {{{
    * # Billing has 2 routes based on fraudCheck response
    * billing {
    *   routes = [{
    *     url = "http://localhost:9000/api/billing"
    *     condition { jsonPath = "$.riskScore", operator = "lt", value = "50", previousDestination = "fraudCheck" }
    *   }, {
    *     url = "http://localhost:9000/api/high-value-processing"
    *     condition { jsonPath = "$.riskScore", operator = "gte", value = "50", previousDestination = "fraudCheck" }
    *   }]
    * }
    *
    * # If fraudCheck returned {"riskScore": 75}:
    * # → Second route matches (75 >= 50)
    * # → Returns Some(endpoint with url = high-value-processing)
    * }}}
    *
    * == Single-Route with Condition ==
    * {{{
    * # Endpoint with single URL but conditional execution
    * premium {
    *   url = "http://localhost:9000/api/premium"
    *   condition { jsonPath = "$.isPremium", operator = "eq", value = "true" }
    * }
    *
    * # If payload is {"isPremium": false}:
    * # → Condition not met
    * # → Returns None (endpoint skipped)
    * }}}
    *
    * @param endpoint The endpoint configuration to resolve
    * @param event The outbox event being published
    * @param routingContext Previous destination responses for condition evaluation
    * @return Some(endpoint) with resolved URL if conditions match, None if endpoint should be skipped
    */
  private def resolveEndpoint(
      endpoint: HttpEndpointConfig,
      event: OutboxEvent,
      routingContext: RoutingContext
  ): Option[HttpEndpointConfig] = {
    val payloadOpt                = event.getPayloadFor(endpoint.destinationName)
    val needsPayloadForConditions = endpoint.routes.nonEmpty || endpoint.condition.isDefined

    (payloadOpt, needsPayloadForConditions) match {
      case (None, true) =>
        // No payload but conditional routing requires it, skip
        logger.warn(
          s"Skipping ${endpoint.destinationName} - conditional routing requires payload but none found"
        )
        None

      case (None, false) =>
        // No payload and no conditions, proceed with call (e.g., DELETE without body)
        logger.debug(
          s"No payload for ${endpoint.destinationName}, but no conditions to evaluate - proceeding"
        )
        Some(endpoint)

      case (Some(requestPayload), _) =>
        if (endpoint.routes.nonEmpty) {
          val matchingRoute = endpoint.routes.find { routeOpt =>
            routeOpt.condition.evaluate(routingContext, requestPayload)
          }

          matchingRoute match {
            case Some(route) =>
              logger.info(s"Selected route for ${endpoint.destinationName}: ${route.url}")
              Some(
                endpoint.copy(
                  url    = Some(route.url),
                  revert = route.revert.orElse(endpoint.revert)
                )
              )
            case None =>
              // No route matched - skip endpoint
              logger.info(s"No matching route for ${endpoint.destinationName}, skipping")
              None
          }
        } else
          endpoint.condition match {
            case None => Some(endpoint) // No condition, always execute
            case Some(condition) =>
              val result = condition.evaluate(routingContext, requestPayload)
              if (!result) {
                logger.info(
                  s"Skipping ${endpoint.destinationName} for aggregate ${event.aggregateId} - " +
                    s"condition not met: ${condition.jsonPath} ${condition.operator} ${condition.value.getOrElse("")}"
                )
              }
              if (result) Some(endpoint) else None
          }
    }
  }

  /** Publishes to multiple destinations sequentially, handling both forward and revert destinations.
    * Collects results and saves them in configured destination order.
    * @param event The event to publish
    * @param destinations List of destinations (can be ForwardDestination or RevertDestination)
    * @param routingContext Tracks responses from previous destinations
    * @param collectedResults Accumulated results from previous destinations
    * @param configuredOrder Destination names in configured order for saving
    */
  private def publishWithDestinations(
      event: OutboxEvent,
      destinations: List[RoutedDestination],
      routingContext: RoutingContext,
      collectedResults: List[AggregateResult],
      configuredOrder: List[String]
  ): Future[PublishResult] = destinations match {
    case Nil =>
      // All destinations processed, save results in configured order
      saveResultsInOrder(collectedResults, configuredOrder)
      Future.successful(PublishResult.Success)

    case destination :: remainingDestinations =>
      destination match {
        case RoutedDestination.Forward(endpoint) =>
          // Normal forward call - resolve routes/conditions first, then call endpoint
          resolveEndpoint(endpoint, event, routingContext) match {
            case None =>
              // Endpoint skipped, continue to the next destination
              publishWithDestinations(
                event,
                remainingDestinations,
                routingContext,
                collectedResults,
                configuredOrder
              )

            case Some(selectedEndpoint) =>
              // Publish to the selected endpoint and capture response
              publishToEndpointWithContext(event, selectedEndpoint).flatMap {
                case (PublishResult.Success, responseOpt, resultOpt) =>
                  // Collect result
                  val updatedResults = resultOpt match {
                    case Some(result) => collectedResults :+ result
                    case None => collectedResults
                  }
                  // Update routing context with response
                  val updatedContext = responseOpt match {
                    case Some(response) =>
                      routingContext.withResponse(endpoint.destinationName, response)
                    case None => routingContext
                  }
                  // Continue with updated context
                  publishWithDestinations(
                    event,
                    remainingDestinations,
                    updatedContext,
                    updatedResults,
                    configuredOrder
                  )
                case (failure, _, resultOpt) =>
                  // Collect failure result and save all collected results so far
                  val updatedResults = resultOpt match {
                    case Some(result) => collectedResults :+ result
                    case None => collectedResults
                  }
                  saveResultsInOrder(updatedResults, configuredOrder)
                  Future.successful(failure)
              }
          }

        case RoutedDestination.Revert(baseDest) =>
          // Virtual revert destination - perform revert operation
          publishRevert(event, RoutedDestination.Revert(baseDest)).flatMap {
            case (PublishResult.Success, resultOpt) =>
              // Collect revert result
              val updatedResults = resultOpt match {
                case Some(result) => collectedResults :+ result
                case None => collectedResults
              }
              publishWithDestinations(
                event,
                remainingDestinations,
                routingContext,
                updatedResults,
                configuredOrder
              )
            case (failure, resultOpt) =>
              // Collect failure result and save all collected results so far
              val updatedResults = resultOpt match {
                case Some(result) => collectedResults :+ result
                case None => collectedResults
              }
              saveResultsInOrder(updatedResults, configuredOrder)
              Future.successful(failure)
          }
      }
  }

  /** Publishes a revert operation for a virtual destination (Saga compensation).
    *
    * Implements the compensation step of the Saga pattern by querying the successful forward
    * result, extracting values from request/response, building the revert endpoint, and calling
    * the revert API to undo the original operation.
    *
    * == Flow ==
    * {{{
    * # 1. Query Database for Forward Result
    * # Example: Find billing forward result for order 123
    * SELECT * FROM aggregate_results
    * WHERE aggregate_id = '123'
    *   AND destination = 'billing'
    *   AND success = true
    *   AND event_type NOT LIKE '!%'
    * # Returns: { endpointUrl: "http://localhost:9000/api/billing",
    * #            requestPayload: {"amount": 99.99, "currency": "USD"},
    * #            responsePayload: {"transaction": {"id": "tx-456"}} }
    *
    * # 2. Extract Values Using JSONPath
    * # From revert config:
    * extract {
    *   transactionId = "response:$.transaction.id"  # → "tx-456"
    *   amount = "request:$.amount"                  # → 99.99
    *   currency = "request:$.currency"              # → "USD"
    * }
    *
    * # 3. Build Revert Endpoint
    * # Template: "http://localhost:9000/api/billing/{transactionId}/refund"
    * # Resolved: "http://localhost:9000/api/billing/tx-456/refund"
    * # Payload: {"transactionId": "tx-456", "amount": 99.99, "currency": "USD", "reason": "order_failed"}
    *
    * # 4. Call Revert API
    * POST http://localhost:9000/api/billing/tx-456/refund
    * Body: {"transactionId": "tx-456", "amount": 99.99, "currency": "USD", "reason": "order_failed"}
    * }}}
    *
    * == Graceful Skipping ==
    * - '''No forward result''': Skip (operation never succeeded, nothing to undo)
    * - '''Already compensated''': Skip (idempotency check prevents duplicate compensation)
    * - '''No revert config''': Skip (read-only operation like fraudCheck, no compensation needed)
    *
    * == Multi-Route Support ==
    * For destinations with multiple routes, the forward URL is used to find the correct revert config:
    * {{{
    * # If billing used high-value-processing route:
    * # findRevertConfig("billing", "http://localhost:9000/api/high-value-processing")
    * # → Returns revert config for high-value-processing route (cancel, not refund)
    * }}}
    *
    * @param event The current revert event (e.g., !OrderCreated or OrderCancelled)
    * @param revert The virtual revert destination (e.g., Revert("billing"))
    * @return Tuple of (PublishResult, Option[AggregateResult]) - result and audit record
    */
  private def publishRevert(
      event: OutboxEvent,
      revert: RoutedDestination.Revert
  ): Future[(PublishResult, Option[AggregateResult])] = {
    logger.info(
      s"Processing virtual revert destination: ${revert.baseDestination}.revert for aggregate ${event.aggregateId}"
    )

    // 1. Query for successful forward result
    db.run(resultRepo.findSuccessfulForward(event.aggregateId, revert.baseDestination)).flatMap {
      case None =>
        // No forward result found - skip silently
        logger.warn(
          s"No successful forward result found for aggregate ${event.aggregateId}, " +
            s"destination ${revert.baseDestination} - skipping revert"
        )
        Future.successful((PublishResult.Success, None))

      case Some(forwardResult) =>
        // 2. Check if already compensated (optional optimization)
        db.run(resultRepo.findSuccessfulRevert(event.aggregateId, revert.baseDestination)).flatMap {
          case Some(existingRevert) =>
            logger.info(
              s"Aggregate ${event.aggregateId}, destination ${revert.baseDestination} " +
                s"already compensated at ${existingRevert.publishedAt} - skipping"
            )
            Future.successful((PublishResult.Success, None))

          case None =>
            // 3. Look up endpoint config from router
            val endpoint = eventRouter.routeEvent(
              OutboxEvent(
                aggregateId = "",
                eventType   = revert.baseDestination,
                payloads    = Map.empty
              )
            )

            // 4. Find the correct revert config by matching the forward URL
            eventRouter.findRevertConfig(revert.baseDestination, forwardResult.endpointUrl) match {
              case None =>
                // No revert config for this specific route - treat as non-revertable and skip gracefully
                // This is valid for operations that don't have counterparts (e.g., notifications, analytics)
                logger.info(
                  s"No revert config found for ${revert.baseDestination} " +
                    s"(forward URL: ${forwardResult.endpointUrl}) - treating as non-revertable, skipping"
                )
                Future.successful((PublishResult.Success, None))

              case Some(revertConfig) =>
                // 5. Build revert endpoint from forward result
                RevertEndpointBuilder.buildRevertEndpoint(
                  revertConfig    = revertConfig,
                  requestPayload  = forwardResult.requestPayload,
                  responsePayload = forwardResult.responsePayload,
                  baseDestination =
                    s"${revert.baseDestination}.revert", // Use .revert suffix for destination name
                  headers = endpoint.headers ++ Map(
                    "X-Revert-Aggregate-Id" -> event.aggregateId,
                    "X-Revert-Event-Type" -> event.eventType,
                    "X-Revert-Destination" -> revert.baseDestination
                  ),
                  timeout = endpoint.timeout
                ) match {
                  case Success((revertEndpoint, revertPayload)) =>
                    logger.info(
                      s"Built revert endpoint for ${revert.baseDestination}: ${revertEndpoint.url.getOrElse("N/A")}"
                    )

                    // 4. Call revert API and return result (don't save immediately)
                    val eventWithPayload = OutboxEvent(
                      id          = event.id,
                      aggregateId = event.aggregateId,
                      eventType   = event.eventType,
                      payloads    = Map(
                        revertEndpoint.destinationName -> models.DestinationConfig(
                          payload    = revertPayload,
                          pathParams = Map.empty
                        )
                      ),
                      createdAt = event.createdAt
                    )

                    publishToEndpointWithContext(eventWithPayload, revertEndpoint).map {
                      case (result, _, resultOpt) =>
                        (result, resultOpt)
                    }

                  case Failure(ex) =>
                    logger.error(
                      s"Failed to build revert endpoint for ${revert.baseDestination}: ${ex.getMessage}",
                      ex
                    )
                    Future.successful(
                      (
                        PublishResult
                          .NonRetryable(s"Failed to build revert endpoint: ${ex.getMessage}"),
                        None
                      )
                    )
                }
            }
        }
    }
  }

  /** Publishes to multiple endpoints sequentially with conditional routing support.
    * Collects all results and saves them in the configured destination order.
    * @param event The event to publish
    * @param endpoints List of endpoints to publish to
    * @param routingContext Tracks responses from previous destinations
    */
  private def publishSequentially(
      event: OutboxEvent,
      endpoints: List[HttpEndpointConfig],
      routingContext: RoutingContext
  ): Future[PublishResult] =
    // Collect results while publishing
    publishSequentiallyWithResults(event, endpoints, routingContext, List.empty).map {
      case (result, collectedResults) =>
        // Save results in configured destination order
        saveResultsInOrder(collectedResults, endpoints.map(_.destinationName))
        result
    }

  private def publishSequentiallyWithResults(
      event: OutboxEvent,
      endpoints: List[HttpEndpointConfig],
      routingContext: RoutingContext,
      collectedResults: List[AggregateResult]
  ): Future[(PublishResult, List[AggregateResult])] = endpoints match {
    case Nil =>
      Future.successful((PublishResult.Success, collectedResults))

    case endpoint :: remainingEndpoints =>
      resolveEndpoint(endpoint, event, routingContext) match {
        case None =>
          // Endpoint skipped (no matching route, condition not met, or missing required payload)
          publishSequentiallyWithResults(
            event,
            remainingEndpoints,
            routingContext,
            collectedResults
          )

        case Some(selectedEndpoint) =>
          // Publish to the selected endpoint and handle result
          publishToEndpointWithContext(event, selectedEndpoint).flatMap {
            case (PublishResult.Success, responseOpt, resultOpt) =>
              // Update routing context with response (if available)
              val updatedContext = responseOpt match {
                case Some(response) =>
                  routingContext.withResponse(endpoint.destinationName, response)
                case None => routingContext
              }
              // Collect result and continue to next
              val updatedResults = resultOpt match {
                case Some(result) => collectedResults :+ result
                case None => collectedResults
              }
              publishSequentiallyWithResults(
                event,
                remainingEndpoints,
                updatedContext,
                updatedResults
              )

            case (retryable: PublishResult.Retryable, _, resultOpt) =>
              // Failed with retryable error, collect result and stop
              val updatedResults = resultOpt match {
                case Some(result) => collectedResults :+ result
                case None => collectedResults
              }
              Future.successful((retryable, updatedResults))

            case (nonRetryable: PublishResult.NonRetryable, _, resultOpt) =>
              // Failed with non-retryable error, collect result and stop
              val updatedResults = resultOpt match {
                case Some(result) => collectedResults :+ result
                case None => collectedResults
              }
              Future.successful((nonRetryable, updatedResults))
          }
      }
  }

  /** Saves results in the configured destination order with sequential timestamps.
    * Saves sequentially to ensure timestamps are actually in order.
    */
  private def saveResultsInOrder(
      results: List[AggregateResult],
      configuredOrder: List[String]
  ): Unit = {
    if (results.isEmpty) return

    val baseTime      = Instant.now()
    val resultsByDest = results.groupBy(_.destination).view.mapValues(_.head).toMap

    logger.info(s"saveResultsInOrder - ConfiguredOrder: [${configuredOrder.mkString(", ")}]")
    logger.info(s"saveResultsInOrder - Available results: [${resultsByDest.keys.mkString(", ")}]")

    // Save sequentially to guarantee timestamp ordering
    val saveActions = configuredOrder.zipWithIndex.flatMap { case (destName, index) =>
      resultsByDest.get(destName).map { result =>
        // Add offset based on configured order index and set fanout_order
        val orderedResult = result.copy(
          publishedAt = baseTime.plusMillis(index),
          fanoutOrder = index
        )
        logger.info(
          s"  → $destName: index=$index, fanoutOrder=$index, timestamp=${orderedResult.publishedAt}"
        )
        resultRepo.upsert(orderedResult)
      }
    }

    // Chain all saves sequentially
    if (saveActions.nonEmpty) {
      val sequentialSave = saveActions.reduce((acc, action) => acc.flatMap(_ => action))
      db.run(sequentialSave).recover { case ex =>
        logger.warn(s"Failed to save aggregate results: ${ex.getMessage}")
      }
    }
  }
}
