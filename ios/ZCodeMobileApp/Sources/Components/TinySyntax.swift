import SwiftUI

/// 轻量语法高亮 —— 代码块的 token 着色（web 用 shiki，移动端不引重型依赖）。
///
/// 只做「按语言取关键词 + 字符串 + 注释 + 数字」四类着色，覆盖阅读需求；
/// 不做 AST 解析，未知语言原样返回（不猜语义）。
enum TinySyntax {

    enum Kind {
        case keyword
        case string
        case comment
        case number
    }

    struct Token {
        let range: Range<String.Index>
        let kind: Kind
    }

    /// 语言别名归一：`ts|tsx|typescript` → `typescript` 等（未知返回 nil = 不高亮）。
    static func canonical(_ language: String?) -> String? {
        let raw = (language ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        switch raw {
        case "swift":
            return "swift"
        case "kt", "kotlin":
            return "kotlin"
        case "java":
            return "java"
        case "ts", "tsx", "typescript", "js", "jsx", "javascript", "mjs":
            return "typescript"
        case "py", "python":
            return "python"
        case "go", "golang":
            return "go"
        case "rs", "rust":
            return "rust"
        case "sh", "bash", "shell", "zsh", "console":
            return "shell"
        case "json":
            return "json"
        case "yml", "yaml":
            return "yaml"
        case "sql", "mysql", "postgres", "postgresql", "sqlite":
            return "sql"
        default:
            return nil
        }
    }

    static func keywords(for language: String) -> Set<String> {
        switch language {
        case "swift":
            return [
                "let", "var", "func", "class", "struct", "enum", "protocol", "extension", "import",
                "if", "else", "guard", "switch", "case", "default", "for", "while", "return",
                "self", "nil", "true", "false", "try", "catch", "throw", "throws", "async", "await",
                "public", "private", "internal", "static", "final", "init", "deinit", "some", "any",
                "where", "in", "as", "is", "actor", "mutating", "lazy", "weak", "unowned",
            ]
        case "kotlin":
            return [
                "val", "var", "fun", "class", "object", "interface", "enum", "data", "sealed",
                "if", "else", "when", "for", "while", "return", "break", "continue", "null", "true",
                "false", "try", "catch", "finally", "throw", "import", "package", "suspend",
                "override", "open", "private", "internal", "public", "protected", "companion",
                "object", "init", "is", "as", "in", "by", "lateinit", "const", "abstract",
                "inline", "reified", "vararg", "typealias", "this", "super",
            ]
        case "java":
            return [
                "class", "interface", "enum", "extends", "implements", "public", "private",
                "protected", "static", "final", "void", "int", "long", "double", "float", "boolean",
                "char", "byte", "short", "new", "return", "if", "else", "for", "while", "do",
                "switch", "case", "default", "break", "continue", "try", "catch", "finally",
                "throw", "throws", "null", "true", "false", "this", "super", "import", "package",
                "abstract", "synchronized", "volatile", "record", "var",
            ]
        case "typescript":
            return [
                "const", "let", "var", "function", "class", "interface", "type", "enum", "extends",
                "implements", "new", "return", "if", "else", "for", "while", "do", "switch", "case",
                "default", "break", "continue", "try", "catch", "finally", "throw", "async", "await",
                "import", "export", "from", "as", "null", "undefined", "true", "false", "this",
                "typeof", "instanceof", "in", "of", "void", "declare", "readonly", "public",
                "private", "protected", "static", "satisfies", "keyof", "never", "unknown",
            ]
        case "python":
            return [
                "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
                "try", "except", "finally", "raise", "import", "from", "as", "with", "lambda",
                "None", "True", "False", "and", "or", "not", "in", "is", "pass", "yield", "async",
                "await", "global", "nonlocal", "assert", "del",
            ]
        case "go":
            return [
                "func", "package", "import", "var", "const", "type", "struct", "interface", "map",
                "chan", "go", "defer", "select", "switch", "case", "default", "for", "range", "if",
                "else", "return", "break", "continue", "nil", "true", "false", "make", "new",
                "string", "int", "int64", "float64", "bool", "error",
            ]
        case "rust":
            return [
                "fn", "let", "mut", "struct", "enum", "trait", "impl", "pub", "use", "mod", "match",
                "if", "else", "for", "while", "loop", "return", "break", "continue", "Some", "None",
                "Ok", "Err", "true", "false", "self", "Self", "super", "crate", "async", "await",
                "move", "ref", "dyn", "where", "as", "const", "static",
            ]
        case "shell":
            return [
                "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until",
                "case", "esac", "function", "return", "exit", "export", "local", "echo", "cd",
                "set", "source", "sudo",
            ]
        case "json":
            return ["true", "false", "null"]
        case "yaml":
            return ["true", "false", "null", "yes", "no", "on", "off"]
        case "sql":
            return [
                "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
                "CREATE", "TABLE", "INDEX", "VIEW", "DROP", "ALTER", "ADD", "COLUMN", "JOIN",
                "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
                "OFFSET", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "DISTINCT",
                "COUNT", "SUM", "AVG", "MIN", "MAX", "PRIMARY", "KEY", "FOREIGN", "REFERENCES",
                "DEFAULT", "UNIQUE", "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC",
            ]
        default:
            return []
        }
    }

    /// 该语言的行注释前缀（`/* */` 不计，避免跨行状态机）。
    static func lineCommentPrefix(for language: String) -> String? {
        switch language {
        case "swift", "kotlin", "java", "typescript", "go", "rust":
            return "//"
        case "python", "shell", "yaml":
            return "#"
        case "sql":
            return "--"
        default:
            return nil
        }
    }

    /// 扫描着色 token（顺序扫描：注释 → 字符串 → 数字 → 标识符关键词）。
    static func tokens(in code: String, language: String?) -> [Token] {
        guard let canonical = canonical(language) else { return [] }
        let commentPrefix = lineCommentPrefix(for: canonical)
        let keywordSet = keywords(for: canonical)
        var result: [Token] = []
        var index = code.startIndex

        while index < code.endIndex {
            let rest = code[index...]

            if let prefix = commentPrefix, rest.hasPrefix(prefix) {
                let end = code[index...].firstIndex(of: "\n") ?? code.endIndex
                result.append(Token(range: index..<end, kind: .comment))
                index = end
                continue
            }

            let char = code[index]
            if char == "\"" || char == "'" || (char == "`" && canonical == "typescript") {
                // 单行字符串（不跨行；转义跳过）
                var cursor = code.index(after: index)
                var closed = false
                while cursor < code.endIndex {
                    let current = code[cursor]
                    if current == "\\" {
                        cursor = code.index(after: cursor)
                        if cursor < code.endIndex { cursor = code.index(after: cursor) }
                        continue
                    }
                    if current == char {
                        cursor = code.index(after: cursor)
                        closed = true
                        break
                    }
                    if current == "\n" { break }
                    cursor = code.index(after: cursor)
                }
                if closed {
                    result.append(Token(range: index..<cursor, kind: .string))
                    index = cursor
                    continue
                }
                index = code.index(after: index)
                continue
            }

            if char.isNumber, index == code.startIndex || !isIdentifier(code[code.index(before: index)]) {
                var cursor = index
                while cursor < code.endIndex, code[cursor].isNumber || code[cursor] == "_" || code[cursor] == "." {
                    cursor = code.index(after: cursor)
                }
                result.append(Token(range: index..<cursor, kind: .number))
                index = cursor
                continue
            }

            if isIdentifierStart(char) {
                var cursor = index
                while cursor < code.endIndex, isIdentifier(code[cursor]) {
                    cursor = code.index(after: cursor)
                }
                let word = String(code[index..<cursor])
                // SQL 关键字大小写不敏感（select/SELECT 都算）；其余语言区分大小写
                let isKeyword = canonical == "sql" ? keywordSet.contains(word.uppercased()) : keywordSet.contains(word)
                if isKeyword {
                    result.append(Token(range: index..<cursor, kind: .keyword))
                }
                index = cursor
                continue
            }

            index = code.index(after: index)
        }
        return result
    }

    /// 着色后的 AttributedString（未知语言返回原文样式）。
    static func highlight(_ code: String, language: String?, color: (Kind) -> Color) -> AttributedString {
        var attributed = AttributedString(code)
        for token in tokens(in: code, language: language) {
            guard let lower = AttributedString.Index(token.range.lowerBound, within: attributed),
                  let upper = AttributedString.Index(token.range.upperBound, within: attributed) else { continue }
            attributed[lower..<upper].foregroundColor = color(token.kind)
        }
        return attributed
    }

    private static func isIdentifierStart(_ char: Character) -> Bool {
        char == "_" || char.isLetter
    }

    private static func isIdentifier(_ char: Character) -> Bool {
        char == "_" || char.isLetter || char.isNumber
    }
}
