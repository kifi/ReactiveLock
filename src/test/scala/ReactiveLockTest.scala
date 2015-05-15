package com.kifi.reactivelock

import org.specs2.mutable.Specification

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ TimeUnit, CountDownLatch }

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global


class ReactiveLockTest extends Specification {

  "ReactiveLock" should {

    "obey concurrency limit 1 for synchronous tasks" in {
      val testLock = new ReentrantLock()
      val rLock = new ReactiveLock()

      val taskCompletionPreventionLock = new ReentrantLock()
      taskCompletionPreventionLock.lock()

      var output: Seq[Int] = Seq.empty

      val fut1 = rLock.withLock {
        taskCompletionPreventionLock.tryLock(10, TimeUnit.SECONDS)
        output = output :+ 1
        val held = testLock.tryLock()
        if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
        Thread.sleep(20) //making sure this task takes a bit of time to provoke races if there are any
        testLock.unlock()
        output = output :+ 1
      }

      val fut2 = rLock.withLock {
        output = output :+ 2
        val held = testLock.tryLock()
        if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
        testLock.unlock()
        output = output :+ 2
      }

      val fut3 = rLock.withLock {
        output = output :+ 3
        val held = testLock.tryLock()
        if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
        testLock.unlock()
        output = output :+ 3
      }

      val fut4 = rLock.withLock {
        output = output :+ 4
        val held = testLock.tryLock()
        if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
        testLock.unlock()
        output = output :+ 4
      }

      val allFuture = Future.sequence(Seq(fut1, fut2, fut3, fut4))

      rLock.running === 1
      rLock.waiting === 3
      taskCompletionPreventionLock.unlock()

      Await.result(allFuture, Duration(30, "seconds"))
      allFuture.value.get.isSuccess === true
      output === Seq(1, 1, 2, 2, 3, 3, 4, 4)
    }

    "not allow more then max queue size tasks to be in the queue" in {
      val testLock = new ReentrantLock()
      val rLock = new ReactiveLock(1, Some(2))

      var output: Seq[Int] = Seq.empty

      val fut1 = rLock.withLockFuture {
        Future {
          output = output :+ 1
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          Thread.sleep(40) //making sure this task takes a bit of time to provoke races if there are any
          testLock.unlock()
          output = output :+ 1
          true
        }
      }

      val fut2 = rLock.withLockFuture {
        Future {
          output = output :+ 2
          val held = testLock.tryLock()
          Thread.sleep(40) //making sure this task takes a bit of time to provoke races if there are any
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          testLock.unlock()
          output = output :+ 2
        }
      }

      val fut3 = rLock.withLockFuture {
        Future {
          output = output :+ 3
          val held = testLock.tryLock()
          Thread.sleep(40) //making sure this task takes a bit of time to provoke races if there are any
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          testLock.unlock()
          output = output :+ 3
        }
      }

      try {
        rLock.withLockFuture {
          Future {
            output = output :+ 4
          }
        }
        failure("task should have not been executed!")
      } catch {
        case e: Exception => //good!
      }

      val allFuture = Future.sequence(Seq(fut1, fut2, fut3))
      Await.result(allFuture, Duration(50, "seconds"))
      allFuture.value.get.isSuccess === true
      output === Seq(1, 1, 2, 2, 3, 3)
    }

    "obey concurrency limit 1 for asynchronous tasks" in {
      val testLock = new ReentrantLock()
      val rLock = new ReactiveLock()

      var output: Seq[Int] = Seq.empty

      val fut1 = rLock.withLockFuture {
        Future {
          output = output :+ 1
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          Thread.sleep(20) //making sure this task takes a bit of time to provoke races if there are any
          testLock.unlock()
          output = output :+ 1
          true
        }
      }

      val fut2 = rLock.withLockFuture {
        Future {
          output = output :+ 2
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          testLock.unlock()
          output = output :+ 2
        }
      }

      val fut3 = rLock.withLockFuture {
        Future {
          output = output :+ 3
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          testLock.unlock()
          output = output :+ 3
        }
      }

      val fut4 = rLock.withLockFuture {
        Future {
          output = output :+ 4
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          testLock.unlock()
          output = output :+ 4
        }
      }

      val allFuture = Future.sequence(Seq(fut1, fut2, fut3, fut4))
      Await.result(allFuture, Duration(30, "seconds"))
      allFuture.value.get.isSuccess === true
      output === Seq(1, 1, 2, 2, 3, 3, 4, 4)
    }

    "obey concurrecy limit 2 exactly (i.e. two tasks running concurrently) for synchronous tasks" in {
      val runningCounter = new AtomicInteger(0)
      val rLock = new ReactiveLock(2)
      val taskCompletionPreventionLock = new CountDownLatch(1)

      val fut1 = rLock.withLock {
        val runningCount = runningCounter.incrementAndGet()
        runningCount must be_<=(2)
        runningCount must be_>=(1)
        rLock.running must be_<=(2)
        taskCompletionPreventionLock.await(10, TimeUnit.SECONDS)
        runningCounter.decrementAndGet()
      }

      val fut2 = rLock.withLock {
        val runningCount = runningCounter.incrementAndGet()
        Thread.sleep(20) //making sure this task takes a bit of time to provoke races if there are any
        runningCount === 2
        rLock.running === 2
        rLock.running must be_<=(2)
        taskCompletionPreventionLock.countDown()
        runningCounter.decrementAndGet()
      }

      val fut3 = rLock.withLock {
        val runningCount = runningCounter.incrementAndGet()
        runningCount must be_<=(2)
        runningCount must be_>=(1)
        rLock.running must be_<=(2)
        runningCounter.decrementAndGet()
      }

      val fut4 = rLock.withLock {
        val runningCount = runningCounter.incrementAndGet()
        runningCount must be_<=(2)
        runningCount must be_>=(1)
        rLock.running must be_<=(2)
        runningCounter.decrementAndGet()
      }

      val allFuture = Future.sequence(Seq(fut1, fut2, fut3, fut4))
      Await.result(allFuture, Duration(30, "seconds"))
      allFuture.value.get.isSuccess === true

    }

  }
}
