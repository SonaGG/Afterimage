package gg.sona.afterimage.index.query

class Parser(source: String) {
    private val tokens = Lexer(source).tokens()
    private var index = 0

    fun parse(): Query {
        val first = tokens[0]
        val kind = if (first.type == TokenType.IDENT) EventKindSpec.of(first.text) else null
        val second = tokens.getOrNull(1)
        val eventMode = kind != null && second != null && second.type != TokenType.LPAREN && second.type != TokenType.OP
        val query = if (eventMode) events(kind!!) else Query.Condition(expr())
        if (peek().type != TokenType.EOF) throw QueryException("Unexpected '${peek().text}'", peek().position)
        return query
    }

    private fun events(kind: EventKindSpec): Query {
        advance()
        val filters = ArrayList<EventFilter>()
        while (true) {
            val token = peek()
            when (token.type) {
                TokenType.IDENT -> {
                    if (token.text.equals("where", true)) {
                        advance()
                        return Query.Events(setOf(kind), filters, expr())
                    }
                    advance()
                    if (peek().type == TokenType.COLON) {
                        advance()
                        filters += filterValue(token.text.lowercase(), token.position)
                    } else filters += EventFilter("player", token.text, null, token.position)
                }

                TokenType.STRING -> {
                    advance()
                    filters += EventFilter("text", token.text, null, token.position)
                }

                TokenType.TIME, TokenType.NUMBER -> {
                    advance()
                    filters += EventFilter("after", token.text, doubleArrayOf(seconds(token)), token.position)
                }

                TokenType.AND -> {
                    advance()
                    return Query.Events(setOf(kind), filters, expr())
                }

                else -> return Query.Events(setOf(kind), filters, null)
            }
        }
    }

    private fun filterValue(key: String, position: Int): EventFilter {
        val token = peek()
        return when (token.type) {
            TokenType.IDENT, TokenType.STRING -> {
                advance()
                EventFilter(key, token.text, null, position)
            }

            TokenType.TIME -> {
                advance()
                EventFilter(key, token.text, doubleArrayOf(seconds(token)), position)
            }

            TokenType.NUMBER -> {
                val numbers = ArrayList<Double>()
                numbers += number(advance())
                while (peek().type == TokenType.COMMA && tokens.getOrNull(index + 1)?.type == TokenType.NUMBER) {
                    advance()
                    numbers += number(advance())
                }
                EventFilter(key, numbers.joinToString(","), numbers.toDoubleArray(), position)
            }

            else -> throw QueryException("Expected a value after '$key:'", token.position)
        }
    }

    private fun expr(): Expr = orExpr()

    private fun orExpr(): Expr {
        var left = andExpr()
        while (peek().type == TokenType.OR) {
            val token = advance()
            left = Expr.Or(left, andExpr(), token.position)
        }
        return left
    }

    private fun andExpr(): Expr {
        var left = notExpr()
        while (peek().type == TokenType.AND) {
            val token = advance()
            left = Expr.And(left, notExpr(), token.position)
        }
        return left
    }

    private fun notExpr(): Expr {
        if (peek().type == TokenType.NOT) {
            val token = advance()
            return Expr.Not(notExpr(), token.position)
        }
        return compare()
    }

    private fun compare(): Expr {
        val left = primary()
        if (peek().type == TokenType.OP) {
            val op = advance()
            return Expr.Compare(op.text, left, primary(), op.position)
        }
        return left
    }

    private fun primary(): Expr {
        val token = advance()
        return when (token.type) {
            TokenType.NUMBER -> Expr.Num(number(token), token.position)
            TokenType.TIME -> Expr.Num(seconds(token), token.position)
            TokenType.STRING -> Expr.Str(token.text, token.position)
            TokenType.LPAREN -> {
                val inner = expr()
                expect(TokenType.RPAREN, ")")
                inner
            }

            TokenType.IDENT -> {
                if (peek().type == TokenType.LPAREN) {
                    advance()
                    val args = ArrayList<Expr>()
                    if (peek().type != TokenType.RPAREN) {
                        args += expr()
                        while (peek().type == TokenType.COMMA) {
                            advance()
                            args += expr()
                        }
                    }
                    expect(TokenType.RPAREN, ")")
                    Expr.Call(token.text.lowercase(), args, token.position)
                } else Expr.Ref(token.text, token.position)
            }

            TokenType.EOF -> throw QueryException("Unexpected end of query", token.position)
            else -> throw QueryException("Unexpected '${token.text}'", token.position)
        }
    }

    private fun expect(type: TokenType, text: String) {
        val token = advance()
        if (token.type != type) throw QueryException("Expected '$text'", token.position)
    }

    private fun number(token: Token): Double =
        token.text.toDoubleOrNull() ?: throw QueryException("Bad number '${token.text}'", token.position)

    private fun seconds(token: Token): Double {
        if (token.type == TokenType.NUMBER) return number(token)
        val parts = token.text.split(':')
        val minutes = parts[0].toDoubleOrNull() ?: throw QueryException("Bad time '${token.text}'", token.position)
        val seconds = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return minutes * 60.0 + seconds
    }

    private fun peek(): Token = tokens[index]

    private fun advance(): Token {
        val token = tokens[index]
        if (index < tokens.size - 1) index++
        return token
    }
}
