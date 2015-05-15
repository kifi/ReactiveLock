package com.kifi.reactivelock

import scala.concurrent.{ Future, Promise, ExecutionContext => EC }
import java.util.concurrent.locks.{ ReentrantLock, Lock }
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ReactiveLock(numConcurrent: Int = 1, maxQueueSize: Option[Int] = None) {
  require(numConcurrent > 0, "Concurrency degree must be strictly positive!")

  def this(numConcurrent: Int, maxQueueSize: Int) = this(numConcurrent, Some(maxQueueSize))

  private case class QueuedItem[T](runner: () => Future[T], promise: Promise[T], ec: EC)

  private val taskQueue = new ConcurrentLinkedQueue[QueuedItem[_]]()

  private val waitingCount = new AtomicInteger(0)

  private var runningCount: Int = 0

  private val lock: Lock = new ReentrantLock()

  //note that all this does inside of the synchronized block is start a future (in the worst case),
  //so actual synchonization is only held for a *very* short amount of time for each task
  private def dispatch(): Unit = lock.synchronized {
    if (runningCount < numConcurrent) {
      val candidate: Option[QueuedItem[_]] = Option(taskQueue.poll())
      candidate.foreach {
        case QueuedItem(runner, promise, ec) => {
          runningCount += 1
          waitingCount.decrementAndGet()
          val fut = runner()
          fut.onComplete { _ =>
            lock.synchronized {
              runningCount -= 1
              dispatch()
            }
          }(ec)
          promise.completeWith(fut)
        }
      }
    }
  }

  def withLock[T](f: => T)(implicit ec: EC): Future[T] = {
    withLock0 { () => Future { f } }
  }

  def withLockFuture[T](f: => Future[T])(implicit ec: EC): Future[T] = {
    //making sure the runner just starts a future and does nothing else (in case f does some other work, keeping dispatch very light)
    withLock0 { () => Future { f } flatMap { future => future } }
  }

  private def withLock0[T](runner: () => Future[T])(implicit ec: EC): Future[T] = {
    maxQueueSize foreach { max =>
      val taskQueueSize = taskQueue.size()
      if (taskQueueSize >= max) {
        throw new ReactiveLockTaskQueueFullException(s"Lock's queue size $taskQueueSize is at or more then max queue size $max. Rejecting task!")
      }
    }
    val p = Promise[T]
    taskQueue.add(QueuedItem[T](runner, p, ec))
    waitingCount.incrementAndGet()
    dispatch()
    p.future
  }

  def waiting: Int = waitingCount.get()

  def running: Int = runningCount

  def clear(): Unit = lock.synchronized {
    lazy val ex = new ReactiveLockTaskQueueClearedException("taskQueue is cleared")

    var candidate: QueuedItem[_] = taskQueue.poll()
    while (candidate != null) {
      waitingCount.decrementAndGet()
      candidate.promise.failure(ex)
      candidate = taskQueue.poll()
    }
  }

}

class ReactiveLockTaskQueueFullException(msg: String) extends Exception(msg) {
  override def fillInStackTrace = this
}

class ReactiveLockTaskQueueClearedException(msg: String) extends Exception(msg) {
  override def fillInStackTrace = this
}
