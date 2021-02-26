/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PreRestart
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit._
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object EventSourcedBehaviorTimersFailureSpec {
  val config: Config = PersistenceTestKitPlugin.config

  val supervision = SupervisorStrategy.restartWithBackoff(1.second, maxBackoff = 1.second, randomFactor = 0.1)

  def testBehavior(persistenceId: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    Behaviors.withTimers { timers =>
      timers.startSingleTimer("scheduled", 1.milli)
      (EventSourcedBehavior[String, String, String](
        persistenceId,
        emptyState = "",
        commandHandler = (_, command) =>
          command match {
            case "scheduled" =>
              probe ! "scheduled"
              Effect.none
            case "persist" =>
              Effect.persist("persist")
          },
        eventHandler = (state, evt) => state + evt)).onPersistFailure(supervision).receiveSignal {
        case (_, PreRestart) =>
          timers.startSingleTimer("scheduled", 1.milli)
          probe ! "restart"
        case (_, RecoveryCompleted) =>
        // or here if not in the withTimers block
      }
    }
}

class EventSourcedBehaviorTimersFailureSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTimersFailureSpec.config)
    with BeforeAndAfterEach
    with AnyWordSpecLike
    with LogCapturing {

  import EventSourcedBehaviorTimersFailureSpec._

  val persistenceTestKit = PersistenceTestKit(system)

  override def beforeEach(): Unit = {
    persistenceTestKit.clearAll()
    persistenceTestKit.resetPolicy()
  }

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId.ofUniqueId(s"c${pidCounter.incrementAndGet()})")

  "EventSourcedBehavior withTimers" must {

    "do something" in {
      val policy = new EventStorage.JournalPolicies.PolicyType {
        class CustomFailure extends RuntimeException
        override def tryProcess(persistenceId: String, processingUnit: JournalOperation): ProcessingResult =
          processingUnit match {
            case WriteEvents(List("persist")) => StorageFailure(new CustomFailure)
            case _                            => ProcessingSuccess
          }
      }
      persistenceTestKit.withPolicy(policy)

      val probe = createTestProbe[String]()
      val pid = nextPid()
      val ref = spawn(testBehavior(pid, probe.ref))
      probe.expectMessage("scheduled")
      ref ! "persist"
      probe.expectMessage("restart")
      probe.expectMessage("scheduled")
    }

  }
}
