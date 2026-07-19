package cb

import scala.reflect.runtime.{universe => ru}
import io.github.classgraph.ClassGraph
import scala.collection.JavaConverters._
import java.net.URLClassLoader
import java.nio.file.{Files, Path}

/**
 * A queryable view of a classpath.
 *
 * {{{
 *   import cb._
 *   val codebase = Codebase.fromClasspath(getClass.getClassLoader)
 *   codebase.vals.ofType[Person](subtypes = true).in("com.acme.hr").toList
 * }}}
 */
final class Codebase(val backend: Backend) {

  /** All `val`s in the codebase. */
  def vals: MemberQuery =
    new MemberQuery(backend, backend.valMembers.map(m => m: Member))

  /** All `def`s in the codebase. */
  def defs: MemberQuery =
    new MemberQuery(backend, backend.defMembers.map(m => m: Member))

  /** All implicit `val`s and `def`s (Scala 3 `given`s land here too). */
  def givens: MemberQuery =
    new MemberQuery(
      backend,
      (backend.valMembers ++ backend.defMembers).collect {
        case m: Member if m.isImplicit => m
      }
    )

  /** All classes and traits. */
  def classes: TypeQuery = new TypeQuery(backend, backend.classes)

  /** All objects (modules). */
  def objects: TypeQuery = new TypeQuery(backend, backend.objects)

  /**
   * Every declared instance of typeclass `T`: implicit `val`/`def` (including
   * parameterized derivations) plus `object`/`class` extending `T`, with the
   * `T` definition itself excluded. Takes a wildcard (`instancesOf[Codec[_]]`)
   * or a fixed argument (`instancesOf[Codec[Bar]]`).
   *
   * Only declared symbols are found: instances materialized by implicit
   * resolution (derivation, summoning) or reachable via implicit conversion
   * have no symbol to enumerate.
   */
  def instancesOf[T](implicit cap: TypeCapture[T]): List[Entity] = {
    val members = givens.of[T].toList
    val types   = (backend.classes ++ backend.objects).filter { t =>
      backend.conforms(t.self, cap.typeRef) && !backend.sameConstructor(t.self, cap.typeRef)
    }
    members ++ types.toList
  }

  /**
   * Collect the live values of type `T` (subtypes included) declared as `val`s
   * or no-arg `def`s in singleton `object`s across the codebase: the "registry
   * scattered as vals" pattern. Class-owned members are skipped, and reading
   * each value runs its initializer, so this is effectful.
   *
   * {{{ val routes: List[Route] = cb.collect[Route] }}}
   *
   * Shorthand for `(vals ++ defs).ofType[T](subtypes = true).instances[T]`; drop
   * to that form when you need to scope with `.in(...)` first.
   */
  def collect[T](implicit cap: TypeCapture[T]): List[T] =
    (backend.valMembers ++ backend.defMembers).iterator
      .filter(m => backend.conforms(m.memberType, cap.typeRef))
      .flatMap(backend.instanceOf)
      .map(_.asInstanceOf[T])
      .toList
}

object Codebase {

  /** A ClassGraph scan: the JVM class names, plus a map from Scala fqn to the
   *  source path (package dir + bytecode `SourceFile`) for classes that have one. */
  private final case class Scan(names: List[String], sources: Map[String, String])

  /** Enumerate classes with ClassGraph (single-threaded by design). */
  private def scanClasspath(
    classLoaders: Seq[ClassLoader],
    classpath: Seq[Path],
    acceptPackages: Seq[String]
  ): Scan = {
    val configure: List[ClassGraph => ClassGraph] = List(
      cg => if (classLoaders.nonEmpty) cg.overrideClassLoaders(classLoaders: _*) else cg,
      cg => if (classpath.nonEmpty) cg.overrideClasspath(classpath.map(_.toString): _*) else cg,
      cg => if (acceptPackages.nonEmpty) cg.acceptPackages(acceptPackages: _*) else cg
    )
    val cg =
      configure.foldLeft(new ClassGraph().enableClassInfo.enableAnnotationInfo)((g, f) => f(g))
    val scan = cg.scan()
    try {
      val infos   = scan.getAllClasses.asScala.toList
      val sources = infos.iterator.flatMap { ci =>
        Option(ci.getSourceFile).map { file =>
          val pkg = ci.getPackageName
          val rel = if (pkg.isEmpty) file else s"${pkg.replace('.', '/')}/$file"
          ReflectBackend.scalaName(ci.getName) -> rel
        }
      }.toMap
      Scan(infos.map(_.getName), sources)
    } finally scan.close()
  }

  /**
   * Resolve a type's [[TypeEntity.sourcePath]] against a set of source roots to
   * the actual file on disk, if one exists.
   *
   * {{{ Codebase.locate(entity, Seq(Paths.get("src/main/scala"))) }}}
   */
  def locate(entity: TypeEntity, roots: Seq[Path]): Option[Path] =
    entity.sourcePath.flatMap(rel => roots.iterator.map(_.resolve(rel)).find(Files.exists(_)))

  /**
   * Scan the current run classpath, scoped to zero or more package prefixes.
   *
   * {{{
   *   Codebase.scan("com.acme")
   *   Codebase.scan("com.acme", "shared")
   *   Codebase.scan()
   * }}}
   */
  def scan(packages: String*): Codebase =
    fromClasspath(getClass.getClassLoader, packages)

  /**
   * Scan `classLoader` (and its parent chain). When run from a build task or
   * `runMain` this is the module's whole run classpath (your code plus every
   * dependency), so scope it with `acceptPackages` or `.in("pkg")`.
   */
  def fromClasspath(classLoader: ClassLoader): Codebase =
    fromClasspath(classLoader, Nil)

  /** As [[fromClasspath]], but restrict the scan to the given packages. */
  def fromClasspath(classLoader: ClassLoader, acceptPackages: Seq[String]): Codebase = {
    val scanned = scanClasspath(Seq(classLoader), Nil, acceptPackages)
    new Codebase(new ReflectBackend(ru.runtimeMirror(classLoader), scanned.names, scanned.sources))
  }

  /** Scan the classloader that loaded `cb` itself. */
  def fromClasspath(): Codebase = fromClasspath(getClass.getClassLoader)

  /**
   * Scan exactly these classpath entries (e.g. Mill's `runClasspath` or sbt's
   * `fullClasspath`) instead of the running JVM's classpath. `parent` must be
   * able to load the Scala standard library and anything the pickles reference;
   * classes it cannot resolve are skipped.
   */
  def fromPaths(
    paths: Seq[Path],
    parent: ClassLoader = classOf[Codebase].getClassLoader,
    acceptPackages: Seq[String] = Nil
  ): Codebase = {
    val cl      = new URLClassLoader(paths.map(_.toUri.toURL).toArray, parent)
    val scanned = scanClasspath(Nil, paths, acceptPackages)
    new Codebase(new ReflectBackend(ru.runtimeMirror(cl), scanned.names, scanned.sources))
  }

  /** Build a [[Codebase]] over an explicit set of JVM class names. */
  def fromClasses(classLoader: ClassLoader, names: List[String]): Codebase =
    new Codebase(new ReflectBackend(ru.runtimeMirror(classLoader), names))
}
