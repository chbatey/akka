/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal

import akka.actor.{ ExtendedActorSystem, Props }
import akka.event.LoggingReceive
import akka.persistence.{ Persistence, PersistentActor, RecoveryCompleted }
import akka.persistence.journal.AsyncAdapterSpec.FuturePersister
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future

case class Wrapper[A](message: String, payload: A)
case class InnerMessage(message: String)

class AsyncAdapter(system: ExtendedActorSystem, pluginId: String) extends EventAdapter {

  lazy val eventAdapters = Persistence(system).adaptersFor(pluginId)

  override def manifest(event: Any): String = ""

  override def toJournal(event: Any): Any = {
    event match {
      case Wrapper(msg, payload) ⇒
        val innerAdapter = eventAdapters.get(payload.getClass)
        Future.successful(Wrapper(msg + " toJournal", innerAdapter.toJournal(payload)))
    }
  }

  override def fromJournal(event: Any, manifest: String): EventSeq = {
    event match {
      case Wrapper(msg, payload) ⇒
        val adaptedInners: EventSeq = eventAdapters.get(payload.getClass).fromJournal(payload, "")
        EventSeq(adaptedInners.events
          .map(in ⇒ Future.successful(Wrapper(msg + " fromJournal", in))): _*)
    }
  }
}

class NormalAdapter extends EventAdapter {
  override def manifest(event: Any): String = ""

  override def toJournal(event: Any): Any = {
    event match {
      case InnerMessage(msg) ⇒ InnerMessage(msg + " toJournal")
    }
  }

  override def fromJournal(event: Any, manifest: String): EventSeq = {
    event match {
      case InnerMessage(msg) ⇒ EventSeq(InnerMessage(msg + " fromJournal"), InnerMessage(msg + " fromJournal 2"))
    }
  }
}

object AsyncAdapterSpec {
  val config = ConfigFactory.parseString(
    s"""
       |akka {
       |  loglevel = DEBUG
       |  actor.debug.receive = on
       |}
       |akka.persistence.journal {
       |  plugin = "akka.persistence.journal.inmem"
       |
       |  inmem {
       |    # showcases re-using and concating configuration of adapters
       |
       |    event-adapters {
       |      async = ${classOf[AsyncAdapter].getCanonicalName}
       |      inner-adapter = ${classOf[NormalAdapter].getCanonicalName}
       |    }
       |
       |    event-adapter-bindings = {
       |      "akka.persistence.journal.Wrapper" = async
       |      "akka.persistence.journal.InnerMessage" = inner-adapter
       |    }
       |  }
       |}
    """.stripMargin).withFallback(ConfigFactory.load())

  class FuturePersister(val persistenceId: String) extends PersistentActor {

    var events = List.empty[Any]

    def receiveRecover: Receive = LoggingReceive {
      case RecoveryCompleted ⇒
      case msg: Any ⇒
        println("Recover " + msg)
        events = msg :: events

    }

    def receiveCommand: Receive = LoggingReceive {
      case fm: Wrapper[_] ⇒
        persist(fm) { e ⇒
          events = e :: events
          sender() ! events
        }
      case _ ⇒
        sender() ! events
    }
  }
}

class AsyncAdapterSpec extends AkkaSpec(AsyncAdapterSpec.config) with ImplicitSender {
  "Async adapter" must {
    val pid = "a"
    "be able to run async tasks" in {
      val a = system.actorOf(Props(new FuturePersister(pid)))
      val msg = Wrapper("wrapper message", InnerMessage("inner message"))
      a ! msg
      expectMsg(List(msg))

      val a2 = system.actorOf(Props(new FuturePersister(pid)))
      a2 ! "get"
      expectMsg(List(
        Wrapper("wrapper message toJournal fromJournal", InnerMessage("inner message toJournal fromJournal 2")),
        Wrapper("wrapper message toJournal fromJournal", InnerMessage("inner message toJournal fromJournal"))
      ))
    }
  }
}
