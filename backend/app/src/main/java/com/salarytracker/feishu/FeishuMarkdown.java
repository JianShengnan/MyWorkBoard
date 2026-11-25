package com.salarytracker.feishu;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 AI 输出的 markdown 收敛成飞书 {@code lark_md} 真正能渲染的子集。
 *
 * <p>实测结论：{@code lark_md} 不支持表格、标题和分隔线——表格会被原样显示成一排竖线，
 * 标题前面的 {@code #} 也会留着。而模型很爱用表格列任务，所以这里在渲染前改写：
 * 表格转成「加粗首列 + 其余列名:值」的项目符号，标题转加粗，分隔线直接丢掉。</p>
 */
final class FeishuMarkdown {
    private FeishuMarkdown() {
    }

    static String render(String source) {
        if (source == null || source.isEmpty()) return "";
        List<String> lines = new ArrayList<>(List.of(source.split("\n", -1)));
        List<String> out = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            String current = lines.get(index);
            if (isTableRow(current) && index + 1 < lines.size() && isDivider(lines.get(index + 1))) {
                List<String> header = cells(current);
                index += 2;
                while (index < lines.size() && isTableRow(lines.get(index))) {
                    out.addAll(table(header, lines.get(index)));
                    index++;
                }
                continue;
            }
            // null 表示这一行渲染后应该消失（分隔线），空行要原样保留，否则段落会粘在一起
            String converted = single(current);
            if (converted != null) out.add(converted);
            index++;
        }
        return String.join("\n", out);
    }

    /** 表格的一行 → 一条或多条项目符号。 */
    private static List<String> table(List<String> header, String raw) {
        List<String> values = cells(raw);
        StringBuilder line = new StringBuilder("- ");
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value.isEmpty()) continue;
            if (line.length() > 2) line.append(" · ");
            if (i == 0) {
                line.append("**").append(value).append("**");
            } else if (i < header.size() && !header.get(i).isEmpty()) {
                line.append(header.get(i)).append('：').append(value);
            } else {
                line.append(value);
            }
        }
        return line.length() == 2 ? List.of() : List.of(line.toString());
    }

    private static String single(String line) {
        String trimmed = line.trim();
        // --- / *** / ___ 分隔线：卡片自身有 <hr>，这里留着只会显示三个减号
        if (trimmed.matches("^([-*_]\\s*){3,}$")) return null;
        // 标题：# / ## / ### → 加粗
        if (trimmed.startsWith("#")) {
            String title = trimmed.replaceFirst("^#+\\s*", "").trim();
            return title.isEmpty() ? null : "**" + title + "**";
        }
        // 引用：lark_md 不认 "> "，去掉符号留正文
        if (trimmed.startsWith("> ")) return trimmed.substring(2);
        return line;
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.length() > 1 && trimmed.startsWith("|") && trimmed.endsWith("|");
    }

    private static boolean isDivider(String line) {
        if (!isTableRow(line)) return false;
        for (String cell : cells(line)) {
            if (!cell.replace(":", "").replace("-", "").trim().isEmpty()) return false;
        }
        return true;
    }

    /** 切单元格，支持 markdown 里转义的 {@code \|}。 */
    private static List<String> cells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if (current == '\\' && i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (current == '|') {
                result.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        result.add(cell.toString().trim());
        return result;
    }
}
