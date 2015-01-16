/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import akka.actor.OneForOneStrategy
import scala.annotation.tailrec
import scala.collection.immutable

/**
 * The behavior of an actor defines how it reacts to the messages that it
 * receives. The message may either be of the type that the Actor declares
 * and which is part of the [[ActorRef]] signature, or it may be a system
 * [[Signal]] that expresses a lifecycle event of either this actor or one of
 * its child actors.
 *
 * Behaviors can be formulated in a number of different ways, either by
 * creating a derived class or by employing factory methods like
 * [[Behavior.Simple]], [[Behavior.Static]], [[Behavior.Full]] etc.
 */
abstract class Behavior[T] { self ⇒
  def management(ctx: ActorContext[T], msg: Signal): Behavior[T]
  def message(ctx: ActorContext[T], msg: T): Behavior[T]

  def narrow[U <: T]: Behavior[U] = this.asInstanceOf[Behavior[U]]

  def widen[U >: T](matcher: PartialFunction[U, T]): Behavior[U] =
    new Behavior[U] { // TODO: optimize allocation-wise
      private def postProcess(ctx: ActorContext[U], behv: Behavior[T]): Behavior[U] =
        Behavior.canonicalize(ctx.asInstanceOf[ActorContext[T]], behv, self).widen(matcher)
      override def management(ctx: ActorContext[U], msg: Signal): Behavior[U] =
        postProcess(ctx, self.management(ctx.asInstanceOf[ActorContext[T]], msg))
      override def message(ctx: ActorContext[U], msg: U): Behavior[U] =
        if (matcher.isDefinedAt(msg))
          postProcess(ctx, self.message(ctx.asInstanceOf[ActorContext[T]], matcher(msg)))
        else ScalaDSL.Unhandled
      override def toString: String = s"${self.toString}.widen(${LN.forClass(matcher.getClass)})"
    }
}

/*
 * FIXME
 * 
 * Closing over ActorContext makes a Behavior immobile: it cannot be moved to
 * another context and executed there, and therefore it cannot be replicated or
 * forked either.
 */

// FIXME to be replaced by extension?
private object LN extends akka.util.LineNumbers

/**
 * System signals are notifications that are generated by the system and
 * delivered to the Actor behavior in a reliable fashion (i.e. they are
 * guaranteed to arrive in contrast to the at-most-once semantics of normal
 * Actor messages).
 */
sealed trait Signal
/**
 * Lifecycle signal that is fired upon creation of the Actor. This will be the
 * first message that the actor processes.
 */
final case object PreStart extends Signal
/**
 * Lifecycle signal that is fired upon restart of the Actor before replacing
 * the behavior with the fresh one (i.e. this signal is received within the
 * behavior that failed).
 */
final case class PreRestart(failure: Throwable) extends Signal
/**
 * Lifecycle signal that is fired upon restart of the Actor after replacing
 * the behavior with the fresh one (i.e. this signal is received within the
 * fresh replacement behavior).
 */
final case class PostRestart(failure: Throwable) extends Signal
/**
 * Lifecycle signal that is fired after this actor and all its child actors
 * (transitively) have terminated. The [[Terminated]] signal is only sent to
 * registered watchers after this signal has been processed.
 *
 * <b>IMPORTANT NOTE:</b> if the actor terminated by switching to the
 * [[Behavior$.Stopped]] behavior then this signal will be ignored (i.e. the
 * Stopped behvavior will do nothing in reaction to it).
 */
final case object PostStop extends Signal
/**
 * Lifecycle signal that is fired when a direct child actor fails. The child
 * actor will be suspended until its fate has been decided. The decision is
 * communicated by returning the next behavior wrapped in one of
 * [[Failed$.Resume]], [[Failed$.Restart]], [[Failed$.Stop]]
 * or [[Failed$.Escalate]]. If this is not
 * done then the default behavior is to escalate the failure, which amounts to
 * failing this actor with the same exception that the child actor failed with.
 */
final case class Failed(cause: Throwable, child: ActorRef[Nothing]) extends Signal
/**
 * The actor can register for a notification in case no message is received
 * within a given time window, and the signal that is raised in this case is
 * this one. See also [[ActorContext#setReceiveTimeout]].
 */
