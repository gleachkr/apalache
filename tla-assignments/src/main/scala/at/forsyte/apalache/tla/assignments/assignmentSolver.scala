// \phi : ϕ
// \delta : δ
// \in : ∈
// \squsubset : ⊏
// \iff : ⇔

package at.forsyte.apalache.tla.assignments

import java.io._

import at.forsyte.apalache.tla.lir.actions.TlaActionOper
import at.forsyte.apalache.tla.lir.oper.{TlaBoolOper, TlaSetOper}
import at.forsyte.apalache.tla.lir.plugins.{Identifier, UniqueDB}
import at.forsyte.apalache.tla.lir.{UID, _}
import com.microsoft.z3._

import scala.collection.immutable.{Map, Set}

/**
  * Object equipped with methods for solving the assignment problem.
  *
  * =Instructions For Use=
  *   1. Extract a set of variables and the next formula from your TLA specification.
  *   1. Do one of the following:
  *     a. To produce a good assignment strategy, call [[[[assignmentSolver#getOrder(p_vars:scala\.collection\.immutable\.Set[at\.forsyte\.apalache\.tla\.lir\.NameEx],p_phi:at\.forsyte\.apalache\.tla\.lir\.OperEx,p_fileName:String):Option[Seq[(at\.forsyte\.apalache\.tla\.lir\.UID,Boolean)]]* getOrder]]]].
  *     a. To produce an SMT file or specification for later use, call [[assignmentSolver#makeSpec makeSpec]].
  */
object assignmentSolver {

  /**
    * Contains various variables and functions that are used internally, but
    * would clutter the solver interface.
    */
  protected object preprocessHelperFunctions {

    /** Symbol to be used for variable names in SMT. */
    var m_varSym = "A"
    /** Symbol to be used for the function name in SMT. */
    var m_fnSym  = "R"

    /**
    * Intermediate class for internal use. Represents ϕ formulas as trees.
    */
    abstract class BoolFormula

    case class False( ) extends BoolFormula
    case class And( args : BoolFormula* ) extends BoolFormula
    case class Or( args : BoolFormula* ) extends BoolFormula
    case class Neg( arg : BoolFormula ) extends BoolFormula
    case class Implies( LHS : BoolFormula, RHS : BoolFormula ) extends BoolFormula
    case class Variable( id : Int ) extends BoolFormula
    case class LtFns( i : Int, j : Int ) extends BoolFormula // ( R( i ) < R( j ) )
    case class NeFns( i : Int, j : Int ) extends BoolFormula // ( R( i ) != R( j ) )

    /**
      * Transforms BoolFormula expressions into SMTLIBv2 format recursively.
      *
      * @param phi The formula being transformed.
      * @return The SMTLIBv2 representation of `phi`. Does not include a top-level assertion command.
      */
    def toSmt2( phi : BoolFormula ) : String = {
      phi match {
        case False() =>
          /* return */ "false" //"( false )"
        case And( args@_* ) =>
          /* return */ "( and %s )".format( args.map( toSmt2 ).mkString( " " ) )
        case Or( args@_* ) =>
          /* return */ "( or %s )".format( args.map( toSmt2 ).mkString( " " ) )
        case Neg( arg : BoolFormula ) =>
          /* return */ "( not %s )".format( toSmt2( arg ) )
        case Implies( lhs, rhs ) =>
          /* return */ "( => %s %s )".format( toSmt2( lhs ), toSmt2( rhs ) )
        case Variable( id : Int ) =>
          /* return */ "%s_%s".format( m_varSym, id )
        case LtFns( i : Int, j : Int ) =>
          /* return */ "( < ( %s %s ) ( %s %s ) )".format( m_fnSym, i, m_fnSym, j )
        case NeFns( i : Int, j : Int ) =>
          /* return */ "( not ( = ( %s %s ) ( %s %s ) ) )".format( m_fnSym, i, m_fnSym, j )
      }
    }

