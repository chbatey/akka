/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.testkit.typed.TestKit
import org.scalatest.concurrent.Eventually
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

class TypedBenchmarkActorsSpec extends TestKit with WordSpecLike with BeforeAndAfterAll with Eventually with Matchers {
  import TypedBenchmarkActors._
  "Echo Actor" must {
    "Must echo then shut down latch" in {
      val w = spawn(waiting)
      val latch = new CountDownLatch(1)
      w ! Start(10, latch)

      latch.await(1, TimeUnit.SECONDS)
      latch.getCount shouldEqual 0
    }
  }

  override protected def afterAll(): Unit = TestKit.shutdown(system)
}
