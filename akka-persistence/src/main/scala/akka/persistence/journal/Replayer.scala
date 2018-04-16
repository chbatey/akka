/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal

import akka.Done
import akka.actor.{ Actor, ActorRef }
import akka.persistence.JournalProtocol.ReplayedMessage
import akka.persistence.PersistentRepr
import akka.persistence.journal.Replayer.{ ReplayerMessages, ReplayerAt }

import scala.annotation.tailrec

private[journal] object Replayer {
  // request to be notified with [Done] once replayer is up to seqNr
  case class ReplayerAt(seqNr: Long)
  // reply the given messages. All must be for the same sequence nr
  // messages can have the same sequence nrs via event adapters
  case class ReplayerMessages(seqNr: Long, prs: Seq[PersistentRepr])
}

private[journal] class Replayer(target: ActorRef, from: Long) extends Actor {

  println("Replayer from: " + from)

  import scala.collection.mutable.Map

  private val buffer = Map.empty[Long, ReplayerMessages]
  private var sequenceNr = from - 1L
  private var relayAt: Option[(ReplayerAt, ActorRef)] = None

  def receive: Receive = {
    case rm: ReplayerMessages ⇒
      println("Replayer: " + rm)
      resequence(rm)
    case replayAt @ ReplayerAt(seqNr) ⇒
      println("Replayer: " + replayAt)
      if (sequenceNr == seqNr)
        sender ! Done
      else
        relayAt = Some((replayAt, sender()))
  }

  @tailrec
  private def resequence(rm: ReplayerMessages): Unit = {
    if (rm.seqNr == sequenceNr + 1) {
      sequenceNr = rm.seqNr
      rm.prs foreach { pr ⇒
        target.tell(ReplayedMessage(pr), Actor.noSender)
      }
      relayAt.foreach { ra ⇒
        if (ra._1.seqNr == sequenceNr) {
          println("Replayer done")
          ra._2 ! Done
        } else {
          println("Replaye not done")
        }
      }
    } else {
      println("Replayer buffering: " + rm)
      buffer += (rm.seqNr → rm)
    }
    // Do we have the next one in line?
    val ro = buffer.remove(sequenceNr + 1)
    if (ro.isDefined) resequence(ro.get)
  }
}

