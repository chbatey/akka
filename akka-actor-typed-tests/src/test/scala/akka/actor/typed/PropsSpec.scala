/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.actor.typed

class PropsSpec extends TypedSpecSetup {

  val dispatcherFirst = DispatcherDefault(DispatcherFromConfig("pool"))

  "A Props" must {

    "must get first dispatcher" in {
      dispatcherFirst.firstOrElse[DispatcherSelector](null) should ===(dispatcherFirst)
    }

    "must yield all configs of some type" in {
      dispatcherFirst.allOf[DispatcherSelector] should ===(DispatcherSelector.default() :: DispatcherSelector.fromConfig("pool") :: Nil)
    }
  }
}
