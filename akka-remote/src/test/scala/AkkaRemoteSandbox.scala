import akka.actor.{ Actor, ActorIdentity, ActorSystem, Identify, Props }
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import scala.io.StdIn
import scala.concurrent.duration._

// FIXME don't check in
object AkkaRemoteSandbox extends App {

  class EchoActor extends Actor {

    def receive = {
      case msg: String ⇒
        sender() ! s"$msg back at ya"
    }
  }

  val config = ConfigFactory.parseString(
    """
      akka {
        loglevel = DEBUG
        actor {
          provider = "remote"
        }

        remote {
          artery {
            enabled = on
            transport = "tcp"
            canonical.hostname = "localhost"
          }
        }
     }
    """)

  val configSystem1 = ConfigFactory.parseString(
    """
      akka {
        remote {
          artery {
            canonical.port = 25551
          }
        }
      }
    """).withFallback(config)

  val configSystem2 = ConfigFactory.parseString(
    """
      akka {
        remote {
          artery {
            canonical.port = 25552
          }
        }
      }
    """).withFallback(config)

  StdIn.readLine("Start system 1?")
  val as1 = ActorSystem("as1", configSystem1)
  val ref1 = as1.actorOf(Props[EchoActor], "echo")
  println(ref1.path)
  StdIn.readLine("Start system 2?")
  val as2 = ActorSystem("as1", configSystem2)
  StdIn.readLine("Identify from 2 to 1")
  val probe2 = TestProbe()(as2)

  as2.actorSelection("akka://as1@cat:25551/user/echo").tell(Identify("mid"), probe2.ref)
  val ref = probe2.expectMsgType[ActorIdentity](60.seconds).ref.get
  println("Got ref: " + ref)
  ref.tell("hello", probe2.ref)
  probe2.expectMsg(60.seconds, "hello back at ya")

  as1.terminate()
  as2.terminate()
}

