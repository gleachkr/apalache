package at.forsyte.apalache.tla.lir.transformations.standard

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper._
import at.forsyte.apalache.tla.lir.transformations.{PredResult, PredResultFail, PredResultOk}
import at.forsyte.apalache.tla.lir.values._

import scala.collection.immutable.HashSet

/**
 * <p>Test whether the expressions fit into the flat fragment: all calls to user operators are inlined, except the calls
 * to nullary let-in definitions.</p>
 *
 * <p>To get a better idea of the accepted fragment, check TestKeraLanguagePred.</p>
 *
 * @see
 *   TestKeraLanguagePred
 * @author
 *   Igor Konnov
 */
class KeraLanguagePred extends ContextualLanguagePred {
  override protected def isOkInContext(letDefs: Set[String], expr: TlaEx): PredResult = {
    expr match {
      case ValEx(TlaBool(_)) | ValEx(TlaInt(_)) | ValEx(TlaStr(_)) =>
        PredResultOk()

      case ValEx(TlaIntSet) | ValEx(TlaNatSet) | ValEx(TlaBoolSet) =>
        PredResultOk()

      case NameEx(_) =>
        PredResultOk()

      case OperEx(oper, arg) if KeraLanguagePred.unaryOps.contains(oper) =>
        isOkInContext(letDefs, arg)

      case OperEx(oper, lhs, rhs) if KeraLanguagePred.binaryOps.contains(oper) =>
        isOkInContext(letDefs, lhs)
          .and(isOkInContext(letDefs, rhs))

      case OperEx(oper, args @ _*) if KeraLanguagePred.naryOps.contains(oper) =>
        args.foldLeft[PredResult](PredResultOk()) { case (r, arg) =>
          r.and(isOkInContext(letDefs, arg))
        }

      case OperEx(oper, NameEx(_), set, pred) if KeraLanguagePred.bindingOps.contains(oper) =>
        isOkInContext(letDefs, set).and(isOkInContext(letDefs, pred))

      case OperEx(TlaControlOper.ifThenElse, pred, thenEx, elseEx) =>
        isOkInContext(letDefs, pred)
          .and(isOkInContext(letDefs, thenEx))
          .and(isOkInContext(letDefs, elseEx))

      case OperEx(ApalacheOper.foldSet | ApalacheOper.foldSeq, opName, base, collection) =>
        isOkInContext(letDefs, opName)
          .and(isOkInContext(letDefs, base))
          .and(isOkInContext(letDefs, collection))

      case OperEx(ApalacheOper.mkSeq, len, opName) =>
        isOkInContext(letDefs, opName)
          .and(isOkInContext(letDefs, len))

      case OperEx(ApalacheOper.repeat, opName, bound, base) =>
        isOkInContext(letDefs, opName)
          .and(isOkInContext(letDefs, bound))
          .and(isOkInContext(letDefs, base))

      case OperEx(oper, args @ _*)
          if oper == TlaSetOper.map || oper == TlaFunOper.funDef || oper == TlaFunOper.recFunDef =>
        val evenArgs = args.zipWithIndex.filter { p => p._2 % 2 == 0 }.map {
          _._1
        }
        evenArgs.foldLeft[PredResult](PredResultOk()) { case (r, arg) =>
          r.and(isOkInContext(letDefs, arg))
        }

      case OperEx(TlaFunOper.recFunRef) =>
        PredResultOk()

      case OperEx(TlaSetOper.seqSet, _) =>
        val message = "Seq(_) produces an infinite set of unbounded sequences. See: " + KeraLanguagePred.MANUAL_LINK_SEQ
        PredResultFail(Seq((expr.ID, message)))

      case LetInEx(body, defs @ _*) =>
        // check the let-definitions first, in a sequence, as they may refer to each other
        val defsResult = eachDefRec(letDefs, defs.toList)
        val newLetDefs = defs.map(_.name).toSet
        // check the terminal expression in the LET-IN chain, by assuming the context generated by the definitions
        defsResult
          .and(isOkInContext(letDefs ++ newLetDefs, body))

      case e @ OperEx(TlaOper.apply, NameEx(opName), args @ _*) =>
        // the only allowed case is calling a nullary operator that was declared with let-in
        if (!letDefs.contains(opName)) {
          PredResultFail(List((e.ID, s"undeclared operator $opName")))
        } else if (args.nonEmpty) {
          PredResultFail(List((e.ID, s"non-nullary operator $opName")))
        } else {
          PredResultOk()
        }

      case e @ OperEx(ApalacheInternalOper.notSupportedByModelChecker, ValEx(TlaStr(msg))) =>
        // unconditionally report an error
        PredResultFail(List((e.ID, "Not supported: " + msg)))

      case e =>
        PredResultFail(List((e.ID, e.toString)))
    }
  }
}

object KeraLanguagePred {
  val MANUAL_LINK_SEQ = "https://apalache.informal.systems/docs/apalache/known-issues.html#using-seqs"

  private val singleton = new KeraLanguagePred

  protected val unaryOps: HashSet[TlaOper] =
    HashSet(
        TlaActionOper.prime,
        TlaBoolOper.not,
        TlaArithOper.uminus,
        TlaSetOper.union,
        TlaSetOper.powerset,
        TlaFunOper.domain,
        TlaFiniteSetOper.isFiniteSet,
        TlaFiniteSetOper.cardinality,
        TlaSeqOper.head,
        TlaSeqOper.tail,
        TlaSeqOper.len,
        VariantOper.variantTag,
        ApalacheOper.skolem,
        ApalacheOper.gen,
        ApalacheOper.expand,
        ApalacheOper.constCard,
        ApalacheOper.setAsFun,
        ApalacheOper.guess,
        ApalacheInternalOper.apalacheSeqCapacity,
        // for the future
        //    TlaActionOper.enabled,
        //    TlaActionOper.unchanged,
        //    TlaTempOper.box,
        //    TlaTempOper.diamond
    ) ////

  protected val binaryOps: HashSet[TlaOper] =
    HashSet(
        TlaOper.eq,
        TlaFunOper.app,
        TlaSetOper.funSet,
        TlaSeqOper.append,
        TlaArithOper.plus,
        TlaArithOper.minus,
        TlaArithOper.mult,
        TlaArithOper.div,
        TlaArithOper.mod,
        TlaArithOper.exp,
        TlaArithOper.dotdot,
        TlaArithOper.lt,
        TlaArithOper.gt,
        TlaArithOper.le,
        TlaArithOper.ge,
        TlaSetOper.in,
        TlaSetOper.cup,
        TlaSeqOper.concat,
        ApalacheOper.assign,
        VariantOper.variant,
        VariantOper.variantGetUnsafe,
        VariantOper.variantFilter,
        // for the future
        //      TlaActionOper.composition,
        //      TlaTempOper.leadsTo,
        //      TlaTempOper.guarantees,
    ) ////

  protected val naryOps: HashSet[TlaOper] =
    HashSet(
        TlaBoolOper.and,
        TlaBoolOper.or,
        TlaSetOper.enumSet,
        TlaFunOper.except,
        TlaFunOper.tuple,
        TlaFunOper.rec,
        TlaSeqOper.subseq,
        TlaOper.label,
        VariantOper.variantGetOrElse,
    ) /////

  protected val bindingOps: HashSet[TlaOper] =
    HashSet(
        TlaBoolOper.exists,
        TlaBoolOper.forall,
        TlaOper.chooseBounded,
        TlaSetOper.filter,
    ) /////

  def apply(): KeraLanguagePred = singleton
}
