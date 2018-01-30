package akka.actor.typed

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.testkit.typed.TestKit
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class TypedBenchmarkActorsSpec extends TestKit with WordSpecLike with BeforeAndAfterAll with Eventually {
  import TypedBenchmarkActors._
  "Echo Actor" must {
    "Must echo then shut down latch" in {
      val waiting = spawn(waiting)
      val latch = new CountDownLatch(10)
      waiting ! Start(10, latch)

      latch.await(1, TimeUnit.SECONDS)
    }
  }

  override protected def afterAll(): Unit = TestKit.shutdown(system)
}
