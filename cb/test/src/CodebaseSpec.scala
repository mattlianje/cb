package cb

import cb.fixtures._

class CodebaseSpec extends munit.FunSuite {

  lazy val codebase: Codebase = Codebase.fromClasspath(getClass.getClassLoader)

  test("vals.ofType exact match") {
    val people = codebase.vals.ofType[Person].in("cb.fixtures")
    val names  = people.toList.map(_.fqn).toSet
    assert(names.contains("cb.fixtures.HR.boss"), names)
  }

  test("vals.ofType with subtypes still finds exact and sub types") {
    val exact = codebase.vals.ofType[Person].in("cb.fixtures").count
    val subs  = codebase.vals.ofType[Person](subtypes = true).in("cb.fixtures").count
    assert(subs >= exact, s"subtypes=$subs should be >= exact=$exact")
    assert(exact >= 1)
  }

  test("in() filters by package") {
    val all       = codebase.vals.ofType[DbConnection]
    val infraOnly = all.in("cb.fixtures")
    assert(all.count >= 1)
    assert(infraOnly.count >= 1)
    assert(codebase.vals.ofType[DbConnection].in("com.does.not.exist").isEmpty)
  }

  test("classes.extending finds subtypes, excludes the trait itself") {
    val rules = codebase.classes.extending[Rule].in("cb.fixtures")
    // RuleA/RuleB are objects, so check via objects too
    val ruleObjects = codebase.objects.extending[Rule].in("cb.fixtures").toList.map(_.name).toSet
    assert(ruleObjects.contains("RuleA"), ruleObjects)
    assert(ruleObjects.contains("RuleB"), ruleObjects)
    assert(!ruleObjects.contains("Rule"))
    val _ = rules
  }

  test("defs.returning matches by type constructor") {
    val boxed = codebase.defs.returning[Box[Int]].in("cb.fixtures").toList.map(_.name).toSet
    // both `boxed: Box[Int]` and `io: Box[String]` share the Box constructor
    assert(boxed.contains("boxed"), boxed)
    assert(boxed.contains("io"), boxed)
  }

  test("givens.of finds implicit instances by constructor") {
    val shows = codebase.givens.of[Show[Person]].in("cb.fixtures").toList.map(_.name).toSet
    assert(shows.contains("personShow"), shows)
  }

  test("annotated finds annotated objects") {
    val annotated =
      codebase.objects.annotated[registered].in("cb.fixtures").toList.map(_.name).toSet
    assert(annotated.contains("RegisteredThing"), annotated)
  }

  test("isPublic reflects visibility") {
    val hrVals = codebase.vals.in("cb.fixtures").filter(_.owner.fqn == "cb.fixtures.HR")
    val boss   = hrVals.filter(_.name == "boss").toList.head
    assert(boss.isPublic, "boss should be public")
    val secret = hrVals.filter(_.name == "secret").toList.headOption
    secret.foreach(s => assert(!s.isPublic, "secret should be private"))
  }

  test("fromClasspath with acceptPackages narrows the scan") {
    val scoped = Codebase.fromClasspath(getClass.getClassLoader, Seq("cb.fixtures"))
    assert(scoped.classes.count >= 1)
    // nothing outside cb.fixtures should have been enumerated
    assert(scoped.classes.toList.forall(_.fqn.startsWith("cb.fixtures")), scoped.classes.toList)
    assert(scoped.vals.ofType[Person].nonEmpty)
  }

  test("assertAll passes when rule holds and throws with message when violated") {
    // holds: all DbConnection vals live under cb.fixtures
    codebase.vals
      .ofType[DbConnection]
      .in("cb.fixtures")
      .assertAll(_.in("cb.fixtures"), "connections leak!")

    // violated: nothing lives in this fake package
    val err = intercept[CbAssertionError] {
      codebase.vals
        .ofType[DbConnection]
        .in("cb.fixtures")
        .assertAll(_.in("com.acme.infra"), "connections leak outside infra!")
    }
    assert(err.getMessage.contains("connections leak outside infra!"))
  }

  test("instances materializes object-owned vals") {
    val people = codebase.vals.ofType[Person].in("cb.fixtures").instances[Person]
    assert(people.map(_.name).contains("boss"), people.map(_.name))
  }

  test("instances follows subtypes: ofType[Person](subtypes = true) picks up an Employee val") {
    // HR.topEmployee is declared `val topEmployee: Employee`, a subtype of Person.
    val exact = codebase.vals.ofType[Person].in("cb.fixtures").instances[Person].map(_.name)
    assert(!exact.contains("ada"), s"exact match should skip the Employee-typed val: $exact")

    val withSubs =
      codebase.vals.ofType[Person](subtypes = true).in("cb.fixtures").instances[Person].map(_.name)
    assert(withSubs.contains("boss"), withSubs)
    assert(withSubs.contains("ada"), withSubs)
  }

  test("instances materializes no-arg object-owned defs") {
    // HR.boxed and HR.io are no-arg defs returning Box[_], both in a singleton.
    val boxes = codebase.defs.returning[Box[Int]].in("cb.fixtures").instances[Box[Any]]
    assert(boxes.contains(Box(1)), boxes)
    assert(boxes.contains(Box("x")), boxes)
  }

  test("instances skips defs that need arguments") {
    // HR.hire(name: String) can't be materialized without an argument.
    val hired = codebase.defs.in("cb.fixtures").filter(_.name == "hire").instances[Person]
    assert(hired.isEmpty, hired)
  }

  test("instances skips class-owned vals it cannot construct") {
    // LeakyService.conn is a class-owned val: no singleton to read it from.
    val conns = codebase.vals.ofType[DbConnection].in("cb.fixtures").instances[DbConnection]
    assert(conns.isEmpty, conns)
  }

  test("collect gathers live values of a type across singletons, subtypes included") {
    val names = codebase.collect[Person].map(_.name)
    assert(names.contains("boss"), names) // val boss: Person
    assert(names.contains("ada"), names)  // val topEmployee: Employee (subtype)
  }

  test("instancesLocated pairs each value with its declaring object's source path") {
    val located =
      codebase.vals.ofType[Person](subtypes = true).in("cb.fixtures").instancesLocated[Person]
    val byName = located.map { case (p, src) => p.name -> src }.toMap
    assert(byName.get("boss").flatten.contains("cb/fixtures/Fixtures.scala"), byName)
    assert(byName.get("ada").flatten.contains("cb/fixtures/Fixtures.scala"), byName)
  }

  test("types carry their reconstructed source path") {
    val hr = codebase.objects.in("cb.fixtures").toList.find(_.name == "HR").get
    assert(hr.sourcePath.contains("cb/fixtures/Fixtures.scala"), hr.sourcePath)
    assert(hr.sourceFile.contains("Fixtures.scala"), hr.sourceFile)

    val employee = codebase.classes.in("cb.fixtures").toList.find(_.name == "Employee").get
    assert(employee.sourceFile.contains("Fixtures.scala"), employee.sourceFile)
  }
}
