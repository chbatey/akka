/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2012-2016 Eligotech BV.
 */

package akka.persistence.journal

import akka.Done
import akka.actor._
import akka.pattern.{ CircuitBreaker, ask, pipe }
import akka.persistence.JournalProtocol.ReplayedMessage
import akka.persistence._
import akka.persistence.journal.Replayer.{ ReplayerAt, ReplayerMessages }
import akka.util.Helpers.toRootLowerCase
import akka.util.Timeout

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
 * Abstract journal, optimized for asynchronous, non-blocking writes.
 */
trait AsyncWriteJournal extends Actor with AsyncRecovery {

  import AsyncWriteJournal._
  import JournalProtocol._
  import context.dispatcher

  private val extension = Persistence(context.system)
  private val publish = extension.settings.internal.publishPluginCommands
  private val config = extension.configFor(self)

  private val breaker = {
    val maxFailures = config.getInt("circuit-breaker.max-failures")
    val callTimeout = config.getDuration("circuit-breaker.call-timeout", MILLISECONDS).millis
    val resetTimeout = config.getDuration("circuit-breaker.reset-timeout", MILLISECONDS).millis
    CircuitBreaker(context.system.scheduler, maxFailures, callTimeout, resetTimeout)
  }

  private val replayFilterMode: ReplayFilter.Mode =
    toRootLowerCase(config.getString("replay-filter.mode")) match {
      case "off"                   ⇒ ReplayFilter.Disabled
      case "repair-by-discard-old" ⇒ ReplayFilter.RepairByDiscardOld
      case "fail"                  ⇒ ReplayFilter.Fail
      case "warn"                  ⇒ ReplayFilter.Warn
      case other ⇒ throw new IllegalArgumentException(
        s"invalid replay-filter.mode [$other], supported values [off, repair-by-discard-old, fail, warn]")
    }
  private def isReplayFilterEnabled: Boolean = replayFilterMode != ReplayFilter.Disabled
  private val replayFilterWindowSize: Int = config.getInt("replay-filter.window-size")
  private val replayFilterMaxOldWriters: Int = config.getInt("replay-filter.max-old-writers")

  private val resequencer = context.actorOf(Props[Resequencer]())
  private var resequencerCounter = 1L

  private val persistence = Persistence(context.system)
  private val eventAdapters = persistence.adaptersFor(self)

  final def receive: Receive = receiveWriteJournal.orElse[Any, Unit](receivePluginInternal)

  // TODO, opt into async adapters in config? This will add overhead for some users not
  // to use
  private def preparePersistentBatch(rb: immutable.Seq[PersistentEnvelope]): Future[immutable.Seq[AtomicWrite]] = {
    val atomics = rb.flatMap {
      case a: AtomicWrite ⇒ Seq(a)
      case _              ⇒ Seq.empty[AtomicWrite]
    }
    Future.traverse(atomics) { a ⇒
      Future.traverse(a.payload)(p ⇒ adaptToJournal(p.update(sender = Actor.noSender)))
        .map(prs ⇒ a.copy(payload = prs))
    }
  }

  private def adaptFromJournal(repr: PersistentRepr): Future[immutable.Seq[PersistentRepr]] = {
    Future.sequence(eventAdapters.get(repr.payload.getClass).fromJournal(repr.payload, repr.manifest).events map {
      case f: Future[_]   ⇒ f
      case adaptedPayload ⇒ Future.successful(adaptedPayload)
    }).map(adaptedPayloads ⇒ adaptedPayloads.map(repr.withPayload))
  }

