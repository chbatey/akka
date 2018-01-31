package akka.actor.typed

import java.util.concurrent.CountDownLatch

import akka.actor.typed.scaladsl.Behaviors

object TypedBenchmarkActors {

  case class EchoMe(msg: Command, reply: ActorRef[Command])

  sealed trait Command
  case class Start(msgs: Int, done: CountDownLatch) extends Command
  case object Msg extends Command

  val echoBehavior: Behavior[EchoMe] = Behaviors.immutable {
    case (_, msg) ⇒
      msg.reply ! msg.msg
      Behaviors.same
  }

  val waiting: Behavior[Command] = Behaviors.immutablePartial[Command] {
    case (ctx, Start(msgs, done)) ⇒
      val child: ActorRef[EchoMe] = ctx.spawnAnonymous(echoBehavior)
      val echoMsg = EchoMe(Msg, ctx.self)
      child ! echoMsg
      echoSender(child, msgs, done, echoMsg)
  }

  def echoSender(child: ActorRef[EchoMe], msgs: Int, finished: CountDownLatch, echoMsg: EchoMe): Behavior[Command] =
    Behaviors.immutable[Command] { (ctx, msg) ⇒
      msg match {
        case Msg ⇒
          if (msgs == 0) {
            ctx.stop(child)
            finished.countDown()
            waiting
          } else {
            child ! echoMsg
            echoSender(child, msgs - 1, finished, echoMsg)
          }
      }
    }
}
