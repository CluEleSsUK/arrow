package arrow.effects.internal

import arrow.core.Either
import arrow.effects.Promise
import java.util.concurrent.atomic.AtomicReference

internal class Promise<A> {

  private val state: AtomicReference<State<A>> = AtomicReference()

  val get: A get() {
    tailrec fun loop(): A {
      val st = state.get()
      return when (st) {
        is State.Pending<A> -> loop()
        is State.Full -> st.value
        is State.Error -> throw st.throwable
      }
    }

    tailrec fun calculateNewState(): Unit {
      val oldState = state.get()
      val newState = when (oldState) {
        is State.Pending<A> -> State.Pending(oldState.joiners)
        is State.Full -> oldState
        is State.Error -> oldState
      }
      return if (state.compareAndSet(oldState, newState)) Unit else calculateNewState()
    }

    calculateNewState()
    return loop()
  }

  fun complete(a: A): Unit {
    tailrec fun calculateNewState(): Unit {
      val oldState = state.get()
      val newState = when (oldState) {
        is State.Pending<A> -> State.Full(a)
        is State.Full -> oldState
        is State.Error -> oldState
      }
      return if (state.compareAndSet(oldState, newState)) Unit else calculateNewState()
    }
    val oldState = state.get()
    when (oldState) {
      is State.Pending -> calculateNewState().let { Unit }
      is State.Full -> throw Promise.AlreadyFulfilled
      is State.Error -> throw Promise.AlreadyFulfilled
    }
  }

  internal sealed class State<out A> {
    data class Pending<A>(val joiners: List<(Either<Throwable, A>) -> Unit>) : State<A>()
    data class Full<A>(val value: A) : State<A>()
    data class Error<A>(val throwable: Throwable) : State<A>()
  }

}