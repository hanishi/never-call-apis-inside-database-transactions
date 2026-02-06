package services

import actors.OutboxProcessor
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.stream.UniqueKillSwitch
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logging}
import slick.jdbc.JdbcBackend.Database

import javax.inject.*
import scala.concurrent.*
import scala.util.{Failure, Success}

/** PostgreSQL LISTEN/NOTIFY notification service for near-instant event processing.
  *
  * == Purpose ==
  * Manages PostgreSQL LISTEN/NOTIFY streams to trigger OutboxProcessor and DLQProcessor
  * immediately when new events are inserted, providing near-instant processing instead of
  * polling-based delays.
  *
  * == Architecture ==
  *
  * '''Database Triggers → LISTEN/NOTIFY → Akka Streams → Processor Actors'''
  * {{{
  * # 1. Repository inserts event into outbox_events or dead_letter_events
  * INSERT INTO outbox_events (aggregate_id, event_type, status) VALUES ('123', 'OrderCreated', 'PENDING');
  *
  * # 2. PostgreSQL trigger fires and sends notification
  * NOTIFY outbox_events_channel, '123';
  *
  * # 3. NotificationService receives notification via Akka Stream
  * # 4. Routes to appropriate processor actor:
  *    - outbox_events_channel → OutboxProcessor.ProcessUnhandledEvent
  *    - dlq_events_channel → OutboxProcessor.TriggerDLQProcessing
  *
  * # 5. Processor immediately claims and processes event (no polling delay)
  * }}}
  *
  * == Performance Benefits ==
  *
  * '''With LISTEN/NOTIFY (useListenNotify = true):'''
  * {{{
  * # Event processing latency: ~10-50ms
  * # 1. Event inserted at T+0ms
  * # 2. NOTIFY sent at T+5ms
  * # 3. Stream receives at T+10ms
  * # 4. Processor claims at T+15ms
  * # 5. API call starts at T+20ms
  * # Total latency: 20ms from insert to processing
  *
  * # Database load: Minimal (no polling queries)
  * # CPU usage: Low (event-driven, not continuous polling)
  * }}}
  *
  * '''Without LISTEN/NOTIFY (useListenNotify = false):'''
  * {{{
  * # Event processing latency: 0-2000ms (depends on poll timing)
  * # 1. Event inserted at T+0ms
  * # 2. Wait for next poll cycle (every 2 seconds)
  * # 3. Processor polls at T+2000ms
  * # 4. Claims event at T+2010ms
  * # Total latency: Up to 2 seconds from insert to processing
  *
  * # Database load: Higher (continuous polling queries every 2 seconds)
  * # CPU usage: Higher (repeated polling even when no events)
  * }}}
  *
  * == Channel Configuration ==
  *
  * '''Outbox Events Channel:'''
  * {{{
  * # Channel: outbox_events_channel
  * # Trigger: After INSERT on outbox_events
  * # Payload: Comma-separated event IDs (e.g., "123,456,789")
  * # Triggers: OutboxProcessor.ProcessUnhandledEvent
  * # Purpose: Process forward events (OrderCreated, OrderStatusUpdated, etc.)
  * }}}
  *
  * '''DLQ Events Channel:'''
  * {{{
  * # Channel: dlq_events_channel
  * # Trigger: After INSERT on dead_letter_events
  * # Payload: Comma-separated DLQ event IDs
  * # Triggers: OutboxProcessor.TriggerDLQProcessing (which delegates to DLQProcessor)
  * # Purpose: Process automatic Saga compensation for failed events
  * }}}
  *
  * == Database Setup ==
  *
  * '''Trigger Function:'''
  * {{{
  * CREATE OR REPLACE FUNCTION notify_outbox_event()
  * RETURNS TRIGGER AS $$
  * BEGIN
  *   PERFORM pg_notify('outbox_events_channel', NEW.id::text);
  *   RETURN NEW;
  * END;
  * $$ LANGUAGE plpgsql;
  *
  * CREATE TRIGGER outbox_events_notify
  * AFTER INSERT ON outbox_events
  * FOR EACH ROW
  * WHEN (NEW.status = 'PENDING')
  * EXECUTE FUNCTION notify_outbox_event();
  * }}}
  *
  * == Lifecycle ==
  *
  * '''Startup:'''
  * {{{
  * # 1. Application starts, NotificationService @Singleton created
  * # 2. Checks outbox.useListenNotify config
  * # 3. If enabled, creates PostgreSQL LISTEN connection
  * # 4. Establishes Akka Stream to process notifications
  * # 5. Listens on: outbox_events_channel, dlq_events_channel
  * # 6. Routes notifications to appropriate processor actors
  * }}}
  *
  * '''Runtime:'''
  * {{{
  * # Continuous stream processing:
  * # PostgreSQL NOTIFY → Akka Stream → Pattern Match on channel → Send message to actor
  *
  * # Example flow:
  * NOTIFY outbox_events_channel, '123'
  *   → Stream receives notification
  *   → Pattern match: "outbox_events_channel"
  *   → outboxActor ! ProcessUnhandledEvent
  *   → OutboxProcessor claims event 123 and processes
  * }}}
  *
  * '''Shutdown:'''
  * {{{
  * # 1. Application receives SIGTERM
  * # 2. ApplicationLifecycle triggers stop hooks
  * # 3. KillSwitch.shutdown() called on stream
  * # 4. PostgreSQL LISTEN connection closed
  * # 5. Stream completes gracefully
  * }}}
  *
  * == Error Handling ==
  *
  * '''Stream Failure:'''
  * {{{
  * # If stream fails (e.g., database connection lost):
  * # 1. Stream completion Future fails
  * # 2. Error logged: "LISTEN/NOTIFY streams failed"
  * # 3. Processors automatically fall back to polling mode
  * # 4. Events still processed via poll intervals (degraded but functional)
  * }}}
  *
  * '''Unknown Channel:'''
  * {{{
  * # If notification received from unexpected channel:
  * NOTIFY unknown_channel, '123'
  *   → Warning logged: "Received notification from unknown channel: unknown_channel"
  *   → Notification ignored, no action taken
  * }}}
  *
  * == Configuration ==
  * {{{
  * # conf/application.conf
  * outbox {
  *   useListenNotify = true  # Enable near-instant processing (default: true)
  *   pollInterval = 2 seconds  # Fallback polling interval when LISTEN/NOTIFY disabled
  * }
  * }}}
  *
  * == Monitoring ==
  * {{{
  * # Startup logs:
  * [info] Starting LISTEN/NOTIFY streams for outbox and DLQ events
  * [info] LISTEN/NOTIFY streams started for outbox and DLQ events
  *
  * # Runtime logs (debug level):
  * [debug] Triggering outbox processing for 3 events
  * [debug] Triggering DLQ processing for 1 events
  *
  * # Shutdown logs:
  * [info] Shutting down LISTEN/NOTIFY streams
  * [info] LISTEN/NOTIFY streams completed
  * }}}
  */
