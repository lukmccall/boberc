// Captures a trivial template and checks the rendered model.
import boberc.*
import boberc.model.FunctionTemplate

@Template
fun greeting(name: String): String = "Hello, $name!"

private val EXPECTED = """
(template 8 "greeting" false PUBLIC false false
  ()
  _
  ((param "name" (class "kotlin/String") false _ false false _))
  (class "kotlin/String")
  (block
    (
      (return 0
        (concat
          (
            (const STRING "Hello, " (class "kotlin/String"))
            (get (param 0))
            (const STRING "!" (class "kotlin/String")))
          (class "kotlin/String"))))
    (class "kotlin/Unit")
    _))
""".trim()

fun box(): String {
    // A template is still an ordinary function...
    if (greeting("Bob") != "Hello, Bob!") return "Fail: template is not callable"
    // ...and template() returns its captured model.
    val t: FunctionTemplate = template(::greeting)
    if (t.name != "greeting") return "Fail: name ${t.name}"
    val rendered = t.render()
    return if (rendered == EXPECTED) "OK" else "Fail, rendered:\n$rendered"
}
