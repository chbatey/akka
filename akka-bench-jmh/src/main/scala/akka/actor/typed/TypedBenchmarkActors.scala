package akka.actor.typed

import java.util.concurrent.CountDownLatch

import akka.actor.typed.scaladsl.Behaviors

object TypedBenchmarkActors {

  case class EchoMe(msg: Any, reply: ActorRef[Any])

  sealed trait Command
  case class Start(msgs: Int, done: CountDownLatch) extends Command
  case object Running extends Command

  val echoBehavior: Behavior[EchoMe] = Behaviors.immutable {
    case (_, msg) ⇒
      msg.reply ! msg
      Behaviors.same
  }

  val waiting: Behavior[Command] = Behaviors.immutablePartial[Command] {
    case (ctx, Start(msgs, done)) ⇒
      val child: ActorRef[EchoMe] = ctx.spawnAnonymous(echoBehavior)
      val echoMsg = EchoMe("any", ctx.self.upcast[Any])
      echoSender(child, msgs, done, echoMsg)
  }

  def echoSender(child: ActorRef[EchoMe], msgs: Int, finished: CountDownLatch, echoMsg: EchoMe): Behavior[Command] =
    Behaviors.immutable[Command] { (ctx, msg) ⇒
      msg match {
        case _ ⇒
          if (msgs == 0) {
            ctx.stop(child)
            finished.countDown()
            Behaviors.same
          } else {
            child ! echoMsg
            echoSender(child, msgs - 1, finished, echoMsg)
          }
      }
    }
}
