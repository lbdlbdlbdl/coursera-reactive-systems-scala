/**
  * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
  */
package actorbintree

import akka.actor._

import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef

    def id: Int

    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {

  import BinaryTreeSet._
  import actorbintree.BinaryTreeNode.{CopyFinished, CopyTo}


  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional (used to stash incoming operations during garbage collection)
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case op: Operation => root ! op
    case GC =>
      val newRoot = createRoot
      root ! CopyTo(newRoot)
      context.become(garbageCollecting(newRoot))

  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case op: Operation => pendingQueue = pendingQueue :+ op
    case CopyFinished =>
      root ! PoisonPill
      root = newRoot
      pendingQueue.foreach(root ! _)
      pendingQueue = Queue.empty[Operation]
      context.become(normal)
  }
}

object BinaryTreeNode {

  trait Position

  case object Left extends Position

  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)

  /**
    * Acknowledges that a copy has been completed. This message should be sent
    * from a node to its parent, when this node and all its children nodes have
    * finished being copied.
    */
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode], elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {

  import BinaryTreeNode._
  import actorbintree.BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  private def lOrRSubtree(compareTo: Int): Position = if (this.elem < compareTo) Left else Right

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case op@Insert(requester: ActorRef, id: Int, elem: Int) =>
      if (this.elem == elem) {
        this.removed = false
        requester ! OperationFinished(id)
      }
      else {
        val position = lOrRSubtree(elem)
        if (subtrees contains position) //todo refactoring
          subtrees(position) ! op
        else {
          subtrees += position -> context.actorOf(BinaryTreeNode.props(elem, initiallyRemoved = false))
          requester ! OperationFinished(id)
        }
      }
    case op@Contains(requester: ActorRef, id: Int, elem: Int) =>
      if (this.elem == elem && !this.removed)
        requester ! ContainsResult(id, result = true)
      else {
        val position = lOrRSubtree(elem)
        if (subtrees contains position)
          subtrees(position) ! op
        else
          requester ! ContainsResult(id, result = false)
      }
    case op@Remove(requester: ActorRef, id: Int, elem: Int)  =>
      if (this.elem == elem) {
        this.removed = true
        requester ! OperationFinished(id)
      }
      else {
        val position = lOrRSubtree(elem)
        if (subtrees contains position) // here `topNode ! Contains ...` could be...
          subtrees(position) ! op
        else
          requester ! OperationFinished(id)
      }
    case op@CopyTo(destination: ActorRef) =>
      val childs = context.children.toSet + self
      context.become(copying(childs))

      if (!this.removed)
        destination ! Insert(self, this.elem, this.elem)
      else
        self ! OperationFinished(this.elem)

      context.children.foreach(_ ! op)
  }


  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef]): Receive = {
    case OperationFinished(_) => finishOrBecomeCopying(expected - self)
    case CopyFinished         => finishOrBecomeCopying(expected - sender())
  }

  private def finishOrBecomeCopying(expected: Set[ActorRef]): Unit =
    if (expected.isEmpty)
      context.parent ! CopyFinished
    else
      context.become(copying(expected))

}
