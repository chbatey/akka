/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.setup._
import akka.actor.typed._
import org.scalatest.wordspec._

class StubbedCluster extends Cluster {
 def isTerminated: Boolean = false
 def manager: akka.actor.typed.ActorRef[akka.cluster.typed.ClusterCommand] = ???
 def selfMember: akka.cluster.Member = ???
 def state: akka.cluster.ClusterEvent.CurrentClusterState = ???
 def subscriptions: akka.actor.typed.ActorRef[akka.cluster.typed.ClusterStateSubscription] = ???
}

class StubbedClusterSpec extends AnyWordSpec {

  val setup: ActorSystemSetup = ActorSystemSetup(
    ClusterSetup(_ => new StubbedCluster()) 
  )

  val guardianBehavior: Behavior[String] = ???

  val system = ActorSystem(guardianBehavior, "StubbedClusterSpec", setup)

}
