/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.typed.{ ActorRef, Behavior, Props, Terminated }
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.{ MemberStatus, MultiNodeClusterSpec }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object RemoteDeathWatchSpecConfig extends MultiNodeConfig {
  val first: RoleName = role("first")
  val second = role("second")

  commonConfig(
    ConfigFactory.parseString(
      """
        akka.loglevel = DEBUG
      """).withFallback(
        MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)
}

object RemoteDeathWatchSpec {

  sealed trait ServerProtocol
  case object End extends ServerProtocol
  case class SayHello(to: ActorRef[Hello]) extends ServerProtocol

  case class Hello(name: String)

  val ServerKey = ServiceKey[SayHello]("s1")
  val server = Behaviors.setup[ServerProtocol] { ctx ⇒
    ctx.log.info("Starting: Registering self with receptionist")
    ctx.system.receptionist ! Receptionist.Register(ServerKey, ctx.self.narrow[SayHello])
    Behaviors.receiveMessage[ServerProtocol] {
      case SayHello(to) ⇒
        ctx.log.info("SayHello {}", to)
        to ! Hello("Boso")
        Behaviors.same
      case End ⇒
        ctx.log.info("Good byte actor system")
        throw new RuntimeException("I've had enough")
        Behaviors.stopped
    }.receiveSignal {
      case (_, sig) ⇒
        ctx.log.info("Got signal: {}", sig)
        Behaviors.same
    }
  }

  sealed trait ClientProtocol
  case class NewServers(ref: Set[ActorRef[SayHello]]) extends ClientProtocol
  case class Response(msg: String) extends ClientProtocol
  case class GetResponses(responses: ActorRef[List[String]]) extends ClientProtocol
  case class GetTerminations(responses: ActorRef[List[ActorRef[_]]) extends ClientProtocol

  val client = Behaviors.setup[ClientProtocol] { ctx ⇒
    val serverAdapter = ctx.messageAdapter[Hello](h ⇒ Response(h.name))
    val receptionistAdapter = ctx.messageAdapter[Receptionist.Listing](l ⇒ NewServers(l.serviceInstances(ServerKey)))
    ctx.system.receptionist ! Receptionist.Subscribe(ServerKey, receptionistAdapter)

    def behav(responses: List[String], terminated: List[ActorRef[_]]): Behavior[ClientProtocol] = {
      Behaviors.receiveMessage[ClientProtocol] {
        case NewServers(s) ⇒
          ctx.log.info("New servers {}", s)
          s.foreach(_ ! SayHello(serverAdapter))
          s.foreach(ctx.watch(_))
          Behaviors.same
        case Response(msg) ⇒
          ctx.log.info("Response from server {}", msg)
          behav(msg :: responses, terminated)
        case GetResponses(replyTo) ⇒
          replyTo ! responses
          Behaviors.same
        case GetTerminations(replyTo) ⇒
          ctx.log.info("Returning terminated {}", terminated)
          replyTo ! terminated
          Behaviors.same
      }.receiveSignal {
        case (ctx, t @ Terminated(who)) ⇒
          ctx.log.info("gotterm: {}", who)
          behav(responses, who :: terminated)
      }
    }
    behav(List.empty, List.empty)
  }
}

class RemoteDeathWatchSpecMultiJvmNode1 extends RemoteDeathWatchSpec
class RemoteDeathWatchSpecMultiJvmNode2 extends RemoteDeathWatchSpec

abstract class RemoteDeathWatchSpec extends MultiNodeSpec(RemoteDeathWatchSpecConfig)
  with MultiNodeTypedClusterSpec {

  import RemoteDeathWatchSpecConfig._
  import akka.actor.typed.scaladsl.adapter._
  import RemoteDeathWatchSpec._

  var s1: ActorRef[ServerProtocol] = _
  var c1: ActorRef[ClientProtocol] = _

  "A typed cluster with multiple data centers" must {
    "be able to form" in {
      formCluster(first, second)
    }

    "be able to watch remote actors" in {
      runOn(first) {
        s1 = system.spawn(server, "s1-on-first")
      }

      runOn(second) {
        c1 = system.spawn(client, "client")
        awaitAssert({
          val probe = TestProbe[List[String]]
          c1 ! GetResponses(probe.ref)
          probe.expectMessage(List("Boso"))
        }, 10.seconds)

      }

      enterBarrier("client-server-communicated")

      runOn(first) {
        val probe = TestProbe[ServerProtocol]
        s1 ! End
        probe.expectTerminated(s1, 2.seconds)

      }

      enterBarrier("ended")

      runOn(second) {
        awaitAssert({
          val probe = TestProbe[List[ActorRef[_]]]
          c1 ! GetTerminations(probe.ref)
          probe.expectMessageType[List[(ActorRef[_], Option[Throwable])]].size shouldEqual 1
        }, 10.seconds)
      }

      enterBarrier("done")
      Thread.sleep(10000)

    }
  }
}

