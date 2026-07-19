package cb.tc

import cb._

trait Codec[A] { def enc(a: A): String }

case class Foo(x: Int)
case class Bar(x: Int)
case class Baz(x: Int)
case class Qux(x: Int)

object Instances {
  implicit val fooCodec: Codec[Foo] = new Codec[Foo] { def enc(a: Foo) = a.x.toString }
  implicit def listCodec[A](implicit a: Codec[A]): Codec[List[A]] =
    new Codec[List[A]] { def enc(a: List[A]) = a.mkString }

  implicit object BarCodec extends Codec[Bar] { def enc(a: Bar) = "bar" }
  object BazCodec          extends Codec[Baz] { def enc(a: Baz) = "baz" }
  class QuxCodec           extends Codec[Qux] { def enc(a: Qux) = "qux" }
}

class TypeclassInstanceSpec extends munit.FunSuite {
  lazy val c = Codebase.fromClasspath(getClass.getClassLoader, Seq("cb.tc"))

  test("extending[Codec[_]] no longer leaks the typeclass trait itself") {
    val classes = c.classes.extending[Codec[_]].toList.map(_.fqn)
    assert(!classes.contains("cb.tc.Codec"), classes)
    assert(classes.contains("cb.tc.Instances.QuxCodec"), classes)
  }

  test("instancesOf[Codec[_]] unions vals, defs, implicit objects, objects, classes") {
    val found    = c.instancesOf[Codec[_]].map(_.fqn).toSet
    val expected = Set(
      "cb.tc.Instances.fooCodec",  // implicit val
      "cb.tc.Instances.listCodec", // implicit def derivation
      "cb.tc.Instances.BarCodec",  // implicit object
      "cb.tc.Instances.BazCodec",  // plain object
      "cb.tc.Instances.QuxCodec"   // plain class
    )
    assert(expected.subsetOf(found), s"missing: ${expected -- found}; got: $found")
    assert(!found.contains("cb.tc.Codec"), "typeclass trait must be excluded")
  }

  test("instancesOf[Codec[Bar]] narrows to a single type argument") {
    val found = c.instancesOf[Codec[Bar]].map(_.fqn).toSet
    assert(found.contains("cb.tc.Instances.BarCodec"), found)
    assert(!found.contains("cb.tc.Instances.BazCodec"), found)
  }

  test("objects.implicits finds implicit objects only") {
    val impl = c.objects.implicits.toList.map(_.name).toSet
    assert(impl.contains("BarCodec"), impl)
    assert(!impl.contains("BazCodec"), impl)
  }
}
