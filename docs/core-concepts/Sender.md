---
sidebar_position: 1
---

# Sender

Sender is a basic abstraction over the possibility to send single message. It's simplified definition looks like this:

```scala
trait Sender[F[_], -A] extends (A => F[Unit]) with Serializable {

  /** Sends a single message.
    */
  def sendOne(msg: A): F[Unit]

  /** Alias for [[sendOne]]. Thanks to this, you can pass a Sender where a function type is expected.
    */
  def apply(msg: A): F[Unit] = sendOne(msg)

}
```

As you can see `Sender` is basically a type of function of `A => F[Unit]` shape. It comes with many combinators for mapping, filtering and combining `Sender`s, as well as `Functor` and `Monoid` instances.

Please refer to [`Sender.scala` sources](https://github.com/ocadotechnology/pass4s/blob/main/kernel/src/main/scala/com/ocadotechnology/pass4s/kernel/Sender.scala) and the scaladocs.