    /**
      * Removes spurious branches from the BoolFormula tree to obtain a
      * logically equivalent but smaller formula.
      *
      * Assumes input formulas are generated by the delta function or equivalent.
      *
      * @param phi The formula being transformed.
      * @return A new BoolFormula, logically equivalent to `phi`, with simpler structure.
      */
    def simplify( phi : BoolFormula ) : BoolFormula = {
      phi match {
        /**
          * Recursively simplify branches first.
          * If any branch is false, the whole formula is false.
          * It is important to recurse first,
          * since otherwise false-simplification would not propagate upward.
          */
        case And( args@_* ) => {
          val newargs = args.map( simplify )
          if ( newargs.contains( False() ) )
          /* return */ False()
          else
          /* return */ And( newargs : _* )
        }

        /**
          * Recursively simplify, then drop all False() branches.
          * Afterwards, if the new tree has too few branches prune accordingly.
          */
        case Or( args@_* ) => {
          val newargs = args.map( simplify ).filterNot( _ == False() )
          newargs.size match {
            case 0 =>
              /* return */ False()
            case 1 =>
              /* return */ newargs.head
            case _ =>
              /* return */ Or( newargs : _* )
          }
        }
        case _ =>
          /* return */ phi
      }
    }

    /**
      * Finds the LHS primed variable of a well-formed formula (given by the ID).
      *
      * Well-formed means that the left hand side is a single primed variable.
      * Undefined behaviour if the formula is not well-formed or if the ID is invalid.
      *
      * @param i The UID of a formula, assumed to be valid.
      * @return The name of the variable, if the expression is well-formed, otherwise None.
      * @see [[rvars]]
      */
    def lvar( i : Int ) : Option[String] = {
      UniqueDB.get( UID( i ) ) match {
        case OperEx( TlaSetOper.in, OperEx( TlaActionOper.prime, NameEx( name ) ), _ ) => Some( name )
        case _ => None
      }
    }

    /**
      * Extracts all primed subexpressions within a given expression, regardless of nesting depth.
      *
      * @param ex An arbitrary TLA expression.
      * @return A set of names. Each name appears uniquely, regardless of
      *         multiple occurrences with different UIDs.
      */
    def findPrimes( ex : TlaEx ) : Set[String] = {
      ex match {
        case OperEx( TlaActionOper.prime, NameEx( name ) ) =>
          /* return */ Set( name )
        case OperEx( _, args@_* ) =>
          /* return */ args.map( findPrimes ).fold( Set[String]() ) { ( a, b ) => a ++ b }
        case _ =>
          /* return */ Set[String]()
      }
    }

    /**
      * Finds the RHS primed variables of an arbitrary formula (given by the ID).
      *
      * The RHS does not need to be well-formed. Undefined behaviour if the ID is invalid.
      *
      * @param i The UID of a formula, assumed to be valid.
      * @return A set of names of the variables, unprimed.
      * @see [[lvar]]
      */
    def rvars( i : Int ) : Set[String] =
      UniqueDB.get( UID( i ) ) match {
        case OperEx( TlaSetOper.in, OperEx( TlaActionOper.prime, NameEx( _ ) ), set ) => findPrimes( set )
        case _ => Set[String]()
      }

    /**
      * The ⊏ binary relation.
      *
      * i ⊏ j ⇔ [[lvar]]( i ) ∈ [[rvars]]( j ).
      *
      * @param i The UID of the first term.
      * @param j The UID of the second term.
      * @return `true` iff the relation is satisfied.
      */
    def sqsubset( i : Int,
                  j : Int
                ) : Boolean = {
      lvar( i ).exists( rvars( j ).contains  )
    }

    type seenType = Set[Int]
    type dependencySetType = Set[(Int, Int)]
    type deltaType = Map[String, BoolFormula]
    type recursionData = (seenType, dependencySetType, dependencySetType, deltaType)

    /**
      * Main internal method.
      *
      * Extracts all relevant information in a single pass.
      * We assume the input is preprocessed, all terms that are not of the form a' ∈ B
      * are ignored for assignment (that includes all a' = ... terms).
      *
      * @param p_phi  The next-step formula.
      * @param p_vars The set of all variable names relevant to the spec.
      * @return A triple `( seen, D, deltas )` where `seen` is the set of all
      *         leaf IDs, `D` is the set of dependent indices and `deltas` is a map storing
      *         δ,,v,,(ϕ) for every v.
      */
    def massProcess( p_phi : TlaEx,
                     p_vars : Set[String]
                   ) : ( seenType, dependencySetType, deltaType ) = {
      val (seen, deps, _, deltas) = innerMassProcess( p_phi, p_vars )
      /* return */ (seen, deps, deltas.map( pa => (pa._1, simplify( pa._2 )) ))
    }

