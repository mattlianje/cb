package cb

/**
 * Backend neutral model of cb ... we don't touch scala.reflect here
 * a BE later materializes these values
 */

/** Source position of an entity. Pickles carry none, so a reflection backend
 *  always yields [[NoPosition]]; a SemanticDB backend can give [[SourcePosition]]. */
sealed trait Position {
  def isKnown: Boolean
}
case object NoPosition extends Position {
  def isKnown           = false
  override def toString = "<no position>"
}
final case class SourcePosition(path: String, line: Int) extends Position {
  def isKnown           = true
  override def toString = s"$path:$line"
}

/** The enclosing class/object/package of an entity. */
final case class Owner(fqn: String, name: String)

final case class TypeRef(show: String, args: List[TypeRef], native: AnyRef) {
  override def toString =
    if (args.isEmpty) show else s"$show[${args.map(_.toString).mkString(", ")}]"
}

/** Anything `cb` can surface: a member, a class, or an object. */
sealed trait Entity {
  def name: String
  def owner: Owner
  def isPublic: Boolean

  /** Fully-qualified names of the annotation types applied to this entity. */
  def annotations: List[String]
  def pos: Position

  def fqn: String = if (owner.fqn.isEmpty) name else s"${owner.fqn}.$name"

  /** True if this entity lives in `pkg` or a sub-package of it. */
  def in(pkg: String): Boolean = fqn.startsWith(pkg + ".")

  def isAnnotated(annotationFqn: String): Boolean = annotations.contains(annotationFqn)
}

/** A `val` or `def`. */
sealed trait Member extends Entity {

  /** For a `val`, its type; for a `def`, its (final) result type. */
  def memberType: TypeRef
  def isImplicit: Boolean
}

final case class ValMember(
  name: String,
  owner: Owner,
  memberType: TypeRef,
  isPublic: Boolean,
  isImplicit: Boolean,
  annotations: List[String],
  pos: Position
) extends Member

final case class DefMember(
  name: String,
  owner: Owner,
  memberType: TypeRef,
  isPublic: Boolean,
  isImplicit: Boolean,
  annotations: List[String],
  pos: Position
) extends Member

/** A class, trait, or object. */
final case class TypeEntity(
  name: String,
  owner: Owner,
  self: TypeRef,
  parents: List[TypeRef],
  isModule: Boolean,
  isImplicit: Boolean,
  isPublic: Boolean,
  annotations: List[String],
  pos: Position
) extends Entity
