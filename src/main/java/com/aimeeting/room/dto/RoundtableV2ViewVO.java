package com.aimeeting.room.dto;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;

/** Everything a roundtable v2 meeting produced: issue card, fact base, member answers and the summary. */
@Data
public class RoundtableV2ViewVO {
    private JSONObject issueCard;
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
}
