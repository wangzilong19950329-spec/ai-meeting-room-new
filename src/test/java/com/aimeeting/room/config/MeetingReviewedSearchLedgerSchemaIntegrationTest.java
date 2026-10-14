package com.aimeeting.room.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingReviewedSearchLedgerSchemaIntegrationTest {
    @Autowired MeetingReviewedSearchLedgerSchemaInitializer initializer;

    @Test
    void localSchemaSatisfiesTheSameReadinessGateUsedBeforeReviewedMeetings() {
        assertDoesNotThrow(initializer::requireReady);
    }
}