  private final def adaptToJournal(repr: PersistentRepr): Future[PersistentRepr] = {
    val payload = repr.payload
    val adapter = eventAdapters.get(payload.getClass)

    // IdentityEventAdapter returns "" as manifest and normally the incoming PersistentRepr
    // doesn't have an assigned manifest, but when WriteMessages is sent directly to the
    // journal for testing purposes we want to preserve the original manifest instead of
    // letting IdentityEventAdapter clearing it out.
    if (adapter == IdentityEventAdapter || adapter.isInstanceOf[NoopWriteEventAdapter])
      Future.successful(repr)
    else {
      val manifest = adapter.manifest(payload)
      val adaptedPayload = adapter.toJournal(payload)
      adaptedPayload match {
        case future: Future[_] ⇒
          future.map(np ⇒ repr.withPayload(np).withManifest(manifest))
        case _ ⇒
          Future.successful(repr.withPayload(adaptedPayload).withManifest(manifest))
      }
    }
  }

  final val receiveWriteJournal: Actor.Receive = {
    // cannot be a val in the trait due to binary compatibility
    val replayDebugEnabled: Boolean = config.getBoolean("replay-filter.debug")

    {
      case WriteMessages(messages, persistentActor, actorInstanceId) ⇒
        val cctr = resequencerCounter
        resequencerCounter += messages.foldLeft(1)((acc, m) ⇒ acc + m.size)

        val atomicWriteCount = messages.count(_.isInstanceOf[AtomicWrite])
        val prepared: Future[immutable.Seq[AtomicWrite]] = preparePersistentBatch(messages)
        val writeResults = prepared.flatMap { aws ⇒
          try breaker.withCircuitBreaker(asyncWriteMessages(aws))
          catch { case NonFatal(e) ⇒ Future.failed(e) }
        }.recover {
          case t ⇒ messages.collect { case a: AtomicWrite ⇒ Failure(t) }
        }.map { results ⇒
          if (results.nonEmpty && results.size != atomicWriteCount)
            throw new IllegalStateException("asyncWriteMessages returned invalid number of results. " +
              s"Expected [${atomicWriteCount}], but got [${results.size}]")
          results
        }

        writeResults.onComplete {
          case Success(results) ⇒
            resequencer ! Desequenced(WriteMessagesSuccessful, cctr, persistentActor, self)

            val resultsIter =
              if (results.isEmpty) Iterator.fill(atomicWriteCount)(AsyncWriteJournal.successUnit)
              else results.iterator
            var n = cctr + 1
            messages.foreach {
              case a: AtomicWrite ⇒
                resultsIter.next() match {
                  case Success(_) ⇒
                    a.payload.foreach { p ⇒
                      resequencer ! Desequenced(WriteMessageSuccess(p, actorInstanceId), n, persistentActor, p.sender)
                      n += 1
                    }
                  case Failure(e) ⇒
                    a.payload.foreach { p ⇒
                      resequencer ! Desequenced(WriteMessageRejected(p, e, actorInstanceId), n, persistentActor, p.sender)
                      n += 1
                    }
                }

              case r: NonPersistentRepr ⇒
                resequencer ! Desequenced(LoopMessageSuccess(r.payload, actorInstanceId), n, persistentActor, r.sender)
                n += 1
            }

          case Failure(e) ⇒
            resequencer ! Desequenced(WriteMessagesFailed(e), cctr, persistentActor, self)
            var n = cctr + 1
            messages.foreach {
              case a: AtomicWrite ⇒
                a.payload.foreach { p ⇒
                  resequencer ! Desequenced(WriteMessageFailure(p, e, actorInstanceId), n, persistentActor, p.sender)
                  n += 1
                }
              case r: NonPersistentRepr ⇒
                resequencer ! Desequenced(LoopMessageSuccess(r.payload, actorInstanceId), n, persistentActor, r.sender)
                n += 1
            }
        }

      case r @ ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, persistentActor) ⇒
        val replyTo =
          if (isReplayFilterEnabled) context.actorOf(ReplayFilter.props(persistentActor, replayFilterMode,
            replayFilterWindowSize, replayFilterMaxOldWriters, replayDebugEnabled))
          else persistentActor

        val replayer = context.actorOf(Props(new Replayer(replyTo, fromSequenceNr)))

        val readHighestSequenceNrFrom = math.max(0L, fromSequenceNr - 1)
        /*
         * The API docs for the [[AsyncRecovery]] say not to rely on asyncReadHighestSequenceNr
         * being called before a call to asyncReplayMessages even tho it currently always is. The Cassandra
         * plugin does rely on this so if you change this change the Cassandra plugin.
         */
        val highestSequenceNr = breaker.withCircuitBreaker(asyncReadHighestSequenceNr(persistenceId, readHighestSequenceNrFrom))

        val journalReplay = highestSequenceNr.flatMap { highSeqNr ⇒
          val toSeqNr = math.min(toSequenceNr, highSeqNr)
          if (highSeqNr == 0L || fromSequenceNr > toSeqNr)
            Future.successful(highSeqNr)
          else {
            // Send replayed messages and replay result to persistentActor directly. No need
            // to resequence replayed messages relative to written and looped messages.
            // not possible to use circuit breaker here

            var future = Future.successful()

            asyncReplayMessages(persistenceId, fromSequenceNr, toSeqNr, max) { p: PersistentRepr ⇒
              if (!p.deleted) { // old records from 2.3 may still have the deleted flag

                val adapted = adaptFromJournal(p)


//                adaptFromJournal(p).foreach { adaptedPrs: immutable.Seq[PersistentRepr] ⇒
//                   This is on a future call back so we no longer are ordered with respect
//                   to messages we send from the journal actor. So we need to deal with replays
//                   over taking each other.
//                  replayer ! ReplayerMessages(p.sequenceNr, adaptedPrs)
                }
            }
              // FIXME, should be configurable as a slow async adapter will
              // cause this to increase

              // Hold off completing this future until the replayer has forwarded all
              // of the ReplayMessages. Meaning that the RecoverySuccess comes after
//              .flatMap(_ ⇒ replayer.ask(ReplayerAt(toSeqNr))(Timeout(5.seconds)))
              .map(_ ⇒ highSeqNr)
          }
        }

        journalReplay.map {
          highSeqNr ⇒ RecoverySuccess(highSeqNr)
        }.recover {
          case e ⇒ ReplayMessagesFailure(e)
        }.pipeTo(replyTo).foreach { _ ⇒
          if (publish) context.system.eventStream.publish(r)
          context.stop(replayer)
        }

      case d @ DeleteMessagesTo(persistenceId, toSequenceNr, persistentActor) ⇒
        breaker.withCircuitBreaker(asyncDeleteMessagesTo(persistenceId, toSequenceNr)) map { _ ⇒
          DeleteMessagesSuccess(toSequenceNr)
        } recover {
          case e ⇒ DeleteMessagesFailure(e, toSequenceNr)
        } pipeTo persistentActor onComplete { _ ⇒
          if (publish) context.system.eventStream.publish(d)
        }
    }
  }

  //#journal-plugin-api
  /**
   * Plugin API: asynchronously writes a batch (`Seq`) of persistent messages to the
   * journal.
   *
   * The batch is only for performance reasons, i.e. all messages don't have to be written
   * atomically. Higher throughput can typically be achieved by using batch inserts of many
   * records compared to inserting records one-by-one, but this aspect depends on the
   * underlying data store and a journal implementation can implement it as efficient as
   * possible. Journals should aim to persist events in-order for a given `persistenceId`
   * as otherwise in case of a failure, the persistent state may be end up being inconsistent.
   *
   * Each `AtomicWrite` message contains the single `PersistentRepr` that corresponds to
   * the event that was passed to the `persist` method of the `PersistentActor`, or it
   * contains several `PersistentRepr` that corresponds to the events that were passed
   * to the `persistAll` method of the `PersistentActor`. All `PersistentRepr` of the
   * `AtomicWrite` must be written to the data store atomically, i.e. all or none must
   * be stored. If the journal (data store) cannot support atomic writes of multiple
   * events it should reject such writes with a `Try` `Failure` with an
   * `UnsupportedOperationException` describing the issue. This limitation should
   * also be documented by the journal plugin.
   *
   * If there are failures when storing any of the messages in the batch the returned
   * `Future` must be completed with failure. The `Future` must only be completed with
   * success when all messages in the batch have been confirmed to be stored successfully,
   * i.e. they will be readable, and visible, in a subsequent replay. If there is
   * uncertainty about if the messages were stored or not the `Future` must be completed
   * with failure.
   *
   * Data store connection problems must be signaled by completing the `Future` with
   * failure.
   *
   * The journal can also signal that it rejects individual messages (`AtomicWrite`) by
   * the returned `immutable.Seq[Try[Unit]]`. It is possible but not mandatory to reduce
   * number of allocations by returning `Future.successful(Nil)` for the happy path,
   * i.e. when no messages are rejected. Otherwise the returned `Seq` must have as many elements
   * as the input `messages` `Seq`. Each `Try` element signals if the corresponding
   * `AtomicWrite` is rejected or not, with an exception describing the problem. Rejecting
   * a message means it was not stored, i.e. it must not be included in a later replay.
   * Rejecting a message is typically done before attempting to store it, e.g. because of
   * serialization error.
   *
   * Data store connection problems must not be signaled as rejections.
   *
   * It is possible but not mandatory to reduce number of allocations by returning
   * `Future.successful(Nil)` for the happy path, i.e. when no messages are rejected.
   *
   * Calls to this method are serialized by the enclosing journal actor. If you spawn
   * work in asynchronous tasks it is alright that they complete the futures in any order,
   * but the actual writes for a specific persistenceId should be serialized to avoid
   * issues such as events of a later write are visible to consumers (query side, or replay)
   * before the events of an earlier write are visible.
   * A PersistentActor will not send a new WriteMessages request before the previous one
   * has been completed.
   *
   * Please note that the `sender` field of the contained PersistentRepr objects has been
   * nulled out (i.e. set to `ActorRef.noSender`) in order to not use space in the journal
   * for a sender reference that will likely be obsolete during replay.
   *
   * Please also note that requests for the highest sequence number may be made concurrently
   * to this call executing for the same `persistenceId`, in particular it is possible that
   * a restarting actor tries to recover before its outstanding writes have completed. In
   * the latter case it is highly desirable to defer reading the highest sequence number
   * until all outstanding writes have completed, otherwise the PersistentActor may reuse
   * sequence numbers.
   *
   * This call is protected with a circuit-breaker.
   */
  def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]]

  /**
   * Plugin API: asynchronously deletes all persistent messages up to `toSequenceNr`
   * (inclusive).
   *
   * This call is protected with a circuit-breaker.
   * Message deletion doesn't affect the highest sequence number of messages, journal must maintain the highest sequence number and never decrease it.
   */
  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit]

  /**
   * Plugin API
   *
   * Allows plugin implementers to use `f pipeTo self` and
   * handle additional messages for implementing advanced features
   *
   */
  def receivePluginInternal: Actor.Receive = Actor.emptyBehavior
  //#journal-plugin-api

}

/**
 * INTERNAL API.
 */
private[persistence] object AsyncWriteJournal {
  val successUnit: Success[Unit] = Success(())

  final case class Desequenced(msg: Any, snr: Long, target: ActorRef, sender: ActorRef)
    extends NoSerializationVerificationNeeded

  class Resequencer extends Actor {
    import scala.collection.mutable.Map

    private val delayed = Map.empty[Long, Desequenced]
    private var delivered = 0L

    def receive = {
      case d: Desequenced ⇒ resequence(d)
    }

    @scala.annotation.tailrec
    private def resequence(d: Desequenced) {
      if (d.snr == delivered + 1) {
        delivered = d.snr
        d.target.tell(d.msg, d.sender)
      } else {
        delayed += (d.snr → d)
      }
      val ro = delayed.remove(delivered + 1)
      if (ro.isDefined) resequence(ro.get)
    }
  }
}
