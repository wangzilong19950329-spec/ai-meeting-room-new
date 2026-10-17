package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingV2RecordMapper;
import com.aimeeting.room.entity.AiMeetingTrackingItem;
import com.aimeeting.room.entity.AiMeetingV2Record;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Judges a roundtable v2 outcome question once its horizon has passed and scores the forecasts with the
 * ranked probability score (RPS, main) and the Brier score, next to a uniform guess as the baseline.
 * The first verdict is stored and never recomputed.
 */
@Service
public class RoundtableV2OutcomeJudge implements ProgramTrackingHandler {

    static final String OUTCOME_EXPIRY = "OUTCOME_EXPIRY";
    static final String OUTCOME_JUDGEMENT = "OUTCOME_JUDGEMENT";
    private static final long MEETING_LEVEL = 0L;

    private final AiMeetingV2RecordMapper recordMapper;
    private final List<MeetingDomain> domains;

    public RoundtableV2OutcomeJudge(AiMeetingV2RecordMapper recordMapper, @Nullable List<MeetingDomain> domains) {
        this.recordMapper = recordMapper;
        this.domains = domains == null ? List.of() : domains;
    }

    /** JUDGED (stored), NOT_DUE, UNAVAILABLE (try again later) or NOT_APPLICABLE (nothing to judge). */
    public JSONObject judge(Long meetingId) {
        JSONObject stored = load(meetingId, OUTCOME_JUDGEMENT);
        if (stored != null) return stored;
        JSONObject card = load(meetingId, RoundtableV2Service.ISSUE_CARD);
        JSONObject question = load(meetingId, RoundtableV2Service.OUTCOME);
        JSONObject result = load(meetingId, RoundtableV2Service.RESULT);
        if (card == null || question == null) return verdict("NOT_APPLICABLE", "这场会议没有可判定的结果题");
        if (result == null || result.getJSONObject("outcome") == null) {
            return verdict("NOT_APPLICABLE", "会议还没有形成合议分布");
        }
        MeetingDomain.OutcomeObservation observed = null;
        for (MeetingDomain domain : domains) {
            observed = domain.observeOutcome(card, question);
            if (observed != null) break;
        }
        if (observed == null) return verdict("NOT_APPLICABLE", "没有能判定这道结果题的领域");
        if (MeetingDomain.OutcomeObservation.NOT_DUE.equals(observed.status)) {
            JSONObject pending = verdict("NOT_DUE", null);
            pending.put("elapsedTradingDays", observed.elapsedTradingDays);
            pending.put("horizonTradingDays", question.getIntValue("horizonTradingDays"));
            pending.put("baseDate", question.getString("baseDate"));
            return pending;
        }
        if (MeetingDomain.OutcomeObservation.UNAVAILABLE.equals(observed.status)) {
            return verdict("UNAVAILABLE", observed.reason);
        }
        return save(meetingId, scored(question, result, finalAnswers(meetingId), observed));
    }

    @Override
    public boolean supports(String itemType) {
        return OUTCOME_EXPIRY.equals(itemType);
    }

