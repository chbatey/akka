/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import java.nio.file.{ OpenOption, Path }

import akka.annotation.InternalApi
import akka.stream.ActorAttributes.Dispatcher
import akka.stream._
import akka.stream.impl.SinkModule
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 * Creates simple synchronous Sink which writes all incoming elements to the given file
 * (creating it before hand if necessary).
 */
@InternalApi private[akka] final class FileSink(
    f: Path,
    startPosition: Long,
    options: immutable.Set[OpenOption],
    val attributes: Attributes,
    shape: SinkShape[ByteString])
    extends SinkModule[ByteString, Future[IOResult]](shape) {

  override protected def label: String = s"FileSink($f, $options)"

  override def create(context: MaterializationContext) = {
    val materializer = ActorMaterializerHelper.downcast(context.materializer)

    val maxInputBufferSize = context.effectiveAttributes.mandatoryAttribute[Attributes.InputBuffer].max

    val ioResultPromise = Promise[IOResult]()
    val props = FileSubscriber.props(f, ioResultPromise, maxInputBufferSize, startPosition, options)
    val ref = materializer.actorOf(
      context,
      props.withDispatcher(context.effectiveAttributes.mandatoryAttribute[Dispatcher].dispatcher))

    (akka.stream.actor.ActorSubscriber[ByteString](ref), ioResultPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[IOResult]] =
    new FileSink(f, startPosition, options, attributes, shape)

  override def withAttributes(attr: Attributes): SinkModule[ByteString, Future[IOResult]] =
    new FileSink(f, startPosition, options, attr, amendShape(attr))
}