final case object ReceiveTimeout extends Signal
/**
 * Lifecycle signal that is fired when an Actor that was watched has terminated.
 * Watching is performed by invoking the
 * [[akka.typed.ActorContext!.watch[U]* watch]] method. The DeathWatch service is
 * idempotent, meaning that registering twice has the same effect as registering
 * once. Registration does not need to happen before the Actor terminates, a
 * notification is guaranteed to arrive after both registration and termination
 * have occurred. Termination of a remote Actor can also be effected by declaring
 * the Actor’s home system as failed (e.g. as a result of being unreachable).
 */
final case class Terminated(ref: ActorRef[Nothing]) extends Signal

/**
 * The parent of an actor decides upon the fate of a failed child actor by
 * encapsulating its next behavior in one of the four wrappers defined within
 * this class.
 *
 * Failure responses have an associated precedence that ranks them, which is in
 * descending importance:
 *
 *  - Escalate
 *  - Stop
 *  - Restart
 *  - Resume
 */
object Failed {

  /**
   * Failure responses are in some cases communicated by using the companion
   * objects of the wrapper behaviors, see the [[StepWise]] behavior for an
   * example.
   */
  sealed trait Decision

  case object NoFailureResponse extends Decision

  /**
   * Resuming the child actor means that the result of processing the message
   * on which it failed is just ignored, the previous state will be used to
   * process the next message. The message that triggered the failure will not
   * be processed again.
   */
  case object Resume extends Decision

  /**
   * Restarting the child actor means resetting its behavior to the initial
   * one that was provided during its creation (i.e. the one which was passed
   * into the [[Props]] constructor). The previously failed behavior will
   * receive a [[PreRestart]] signal before this happens and the replacement
   * behavior will receive a [[PostRestart]] signal afterwards.
   */
  case object Restart extends Decision

  /**
   * Stopping the child actor will free its resources and eventually
   * (asynchronously) unregister its name from the parent. Completion of this
   * process can be observed by watching the child actor and reacting to its
   * [[Terminated]] signal.
   */
  case object Stop extends Decision

  /**
   * The default response to a failure in a child actor is to escalate the
   * failure, entailing that the parent actor fails as well. This is equivalent
   * to an exception unwinding the call stack, but it applies to the supervision
   * hierarchy instead.
   */
  case object Escalate extends Decision

}

object Behavior {

  /**
   * INTERNAL API.
   */
  private[akka] object emptyBehavior extends Behavior[Any] {
    override def management(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = ScalaDSL.Unhandled
    override def message(ctx: ActorContext[Any], msg: Any): Behavior[Any] = ScalaDSL.Unhandled
    override def toString = "Empty"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object ignoreBehavior extends Behavior[Any] {
    override def management(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = ScalaDSL.Same
    override def message(ctx: ActorContext[Any], msg: Any): Behavior[Any] = ScalaDSL.Same
    override def toString = "Ignore"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object unhandledBehavior extends Behavior[Nothing] {
    override def management(ctx: ActorContext[Nothing], msg: Signal): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def message(ctx: ActorContext[Nothing], msg: Nothing): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Unhandled"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object sameBehavior extends Behavior[Nothing] {
    override def management(ctx: ActorContext[Nothing], msg: Signal): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def message(ctx: ActorContext[Nothing], msg: Nothing): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Same"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object stoppedBehavior extends Behavior[Nothing] {
    override def management(ctx: ActorContext[Nothing], msg: Signal): Behavior[Nothing] = {
      assert(msg == PostStop, s"stoppedBehavior received $msg (only PostStop is expected)")
      this
    }
    override def message(ctx: ActorContext[Nothing], msg: Nothing): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Stopped"
  }

  /**
   * Given a possibly wrapped behavior (see [[Behavior.Wrapper]]) and a
   * “current” behavior (which defines the meaning of encountering a [[#Same]]
   * behavior) this method unwraps the behavior such that the innermost behavior
   * is returned, i.e. it removes the decorations.
   */
  def canonicalize[T](ctx: ActorContext[T], behavior: Behavior[T], current: Behavior[T]): Behavior[T] =
    behavior match {
      case `sameBehavior`      ⇒ current
      case `unhandledBehavior` ⇒ current
      case other               ⇒ other
    }

  def isAlive[T](behavior: Behavior[T]): Boolean = behavior ne stoppedBehavior

  def isUnhandled[T](behavior: Behavior[T]): Boolean = behavior eq unhandledBehavior
}

