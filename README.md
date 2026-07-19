<div align="center">
    <img src="pix/pet_with_github.png" width="400"/>
</div>

# cb
**Easy codebase introspection**

A tiny, intuitive libray for querying Scala classpaths.

## Features
- Real Scala types
- Smooth rules and queries
- Swappable backends (pickle or TASTy)


All you need:
```scala
import cb._
```

## Quick Example

```scala
import cb._

/* Scan your sources */
val cb = Codebase.scan("com.acme")

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

## API

```scala
/* Scope a classpath */
val cb = Codebase.scan("com.acme")

/* Query it */
cb.vals.ofType[Person]
cb.vals.ofType[Person](subtypes = true)
cb.defs.returning[IO[_]].filter(_.isPublic)
cb.classes.extending[Rule]
cb.givens.of[JsonCodec[_]]
cb.objects.annotated[registered] // objects carrying @registered

/* Enforce rules */
cb.vals.ofType[DbConnection]
  .in("com.acme.infra")
  .assertAll(!_.isPublic, "connections must be private")

cb.objects.annotated[registered].toList // plain Entity list
```


### Collecting instances

```scala
val routes: List[Route] =
  cb.vals.ofType[Route](subtypes = true).instances[Route]
```

## Which classpath?

```scala
Codebase.scan("com.acme") // Current run classpath (scoped)
Codebase.scan() // Everything on the classpath
Codebase.fromPaths(paths, acceptPackages = Seq("com.acme")) // Explicit entries
```

