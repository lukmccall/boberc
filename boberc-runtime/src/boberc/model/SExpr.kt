package boberc.model

/**
 * Minimal S-expression format used to serialize template models.
 *  - atoms: bare symbols (`call`, `true`, `12`) or quoted strings (`"kotlin/String"`)
 *  - lists: `(head item item ...)`
 */
internal sealed class SNode {
  data class Atom(val text: String, val quoted: Boolean) : SNode()
  data class SList(val items: List<SNode>) : SNode()
}

internal object SExpr {
  private const val MAX_LINE = 100

  fun compact(node: SNode): String = StringBuilder().also { writeCompact(node, it) }.toString()

  fun pretty(node: SNode): String = StringBuilder().also { writePretty(node, it, 0) }.toString()

  private fun writeCompact(node: SNode, out: StringBuilder) {
    when (node) {
      is SNode.Atom -> writeAtom(node, out)
      is SNode.SList -> {
        out.append('(')
        node.items.forEachIndexed { i, item ->
          if (i > 0) out.append(' ')
          writeCompact(item, out)
        }
        out.append(')')
      }
    }
  }

  private fun writePretty(node: SNode, out: StringBuilder, indent: Int) {
    val flat = compact(node)
    if (node is SNode.Atom || flat.length + indent <= MAX_LINE) {
      out.append(flat)
      return
    }
    node as SNode.SList
    out.append('(')
    // Keep the head and leading atoms on the first line, put nested lists on their own lines.
    var i = 0
    while (i < node.items.size && node.items[i] is SNode.Atom) {
      if (i > 0) out.append(' ')
      writeAtom(node.items[i] as SNode.Atom, out)
      i++
    }
    while (i < node.items.size) {
      out.append('\n')
      repeat(indent + 2) { out.append(' ') }
      writePretty(node.items[i], out, indent + 2)
      i++
    }
    out.append(')')
  }

  private fun writeAtom(atom: SNode.Atom, out: StringBuilder) {
    if (!atom.quoted) {
      out.append(atom.text)
      return
    }
    out.append('"')
    for (c in atom.text) {
      when {
        c == '"' -> out.append("\\\"")
        c == '\\' -> out.append("\\\\")
        c == '\n' -> out.append("\\n")
        c == '\r' -> out.append("\\r")
        c == '\t' -> out.append("\\t")
        c < ' ' || c.isSurrogate() || c.code > 0x7e -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> out.append(c)
      }
    }
    out.append('"')
  }

  fun parse(text: String): SNode {
    val parser = Parser(text)
    val node = parser.node()
    parser.skipWhitespace()
    require(parser.atEnd()) { "Trailing characters in template data at ${parser.pos}" }
    return node
  }

  private class Parser(val text: String) {
    var pos = 0

    fun atEnd() = pos >= text.length

    fun skipWhitespace() {
      while (!atEnd() && text[pos].isWhitespace()) pos++
    }

    fun node(): SNode {
      skipWhitespace()
      require(!atEnd()) { "Unexpected end of template data" }
      return when (text[pos]) {
        '(' -> {
          pos++
          val items = mutableListOf<SNode>()
          while (true) {
            skipWhitespace()
            require(!atEnd()) { "Unclosed list in template data" }
            if (text[pos] == ')') {
              pos++
              break
            }
            items += node()
          }
          SNode.SList(items)
        }
        '"' -> quoted()
        else -> {
          val start = pos
          while (!atEnd() && !text[pos].isWhitespace() && text[pos] != '(' && text[pos] != ')') pos++
          SNode.Atom(text.substring(start, pos), quoted = false)
        }
      }
    }

    private fun quoted(): SNode.Atom {
      pos++ // opening quote
      val sb = StringBuilder()
      while (true) {
        require(!atEnd()) { "Unclosed string in template data" }
        val c = text[pos++]
        when (c) {
          '"' -> break
          '\\' -> {
            when (val e = text[pos++]) {
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                pos += 4
              }
              else -> sb.append(e)
            }
          }
          else -> sb.append(c)
        }
      }
      return SNode.Atom(sb.toString(), quoted = true)
    }
  }
}
