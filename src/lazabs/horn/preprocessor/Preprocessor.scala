/**
 * Copyright (c) 2016 Philipp Ruemmer. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * 
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * 
 * * Neither the name of the authors nor the names of their
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package lazabs.horn.preprocessor

import ap.parser._
import IExpression._

import lazabs.horn.bottomup.HornClauses
import lazabs.horn.global._
import lazabs.horn.bottomup.Util.{Dag, DagNode, DagEmpty}

import scala.collection.mutable.{HashSet => MHashSet, HashMap => MHashMap,
                                 LinkedHashSet, ArrayBuffer}
import scala.collection.{Set => GSet}

object HornPreprocessor {

  /**
   * Trait for providing verification hints, associated with predicates
   * defining the analysed programs.
   */
  trait VerificationHints {
    val predicateHints : Map[IExpression.Predicate, Seq[VerifHintElement]]

    def isEmpty = predicateHints.isEmpty

    def filterPredicates(remainingPreds : GSet[IExpression.Predicate]) = {
      val remHints = predicateHints filterKeys remainingPreds
      VerificationHints(remHints)
    }

    def filterNotPredicates(removed : GSet[IExpression.Predicate]) =
      if (removed.isEmpty)
        this
      else
        VerificationHints(predicateHints -- removed)

    def addPredicateHints(
          hints : Map[IExpression.Predicate, Seq[VerifHintElement]]) =
      if (hints.isEmpty)
        this
      else
        VerificationHints(predicateHints ++ hints)

    def toInitialPredicates : Map[IExpression.Predicate, Seq[IFormula]] =
      (for ((p, hints) <- predicateHints.iterator;
            remHints = for (VerifHintInitPred(f) <- hints) yield f;
            if !remHints.isEmpty)
       yield (p -> remHints)).toMap
  }

  //////////////////////////////////////////////////////////////////////////////

  object VerificationHints {
    def apply(hints : Map[IExpression.Predicate, Seq[VerifHintElement]]) =
      new VerificationHints {
        val predicateHints = hints
      }
  }

  object EmptyVerificationHints extends VerificationHints {
    val predicateHints = Map[IExpression.Predicate, Seq[VerifHintElement]]()
    override def filterPredicates(
                   remainingPreds : GSet[IExpression.Predicate]) = this
    override def toInitialPredicates : Map[IExpression.Predicate, Seq[IFormula]] =
      Map()
  }

  class InitPredicateVerificationHints(preds : Map[Predicate, Seq[IFormula]])
        extends VerificationHints {
    val predicateHints = preds mapValues { l => l map (VerifHintInitPred(_)) }
  }

  //////////////////////////////////////////////////////////////////////////////

  abstract sealed class VerifHintElement {
    /**
     * Shift references to predicate arguments by the given <code>offset</code>
     * and <code>shift</code> (like in <code>VariableShiftVisitor</code>).
     */
    def shiftArguments(offset : Int, shift : Int) : VerifHintElement

    /**
     * Shift references to predicate arguments using the given mapping.
     * If the hint contains any reference not occurring in the given map,
     * <code>None</code> will be returned. Otherwise, every variable
     * <code>_i</code> will be replaced with <code>_(i + mapping(i))</code..
     */
    def shiftArguments(mapping : Map[Int, Int]) : Option[VerifHintElement]

    protected def shiftVars(t : IExpression,
                            mapping : Map[Int, Int]) : Option[IExpression] =
      if (ContainsSymbol(t,
            (f : IExpression) => f match {
              case IVariable(ind) => !(mapping contains ind)
              case _ => false
             }))
        None
      else
        Some(VariablePermVisitor(t, IVarShift(mapping, 0)))
  }
  
  abstract sealed class VerifHintTplElement(val cost : Int)
                                         extends VerifHintElement

  case class VerifHintTplIterationThreshold(threshold : Int)
                                         extends VerifHintElement {
    def shiftArguments(offset : Int, shift : Int)
                      : VerifHintTplIterationThreshold = this
    def shiftArguments(mapping : Map[Int, Int])
                      : Option[VerifHintTplIterationThreshold] = Some(this)
  }

  case class VerifHintInitPred(f : IFormula) extends VerifHintElement {
    def shiftArguments(offset : Int, shift : Int) : VerifHintInitPred =
      VerifHintInitPred(VariableShiftVisitor(f, offset, shift))
    def shiftArguments(mapping : Map[Int, Int]) : Option[VerifHintInitPred] =
      for (newF <- shiftVars(f, mapping))
      yield VerifHintInitPred(newF.asInstanceOf[IFormula])
  }
  
  case class VerifHintTplPred(f : IFormula, _cost : Int)
                                         extends VerifHintTplElement(_cost) {
    def shiftArguments(offset : Int, shift : Int) : VerifHintTplPred =
      VerifHintTplPred(VariableShiftVisitor(f, offset, shift), cost)
    def shiftArguments(mapping : Map[Int, Int]) : Option[VerifHintTplPred] =
      for (newF <- shiftVars(f, mapping))
      yield VerifHintTplPred(newF.asInstanceOf[IFormula], cost)
  }
  
  case class VerifHintTplPredPosNeg(f : IFormula, _cost : Int)
                                         extends VerifHintTplElement(_cost) {
    def shiftArguments(offset : Int, shift : Int) : VerifHintTplPredPosNeg =
      VerifHintTplPredPosNeg(VariableShiftVisitor(f, offset, shift), cost)
    def shiftArguments(mapping : Map[Int, Int])
                      : Option[VerifHintTplPredPosNeg] =
      for (newF <- shiftVars(f, mapping))
      yield VerifHintTplPredPosNeg(newF.asInstanceOf[IFormula], cost)
  }
  
  case class VerifHintTplEqTerm(t : ITerm, _cost : Int)
                                         extends VerifHintTplElement(_cost) {
    def shiftArguments(offset : Int, shift : Int) : VerifHintTplEqTerm =
      VerifHintTplEqTerm(VariableShiftVisitor(t, offset, shift), cost)
    def shiftArguments(mapping : Map[Int, Int]) : Option[VerifHintTplEqTerm] =
      for (newT <- shiftVars(t, mapping))
      yield VerifHintTplEqTerm(newT.asInstanceOf[ITerm], cost)
  }

  case class VerifHintTplInEqTerm(t : ITerm, _cost : Int)
                                         extends VerifHintTplElement(_cost) {
    def shiftArguments(offset : Int, shift : Int) : VerifHintTplInEqTerm =
      VerifHintTplInEqTerm(VariableShiftVisitor(t, offset, shift), cost)
    def shiftArguments(mapping : Map[Int, Int]) : Option[VerifHintTplInEqTerm] =
      for (newT <- shiftVars(t, mapping))
      yield VerifHintTplInEqTerm(newT.asInstanceOf[ITerm], cost)
  }

  case class VerifHintTplInEqTermPosNeg(t : ITerm, _cost : Int)
                                         extends VerifHintTplElement(_cost) {
    def shiftArguments(offset : Int, shift : Int) : VerifHintTplInEqTermPosNeg =
      VerifHintTplInEqTermPosNeg(VariableShiftVisitor(t, offset, shift), cost)
    def shiftArguments(mapping : Map[Int, Int])
                      : Option[VerifHintTplInEqTermPosNeg] =
      for (newT <- shiftVars(t, mapping))
      yield VerifHintTplInEqTermPosNeg(newT.asInstanceOf[ITerm], cost)
  }

  //////////////////////////////////////////////////////////////////////////////

  type Solution = Map[Predicate, IFormula]
  type CounterExample = Dag[(IAtom, HornClauses.Clause)]

  type Clauses = Seq[HornClauses.Clause]

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Simplify a counterexample by merging multiple occurrences of the
   * same state.
   */
  def simplify(cex : CounterExample) : CounterExample = {
    val seenStates = new MHashMap[IAtom, Int]

    def simplifyHelp(depth : Int, dag : CounterExample)
                    : (CounterExample, List[Int]) = dag match {
      case DagNode(pair@(atom, clause), children, next) => {
        val (newNext, shifts) = simplifyHelp(depth + 1, next)
        val newChildren = for (c <- children) yield (c + shifts(c - 1))
        val newShifts = (seenStates get atom) match {
          case None => {
            seenStates.put(atom, depth)
            0 :: shifts
          }
          case Some(d) =>
            (d - depth) :: shifts
        }
        (DagNode(pair, newChildren, newNext), newShifts)
      }
      case DagEmpty =>
        (DagEmpty, List())
    }

    simplifyHelp(0, cex)._1.elimUnconnectedNodes
  }

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Class for back-translating solutions of Horn constraints,
   * undoing any simplification that was done upfront.
   */
  abstract class BackTranslator {
    def translate(solution : Solution) : Solution
    def translate(cex : CounterExample) : CounterExample

    def translate(result : Either[Solution, CounterExample])
                 : Either[Solution, CounterExample] =
      result match {
        case Left(sol) => Left(translate(sol))
        case Right(cex) => Right(translate(cex))
      }
  }

  abstract class ExtendingBackTranslator(parent : BackTranslator)
           extends BackTranslator {
    def preTranslate(solution : Solution) : Solution
    def preTranslate(cex : CounterExample) : CounterExample

    def translate(solution : Solution) =
      parent.translate(preTranslate(solution))
    def translate(cex : CounterExample) =
      parent.translate(preTranslate(cex))
  }

  class ComposedBackTranslator(translators : Seq[BackTranslator])
        extends BackTranslator {
    def translate(solution : Solution) =
      (solution /: translators) { case (sol, t) => t translate sol }
    def translate(cex : CounterExample) =
      (cex /: translators) { case (cex, t) => t translate cex }
  }

  val IDENTITY_TRANSLATOR = new BackTranslator {
    def translate(solution : Solution) = solution
    def translate(cex : CounterExample) = cex
  }

}

////////////////////////////////////////////////////////////////////////////////

/**
 * Processors for upfront simplification of Horn clauses.
 */
trait HornPreprocessor {
  import HornPreprocessor._

  val name : String

  def process(clauses : Clauses, hints : VerificationHints)
             : (Clauses, VerificationHints, BackTranslator)

}