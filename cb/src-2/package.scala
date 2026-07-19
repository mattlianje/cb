import scala.reflect.runtime.{universe => ru}

package object cb {

  /** Capture `T` as a [[TypeRef]]. `WeakTypeTag` (not `TypeTag`) so wildcards
   *  like `IO[_]` are allowed. In scope via `import cb._`. */
  implicit def captureType[T](implicit wtt: ru.WeakTypeTag[T]): TypeCapture[T] =
    new TypeCapture[T] {
      val typeRef: TypeRef = ReflectBackend.toTypeRef(wtt.tpe)
    }
}
