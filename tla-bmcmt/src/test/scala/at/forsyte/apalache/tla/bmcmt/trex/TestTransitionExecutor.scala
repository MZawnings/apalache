package at.forsyte.apalache.tla.bmcmt.trex

import at.forsyte.apalache.tla.bmcmt.{SymbState, SymbStateRewriter, SymbStateRewriterImpl}
import at.forsyte.apalache.tla.bmcmt.analyses._
import at.forsyte.apalache.tla.bmcmt.smt.Z3SolverContext
import at.forsyte.apalache.tla.bmcmt.types.eager.TrivialTypeFinder
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.convenience.tla
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FunSuite}

@RunWith(classOf[JUnitRunner])
class TestTransitionExecutor extends FunSuite with BeforeAndAfter {
  private var typeFinder: TrivialTypeFinder = new TrivialTypeFinder()
  private var solver: Z3SolverContext = new Z3SolverContext()
  private var rewriter = new SymbStateRewriterImpl(solver, typeFinder, new ExprGradeStoreImpl())
  private var execCtx = new IncrementalExecutorContext(rewriter, typeFinder)

  before {
    // initialize the executor context
    typeFinder = new TrivialTypeFinder()
    solver = new Z3SolverContext()
    rewriter = new SymbStateRewriterImpl(solver, typeFinder, new ExprGradeStoreImpl())
    execCtx = new IncrementalExecutorContext(rewriter, typeFinder)
  }

  test("push 1 transition") {
    // y' <- 1 /\ x' <- 1
    val init = tla.and(mkAssign("y", 1), mkAssign("x", 1))
    val trex = new TransitionExecutor(Set.empty, Set("x", "y"), execCtx)
    trex.debug = true
    assert(trex.stepNo == 0)
    // init is a potential transition with index 3 (the index is defined by the input spec)
    trex.prepareTransition(3, init)
    // assume that one of the prepared transitions fires
    trex.pickTransition()
    // advance the computation: forget the non-primed variables, rename primed to non-primed
    trex.nextState()
    assert(trex.stepNo == 1)
    // assert something about the current state
    trex.assertState(tla.eql(tla.name("y"), tla.int(1)))
    assert(trex.sat(60).contains(true))
  }

  test("check enabled and discard") {
    // an obviously disabled transition: y' <- 1 /\ y' <- 2
    val init = tla.and(
      mkAssign("y", 1),
      tla.eql(tla.prime(tla.name("y")), tla.int(2)),
      mkAssign("x", 3))
    val trex = new TransitionExecutor(Set.empty, Set("x", "y"), execCtx)
    trex.debug = true
    assert(trex.stepNo == 0)
    // init is a potential transition with index 3 (the index is defined by the input spec)
    trex.prepareTransition(1, init)
    // check, whether the transition is enabled
    trex.assumeTransition(1)
    assert(trex.sat(60).contains(false))
  }

  test("check an invariant after transition") {
    // y' <- 1 /\ x' <- 1
    val init = tla.and(mkAssign("y", 1), mkAssign("x", 1))
    val trex = new TransitionExecutor(Set.empty, Set("x", "y"), execCtx)
    trex.debug = true
    assert(trex.stepNo == 0)
    // init is a potential transition with index 3 (the index is defined by the input spec)
    trex.prepareTransition(3, init)
    // assume that the transition has fired
    trex.assumeTransition(3)
    // create a snapshot for a later rollback
    val snapshot = trex.snapshot()
    // assert invariant violation and check it
    val notInv = tla.not(tla.eql(tla.prime(tla.name("y")), tla.prime(tla.name("x"))))
    trex.assertState(notInv)
    assert(trex.sat(60).contains(false))
    // rollback the snapshot
    trex.recover(snapshot)
    // now the context is satisfiable again
    assert(trex.sat(60).contains(true))
  }

  test("Init + 3x Next") {
    // x' <- 1 /\ y' <- 1
    val init = tla.and(mkAssign("y", 1), mkAssign("x", 1))
    // x' <- y /\ y' <- x + y
    val nextTrans = tla.and(
      mkAssign("x", tla.name("y")),
      mkAssign("y", tla.plus(tla.name("x"), tla.name("y"))))
    // 3 = x /\ 3 = y
    val inv = tla.ge(tla.name("y"), tla.name("x"))
    val trex = new TransitionExecutor(Set.empty, Set("x", "y"), execCtx)
    trex.prepareTransition(1, init)
    trex.pickTransition()
    trex.nextState()
    trex.prepareTransition(1, nextTrans)
    trex.pickTransition()
    trex.nextState()
    trex.prepareTransition(1, nextTrans)
    trex.pickTransition()
    trex.nextState()
    trex.prepareTransition(1, nextTrans)
    trex.pickTransition()
    trex.nextState()
    // test the symbolic execution
    val exe = trex.execution
    assert(exe.path.length == 5)
    // check the assertions about the execution states
    // state 0 is not restricted, as there are no parameters

    // state 1 is the state right after Init, that is, Init(state0)
    val state1 = exe.path(1)
    assertValid(trex, tla.eql(state1("x").toNameEx, tla.int(1)))
    assertValid(trex, tla.eql(state1("y").toNameEx, tla.int(1)))

    // state 2 is the state Next(Init(state0))
    val state2 = exe.path(2)
    assertValid(trex, tla.eql(state2("x").toNameEx, tla.int(1)))
    assertValid(trex, tla.eql(state2("y").toNameEx, tla.int(2)))

    // state 3 is the state Next(Next(Init(state0)))
    val state3 = exe.path(3)
    assertValid(trex, tla.eql(state3("x").toNameEx, tla.int(2)))
    assertValid(trex, tla.eql(state3("y").toNameEx, tla.int(3)))

    // state 4 is the state Next(Next(Next(Init(state0)))
    val state4 = exe.path(4)
    assertValid(trex, tla.eql(state4("x").toNameEx, tla.int(3)))
    assertValid(trex, tla.eql(state4("y").toNameEx, tla.int(5)))
  }

  private def mkAssign(name: String, value: Int) =
    tla.assignPrime(tla.name(name), tla.int(value))

  private def mkAssign(name: String, rhs: TlaEx) =
    tla.assignPrime(tla.name(name), rhs)

  protected def assertValid(trex: TransitionExecutor[IncrementalSnapshot], assertion: TlaEx): Unit = {
    var snapshot = trex.snapshot()
    trex.assertState(assertion)
    assert(trex.sat(0).contains(true))
    trex.recover(snapshot)

    snapshot = trex.snapshot()
    trex.assertState(tla.not(assertion))
    assert(trex.sat(0).contains(false))
    trex.recover(snapshot)
  }
}
