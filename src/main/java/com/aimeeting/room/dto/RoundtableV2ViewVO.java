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
    private JSONObject result;
}
