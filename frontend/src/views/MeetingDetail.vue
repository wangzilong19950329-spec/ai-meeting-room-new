<template>
  <div class="meeting-detail" v-loading="loading">
    <template v-if="meeting">

      <!-- 顶部信息栏 -->
      <el-card shadow="never" class="header-card">
        <div class="meeting-header">
          <div class="header-left">
            <div class="header-title">
              <el-tag :type="statusType(meeting.status)" effect="dark" round style="margin-right:8px;flex-shrink:0">
                {{ meeting.statusName }}
              </el-tag>
              <h2>{{ meeting.title }}</h2>
            </div>
            <p v-if="meeting.description" class="header-desc">{{ meeting.description }}</p>
            <div v-if="meeting.projectKey" class="project-chip-row">
              <el-tag round effect="plain" type="info">项目：{{ meeting.projectKey }}</el-tag>
            </div>
            <div class="header-meta">
              <span><el-icon><User /></el-icon> {{ meeting.members?.length || 0 }} 位成员</span>
              <span><el-icon><Refresh /></el-icon> 第 {{ meeting.currentRound || 0 }} / {{ meeting.totalRounds || 0 }} 轮</span>
              <span v-if="[1, 6].includes(meeting.status)" class="live-indicator">
                <i class="live-dot"></i> 讨论中 · {{ meeting.opinions?.length || 0 }} 条消息
              </span>
            </div>
          </div>
          <div class="header-actions">
            <el-button v-if="[0, 5].includes(meeting.status)" type="primary" size="small" @click="handleStart">
              {{ meeting.status === 5 ? '继续讨论' : '启动讨论' }}
            </el-button>
            <template v-if="[1, 6].includes(meeting.status)">
              <el-button type="warning" size="small" :loading="terminating" @click="handleTerminate">
                提交结论
              </el-button>
              <el-button type="danger" plain size="small" :loading="cancelling" @click="handleCancel">
                暂停
              </el-button>
            </template>
            <template v-if="meeting.status === 2">
              <el-button type="success" size="small" @click="handleVerify(true)">✓ 结论有效</el-button>
              <el-button type="danger" plain size="small" @click="handleVerify(false)">✗ 结论无效</el-button>
            </template>
            <el-button v-if="[2,3,4,5].includes(meeting.status)" type="primary" plain size="small" @click="handleRestart">
              重启会议
            </el-button>
          </div>
        </div>

        <div v-if="[1, 6].includes(meeting.status)" class="progress-wrap">
          <el-progress :percentage="progressPct" :stroke-width="5" striped striped-flow status="striped" />
        </div>

        <el-alert v-if="meeting.finalConclusion"
          :title="'最终结论：' + meeting.finalConclusion"
          type="success" :closable="false" show-icon style="margin-top:10px" />
      </el-card>

      <el-row :gutter="16">

        <!-- 左：聊天室主体 -->
        <el-col :xs="24" :md="17">
          <el-card shadow="never" class="chat-card">
            <template #header>
              <div class="chat-card-header">
                <span>
                  💬 讨论过程
                  <el-badge v-if="[1, 6].includes(meeting.status)" value="LIVE" type="danger" style="margin-left:6px" />
                </span>
                <div style="display:flex;gap:8px;align-items:center">
                  <el-select v-model="filterRound" placeholder="全部轮次" clearable size="small" style="width:100px">
                    <el-option v-for="r in rounds" :key="r" :label="`第 ${r} 轮`" :value="r" />
                  </el-select>
                  <el-tooltip content="跳到最新" placement="top">
                    <el-button :icon="ArrowDown" circle size="small" @click="scrollToBottom" />
                  </el-tooltip>
                </div>
              </div>
            </template>

            <!-- 聊天消息区 -->
            <div class="chat-body" ref="chatRef">

              <!-- 等待开始 -->
              <div v-if="[1, 6].includes(meeting.status) && !filteredOpinions.length" class="thinking-placeholder">
                <div class="dots"><span></span><span></span><span></span></div>
                <p>AI 成员正在思考，即将发言…</p>
              </div>

              <template v-if="filteredOpinions.length">
                <template v-for="(group, round) in groupedOpinions" :key="round">
                  <!-- 轮次分隔 -->
                  <div class="round-sep">
                    <span>第 {{ round }} 轮</span>
                  </div>
                  <!-- 轮次摘要 -->
                  <div v-if="roundSummaryMap[round]" class="round-summary">
                    <el-icon style="color:#909399;font-size:11px"><Document /></el-icon>
                    {{ roundSummaryMap[round] }}
                  </div>

                  <transition-group name="bubble" tag="div">
                    <div v-for="op in group" :key="op.id"
                         :id="`opinion-${op.id}`"
                         class="chat-msg"
                         :class="{
                           'is-new': newIds.has(op.id),
                           'is-human': [3, 5].includes(op.opinionType),
                           'is-focused': focusedOpinionId === op.id
                         }">

                      <!-- 头像 -->
                      <div class="chat-avatar"
                           :style="{ background: [3, 5].includes(op.opinionType) ? '#10b981' : memberColor(op.memberId) }"
                           :title="op.memberName">
                        {{ [3, 5].includes(op.opinionType) ? '我' : op.memberName?.charAt(0) }}
                      </div>

                      <div class="chat-content">
                        <!-- 发言人名称 + 类型标签 -->
                        <div class="chat-name" :style="{ color: [3, 5].includes(op.opinionType) ? '#10b981' : memberColor(op.memberId) }">
                          {{ op.memberName }}
                          <el-tag v-if="op.opinionType === 1" size="small" type="danger"   effect="plain" style="margin-left:4px">反驳</el-tag>
                          <el-tag v-if="op.opinionType === 2" size="small" type="success"  effect="plain" style="margin-left:4px">认同</el-tag>
                          <el-tag v-if="op.opinionType === 3" size="small" type="info"     effect="dark"  style="margin-left:4px">你</el-tag>
                          <el-tag v-if="op.opinionType === 4" size="small" type="warning"  effect="dark"  style="margin-left:4px">主持指令</el-tag>
                          <el-tag v-if="op.opinionType === 5" size="small" type="warning"  effect="plain" style="margin-left:4px">定向追问</el-tag>
                          <el-tag v-if="op.opinionType === 5 && op.targetMemberName" size="small" type="danger" effect="plain" style="margin-left:4px">
                            指向 {{ op.targetMemberName }}
                          </el-tag>
                          <el-tag v-if="op.responseToTargetedQuestion" size="small" type="success" effect="plain" style="margin-left:4px">
                            回应追问
                          </el-tag>
                        </div>
                        <div v-if="op.replyToLabel" class="chat-reply-label">{{ op.replyToLabel }}</div>

                        <!-- 搜索证据卡（仅 AI 消息且有 evidence） -->
                        <div v-if="![3, 4, 5].includes(op.opinionType) && op.evidence && hasEvidence(op.evidence)" class="evidence-card">
                          <div class="evidence-header">
                            <span>🔍 {{ parsedEvidence(op.evidence).realResults?.length ? '联网搜索依据' : '引用论据' }}</span>
                          </div>
                          <div class="evidence-body">
                            <div v-if="parsedEvidence(op.evidence).searchMeta?.note" class="evidence-note">
                              {{ parsedEvidence(op.evidence).searchMeta.note }}
                            </div>
                            <div v-if="parsedEvidence(op.evidence).searchMeta" class="evidence-queries">
                              <span class="evidence-label">检索状态：</span>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.mode" size="small" type="primary" effect="plain" style="margin:0 4px 4px 0">
                                {{ parsedEvidence(op.evidence).searchMeta.mode }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.status" size="small" type="info" effect="plain" style="margin:0 4px 4px 0">
                                {{ parsedEvidence(op.evidence).searchMeta.status }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.modelStatus" size="small" type="danger" effect="plain" style="margin:0 4px 4px 0">
                                {{ parsedEvidence(op.evidence).searchMeta.modelStatus }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.queryCount != null" size="small" type="warning" effect="plain" style="margin:0 4px 4px 0">
                                查询 {{ parsedEvidence(op.evidence).searchMeta.queryCount }} 次
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.providerResultCount != null" size="small" type="success" effect="plain" style="margin:0 4px 4px 0">
                                网页 {{ parsedEvidence(op.evidence).searchMeta.providerResultCount }} 条
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.providerTimingMs != null" size="small" type="danger" effect="plain" style="margin:0 4px 4px 0">
                                Provider {{ parsedEvidence(op.evidence).searchMeta.providerTimingMs }}ms
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.bridgeRoundTripMs != null" size="small" type="danger" effect="plain" style="margin:0 4px 4px 0">
                                Bridge {{ parsedEvidence(op.evidence).searchMeta.bridgeRoundTripMs }}ms
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.contentStatus" size="small" type="warning" effect="plain" style="margin:0 4px 4px 0">
                                {{ parsedEvidence(op.evidence).searchMeta.contentStatus }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.answerSource" size="small" type="info" effect="plain" style="margin:0 4px 4px 0">
                                {{ parsedEvidence(op.evidence).searchMeta.answerSource }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.waitDurationMs != null" size="small" type="danger" effect="plain" style="margin:0 4px 4px 0">
                                Wait {{ parsedEvidence(op.evidence).searchMeta.waitDurationMs }}ms
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.completionReason" size="small" type="info" effect="plain" style="margin:0 4px 4px 0">
                                {{ parsedEvidence(op.evidence).searchMeta.completionReason }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.answerLength != null" size="small" type="info" effect="plain" style="margin:0 4px 4px 0">
                                字数 {{ parsedEvidence(op.evidence).searchMeta.answerLength }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.newLinkCount != null" size="small" type="success" effect="plain" style="margin:0 4px 4px 0">
                                新链接 {{ parsedEvidence(op.evidence).searchMeta.newLinkCount }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.assistantBlockDeltaCount != null" size="small" type="success" effect="plain" style="margin:0 4px 4px 0">
                                新回答块 {{ parsedEvidence(op.evidence).searchMeta.assistantBlockDeltaCount }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.bodyDeltaLength != null" size="small" type="warning" effect="plain" style="margin:0 4px 4px 0">
                                增量 {{ parsedEvidence(op.evidence).searchMeta.bodyDeltaLength }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.expertMode === true" size="small" type="success" effect="plain" style="margin:0 4px 4px 0">
                                专家模式
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.deepThinkEnabled === true" size="small" type="success" effect="plain" style="margin:0 4px 4px 0">
                                深度思考
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchMeta.searchEnabled === true" size="small" type="success" effect="plain" style="margin:0 4px 4px 0">
                                智能搜索
                              </el-tag>
                            </div>
                            <div v-if="parsedEvidence(op.evidence).searchMeta?.contentNote" class="evidence-note">
                              {{ parsedEvidence(op.evidence).searchMeta.contentNote }}
                            </div>
                            <div v-if="parsedEvidence(op.evidence).searchMeta?.modelNote" class="evidence-note">
                              {{ parsedEvidence(op.evidence).searchMeta.modelNote }}
                            </div>
                            <div v-if="parsedEvidence(op.evidence).searchMeta?.providerDiagnostics?.length" class="evidence-items" style="margin-top:6px">
                              <div class="evidence-label" style="margin-bottom:4px">🔎 Provider 诊断：</div>
                              <div
                                v-for="(d, i) in parsedEvidence(op.evidence).searchMeta.providerDiagnostics"
                                :key="'pd'+i"
                                class="evidence-item"
                              >
                                <span class="evidence-point">{{ d }}</span>
                              </div>
                            </div>
                            <div v-if="parsedEvidence(op.evidence).searchPlan" class="evidence-plan">
                              <span class="evidence-label">检索规划：</span>
                              <el-tag v-if="parsedEvidence(op.evidence).searchPlan.intent" size="small" type="warning" effect="plain" style="margin-right:4px">
                                {{ parsedEvidence(op.evidence).searchPlan.intent }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchPlan.targetYear" size="small" type="success" effect="plain" style="margin-right:4px">
                                目标年份 {{ parsedEvidence(op.evidence).searchPlan.targetYear }}
                              </el-tag>
                              <el-tag v-if="parsedEvidence(op.evidence).searchPlan.freshnessDays" size="small" type="info" effect="plain">
                                新近性 {{ parsedEvidence(op.evidence).searchPlan.freshnessDays }} 天
                              </el-tag>
                            </div>
                            <!-- 搜索词 -->
                            <div v-if="parsedEvidence(op.evidence).queries?.length" class="evidence-queries">
                              <span class="evidence-label">搜索词：</span>
                              <el-tag
                                v-for="q in parsedEvidence(op.evidence).queries"
                                :key="q" size="small" type="info" effect="plain"
                                style="margin:0 4px 4px 0">
                                {{ q }}
                              </el-tag>
                            </div>
                            <!-- AI 论据 -->
                            <div v-if="parsedEvidence(op.evidence).evidence?.length" class="evidence-items">
                              <div v-for="(e, i) in parsedEvidence(op.evidence).evidence" :key="i" class="evidence-item">
                                <span class="evidence-source">
                                  <template v-if="e.url || e.link">
                                    <a :href="e.url || e.link" target="_blank" rel="noopener" class="evidence-link">{{ e.s || e.source }} <el-icon size="10"><ArrowRight /></el-icon></a>
                                  </template>
                                  <template v-else>{{ e.s || e.source }}</template>
                                </span>
                                <span class="evidence-point">{{ e.p || e.keyPoint }}</span>
                              </div>
                            </div>
                            <!-- 真实搜索结果 -->
                            <div v-if="parsedEvidence(op.evidence).realResults?.length" class="evidence-items" style="margin-top:6px">
                              <div class="evidence-label" style="margin-bottom:4px">🌐 实时搜索结果：</div>
                              <div v-for="(r, i) in parsedEvidence(op.evidence).realResults" :key="'r'+i" class="evidence-item">
                                <span class="evidence-source">
                                  <template v-if="r.url">
                                    <a :href="r.url" target="_blank" rel="noopener" class="evidence-link">{{ r.source }} <el-icon size="10"><ArrowRight /></el-icon></a>
                                  </template>
                                  <template v-else>{{ r.source }}</template>
                                </span>
                                <span class="evidence-point">{{ r.snippet }}</span>
                              </div>
                            </div>
                            <div v-if="parsedEvidence(op.evidence).validation?.note" class="evidence-note">
                              {{ parsedEvidence(op.evidence).validation.note }}
                            </div>
                            <div v-if="parsedEvidence(op.evidence).conflictValidation?.note" class="evidence-note evidence-note-danger">
                              {{ parsedEvidence(op.evidence).conflictValidation.note }}
                            </div>
                            <div v-if="parsedEvidence(op.evidence).conflictValidation?.conflicts?.length" class="evidence-items" style="margin-top:6px">
                              <div class="evidence-label" style="margin-bottom:4px">⚠️ 自动识别到的冲突：</div>
                              <div
                                v-for="(c, i) in parsedEvidence(op.evidence).conflictValidation.conflicts"
                                :key="'c'+i"
                                class="evidence-item"
                              >
                                <span class="evidence-source">{{ c.label }}</span>
                                <span class="evidence-point">
                                  本轮 {{ c.currentValue }}；{{ c.previousSpeaker || '前序议员' }} 曾引用 {{ c.previousValue }}
                                </span>
                              </div>
                            </div>
                          </div>
                        </div>

                        <!-- 消息气泡 -->
                        <div class="chat-bubble"
                             :style="{ '--bubble-color': op.opinionType === 3 ? '#d1fae5' : memberColorLight(op.memberId) }">
                          <span>{{ op.content }}</span>
                        </div>
                        <div class="chat-time">{{ formatTime(op.gmtCreate) }}</div>
                      </div>
                    </div>
                  </transition-group>
                </template>
              </template>

              <el-empty v-else-if="![1, 6].includes(meeting.status)" description="暂无讨论记录" :image-size="80" />
            </div>

            <!-- 用户输入框 -->
            <div class="input-area">
              <el-input
                v-model="humanInput"
                :placeholder="inputPlaceholder"
                :disabled="sending"
                @keydown.enter.exact.prevent="sendHumanMessage"
                clearable
              >
                <template #prepend>
                  <div class="input-prepend-wrap">
                    <el-select v-model="humanMode" style="width:110px">
                      <el-option label="讨论补充" value="message" />
                      <el-option label="主持人模式" value="directive" />
                    </el-select>
                    <el-select
                      v-if="humanMode === 'message'"
                      v-model="targetMemberId"
                      clearable
                      placeholder="点名议员"
                      style="width:140px"
                    >
                      <el-option
                        v-for="member in targetableMembers"
                        :key="member.id"
                        :label="member.memberName"
                        :value="member.id"
                      />
                    </el-select>
                  </div>
                </template>
                <template #append>
                  <el-button
                    type="primary"
                    :loading="sending"
                    :disabled="!humanInput.trim()"
                    @click="sendHumanMessage"
                    style="border-radius:0 6px 6px 0">
                    {{ sendButtonText }}
                  </el-button>
                </template>
              </el-input>
              <div class="input-hint">{{ inputHint }}</div>
            </div>
          </el-card>
        </el-col>

        <!-- 右侧：成员 + 贡献 + 总结 -->
        <el-col :xs="24" :md="7">
          <el-card shadow="never" class="members-card">
            <template #header><span>👥 参与成员</span></template>
            <div v-for="(m, idx) in meeting.members" :key="idx" class="member-item">
              <div class="member-avatar" :style="{ background: memberColorByIdx(idx) }">
                {{ m.memberName?.charAt(0) }}
              </div>
              <div class="member-info">
                <div class="member-name">{{ m.memberName }}</div>
                <div style="display:flex;gap:4px;flex-wrap:wrap;margin-top:2px">
                  <el-tag size="small" effect="plain">{{ m.modelType }}</el-tag>
                  <el-tag size="small" :type="m.role === 1 ? 'warning' : 'info'">
                    {{ m.role === 1 ? '主持人' : '参与者' }}
                  </el-tag>
                </div>
                <div v-if="[1, 6].includes(meeting.status)" class="speech-count">
                  已发言 {{ memberSpeechCount(m.id) }} 次
                </div>
              </div>
            </div>
          </el-card>

          <el-card v-if="targetedThreads.length" shadow="never" style="margin-top:12px">
            <template #header><span>🎯 定向追问</span></template>
            <div class="targeted-thread-list">
              <div v-for="thread in targetedThreads" :key="thread.question.id" class="targeted-thread-item">
                <div class="targeted-thread-head">
                  <el-tag size="small" type="warning" effect="plain">指向 {{ thread.question.targetMemberName }}</el-tag>
                  <span class="targeted-thread-time">{{ formatDateTime(thread.question.gmtCreate) }}</span>
                  <el-button link type="primary" size="small" @click="jumpToOpinion(thread.question.id)">定位问题</el-button>
                </div>
                <div class="targeted-thread-question">{{ thread.question.content }}</div>
                <div v-if="thread.reply" class="targeted-thread-reply">
                  <div class="targeted-thread-reply-head">
                    <el-tag size="small" type="success" effect="plain">已回应</el-tag>
                    <span>{{ thread.reply.memberName }}</span>
                    <span class="targeted-thread-time">{{ formatDateTime(thread.reply.gmtCreate) }}</span>
                    <el-button link type="primary" size="small" @click="jumpToOpinion(thread.reply.id)">定位回复</el-button>
                  </div>
                  <div class="targeted-thread-reply-content">{{ thread.reply.content }}</div>
                </div>
                <div v-else class="targeted-thread-pending">该议员尚未给出单独回应</div>
              </div>
            </div>
          </el-card>

          <el-card v-if="meeting.projectContext || meeting.inheritedContext" shadow="never" style="margin-top:12px">
            <template #header><span>🗂 项目记忆</span></template>
            <div v-if="meeting.projectContext" class="memory-section">
              <div class="memory-title">当前项目背景</div>
              <div class="summary-text">{{ meeting.projectContext }}</div>
            </div>
            <div v-if="meeting.inheritedContext" class="memory-section">
              <div class="memory-title">继承的历史结论</div>
              <div class="summary-text pre-wrap">{{ meeting.inheritedContext }}</div>
            </div>
          </el-card>

          <el-card v-if="workflowSummary" shadow="never" style="margin-top:12px">
            <template #header><span>🧭 会议流程</span></template>
            <div class="workflow-overview">
              <div class="workflow-line"><span>模板</span><b>{{ workflowSummary.templateName }}</b></div>
              <div class="workflow-line"><span>运行状态</span><el-tag size="small" effect="plain">{{ workflowSummary.runStatusName }}</el-tag></div>
              <div class="workflow-line"><span>当前阶段</span><b>{{ workflowSummary.currentStageName || '-' }}</b></div>
              <div v-if="workflowSummary.currentActorBindingMode === 'BY_SLOT' && workflowSummary.currentActorSlotKey" class="workflow-line"><span>当前槽位</span><b>{{ workflowSummary.currentActorSlotKey }}</b></div>
              <div v-if="workflowSummary.currentActorCandidates?.length" class="workflow-line"><span>可用执行人</span><b>{{ workflowSummary.currentActorCandidates.join('、') }}</b></div>
              <div v-if="workflowSummary.currentStageStatusName" class="workflow-line"><span>阶段状态</span><el-tag size="small" type="warning" effect="plain">{{ workflowSummary.currentStageStatusName }}</el-tag></div>
              <div v-if="workflowSummary.currentSearchStatus || workflowSummary.currentSearchNote" class="workflow-line workflow-line-search">
                <span>联网搜索</span>
                <div class="workflow-line-search-content">
                  <el-tag
                    size="small"
                    :type="workflowSearchStatusType(workflowSummary.currentSearchStatus, workflowSummary.currentSearchDegraded)"
                    effect="plain"
                  >
                    {{ workflowSearchStatusLabel(workflowSummary.currentSearchStatus, workflowSummary.currentSearchAttempted) }}
                  </el-tag>
                  <el-tag v-if="workflowSummary.currentSearchProvider" size="small" type="primary" effect="plain">
                    {{ workflowSummary.currentSearchProvider }}
                  </el-tag>
                  <el-tag v-if="workflowSummary.currentSearchQueryCount != null" size="small" type="warning" effect="plain">
                    查询 {{ workflowSummary.currentSearchQueryCount }}
                  </el-tag>
                  <el-tag v-if="workflowSummary.currentSearchResultCount != null" size="small" type="success" effect="plain">
                    结果 {{ workflowSummary.currentSearchResultCount }}
                  </el-tag>
                </div>
              </div>
              <div v-if="workflowSummary.currentSearchNote" class="workflow-blocked" :class="{ 'workflow-search-warning': workflowSummary.currentSearchDegraded }">
                {{ workflowSummary.currentSearchNote }}
              </div>
              <div v-if="workflowSummary.currentSearchDiagnostics?.length" class="workflow-search-diagnostics">
                {{ workflowSummary.currentSearchDiagnostics.join('；') }}
              </div>
              <div v-if="workflowSummary.blockedReason" class="workflow-blocked">{{ workflowSummary.blockedReason }}</div>
              <div class="workflow-line">
                <span>查看详情</span>
                <el-button
                  v-if="workflowNodes.length"
                  link
                  type="primary"
                  @click="openWorkflowNode(workflowNodes.find(node => node.stageKey === workflowSummary.currentStageKey) || workflowNodes[0])"
                >
                  打开当前节点时间线
                </el-button>
              </div>
            </div>
            <div v-if="workflowNodes.length" class="workflow-map">
              <div
                v-for="(node, idx) in workflowNodes"
                :key="node.stageKey"
                class="workflow-node"
                :class="{
                  'is-active': node.activeNode,
                  'is-blocked': node.latestStatus === 'BLOCKED',
                  'is-done': node.latestStatus === 'COMPLETED'
                }"
                @click="openWorkflowNode(node)"
              >
                <div class="workflow-node-rail" v-if="idx < workflowNodes.length - 1">
                  <span class="workflow-node-rail-line"></span>
                </div>
                <div class="workflow-node-dot"></div>
                <div class="workflow-node-card">
                  <div class="workflow-node-head">
                    <span class="workflow-node-order">#{{ node.stageOrder }}</span>
                    <span class="workflow-node-name">{{ node.stageName }}</span>
                    <el-tag size="small" :type="workflowNodeStatusType(node.latestStatus)" effect="plain">{{ node.latestStatusName || node.latestStatus }}</el-tag>
                  </div>
                  <div class="workflow-node-meta">
                    <span>{{ node.stageType }}</span>
                    <span>{{ node.actorType }}</span>
                    <span v-if="node.actorBindingMode === 'BY_SLOT' && node.actorSlotKey">槽位 · {{ node.actorSlotKey }}</span>
                    <span>{{ node.searchStrategyLabel }}</span>
                  </div>
                  <div v-if="node.searchStatus || node.searchNote" class="workflow-node-search">
                    <el-tag
                      size="small"
                      :type="workflowSearchStatusType(node.searchStatus, node.searchDegraded)"
                      effect="plain"
                    >
                      {{ workflowSearchStatusLabel(node.searchStatus, node.searchAttempted) }}
                    </el-tag>
                    <span v-if="node.searchProvider">{{ node.searchProvider }}</span>
                    <span v-if="node.searchResultCount != null">结果 {{ node.searchResultCount }}</span>
                  </div>
                  <div class="workflow-node-foot">
                    <span>{{ node.attempts?.length || 0 }} 次执行</span>
                    <span v-if="node.passTarget">通过 → {{ workflowTransitionLabel(node.passTarget) }}</span>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="showWorkflowActionPanel" class="workflow-action-panel">
              <div class="memory-title">当前阶段操作</div>
              <el-input
                v-if="canSubmitWorkflowStage || canReviewWorkflowStage"
                v-model="workflowActionInput"
                type="textarea"
                :rows="4"
                :placeholder="workflowActionPlaceholder"
              />
              <el-select
                v-if="(canSubmitWorkflowStage || canReviewWorkflowStage) && workflowSummary.currentActorCandidates?.length"
                v-model="workflowActorRef"
                clearable
                filterable
                placeholder="选择本阶段执行人"
                style="margin-top:10px;width:100%"
              >
                <el-option v-for="item in workflowSummary.currentActorCandidates" :key="item" :label="item" :value="item" />
              </el-select>
              <div class="workflow-action-buttons">
                <el-button
                  v-if="canRetryWorkflowStage"
                  type="warning"
                  plain
                  :loading="workflowActionLoading"
                  @click="retryWorkflowAutoStage"
                >
                  重试自动执行
                </el-button>
                <el-button
                  v-if="canSubmitWorkflowStage"
                  type="primary"
                  :loading="workflowActionLoading"
                  :disabled="!workflowActionInput.trim()"
                  @click="submitWorkflowStage"
                >
                  {{ workflowSummary.currentStageGuide?.submitLabel || '提交当前阶段' }}
                </el-button>
                <template v-if="canReviewWorkflowStage">
                  <el-button
                    type="success"
                    :loading="workflowActionLoading"
                    @click="reviewWorkflowStage(true)"
                  >
                    {{ workflowSummary.currentStageGuide?.reviewPassLabel || '审核通过' }}
                  </el-button>
                  <el-button
                    type="danger"
                    plain
                    :loading="workflowActionLoading"
                    @click="reviewWorkflowStage(false)"
                  >
                    {{ workflowSummary.currentStageGuide?.reviewRejectLabel || '打回修改' }}
                  </el-button>
                </template>
              </div>
              <div class="workflow-action-hint">
                <template v-if="canReviewWorkflowStage">
                  {{ workflowSummary.currentStageGuide?.outputExpectation || '当前是审核节点。你可以开启人工审核，或者在模板里关闭审核开关让它自动透传。' }}
                </template>
                <template v-else>
                  {{ workflowSummary.currentStageGuide?.outputExpectation || '当前是手工阶段提交节点，提交后会按照模板流转到下一阶段。' }}
                </template>
                <template v-if="workflowSummary.currentStageStatus === 'BLOCKED'">
                  <br>当前节点已阻塞。你可以先重试自动执行；如果仍不稳定，也可以人工接管并直接补交该节点产出。
                </template>
              </div>
            </div>
          </el-card>

          <el-drawer
            v-model="workflowDrawerVisible"
            :title="selectedWorkflowNode ? `${selectedWorkflowNode.stageName} · 节点时间线` : '节点时间线'"
            size="min(720px, 92vw)"
          >
            <template v-if="selectedWorkflowNode">
              <div class="workflow-drawer-meta">
                <el-tag size="small" effect="plain">{{ selectedWorkflowNode.stageType }}</el-tag>
                <el-tag size="small" effect="plain">{{ selectedWorkflowNode.actorType }}</el-tag>
                <el-tag v-if="selectedWorkflowNode.actorSlotKey" size="small" type="info" effect="plain">槽位 · {{ selectedWorkflowNode.actorSlotKey }}</el-tag>
                <el-tag size="small" type="warning" effect="plain">{{ selectedWorkflowNode.searchStrategyLabel }}</el-tag>
                <el-tag size="small" :type="workflowNodeStatusType(selectedWorkflowNode.latestStatus)" effect="plain">{{ selectedWorkflowNode.latestStatusName }}</el-tag>
                <el-tag
                  v-if="selectedWorkflowNode.searchStatus || selectedWorkflowNode.searchNote"
                  size="small"
                  :type="workflowSearchStatusType(selectedWorkflowNode.searchStatus, selectedWorkflowNode.searchDegraded)"
                  effect="plain"
                >
                  {{ workflowSearchStatusLabel(selectedWorkflowNode.searchStatus, selectedWorkflowNode.searchAttempted) }}
                </el-tag>
                <el-tag v-if="selectedWorkflowNode.searchProvider" size="small" type="primary" effect="plain">{{ selectedWorkflowNode.searchProvider }}</el-tag>
              </div>
              <div v-if="selectedWorkflowNode.searchNote" class="workflow-blocked" :class="{ 'workflow-search-warning': selectedWorkflowNode.searchDegraded }">
                {{ selectedWorkflowNode.searchNote }}
              </div>
              <div v-if="selectedWorkflowNode.searchDiagnostics?.length" class="workflow-search-diagnostics">
                {{ selectedWorkflowNode.searchDiagnostics.join('；') }}
              </div>
              <div v-if="selectedWorkflowNode.skillKey" class="workflow-line"><span>Skill</span><b>{{ skillLabel(selectedWorkflowNode.skillKey) }}</b></div>
              <div v-if="selectedWorkflowNode.skillPrompt" class="workflow-blocked workflow-skill-prompt">Skill 指令：{{ selectedWorkflowNode.skillPrompt }}</div>
              <div v-if="selectedWorkflowNode.executionGuide?.checkpoints?.length" class="workflow-guide-list">
                <div class="workflow-guide-title">{{ selectedWorkflowNode.executionGuide.actionTitle }}</div>
                <ul>
                  <li v-for="item in selectedWorkflowNode.executionGuide.checkpoints" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div v-if="selectedWorkflowNode.skillFiles?.length" class="workflow-file-list">
                <div class="workflow-guide-title">Skill 文件</div>
                <div v-for="file in selectedWorkflowNode.skillFiles" :key="`drawer-skill-${file.id}`" class="workflow-file-item">
                  <span>{{ file.fileName }}</span>
                  <el-button link @click="downloadSkillFile(file.id)">下载</el-button>
                </div>
              </div>

              <div class="workflow-attempt-list">
                <div
                  v-for="stage in selectedWorkflowNode.attempts"
                  :key="`${stage.stageKey}-${stage.attemptNo}`"
                  :id="`stage-run-${stage.stageRunId}`"
                  class="workflow-stage-item"
                  :class="{ 'is-focused': focusedStageRunId === stage.stageRunId }"
                >
                  <div class="workflow-stage-main">
                    <span class="workflow-stage-order">{{ workflowAttemptTitle(stage) }}</span>
                    <el-tag size="small" :type="workflowNodeStatusType(stage.status)" effect="plain">{{ stage.statusName }}</el-tag>
                    <el-tag v-if="stage.autoStage" size="small" type="warning" effect="plain">自动节点</el-tag>
                  </div>
                  <div class="workflow-stage-meta">
                    <span v-if="stage.startedAt">开始 {{ formatDateTime(stage.startedAt) }}</span>
                    <span v-if="stage.completedAt">完成 {{ formatDateTime(stage.completedAt) }}</span>
                    <span v-if="stage.outcome">结果：{{ stage.outcome }}</span>
                  </div>
                  <div v-if="stage.handoffArtifact || stage.handoffSummary" class="workflow-stage-handoff">
                    <div class="workflow-handoff-title">节点输入 / 上游交付</div>
                    <div v-if="stage.handoffArtifact?.sourceStageName" class="workflow-handoff-meta">
                      来自 {{ stage.handoffArtifact.sourceStageName }}
                      <template v-if="stage.handoffArtifact.sourceAttemptNo"> · 第 {{ stage.handoffArtifact.sourceAttemptNo }} 次</template>
                      <template v-if="stage.handoffArtifact.createdAt"> · {{ formatDateTime(stage.handoffArtifact.createdAt) }}</template>
                    </div>
                    <div v-if="stage.handoffArtifact?.artifact" class="workflow-artifact-card">
                      <div class="workflow-artifact-summary">{{ stage.handoffArtifact.artifact.summary }}</div>
                      <ul v-if="stage.handoffArtifact.artifact.highlights?.length" class="workflow-artifact-list">
                        <li v-for="item in stage.handoffArtifact.artifact.highlights" :key="item">{{ item }}</li>
                      </ul>
                      <div v-if="stage.handoffArtifact.artifact.detailText" class="workflow-artifact-detail">{{ stage.handoffArtifact.artifact.detailText }}</div>
                    </div>
                    <div v-else class="workflow-handoff-content">{{ stage.handoffArtifact?.content || stage.handoffSummary }}</div>
                  </div>
                  <div v-if="stage.rejectReason" class="workflow-stage-reject">打回原因：{{ stage.rejectReason }}</div>
                  <div v-if="stage.submissions?.length" class="workflow-submission-list">
                    <div class="workflow-submission-title">节点处理记录</div>
                    <div
                      v-for="submission in stage.submissions"
                      :key="submission.id"
                      class="workflow-submission-item"
                    >
                      <div class="workflow-submission-head">
                        <el-tag size="small" :type="submissionTagType(submission.actionType)" effect="plain">
                          {{ submissionActionLabel(submission.actionType) }}
                        </el-tag>
                        <el-tag
                          v-if="submission.executionSourceLabel"
                          size="small"
                          :type="submission.executionSource === 'AUTO' || submission.executionSource === 'AUTO_MERGED' ? 'warning' : 'info'"
                          effect="plain"
                        >
                          {{ submission.executionSourceLabel }}
                        </el-tag>
                        <el-tag v-if="submission.mergedOutput" size="small" type="success" effect="plain">已汇总</el-tag>
                        <span class="workflow-submission-actor">{{ submissionActorLabel(submission.actorRef) }}</span>
                        <span class="workflow-submission-time">{{ formatDateTime(submission.gmtCreate) }}</span>
                      </div>
                      <div v-if="submission.sourceActorRefs?.length" class="workflow-submission-sources">
                        来源成员：{{ submission.sourceActorRefs.join('、') }}
                      </div>
                      <div v-if="submission.searchTraces?.length" class="workflow-search-list">
                        <div class="workflow-submission-title" style="margin-bottom:8px">联网检索记录</div>
                        <div v-for="(trace, traceIdx) in submission.searchTraces" :key="`${submission.id}-trace-${traceIdx}`" class="workflow-search-card">
                          <div class="workflow-search-head">
                            <el-tag size="small" type="warning" effect="plain">{{ trace.strategyLabel }}</el-tag>
                            <el-tag v-if="trace.status" size="small" type="info" effect="plain">{{ trace.status }}</el-tag>
                            <el-tag v-if="trace.provider" size="small" type="primary" effect="plain">{{ trace.provider }}</el-tag>
                            <span v-if="trace.executedAt" class="workflow-submission-time">{{ formatDateTime(trace.executedAt) }}</span>
                          </div>
                          <div v-if="trace.note" class="workflow-search-note">{{ trace.note }}</div>
                          <div v-if="trace.contractNote || trace.contractSources?.length || trace.contractFields?.length" class="workflow-search-digest">
                            <div class="workflow-inline-label">搜索契约</div>
                            <div v-if="trace.contractStrict" class="workflow-search-note">严格模式：未满足要求时阻塞节点</div>
                            <div v-if="trace.contractNote">{{ trace.contractNote }}</div>
                            <div v-if="trace.contractSources?.length" class="workflow-search-queries">
                              <span class="workflow-inline-label">要求来源：</span>{{ trace.contractSources.join('；') }}
                            </div>
                            <div v-if="trace.contractFields?.length" class="workflow-search-queries">
                              <span class="workflow-inline-label">要求字段：</span>{{ trace.contractFields.join('；') }}
                            </div>
                          </div>
                          <div v-if="trace.agentPlanQueries?.length || trace.agentPlanSources?.length || trace.agentPlanFields?.length" class="workflow-search-digest">
                            <div class="workflow-inline-label">模型搜索计划</div>
                            <div v-if="trace.agentPlanNote" class="workflow-search-note">{{ trace.agentPlanNote }}</div>
                            <div v-if="trace.agentPlanQueries?.length" class="workflow-search-queries">
                              <span class="workflow-inline-label">计划搜索词：</span>{{ trace.agentPlanQueries.join('；') }}
                            </div>
                            <div v-if="trace.agentPlanSources?.length" class="workflow-search-queries">
                              <span class="workflow-inline-label">计划来源：</span>{{ trace.agentPlanSources.join('；') }}
                            </div>
                            <div v-if="trace.agentPlanFields?.length" class="workflow-search-queries">
                              <span class="workflow-inline-label">计划字段：</span>{{ trace.agentPlanFields.join('；') }}
                            </div>
                          </div>
                          <div v-if="submission.searchQueries?.length" class="workflow-search-queries">
                            <span class="workflow-inline-label">搜索词：</span>{{ submission.searchQueries.join('；') }}
                          </div>
                          <div v-if="trace.providerDiagnostics?.length" class="workflow-search-queries">
                            <span class="workflow-inline-label">诊断：</span>{{ trace.providerDiagnostics.join('；') }}
                          </div>
                          <div v-if="trace.digestSummary" class="workflow-search-digest">
                            <div class="workflow-inline-label">研究摘要</div>
                            <div>{{ trace.digestSummary }}</div>
                            <div v-if="trace.digestVerificationNote" class="workflow-search-note">{{ trace.digestVerificationNote }}</div>
                            <div v-if="trace.digestFreshnessNote" class="workflow-search-note">{{ trace.digestFreshnessNote }}</div>
                          </div>
                          <div v-if="trace.results?.length" class="workflow-search-results">
                            <div class="workflow-inline-label">搜索结果</div>
                            <div v-for="(result, resultIdx) in trace.results" :key="`${submission.id}-trace-${traceIdx}-result-${resultIdx}`" class="workflow-search-result">
                              <a v-if="result.url" :href="result.url" target="_blank" rel="noopener" class="evidence-link">{{ result.source }} <el-icon size="10"><ArrowRight /></el-icon></a>
                              <span v-else>{{ result.source }}</span>
                              <div class="workflow-search-result-snippet">{{ result.snippet }}</div>
                            </div>
                          </div>
                        </div>
                      </div>
                      <div v-if="submission.artifact" class="workflow-artifact-card">
                        <div class="workflow-artifact-summary">{{ submission.artifact.summary }}</div>
                        <ul v-if="submission.artifact.highlights?.length" class="workflow-artifact-list">
                          <li v-for="item in submission.artifact.highlights" :key="item">{{ item }}</li>
                        </ul>
                        <div v-if="submission.artifact.detailText" class="workflow-artifact-detail">{{ submission.artifact.detailText }}</div>
                        <div
                          v-if="stage.handoffArtifact?.actionType === 'REJECT' && stage.handoffArtifact?.content && submission.diffFromPrevious"
                          class="workflow-review-feedback"
                        >
                          <div class="workflow-review-feedback-title">对应审核意见</div>
                          <div class="workflow-review-feedback-content">{{ stage.handoffArtifact.content }}</div>
                          <div v-if="submission.diffFromPrevious.reviewAlignmentNote" class="workflow-review-feedback-note">
                            {{ submission.diffFromPrevious.reviewAlignmentNote }}
                          </div>
                        </div>
                        <div v-if="submission.diffFromPrevious" class="workflow-artifact-diff">
                          <div class="workflow-artifact-diff-title">相对上一次提交的变化</div>
                          <div v-if="submission.diffFromPrevious.changeNote" class="workflow-artifact-diff-note">
                            {{ submission.diffFromPrevious.changeNote }}
                          </div>
                          <div v-if="submission.diffFromPrevious.previousSummary || submission.diffFromPrevious.currentSummary" class="workflow-artifact-diff-summary">
                            <div v-if="submission.diffFromPrevious.previousSummary">上次摘要：{{ submission.diffFromPrevious.previousSummary }}</div>
                            <div v-if="submission.diffFromPrevious.currentSummary">本次摘要：{{ submission.diffFromPrevious.currentSummary }}</div>
                          </div>
                        </div>
                      </div>
                      <div v-else-if="submission.content" class="workflow-submission-content">{{ submission.content }}</div>
                    </div>
                  </div>
                </div>
              </div>
            </template>
          </el-drawer>

          <el-card v-if="trackingSummary" shadow="never" style="margin-top:12px">
            <template #header><span>📌 追踪项</span></template>
            <div class="tracking-summary-row">
              <el-tag size="small">总数 {{ trackingSummary.totalCount || 0 }}</el-tag>
              <el-tag size="small" type="warning">待处理 {{ trackingSummary.openCount || 0 }}</el-tag>
              <el-tag size="small" type="danger">阻塞 {{ trackingSummary.blockedCount || 0 }}</el-tag>
              <el-tag size="small" type="success">触发继续 {{ trackingSummary.triggerCount || 0 }}</el-tag>
            </div>
            <div v-if="trackingSummary.resumePolicy" class="tracking-policy-card">
              <div class="tracking-policy-head">
                <span class="tracking-policy-title">继续会议判定</span>
                <el-tag
                  size="small"
                  :type="trackingSummary.resumePolicy.canResumeMeeting ? 'success' : 'warning'"
                  effect="plain"
                >
                  {{ trackingSummary.resumePolicy.canResumeMeeting ? '可继续重开' : '暂不可重开' }}
                </el-tag>
              </div>
              <div class="tracking-policy-line">
                <span>必需项</span>
                <b>{{ trackingSummary.resumePolicy.allRequiredReady ? '已满足' : '未满足' }}</b>
              </div>
              <div class="tracking-policy-line">
                <span>触发项</span>
                <b>{{ trackingSummary.resumePolicy.anyTriggerReady ? '已有完成项' : '尚无完成项' }}</b>
              </div>
              <div v-if="trackingSummary.resumePolicy.evaluationNote" class="tracking-policy-note">
                {{ trackingSummary.resumePolicy.evaluationNote }}
              </div>
              <div v-if="trackingSummary.resumePolicy.pendingRequiredItems?.length" class="tracking-policy-list">
                <div class="tracking-policy-subtitle">待完成必需项</div>
                <ul>
                  <li v-for="item in trackingSummary.resumePolicy.pendingRequiredItems" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div v-if="trackingSummary.resumePolicy.readyTriggerItems?.length" class="tracking-policy-list">
                <div class="tracking-policy-subtitle">已满足触发项</div>
                <ul>
                  <li v-for="item in trackingSummary.resumePolicy.readyTriggerItems" :key="item">{{ item }}</li>
                </ul>
              </div>
            </div>
            <div v-if="trackingSummary.items?.length" class="tracking-list">
              <div
                v-for="item in trackingSummary.items"
                :id="`tracking-item-${item.id}`"
                :key="item.id"
                class="tracking-item"
                :class="{ 'is-focused': focusedTrackingItemId === item.id || focusedTrackingItemIds.has(item.id) }"
              >
                <div class="tracking-item-head">
                  <div class="tracking-item-title">{{ item.title }}</div>
                  <el-tag size="small" effect="plain">{{ item.statusName }}</el-tag>
                </div>
                <div v-if="item.description" class="tracking-item-desc">{{ item.description }}</div>
                <div v-if="item.latestUpdateSummary" class="tracking-latest-update">{{ item.latestUpdateSummary }}</div>
                <div v-if="item.reviewResolutionNote" class="tracking-review-resolution">
                  <div class="tracking-review-resolution-title">关联审核意见</div>
                  <div class="tracking-review-resolution-note">{{ item.reviewResolutionNote }}</div>
                  <div v-if="item.matchedReviewPoints?.length" class="tracking-review-resolution-list">
                    <span class="matched">已消解审核点</span>
                    <ul>
                      <li v-for="point in item.matchedReviewPoints" :key="`tracking-matched-${point}`">{{ point }}</li>
                    </ul>
                  </div>
                  <div v-if="item.unresolvedReviewPoints?.length" class="tracking-review-resolution-list">
                    <span class="unmatched">未消解审核点</span>
                    <ul>
                      <li v-for="point in item.unresolvedReviewPoints" :key="`tracking-unmatched-${point}`">{{ point }}</li>
                    </ul>
                  </div>
                </div>
                <div v-if="item.conclusionResolutionNote" class="tracking-conclusion-resolution">
                  <div class="tracking-conclusion-resolution-title">最新结论响应</div>
                  <div class="tracking-conclusion-resolution-note">{{ item.conclusionResolutionNote }}</div>
                  <div v-if="item.resolvedByLatestConclusion && latestConclusionVersion" class="tracking-resolution-actions">
                    <el-button
                      link
                      type="primary"
                      size="small"
                      @click="jumpToConclusionVersion(latestConclusionVersion.versionNo)"
                    >
                      定位最新结论
                    </el-button>
                  </div>
                  <div v-if="item.matchedConclusionPoints?.length" class="tracking-conclusion-resolution-list">
                    <span class="matched">命中的结论要点</span>
                    <ul>
                      <li v-for="point in item.matchedConclusionPoints" :key="`conclusion-${point}`">{{ point }}</li>
                    </ul>
                  </div>
                </div>
                <div class="tracking-item-flags">
                  <span v-if="item.sourceType">来源：{{ item.sourceType }}</span>
                  <span v-if="item.requiredForContinue">继续会议前必需</span>
                  <span v-if="item.reopenTrigger">完成后可触发重开</span>
                  <span v-if="item.reopenPolicy">重开策略：{{ item.reopenPolicy }}</span>
                  <span v-if="item.autoRefreshEnabled">自动刷新中</span>
                  <span v-if="item.refreshMode">刷新模式：{{ item.refreshMode }}{{ item.refreshIntervalHours ? `/${item.refreshIntervalHours}h` : '' }}</span>
                  <span v-if="item.changeSeverity">变化等级：<b :class="`severity-${String(item.changeSeverity).toLowerCase()}`">{{ item.changeSeverity }}</b></span>
                  <span v-if="item.lastCheckedAt">最近刷新：{{ formatDateTime(item.lastCheckedAt) }}</span>
                  <span v-if="item.nextCheckAt">下次刷新：{{ formatDateTime(item.nextCheckAt) }}</span>
                  <span v-if="item.ownerType">责任方：{{ item.ownerType }}{{ item.ownerRef ? `/${item.ownerRef}` : '' }}</span>
                  <span v-if="item.sourceStageName">
                    来源阶段：
                    <el-button
                      v-if="item.sourceStageRunId"
                      link
                      type="primary"
                      size="small"
                      @click="jumpToStageRun(item.sourceStageRunId)"
                    >
                      {{ item.sourceStageName }}
                    </el-button>
                    <template v-else>{{ item.sourceStageName }}</template>
                  </span>
                </div>
                <div v-if="item.lastCheckNote" class="tracking-refresh-note">{{ item.lastCheckNote }}</div>
                <div v-if="item.sourceHandoffArtifact?.content" class="tracking-source-artifact">
                  <div class="tracking-source-title">关联交付物</div>
                  <div v-if="item.sourceHandoffArtifact?.sourceStageName" class="tracking-source-meta">
                    来自 {{ item.sourceHandoffArtifact.sourceStageName }}
                    <template v-if="item.sourceHandoffArtifact.sourceAttemptNo"> · 第 {{ item.sourceHandoffArtifact.sourceAttemptNo }} 次</template>
                    <template v-if="item.sourceHandoffArtifact.actionType"> · {{ submissionActionLabel(item.sourceHandoffArtifact.actionType) }}</template>
                    <template v-if="item.sourceHandoffArtifact.actorRef"> · {{ submissionActorLabel(item.sourceHandoffArtifact.actorRef) }}</template>
                    <el-button
                      v-if="item.sourceHandoffArtifact.sourceStageRunId"
                      link
                      type="primary"
                      size="small"
                      @click="jumpToStageRun(item.sourceHandoffArtifact.sourceStageRunId)"
                    >
                      定位阶段
                    </el-button>
                  </div>
                  <div class="tracking-source-content">{{ item.sourceHandoffArtifact.content }}</div>
                </div>
                <div class="tracking-actions">
                  <el-button v-if="['OPEN','BLOCKED'].includes(item.status)" size="small" plain @click="updateTracking(item, 'IN_PROGRESS')">开始处理</el-button>
                  <el-button v-if="['OPEN','IN_PROGRESS','READY_FOR_REVIEW','BLOCKED'].includes(item.status)" size="small" type="success" plain @click="updateTracking(item, 'VERIFIED')">标记已验证</el-button>
                  <el-button v-if="!['CLOSED','VERIFIED'].includes(item.status)" size="small" type="info" plain @click="updateTracking(item, 'CLOSED')">关闭</el-button>
                  <el-button v-if="!['REJECTED','CLOSED'].includes(item.status)" size="small" type="danger" plain @click="updateTracking(item, 'REJECTED')">驳回</el-button>
                </div>
                <div v-if="item.todayUpdates?.length" class="tracking-history tracking-today-history">
                  <div class="tracking-history-title">今日追踪明细</div>
                  <div
                    v-for="update in item.todayUpdates"
                    :key="`today-${update.id}`"
                    class="tracking-history-item"
                  >
                    <div class="tracking-history-head">
                      <el-tag size="small" :type="trackingUpdateTagType(update)" effect="plain">
                        {{ trackingUpdateLabel(update) }}
                      </el-tag>
                      <span class="tracking-history-time">{{ formatDateTime(update.gmtCreate) }}</span>
                    </div>
                    <div v-if="update.content" class="tracking-history-content">{{ update.content }}</div>
                    <div v-if="trackingUpdateEvidenceText(update)" class="tracking-history-evidence">
                      证据：{{ trackingUpdateEvidenceText(update) }}
                    </div>
                  </div>
                </div>
                <div v-if="item.updates?.length" class="tracking-history">
                  <div class="tracking-history-title">处理记录</div>
                  <div
                    v-for="update in item.updates"
                    :id="`tracking-update-${update.id}`"
                    :key="update.id"
                    class="tracking-history-item"
                    :class="{ 'is-focused': focusedTrackingUpdateId === update.id }"
                  >
                    <div class="tracking-history-head">
                      <el-tag size="small" :type="trackingUpdateTagType(update)" effect="plain">
                        {{ trackingUpdateLabel(update) }}
                      </el-tag>
                      <span class="tracking-history-time">{{ formatDateTime(update.gmtCreate) }}</span>
                      <el-button
                        v-if="update.relatedConclusionVersionNo"
                        link
                        type="primary"
                        size="small"
                        @click="jumpToConclusionVersion(update.relatedConclusionVersionNo)"
                      >
                        定位重开版本
                      </el-button>
                      <el-button
                        v-if="trackingUpdateOpinionId(update)"
                        link
                        type="primary"
                        size="small"
                        @click="jumpToOpinion(trackingUpdateOpinionId(update))"
                      >
                        定位重开指令
                      </el-button>
                    </div>
                    <div v-if="update.content" class="tracking-history-content">{{ update.content }}</div>
                    <div v-if="trackingUpdateEvidenceText(update)" class="tracking-history-evidence">
                      证据：{{ trackingUpdateEvidenceText(update) }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <el-empty v-else description="暂无追踪项" :image-size="64" />
          </el-card>

          <el-card v-if="contributions.length" shadow="never" class="contrib-card" style="margin-top:12px">
            <template #header><span>📊 贡献度</span></template>
            <div v-for="c in contributions" :key="c.memberId" class="contrib-item">
              <div class="contrib-name">{{ c.memberName }}</div>
              <el-progress :percentage="Math.round(c.contributionWeight * 25)" :stroke-width="5"
                           :color="c.inConsensusCluster ? '#67c23a' : '#909399'" />
            </div>
          </el-card>

          <el-card v-if="meeting.summary" shadow="never" style="margin-top:12px">
            <template #header><span>📋 会议总结</span></template>
            <div class="summary-text">{{ meeting.summary }}</div>
          </el-card>

          <el-card v-if="decisionArtifact" shadow="never" style="margin-top:12px">
            <template #header><span>🧾 决策卡片</span></template>
            <div class="artifact-section">
              <div class="memory-title">议题概述</div>
              <div class="summary-text">{{ decisionArtifact.topicOverview || meeting.title }}</div>
            </div>
            <div v-if="decisionArtifact.finalDecision" class="artifact-section">
              <div class="memory-title">最终决策</div>
              <div class="summary-text">{{ decisionArtifact.finalDecision }}</div>
            </div>
            <div v-if="decisionArtifact.consensusPath" class="artifact-section">
              <div class="memory-title">共识形成路径</div>
              <div class="summary-text">{{ decisionArtifact.consensusPath }}</div>
            </div>
            <div v-if="artifactList(decisionArtifact.keyViewpoints).length" class="artifact-section">
              <div class="memory-title">核心观点</div>
              <ul class="artifact-list">
                <li v-for="item in artifactList(decisionArtifact.keyViewpoints)" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div v-if="artifactList(decisionArtifact.majorDisputes).length" class="artifact-section">
              <div class="memory-title">主要争议</div>
              <ul class="artifact-list">
                <li v-for="item in artifactList(decisionArtifact.majorDisputes)" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div v-if="artifactList(decisionArtifact.decisionBasis).length" class="artifact-section">
              <div class="memory-title">决策依据</div>
              <ul class="artifact-list">
                <li v-for="item in artifactList(decisionArtifact.decisionBasis)" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div v-if="artifactList(decisionArtifact.openQuestions).length" class="artifact-section">
              <div class="memory-title">待解问题</div>
              <ul class="artifact-list">
                <li v-for="item in artifactList(decisionArtifact.openQuestions)" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div v-if="artifactList(decisionArtifact.nextActions).length" class="artifact-section">
              <div class="memory-title">后续动作</div>
              <ul class="artifact-list">
                <li v-for="item in artifactList(decisionArtifact.nextActions)" :key="item">{{ item }}</li>
              </ul>
            </div>
          </el-card>

          <el-card v-if="meeting.conclusionHistory?.length" shadow="never" style="margin-top:12px">
            <template #header><span>🕰 结论版本链</span></template>
            <div class="conclusion-history-list">
              <div
                v-for="entry in meeting.conclusionHistory"
                :id="`conclusion-version-${entry.versionNo}`"
                :key="`${entry.versionNo}-${entry.eventType}-${entry.eventTime}`"
                class="conclusion-history-item"
                :class="{
                  'is-latest': latestConclusionVersion?.versionNo === entry.versionNo,
                  'is-focused': focusedConclusionVersion === entry.versionNo
                }"
              >
                <div class="conclusion-history-head">
                  <el-tag size="small" effect="plain">{{ entry.eventLabel || entry.eventType }}</el-tag>
                  <span class="conclusion-history-version">v{{ entry.versionNo }}</span>
                  <el-tag v-if="latestConclusionVersion?.versionNo === entry.versionNo" size="small" type="success" effect="dark">
                    最新版本
                  </el-tag>
                  <span v-if="entry.roundNumber" class="conclusion-history-meta">第 {{ entry.roundNumber }} 轮</span>
                  <span v-if="entry.eventTime" class="conclusion-history-meta">{{ formatDateTime(entry.eventTime) }}</span>
                </div>
                <div v-if="entry.triggerReason" class="conclusion-history-reason">{{ entry.triggerReason }}</div>
                <div
                  v-if="entry.eventType === 'REOPEN_TRACKING' && (entry.triggerTrackingItemId || entry.triggerOpinionId)"
                  class="conclusion-history-reopen-links"
                >
                  <span class="conclusion-history-links-label">本次重开来源</span>
                  <div class="conclusion-history-links-list">
                    <el-button
                      v-if="entry.triggerTrackingItemId"
                      link
                      type="primary"
                      size="small"
                      @click="jumpToTrackingItem(entry.triggerTrackingItemId)"
                    >
                      触发追踪项：{{ entry.triggerTrackingItemTitle || `#${entry.triggerTrackingItemId}` }}
                    </el-button>
                    <el-button
                      v-if="entry.triggerTrackingUpdateId"
                      link
                      type="primary"
                      size="small"
                      @click="jumpToTrackingUpdate(entry.triggerTrackingUpdateId)"
                    >
                      定位触发记录
                    </el-button>
                    <el-button
                      v-if="entry.triggerOpinionId"
                      link
                      type="primary"
                      size="small"
                      @click="jumpToOpinion(entry.triggerOpinionId)"
                    >
                      定位重开指令
                    </el-button>
                  </div>
                </div>
                <div v-if="entry.conclusion" class="conclusion-history-conclusion">{{ entry.conclusion }}</div>
                <div v-if="entry.relatedTrackingItems?.length" class="conclusion-history-links">
                  <span class="conclusion-history-links-label">本版结论吸收的追踪项</span>
                  <div class="conclusion-history-links-actions">
                    <el-button
                      link
                      type="primary"
                      size="small"
                      @click="jumpToTrackingItems(entry.relatedTrackingItems.map(link => link.trackingItemId))"
                    >
                      定位全部吸收项
                    </el-button>
                  </div>
                  <div class="conclusion-history-links-list">
                    <el-button
                      v-for="link in entry.relatedTrackingItems"
                      :key="`tracking-link-${entry.versionNo}-${link.trackingItemId}`"
                      link
                      type="primary"
                      size="small"
                      @click="jumpToTrackingItem(link.trackingItemId)"
                    >
                      {{ link.trackingItemTitle }}
                    </el-button>
                  </div>
                </div>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </template>

    <el-result v-else-if="!loading" icon="error" title="会议不存在">
      <template #extra>
        <el-button @click="$router.push('/meetings')">返回列表</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowRight, User, Refresh, Document } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { meetingApi } from '@/api/meeting'
import { getWorkflowSkillMeta } from '@/config/workflowSkills'

const route = useRoute()
const meetingId = route.params.id
const meeting = ref(null)
const contributions = ref([])
const loading = ref(false)
const cancelling = ref(false)
const terminating = ref(false)
const restarting = ref(false)
const filterRound = ref(null)
const chatRef = ref()
const newIds = ref(new Set())
const humanInput = ref('')
const humanMode = ref('message')
const targetMemberId = ref(null)
const sending = ref(false)
const workflowActionInput = ref('')
const workflowActorRef = ref('')
const workflowActionLoading = ref(false)
const workflowDrawerVisible = ref(false)
const selectedWorkflowNodeKey = ref(null)
const focusedOpinionId = ref(null)
const focusedStageRunId = ref(null)
const focusedConclusionVersion = ref(null)
const focusedTrackingItemId = ref(null)
const focusedTrackingItemIds = ref(new Set())
const focusedTrackingUpdateId = ref(null)
let pollTimer = null
let prevOpinionIds = new Set()

// 颜色系统
const COLORS = [
  { main: '#409eff', light: '#ecf5ff' },
  { main: '#67c23a', light: '#f0f9eb' },
  { main: '#e6a23c', light: '#fdf6ec' },
  { main: '#f56c6c', light: '#fef0f0' },
  { main: '#9b59b6', light: '#f3ecf8' },
  { main: '#1abc9c', light: '#e8faf6' },
  { main: '#e67e22', light: '#fdf2e9' },
  { main: '#2980b9', light: '#eaf4fb' },
]
const memberColorMap = ref({})

const memberColor      = (id) => COLORS[memberColorMap.value[id] ?? 0]?.main  ?? '#909399'
const memberColorLight = (id) => COLORS[memberColorMap.value[id] ?? 0]?.light ?? '#f5f5f5'
const memberColorByIdx = (idx) => COLORS[idx % COLORS.length]?.main ?? '#909399'

const skillLabel = (skillKey) => {
  const meta = getWorkflowSkillMeta(skillKey)
  return meta ? `${meta.name} · ${skillKey}` : skillKey
}

const downloadSkillFile = (fileId) => {
  if (!fileId) return
  window.open(`/api/skill-files/${fileId}/download`, '_blank')
}

const statusType = (s) => ({0:'info',1:'warning',2:'primary',3:'success',4:'danger',5:'warning',6:'warning',7:'danger'})[s] ?? 'info'
const formatTime = (t) => t ? dayjs(t).format('HH:mm:ss') : ''
const formatDateTime = (t) => t ? dayjs(t).format('MM-DD HH:mm:ss') : ''
const memberSpeechCount = (id) => meeting.value?.opinions?.filter(o => o.memberId === id).length ?? 0
const inputPlaceholder = computed(() => {
  if (humanMode.value === 'directive') return '输入下一轮主持人指令，例如：只讨论成本和上线节奏，不再展开技术路线'
  if (targetMemberId.value) {
    const target = meeting.value?.members?.find(m => m.id === targetMemberId.value)
    return `输入你想让 ${target?.memberName || '该议员'} 正面回应的问题…`
  }
  if ([2, 3, 4].includes(meeting.value?.status)) return '继续追问将自动重开会议并进入新一轮讨论…'
  return '输入你的观点，加入讨论…'
})
const sendButtonText = computed(() => {
  if (humanMode.value === 'directive') return [2, 3, 4].includes(meeting.value?.status) ? '重开并下达' : '下达指令'
  return [2, 3, 4].includes(meeting.value?.status) ? '继续追问' : '发送'
})
const inputHint = computed(() => {
  if (humanMode.value === 'directive') return '主持人模式会作为最高优先级指令注入下一轮讨论'
  if (targetMemberId.value) return '定向追问会优先注入指定议员的下一轮上下文，要求其直接回应'
  if ([2, 3, 4].includes(meeting.value?.status)) return '已结论会议继续追问后会自动进入“结论修订中”'
  return 'Enter 发送 · AI 成员将在下一轮看到你的发言'
})
const targetableMembers = computed(() => meeting.value?.members || [])

const rounds = computed(() => {
  if (!meeting.value?.opinions) return []
  return [...new Set(meeting.value.opinions.map(o => o.roundNumber))].sort((a, b) => a - b)
})
const filteredOpinions = computed(() => {
  if (!meeting.value?.opinions) return []
  return filterRound.value
    ? meeting.value.opinions.filter(o => o.roundNumber === filterRound.value)
    : meeting.value.opinions
})
const groupedOpinions = computed(() => {
  const groups = {}
  for (const op of filteredOpinions.value) {
    if (!groups[op.roundNumber]) groups[op.roundNumber] = []
    groups[op.roundNumber].push(op)
  }
  return groups
})
const targetedThreads = computed(() => {
  const opinions = meeting.value?.opinions || []
  const replies = opinions.filter(op => op.responseToTargetedQuestion)
  return opinions
    .filter(op => op.opinionType === 5 && op.targetMemberName)
    .map(question => ({
      question,
      reply: replies.find(reply => (reply.replyToOpinionId || reply.parentOpinionId) === question.id) || null
    }))
})
const progressPct = computed(() => {
  if (!meeting.value?.totalRounds) return 0
  const current = meeting.value.currentRound || 0
  const total = meeting.value.totalRounds || 1
  if ([2, 3, 4, 5].includes(meeting.value.status)) return 100
  return Math.min(95, Math.max(5, Math.round((current / total) * 100)))
})

const decisionArtifact = computed(() => meeting.value?.decisionArtifact || null)
const workflowSummary = computed(() => meeting.value?.workflow || null)
const workflowNodes = computed(() => workflowSummary.value?.nodes || [])
const selectedWorkflowNode = computed(() =>
  workflowNodes.value.find(node => node.stageKey === selectedWorkflowNodeKey.value) || null
)
const trackingSummary = computed(() => meeting.value?.tracking || null)
const latestConclusionVersion = computed(() => {
  const history = meeting.value?.conclusionHistory || []
  const concluded = history.filter(entry => entry.eventType === 'CONCLUDED' && entry.conclusion)
  return concluded.length ? concluded[concluded.length - 1] : null
})
const workflowStageIsActive = computed(() =>
  ['ACTIVE', 'REOPENED'].includes(workflowSummary.value?.runStatus)
    && ['ACTIVE', 'BLOCKED'].includes(workflowSummary.value?.currentStageStatus)
)
const canSubmitWorkflowStage = computed(() => {
  const t = workflowSummary.value?.currentStageType
  return !!workflowSummary.value
    && workflowStageIsActive.value
    && !isDefaultWorkflow.value
    && ['ANALYSIS', 'DEBATE', 'TRACKING'].includes(t)
})
const canReviewWorkflowStage = computed(() => {
  const t = workflowSummary.value?.currentStageType
  return !!workflowSummary.value && !isDefaultWorkflow.value
    && workflowStageIsActive.value
    && ['REVIEW', 'APPROVAL'].includes(t)
    && workflowSummary.value?.currentStageReviewRequired !== 0
})
const canRetryWorkflowStage = computed(() =>
  !!workflowSummary.value
    && !isDefaultWorkflow.value
    && workflowStageIsActive.value
    && workflowSummary.value?.currentStageRetryable
)
const showWorkflowActionPanel = computed(() => canSubmitWorkflowStage.value || canReviewWorkflowStage.value || canRetryWorkflowStage.value)
const isDefaultWorkflow = computed(() => workflowSummary.value?.templateKey === 'default-roundtable')
const workflowActionPlaceholder = computed(() => {
  if (workflowSummary.value?.currentStageGuide?.actionPlaceholder) return workflowSummary.value.currentStageGuide.actionPlaceholder
  if (canReviewWorkflowStage.value) return '填写审核意见；通过时可留空，打回时建议说明原因'
  if (canSubmitWorkflowStage.value) return '填写当前阶段的输出内容，例如分析结论、核对结果或补充说明'
  return ''
})

function workflowNodeStatusType(status) {
  return ({
    ACTIVE: 'warning',
    COMPLETED: 'success',
    BLOCKED: 'danger',
    REJECTED: 'danger',
    PENDING: 'info'
  })[status] || 'info'
}

function workflowSearchStatusType(status, degraded) {
  if (degraded) return 'danger'
  return ({
    success: 'success',
    missing_required_search: 'danger',
    attempted_no_results: 'warning',
    not_attempted: 'info',
    no_provider_succeeded: 'warning'
  })[status] || 'info'
}

function workflowSearchStatusLabel(status, attempted) {
  if (status === 'success') return '搜索成功'
  if (status === 'missing_required_search') return '缺少必需搜索'
  if (status === 'attempted_no_results') return '已搜索无结果'
  if (status === 'no_provider_succeeded') return '搜索失败'
  if (status === 'not_attempted') return '未搜索'
  if (!status) return attempted ? '已搜索' : '未搜索'
  return status
}

function workflowTransitionLabel(stageKey) {
  const node = workflowNodes.value.find(item => item.stageKey === stageKey)
  return node?.stageName || stageKey || '-'
}

function openWorkflowNode(node) {
  if (!node?.stageKey) return
  selectedWorkflowNodeKey.value = node.stageKey
  workflowDrawerVisible.value = true
}

function workflowAttemptTitle(attempt) {
  return `第 ${attempt?.attemptNo || 0} 次执行`
}

// 解析 roundSummaries JSON 为 { roundNumber: summaryText } 的 map
const roundSummaryMap = computed(() => {
  const raw = meeting.value?.roundSummaries
  if (!raw) return {}
  try {
    const arr = JSON.parse(raw)
    const map = {}
    for (const item of arr) {
      map[item.round] = item.summary
    }
    return map
  } catch { return {} }
})

// 解析 evidence JSON，兼容短键名 {q,e} 和长键名 {queries,evidence}
const evidenceCache = new Map()
function parsedEvidence(raw) {
  if (!raw) return {}
  if (evidenceCache.has(raw)) return evidenceCache.get(raw)
  try {
    const r = JSON.parse(raw)
    const result = {
      queries:     r.q  || r.queries  || [],
      evidence:    r.e  || r.evidence || [],
      realResults: r.results || [],
      searchPlan:  r.searchPlan || null,
      searchMeta:  r.searchMeta || null,
      validation:  r.validation || null,
      conflictValidation: r.conflictValidation || null
    }
    evidenceCache.set(raw, result)
    return result
  } catch {
    evidenceCache.set(raw, {})
    return {}
  }
}

function hasEvidence(raw) {
  const ev = parsedEvidence(raw)
  return (ev.queries?.length > 0)
    || (ev.evidence?.length > 0)
    || (ev.realResults?.length > 0)
    || !!ev.searchPlan
    || !!ev.searchMeta
    || !!ev.validation
    || !!ev.conflictValidation
}

function artifactList(value) {
  return Array.isArray(value) ? value.filter(Boolean) : []
}

function submissionActionLabel(actionType) {
  return ({
    SUBMIT: '提交',
    APPROVE: '审核通过',
    REJECT: '打回',
    BYPASS: '自动跳过',
    SEARCH_FAIL: '搜索失败'
  })[actionType] || actionType
}

function submissionTagType(actionType) {
  return ({
    SUBMIT: 'primary',
    APPROVE: 'success',
    REJECT: 'danger',
    BYPASS: 'warning',
    SEARCH_FAIL: 'danger'
  })[actionType] || 'info'
}

function submissionActorLabel(actorRef) {
  if (!actorRef) return '未知执行者'
  if (actorRef === 'SYSTEM') return '系统'
  if (actorRef === 'HUMAN') return '人工提交'
  return actorRef
}

function trackingUpdateLabel(update) {
  if (update.updateType === 'TRIGGER_REOPEN') return '触发重开'
  if (update.statusAfter) return `状态 -> ${trackingStatusLabel(update.statusAfter)}`
  return ({
    CREATED: '已创建',
    STATUS_CHANGE: '状态更新'
  })[update.updateType] || update.updateType
}

function trackingUpdateTagType(update) {
  if (update.updateType === 'TRIGGER_REOPEN') return 'success'
  if (update.statusAfter === 'VERIFIED') return 'success'
  if (['REJECTED', 'BLOCKED'].includes(update.statusAfter)) return 'danger'
  if (update.statusAfter === 'IN_PROGRESS') return 'primary'
  if (update.statusAfter === 'CLOSED') return 'info'
  return 'warning'
}

function trackingStatusLabel(status) {
  return ({
    OPEN: '待处理',
    IN_PROGRESS: '处理中',
    READY_FOR_REVIEW: '待审核',
    VERIFIED: '已验证',
    BLOCKED: '已阻塞',
    REJECTED: '已驳回',
    CLOSED: '已关闭'
  })[status] || status
}

function jumpToOpinion(opinionId) {
  focusedOpinionId.value = opinionId
  nextTick(() => {
    const node = document.getElementById(`opinion-${opinionId}`)
    if (node) {
      node.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
    window.setTimeout(() => {
      if (focusedOpinionId.value === opinionId) {
        focusedOpinionId.value = null
      }
    }, 1800)
  })
}

function jumpToStageRun(stageRunId) {
  focusedStageRunId.value = stageRunId
  nextTick(() => {
    const node = document.getElementById(`stage-run-${stageRunId}`)
    if (node) {
      node.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
    window.setTimeout(() => {
      if (focusedStageRunId.value === stageRunId) {
        focusedStageRunId.value = null
      }
    }, 1800)
  })
}

function jumpToConclusionVersion(versionNo) {
  focusedConclusionVersion.value = versionNo
  nextTick(() => {
    const node = document.getElementById(`conclusion-version-${versionNo}`)
    if (node) {
      node.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
    window.setTimeout(() => {
      if (focusedConclusionVersion.value === versionNo) {
        focusedConclusionVersion.value = null
      }
    }, 1800)
  })
}

function jumpToTrackingItem(trackingItemId) {
  focusedTrackingItemIds.value = new Set()
  focusedTrackingItemId.value = trackingItemId
  nextTick(() => {
    const node = document.getElementById(`tracking-item-${trackingItemId}`)
    if (node) {
      node.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
    window.setTimeout(() => {
      if (focusedTrackingItemId.value === trackingItemId) {
        focusedTrackingItemId.value = null
      }
    }, 1800)
  })
}

function jumpToTrackingItems(trackingItemIds) {
  const ids = (trackingItemIds || []).filter(Boolean)
  if (!ids.length) return
  focusedTrackingItemId.value = null
  focusedTrackingItemIds.value = new Set(ids)
  nextTick(() => {
    const node = document.getElementById(`tracking-item-${ids[0]}`)
    if (node) {
      node.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
    window.setTimeout(() => {
      if (ids.every(id => focusedTrackingItemIds.value.has(id))) {
        focusedTrackingItemIds.value = new Set()
      }
    }, 2200)
  })
}

function jumpToTrackingUpdate(trackingUpdateId) {
  focusedTrackingUpdateId.value = trackingUpdateId
  nextTick(() => {
    const node = document.getElementById(`tracking-update-${trackingUpdateId}`)
    if (node) {
      node.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
    window.setTimeout(() => {
      if (focusedTrackingUpdateId.value === trackingUpdateId) {
        focusedTrackingUpdateId.value = null
      }
    }, 1800)
  })
}

function trackingUpdateOpinionId(update) {
  const ref = update?.evidenceRef
  if (!ref) return null
  const match = String(ref).match(/opinion:(\d+)/)
  return match ? Number(match[1]) : null
}

function trackingUpdateEvidenceText(update) {
  const ref = update?.evidenceRef
  if (!ref) return ''
  return String(ref)
    .split('|')
    .map(part => part.trim())
    .filter(part => part && !part.startsWith('opinion:'))
    .join('；')
}

async function loadDetail(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await meetingApi.getDetail(meetingId)
    const data = res.data

    const currentIds = new Set((data.opinions || []).map(o => o.id))
    const added = new Set([...currentIds].filter(id => !prevOpinionIds.has(id)))
    prevOpinionIds = currentIds
    newIds.value = added

    meeting.value = data
    if (data?.workflow?.nodes?.length) {
      const existing = data.workflow.nodes.find(node => node.stageKey === selectedWorkflowNodeKey.value)
      if (!existing) {
        selectedWorkflowNodeKey.value = data.workflow.currentStageKey || data.workflow.nodes[0]?.stageKey || null
      }
    } else {
      selectedWorkflowNodeKey.value = null
    }
    if (data?.members) {
      data.members.forEach((m, i) => { memberColorMap.value[m.id] = i % COLORS.length })
    }
    if ([2, 3, 4].includes(data?.status)) {
      try { contributions.value = (await meetingApi.getContributions(meetingId)).data || [] } catch {}
    }
    if (added.size > 0) {
      await nextTick()
      scrollToBottom()
      setTimeout(() => { newIds.value = new Set() }, 2500)
    }
  } finally { if (!silent) loading.value = false }
}

function scrollToBottom() {
  if (chatRef.value) chatRef.value.scrollTop = chatRef.value.scrollHeight
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    if ([1, 6].includes(meeting.value?.status)) await loadDetail(true)
    else { clearInterval(pollTimer); pollTimer = null }
  }, 2000)
}

async function handleStart() {
  await meetingApi.start(meetingId)
  ElMessage.success(meeting.value?.status === 5 ? '讨论已继续' : '讨论已启动')
  await loadDetail()
  startPolling()
}

async function handleCancel() {
  try {
    await ElMessageBox.confirm('确认暂停会议？当前历史会保留，稍后可以继续讨论。', '暂停会议',
      { type: 'warning', confirmButtonText: '确认暂停', cancelButtonText: '关闭' })
    cancelling.value = true
    await meetingApi.cancel(meetingId)
    ElMessage.warning('会议已暂停')
    clearInterval(pollTimer); pollTimer = null
    await loadDetail()
  } catch {} finally { cancelling.value = false }
}

async function handleTerminate() {
  try {
    await ElMessageBox.confirm('根据当前讨论进度强制生成结论并终止会议？', '提交结论',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '继续讨论' })
    terminating.value = true
    await meetingApi.terminate(meetingId)
    ElMessage.success('已提交，正在生成结论…')
    clearInterval(pollTimer); pollTimer = null
    await loadDetail()
  } catch {} finally { terminating.value = false }
}

async function handleRestart() {
  try {
    await ElMessageBox.confirm('重启会议将清空所有讨论记录，重新开始。确认吗？', '重启会议',
      { type: 'warning', confirmButtonText: '确认重启', cancelButtonText: '取消' })
    restarting.value = true
    await meetingApi.restart(meetingId)
    ElMessage.success('会议已重置，可重新启动')
    await loadDetail()
  } catch {} finally { restarting.value = false }
}

async function handleVerify(valid) {
  await ElMessageBox.confirm(`确认将结论标记为「${valid ? '有效' : '无效'}」？`, '验证结论',
    { type: valid ? 'success' : 'warning' })
  await meetingApi.verify(meetingId, valid)
  ElMessage.success('验证完成，积分已结算')
  loadDetail()
}

async function sendHumanMessage() {
  if (!humanInput.value.trim() || sending.value) return
  sending.value = true
  try {
    await meetingApi.sendHumanMessage(meetingId, humanInput.value.trim(), humanMode.value, targetMemberId.value)
    humanInput.value = ''
    if (humanMode.value !== 'directive') targetMemberId.value = null
    await loadDetail(true)
    await nextTick()
    scrollToBottom()
    if ([1, 6].includes(meeting.value?.status) && !pollTimer) startPolling()
  } catch {
    ElMessage.error('发送失败，请重试')
  } finally {
    sending.value = false
  }
}

async function updateTracking(item, status) {
  const labels = {
    IN_PROGRESS: '开始处理',
    VERIFIED: '标记已验证',
    CLOSED: '关闭',
    REJECTED: '驳回'
  }
  const actionText = labels[status] || status
  const note = status === 'VERIFIED'
    ? '已完成验证，可继续推进会议'
    : `通过详情页执行了「${actionText}」`
  try {
    await ElMessageBox.confirm(`确认将追踪项「${item.title}」设为「${actionText}」？`, '更新追踪项', {
      type: status === 'REJECTED' ? 'warning' : 'info',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    })
    await meetingApi.updateTrackingStatus(item.id, status, note)
    ElMessage.success(`追踪项已更新为「${actionText}」`)
    await loadDetail()
    if ([1, 6].includes(meeting.value?.status) && !pollTimer) startPolling()
  } catch {}
}

async function submitWorkflowStage() {
  if (!workflowActionInput.value.trim() || workflowActionLoading.value) return
  workflowActionLoading.value = true
  try {
    await meetingApi.submitWorkflowStage(meetingId, workflowActionInput.value.trim(), workflowActorRef.value.trim())
    workflowActionInput.value = ''
    workflowActorRef.value = ''
    ElMessage.success('当前阶段已提交')
    await loadDetail()
  } catch {
    ElMessage.error('阶段提交失败，请重试')
  } finally {
    workflowActionLoading.value = false
  }
}

async function reviewWorkflowStage(approved) {
  if (workflowActionLoading.value) return
  workflowActionLoading.value = true
  try {
    await meetingApi.reviewWorkflowStage(meetingId, approved, workflowActionInput.value.trim(), workflowActorRef.value.trim())
    workflowActionInput.value = ''
    workflowActorRef.value = ''
    ElMessage.success(approved ? '审核已通过' : '已打回到上游阶段')
    await loadDetail()
  } catch {
    ElMessage.error('审核操作失败，请重试')
  } finally {
    workflowActionLoading.value = false
  }
}

async function retryWorkflowAutoStage() {
  if (workflowActionLoading.value) return
  workflowActionLoading.value = true
  try {
    await meetingApi.retryWorkflowAutoStage(meetingId)
    ElMessage.success('已重新触发自动执行')
    await loadDetail()
  } catch {
    ElMessage.error('自动重试失败，请稍后再试')
  } finally {
    workflowActionLoading.value = false
  }
}

onMounted(async () => {
  await loadDetail()
  if ([1, 6].includes(meeting.value?.status)) startPolling()
})
onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })
</script>

<style scoped>
.meeting-detail { display: flex; flex-direction: column; gap: 14px; }
.header-card, .chat-card, .members-card, .contrib-card { border-radius: 12px; }

/* 头部 */
.meeting-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; }
.header-left  { flex: 1; min-width: 0; }
.header-title { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.header-title h2 { font-size: 17px; font-weight: 700; margin: 0; }
.header-desc  { color: #909399; font-size: 13px; margin: 5px 0 4px; }
.project-chip-row { margin: 6px 0 2px; }
.header-meta  { display: flex; gap: 12px; font-size: 12px; color: #606266; flex-wrap: wrap; margin-top: 4px; }
.header-meta span { display: flex; align-items: center; gap: 4px; }
.header-actions { display: flex; gap: 6px; flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }
.progress-wrap { margin-top: 10px; }

/* LIVE 指示器 */
.live-indicator { color: #f56c6c; font-weight: 500; }
.live-dot {
  display: inline-block; width: 7px; height: 7px; border-radius: 50%;
  background: #f56c6c; margin-right: 3px;
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse { 0%,100%{opacity:1;transform:scale(1)} 50%{opacity:.4;transform:scale(.6)} }

/* 聊天区 */
.chat-card-header { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.chat-body {
  height: 500px; overflow-y: auto; padding: 4px 2px;
  display: flex; flex-direction: column; gap: 2px;
  scroll-behavior: smooth;
}

/* 轮次分隔线 */
.round-sep {
  display: flex; align-items: center; gap: 8px;
  margin: 12px 0 8px; font-size: 11px; color: #c0c4cc;
}
.round-sep::before, .round-sep::after {
  content: ''; flex: 1; height: 1px; background: #f0f0f0;
}
.round-sep span {
  background: #f5f7fa; padding: 2px 10px; border-radius: 10px;
  border: 1px solid #e8e8e8; white-space: nowrap;
}

/* 轮次摘要 */
.round-summary {
  font-size: 11px; color: #909399; background: #fafafa;
  border: 1px dashed #e8e8e8; border-radius: 6px;
  padding: 4px 10px; margin: 4px 16px 8px;
  display: flex; align-items: flex-start; gap: 5px; line-height: 1.5;
}

/* 消息行 */
.chat-msg {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 4px 6px; border-radius: 10px;
  transition: background .3s;
}
.chat-msg.is-new { background: #f0f7ff; }
.chat-msg.is-focused {
  background: rgba(64, 158, 255, 0.08);
  box-shadow: inset 0 0 0 1px rgba(64, 158, 255, 0.18);
}
.chat-msg.is-human {
  flex-direction: row-reverse;
}
.chat-msg.is-human .chat-content {
  align-items: flex-end;
}
.chat-msg.is-human .chat-bubble {
  border-radius: 12px 0 12px 12px;
}
.chat-msg.is-human .chat-name {
  justify-content: flex-end;
}

/* 气泡进入动画 */
.bubble-enter-active { transition: all .35s ease; }
.bubble-enter-from   { opacity: 0; transform: translateY(10px); }

.chat-avatar {
  width: 36px; height: 36px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700; font-size: 14px;
  margin-top: 2px;
}
.chat-content { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.chat-name {
  font-size: 12px; font-weight: 600; margin-bottom: 4px;
  display: flex; align-items: center;
}
.chat-reply-label {
  margin-bottom: 4px;
  font-size: 11px;
  color: #909399;
}
.chat-bubble {
  display: inline-block; max-width: 90%;
  background: var(--bubble-color, #f5f5f5);
  border-radius: 0 12px 12px 12px;
  padding: 10px 14px;
  font-size: 14px; line-height: 1.65; color: #303133;
  word-break: break-word; white-space: pre-wrap;
}
.chat-bubble .el-button {
  vertical-align: baseline;
  margin-left: 4px;
  font-size: 12px;
  height: auto;
  padding: 0;
}
.chat-time {
  font-size: 11px; color: #c0c4cc; margin-top: 4px; padding-left: 2px;
}

/* 搜索证据卡 */
.evidence-card {
  margin-bottom: 6px;
  background: #f8faff;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  font-size: 12px;
  max-width: 90%;
  overflow: hidden;
}
.evidence-header {
  display: flex; align-items: center; gap: 6px;
  padding: 5px 10px;
  color: #3b82f6; font-weight: 600; font-size: 12px;
}
.evidence-body { padding: 8px 10px; border-top: 1px solid #dbeafe; }
.evidence-note {
  margin-bottom: 6px;
  padding: 6px 8px;
  font-size: 12px;
  color: #92400e;
  background: #fffbeb;
  border: 1px solid #fcd34d;
  border-radius: 8px;
  line-height: 1.5;
}
.evidence-note-danger {
  color: #991b1b;
  background: #fef2f2;
  border-color: #fca5a5;
}
.evidence-plan {
  margin-bottom: 6px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}
.evidence-queries { margin-bottom: 6px; display: flex; flex-wrap: wrap; align-items: center; }
.evidence-label { color: #6b7280; margin-right: 4px; }
.evidence-items { display: flex; flex-direction: column; gap: 4px; }
.evidence-item {
  display: flex; gap: 6px; align-items: flex-start;
  padding: 4px 6px; background: #fff; border-radius: 5px;
  border: 1px solid #e0e7ff;
}
.evidence-source {
  color: #6366f1; font-weight: 600; white-space: nowrap;
  font-size: 11px; padding: 1px 4px; background: #eef2ff;
  border-radius: 3px; flex-shrink: 0;
}
.evidence-link {
  color: #6366f1; text-decoration: none;
}
.evidence-link:hover {
  text-decoration: underline;
}
.evidence-link .el-icon { margin-left: 2px; vertical-align: 0; }
.evidence-point { color: #374151; line-height: 1.5; }

/* 用户输入区 */
.input-area {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}
.input-prepend-wrap {
  display: flex;
  align-items: stretch;
}
.input-hint {
  font-size: 11px; color: #c0c4cc; margin-top: 5px; text-align: right;
}

/* AI 思考动画 */
.thinking-placeholder {
  display: flex; flex-direction: column; align-items: center;
  justify-content: center; flex: 1; height: 200px; color: #909399;
}
.thinking-placeholder p { margin-top: 12px; font-size: 13px; }
.dots { display: flex; gap: 6px; }
.dots span {
  width: 9px; height: 9px; border-radius: 50%; background: #c0c4cc;
  animation: bounce 1.2s infinite ease-in-out;
}
.dots span:nth-child(2) { animation-delay: .2s; }
.dots span:nth-child(3) { animation-delay: .4s; }
@keyframes bounce { 0%,80%,100%{transform:translateY(0)} 40%{transform:translateY(-9px);background:#409eff} }

/* 右侧成员区 */
.member-item { display: flex; gap: 10px; align-items: center; padding: 8px 0; border-bottom: 1px solid #f0f0f0; }
.member-item:last-child { border-bottom: none; }
.member-avatar {
  width: 36px; height: 36px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700; font-size: 14px;
}
.member-name   { font-weight: 600; font-size: 13px; }
.speech-count  { font-size: 11px; color: #909399; margin-top: 2px; }
.contrib-item  { margin-bottom: 10px; }
.contrib-name  { font-size: 13px; font-weight: 500; margin-bottom: 4px; }
.workflow-overview { display: flex; flex-direction: column; gap: 8px; }
.workflow-line { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; color: #606266; }
.workflow-line span:first-child { color: #909399; }
.workflow-line-search { align-items: flex-start; }
.workflow-line-search-content { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; }
.workflow-blocked { margin-top: 6px; font-size: 12px; color: #e67e22; line-height: 1.6; }
.workflow-search-warning { color: #c2410c; }
.workflow-search-diagnostics {
  margin-top: 4px;
  font-size: 12px;
  color: #7c2d12;
  line-height: 1.6;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  padding: 8px 10px;
}
.workflow-skill-prompt { color: #2563eb; }
.workflow-map {
  margin-top: 12px;
  padding: 12px;
  border-radius: 12px;
  background:
    radial-gradient(circle at top left, rgba(56, 189, 248, 0.12), transparent 35%),
    linear-gradient(180deg, #fbfdff 0%, #f7fbff 100%);
  border: 1px solid #dbeafe;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.workflow-node {
  position: relative;
  display: grid;
  grid-template-columns: 22px 1fr;
  gap: 10px;
  cursor: pointer;
}
.workflow-node-dot {
  position: relative;
  width: 14px;
  height: 14px;
  margin-top: 18px;
  border-radius: 50%;
  background: #cbd5e1;
  border: 3px solid #fff;
  box-shadow: 0 0 0 1px rgba(148, 163, 184, 0.35);
  z-index: 2;
}
.workflow-node.is-done .workflow-node-dot {
  background: #22c55e;
  box-shadow: 0 0 0 1px rgba(34, 197, 94, 0.28);
}
.workflow-node.is-blocked .workflow-node-dot {
  background: #ef4444;
  box-shadow: 0 0 0 1px rgba(239, 68, 68, 0.24);
}
.workflow-node.is-active .workflow-node-dot {
  background: #f59e0b;
  animation: workflowPulse 1.4s ease-in-out infinite;
}
.workflow-node-rail {
  position: absolute;
  left: 6px;
  top: 28px;
  bottom: -14px;
  width: 2px;
}
.workflow-node-rail-line {
  display: block;
  width: 2px;
  height: 100%;
  background: linear-gradient(180deg, #93c5fd 0%, #dbeafe 100%);
}
.workflow-node-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 12px;
  background: rgba(255,255,255,0.92);
  transition: transform .2s ease, box-shadow .2s ease, border-color .2s ease;
}
.workflow-node:hover .workflow-node-card {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(148, 163, 184, 0.16);
}
.workflow-node.is-active .workflow-node-card {
  border-color: #f59e0b;
  box-shadow: 0 12px 28px rgba(245, 158, 11, 0.18);
}
.workflow-node-head,
.workflow-drawer-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.workflow-node-order {
  font-size: 11px;
  font-weight: 700;
  color: #2563eb;
  background: #eff6ff;
  border-radius: 999px;
  padding: 2px 8px;
}
.workflow-node-name {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}
.workflow-node-meta,
.workflow-node-foot {
  display: flex;
  gap: 8px 12px;
  flex-wrap: wrap;
  margin-top: 8px;
  font-size: 12px;
  color: #64748b;
}
.workflow-node-foot {
  color: #475569;
}
.workflow-node-search {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
  font-size: 12px;
  color: #7c2d12;
}
.workflow-attempt-list {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.workflow-file-list { margin-top: 8px; padding: 8px 10px; border-radius: 8px; background: #fff; border: 1px solid #e2e8f0; }
.workflow-file-item { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 12px; color: #334155; }
.workflow-file-item + .workflow-file-item { margin-top: 6px; }
.workflow-guide-list { margin-top: 8px; padding: 8px 10px; border-radius: 8px; background: #f8fbff; border: 1px solid #dbeafe; }
.workflow-guide-title { font-size: 12px; font-weight: 600; color: #1d4ed8; }
.workflow-guide-list ul { margin: 6px 0 0 0; padding-left: 18px; color: #475569; font-size: 12px; }
.workflow-guide-list li { line-height: 1.7; }
.stage-guide { background: #fff; border-color: #e5eefc; }
.workflow-stage-list { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
.workflow-action-panel { margin-top: 12px; padding: 10px; border: 1px solid #ebeef5; border-radius: 8px; background: #fafbfd; }
.workflow-action-buttons { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.workflow-action-hint { margin-top: 8px; font-size: 12px; color: #909399; line-height: 1.6; }
.workflow-stage-item { border: 1px solid #ebeef5; border-radius: 8px; padding: 10px; background: #fafbfd; }
.workflow-stage-item.is-focused {
  border-color: #93c5fd;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.12);
  background: #f5f9ff;
}
.workflow-stage-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.workflow-stage-order { font-size: 11px; color: #909399; }
.workflow-stage-name { font-size: 13px; font-weight: 600; color: #303133; }
.workflow-stage-meta { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 6px; font-size: 12px; color: #909399; }
.workflow-stage-handoff { margin-top: 6px; font-size: 12px; color: #606266; line-height: 1.6; background: #f8fafc; border: 1px solid #e8eef5; border-radius: 6px; padding: 6px 8px; }
.workflow-handoff-title { font-size: 12px; font-weight: 600; color: #606266; }
.workflow-handoff-meta { margin-top: 4px; font-size: 11px; color: #909399; }
.workflow-handoff-content { margin-top: 6px; white-space: pre-wrap; color: #303133; }
.workflow-stage-reject { margin-top: 6px; font-size: 12px; color: #f56c6c; line-height: 1.6; }
.workflow-submission-list { margin-top: 10px; padding-top: 10px; border-top: 1px dashed #e5e7eb; display: flex; flex-direction: column; gap: 8px; }
.workflow-submission-title { font-size: 12px; font-weight: 600; color: #606266; }
.workflow-submission-item { background: #fff; border: 1px solid #eef1f6; border-radius: 8px; padding: 8px; }
.workflow-submission-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.workflow-submission-actor,
.workflow-submission-time { font-size: 12px; color: #909399; }
.workflow-submission-sources { margin-top: 6px; font-size: 12px; color: #b45309; line-height: 1.6; }
.workflow-submission-content { margin-top: 6px; font-size: 12px; color: #303133; line-height: 1.7; white-space: pre-wrap; }
.workflow-artifact-card { margin-top: 6px; padding: 8px; border: 1px solid #e8eef5; border-radius: 8px; background: #fff; }
.workflow-artifact-summary { font-size: 12px; font-weight: 600; color: #303133; line-height: 1.7; }
.workflow-artifact-list { margin: 6px 0 0 0; padding-left: 18px; color: #475569; font-size: 12px; }
.workflow-artifact-list li { line-height: 1.7; }
.workflow-artifact-detail { margin-top: 6px; font-size: 12px; color: #606266; line-height: 1.7; white-space: pre-wrap; }
.workflow-review-feedback { margin-top: 8px; padding: 8px; border-radius: 8px; background: #fff7ed; border: 1px solid #fed7aa; }
.workflow-review-feedback-title { font-size: 12px; font-weight: 600; color: #c2410c; }
.workflow-review-feedback-content { margin-top: 4px; font-size: 12px; color: #9a3412; line-height: 1.7; white-space: pre-wrap; }
.workflow-review-feedback-note { margin-top: 6px; font-size: 12px; color: #7c2d12; line-height: 1.7; }
.workflow-review-feedback-list { margin-top: 6px; font-size: 12px; }
.workflow-review-feedback-list span.matched { color: #15803d; font-weight: 600; }
.workflow-review-feedback-list span.unmatched { color: #b45309; font-weight: 600; }
.workflow-review-feedback-list ul { margin: 4px 0 0 0; padding-left: 18px; }
.workflow-review-feedback-list li { color: #7c2d12; line-height: 1.7; }
.workflow-artifact-diff { margin-top: 8px; padding-top: 8px; border-top: 1px dashed #e5e7eb; }
.workflow-artifact-diff-title { font-size: 12px; font-weight: 600; color: #303133; }
.workflow-artifact-diff-note,
.workflow-artifact-diff-summary { margin-top: 6px; font-size: 12px; color: #606266; line-height: 1.7; }
.workflow-artifact-diff-list { margin-top: 6px; font-size: 12px; color: #475569; }
.workflow-artifact-diff-list span.added { color: #16a34a; font-weight: 600; }
.workflow-artifact-diff-list span.removed { color: #dc2626; font-weight: 600; }
.workflow-artifact-diff-list ul { margin: 4px 0 0 0; padding-left: 18px; }
.workflow-artifact-diff-list li { line-height: 1.7; }
.workflow-search-list { margin-top: 8px; display: flex; flex-direction: column; gap: 8px; }
.workflow-search-card {
  padding: 8px;
  border-radius: 8px;
  border: 1px solid #dbeafe;
  background: #f8fbff;
}
.workflow-search-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.workflow-search-note,
.workflow-search-queries,
.workflow-search-digest,
.workflow-search-results {
  margin-top: 6px;
  font-size: 12px;
  color: #475569;
  line-height: 1.7;
}
.workflow-inline-label {
  display: inline-block;
  font-size: 12px;
  font-weight: 600;
  color: #1d4ed8;
  margin-right: 6px;
}
.workflow-search-result + .workflow-search-result {
  margin-top: 6px;
}
.workflow-search-result-snippet {
  margin-top: 2px;
  color: #64748b;
}
@keyframes workflowPulse {
  0%, 100% { transform: scale(1); box-shadow: 0 0 0 1px rgba(245, 158, 11, 0.24); }
  50% { transform: scale(1.18); box-shadow: 0 0 0 8px rgba(245, 158, 11, 0.12); }
}
.tracking-summary-row { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
.tracking-policy-card { margin-bottom: 12px; padding: 10px 12px; border: 1px solid #e5e7eb; border-radius: 8px; background: #fafcff; }
.tracking-policy-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.tracking-policy-title { font-size: 13px; font-weight: 600; color: #303133; }
.tracking-policy-line { margin-top: 8px; display: flex; justify-content: space-between; gap: 8px; font-size: 12px; color: #606266; }
.tracking-policy-line b { color: #303133; }
.tracking-policy-note { margin-top: 8px; font-size: 12px; color: #475569; line-height: 1.7; }
.tracking-policy-list { margin-top: 10px; font-size: 12px; color: #475569; }
.tracking-policy-subtitle { font-weight: 600; color: #303133; margin-bottom: 4px; }
.tracking-policy-list ul { margin: 0; padding-left: 18px; }
.tracking-policy-list li { line-height: 1.7; }
.tracking-list { display: flex; flex-direction: column; gap: 10px; }
.tracking-item {
  border: 1px solid #ebeef5; border-radius: 8px; padding: 10px; background: #fff;
  transition: border-color .2s ease, box-shadow .2s ease, background-color .2s ease;
}
.tracking-item.is-focused { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64, 158, 255, .15); background: #f8fbff; }
.tracking-item-head { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
.tracking-item-title { font-size: 13px; font-weight: 600; color: #303133; line-height: 1.6; }
.tracking-item-desc { margin-top: 6px; font-size: 12px; color: #606266; line-height: 1.6; }
.tracking-latest-update { margin-top: 6px; font-size: 12px; color: #303133; line-height: 1.6; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 6px; padding: 6px 8px; }
.tracking-review-resolution { margin-top: 8px; padding: 8px; border-radius: 8px; background: #fffbeb; border: 1px solid #fde68a; }
.tracking-review-resolution-title { font-size: 12px; font-weight: 600; color: #92400e; }
.tracking-review-resolution-note { margin-top: 4px; font-size: 12px; color: #78350f; line-height: 1.7; }
.tracking-review-resolution-list { margin-top: 6px; font-size: 12px; }
.tracking-review-resolution-list span.matched { color: #15803d; font-weight: 600; }
.tracking-review-resolution-list span.unmatched { color: #b45309; font-weight: 600; }
.tracking-review-resolution-list ul { margin: 4px 0 0 0; padding-left: 18px; }
.tracking-review-resolution-list li { color: #78350f; line-height: 1.7; }
.tracking-conclusion-resolution { margin-top: 8px; padding: 8px; border-radius: 8px; background: #eff6ff; border: 1px solid #bfdbfe; }
.tracking-conclusion-resolution-title { font-size: 12px; font-weight: 600; color: #1d4ed8; }
.tracking-conclusion-resolution-note { margin-top: 4px; font-size: 12px; color: #1e3a8a; line-height: 1.7; }
.tracking-resolution-actions { margin-top: 6px; }
.tracking-conclusion-resolution-list { margin-top: 6px; font-size: 12px; }
.tracking-conclusion-resolution-list span.matched { color: #1d4ed8; font-weight: 600; }
.tracking-conclusion-resolution-list ul { margin: 4px 0 0 0; padding-left: 18px; }
.tracking-conclusion-resolution-list li { color: #1e40af; line-height: 1.7; }
.tracking-source-artifact { margin-top: 8px; background: #f8fafc; border: 1px solid #e8eef5; border-radius: 6px; padding: 6px 8px; }
.tracking-source-title { font-size: 12px; font-weight: 600; color: #606266; }
.tracking-source-meta { margin-top: 4px; font-size: 12px; color: #606266; display: flex; gap: 4px; flex-wrap: wrap; align-items: center; }
.tracking-source-content { margin-top: 4px; font-size: 12px; color: #303133; line-height: 1.6; white-space: pre-wrap; }
.tracking-item-flags { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 6px; font-size: 11px; color: #909399; }
.tracking-refresh-note { margin-top: 8px; background: #fff7ed; border: 1px solid #fed7aa; border-radius: 8px; padding: 8px 10px; font-size: 12px; color: #7c2d12; line-height: 1.6; white-space: pre-wrap; }
.severity-none { color: #475569; }
.severity-minor { color: #b45309; }
.severity-material { color: #c2410c; }
.tracking-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.tracking-history { margin-top: 10px; padding-top: 10px; border-top: 1px dashed #e5e7eb; display: flex; flex-direction: column; gap: 8px; }
.tracking-today-history { border-top: none; padding-top: 0; }
.tracking-history-title { font-size: 12px; font-weight: 600; color: #606266; }
.tracking-history-item { background: #f9fafb; border: 1px solid #eef2f7; border-radius: 8px; padding: 8px; }
.tracking-history-item.is-focused { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64, 158, 255, .12); background: #f0f7ff; }
.tracking-history-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tracking-history-time { font-size: 12px; color: #909399; }
.tracking-history-content,
.tracking-history-evidence { margin-top: 6px; font-size: 12px; color: #303133; line-height: 1.6; white-space: pre-wrap; }
.tracking-history-evidence { color: #606266; }
.targeted-thread-list { display: flex; flex-direction: column; gap: 10px; }
.targeted-thread-item { border: 1px solid #ebeef5; border-radius: 8px; padding: 10px; background: #fafbfd; }
.targeted-thread-head,
.targeted-thread-reply-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.targeted-thread-time { font-size: 12px; color: #909399; }
.targeted-thread-question,
.targeted-thread-reply-content { margin-top: 6px; font-size: 12px; color: #303133; line-height: 1.7; white-space: pre-wrap; }
.targeted-thread-reply { margin-top: 8px; padding-top: 8px; border-top: 1px dashed #e5e7eb; }
.targeted-thread-pending { margin-top: 8px; font-size: 12px; color: #e67e22; }
.conclusion-history-list { display: flex; flex-direction: column; gap: 10px; }
.conclusion-history-item {
  border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px; background: #fafcff;
  transition: border-color .2s ease, box-shadow .2s ease, background-color .2s ease;
}
.conclusion-history-item.is-latest { border-color: #95d475; background: #f6ffed; }
.conclusion-history-item.is-focused { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64, 158, 255, .15); }
.conclusion-history-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.conclusion-history-version { font-size: 12px; font-weight: 600; color: #303133; }
.conclusion-history-meta { font-size: 12px; color: #909399; }
.conclusion-history-reason { margin-top: 6px; font-size: 12px; color: #475569; line-height: 1.7; }
.conclusion-history-reopen-links { margin-top: 8px; }
.conclusion-history-conclusion { margin-top: 6px; font-size: 12px; color: #303133; line-height: 1.8; white-space: pre-wrap; }
.conclusion-history-links { margin-top: 8px; padding-top: 8px; border-top: 1px dashed #dbeafe; }
.conclusion-history-links-label { display: block; font-size: 12px; font-weight: 600; color: #1d4ed8; margin-bottom: 4px; }
.conclusion-history-links-actions { margin-bottom: 4px; }
.conclusion-history-links-list { display: flex; flex-wrap: wrap; gap: 8px; }
.summary-text  { font-size: 13px; line-height: 1.8; color: #303133; white-space: pre-wrap; }
.memory-section + .memory-section,
.artifact-section + .artifact-section { margin-top: 12px; }
.memory-title { font-size: 12px; color: #909399; margin-bottom: 6px; font-weight: 600; }
.artifact-list { margin: 0; padding-left: 18px; color: #303133; line-height: 1.7; }
.pre-wrap { white-space: pre-wrap; }
</style>
