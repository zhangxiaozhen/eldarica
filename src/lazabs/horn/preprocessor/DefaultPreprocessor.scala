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
import lazabs.horn.bottomup.Util.Dag

import scala.collection.mutable.{HashSet => MHashSet, HashMap => MHashMap,
                                 LinkedHashSet, ArrayBuffer}

/**
 * Default preprocessing pipeline used in Eldarica.
 */
class DefaultPreprocessor extends HornPreprocessor {
  import HornPreprocessor._

  val name : String = "default"
  val printWidth = 47

  val stages : List[HornPreprocessor] =
    List(ReachabilityChecker,
         DefinitionInliner,
         new ClauseInliner,
         Slicer,
         new ClauseShortener) ++
    (if (lazabs.GlobalParameters.get.splitClauses)
      List(new ClauseSplitter) else List()) ++
    (if (lazabs.GlobalParameters.get.staticAccelerate)
      List(Accelerator) else List())

  def process(clauses : Clauses, hints : VerificationHints)
             : (Clauses, VerificationHints, BackTranslator) = {
    var curClauses = clauses
    var curHints = hints

    Console.err.println(
      "--------------------- Preprocessing ------------------------")
    Console.err.println("Initially:" + (" " * (printWidth - 10)) +
                        curClauses.size + " clauses")

    val translators = for (stage <- stages) yield {
      val (newClauses, newHints, translator) =
        stage.process(curClauses, curHints)
      curClauses = newClauses
      curHints = newHints

      Console.err.println("After " + stage.name +
                          ":" + (" " * (printWidth - 7 - stage.name.size)) +
                          curClauses.size + " clauses")

      translator
    }

    Console.err.println

    (curClauses, curHints, new ComposedBackTranslator(translators.reverse))
  }

}