@Singleton
class NotificationService @Inject() (
    lifecycle: ApplicationLifecycle,
    eventProcessing: EventProcessingService,
    config: Configuration
)(using system: ActorSystem[Nothing], ec: ExecutionContext, db: Database)
    extends Logging {

  private val useListenNotify =
    config.getOptional[Boolean]("outbox.useListenNotify").getOrElse(true)

  private val listenKillSwitch: Option[UniqueKillSwitch] =
    if (useListenNotify) {
      logger.info("Starting LISTEN/NOTIFY streams for outbox and DLQ events")

      val streamConfig = PostgresListenNotifyStream.Config(
        channels = Seq("outbox_events_channel", "dlq_events_channel")
      )

      val (killSwitch, doneF) = PostgresListenNotifyStream
        .createChannelSource(streamConfig)
        .map { notification =>
          notification.channel match {
            case "outbox_events_channel" =>
              logger.debug(
                s"Triggering outbox processing for ${notification.eventIds.length} events"
              )
              eventProcessing.outboxActor ! OutboxProcessor.ProcessUnhandledEvent
            case "dlq_events_channel" =>
              logger.debug(
                s"Triggering DLQ processing for ${notification.eventIds.length} events"
              )
              eventProcessing.outboxActor ! OutboxProcessor.TriggerDLQProcessing
            case other =>
              logger.warn(s"Received notification from unknown channel: $other")
          }
        }
        .toMat(Sink.ignore)(Keep.both)
        .run()

      doneF.onComplete {
        case Success(_) =>
          logger.info("LISTEN/NOTIFY streams completed")
        case Failure(ex) =>
          logger.error("LISTEN/NOTIFY streams failed", ex)
      }

      logger.info("LISTEN/NOTIFY streams started for outbox and DLQ events")
      Some(killSwitch)
    } else None

  lifecycle.addStopHook { () =>
    listenKillSwitch.foreach { ks =>
      logger.info("Shutting down LISTEN/NOTIFY streams")
      ks.shutdown()
    }
    Future.successful(())
  }
}