    /**
      * Recursive submethod called within [[massProcess]].
      *
      * @return An additional extra set of independent indices, as bookkeeping,
      *         which is discarded in the return of [[massProcess]].
      */
    def innerMassProcess( p_phi : TlaEx,
                          p_vars : Set[String]
                        ) : recursionData = {

      /** We name the default arguments to return at irrelevant terms  */
      val defaultMap = ( for {v <- p_vars} yield (v, False()) ).toMap
      val defaultArgs = (Set[Int](), Set[(Int, Int)](), Set[(Int, Int)](), defaultMap)

      p_phi match {
        /**
          * The δ_v function is defined only on boolean conjunctions/disjunctions
          * and leafs ( ∈-expressions ). At a leaf with id i, if [[lvar]](i) is v then
          * the δ_v formula evaluates to a fresh boolean variable A_i. Otherwise, we compute the
          * δ for all branches and flip the boolean connective.
          *
          * The (in)dependece sets are directly computable at leafs, for a leaf with id i
          * the dep. set D is {(i,i)} and the indep. set I is {}.
          * Otherwise, at a boolean connective, we first compute all (D_j,I_j) pairs for all children
          * and the set S' of all seen ids so far. To obtain (D_i,I_i) at the call level, recall that
          * D_i \cup I_i = S' \times S' = S, as each pair is either dependent of independent.
          * Let D_i' be the union of all D_j, similarly for I_i'. We need to determine whether
          * all elements of U = S \ ( D_i' \cup I_i') should belong to D_i or I_i.
          * If the connective is AND, then the first common ancestor of all pairs in U is this AND
          * node and therefore all pairs in U are dependent, since any branch will include all
          * subbranches of an AND node. Then, D_i = S \ I_i' and I_i = I_i'.
          * Conversely, if the node is an OR, D_i = D_i' and I_i = S \ D_i'.
          *
          * The set of seen elements is simply the union of all such child sets.
          *
          */
        case OperEx( oper, args@_* ) =>
          oper match {
            /** Recursive case */
            case TlaBoolOper.and | TlaBoolOper.or =>

              /** First, process all children */
              val processedArgs : Seq[recursionData] = args.map( innerMassProcess( _, p_vars ) )

              /** Next, compute a map from each v to a sequence of all child delta_v formulas  */
              val newMapArgs : Map[String, Seq[BoolFormula]] =
                ( for {v <- p_vars} yield
                  (v,
                    processedArgs.map(
                      /** Take the current delta_v. We know none of them are None by construction */
                      _._4.get( v ).head
                    )
                  )
                  ).toMap

              /**
                * The rest we obtain by folding and taking the unions of all components.
                * The default arguments are empty sets as not to impact the result.
                */
              val (seen, depSet, indepSet, _) = processedArgs.fold(
                defaultArgs
              ) {
                ( a, b ) =>
                  (a._1 ++ b._1,
                    a._2 ++ b._2,
                    a._3 ++ b._3,
                    defaultArgs._4 // irrelevant
                  )
              }

              /** Deltas flip boolean connectives */
              val newMap : deltaType =
                (
                  for {v <- p_vars}
                    yield (v,
                      if ( oper == TlaBoolOper.and )
                        Or( newMapArgs( v ) : _* )
                      else
                        And( newMapArgs( v ) : _* )
                    )
                  ).toMap

              /** S is the set of all index pairs which we will be certain about after this step */
              val S : dependencySetType = for {x <- seen; y <- seen} yield (x, y)

              /** One set is unchanged, the other is the remeining elements from S */
              oper match {
                case TlaBoolOper.and => (seen, S -- indepSet, indepSet, newMap)
                case TlaBoolOper.or => (seen, depSet, S -- depSet, newMap)
              }

            /** Base case */
            case TlaSetOper.in =>

              /** First, we check for well-formed expr., i.e. a' \in B */
              args.head match {
                case OperEx( TlaActionOper.prime, NameEx( name ) ) =>
                  val n : Int = p_phi.ID.id
                  /** Use the definition of delta_v for base cases */
                  val newmap =
                    ( for {v <- p_vars}
                      yield (v,
                        if ( name == v )
                          Variable( n )
                        else
                          False()
                      )
                      ).toMap

                  /** If well formed, S = {n}, D = {(n,n)}, I = {}, deltas = newmap */
                  /* return */ (Set[Int]( n ), Set[(Int, Int)]( (n, n) ), Set[(Int, Int)](), newmap)
                case _ =>

                  /** If not well-formed, ignore and return empty sets/trivial maps */
                  /* return */ defaultArgs
              }

            /** Quantifier introspection, all existential quanitifications may have nested assignemnts */
            case TlaBoolOper.exists =>
              /* return */ innerMassProcess( args.tail.tail.head, p_vars )

            /** Other case */
            case _ =>

              /** If the term is of any other form it is just ignored */
              /* return */ defaultArgs
          }

        /** Not an operator. We know the top-level call is on an OperEx. Other terms are ignored */
        case _ =>
          /* return */ defaultArgs
      }
    }

