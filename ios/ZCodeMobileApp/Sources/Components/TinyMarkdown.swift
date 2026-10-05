import SwiftUI

/// 轻量 Markdown 渲染：标题 / **粗体** / `行内代码` / ```代码块``` / - 列表。
enum TinyMarkdown {

    struct Block: Identifiable {
        let id: Int
        let content: BlockContent
    }

    enum BlockContent {
        case heading(String, level: Int)
        case paragraph(String)
        case listItem(String)
        case code(language: String?, code: String)
        case table(header: [String], rows: [[String]])
    }

    static func parse(_ text: String) -> [Block] {
        var blocks: [Block] = []
        var id = 0
        let lines = text.components(separatedBy: "\n")
        var index = 0

        func push(_ content: BlockContent) {
            id += 1
            blocks.append(Block(id: id, content: content))
        }

        while index < lines.count {
            let line = lines[index]
            if line.hasPrefix("```") {
                let language = String(line.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                index += 1
                var code: [String] = []
                while index < lines.count, !lines[index].hasPrefix("```") {
                    code.append(lines[index])
                    index += 1
                }
                index += 1
                push(.code(language: language.isEmpty ? nil : language, code: code.joined(separator: "\n")))
                continue
            }
            // 管道表格：连续 | 开头的行；第 2 行为分隔行（|---|---|）则首行是表头
            if line.trimmingCharacters(in: .whitespaces).hasPrefix("|") {
                var tableLines: [String] = []
                while index < lines.count, lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("|") {
                    tableLines.append(lines[index])
                    index += 1
                }
                guard tableLines.count >= 2 else {
                    tableLines.forEach { push(.paragraph($0)) }
                    continue
                }
                func cellsOf(_ row: String) -> [String] {
                    var parts = row.split(separator: "|", omittingEmptySubsequences: false)
                        .map { $0.trimmingCharacters(in: .whitespaces) }
                    if let first = parts.first, first.isEmpty { parts.removeFirst() }
                    if let last = parts.last, last.isEmpty { parts.removeLast() }
                    return parts
                }
                let header = cellsOf(tableLines[0])
                var rows: [[String]] = []
                for rowLine in tableLines.dropFirst() {
                    if rowLine.range(of: "^\\|[\\s:|-]+\\|\\s*$", options: .regularExpression) != nil { continue } // 分隔行
                    rows.append(cellsOf(rowLine))
                }
                if !header.isEmpty {
                    push(.table(header: header, rows: rows))
                }
                continue
            }
            if line.hasPrefix("### ") {
                push(.heading(String(line.dropFirst(4)), level: 3))
            } else if line.hasPrefix("## ") {
                push(.heading(String(line.dropFirst(3)), level: 2))
            } else if line.hasPrefix("# ") {
                push(.heading(String(line.dropFirst(2)), level: 1))
            } else if line.hasPrefix("- ") || line.hasPrefix("* ") {
                push(.listItem(String(line.dropFirst(2))))
            } else if line.trimmingCharacters(in: .whitespaces).isEmpty {
                // 空行分段
            } else {
                push(.paragraph(line))
            }
            index += 1
        }
        return blocks
    }
}

struct MarkdownBlockView: View {
    let block: TinyMarkdown.Block
    var codeFontSize: CGFloat = 12

    var body: some View {
        switch block.content {
        case .heading(let text, let level):
            inline(text)
                .font(T.font(level == 1 ? 18 : level == 2 ? 16 : 14.5, .bold))
                .foregroundColor(T.text)
                .padding(.top, level <= 2 ? T.sp2 : 0)
        case .paragraph(let text):
            inline(text)
                .font(T.font(14.5))
                .foregroundColor(T.text)
                .lineSpacing(4)
        case .listItem(let text):
            HStack(alignment: .firstTextBaseline, spacing: T.sp2) {
                Circle().fill(T.accentText).frame(width: 5, height: 5).offset(y: -2)
                inline(text)
                    .font(T.font(14.5))
                    .foregroundColor(T.text)
                    .lineSpacing(4)
            }
        case .code(let language, let code):
            CodeBlockView(language: language, code: code, fontSize: codeFontSize)
        case .table(let header, let rows):
            MarkdownTableView(header: header, rows: rows, cellFontSize: codeFontSize)
        }
    }
}

/// 管道表格渲染：表头强调底色 + 行分隔线 + 横向滚动（窄屏不挤压列）
struct MarkdownTableView: View {
    let header: [String]
    let rows: [[String]]
    var cellFontSize: CGFloat = 12

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: T.sp3) {
                    ForEach(Array(header.enumerated()), id: \.offset) { _, cell in
                        inline(cell)
                            .font(T.mono(cellFontSize, .semibold))
                            .foregroundColor(T.text)
                    }
                }
                .padding(.horizontal, T.sp3)
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(T.bgCode)
                Divider().overlay(T.border)
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(alignment: .firstTextBaseline, spacing: T.sp3) {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                            inline(cell)
                                .font(T.mono(cellFontSize))
                                .foregroundColor(T.text2)
                        }
                    }
                    .padding(.horizontal, T.sp3)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Divider().overlay(T.border)
                }
            }
        }
        .background(T.bgCard)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
    }
}

/// 行内标记：**粗体** 与 `行内代码`
private func inline(_ text: String) -> Text {
    var result = Text("")
    var remaining = Substring(text)

    while let open = remaining.firstIndex(where: { $0 == "*" || $0 == "`" }) {
        result = result + Text(String(remaining[remaining.startIndex..<open]))
        let marker = remaining[open]
        let contentStart = remaining.index(after: open)
        if let close = remaining[contentStart...].firstIndex(of: marker),
           contentStart < close {
            let inner = String(remaining[contentStart..<close])
            remaining = remaining[remaining.index(after: close)...]
            switch marker {
            case "*":
                result = result + Text(inner).bold()
            default:
                result = result + Text(inner)
                    .font(.system(size: 13, weight: .regular, design: .monospaced))
                    .foregroundColor(T.accentText)
            }
        } else {
            result = result + Text(String(marker))
            remaining = remaining[contentStart...]
        }
    }
    result = result + Text(String(remaining))
    return result
}

/// 代码块：bg-code 底、12px mono、头部 44px 带复制
struct CodeBlockView: View {
    let language: String?
    let code: String
    var fontSize: CGFloat = 12
    var showHeader: Bool = true

    @State private var copied = false

    var body: some View {
        VStack(spacing: 0) {
            if showHeader {
                HStack {
                    Text(language?.uppercased() ?? "CODE")
                        .font(T.mono(10.5, .semibold))
                        .foregroundColor(T.codeLab)
                    Spacer()
                    Button {
                        UIPasteboard.general.string = code
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { copied = false }
                    } label: {
                        Label(copied ? "已复制" : "复制", systemImage: copied ? "checkmark" : "doc.on.doc")
                            .font(T.font(11, .medium))
                            .foregroundColor(copied ? T.accentText : T.text3)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityIdentifier("09-code-copy")
                }
                .padding(.horizontal, T.sp3)
                .frame(height: 44)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(T.mono(fontSize))
                    .foregroundColor(T.text2)
                    .lineSpacing(4)
                    .padding(T.sp3)
                    .frame(minWidth: UIScreen.main.bounds.width - 80, alignment: .leading)
            }
        }
        .background(T.bgCode)
        .clipShape(RoundedRectangle(cornerRadius: T.rM))
        .overlay(RoundedRectangle(cornerRadius: T.rM).stroke(T.border, lineWidth: 1))
    }
}
