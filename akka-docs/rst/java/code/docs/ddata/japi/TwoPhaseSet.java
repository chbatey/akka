/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.ddata.japi;

import java.util.HashSet;

import java.util.Set;

import akka.cluster.ddata.AbstractReplicatedData;
import akka.cluster.ddata.GSet;

//#twophaseset
public class TwoPhaseSet
  extends AbstractReplicatedData<TwoPhaseSet> {
  
  private final GSet<String> adds;
  private final GSet<String> removals;
  
  private TwoPhaseSet(GSet<String> adds, GSet<String> removals) {
    this.adds = adds;
    this.removals = removals;
  }
  
  public static TwoPhaseSet create() {
    return new TwoPhaseSet(GSet.create(), GSet.create());
  }

  public TwoPhaseSet add(String element) {
    return new TwoPhaseSet(adds.add(element), removals);
  }
  
  public TwoPhaseSet remove(String element) {
    return new TwoPhaseSet(adds, removals.add(element));
  }

  public Set<String> getElements() {
    Set<String> result = new HashSet<>(adds.getElements());
    result.removeAll(removals.getElements());
    return result;
  }

  public TwoPhaseSet merge(TwoPhaseSet that) {
    return new TwoPhaseSet(this.adds.merge(that.adds), 
        this.removals.merge(that.removals));
  }
}
//#twophaseset