    /**
      * Allows calls to a com.microsoft.z3.FuncInterp object as if it were a String -> Int function.
      *
      * @param p_fun The funciton interpretation produced by the z3 solver.
      */
    class FunWrapper( p_fun : FuncInterp ) {
      /** Return value for arguments outside the relevant subdomain. */
      protected val m_default : Int = p_fun.getElse.asInstanceOf[IntNum].getInt

      /**
        * Internal map, corresponds to the restriction of the function represented by `p_fun`
        * to the relevant subdomain.
        */
      protected val m_map : Map[String, Int] =
        ( for {e : FuncInterp.Entry <- p_fun.getEntries}
          yield (
            "%s_%s".format( preprocessHelperFunctions.m_varSym, e.getArgs.head ),
            e.getValue.asInstanceOf[IntNum].getInt
          )
          ).toMap

      /** The wrapper can be called like a function. */
      def apply( arg : String ) : Int = m_map.getOrElse( arg, m_default )

      override def toString : String = m_map.toString
    }

  }

  /**
    * Given a Next formula and a set of variables, produces an SMT formula that
    * is satisfiable iff the assignment problem for the given formula has a solution.
    *
    * @param p_vars         The set of variables declared by the specification.
    * @param p_phi          The Next formula.
    * @param p_fileName     Optional parameter, if `p_fileName` is nonempty, a file with the
    *                       complete specification is produced, including set-logic,
    *                       check-sat and get-model commands. Set to None by default.
    * @return An SMTLIBv2 string to be passed to the z3 API.
    */
  def makeSpec( p_vars : Set[String],
                p_phi : TlaEx,
                p_fileName : Option[String] = None
              ) : String = {

    import preprocessHelperFunctions._

    /** Extract the list of leaf ids, the dependency set and the delta mapping */
    val (seen, deps, deltas) = massProcess( p_phi, p_vars )

    /**
      * We need two subsets of deps, one where the \sqsubset relation holds
      * and one where lvars match for constructing \phi_R and \phi^\exists!^
      * respectively.
      */
    val D_sqss = deps.filter( pa => sqsubset( pa._1, pa._2 ) )
    val D_exOne = deps.filter( pa => pa._1 < pa._2 && lvar( pa._1 ) == lvar( pa._2 ) )

    /** \phi_A */
    val aargs = deltas.values
    val aargsSMT = aargs.map( toSmt2 )

    /** \phi_R */
    val rargs =
      for {(i, j) <- D_sqss}
        yield
          Implies(
            And( Variable( i ),
              Variable( j )
            ),
            LtFns( i, j )
          )
    val rargsSMT = rargs.map( toSmt2 )

    /** \phi_R^{inj}^ */
    val injargs = for {i <- seen; j <- seen if i < j} yield NeFns( i, j )
    val injargsSMT = injargs.map( toSmt2 )

    /** \phi^{\exists!}^ */
    val exOneargs =
      for {(i, j) <- D_exOne}
        yield Neg( And( Variable( i ), Variable( j ) ) )
    val exOneargsSMT = exOneargs.map( toSmt2 )

    /** The constant/funciton declaration commands */
    val typedecls = seen.map( "( declare-fun %s_%s () Bool )".format( m_varSym, _ ) ).mkString( "\n" )
    val fndecls = "\n( declare-fun %s ( Int ) Int )\n".format( m_fnSym )

    /** Assert all of the constraints, as defined in \phi_S^{good}^ */
    val constraints = ( aargsSMT ++ rargsSMT ++ injargsSMT ++ exOneargsSMT ).map(
      str => "( assert %s )".format( str )
    ).mkString( "\n" )

    /** Partial return, sufficient for the z3 API */
    val ret = typedecls + fndecls + constraints

    /** Possibly produce standalone file */
    if ( p_fileName.nonEmpty ) {
      val logic = "( set-logic QF_UFLIA )\n"
      val end = "\n( check-sat )\n( get-model )\n( exit )"

      val pw = new PrintWriter( new File( p_fileName.get ) )
      pw.write( logic + ret + end )
      pw.close()

    }

     /* return */ ret
  }

