package org.http4s
package armeria
package client

import com.linecorp.armeria.common.stream.{
  AbortedStreamException,
  CancelledSubscriptionException,
  SubscriptionOption
}
import com.linecorp.armeria.common.{
  CommonPools,
  HttpData,
  HttpHeaders,
  HttpObject,
  HttpResponse,
  HttpStatus,
  ResponseHeaders
}
import io.netty.channel.EventLoop
import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import ResponseSubscriber.SUCCESS
import cats.effect.{Async, ContextShift}
import scala.concurrent.Promise
import cats.implicits._

private[client] final class ResponseSubscriber[F[_]](response: HttpResponse)(implicit
    F: Async[F],
    cs: ContextShift[F])
    extends Subscriber[HttpObject] {

  // TODO(ikhoon): Remove this class once Armeria HttpResponse natively supports ResponseBodyStream.

  private val eventLoop: EventLoop = CommonPools.workerGroup.next

  // Start subscribing HttpResponse.
  response.subscribe(this, eventLoop, SubscriptionOption.NOTIFY_CANCELLATION)

  private val headersPromise: Promise[ResponseHeaders] = Promise()
  private val trailersPromise: Promise[HttpHeaders] = Promise()
  private val responseBodyPublisher: ResponseBodyPublisher = new ResponseBodyPublisher(this)

  @volatile
  private var subscription: Subscription = _
  @volatile
  private var completedCause: Throwable = _
  private var sawLeadingHeaders: Boolean = false

  def headers: F[ResponseHeaders] = Async.fromFuture(F.pure(headersPromise.future))

  def trailers: F[HttpHeaders] = Async.fromFuture(F.pure(trailersPromise.future))

  def bodyPublisher: F[ResponseBodyPublisher] = headers.as(responseBodyPublisher)

  override def onSubscribe(subscription: Subscription): Unit =
    if (subscription == null)
      throw new NullPointerException("subscription")
    else {
      this.subscription = subscription
      subscription.request(1)
    }

  override def onNext(httpObject: HttpObject): Unit =
    httpObject match {
      case headers: HttpHeaders =>
        headers match {
          case responseHeaders: ResponseHeaders if !sawLeadingHeaders =>
            if (responseHeaders.status().isInformational)
              subscription.request(1)
            else {
              sawLeadingHeaders = true
              headersPromise
                .success(responseHeaders)
            }
          case _ =>
            trailersPromise.success(headers)
        }
      case data: HttpData =>
        val subscriber = responseBodyPublisher.subscriber
        if (subscriber == null) {
          onError(
            new IllegalStateException(
              s"HttpObject was relayed downstream when there's no subscriber: ${httpObject}"))
          subscription.cancel()
        } else
          subscriber.onNext(data)
    }

  override def onComplete(): Unit = complete(SUCCESS)

  override def onError(cause: Throwable): Unit = complete(cause)

  private def complete(cause: Throwable): Unit = {
    completedCause = cause
    // Complete the future for the response headers if it did not receive any non-informational headers yet.
    if ((cause ne SUCCESS) &&
      !cause.isInstanceOf[CancelledSubscriptionException] &&
      !cause.isInstanceOf[AbortedStreamException]) {
      headersPromise.tryFailure(cause)
      trailersPromise.tryFailure(cause)
    } else {
      if (!headersPromise.isCompleted)
        headersPromise.trySuccess(ResponseHeaders.of(HttpStatus.UNKNOWN))
      trailersPromise.trySuccess(HttpHeaders.of())
    }
    // Notify the publisher.
    responseBodyPublisher.relayOnComplete()
  }

  private def upstreamSubscription: Subscription = {
    val subscription = this.subscription
    if (subscription == null)
      throw new IllegalStateException("No subscriber has been subscribed.")
    else
      subscription
  }

  final private[client] class ResponseBodyPublisher(parent: ResponseSubscriber[F])
      extends Publisher[HttpData]
      with Subscription {

    private[client] def subscriber: Subscriber[_ >: HttpData] = subscriberRef.get

    private val subscriberRef: AtomicReference[Subscriber[_ >: HttpData]] = new AtomicReference

    override def subscribe(s: Subscriber[_ >: HttpData]): Unit =
      if (subscriberRef.compareAndSet(null, s))
        s.onSubscribe(this)
      else
        s.onError(new IllegalStateException("subscribed by other subscriber already"))

    override def request(n: Long): Unit =
      if (parent.completedCause == null)
        // The stream is not completed yet.
        parent.upstreamSubscription.request(n)
      else
        // If this stream is already completed, invoke 'onComplete' or 'onError' asynchronously.
        parent.eventLoop.execute(() => this.relayOnComplete())

    override def cancel(): Unit =
      parent.upstreamSubscription.cancel()

    private[client] def relayOnComplete(): Unit = {
      val cause = parent.completedCause
      assert(cause != null)
      if (subscriber != null)
        if (cause eq SUCCESS) subscriber.onComplete()
        else subscriber.onError(cause)
      else {
        // If there's no Subscriber yet, we can notify later
        // when a Subscriber subscribes and calls Subscription.request().
      }
    }
  }

}

private object ResponseSubscriber {
  private val SUCCESS: Throwable = new Throwable("SUCCESS", null, false, false) {}
}
