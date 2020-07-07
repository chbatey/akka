/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import akka.actor._
import akka.testkit.ImplicitSender
import akka.testkit.TestActors

object HandshakeRetrySpec {
  val commonConfig = ConfigFactory.parseString(s"""
     akka.loglevel = DEBUG
     akka.remote.artery.advanced.handshake-timeout = 10s
     akka.remote.artery.advanced.aeron.image-liveness-timeout = 7s
  """).withFallback(ArterySpecSupport.defaultConfig)

}

class HandshakeRetrySpec extends ArteryMultiNodeSpec(HandshakeRetrySpec.commonConfig) with ImplicitSender {

  val portB = freePort()

  "Artery handshake" must {

    "be retried during handshake-timeout (no message loss)" in {
      println("log level " + system.classicSystem.settings.config.getString("akka.loglevel"))
      def sel = system.actorSelection(s"akka://systemB@localhost:$portB/user/echo")
      sel ! "hello"
      expectNoMessage(1.second)

      val systemB =
        newRemoteSystem(name = Some("systemB"), extraConfig = Some(s"akka.remote.artery.canonical.port = $portB"))
      println("log level " + systemB.classicSystem.settings.config.getString("akka.loglevel"))
      systemB.actorOf(TestActors.echoActorProps, "echo")

      expectMsg("hello")

      sel ! Identify(None)
      val remoteRef = expectMsgType[ActorIdentity].ref.get

      remoteRef ! "ping"
      expectMsg("ping")
    }

  }

}