  type StrategyType = Seq[UID]
  type AssignmentType = StrategyType

  /**
    * Point of access method, presents the solution to the assignment problem for
    * the specification `p_spec`.
    *
    * @param p_spec A SMTLIBv2 specification string, as required by the parser method of
    *               [[com.microsoft.z3.Context]].
    * @return `None`, if the assignment problem has no solution. Otherwise, returns a sequence
    *         of [[UID UIDs]], constituting a good assignmentStrategy, sorted by the
    *         ranking function, in ascending order.
    * @see [[[[getStrategy(p_vars:scala\.collection\.immutable\.Set[at\.forsyte\.apalache\.tla\.lir\.NameEx],p_phi:at\.forsyte\.apalache\.tla\.lir\.OperEx,p_fileName:String):Option[Seq[(at\.forsyte\.apalache\.tla\.lir\.UID,Boolean)]]* getStrategy]]]]
    **/
  def getStrategy( p_spec : String ) : Option[StrategyType] = {
    import preprocessHelperFunctions._

    /** Initialize a context and solver */
    val ctx = new Context()
    val solver = ctx.mkSolver()

    /** Parse the spec and add it to the solver */
    solver.add( ctx.parseSMTLIB2String( p_spec, null, null, null, null ) )

    /** Check sat, if not SAT terminate with None */
    val status = solver.check.toString
    if ( status != "SATISFIABLE" )
      return None

    /** If SAT, get a model. */
    val m = solver.getModel

    /** Extract the rank function. Should be the only (non-const.) function */
    val fnDecl = m.getFuncDecls

    fnDecl.size match{
      case 0 => { /** Only happens if Next is exactly 1 assignment */
        val trues = m.getConstDecls.withFilter( x => m.getConstInterp( x ).isTrue ).map( _.getName.toString )
        Some( trues.map( x => UID( x.substring( 2 ).toInt ) )  )
      }
      case 1 => {
        if ( fnDecl.size != 1 )
          return None

        /** Wrap the function so it can be used to sort the sequence later. */
        val wrap = new FunWrapper( m.getFuncInterp( fnDecl( 0 ) ) )

        /** Extract all constants which are set to true */
        val trues = m.getConstDecls.withFilter( x => m.getConstInterp( x ).isTrue ).map( _.getName.toString )

        /** Sort by rank */
        val sorted = trues.sortBy( x => wrap( x ) )

        /* return */ Some( sorted.map( x => UID( x.substring( 2 ).toInt ) ) )
      }
      case _ => None
    }
  }

  /**
    * Point of access method, presents the solution to the assignment problem for
    * the specification with variables `p_vars` and a Next-formula `p_phi`.
    *
    * @param p_vars     The set of variables declared by the specification.
    * @param p_phi      The Next formula.
    * @param p_fileName Optional parameter, if `p_fileName` is nonempty, a file with the complete
    *                   specification is also produced. Set to empty by default.
    * @return `None`, if the assignment problem has no solution. Otherwise, returns a sequence
    *         of [[UID UIDs]], constituting a good assignmentStrategy, sorted by the
    *         ranking function, in ascending order.
    * @see [[makeSpec]], [[[[getOrder(p_spec:String):Option[Seq[(at\.forsyte\.apalache\.tla\.lir\.UID,Boolean)]]* getOrder]]]]
    **/
  def getStrategy( p_vars : Set[String],
                   p_phi : TlaEx,
                   p_fileName : Option[String] = None
              ) : Option[StrategyType] = {
    /* return */ getStrategy( makeSpec( p_vars, p_phi, p_fileName ) )
  }

