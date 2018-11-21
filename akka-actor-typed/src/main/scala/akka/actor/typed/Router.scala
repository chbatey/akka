/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.Behaviors

import scala.reflect.ClassTag

object Router {

  private[akka] sealed trait Command[T]
  private case class NewInstances[T](instances: Set[ActorRef[T]]) extends Command[T]
  case class Envelope[T](t: T) extends Command[T]

  private[akka] def routerInternal[T](key: ServiceKey[T]): Behavior[Command[T]] = {
    Behaviors.setup[Command[T]] { ctx ⇒
      val log = ctx.log
      val receptionistAdapter: ActorRef[Receptionist.Listing] = ctx.messageAdapter[Receptionist.Listing](listing ⇒ {
        NewInstances[T](listing.serviceInstances(key))
      })
      ctx.system.receptionist ! Receptionist.Subscribe(key, receptionistAdapter)
      def withInstances(instances: IndexedSeq[ActorRef[T]], count: Long): Behavior[Command[T]] = {
        Behaviors.receiveMessage[Command[T]] {
          case NewInstances(newInstances) ⇒
            log.debug("New instances {}", newInstances.seq)
            withInstances(newInstances.seq.toVector, count)
          case Envelope(t) ⇒
            if (instances.isEmpty) {
              ctx.log.debug("No routees registered. Sending msg to dead letters: {}", t)
              ctx.system.deadLetters ! t
            } else {
              instances((count % instances.size).toInt) ! t
            }
            withInstances(instances, count + 1)
        }

      }
      withInstances(Vector.empty, 0)
    }
  }

  def router[T: ClassTag](key: ServiceKey[T]): Behavior[T] =
    Behaviors.contramap[T, Command[T]](routerInternal(key), o ⇒ Envelope(o))

  // The contramap won't work for creating generic routers
  def router2[T](key: ServiceKey[T]): Behavior[Envelope[T]] =
    routerInternal(key).narrow[Envelope[T]]

}
