package zio.stream

import StreamUtils.nPulls
import ZStream.Pull

import zio._
import zio.test.Assertion.{ equalTo, isFalse, isTrue }
import zio.test._

object StreamPullSafetySpec extends ZIOBaseSpec {

  def spec = suite("StreamPullSafetySpec")(combinators, constructors)

  def combinators = suite("Combinators")(
    testM("Stream.++ is safe to pull again") {
      ((Stream(1) ++ Stream.fail("Ouch 2")) ++ (Stream.fail("Ouch 3") ++ Stream(4)) ++ Stream.fail("Ouch 5")).process
        .use(nPulls(_, 7))
        .map(
          assert(_)(
            equalTo(
              List(
                Right(1),
                Left(Some("Ouch 2")),
                Left(Some("Ouch 3")),
                Right(4),
                Left(Some("Ouch 5")),
                Left(None),
                Left(None)
              )
            )
          )
        )
    },
    suite("Stream.aggregate")(
      testM("is safe to pull again after success") {
        Stream(1, 2, 3, 4, 5, 6)
          .aggregate(ZSink.collectAllN[Int](2).map(_.sum))
          .process
          .use(nPulls(_, 5))
          .map(assert(_)(equalTo(List(Right(3), Right(7), Right(11), Left(None), Left(None)))))
      },
      testM("is safe to pull again after upstream failure") {
        (Stream(1, 2) ++ Stream.failNow("Ouch") ++ Stream(3, 4))
          .aggregate(ZSink.collectAllN[Int](2).map(_.sum))
          .process
          .use(nPulls(_, 5))
          .map(assert(_)(equalTo(List(Right(3), Left(Some("Ouch")), Right(7), Left(None), Left(None)))))
      },
      testM("is safe to pull again after initial failure") {
        def initFailureSink(ref: Ref[(Int, Boolean)]): Sink[String, Nothing, Int, String] =
          new Sink[String, Nothing, Int, String] {
            type State = Option[Int]

            def initial: IO[String, State] =
              ref.modify {
                case (n, failed) =>
                  if (failed) (UIO.succeedNow(None), (n + 1, false)) else (IO.failNow("Ouch"), (n, true))
              }.flatten

            def step(s: State, a: Int): UIO[State] = UIO.succeedNow(Some(a))

            def extract(s: State): IO[String, (String, Chunk[Nothing])] =
              IO.fromOption(s).map(n => (n.toString, Chunk.empty)).asError("Empty")

            def cont(s: State): Boolean = s.isEmpty
          }

        for {
          ref <- Ref.make((1, false))
          pulls <- Stream(1, 2, 3)
                    .aggregate(initFailureSink(ref))
                    .process
                    .use(nPulls(_, 9))
        } yield assert(pulls)(
          equalTo(
            List(
              Left(Some("Ouch")),
              Right("1"),
              Left(Some("Ouch")),
              Right("2"),
              Left(Some("Ouch")),
              Right("3"),
              Left(Some("Ouch")),
              Left(None),
              Left(None)
            )
          )
        )
      },
      testM("is safe to pull again after sink step failure") {
        Stream(1, 2, 3, 4)
          .aggregate(ZSink.identity[Int].contramapM { (n: Int) =>
            if (n % 2 == 0) IO.failNow("Ouch") else UIO.succeedNow(n)
          })
          .process
          .use(nPulls(_, 6))
          .map(
            assert(_)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3), Left(Some("Ouch")), Left(None), Left(None))))
          )
      },
      testM("is safe to pull again after sink extraction failure") {
        assertM(
          Stream(1, 2, 3, 4)
            .aggregate(ZSink.fromFunctionM { (n: Int) =>
              if (n % 2 == 0) IO.failNow("Ouch") else UIO.succeedNow(n)
            })
            .process
            .use(nPulls(_, 6))
        )(
          equalTo(
            List(Right(1), Left(Some(Some("Ouch"))), Right(3), Left(Some(Some("Ouch"))), Left(None), Left(None))
          )
        )
      }
    ),
    suite("Stream.aggregateManaged")(
      testM("is safe to pull again after success") {
        Stream(1, 2, 3, 4, 5, 6)
          .aggregateManaged(Managed.succeedNow(ZSink.collectAllN[Int](2).map(_.sum)))
          .process
          .use(nPulls(_, 5))
          .map(assert(_)(equalTo(List(Right(3), Right(7), Right(11), Left(None), Left(None)))))
      },
      testM("is safe to pull again from a failed Managed") {
        Stream(1, 2, 3, 4, 5, 6)
          .aggregateManaged(Managed.failNow("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.buffer is safe to pull again") {
      assertM(
        Stream(1, 2, 3, 4, 5)
          .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
          .buffer(2)
          .process
          .use(nPulls(_, 7))
      )(
        equalTo(
          List(
            Right(1),
            Left(Some("Ouch 2")),
            Right(3),
            Left(Some("Ouch 4")),
            Right(5),
            Left(None),
            Left(None)
          )
        )
      )
    },
    testM("Stream.bufferDropping is safe to pull again") {
      assertM(
        Stream(1, 2, 3, 4, 5)
          .mapM(n => if (n % 2 == 0) IO.fail(s"Ouch $n") else UIO.succeed(n))
          .bufferDropping(2)
          .process
          .use(nPulls(_, 7))
      )(
        equalTo(
          List(
            Right(1),
            Left(Some("Ouch 2")),
            Right(3),
            Left(Some("Ouch 4")),
            Right(5),
            Left(None),
            Left(None)
          )
        )
      )
    },
    testM("Stream.bufferSliding is safe to pull again") {
      assertM(
        Stream(1, 2, 3, 4, 5)
          .mapM(n => if (n % 2 == 0) IO.fail(s"Ouch $n") else UIO.succeed(n))
          .bufferSliding(2)
          .process
          .use(nPulls(_, 7))
      )(
        equalTo(
          List(
            Right(1),
            Left(Some("Ouch 2")),
            Right(3),
            Left(Some("Ouch 4")),
            Right(5),
            Left(None),
            Left(None)
          )
        )
      )
    },
    testM("Stream.bufferUnbounded is safe to pull again") {
      assertM(
        Stream(1, 2, 3, 4, 5)
          .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
          .bufferUnbounded
          .process
          .use(nPulls(_, 7))
      )(
        equalTo(
          List(
            Right(1),
            Left(Some("Ouch 2")),
            Right(3),
            Left(Some("Ouch 4")),
            Right(5),
            Left(None),
            Left(None)
          )
        )
      )
    },
    suite("Stream.collect")(
      testM("ZStream#collect is safe to pull again") {
        Stream(1, 2, 3, 4, 5, 7, 9)
          .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
          .collect {
            case n if n < 6 && n != 3 => n
          }
          .process
          .use(nPulls(_, 6))
          .map(
            assert(_)(
              equalTo(
                List(
                  Right(1),
                  Left(Some("Ouch 2")),
                  Left(Some("Ouch 4")),
                  Right(5),
                  Left(None),
                  Left(None)
                )
              )
            )
          )
      },
      testM("StreamEffect#collect is safe to pull again") {
        val stream = StreamEffect[Any, String, Int] {
          Managed.effectTotal {
            var counter = 0

            () => {
              counter += 1
              if (counter >= 6) StreamEffect.end[Int]
              else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
              else counter
            }
          }
        }

        stream.collect {
          case n if n < 6 && n != 3 => n
        }.process
          .use(nPulls(_, 6))
          .map(
            assert(_)(
              equalTo(
                List(
                  Right(1),
                  Left(Some("Ouch 2")),
                  Left(Some("Ouch 4")),
                  Right(5),
                  Left(None),
                  Left(None)
                )
              )
            )
          )
      }
    ),
    testM("Stream.collectM is safe to pull again") {
      Stream(1, 2, 3, 4, 5, 7)
        .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
        .collectM {
          case 1 => UIO.succeedNow(1)
          case 3 => IO.fail(s"Collect ouch 3")
          case 5 => UIO.succeedNow(5)
        }
        .process
        .use(nPulls(_, 7))
        .map(
          assert(_)(
            equalTo(
              List(
                Right(1),
                Left(Some("Ouch 2")),
                Left(Some("Collect ouch 3")),
                Left(Some("Ouch 4")),
                Right(5),
                Left(None),
                Left(None)
              )
            )
          )
        )
    },
    suite("Stream.collectWhile")(
      testM("ZStream#collectWhile is safe to pull again") {
        Stream(1, 2, 3, 4, 5, 7, 9)
          .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
          .collectWhile {
            case n if n < 6 => n
          }
          .process
          .use(nPulls(_, 7))
          .map(
            assert(_)(
              equalTo(
                List(
                  Right(1),
                  Left(Some("Ouch 2")),
                  Right(3),
                  Left(Some("Ouch 4")),
                  Right(5),
                  Left(None),
                  Left(None)
                )
              )
            )
          )
      },
      testM("StreamEffect#collectWhile is safe to pull again") {
        val stream = StreamEffect[Any, String, Int] {
          Managed.effectTotal {
            var counter = 0

            () => {
              counter += 1
              if (counter >= 6) StreamEffect.end[Int]
              else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
              else counter
            }
          }
        }

        stream.collectWhile {
          case n if n < 5 => n
        }.process
          .use(nPulls(_, 6))
          .map(
            assert(_)(
              equalTo(
                List(
                  Right(1),
                  Left(Some("Ouch 2")),
                  Right(3),
                  Left(Some("Ouch 4")),
                  Left(None),
                  Left(None)
                )
              )
            )
          )
      }
    ),
    testM("Stream.collectWhileM is safe to pull again") {
      Stream(1, 2, 3, 4, 5, 7, 9)
        .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
        .collectWhileM {
          case 1 => UIO.succeedNow(1)
          case 3 => IO.fail(s"Collect ouch 3")
          case 5 => UIO.succeedNow(5)
        }
        .process
        .use(nPulls(_, 7))
        .map(
          assert(_)(
            equalTo(
              List(
                Right(1),
                Left(Some("Ouch 2")),
                Left(Some("Collect ouch 3")),
                Left(Some("Ouch 4")),
                Right(5),
                Left(None),
                Left(None)
              )
            )
          )
        )
    },
    testM("Stream.drop is safe to pull again") {
      assertM(
        Stream(1, 2, 3, 4, 5, 6, 7)
          .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
          .drop(3)
          .process
          .use(nPulls(_, 6))
      )(
        equalTo(
          List(
            Left(Some("Ouch 2")), // dropped 1 up to here
            Left(Some("Ouch 4")), // dropped 2 up to here
            Left(Some("Ouch 6")), // dropped 3 up to here
            Right(7),
            Left(None),
            Left(None)
          )
        )
      )
    },
    suite("Stream.dropWhile")(
      testM("ZStream#dropWhile is safe to pull again") {
        assertM(
          Stream(1, 2, 3, 4, 5)
            .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
            .dropWhile(_ < 3)
            .process
            .use(nPulls(_, 6))
        )(equalTo(List(Left(Some("Ouch 2")), Right(3), Left(Some("Ouch 4")), Right(5), Left(None), Left(None))))
      },
      testM("StreamEffect#dropWhile is safe to pull again") {
        val stream = StreamEffect[Any, String, Int] {
          Managed.effectTotal {
            var counter = 0

            () => {
              counter += 1
              if (counter >= 6) StreamEffect.end[Int]
              else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
              else counter
            }
          }
        }

        assertM(
          stream
            .dropWhile(_ < 3)
            .process
            .use(nPulls(_, 6))
        )(equalTo(List(Left(Some("Ouch 2")), Right(3), Left(Some("Ouch 4")), Right(5), Left(None), Left(None))))
      }
    ),
    testM("Stream.flatMap is safe to pull again") {
      for {
        ref <- Ref.make(List.empty[String])
        pulls <- Stream(1, 2, 3, 4).flatMap { n =>
                  if (n % 2 == 0) {
                    (Stream.failNow(s"Ouch $n") ++ Stream.succeedNow(n))
                      .tap(_ => ref.update(s"inner $n" :: _))
                      .ensuring(ref.update(s"outer $n" :: _))
                  } else {
                    Stream.succeedNow(n)
                  }
                }.process
                  .use(nPulls(_, 8))
        finalizers <- ref.get
      } yield assert(pulls)(
        equalTo(
          List(
            Right(1),
            Left(Some("Ouch 2")),
            Right(2),
            Right(3),
            Left(Some("Ouch 4")),
            Right(4),
            Left(None),
            Left(None)
          )
        )
      ) && assert(finalizers)(equalTo(List("outer 4", "inner 4", "outer 2", "inner 2")))
    },
    suite("Stream.mapAccum")(
      testM("ZStream#mapAccum is safe to pull again") {
        Stream(1, 2, 3, 4, 5, 6)
          .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
          .mapAccum(0)((sum, n) => (sum + n, sum + n))
          .process
          .use(nPulls(_, 8))
          .map(
            assert(_)(
              equalTo(
                List(
                  Right(1),
                  Left(Some("Ouch 2")),
                  Right(4),
                  Left(Some("Ouch 4")),
                  Right(9),
                  Left(Some("Ouch 6")),
                  Left(None),
                  Left(None)
                )
              )
            )
          )
      },
      testM("StreamEffect#mapAccum is safe to pull again") {
        val stream = StreamEffect[Any, String, Int] {
          Managed.effectTotal {
            var counter = 0

            () => {
              counter += 1
              if (counter >= 7) StreamEffect.end[Int]
              else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
              else counter
            }
          }
        }

        assertM(
          stream
            .mapAccum(0)((sum, n) => (sum + n, sum + n))
            .process
            .use(nPulls(_, 8))
        )(
          equalTo(
            List(
              Right(1),
              Left(Some("Ouch 2")),
              Right(4),
              Left(Some("Ouch 4")),
              Right(9),
              Left(Some("Ouch 6")),
              Left(None),
              Left(None)
            )
          )
        )
      }
    ),
    testM("Stream.mapAccumM is safe to pull again") {
      assertM(
        Stream(1, 2, 3, 4, 5)
          .mapAccumM(0)((sum, n) => if (n % 2 == 0) IO.failNow("Ouch") else UIO.succeedNow((sum + n, sum + n)))
          .process
          .use(nPulls(_, 8))
      )(
        equalTo(
          List(
            Right(1),
            Left(Some("Ouch")),
            Right(4),
            Left(Some("Ouch")),
            Right(9),
            Left(None),
            Left(None),
            Left(None)
          )
        )
      )
    },
    suite("Stream.take")(
      testM("ZStream#take is safe to pull again") {
        assertM(
          Stream(1, 2, 3, 4, 5)
            .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
            .take(3)
            .process
            .use(nPulls(_, 7))
        )(
          equalTo(
            List(
              Right(1), // took 1 up to here
              Left(Some("Ouch 2")),
              Right(3), // took 2 up to here
              Left(Some("Ouch 4")),
              Right(5), // took 3 up to here
              Left(None),
              Left(None)
            )
          )
        )
      },
      testM("StreamEffect#take is safe to pull again") {
        val stream = StreamEffect[Any, String, Int] {
          Managed.effectTotal {
            var counter = 0

            () => {
              counter += 1
              if (counter >= 6) StreamEffect.end[Int]
              else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
              else counter
            }
          }
        }

        assertM(
          stream.take(3).process.use(nPulls(_, 7))
        )(
          equalTo(
            List(
              Right(1), // took 1 up to here
              Left(Some("Ouch 2")),
              Right(3), // took 2 up to here
              Left(Some("Ouch 4")),
              Right(5), // took 3 up to here
              Left(None),
              Left(None)
            )
          )
        )
      }
    ),
    suite("Stream.takeUntil")(
      testM("ZStream#takeUntil is safe to pull again") {
        assertM(
          Stream(1, 2, 3, 4, 5)
            .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
            .takeUntil(_ > 3)
            .process
            .use(nPulls(_, 7))
        )(
          equalTo(
            List(
              Right(1),
              Left(Some("Ouch 2")),
              Right(3),
              Left(Some("Ouch 4")),
              Right(5),
              Left(None),
              Left(None)
            )
          )
        )
      },
      testM("StreamEffect#takeUntil is safe to pull again") {
        val stream = StreamEffect[Any, String, Int] {
          Managed.effectTotal {
            var counter = 0

            () => {
              counter += 1
              if (counter >= 6) StreamEffect.end[Int]
              else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
              else counter
            }
          }
        }

        assertM(
          stream.takeUntil(_ > 3).process.use(nPulls(_, 7))
        )(
          equalTo(
            List(
              Right(1),
              Left(Some("Ouch 2")),
              Right(3),
              Left(Some("Ouch 4")),
              Right(5),
              Left(None),
              Left(None)
            )
          )
        )
      }
    ),
    suite("Stream.takeWhile")(
      testM("ZStream#takeWhile is safe to pull again") {
        assertM(
          Stream(1, 2, 3, 4, 5, 7, 9)
            .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
            .takeWhile(_ < 6)
            .process
            .use(nPulls(_, 7))
        )(
          equalTo(
            List(
              Right(1),
              Left(Some("Ouch 2")),
              Right(3),
              Left(Some("Ouch 4")),
              Right(5),
              Left(None),
              Left(None)
            )
          )
        )
      },
      testM("StreamEffect#takeWhile is safe to pull again") {
        val stream = StreamEffect[Any, String, Int] {
          Managed.effectTotal {
            var counter = 0

            () => {
              counter += 1
              if (counter >= 6) StreamEffect.end[Int]
              else if (counter % 2 == 0) StreamEffect.fail[String, Int](s"Ouch $counter")
              else counter
            }
          }
        }

        assertM(
          stream.takeWhile(_ < 5).process.use(nPulls(_, 6))
        )(
          equalTo(
            List(
              Right(1),
              Left(Some("Ouch 2")),
              Right(3),
              Left(Some("Ouch 4")),
              Left(None),
              Left(None)
            )
          )
        )
      }
    ),
    testM("Stream.zipWithIndex is safe to pull again") {
      Stream(1, 2, 3, 4, 5)
        .mapM(n => if (n % 2 == 0) IO.failNow(s"Ouch $n") else UIO.succeedNow(n))
        .zipWithIndex
        .process
        .use(nPulls(_, 7))
        .map(
          assert(_)(
            equalTo(
              List(
                Right((1, 0L)),
                Left(Some("Ouch 2")),
                Right((3, 1L)),
                Left(Some("Ouch 4")),
                Right((5, 2L)),
                Left(None),
                Left(None)
              )
            )
          )
        )
    }
  )

  def constructors = suite("Constructors")(
    suite("Stream.bracket")(
      testM("is safe to pull again after success") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.bracket(UIO.succeedNow(5))(_ => ref.set(true)).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(5), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.bracket(IO.failNow("Ouch"))(_ => ref.set(true)).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .bracket(UIO.succeedNow(5))(_ => ref.set(true))
                    .flatMap(_ => Stream.failNow("Ouch"))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      }
    ),
    suite("Stream.bracketExit")(
      testM("is safe to pull again after success") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.bracketExit(UIO.succeedNow(5))((_, _) => ref.set(true)).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(5), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.bracketExit(IO.failNow("Ouch"))((_, _) => ref.set(true)).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .bracketExit(UIO.succeedNow(5))((_, _) => ref.set(true))
                    .flatMap(_ => Stream.failNow("Ouch"))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      }
    ),
    suite("Stream.flatten")(
      testM("is safe to pull again after success") {
        Stream
          .flatten(Stream(Stream.fromEffect(UIO.succeedNow(5))))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(5), Left(None), Left(None)))))
      },
      testM("is safe to pull again after inner failure") {
        Stream
          .flatten(Stream(Stream.failNow("Ouch")))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      },
      testM("is safe to pull again after outer failure") {
        Stream
          .flatten(Stream.failNow("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.empty is safe to pull again") {
      Stream.empty.process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Left(None), Left(None), Left(None)))))
    },
    suite("Stream.effectAsync")(
      testM("is safe to pull again after error") {
        Stream
          .effectAsync[String, Int] { k =>
            List(1, 2, 3).foreach { n =>
              k(if (n % 2 == 0) Pull.failNow("Ouch") else Pull.emitNow(n))
            }
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3)))))
      },
      testM("is safe to pull again after end") {
        Stream
          .effectAsync[String, Int] { k =>
            k(Pull.emitNow(1))
            k(Pull.end)
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
      }
    ),
    suite("Stream.effectAsyncM")(
      testM("is safe to pull again after error") {
        Stream
          .effectAsyncM[String, Int] { k =>
            List(1, 2, 3).foreach { n =>
              k(if (n % 2 == 0) Pull.failNow("Ouch") else Pull.emitNow(n))
            }
            UIO.unit
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3)))))
      },
      testM("is safe to pull again after end") {
        Stream
          .effectAsyncM[String, Int] { k =>
            k(Pull.emitNow(1))
            k(Pull.end)
            UIO.unit
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
      }
    ),
    suite("Stream.effectAsyncMaybe")(
      testM("is safe to pull again after error async case") {
        Stream
          .effectAsyncMaybe[String, Int] { k =>
            List(1, 2, 3).foreach { n =>
              k(if (n % 2 == 0) Pull.failNow("Ouch") else Pull.emitNow(n))
            }
            None
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3)))))
      },
      testM("is safe to pull again after error sync case") {
        Stream
          .effectAsyncMaybe[String, Int] { k =>
            k(Pull.failNow("Ouch async"))
            Some(Stream.failNow("Ouch sync"))
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch sync")), Left(None), Left(None)))))
      },
      testM("is safe to pull again after end async case") {
        Stream
          .effectAsyncMaybe[String, Int] { k =>
            k(Pull.emitNow(1))
            k(Pull.end)
            None
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
      },
      testM("is safe to pull again after end sync case") {
        Stream
          .effectAsyncMaybe[String, Int] { k =>
            k(Pull.failNow("Ouch async"))
            Some(Stream.empty)
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(None), Left(None), Left(None)))))
      }
    ),
    suite("Stream.effectAsyncInterrupt")(
      testM("is safe to pull again after error async case") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .effectAsyncInterrupt[String, Int] { k =>
                      List(1, 2, 3).foreach { n =>
                        k(if (n % 2 == 0) Pull.failNow("Ouch") else Pull.emitNow(n))
                      }
                      Left(ref.set(true))
                    }
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3))))
      },
      testM("is safe to pull again after error sync case") {
        Stream
          .effectAsyncInterrupt[String, Int] { k =>
            k(IO.failNow(Some("Ouch async")))
            Right(Stream.failNow("Ouch sync"))
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch sync")), Left(None), Left(None)))))
      },
      testM("is safe to pull again after end async case") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .effectAsyncInterrupt[String, Int] { k =>
                      k(Pull.emitNow(1))
                      k(Pull.end)
                      Left(ref.set(true))
                    }
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(1), Left(None), Left(None))))
      },
      testM("is safe to pull again after end sync case") {
        Stream
          .effectAsyncInterrupt[String, Int] { k =>
            k(IO.failNow(Some("Ouch async")))
            Right(Stream.empty)
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(None), Left(None), Left(None)))))
      }
    ),
    testM("Stream.fail is safe to pull again") {
      Stream
        .failNow("Ouch")
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
    },
    testM("Stream.finalizer is safe to pull again") {
      for {
        ref   <- Ref.make(0)
        pulls <- Stream.finalizer(ref.update(_ + 1)).process.use(nPulls(_, 3))
        fin   <- ref.get
      } yield assert(fin)(equalTo(1)) && assert(pulls)(equalTo(List(Left(None), Left(None), Left(None))))
    },
    testM("Stream.fromChunk is safe to pull again") {
      Stream
        .fromChunk(Chunk(1))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
    },
    suite("Stream.fromEffect")(
      testM("is safe to pull again after success") {
        Stream
          .fromEffect(UIO.succeedNow(5))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(5), Left(None), Left(None)))))
      },
      testM("is safe to pull again after failure") {
        Stream
          .fromEffect(IO.failNow("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.fromInputStream is safe to pull again") {
      val error = new java.io.IOException()
      val is = new java.io.InputStream {
        var state = 0

        def read(): Int = {
          state += 1
          if (state == 2) throw error
          else if (state == 4) -1
          else state
        }
      }

      Stream
        .fromInputStream(is, 1)
        .process
        .use(nPulls(_, 5))
        .map(assert(_)(equalTo(List(Right(1.toByte), Left(Some(error)), Right(3.toByte), Left(None), Left(None)))))
    },
    testM("Stream.fromIterable is safe to pull again") {
      Stream
        .fromIterable(List(1))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Right(1), Left(None), Left(None)))))
    },
    suite("Stream.fromIterator")(
      testM("is safe to pull again after success") {
        Stream
          .fromIterator(UIO.succeedNow(List(1, 2).iterator))
          .process
          .use(nPulls(_, 4))
          .map(assert(_)(equalTo(List(Right(1), Right(2), Left(None), Left(None)))))
      },
      testM("is safe to pull again after failure") {
        Stream
          .fromIterator(IO.failNow("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    suite("Stream.fromIteratorManaged")(
      testM("is safe to pull again after success") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .fromIteratorManaged(Managed.make(UIO.succeedNow(List(1, 2).iterator))(_ => ref.set(true)))
                    .process
                    .use(nPulls(_, 4))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(1), Right(2), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .fromIteratorManaged(Managed.make(IO.failNow("Ouch"))(_ => ref.set(true)))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .fromIteratorManaged(Managed.make(UIO.succeedNow(List(1, 2).iterator))(_ => ref.set(true)))
                    .flatMap(n =>
                      Stream.succeedNow((n * 2).toString) ++ Stream.failNow("Ouch") ++ Stream.succeedNow(
                        (n * 3).toString
                      )
                    )
                    .process
                    .use(nPulls(_, 8))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(
          equalTo(
            List(
              Right("2"),
              Left(Some("Ouch")),
              Right("3"),
              Right("4"),
              Left(Some("Ouch")),
              Right("6"),
              Left(None),
              Left(None)
            )
          )
        )
      },
      testM("is safe to pull again from a failed Managed") {
        Stream
          .fromIteratorManaged(Managed.failNow("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.fromQueue is safe to pull again") {
      for {
        queue <- Queue.bounded[Int](1)
        pulls <- Stream
                  .fromQueue(queue)
                  .process
                  .use { pull =>
                    for {
                      _  <- queue.offer(1)
                      e1 <- pull.either
                      _  <- queue.offer(2)
                      e2 <- pull.either
                      _  <- queue.shutdown
                      e3 <- pull.either
                      e4 <- pull.either
                    } yield List(e1, e2, e3, e4)
                  }
      } yield assert(pulls)(equalTo(List(Right(1), Right(2), Left(None), Left(None))))
    },
    testM("Stream.fromQueueWithShutdown is safe to pull again") {
      for {
        queue <- Queue.bounded[Int](1)
        pulls <- Stream
                  .fromQueueWithShutdown(queue)
                  .process
                  .use { pull =>
                    for {
                      _  <- queue.offer(1)
                      e1 <- pull.either
                      _  <- queue.offer(2)
                      e2 <- pull.either
                    } yield List(e1, e2)
                  }
        fin <- queue.isShutdown
      } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(1), Right(2))))
    },
    testM("Stream.halt is safe to pull again if failing with a checked error") {
      Stream
        .haltNow(Cause.fail("Ouch"))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
    },
    suite("Stream.managed")(
      testM("is safe to pull again after success") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.managed(Managed.make(UIO.succeedNow(5))(_ => ref.set(true))).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(5), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref   <- Ref.make(false)
          pulls <- Stream.managed(Managed.make(IO.failNow("Ouch"))(_ => ref.set(true))).process.use(nPulls(_, 3))
          fin   <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .managed(Managed.make(UIO.succeedNow(5))(_ => ref.set(true)))
                    .flatMap(_ => Stream.failNow("Ouch"))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again from a failed Managed") {
        Stream
          .managed(Managed.failNow("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    testM("Stream.paginate is safe to pull again") {
      Stream
        .paginate(0)(n => (n, None))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Right(0), Left(None), Left(None)))))
    },
    suite("Stream.paginateM")(
      testM("is safe to pull again after success") {
        Stream
          .paginateM(0)(n => UIO.succeedNow((n, None)))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(0), Left(None), Left(None)))))
      },
      testM("is safe to pull again after failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .paginateM(1) { n =>
                      ref.get.flatMap { done =>
                        if (n == 2 && !done) ref.set(true) *> IO.failNow("Ouch")
                        else UIO.succeedNow((n, Some(n + 1)))
                      }
                    }
                    .process
                    .use(nPulls(_, 3))
        } yield assert(pulls)(equalTo(List(Right(1), Left(Some("Ouch")), Right(2))))
      }
    ),
    testM("Stream.range is safe to pull again") {
      Stream
        .range(1, 4)
        .process
        .use(nPulls(_, 5))
        .map(assert(_)(equalTo(List(Right(1), Right(2), Right(3), Left(None), Left(None)))))
    },
    testM("Stream.repeatEffect is safe to pull again after error") {
      Stream
        .repeatEffect(IO.failNow("Ouch"))
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(Some("Ouch")), Left(Some("Ouch"))))))
    },
    testM("Stream.repeatEffectWith is safe to pull again") {
      def effect(ref: Ref[Int]): IO[String, Int] =
        for {
          cnt <- ref.updateAndGet(_ + 1)
          res <- if (cnt == 2) IO.failNow("Ouch") else UIO.succeedNow(cnt)
        } yield res

      for {
        ref <- Ref.make(0)
        pulls <- Stream
                  .repeatEffectWith(effect(ref), Schedule.recurs(2))
                  .process
                  .use(nPulls(_, 5))
      } yield assert(pulls)(equalTo(List(Right(1), Left(Some("Ouch")), Right(3), Left(None), Left(None))))
    },
    testM("Stream.succeed is safe to pull again") {
      Stream
        .succeedNow(5)
        .process
        .use(nPulls(_, 3))
        .map(assert(_)(equalTo(List(Right(5), Left(None), Left(None)))))
    },
    testM("Stream.unfold is safe to pull again") {
      Stream
        .unfold(0) { n =>
          if (n > 2) None
          else Some((n, n + 1))
        }
        .process
        .use(nPulls(_, 5))
        .map(assert(_)(equalTo(List(Right(0), Right(1), Right(2), Left(None), Left(None)))))
    },
    suite("Stream.unfoldM")(
      testM("is safe to pull again after success") {
        Stream
          .unfoldM(0) { n =>
            if (n == 1) UIO.succeedNow(None)
            else UIO.succeedNow(Some((n, n + 1)))
          }
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(0), Left(None), Left(None)))))
      },
      testM("is safe to pull again after failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .unfoldM(1) { n =>
                      ref.get.flatMap { done =>
                        if (n == 2 && !done) ref.set(true) *> IO.failNow("Ouch")
                        else if (n > 2) UIO.succeedNow(None)
                        else UIO.succeedNow(Some((n, n + 1)))
                      }
                    }
                    .process
                    .use(nPulls(_, 5))
        } yield assert(pulls)(equalTo(List(Right(1), Left(Some("Ouch")), Right(2), Left(None), Left(None))))
      }
    ),
    suite("Stream.unwrap")(
      testM("is safe to pull again after success") {
        Stream
          .unwrap(UIO.succeedNow(Stream.fromEffect(UIO.succeedNow(5))))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Right(5), Left(None), Left(None)))))
      },
      testM("is safe to pull again after inner failure") {
        Stream
          .unwrap(UIO.succeedNow(Stream.failNow("Ouch")))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      },
      testM("is safe to pull again after outer failure") {
        Stream
          .unwrap(IO.failNow("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    ),
    suite("Stream.unwrapManaged")(
      testM("is safe to pull again after success") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .unwrapManaged(
                      Managed.make(UIO.succeedNow(Stream.fromEffect(UIO.succeedNow(5))))(_ => ref.set(true))
                    )
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Right(5), Left(None), Left(None))))
      },
      testM("is safe to pull again after failed acquisition") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .unwrapManaged(Managed.make(IO.failNow("Ouch"))(_ => ref.set(true)))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isFalse) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again after inner failure") {
        for {
          ref <- Ref.make(false)
          pulls <- Stream
                    .unwrapManaged(
                      Managed.make(UIO.succeedNow(Stream.fromEffect(UIO.succeedNow(5))))(_ => ref.set(true))
                    )
                    .flatMap(_ => Stream.failNow("Ouch"))
                    .process
                    .use(nPulls(_, 3))
          fin <- ref.get
        } yield assert(fin)(isTrue) && assert(pulls)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None))))
      },
      testM("is safe to pull again from a failed Managed") {
        Stream
          .unwrapManaged(Managed.failNow("Ouch"))
          .process
          .use(nPulls(_, 3))
          .map(assert(_)(equalTo(List(Left(Some("Ouch")), Left(None), Left(None)))))
      }
    )
  )
}
