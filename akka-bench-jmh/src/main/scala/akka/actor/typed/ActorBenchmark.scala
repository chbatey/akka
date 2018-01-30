package akka.actor.typed

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.actor.typed.ActorSystem
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations._
import akka.actor.BenchmarkActors._

object ActorBenchmark {
  // Constants because they are used in annotations
  final val threads = 8 // update according to cpu
}

@State(Scope.Benchmark)
@Fork(1)
@BenchmarkMode(Array(Mode.Throughput))
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 15, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class ActorBenchmark {

  import ActorBenchmark._
  import TypedBenchmarkActors._

  implicit var system: ActorSystem[Start] = _

  val msgs = 1000000

  @Setup(Level.Trial)
  def setup(): Unit = {
    requireRightNumberOfCores(threads)
    system = ActorSystem(waiting, "AS")
  }

  @Benchmark
  def echo(): Unit = {
    val cl = new CountDownLatch(1)
    system ! Start(msgs, cl)
    cl.await()
  }
}
