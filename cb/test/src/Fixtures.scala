package cb.fixtures

import scala.annotation.StaticAnnotation

/** Annotation used to exercise `annotated[...]`. */
class registered extends StaticAnnotation

/** A tiny type-class used to exercise `givens.of[...]`. */
trait Show[A] {
  def show(a: A): String
}

/** A parametric wrapper used to exercise `returning[Box[_]]`. */
final case class Box[A](value: A)

trait Person {
  def name: String
}

class Employee(val name: String) extends Person

trait Rule
object RuleA extends Rule
object RuleB extends Rule

class DbConnection
class LeakyService {
  val conn: DbConnection = new DbConnection
}

object HR {
  val boss: Person                      = new Employee("boss")
  val topEmployee: Employee             = new Employee("ada")
  val headcount: Int                    = 3
  private val secret: Int               = 42
  implicit val personShow: Show[Person] = new Show[Person] {
    def show(a: Person) = a.name
  }

  def hire(name: String): Person = new Employee(name)
  def boxed: Box[Int]            = Box(1)
  def io: Box[String]            = Box("x")
}

@registered
object RegisteredThing {
  val label: String = "hello"
}
