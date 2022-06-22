---
sidebar_position: 2
---

# Consumer

Consumer is an abstraction for continous process of executin logic upon recieving a message.

It's defined as a function in following shape: `(A => F[Unit]) => F[Unit]`. This means a function that:
- takes an argument of type `A => F[Unit]` - think of it as the processing logic type
- returns `F[Unit]` means that consuming itself is only a side effect and yiealds no real value

```scala
trait Consumer[F[_], +A] extends ((A => F[Unit]) => F[Unit]) with Serializable { self =>
  /** Starts the consumer, passing every message through the processing function `f`. Think of it like of an `evalMap` on [[Stream]] or
    * `use` on [[cats.effect.Resource]].
    */
  def consume(f: A => F[Unit]): F[Unit]

  /** Starts the consumer, but allows the processing function `f` to be in a different effect than that of the consumer's. A `commit`
    * function also needs to be passed - it will be used after every message.
    */
  def consumeCommit[T[_]](commit: T[Unit] => F[Unit])(f: A => T[Unit]): F[Unit] = self.consume(f andThen commit)
}
```

To start a consumer, you need a function that will handle messages of type `A` and return effects in `F[_]`. As you can see in the example above, the consumer can also be transactional, meaning it can perform an action in one effect and then translate the result to the other. It's especially useful when you wan to perform database operations in `ConnectionIO[_]` while your application effect is `IO[_]`.

The end user usually doesn't instantiate the `Consumer` directly. Instead they would usually get one from [`Broker`](broker) or [`MessageProcessor`](../modules/message-processor).


This abstraction comes with a lot of useful manipulators as well as `Semigroup`, `Monoid`, `Functor` and `Monad` instances. Please refer to [`Consumer.scala` sources](https://github.com/ocadotechnology/pass4s/blob/main/kernel/src/main/scala/com/ocadotechnology/pass4s/kernel/Consumer.scala) and the scaladocs.
