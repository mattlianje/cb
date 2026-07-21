<div align="center">
    <img src="pix/pet_with_github.png" width="400"/>
</div>

# cb
**Easy codebase introspection**

A tiny, intuitive libray for querying and enforcing rules on Scala classpaths.

## Features
- Real Scala types
- Smooth rules and queries
- Swappable backends (pickle or TASTy)

## Installation
On MavenCentral, cross-built for Scala 2.12, 2.13 (JVM only)
```scala
"xyz.matthieucourt" %% "cb" % "0.1.1"
```

All you need
```scala
import cb._
```

## Quick Example

```scala
import cb._

/* Scan your sources */
val cb = Codebase.scan("com.acme")

/* Query them */
cb.vals.ofType[Person]

/* Enfore rules */
cb.defs.filter(_.isPublic).returning[IO[_]]
  .assertAll(_.in("com.acme.service"),
             "public defs returning IO must stay in the service layer")
```

Break the rule and the build fails with the offending Scala types spelled out:

```
cb.CbAssertionError: public defs returning IO must stay in the service layer
  - com.acme.web.UserController.fetch: cats.effect.IO[User]
  - com.acme.repo.OrderRepo.save:      cats.effect.IO[Unit]
```

## Of note
There is already a rich tradition of programs which scan JVM classpaths ([ClassGraph](https://github.com/classgraph/classgraph), [ServiceLoader](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html), [Spring component-scan](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html), etc), then
let your perform [ArchUnit](https://github.com/TNG/archUnit)-like assertions to enforce invariants and "unit test your code structure".

Whilst **cb** lets you do the afore... it really is just a modest ClassGraph wrapper which puts novel emphasis on:
- Scala ergonomics
- Typed retrieval of instances

## API

```scala
/* Scope a classpath */
val cb = Codebase.scan("com.acme")

/* Query it */
cb.vals.ofType[Person]
cb.vals.ofType[Person](subtypes = true)
cb.defs.returning[IO[_]].filter(_.isPublic)
cb.classes.extending[Rule]
cb.givens.of[JsonCodec[_]] // implicit JsonCodec vals/defs
cb.objects.annotated[registered] // objects carrying @registered

/* Collect live values of a type */
val routes: List[Route] = cb.collect[Route]

/* Enforce rules */
cb.vals.ofType[DbConnection]
  .in("com.acme.infra")
  .assertAll(!_.isPublic, "connections must be private")
```

## Collecting instances

```scala
/* Scope to a package, subtypes included */
val filteredRoutes: List[Route] =
     cb.vals.ofType[Route](subtypes = true).in("com.acme.routes").instances[Route]

/* Pair each value with the source file it came from */
val located: List[(Route, Option[String])] =
     cb.vals.ofType[Route](subtypes = true).instancesLocated[Route]

/* Where is a type defined? */
cb.classes.ofType[Route].toList.head.sourcePath // Some("com/acme/routes/Routes.scala")
cb.classes.ofType[Route].toList.head.sourceFile // Some("Routes.scala")
```

## Which classpath?

```scala
Codebase.scan("com.acme") // Current run classpath (scoped)
Codebase.scan() // Everything on the classpath
Codebase.fromPaths(paths, acceptPackages = Seq("com.acme")) // Explicit entries
```

