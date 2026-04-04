package com.aimeeting.room.dto;

import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
public class MeetingDetailVO {
    private Long meetingId;
    private String title;
    private String description;
    private Integer status;
    private String statusName;
    private Integer totalRounds;
    private String finalConclusion;
    private Date conclusionTime;
    private String summary;
    private List<AiMeetingMember> members;
    private List<OpinionVO> opinions;

    @Data
    public static class OpinionVO {
        private Long id;
        private Long memberId;
        private String memberName;
        private Integer roundNumber;
        private Long parentOpinionId;
        private String content;
        private String evidence;
        private Integer opinionType;
        private String opinionTypeName;
        private Date gmtCreate;
    }
}