  type SymbNext = (AssignmentType, TlaEx)

  /**
    * Helper functions for [[getSymbNexts]].
    */
  protected object symbolicNextHelperFunctions {

    type LabelMapType = Map[UID, Set[UID]]

    /**
      * Merges two maps.
      * If a key is defined in both maps, the resulting map will associate it with
      * the union of sets in the individual maps.
      * @param p_map1 A map whose values are sets.
      * @param p_map2 A map whose values are sets.
      * @tparam K Map key type.
      * @tparam V Map value set element type.
      * @return
      */
    def joinSetMaps[K, V]( p_map1 : Map[K, Set[V]],
                           p_map2 : Map[K, Set[V]]
                         ) : Map[K, Set[V]] = {

      Map[K, Set[V]](
        ( p_map1.keySet ++ p_map2.keySet ).toSeq.map(
          k => (k, p_map1.getOrElse( k, Set[V]() ) ++ p_map2.getOrElse( k, Set[V]() ))
        ) : _*
      )
    }

    /**
      * Shorthand for common .getOrElse uses
      */
    def labelsAt( p_ex : TlaEx,
                  p_knownLabels : LabelMapType
                ) : Set[UID] = p_knownLabels.getOrElse( p_ex.ID, Set() )

    /**
      * Decides whether a given [[TlaEx]] is considered a leaf in the formula tree.
      * For our puposes, leaves are assignment candidates, i.e. expressions of the
      * form x' \in S.
      * @param p_ex Any TLA expression
      * @return `true` iff the expression is a leaf in the formula tree.
      */
    def leafJudge( p_ex : TlaEx ) : Boolean =
      p_ex match {
        case OperEx( TlaSetOper.in, OperEx( TlaActionOper.prime, NameEx( _ ) ), _ ) => true
        case _ => false
      }

    /**
      * Creates a partial label map at a leaf.
      * @param p_ex Leaf expression.
      * @param p_stratSet The assignment strategy.
      * @return The empty map, if the leaf is not part of the srategy, otherwise the one-key
      *         map, which assigns to the leaf ID a singleton which contains that ID.
      */
    def leafFun( p_ex : TlaEx,
                 p_stratSet : Set[UID]
               ) : LabelMapType = {
      if ( p_stratSet.contains( p_ex.ID ) ) {
        Map( p_ex.ID -> Set( p_ex.ID ) )
      }
      else
        Map()
    }

    /**
      * Constructs a new label at the parent from child labels.
      * @param p_ex Current node in the formula tree.
      * @param p_childResults All the maps computed at child nodes.
      * @return A new label map, which agrees with all child maps and additionally assigns to the current
      *         node ID the union of all label sets found at its children.
      */
    def parentFun( p_ex : TlaEx,
                   p_childResults : Seq[LabelMapType]
                 ) : LabelMapType = {

      /** Unify all child maps */
      val superMap = p_childResults.fold( Map() )( joinSetMaps )

      p_ex match {
        /** Guaranteed, if invoked by the [[SpecHandler]] */
        case OperEx( _, args@_* ) => {
          /** The set of all child labels */
          val mySet = args.map( labelsAt( _, superMap ) ).fold( Set() )( _ ++ _ )
          superMap + ( p_ex.ID -> mySet )
        }
        case _ => Map()
      }

    }

    /**
      * Returns a consistent labeling of the formula tree rooted at `p_phi`.
      *
      * The labeling assigns to every node in the formula tree a label set (a subset of `p_stratSet`),
      * where a parent label set is the union of all child label sets. The leaves are labled
      * either with their own IDs or not at all, depending on whether or not they are part of `p_stratSet`.
      * @param p_ex Root node.
      * @param p_stratSet Assignment strategy (as set)
      * @return A map representing a consistent labeling.
      * @see [[isConsistentLabeling]]
      */
    def labelAll( p_ex : TlaEx,
                  p_stratSet : Set[UID]
                ) : LabelMapType = {
      SpecHandler.bottomUpVal[LabelMapType](
        p_ex,
        leafJudge,
        leafFun( _, p_stratSet ),
        parentFun,
        Map()
      )
    }

