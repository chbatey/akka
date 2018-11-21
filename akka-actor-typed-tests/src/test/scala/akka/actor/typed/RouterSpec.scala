/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.DeadLetter
import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.RouterSpec.{ AckedMessage, ByeBye, ImportantMessage, Received }
import akka.actor.typed.receptionist.Receptionist.{ Register, Registered }
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import org.scalatest.WordSpecLike

object RouterSpec {

  case class Received(by: String)

  case class ImportantMessage(msg: String)

  sealed trait Acker

  case class AckedMessage(msg: String, ack: ActorRef[Received]) extends Acker

  case object ByeBye extends Acker

  def routee(key: ServiceKey[AckedMessage], id: String): Behavior[Acker] = Behaviors.setup { ctx ⇒

    ctx.system.receptionist ! Register(key, ctx.self)
    ctx.log.info("Registering with receptionist {}", id)

    Behaviors.receiveMessage {
      case AckedMessage(msg, ack) ⇒
        ctx.log.info("Routee received {}", msg)
        ack ! Received(id)
        Behaviors.same
      case ByeBye ⇒
        Behaviors.stopped
    }
  }

}

class RouterSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  val untypedSystem = system.toUntyped

  "Router" must {
    "send to dead letters when no registered actors" in {

      val deadLetterProbe = testKit.deadLetterProbe()
      val key = ServiceKey[ImportantMessage]("m1")
      val router = spawn(Router.router(key))
      val msg = ImportantMessage("hello")
      router ! msg

      deadLetterProbe.expectMessageType[DeadLetter].message shouldEqual msg
    }

    "round robins between routees" in {
      val deadLetterProbe = testKit.deadLetterProbe()
      val key = ServiceKey[ImportantMessage]("m3")
      val router = spawn(Router.router(key))
      val routeeeOne = TestProbe[ImportantMessage]
      val routeeeTwo = TestProbe[ImportantMessage]
      val routeeeThree = TestProbe[ImportantMessage]
      val receptionistProbe = TestProbe[Registered]

      system.receptionist ! Register(key, routeeeOne.ref, receptionistProbe.ref)
      receptionistProbe.expectMessageType[Registered]
      system.receptionist ! Register(key, routeeeTwo.ref, receptionistProbe.ref)
      receptionistProbe.expectMessageType[Registered]
      system.receptionist ! Register(key, routeeeThree.ref, receptionistProbe.ref)
      receptionistProbe.expectMessageType[Registered]
      val routees = Vector(routeeeOne, routeeeTwo, routeeeThree)

      // Is there a race here that the routee hasn't seen the new Listing, what guarantees
      // do we get once we receive a Registered?
      (0 to 10) foreach { i ⇒
        val msg = ImportantMessage(s"$i")
        router ! msg
        deadLetterProbe.expectNoMessage()
        val expectedRoute = routees(i % routees.size)
        expectedRoute.expectMessage(msg)
      }
    }

    "stops routing when actors are removed from listing" in {
      val key = ServiceKey[AckedMessage]("m4")
      val router = spawn(Router.router(key))
      val routee1 = spawn(RouterSpec.routee(key, "1"))
      spawn(RouterSpec.routee(key, "2"))
      val probe = TestProbe[Received]

      // Give time for the routees to register
      eventually {
        router ! AckedMessage("hello", probe.ref)
        router ! AckedMessage("there", probe.ref)
        probe.receiveN(2).map(_.by).toSet shouldEqual Set("1", "2")
      }
      system.log.info("Killing routee 1")

      routee1 ! ByeBye
      probe.expectTerminated(routee1)

      // Is there a race here that it gets sent to route1 before it is unregistered?
      val nrMessages = 10
      (0 to nrMessages) foreach { i ⇒
        router ! AckedMessage(s"$i", probe.ref)
      }
      val responses = probe.receiveN(nrMessages).map(_.by)
      responses.size shouldEqual nrMessages
      responses.toSet shouldEqual Set("2")
      testKit.deadLetterProbe().expectNoMessage()
    }
  }
}
