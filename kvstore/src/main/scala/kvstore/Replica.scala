package kvstore

import akka.actor.{ OneForOneStrategy, Props, ActorRef, Actor }
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.util.Timeout

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  import ConsistencyManager._
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  var keyValueStorage = Map.empty[String, String]
  
  var managers = Map.empty[String, ActorRef]

  private var expectedSnapshot = 0

  val persistence = context.actorOf(persistenceProps)
  

  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = handleGetOperation orElse {
    case Insert(key, value, id) => {
      keyValueStorage = keyValueStorage + (key -> value)
      leaderManager(key) ! Modification(sender, id, key, Some(value))
    }

    case Remove(key, id) => {
      keyValueStorage = keyValueStorage - key
      leaderManager(key) ! Modification(sender, id, key, None)
    }

    case Replicas(replicas) => {
      whenReplicasChange(replicas)
    }

    case _ => {}
  }

  /* TODO Behavior for the replica role. */
  val replica: Receive = handleGetOperation orElse {
    case Snapshot(key, valueOption, id) => {
      if (expectedSnapshot == id) {
        keyValueStorage = valueOption.fold(keyValueStorage - key)(keyValueStorage.updated(key, _))
        expectedSnapshot += 1
      }

      if (id < expectedSnapshot) {
        replicaManager(key) ! Modification(sender, id, key, valueOption)
      }
    }
  }

  override def preStart() {
    arbiter ! Join
  }

  private def handleGetOperation: Receive = {
    case Get(key, id) => {
      sender ! GetResult(key, keyValueStorage.get(key), id)
    }
  }

  private def manager(key: String)(ackBuilder: (Long, String) => Any) =
    managers.get(key).getOrElse {
      val manager = context.actorOf(Props(classOf[ConsistencyManager],
                                          persistence,
                                          secondaries.values.toSet,
                                          ackBuilder))

      managers += (key -> manager)
      manager
    }

  private def leaderManager(key: String) =
    manager(key)((id: Long, key: String) => OperationAck(id))

  private def replicaManager(key: String) =
    manager(key)((seq: Long, key: String) => SnapshotAck(key, seq))

  private def whenReplicasChange(replicas: Set[ActorRef]) =  {
    def isGone(replica: ActorRef) = {
      secondaries.contains(replica) && !replicas.contains(replica)
    }

    def isNew(replica: ActorRef) = {
      !secondaries.contains(replica) && replicas.contains(replica)
    }

    def handleReplicaRemoval(replica: ActorRef) = {
      managers.values.foreach(_ ! Gone(secondaries(replica)))
      context.stop(secondaries(replica))
      secondaries -= replica
    }

    def handleReplicaCreation(replica: ActorRef) = {
      val replicator = context.actorOf(Props(classOf[Replicator], replica))

      managers.values.foreach(_ ! Entered(replicator))
      secondaries += (replica -> replicator)

      keyValueStorage.foldLeft(0) {
        case (seq, (key, value)) => {
          replicator ! Replicate(key, Some(value), seq)
          seq - 1
        }
      }
    }

    for (replica <- (replicas - self) ++ secondaries.keys.toSet) {
      if (isGone(replica)) {
        handleReplicaRemoval(replica)
      } else if (isNew(replica)) {
        handleReplicaCreation(replica)
      }
    }
  }
}

