package org.http4s.client.middleware

import cats.effect.{Clock, Resource, Sync}
import cats.implicits._
import java.util.concurrent.TimeUnit
import org.http4s.{Request, Response}
import org.http4s.client.Client
import org.http4s.metrics.MetricsOps
import scala.concurrent.TimeoutException

/**
  * Client middleware to record metrics for the http4s client.
  *
  * This middleware will record:
  * - Number of active requests
  * - Time duration to receive the response headers, taking into account the HTTP status code
  * - Total duration of each request, including consumption of the response body, taking into account the HTTP status code
  * - Number of timeouts
  * - Number of other errors
  *
  * This middleware can be extended to support any metrics ecosystem by implementing the [[MetricsOps]] type
  */
object Metrics {

  /**
    * Wraps a [[Client]] with a middleware capable of recording metrics
    *
    * @param ops a algebra describing the metrics operations
    * @param classifierF a function that allows to add a classifier that can be customized per request
    * @param client the [[Client]] to gather metrics from
    * @return the metrics middleware wrapping the [[Client]]
    */
  def apply[F[_]](ops: MetricsOps[F], classifierF: Request[F] => Option[String] = { _: Request[F] =>
    None
  })(client: Client[F])(implicit F: Sync[F], clock: Clock[F]): Client[F] = {

    Client(withMetrics(client, ops, classifierF))
  }

  private def withMetrics[F[_]](client: Client[F], ops: MetricsOps[F], classifierF: Request[F] => Option[String])(req: Request[F])
                               (implicit F: Sync[F], clock: Clock[F]): Resource[F, Response[F]] =
    (for {
      start <- Resource.liftF(clock.monotonic(TimeUnit.NANOSECONDS))
      _ <- Resource.liftF(ops.increaseActiveRequests(classifierF(req)))
      resp <- client.run(req)
      end <- Resource.liftF(clock.monotonic(TimeUnit.NANOSECONDS))
      _ <- Resource.liftF(ops.recordHeadersTime(resp.status, end - start, classifierF(req)))
      _ <- Resource.liftF(ops.decreaseActiveRequests(classifierF(req)))
      elapsed <- Resource.liftF(clock.monotonic(TimeUnit.NANOSECONDS).map(now => now - start))
      _ <- Resource.liftF(ops.recordTotalTime(resp.status, elapsed, classifierF(req)))
    } yield resp).handleErrorWith { e: Throwable =>
      Resource.liftF[F, Response[F]](
        ops.decreaseActiveRequests(classifierF(req)) *> registerError(ops, classifierF(req))(e) *>
          F.raiseError[Response[F]](e)
        )
    }

  private def registerError[F[_]](ops: MetricsOps[F], classifier: Option[String])(e: Throwable): F[Unit] =
    if (e.isInstanceOf[TimeoutException]) {
      ops.increaseTimeouts(classifier)
    } else {
      ops.increaseErrors(classifier)
    }
}
