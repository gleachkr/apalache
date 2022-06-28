package at.forsyte.apalache.tla.lir.oper

/**
 * Operators over variants.
 *
 * @author
 *   Igor Konnov
 */
abstract class VariantOper extends TlaOper {
  override def interpretation: Interpretation.Value = Interpretation.StandardLib
}

object VariantOper {

  /**
   * Variant constructor.
   */
  object variant extends VariantOper {
    override def name: String = "Variants!Variant"

    override def arity: OperArity = FixedArity(2)

    override val precedence: (Int, Int) = (100, 100)
  }

  /**
   * Set filter over variants.
   */
  object variantFilter extends VariantOper {
    override def name: String = "Variants!VariantFilter"

    override def arity: OperArity = FixedArity(2)

    override val precedence: (Int, Int) = (100, 100)
  }

  /**
   * Match a variant by tag.
   */
  object variantMatch extends VariantOper {
    override def name: String = "Variants!VariantMatch"

    override def arity: OperArity = FixedArity(4)

    override val precedence: (Int, Int) = (100, 100)
  }

  /**
   * Match a single variant.
   */
  object variantUnwrap extends VariantOper {
    override def name: String = "Variants!VariantUnwrap"

    override def arity: OperArity = FixedArity(2)

    override val precedence: (Int, Int) = (100, 100)
  }

  /**
   * Get the value associated with the tag name, if the tag is matching the tag name. Otherwise, return the default
   * value.
   */
  object variantGetOrElse extends VariantOper {
    override def name: String = "Variants!VariantGetOrElse"

    override def arity: OperArity = FixedArity(3)

    override val precedence: (Int, Int) = (100, 100)
  }

  /**
   * Unsafely extract the value associated with a tag. If the tag name is different from the actual tag, return some
   * value of proper type.
   */
  object variantGetUnsafe extends VariantOper {
    override def name: String = "Variants!VariantGetUnsafe"

    override def arity: OperArity = FixedArity(2)

    override val precedence: (Int, Int) = (100, 100)
  }
}
