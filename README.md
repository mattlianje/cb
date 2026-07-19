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

/* Scan your sources, not your deps */
val cb = Codebase.scan("com.acme")

/* Easily scoop up vals, defs, etc and check for rules */
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

## The query language

```scala
val cb = Codebase.scan("com.acme")

cb.vals.ofType[Person]                       // vals typed exactly Person
cb.vals.ofType[Person](subtypes = true)      // ...and its subtypes
cb.defs.returning[IO[_]].filter(_.isPublic)  // defs returning IO of anything
cb.classes.extending[Rule]                   // classes/traits implementing Rule
cb.givens.of[JsonCodec[_]]                   // implicit vals/defs
cb.objects.annotated[registered]             // objects carrying @registered

/* Easy scoping, then enforcement */
cb.vals.ofType[DbConnection]
  .in("com.acme.infra")
  .assertAll(!_.isPublic, "connections must be private")

cb.objects.annotated[registered].toList // plain Entity list
```


### Collecting instances

Gather the live values of a type ... like when you have a registry of 
`val`s scattered in objects.

```scala
val routes: List[Route] = cb.collect[Route]
```

Or you can scope first:

```scala
cb.vals.ofType[Route](subtypes = true).in("com.acme.routes").instances[Route]
```

## Which classpath?

```scala
Codebase.scan("com.acme") // Current run classpath, scoped
Codebase.scan() // Everything on the classpath
Codebase.fromPaths(paths, acceptPackages = Seq("com.acme")) // Explicit entries
```

