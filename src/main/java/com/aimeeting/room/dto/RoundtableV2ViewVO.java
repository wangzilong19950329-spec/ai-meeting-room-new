package com.aimeeting.room.dto;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;

/** Everything a roundtable v2 meeting produced: issue card, fact base, member answers and the summary. */
@Data
public class RoundtableV2ViewVO {
    private JSONObject issueCard;
    /** Probability question over return intervals; only for domains with a judgeable outcome. */
    private JSONObject outcomeQuestion;
    private JSONArray facts = new JSONArray();
    private JSONArray answers = new JSONArray();
    /** Program-chosen disputes, pairs and red-team target for the cross-examination. */
    private JSONObject agenda;
    private JSONArray crossExams = new JSONArray();
    private JSONObject redTeam;
    /** Answers after the anonymous re-estimate; the summary is built from these. */
    private JSONArray finalAnswers = new JSONArray();
    /** Moderator's type, crux and evidence question for each dispute left after the first round. */
    private JSONObject classification;
    /** Facts added by evidence top-up for fact disputes (budget: three questions per meeting). */
    private JSONObject topUp;
    private JSONArray secondCrossExams = new JSONArray();
    private JSONArray secondFinalAnswers = new JSONArray();
    private JSONObject result;
    /** The judged outcome and forecast scores, once the horizon has passed. */
    private JSONObject outcomeJudgement;
    /** The one-pass answer of a single analyst on the same card and fact base, made on request as a control. */
    private JSONObject solo;
    /** Each member's own evidence questions: what was reused from existing facts and what was searched. */
    private JSONArray research = new JSONArray();
    /** What the user added or asked while the meeting ran, and how each was applied. */
    private JSONArray interventions = new JSONArray();
}
