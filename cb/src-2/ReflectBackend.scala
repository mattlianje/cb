package cb

import scala.reflect.runtime.{universe => ru}
import scala.util.Try

/**
 * A [[Backend]] that reads Scala types out of `@ScalaSignature` pickles via the
 * `scala.reflect` runtime universe. Every symbol access is wrapped in `Try` so
 * one unresolvable class never fails the whole scan.
 */
final class ReflectBackend(
  val mirror: ru.Mirror,
  names: List[String],
  sources: Map[String, String] = Map.empty
) extends Backend {
  import ReflectBackend._

  private def clean(n: ru.Name): String = n.decodedName.toString.trim

  private lazy val scalaNames: Vector[String] =
    names.iterator.map(scalaName).toVector.distinct

  private lazy val classSyms: Vector[ru.ClassSymbol] =
    scalaNames.flatMap(n => Try(mirror.staticClass(n)).toOption)

  private lazy val moduleSyms: Vector[ru.ModuleSymbol] =
    scalaNames.flatMap(n => Try(mirror.staticModule(n)).toOption)

  private def ownerOf(s: ru.Symbol): Owner =
    Owner(s.fullName, clean(s.name))

  private def annotationsOf(s: ru.Symbol): List[String] =
    Try {
      s.info // force unpickling so annotations are populated
      s.annotations.map(a => a.tree.tpe.dealias.typeSymbol.fullName)
    }.getOrElse(Nil)

  private def valOf(m: ru.MethodSymbol, owner: Owner): ValMember =
    ValMember(
      clean(m.name),
      owner,
      toTypeRef(m.returnType.finalResultType),
      m.isPublic,
      m.isImplicit,
      annotationsOf(m),
      NoPosition
    )

  private def defOf(m: ru.MethodSymbol, owner: Owner): DefMember =
    DefMember(
      clean(m.name),
      owner,
      toTypeRef(m.returnType.finalResultType),
      m.isPublic,
      m.isImplicit,
      annotationsOf(m),
      NoPosition
    )

  private def membersOf(info: ru.Type, owner: Owner): Vector[Member] = {
    val methods = Try(info.decls.toVector).getOrElse(Vector.empty).collect {
      case s if s.isMethod => s.asMethod
    }
    methods.collect {
      // a `val` is a stable getter; its backing field is skipped
      case m if m.isGetter && m.isStable                                        => valOf(m, owner)
      case m if !m.isConstructor && !m.isAccessor && !m.isGetter && !m.isSetter =>
        defOf(m, owner)
    }
  }

  private def typeEntity(sym: ru.Symbol, tpe: ru.Type, isModule: Boolean): TypeEntity =
    TypeEntity(
      clean(sym.name),
      ownerOf(sym.owner),
      toTypeRef(tpe),
      parentsOf(tpe),
      isModule,
      sym.isImplicit,
      sym.isPublic,
      annotationsOf(sym),
      NoPosition,
      sources.get(sym.fullName)
    )

  private lazy val allMembers: Vector[Member] =
    classSyms.flatMap(c => Try(membersOf(c.info, ownerOf(c))).getOrElse(Vector.empty)) ++
      moduleSyms.flatMap(m =>
        Try(membersOf(m.moduleClass.asClass.info, ownerOf(m))).getOrElse(Vector.empty)
      )

  lazy val valMembers: Vector[ValMember] = allMembers.collect { case v: ValMember => v }
  lazy val defMembers: Vector[DefMember] = allMembers.collect { case d: DefMember => d }

  lazy val classes: Vector[TypeEntity] =
    classSyms.flatMap(c => Try(typeEntity(c, c.toType, isModule = false)).toOption)

  lazy val objects: Vector[TypeEntity] =
    moduleSyms.flatMap(m =>
      Try(typeEntity(m, m.moduleClass.asClass.toType, isModule = true)).toOption
    )

  /** Supertypes of `tpe`, excluding `tpe` itself. */
  private def parentsOf(tpe: ru.Type): List[TypeRef] =
    Try(tpe.baseClasses.drop(1).map(s => toTypeRef(s.asType.toType))).getOrElse(Nil)

  private def nativeOf(r: TypeRef): ru.Type = r.native match {
    case null => ru.NoType
    case t    => t.asInstanceOf[ru.Type]
  }

  def sameType(a: TypeRef, b: TypeRef): Boolean =
    Try(nativeOf(a) =:= nativeOf(b)).getOrElse(false)

  // Nothing/Null are subtypes of everything; matching them as a subtype never
  // reflects user intent and would sweep in every `def foo: Nothing` stub or
  // synthetic `scala.Null` default-arg accessor.
  private lazy val bottomSyms: Set[ru.Symbol] =
    Set(ru.definitions.NothingClass, ru.definitions.NullClass)

  def conforms(sub: TypeRef, sup: TypeRef): Boolean =
    Try {
      val s = nativeOf(sub)
      s <:< nativeOf(sup) && !bottomSyms.contains(s.typeSymbol)
    }.getOrElse(false)

  def sameConstructor(a: TypeRef, b: TypeRef): Boolean =
    Try(nativeOf(a).typeConstructor =:= nativeOf(b).typeConstructor).getOrElse(false)

  private lazy val moduleByFqn: Map[String, ru.ModuleSymbol] =
    moduleSyms.map(m => m.fullName -> m).toMap

  def instanceOf(member: Member): Option[Any] =
    moduleByFqn.get(member.owner.fqn).flatMap { mod =>
      Try {
        val instance = mirror.reflectModule(mod).instance
        val getter   = mod.moduleClass.asClass.info.decl(ru.TermName(member.name)).asMethod
        mirror.reflect(instance).reflectMethod(getter).apply()
      }.toOption
    }
}

object ReflectBackend {

  /** JVM class name to Scala fqn: drop a module's trailing `$`, turn the inner
   *  class separator `$` into `.` (e.g. `a.B$C$` becomes `a.B.C`). */
  def scalaName(jvm: String): String = jvm.stripSuffix("$").replace('$', '.')

  /** Convert a `scala.reflect` type into a neutral [[TypeRef]]. */
  def toTypeRef(t0: ru.Type): TypeRef =
    Try {
      val t = t0.dealias
      TypeRef(t.typeSymbol.fullName, t.typeArgs.map(toTypeRef), t)
    }.getOrElse(TypeRef("<unknown>", Nil, ru.NoType))
}
