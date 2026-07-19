package cb

/** Thrown by `assertAll` when an architectural rule is violated. */
final class CbAssertionError(message: String) extends AssertionError(message)

/**
 * A fluent, immutable query over members (`val`s and `def`s).
 *
 * Every combinator returns a new [[MemberQuery]]; terminal operations
 * (`count`, `toList`, `foreach`, `assertAll`) consume it.
 */
final class MemberQuery private[cb] (backend: Backend, val items: Vector[Member]) {

  private def next(xs: Vector[Member]): MemberQuery = new MemberQuery(backend, xs)

  /** Members whose type is exactly `T`. */
  def ofType[T](implicit cap: TypeCapture[T]): MemberQuery =
    next(items.filter(m => backend.sameType(m.memberType, cap.typeRef)))

  /**
   * Members whose type is `T`, optionally including subtypes of `T`.
   *
   * {{{ cb.vals.ofType[Person](subtypes = true) }}}
   */
  def ofType[T](subtypes: Boolean)(implicit cap: TypeCapture[T]): MemberQuery =
    if (subtypes) next(items.filter(m => backend.conforms(m.memberType, cap.typeRef)))
    else ofType[T]

  /** Members whose type has the same type constructor as `T` (e.g. `IO[_]`). */
  def returning[T](implicit cap: TypeCapture[T]): MemberQuery =
    next(items.filter(m => backend.sameConstructor(m.memberType, cap.typeRef)))

  /** Alias of [[returning]], reads better for givens: `givens.of[JsonCodec[_]]`. */
  def of[T](implicit cap: TypeCapture[T]): MemberQuery = returning[T]

  /** Members declared in `pkg` or a sub-package of it. */
  def in(pkg: String): MemberQuery = next(items.filter(_.in(pkg)))

  /** Members annotated with `A`. */
  def annotated[A](implicit cap: TypeCapture[A]): MemberQuery =
    next(items.filter(_.isAnnotated(cap.typeRef.show)))

  def filter(p: Member => Boolean): MemberQuery = next(items.filter(p))

  def count: Int                       = items.size
  def isEmpty: Boolean                 = items.isEmpty
  def nonEmpty: Boolean                = items.nonEmpty
  def toList: List[Member]             = items.toList
  def foreach(f: Member => Unit): Unit = items.foreach(f)

  /**
   * Materialize the runtime value of every member that lives in a singleton
   * `object` (e.g. case-class `val`s scattered across your code). Class-owned
   * members are skipped, since reading them would require an instance. Reading a
   * member runs its initializer, so this is effectful.
   *
   * {{{ cb.vals.ofType[Route](subtypes = true).instances[Route] }}}
   */
  def instances[T]: List[T] =
    items.iterator.flatMap(backend.instanceOf).map(_.asInstanceOf[T]).toList

  /**
   * Like [[instances]], but pairs every materialized value with the source path
   * of the `object` it was declared in (see [[TypeEntity.sourcePath]]), joined on
   * the owner. The path is `None` when the backend has no `SourceFile` for it.
   *
   * {{{ cb.vals.ofType[Route](subtypes = true).instancesLocated[Route] }}}
   */
  def instancesLocated[T]: List[(T, Option[String])] = {
    val srcByFqn = backend.objects.iterator.map(o => o.fqn -> o.sourcePath).toMap
    items.iterator.flatMap { m =>
      backend.instanceOf(m).map(v => (v.asInstanceOf[T], srcByFqn.getOrElse(m.owner.fqn, None)))
    }.toList
  }

  /** Throw [[CbAssertionError]] with `msg` and the offenders if any member fails `p`. */
  def assertAll(p: Member => Boolean, msg: String): Unit = {
    val offenders = items.filterNot(p)
    if (offenders.nonEmpty)
      throw new CbAssertionError(
        s"$msg\n" + offenders.map(m => s"  - ${m.fqn}: ${m.memberType}").mkString("\n")
      )
  }
}

/**
 * A fluent, immutable query over classes, traits, and objects.
 */
final class TypeQuery private[cb] (backend: Backend, val items: Vector[TypeEntity]) {

  private def next(xs: Vector[TypeEntity]): TypeQuery = new TypeQuery(backend, xs)

  /**
   * Types that extend `T` (strict subtypes; excludes `T` itself).
   *
   * Works with a wildcard, e.g. `extending[Codec[_]]`; the excluded "self" is
   * matched by type constructor so the `Codec` trait itself never leaks in.
   */
  def extending[T](implicit cap: TypeCapture[T]): TypeQuery =
    next(items.filter { t =>
      backend.conforms(t.self, cap.typeRef) && !backend.sameConstructor(t.self, cap.typeRef)
    })

  /** Alias of [[extending]], reads better for typeclasses: `implementing[Codec[_]]`. */
  def implementing[T](implicit cap: TypeCapture[T]): TypeQuery = extending[T]

  /** Only implicit types (i.e. `implicit object`/`implicit class` instances). */
  def implicits: TypeQuery = next(items.filter(_.isImplicit))

  /** Types (exactly) equal to `T`. */
  def ofType[T](implicit cap: TypeCapture[T]): TypeQuery =
    next(items.filter(t => backend.sameType(t.self, cap.typeRef)))

  /** Types declared in `pkg` or a sub-package of it. */
  def in(pkg: String): TypeQuery = next(items.filter(_.in(pkg)))

  /** Types annotated with `A`. */
  def annotated[A](implicit cap: TypeCapture[A]): TypeQuery =
    next(items.filter(_.isAnnotated(cap.typeRef.show)))

  def filter(p: TypeEntity => Boolean): TypeQuery = next(items.filter(p))

  def count: Int                           = items.size
  def isEmpty: Boolean                     = items.isEmpty
  def nonEmpty: Boolean                    = items.nonEmpty
  def toList: List[TypeEntity]             = items.toList
  def foreach(f: TypeEntity => Unit): Unit = items.foreach(f)

  def assertAll(p: TypeEntity => Boolean, msg: String): Unit = {
    val offenders = items.filterNot(p)
    if (offenders.nonEmpty)
      throw new CbAssertionError(
        s"$msg\n" + offenders.map(t => s"  - ${t.fqn}").mkString("\n")
      )
  }
}
