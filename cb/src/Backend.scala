package cb

/** The seam between the facade and a source of type information */
trait Backend {

  /** All `val`s declared by the scanned classes and objects. */
  def valMembers: Vector[ValMember]

  /** All `def`s declared by the scanned classes and objects. */
  def defMembers: Vector[DefMember]

  /** All classes and traits found on the classpath. */
  def classes: Vector[TypeEntity]

  /** All objects (modules) found on the classpath. */
  def objects: Vector[TypeEntity]

  /** `a =:= b` in the backend's type universe (aliases dealiased). */
  def sameType(a: TypeRef, b: TypeRef): Boolean

  /** `sub <:< sup` in the backend's type universe. */
  def conforms(sub: TypeRef, sup: TypeRef): Boolean

  /** True if `a` and `b` share a type constructor, ignoring type arguments. */
  def sameConstructor(a: TypeRef, b: TypeRef): Boolean

  /** The runtime value of `member` when its owner is a singleton `object`, read
   *  without constructing anything. `None` for class-owned members (no instance) */
  def instanceOf(member: Member): Option[Any]
}

/** Captures a compile-time type `T` as a [[TypeRef]]. On Scala 2 (only this for now)
 *  we use `WeakTypeTag` so that wildcard like `IO[_]` work */
trait TypeCapture[T] {
  def typeRef: TypeRef
}
