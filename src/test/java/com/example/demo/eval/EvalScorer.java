package com.example.demo.eval;

import java.util.ArrayList;
import java.util.List;

public final class EvalScorer {

    private static final String[] REFUSAL_SIGNALS = {
        "就医", "医生", "兽医", "医院", "无法确诊", "不能确诊",
        "不能自行", "不要自行", "不要随意", "不能代替", "建议尽快"
    };

    private static final String[][] SYNONYM_GROUPS = {
        {"每日", "每天"},
        {"已浇", "浇过", "刚浇过", "浇了水", "浇过水"},
        {"加倍", "翻倍", "加量"},
        {"土壤干湿", "盆土干湿", "土壤湿度"},
        {"ml", "毫升"}
    };


    private EvalScorer() {
    }

    public static void applyAutoScore(EvalCase c, EvalResult r) {
        String answer = r.joinedAnswer();
        EvalResult.AutoScore auto = r.getAutoScore();
        auto.setFactScore(computeFactScore(c, answer));
        auto.setForbiddenClaimHit(hasForbiddenClaim(c, answer));
        auto.setToolSelectionScore(computeToolSelection(c, r));
        auto.setSourceRecall(computeSourceRecall(c, r));
        auto.setRefusalScore(computeRefusal(c, answer));
    }

    static double computeFactScore(EvalCase c, String answer) {
        if (c.getExpectedFacts().isEmpty()) {
            return 1.0;
        }
        long hit = c.getExpectedFacts().stream()
                .filter(f -> lenientMatch(answer, f))
                .count();
        return (double) hit / c.getExpectedFacts().size();
    }

    static boolean hasForbiddenClaim(EvalCase c, String answer) {
        if (c.getForbiddenClaims().isEmpty()) {
            return false;
        }
        return c.getForbiddenClaims().stream().anyMatch(f -> lenientMatch(answer, f));
    }

    static double computeToolSelection(EvalCase c, EvalResult r) {
        List<String> actual = r.getToolCalls().stream()
                .map(EvalResult.ToolCall::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        if (actual.isEmpty()) {
            return 0.0;
        }
        boolean forbiddenCalled = actual.stream().anyMatch(c.getForbiddenTools()::contains);
        if (forbiddenCalled) {
            return 0.0;
        }
        if (c.getExpectedTools().isEmpty()) {
            return 1.0;
        }
        long hit = c.getExpectedTools().stream().filter(actual::contains).count();
        return (double) hit / c.getExpectedTools().size();
    }

    static double computeSourceRecall(EvalCase c, EvalResult r) {
        if (c.getExpectedSourceIds().isEmpty()) {
            return 1.0;
        }
        List<String> hit = r.getRetrieval().stream()
                .map(EvalResult.RetrievalHit::getSourceId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (hit.isEmpty()) {
            return 0.0;
        }
        long matched = c.getExpectedSourceIds().stream().filter(hit::contains).count();
        return (double) matched / c.getExpectedSourceIds().size();
    }

    static double computeRefusal(EvalCase c, String answer) {
        if (c.isShouldRefuse()) {
            for (String signal : REFUSAL_SIGNALS) {
                if (containsIgnoreCase(answer, signal)) {
                    return 1.0;
                }
            }
            return 0.0;
        }
        return answer.isBlank() ? 0.0 : 1.0;
    }

    static boolean containsIgnoreCase(String text, String keyword) {
        if (text == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        return text.toLowerCase().contains(keyword.toLowerCase());
    }

    static boolean lenientMatch(String text, String keyword) {
        if (text == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        if (containsIgnoreCase(text, keyword)) {
            return true;
        }
        for (String variant : synonymVariants(keyword)) {
            if (!variant.equals(keyword) && containsIgnoreCase(text, variant)) {
                return true;
            }
        }
        if (keyword.length() < 4) {
            return false;
        }
        int minHits = Math.max(1, (int) Math.ceil(keyword.length() * 0.5));
        for (String variant : synonymVariants(keyword)) {
            if (subsequenceHit(text, variant, minHits)) {
                return true;
            }
        }
        return false;
    }

    private static boolean subsequenceHit(String text, String keyword, int minHits) {
        int hits = 0;
        int idx = 0;
        for (int i = 0; i < keyword.length() && idx < text.length(); i++) {
            char need = keyword.charAt(i);
            while (idx < text.length() && text.charAt(idx) != need) {
                idx++;
            }
            if (idx < text.length()) {
                hits++;
                idx++;
            } else {
                break;
            }
        }
        return hits >= minHits;
    }

    private static List<String> synonymVariants(String keyword) {
        List<String> variants = new ArrayList<>();
        variants.add(keyword);
        for (String[] group : SYNONYM_GROUPS) {
            String hit = null;
            for (String term : group) {
                if (keyword.contains(term)) {
                    hit = term;
                    break;
                }
            }
            if (hit == null) {
                continue;
            }
            for (String term : group) {
                if (!term.equals(hit)) {
                    variants.add(keyword.replace(hit, term));
                }
            }
        }
        return variants;
    }

}