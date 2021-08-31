package at.forsyte.apalache.tla.typecheck

import at.forsyte.apalache.tla.lir.TlaType1

import scala.collection.immutable.SortedMap

/**
 * A type context assigns poly- or monotypes to names (such as operator names and the names of quantified variables).
 *
 * @param largestVarIndex the largest variable index that should be treated as non-free, that is, not universally quantified; greater or equal to 0.
 * @param types           a map from a name to the assigned typed and universal type variables
 * @author Igor Konnov
 */
class TypeContext(val largestVarIndex: Int, val types: Map[String, (TlaType1, Set[Int])]) {
  def apply(name: String): (TlaType1, Set[Int]) = {
    types.getOrElse(name, new IllegalArgumentException(s"No type binding for $name in the type context"))
  }
}

object TypeContext {

  /**
   * The value of the variable index that means that all variables can be quantified.
   */
  val NIL_VAR_INDEX: Int = -1

  /**
   * An empty type context.
   */
  val empty: TypeContext = new TypeContext(NIL_VAR_INDEX, Map.empty)

  def apply(namedTypes: (String, (TlaType1, Set[Int]))*): TypeContext = {
    new TypeContext(NIL_VAR_INDEX, SortedMap(namedTypes: _*))
  }
}
