package interlinked.util

import utest.*


object MustacheTemplateTest extends TestSuite:
  val tests = Tests:
    test("simple value substitution"):
      val template = "Hello {{name}}!"
      val attributes = Map("name" -> "World")
      val result = interlinked.util.MustacheCompiler.compile(template).render(attributes)
      assert(result == "Hello World!")

    test("sequence rendering"):
      val template = "{{#items}}{{.}} {{/items}}"
      val attributes = Map("items" -> Seq("apple", "banana", "cherry"))
      val result = interlinked.util.MustacheCompiler.compile(template).render(attributes)
      assert(result == "apple banana cherry ")

    test("nested maps"):
      val template = "User: {{user.name}}, Age: {{user.age}}"
      val attributes = Map(
        "user" -> Map(
          "name" -> "John",
          "age" -> 30
        )
      )
      val result = interlinked.util.MustacheCompiler.compile(template).render(attributes)
      assert(result == "User: John, Age: 30")

    test("nested sequences of maps"):
      val template = "{{#users}}{{name}} ({{age}}) {{/users}}"
      val attributes = Map(
        "users" -> Seq(
          Map("name" -> "John", "age" -> 30),
          Map("name" -> "Jane", "age" -> 25),
          Map("name" -> "Bob", "age" -> 35)
        )
      )
      val result = interlinked.util.MustacheCompiler.compile(template).render(attributes)
      assert(result == "John (30) Jane (25) Bob (35) ")

    test("complex nested structure"):
      val template =
        "{{#departments}}{{deptName}}: {{#employees}}{{name}} ({{role}}) {{/employees}}| {{/departments}}"
      val attributes = Map(
        "departments" -> Seq(
          Map(
            "deptName" -> "Engineering",
            "employees" -> Seq(
              Map("name" -> "Alice", "role" -> "Developer"),
              Map("name" -> "Bob", "role" -> "QA")
            )
          ),
          Map(
            "deptName" -> "Marketing",
            "employees" -> Seq(
              Map("name" -> "Charlie", "role" -> "Manager")
            )
          )
        )
      )
      val result = interlinked.util.MustacheCompiler.compile(template).render(attributes)
      assert(result == "Engineering: Alice (Developer) Bob (QA) | Marketing: Charlie (Manager) | ")
