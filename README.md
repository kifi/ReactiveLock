Reactive programming and locking patterns don't go together? Think again. ReactiveLock, as the name might suggest, is a Reactive Lock. I.e. it is a construct that allows you to employ patterns similar to classic thread/lock concurrency without actually blocking any threads, thus making it possible to use efficient small thread pools.

It's great for transitioning a codebase from a classic threads & locks pattern to a more reactive environment and it's also great for transitioning a skill set from those classic patterns.

Even with perfectly thread safe code a `ReactiveLock` can come in really handy to simply throttle a piece of code. There are many scenarios where reducing concurrency can actually increase throughput, e.g. when competing for IO resources.

There are quite a few parallels to Actors here, but the mental model is simpler (even if arguably less powerful) especially if coming from Java.

# Usage

###Creation
Creating a lock is dead simple


A lock that will let only one thread execute code in it at a time:

```scala
val lock = new ReactiveLock() //Allow only one thread at a time
```

A lock that will let a specified number of threads execute under the lock:

```scala
val lock = new ReactiveLock(concurrency: Int) //Allow only one threads actually executing at a time
```

A lock that will let a specified number of threads execute under the lock, and will only allow up to `limit` waiting future. Additional entries into the lock will throw a `ReactiveLockTaskQueueFullException`.

```scala
val lock = new ReactiveLock(concurrency: Int, limit: Int) 
```


###Locking

Suppose you have a function 

```scala
def reallyNotReetrant(...): T = {
	//some not thread safe computation
	//or an expensive computation that needs to be throttled
}
```
which needs concurrency control. In a classic setting you might do something like this

```scala
def reallyNotReetrant(...): T = synchronized { ... }
```
So, while the function is running every new thread calling it will block. This is bad if you have a small thread pool - which is generally desireable - as it can quickly lead to starvation if this function gets called a lot, thus breaking possibly completely unrelated parts of your code because no threads are available. 
With a `ReactiveLock` this becomes something like this

```scala
def reallyNotReetrant(...): Future[T] = lock.withLock { ... }
```
Calls to `reallyNotReentrant` will now __return immeditaly__ with a Future, however, that __Future will only actually execute when the lock is available__.

Note that this only controlls the concurrency of the thread executing the function. Just as with classic locks, if more concurrency is created, e.g. by spawning another future, that is not under the lock.

If you have a function returning a future, like so

```scala
def asyncFun(...): Future[T] = { /*some code*/ }
```
and you would like to not only control the concurrency of the function returning the future, but also of the future itself, you can use

```scala
def asyncFun(...): Future[T] = lock.withLockFuture { /*some code*/ }
```

So the reactive lock nicely integrates as a concurrency control in (some) existing reactive patterns.

Note that both `withLock` and `withLockFuture` require an implicit execution context in scope.

###Utilities

A `ReactiveLock` instance has `waiting` and `running` properties (both `Int`) for monitoring. You can also `clear()` the lock, causing all waiting futures to be completed with a `ReactiveLockTaskQueueClearedException`. Particularly useful in testing.

#Installation

You can get ReactiveLock from maven central. The artifact is `reactivelock_2.11` and the group id is `com.kifi`.  
The current version is `1.0.0`. For example, if you are using __sbt__, just add this to your dependencies:

```
"com.kifi" % "reactivelock_2.11" % "1.0.0"
```

All classes are in in `com.kifi.reactivelock`.
