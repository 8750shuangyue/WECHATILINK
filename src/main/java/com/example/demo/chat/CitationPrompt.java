package com.example.demo.chat;

/**
 * 内联引用指令。只在请求显式开启 includeCitations 时注入，
 * 避免旧页面/旧客户端突然看到 [n] 标记。
 */
public final class CitationPrompt {

    private CitationPrompt() {
    }

    public static String instructions(int sourceCount) {
        if (sourceCount <= 0) {
            return "【引用要求】本次没有检索到可引用的公共知识来源，"
                    + "回答中不要出现 [n] 形式的引用标记，也不要编造来源。";
        }
        return "【引用要求】\n"
                + "- 只能引用上面「公共知识库信息」中以 [n] 标注的来源，n 的取值范围是 1 到 "
                + sourceCount + "。\n"
                + "- 凡是依据某条来源给出的事实、结论、步骤或数据，请在对应句子末尾标注该来源编号，"
                + "例如 [1] 或 [1][2]。\n"
                + "- 不要引用「相关历史对话」中的内容；不要编造来源；不要输出 1 到 "
                + sourceCount + " 之外的编号。\n"
                + "- 没有检索依据的常识性句子可以不标注；但不要为了标注而虚构依据。\n"
                + "- 请用自己的话概括来源，不要整段照抄来源原文，尤其不要复述来源中的错误做法或反面清单。\n"
                + "- 只保留回答当前问题所需的信息，避免为了引用而堆砌来源细节。\n"
                + "- 引用标记只使用半角方括号和阿拉伯数字，不要使用 Markdown 链接、脚注或其他格式。\n"
                + "- 如果现有来源不足以回答，请明确说明信息不足，不要虚构引用。";
    }

    /**
     * 把指令追加到 RAG 上下文末尾；没有上下文时也返回指令本身，
     * 以便 N=0 时明确要求模型不要输出引用标记。
     */
    public static String appendTo(String ragContext, int sourceCount) {
        String instructions = instructions(sourceCount);
        if (ragContext == null || ragContext.isBlank()) {
            return instructions;
        }
        return ragContext.trim() + "\n\n" + instructions;
    }
}