    /**
      * Checks whether a triplet of a formula, a strategy and a labeling is consistent.
      */
    def isConsistentLabeling( p_ex : TlaEx,
                              p_stratSet : Set[UID],
                              p_knownLabels : LabelMapType
                           ) : Boolean = {
      p_ex match {
        /** If inner node, own labels must exist and be equal to the union of the child labels */
        case OperEx( TlaBoolOper.and | TlaBoolOper.or, args@_* ) =>
          p_knownLabels.contains( p_ex.ID ) && p_knownLabels( p_ex.ID ) == args.map(
            x => p_knownLabels.getOrElse( x.ID, Set() )
          ).fold( Set() )( _ ++ _ )
        case _ =>
          /** If leaf and part of the strategy, the label set must be a singleton */
          if ( p_stratSet.contains( p_ex.ID ) )
            p_knownLabels.contains( p_ex.ID ) && p_knownLabels( p_ex.ID ) == Set( p_ex.ID )
          /** Otherwise, must be unlabeled */
          else
            !p_knownLabels.contains( p_ex.ID )
      }
    }

    /**
      * Checks whether the assignment candidate `p_currentAsgn` lies entirely on a single branch (
      * as defined in the paper).
      *
      * This property is intuitively interpreted as follows:
      * a) The empty assignment is always good, as it vacouously holds that all of its elements lie on
      * the same branch.
      * a) The candidate `p_currentAsgn` is never good at a node `p_ex`, if the subtree rooted at `p_ex` does
      * not witness at least all labels from `p_currentAsgn`, i.e. the lables at `p_ex` must be a superset
      * or `p_currentAsgn`
      * a) In the case of an AND node, the individual assignments can be spread throughout the subtrees
      * arbitrarily, since a branch witnesses all children of an AND node.
      * a) In the case of an OR node, all the assignments must lie in the same subtree.
      *
      * @param p_ex Root of the formula subtree.
      * @param p_knownLabels Known labeling.
      * @param p_currentAsgn Assignment candidate.
      * @return `true` iff `p_currentAsgn` satisfied the above property.
      */
    def isGoodAtNode( p_ex : TlaEx,
                      p_knownLabels : LabelMapType,
                      p_currentAsgn : Set[UID]
                  ) : Boolean = {
      /** Vacuous termination */
      if ( p_currentAsgn.isEmpty )
        true
      /** Impossibility termination */
      else if ( !p_currentAsgn.subsetOf( labelsAt( p_ex, p_knownLabels ) ) )
        false
      else
        p_ex match {
          case OperEx( TlaBoolOper.and, args@_* ) =>
            /**
              * In the AND case, we check the intersections of `p_currentAsgn` with
              * subtree labels. If the labeling is consistent, each subtree will have disjoint labels.
              */
            args.forall(
              nd => isGoodAtNode(
                nd,
                p_knownLabels,
                p_currentAsgn.intersect( labelsAt( nd, p_knownLabels ) )
              )
            )
          case OperEx( TlaBoolOper.or, args@_* ) =>
            /** In the OR case, there must exist a child node, where `p_currentAsgn` is good. */
            args.exists( nd => isGoodAtNode( nd, p_knownLabels, p_currentAsgn ) )
          case OperEx( TlaBoolOper.exists | TlaBoolOper.forall, _, _, body ) =>
            /** If we see an existential quantifier, we look inside. */
            isGoodAtNode( body, p_knownLabels, p_currentAsgn )
          case _ =>
            /**
              * If we are at a leaf and we did not terminate because of the subset inclusion exit,
              * then it must hold that `p_currentAsgn` is a subset of the leaf labels. If the leaf labels
              * were empty, we would have terminated at p_currentAsgn.isEmpty by necessity,
              * so both the leaf labels and `p_currentAsgn` must be singletons, containing the same
              * element. Therefore, we can conclude the property holds and return `true`.
              */
            true //p_currentAsgn.subsetOf( labelsAt( p_ex, p_knownLabels ) )

        }
    }

    /** Calls [[isGoodAtNode]] on the entire formula. */
    def isGoodAssignment( p_phi : TlaEx,
                          p_knownLabels : LabelMapType,
                          p_asgnSet : Set[UID]
                        ) : Boolean = {
      isGoodAtNode( p_phi, p_knownLabels, p_asgnSet )
    }

