package akka.actor.typed

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration._

object ActorBenchmark {
}

@State(Scope.Thread)
@Fork(1)
class ActorBenchmark {

  import TypedBenchmarkActors._

  implicit var system: ActorSystem[Start] = _

  val msgs = 1000000

  val config = ConfigFactory.parseString(
    """
      |akka.loglevel = INFO
    """.stripMargin)

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem(waiting, "AS", config)
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def echo(): Unit = {
    val cl = new CountDownLatch(1)
    system ! Start(msgs, cl)
    cl.await()
  }
}
