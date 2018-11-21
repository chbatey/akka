/*
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import java.util.concurrent.TimeUnit

import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration._

object ActorWatchBenchmark {

  class Watcher(nr: Int) extends Actor {

    var terminated = 0

    override def receive: Receive = {
      case ref: ActorRef ⇒
        context.watch(ref)
        sender() ! "watched"
      case Terminated(ref) ⇒
        terminated += 1
        if (terminated == nr) {
          context.stop(self)
        }
    }

  }

  class Watchee extends Actor {

    override def receive: Receive = {
      case "shutdown" ⇒
        context.stop(self)
        sender() ! "done"

    }
  }

}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(1)
@Threads(1)
@Warmup(iterations = 1, time = 5, timeUnit = TimeUnit.MILLISECONDS, batchSize = 1)
@Measurement(iterations = 1, time = 15, timeUnit = TimeUnit.MILLISECONDS, batchSize = 1)
class ActorWatchBenchmark {

  import ActorWatchBenchmark._

  @Param(Array("1"))
  var nrWatches = 10000

  var watcher: ActorRef = _

  var watchees: Array[ActorRef] = _

  implicit var system: ActorSystem = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem("ActorWatchBenchmark", ConfigFactory.parseString(
      s"""
       akka {
        loglevel = DEBUG
        actor {
          debug {
            # enable DEBUG logging of unhandled messages
            unhandled = on
          }
        }
       }
      """
    ))

    watchees = new Array[ActorRef](nrWatches)
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  @Benchmark
  def createAndDestroy(): Unit = {
    val probe = TestProbe()
    watcher = system.actorOf(Props(new Watcher(nrWatches)))
    for (i ← 0 until nrWatches) {
      watchees(i) = system.actorOf(Props(new Watchee()))
    }
    for (i ← 0 until nrWatches) {
      watchees(i).tell("shutdown", probe.ref)
      probe.expectMsg("done")
    }
    watcher ! PoisonPill
  }

  @Benchmark
  def createDestroyAndWatch(): Unit = {
    val probe = TestProbe()
    watcher = system.actorOf(Props(new Watcher(nrWatches)))
    probe.watch(watcher)
    for (i ← 0 until nrWatches) {
      watchees(i) = system.actorOf(Props(new Watchee()))
      watcher.tell(watchees(i), probe.ref)
      probe.expectMsg("watched")

    }
    for (i ← 0 until nrWatches) {
      watchees(i).tell("shutdown", probe.ref)
      probe.expectMsg("done")
    }
    probe.expectTerminated(watcher, 10.seconds)
  }

}

