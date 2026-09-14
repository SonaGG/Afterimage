package gg.sona.afterimage.index.query

class Lexer(private val source: String) {
    private var index = 0
    private val tokens = ArrayList<Token>()

    fun tokens(): List<Token> {
        while (true) {
            skipSpace()
            if (index >= source.length) break
            val start = index
            val c = source[index]
            when {
                c == '(' -> single(TokenType.LPAREN)
                c == ')' -> single(TokenType.RPAREN)
                c == ',' -> single(TokenType.COMMA)
                c == ':' -> single(TokenType.COLON)
                c == '"' || c == '\'' -> string(c)
                c == '<' || c == '>' || c == '=' || c == '!' -> operator()
                c.isDigit() || (c == '-' && index + 1 < source.length && source[index + 1].isDigit()) -> number()
                c == '#' || c.isLetter() || c == '_' -> ident()
                else -> throw QueryException("Unexpected character '$c'", start)
            }
        }
        tokens += Token(TokenType.EOF, "", source.length)
        return tokens
    }

    private fun skipSpace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun single(type: TokenType) {
        tokens += Token(type, source[index].toString(), index)
        index++
    }

    private fun string(quote: Char) {
        val start = index
        index++
        val text = StringBuilder()
        while (index < source.length && source[index] != quote) {
            if (source[index] == '\\' && index + 1 < source.length) index++
            text.append(source[index])
            index++
        }
        if (index >= source.length) throw QueryException("Unterminated string", start)
        index++
        tokens += Token(TokenType.STRING, text.toString(), start)
    }

    private fun operator() {
        val start = index
        val two = if (index + 1 < source.length) source.substring(index, index + 2) else ""
        val text = when {
            two == "<=" || two == ">=" || two == "==" || two == "!=" -> two
            source[index] == '<' || source[index] == '>' -> source[index].toString()
            source[index] == '=' -> "="
            else -> throw QueryException("Unexpected character '${source[index]}'", start)
        }
        index += text.length
        tokens += Token(TokenType.OP, if (text == "=") "==" else text, start)
    }

    private fun number() {
        val start = index
        if (source[index] == '-') index++
        while (index < source.length && source[index].isDigit()) index++
        if (index < source.length && source[index] == ':' && index + 1 < source.length && source[index + 1].isDigit()) {
            index++
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
            tokens += Token(TokenType.TIME, source.substring(start, index), start)
            return
        }
        if (index < source.length && source[index] == '.' && index + 1 < source.length && source[index + 1].isDigit()) {
            index++
            while (index < source.length && source[index].isDigit()) index++
        }
        tokens += Token(TokenType.NUMBER, source.substring(start, index), start)
    }

    private fun ident() {
        val start = index
        index++
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '.')) index++
        val text = source.substring(start, index)
        val type = when (text.lowercase()) {
            "and" -> TokenType.AND
            "or" -> TokenType.OR
            "not" -> TokenType.NOT
            else -> TokenType.IDENT
        }
        tokens += Token(type, text, start)
    }
}
