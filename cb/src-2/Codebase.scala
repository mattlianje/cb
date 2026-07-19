package cb

import scala.reflect.runtime.{universe => ru}
import io.github.classgraph.ClassGraph
import scala.collection.JavaConverters._
import java.net.URLClassLoader
import java.nio.file.Path

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
}

object Codebase {

  /** Enumerate class names with ClassGraph (single-threaded by design). */
  private def scanNames(
    classLoaders: Seq[ClassLoader],
    classpath: Seq[Path],
    acceptPackages: Seq[String]
  ): List[String] = {
    val configure: List[ClassGraph => ClassGraph] = List(
      cg => if (classLoaders.nonEmpty) cg.overrideClassLoaders(classLoaders: _*) else cg,
      cg => if (classpath.nonEmpty) cg.overrideClasspath(classpath.map(_.toString): _*) else cg,
      cg => if (acceptPackages.nonEmpty) cg.acceptPackages(acceptPackages: _*) else cg
    )
    val cg =
      configure.foldLeft(new ClassGraph().enableClassInfo.enableAnnotationInfo)((g, f) => f(g))
    val scan = cg.scan()
    try scan.getAllClasses.getNames.asScala.toList
    finally scan.close()
  }

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
  def fromClasspath(classLoader: ClassLoader, acceptPackages: Seq[String]): Codebase =
    new Codebase(
      new ReflectBackend(
        ru.runtimeMirror(classLoader),
        scanNames(Seq(classLoader), Nil, acceptPackages)
      )
    )

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
    val cl = new URLClassLoader(paths.map(_.toUri.toURL).toArray, parent)
    new Codebase(new ReflectBackend(ru.runtimeMirror(cl), scanNames(Nil, paths, acceptPackages)))
  }

  /** Build a [[Codebase]] over an explicit set of JVM class names. */
  def fromClasses(classLoader: ClassLoader, names: List[String]): Codebase =
    new Codebase(new ReflectBackend(ru.runtimeMirror(classLoader), names))
}