    @Override
    public Check refresh(AiMeetingTrackingItem item) {
        JSONObject verdict = judge(item.getMeetingId());
        Date tomorrow = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24));
        switch (verdict.getString("status")) {
            case "JUDGED":
                return new Check("success", note(verdict), null, true);
            case "NOT_DUE":
                return new Check("pending", "未到期：起点 " + verdict.getString("baseDate") + " 之后已过 "
                        + verdict.getIntValue("elapsedTradingDays") + "/" + verdict.getIntValue("horizonTradingDays")
                        + " 个交易日，明天再查。", tomorrow, false);
            case "UNAVAILABLE":
                return new Check("error", "暂时无法判定：" + verdict.getString("reason") + "。明天再查。", tomorrow, false);
            default:
                return new Check("error", verdict.getString("reason"), null, false);
        }
    }

    private static JSONObject scored(JSONObject question, JSONObject result, List<JSONObject> answers,
                                     MeetingDomain.OutcomeObservation observed) {
        JSONArray options = question.getJSONArray("options");
        double returnPct = observed.endClose.divide(observed.baseClose, 10, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).doubleValue() * 100;
        int hit = bin(options, returnPct);
        JSONObject verdict = verdict("JUDGED", null);
        verdict.put("baseDate", observed.baseDate);
        verdict.put("baseClose", observed.baseClose);
        verdict.put("endDate", observed.endDate);
        verdict.put("endClose", observed.endClose);
        verdict.put("horizonTradingDays", question.getIntValue("horizonTradingDays"));
        verdict.put("returnPct", round(returnPct, 2));
        verdict.put("observedOptionId", options.getJSONObject(hit).getString("id"));
        verdict.put("observedLabel", options.getJSONObject(hit).getString("label"));
        verdict.put("source", observed.source);

        double[] pooled = new double[options.size()];
        JSONArray summary = result.getJSONObject("outcome").getJSONArray("options");
        for (int i = 0; i < options.size(); i++) {
            String id = options.getJSONObject(i).getString("id");
            for (Object item : summary) {
                JSONObject line = (JSONObject) item;
                if (id.equals(line.getString("id"))) pooled[i] = line.getDoubleValue("mean");
            }
        }
        double[] uniform = new double[options.size()];
        java.util.Arrays.fill(uniform, 1.0 / options.size());
        verdict.put("pooled", scores(pooled, hit));
        verdict.put("uniform", scores(uniform, hit));

        JSONArray members = new JSONArray();
        for (JSONObject answer : answers) {
            double[] forecast = forecastOf(answer, question.getString("id"), options);
            if (forecast == null) continue;
            JSONObject member = scores(forecast, hit);
            member.put("memberId", answer.get("memberId"));
            member.put("memberName", answer.getString("memberName"));
            members.add(member);
        }
        verdict.put("members", members);
        verdict.put("judgedAt", Instant.now().toString());
        return verdict;
    }

    /**
     * Which interval the return fell into. The middle interval includes both bounds; on the falling side the
     * lower bound belongs to the interval, on the rising side the upper bound does.
     */
    static int bin(JSONArray options, double returnPct) {
        for (int i = 0; i < options.size(); i++) {
            JSONObject option = options.getJSONObject(i);
            Double lower = option.getDouble("lowerPct");
            Double upper = option.getDouble("upperPct");
            boolean in;
            if (lower == null) in = returnPct < upper;
            else if (upper == null) in = returnPct > lower;
            else if (lower < 0 && upper > 0) in = returnPct >= lower && returnPct <= upper;
            else if (upper <= 0) in = returnPct >= lower && returnPct < upper;
            else in = returnPct > lower && returnPct <= upper;
            if (in) return i;
        }
        throw new IllegalStateException("return " + returnPct + "% matches no outcome interval");
    }

    /** RPS = mean squared gap between cumulative forecast and cumulative outcome; Brier = squared gap per interval. */
    private static JSONObject scores(double[] forecast, int hit) {
        double cumulativeForecast = 0;
        double rps = 0;
        for (int k = 0; k < forecast.length - 1; k++) {
            cumulativeForecast += forecast[k];
            double cumulativeOutcome = k >= hit ? 1 : 0;
            rps += Math.pow(cumulativeForecast - cumulativeOutcome, 2);
        }
        double brier = 0;
        for (int k = 0; k < forecast.length; k++) brier += Math.pow(forecast[k] - (k == hit ? 1 : 0), 2);
        JSONObject scores = new JSONObject(true);
        scores.put("rps", round(rps / (forecast.length - 1), 4));
        scores.put("brier", round(brier, 4));
        return scores;
    }

    private static double[] forecastOf(JSONObject answer, String questionId, JSONArray options) {
        JSONArray items = answer.getJSONArray("answers");
        for (int i = 0; items != null && i < items.size(); i++) {
            JSONObject probabilities = items.getJSONObject(i).getJSONObject("probabilities");
            if (!questionId.equals(items.getJSONObject(i).getString("questionId")) || probabilities == null) continue;
            double[] forecast = new double[options.size()];
            for (int k = 0; k < options.size(); k++) {
                forecast[k] = probabilities.getDoubleValue(options.getJSONObject(k).getString("id"));
            }
            return forecast;
        }
        return null;
    }

    /** The answers the summary was built from: the latest re-estimate round, else the first answers. */
    private List<JSONObject> finalAnswers(Long meetingId) {
        List<AiMeetingV2Record> records = recordMapper.selectByMeetingId(meetingId);
        for (String kind : List.of(RoundtableV2Service.FINAL_ANSWER + RoundtableV2Service.SECOND_ROUND,
                RoundtableV2Service.FINAL_ANSWER, RoundtableV2Service.ANSWER)) {
            List<JSONObject> answers = new ArrayList<>();
            for (AiMeetingV2Record record : records == null ? List.<AiMeetingV2Record>of() : records) {
                if (!kind.equals(record.getKind())) continue;
                JSONObject answer = JSON.parseObject(record.getPayload());
                if (!answer.getBooleanValue("skipped")) answers.add(answer);
            }
            if (!answers.isEmpty()) return answers;
        }
        return List.of();
    }

    private static String note(JSONObject verdict) {
        StringBuilder sb = new StringBuilder("到期判定：实际涨跌 ")
                .append(signed(verdict.getDoubleValue("returnPct"))).append("%（")
                .append(verdict.getString("baseDate")).append(" 收盘 ").append(verdict.getBigDecimal("baseClose"))
                .append(" → ").append(verdict.getString("endDate")).append(" 收盘 ").append(verdict.getBigDecimal("endClose"))
                .append("，").append(verdict.getString("source")).append("前复权），落在「")
                .append(verdict.getString("observedLabel")).append("」。合议 RPS ")
                .append(verdict.getJSONObject("pooled").getBigDecimal("rps")).append("（越低越好），均匀猜测基准 ")
                .append(verdict.getJSONObject("uniform").getBigDecimal("rps"));
        JSONArray members = verdict.getJSONArray("members");
        for (int i = 0; i < members.size(); i++) {
            JSONObject member = members.getJSONObject(i);
            sb.append(i == 0 ? "；议员：" : "，").append(member.getString("memberName")).append(" ")
                    .append(member.getBigDecimal("rps"));
        }
        return sb.append("。").toString();
    }

    private static String signed(double value) {
        return (value > 0 ? "+" : "") + BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static JSONObject verdict(String status, String reason) {
        JSONObject verdict = new JSONObject(true);
        verdict.put("status", status);
        if (reason != null) verdict.put("reason", reason);
        return verdict;
    }

    private static BigDecimal round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private JSONObject load(Long meetingId, String kind) {
        AiMeetingV2Record record = recordMapper.selectOne(meetingId, kind, MEETING_LEVEL);
        return record == null ? null : JSON.parseObject(record.getPayload());
    }

    /** First verdict wins; a concurrent judgment returns what is already stored. */
    private JSONObject save(Long meetingId, JSONObject verdict) {
        AiMeetingV2Record result = recordMapper.selectOne(meetingId, RoundtableV2Service.RESULT, MEETING_LEVEL);
        AiMeetingV2Record record = new AiMeetingV2Record();
        record.setMeetingId(meetingId);
        record.setKind(OUTCOME_JUDGEMENT);
        record.setMemberId(MEETING_LEVEL);
        record.setPayload(JSON.toJSONString(verdict));
        record.setRunEpoch(result == null ? null : result.getRunEpoch());
        try {
            recordMapper.insert(record);
            return verdict;
        } catch (DuplicateKeyException alreadyJudged) {
            return load(meetingId, OUTCOME_JUDGEMENT);
        }
    }
}
