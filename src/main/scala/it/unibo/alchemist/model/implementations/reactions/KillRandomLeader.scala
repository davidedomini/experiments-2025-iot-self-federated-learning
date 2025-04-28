package it.unibo.alchemist.model.implementations.reactions

import it.unibo.alchemist.model.{Environment, Node, Position, TimeDistribution}
import it.unibo.alchemist.model.molecules.SimpleMolecule
import scala.jdk.CollectionConverters.IteratorHasAsScala
import it.unibo.scafi.Sensors
import scala.util.Random

class KillRandomLeader[T, P <: Position[P]](
                                             environment: Environment[T, P],
                                             timeDistribution: TimeDistribution[T],
                                             seed: Int,
                                             toKill: Int
                                           ) extends AbstractGlobalReaction(environment, timeDistribution){

  private val random = new Random(seed)
  private var executed = false

  override protected def executeBeforeUpdateDistribution(): Unit = {
    if (!executed) {
      executed = true
      val leaders = findLeaders(environment)
      val toBeKilled = random.shuffle(leaders).take(toKill)
      toBeKilled.foreach {
        killing =>
          println(s"[DEBUG] killing node ${killing.getId}")
          //environment.getSimulation.schedule(() => environment.removeNode(killing))
          killing.setConcentration(new SimpleMolecule(Sensors.isDown), true.asInstanceOf[T])
      }
    }
  }

  private def findLeaders(environment: Environment[T, P]): List[Node[T]] =
    environment
      .getNodes
      .iterator()
      .asScala
      .toList
      .filter(_.getConcentration(new SimpleMolecule(Sensors.isAggregator)).asInstanceOf[Boolean])

}