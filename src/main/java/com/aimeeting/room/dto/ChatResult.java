package com.aimeeting.room.dto;

import com.aimeeting.room.service.ai.WebSearchService;
import java.util.Collections;
import java.util.List;

/**
 * AI 调用结果，携带内容 + 工具调用期间产生的搜索证据
 */
public class ChatResult {
    public final String content;
    public final List<String> searchQueries;
    public final List<WebSearchService.SearchResult> searchResults;
    public final String searchMode;
    public final String searchStatus;
    public final String searchNote;
    public final String modelStatus;
    public final String modelNote;
    public final boolean providerOutcomeUnknown;
    public final Boolean retrySafe;
    /** Model reply rejected by grounding; visible only as an unverified draft. */
    public final String unverifiedReply;
    public final String rejectedClaimAuditJson;

    public ChatResult(String content) {
        this(content, Collections.emptyList(), Collections.emptyList(), null, null, null);
    }

    public ChatResult(String content, List<String> searchQueries, List<WebSearchService.SearchResult> searchResults) {
        this(content, searchQueries, searchResults, null, null, null);
    }

    public ChatResult(String content,
                      List<String> searchQueries,
                      List<WebSearchService.SearchResult> searchResults,
                      String searchMode,
                      String searchStatus,
                      String searchNote) {
        this(content, searchQueries, searchResults, searchMode, searchStatus, searchNote, null, null);
    }

    public ChatResult(String content,
                      List<String> searchQueries,
                      List<WebSearchService.SearchResult> searchResults,
                      String searchMode,
                      String searchStatus,
                      String searchNote,
                      String modelStatus,
                      String modelNote) {
        this(content, searchQueries, searchResults, searchMode, searchStatus, searchNote,
                modelStatus, modelNote, false, null);
    }

    public ChatResult(String content,
                      List<String> searchQueries,
                      List<WebSearchService.SearchResult> searchResults,
                      String searchMode,
                      String searchStatus,
                      String searchNote,
                      String modelStatus,
                      String modelNote,
                      boolean providerOutcomeUnknown,
                      Boolean retrySafe) {
        this(content, searchQueries, searchResults, searchMode, searchStatus, searchNote,
                modelStatus, modelNote, providerOutcomeUnknown, retrySafe, null, null);
    }

    public ChatResult(String content,
                      List<String> searchQueries,
                      List<WebSearchService.SearchResult> searchResults,
                      String searchMode,
                      String searchStatus,
                      String searchNote,
                      String modelStatus,
                      String modelNote,
                      boolean providerOutcomeUnknown,
                      Boolean retrySafe,
                      String unverifiedReply,
                      String rejectedClaimAuditJson) {
        this.content = content;
        this.searchQueries = searchQueries != null ? searchQueries : Collections.emptyList();
        this.searchResults = searchResults != null ? searchResults : Collections.emptyList();
        this.searchMode = searchMode;
        this.searchStatus = searchStatus;
        this.searchNote = searchNote;
        this.modelStatus = modelStatus;
        this.modelNote = modelNote;
        this.providerOutcomeUnknown = providerOutcomeUnknown;
        this.retrySafe = retrySafe;
        this.unverifiedReply = unverifiedReply;
        this.rejectedClaimAuditJson = rejectedClaimAuditJson;
    }

    public boolean hasSearchEvidence() {
        return !searchQueries.isEmpty() || !searchResults.isEmpty();
    }

    public boolean blocksAutomaticRetry() {
        return providerOutcomeUnknown || Boolean.FALSE.equals(retrySafe);
    }
}
