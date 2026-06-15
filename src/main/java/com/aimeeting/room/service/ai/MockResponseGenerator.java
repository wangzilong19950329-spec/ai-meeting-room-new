package com.aimeeting.room.service.ai;

/**
 * API 不可用时的兜底模拟回复，模拟真实群聊口吻。
 * 根据成员名、人设、议题、轮次生成有差异的对话内容。
 */
public class MockResponseGenerator {

    private static final String[][] CHAT_TEMPLATES = {
        // 0: 积极支持
        {
            "我觉得%s这件事其实挺值得推进的，现在条件也差不多成熟了。",
            "别总想着等，机会等来等去就没了。与其犹豫，不如先迈一步试试看。",
            "而且你们看，现在市面上已经有不少类似的成功案例了，风险没那么大的。"
        },
        // 1: 审慎质疑
        {
            "等等，%s这个事情我觉得大家想得太简单了。",
            "之前有过类似的尝试，最后怎么样大家也知道。我们不能光看好的一面。",
            "我不是反对，只是觉得在正式推进之前，至少要把风险点列出来仔细看看。"
        },
        // 2: 补充视角
        {
            "补充一下，%s这个问题其实还有另一个角度值得考虑。",
            "大家说的都有道理，但我感觉讨论一直围绕着执行层面，方向本身有没有问题反而没人说。",
            "能不能先把目标定清楚？大家对\"成功\"的定义好像也不太一样。"
        },
        // 3: 风险导向
        {
            "说到%s，我最担心的就是一旦出问题怎么办，有没有备案？",
            "我们现在讨论的是往前走，但万一走偏了，退回来的成本有没有算过？",
            "不是泼冷水，但做决策之前把最坏的情况想清楚，总比事后再补救强。"
        },
        // 4: 数据导向
        {
            "大家说了这么多，有没有实际数据支撑一下？光靠感觉说话不太够。",
            "关于%s，我查了一些资料，情况比想象中复杂，简单类比可能不太准。",
            "能不能先做个小规模测试，拿到真实数据再来讨论？这样比较有说服力。"
        },
        // 5: 折中推进
        {
            "我觉得大家说的都有点道理，但其实可以折中一下。",
            "%s这件事，完全不做风险也有，但硬上风险更大。分阶段来可能是最稳的方式。",
            "先定一个最小可行的方案，快速跑一遍，有问题及时调，比在这里争要实在得多。"
        },
    };

    public static String generate(String systemPrompt, String userPrompt) {
        String memberName = extractMemberName(systemPrompt);
        String persona    = extractPersona(systemPrompt);
        String topic      = extractTopic(userPrompt);
        int    round      = extractRound(userPrompt);

        String topicLabel = (topic == null || topic.isEmpty()) ? "这件事" : topic;

        long h = 17L;
        h = h * 31 + memberName.hashCode();
        h = h * 31 + persona.hashCode();
        h = h * 31 + topic.hashCode();
        h = h * 31 + round;
        int templateIdx  = (int)(Math.abs(h) % CHAT_TEMPLATES.length);
        int sentenceIdx  = (int)(Math.abs(h / 7) % 3);

        String[] tpl = CHAT_TEMPLATES[templateIdx];
        String sentence = tpl[sentenceIdx];
        // 只在有 %s 占位符时替换
        if (sentence.contains("%s")) sentence = String.format(sentence, topicLabel);

        return sentence;
    }

    // ---- 辅助提取方法 ----

    private static String extractMemberName(String systemPrompt) {
        if (systemPrompt == null) return "成员";
        int s = systemPrompt.indexOf("「"), e = systemPrompt.indexOf("」");
        return (s >= 0 && e > s) ? systemPrompt.substring(s + 1, e) : "成员";
    }

    private static String extractPersona(String systemPrompt) {
        if (systemPrompt == null) return "";
        String marker = "背景：";
        int s = systemPrompt.indexOf(marker);
        if (s < 0) { marker = "人设："; s = systemPrompt.indexOf(marker); }
        if (s < 0) return "";
        s += marker.length();
        int e = systemPrompt.indexOf("\n", s);
        return e >= 0 ? systemPrompt.substring(s, e).trim() : systemPrompt.substring(s).trim();
    }

    private static String extractTopic(String userPrompt) {
        if (userPrompt == null) return "";
        String marker = "【会议主题】";
        int s = userPrompt.indexOf(marker);
        if (s < 0) return "";
        s += marker.length();
        int e = userPrompt.indexOf("\n", s);
        return e >= 0 ? userPrompt.substring(s, e).trim() : userPrompt.substring(s).trim();
    }

    private static int extractRound(String userPrompt) {
        if (userPrompt == null) return 1;
        String marker = "【当前轮次】第";
        int s = userPrompt.indexOf(marker);
        if (s < 0) return 1;
        s += marker.length();
        int e = s;
        while (e < userPrompt.length() && Character.isDigit(userPrompt.charAt(e))) e++;
        try { return Integer.parseInt(userPrompt.substring(s, e)); }
        catch (NumberFormatException ex) { return 1; }
    }
}