    /**
      * Extracts the name from a leaf node, if possible.
      * @param p_ex Leaf candidate
      * @return None, if the candidate is not a leaf, otherwise the name of the left hand side variable.
      */
    def getLHSName( p_ex : TlaEx ) : Option[String] = {
      p_ex match {
        case OperEx( TlaSetOper.in, OperEx( TlaActionOper.prime, NameEx( name ) ), _ ) => Some( name )
        case _ => None
      }
    }

    type VarMapType = Map[String, Set[UID]]

    def singleVarMap( p_ex : TlaEx ) : VarMapType = {
      getLHSName( p_ex ) match {
        case Some( s ) => Map( s -> Set( p_ex.ID ) )
        case None => Map()
      }
    }

    def getVarMap( p_stratSet : Set[UID] ) : VarMapType = {
      p_stratSet.map(
        x => singleVarMap( UniqueDB.get( x ) )
      ).fold( Map() )( joinSetMaps )
    }

    def createBranches( p_phi : TlaEx,
                        p_current : Seq[Set[UID]],
                        p_varMap : VarMapType,
                        p_labels : LabelMapType,
                        p_varName : String
                      ) : Seq[Set[UID]] = {
      val uidsForS = p_varMap( p_varName ).toSeq
      val possibilities = uidsForS.map(
        uid => p_current.map( S => S + uid ).filter( isGoodAssignment( p_phi, p_labels, _ ) )
      )
      possibilities.fold( Seq() )( _ ++ _ )
    }

    def makeAssignments( p_phi : TlaEx,
                         p_labels : LabelMapType,
                         p_strategy : Set[UID]
                       ) : Seq[Set[UID]] = {

      val byVar : VarMapType = getVarMap( p_strategy )

      byVar.keySet.foldLeft( Seq[Set[UID]]( Set() ) )(
        createBranches( p_phi, _, byVar, p_labels, _ )
      )
    }

    def assignmentFilter( p_ex : TlaEx,
                          p_asgnSet : Set[UID],
                          p_labels : LabelMapType
                        ) : TlaEx = {
      p_ex match {
        case OperEx( TlaBoolOper.or, args@_* ) => {
          /**
            * Or-branches have the property that they either contain
            * all assignments or none of them. Therefore it suffices to check for
            * non-emptiness of the intersection.
            */
          val newArgs = args.filter(
            x => p_labels.getOrElse( x.ID, Set() ).exists( y => p_asgnSet.contains( y ) )
          )
          if ( newArgs.isEmpty )
            p_ex
          else {
            assert( newArgs.size == 1 )
            newArgs.head // if nonempty, it has exactly 1 member
          }
        }
        case _ => p_ex
      }
    }

    def mkOrdered( p_asgnSet : Set[UID],
                   p_strat : StrategyType
                 ) : AssignmentType = {
      p_strat.filter( p_asgnSet.contains )
    }

    def mkNext( p_phi : TlaEx,
                p_asgnSet : Set[UID],
                p_strat : StrategyType,
                p_labels : LabelMapType
              ) : SymbNext = {
      (
        mkOrdered( p_asgnSet, p_strat ),
        SpecHandler.getNewEx( p_phi, assignmentFilter( _, p_asgnSet, p_labels ) )
      )
    }

  }

  def getSymbNexts( p_phi : TlaEx, p_asgnStrategy : StrategyType ) : Seq[SymbNext] = {
    /**
      * Every assignment node "bubbles" up, colors its path. Then, search from root for all
      */

    import symbolicNextHelperFunctions._

    val stratSet = p_asgnStrategy.toSet
    val labels = labelAll( p_phi, stratSet )

    assert( isConsistentLabeling( p_phi, stratSet, labels ) )

    val asgnBranches = makeAssignments( p_phi, labels, stratSet )

    asgnBranches.map( mkNext( p_phi, _, p_asgnStrategy, labels ) )

  }


  def getSymbolicTransitions( p_variables: Set[String],
                              p_phi : TlaEx
                            ) : Option[Seq[(AssignmentType, TlaEx)]] = {
      getStrategy( makeSpec( p_variables, p_phi ) ).map( getSymbNexts( p_phi, _ ) )
  }

}
