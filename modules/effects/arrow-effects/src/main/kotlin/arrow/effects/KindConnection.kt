package arrow.effects

import arrow.Kind
import arrow.effects.typeclasses.MonadDefer
import arrow.typeclasses.Applicative
import java.util.concurrent.atomic.AtomicReference

typealias CancelToken<F> = Kind<F, Unit>

/**
 * Connection for kinded type [F].
 *
 * A connection is represented by a composite of [cancel] functions,
 * [cancel] is idempotent and all methods are thread-safe & atomic.
 *
 * The cancellation functions are maintained in a stack and executed in a FIFO order.
 */
sealed class KindConnection<F> {

  /**
   * Cancels all work represented by this reference.
   *
   * Guaranteed idempotency - calling it multiple times should have the same side-effect as calling it only
   * once. Implementations of this method should also be thread-safe.
   */
  abstract fun cancel(): CancelToken<F>

  abstract fun isCanceled(): Boolean

  /**
   * Pushes a cancellation function, or token, meant to cancel and cleanup resources.
   * These functions are kept inside a stack, and executed in FIFO order on cancellation.
   */
  abstract fun push(token: CancelToken<F>): Unit

  /**
   * Pushes a pair of [KindConnection] on the stack, which on cancellation will get trampolined. This is useful in
   * race for example, because combining a whole collection of tasks, two by two, can lead to building a
   * cancelable that's stack unsafe.
   */
  abstract fun pushPair(lh: KindConnection<F>, rh: KindConnection<F>): Unit

  /**
   * Pops a cancelable reference from the FIFO stack of references for this connection.
   * A cancelable reference is meant to cancel and cleanup resources.
   *
   * @return the cancelable reference that was removed.
   */
  abstract fun pop(): CancelToken<F>

  /**
   * Tries to reset an [KindConnection], from a cancelled state, back to a pristine state, but only if possible.
   *
   * @return true on success, false if there was a race condition (i.e. the connection wasn't cancelled) or if
   * the type of the connection cannot be reactivated.
   */
  abstract fun tryReactivate(): Boolean

  companion object {

    operator fun <F> invoke(MD: MonadDefer<F>): KindConnection<F> = DefaultKindConnection(MD)

    fun <F> uncancelable(FA: Applicative<F>): KindConnection<F> = Uncancelable(FA)
  }

  /**
   * Reusable [KindConnection] reference that cannot be canceled.
   */
  private class Uncancelable<F>(FA: Applicative<F>) : KindConnection<F>(), Applicative<F> by FA {
    override fun cancel(): CancelToken<F> = unit()
    override fun isCanceled(): Boolean = false
    override fun push(token: CancelToken<F>) = Unit
    override fun pop(): CancelToken<F> = unit()
    override fun tryReactivate(): Boolean = true
    override fun pushPair(lh: KindConnection<F>, rh: KindConnection<F>): Unit = Unit
  }

  /**
   * Default [KindConnection] implementation.
   */
  private class DefaultKindConnection<F>(MD: MonadDefer<F>) : KindConnection<F>(), MonadDefer<F> by MD {
    private val state = AtomicReference(emptyList<CancelToken<F>>())

    override fun cancel(): CancelToken<F> = defer {
      state.getAndSet(null).let { stack ->
        println("DefaultKindConnection#cancel($stack)")
        when {
          stack == null || stack.isEmpty() -> unit()
          else -> stack.cancelAll()
        }
      }
    }

    override fun isCanceled(): Boolean = state.get() == null

    override tailrec fun push(token: CancelToken<F>): Unit = when (val list = state.get()) {
      null -> if (!state.compareAndSet(list, listOf(token))) push(token) else Unit
      else -> if (!state.compareAndSet(list, listOf(token) + list)) push(token) else Unit
    }

    override fun pushPair(lh: KindConnection<F>, rh: KindConnection<F>): Unit =
      push(listOf(lh.cancel(), rh.cancel()).cancelAll())

    override tailrec fun pop(): CancelToken<F> {
      val state = state.get()
      return when {
        state == null || state.isEmpty() -> unit()
        else -> if (!this.state.compareAndSet(state, state.drop(1))) pop()
        else state.first()
      }
    }

    override fun tryReactivate(): Boolean =
      state.compareAndSet(null, emptyList())

    private fun List<CancelToken<F>>.cancelAll(): CancelToken<F> = defer {
      fold(unit()) { acc, f -> f.flatMap { acc } }
    }

    override fun toString(): String = state.get().toString()

  }

}