<template>
  <div class="stock-watch-page">
    <header class="watch-header">
      <div :class="['header-title-wrap', { detail: isDetail }]">
        <div>
          <p class="eyebrow">Stock Watch</p>
          <h1>{{ isDetail ? `${selectedStock.name} 技术盯盘` : '股票盯盘控制台' }}</h1>
        </div>
      </div>
      <div class="header-actions">
        <span v-if="demoModeActive" class="status-chip warning">演示数据</span>
        <span :class="['status-chip', runtimeSchedulingTone]">{{ runtimeSchedulingLabel }}</span>
        <span class="status-chip neutral">{{ autoRefreshStatusLabel }}</span>
        <span :class="['status-chip', runtimeProviderTone]">{{ runtimeProviderLabel }}</span>
        <button v-if="isDetail" class="plain-button back-button" type="button" @click="backToList">
          ← 返回列表
        </button>
      </div>
    </header>

    <section class="metric-strip">
      <article v-for="metric in metrics" :key="metric.label" class="metric-tile">
        <span>{{ metric.label }}</span>
        <strong :class="metric.tone">{{ metric.value }}</strong>
        <small>{{ metric.note }}</small>
      </article>
    </section>

    <section v-if="!isDetail" class="list-layout">
      <main class="panel watchlist-panel">
        <div class="panel-bar">
          <div>
            <p class="section-label">watchlist.json</p>
            <h2>监控标的</h2>
          </div>
          <div class="panel-tools">
            <button class="plain-button" type="button" :disabled="quoteRefreshing" @click="refreshQuotes('手动')">
              {{ quoteRefreshing ? '刷新中' : '刷新行情' }}
            </button>
            <button class="primary-button" type="button" @click="openAddDialog">
              <span>+</span>
              添加股票
            </button>
            <div class="segmented">
              <button
                v-for="tab in severityTabs"
                :key="tab.value"
                :class="{ active: activeFilter === tab.value }"
                @click="activeFilter = tab.value"
              >
                {{ tab.label }}
              </button>
            </div>
            <div class="segmented group-segmented">
              <button
                v-for="tab in stockGroupTabs"
                :key="tab.value"
                :class="{ active: activeGroupFilter === tab.value }"
                @click="activeGroupFilter = tab.value"
              >
                {{ tab.label }}
              </button>
            </div>
          </div>
        </div>

        <div class="watch-table">
          <div v-if="filteredWatchlist.length === 0" class="empty-state">
            <strong>暂无真实监控标的</strong>
            <span>后端 watchlist 为空。添加股票后才会开始真实行情刷新。</span>
          </div>
          <button
            v-for="stock in filteredWatchlist"
            :key="stock.symbol"
            class="stock-row"
            @click="openDetail(stock.symbol)"
          >
            <span :class="['row-state', stock.severity]" />
            <span class="stock-avatar">
              <img v-if="stock.image" :src="stock.image" :alt="stock.name" />
              <span v-else>{{ stockInitials(stock) }}</span>
            </span>
            <div class="stock-name">
              <strong>{{ stock.name }}</strong>
              <small>{{ stock.symbol }} · {{ stock.market }}</small>
              <span :class="['stock-group-chip', stockGroupTone(stock.groupKey)]">
                {{ stockGroupLabel(stock.groupKey) }}
              </span>
            </div>
            <div class="quote-cell">
              <strong>{{ stock.price }}</strong>
              <small :class="stock.change >= 0 ? 'up' : 'down'">
                {{ stock.change >= 0 ? '+' : '' }}{{ stock.change }}%
              </small>
            </div>
            <div class="signal-cell">
              <strong>{{ stock.status }}</strong>
              <small>{{ stock.rules.map((rule) => rule.type).join(' / ') }}</small>
            </div>
            <div class="tech-cell">
              <span>VWAP {{ stock.technical.vwapDeviation }}</span>
              <span>量比 {{ stock.technical.volumeRatio }}</span>
              <span>RS {{ stock.technical.rsRank }}</span>
            </div>
            <div class="refresh-cell">
              <strong>{{ stock.refresh.source }}</strong>
              <small>{{ stock.refresh.last }} → {{ stock.refresh.next }}</small>
              <small class="analysis-cadence-line">{{ stockAnalysisCadenceLabel(stock) }}</small>
            </div>
          </button>
        </div>
      </main>

      <aside class="side-stack">
        <section class="panel">
          <div class="panel-bar compact">
            <div>
              <p class="section-label">event bus</p>
              <h2>事件流</h2>
            </div>
          </div>
          <button
            v-for="event in events"
            :key="event.eventId"
            class="event-row"
            :disabled="!event.symbol"
            @click="event.symbol ? openDetail(event.symbol) : null"
          >
            <span :class="['event-dot', event.severity]" />
            <div>
              <strong>{{ event.title }}</strong>
              <small>{{ event.stockName || '系统事件' }} · {{ event.time }}</small>
            </div>
          </button>
        </section>

        <section class="panel">
          <div class="panel-bar compact">
            <div>
              <p class="section-label">plugins.json</p>
              <h2>插件闸门</h2>
            </div>
          </div>
          <article v-for="plugin in plugins" :key="plugin.name" class="plugin-row">
            <div>
              <strong>{{ plugin.name }}</strong>
              <small>{{ plugin.desc }}</small>
            </div>
            <button
              class="toggle"
              :class="{ on: plugin.enabled }"
              :aria-label="`${plugin.name} ${plugin.enabled ? '开启' : '关闭'}`"
              @click="plugin.enabled = !plugin.enabled"
            >
              <i />
            </button>
          </article>
          <article v-if="plugins.length === 0" class="plugin-empty-state">
            暂无真实插件闸门配置。
          </article>
        </section>
      </aside>
    </section>

    <section v-else class="detail-layout">
      <section class="panel quote-panel">
        <div class="quote-identity">
          <span class="stock-avatar detail-avatar">
            <img v-if="selectedStock.image" :src="selectedStock.image" :alt="selectedStock.name" />
            <span v-else>{{ stockInitials(selectedStock) }}</span>
          </span>
          <div>
            <p class="section-label">quote snapshot</p>
            <h2>{{ selectedStock.name }} · {{ selectedStock.symbol }}</h2>
            <small>{{ selectedStock.refresh.endpoint }} · {{ selectedStock.refresh.last }} 更新</small>
          </div>
        </div>
        <div class="quote-numbers">
          <strong>{{ selectedStock.price }}</strong>
          <span :class="selectedStock.change >= 0 ? 'up' : 'down'">
            {{ selectedStock.change >= 0 ? '+' : '' }}{{ selectedStock.change }}%
          </span>
        </div>
      </section>

      <main class="panel chart-panel">
        <div class="panel-bar">
          <div>
            <p class="section-label">intraday ohlc</p>
            <h2>真实分钟行情</h2>
          </div>
          <span class="source-badge">{{ chartSourceLabel }}</span>
        </div>

        <div class="price-chart">
          <IntradayBarChart v-if="chartState === 'VALID'" :bars="selectedIntradayBars" />
          <BarUnavailableState v-else :state="chartState" :message="barUnavailableMessage" />
        </div>

        <div class="technical-grid">
          <MetricCard v-for="item in technicalMetrics" :key="item.key" :metric="item" />
        </div>
      </main>

      <section class="panel ai-analysis-panel">
        <div class="panel-bar ai-analysis-head">
          <div>
            <p class="section-label">deepseek web analysis</p>
            <h2>{{ selectedStock.name }} 多周期技术面分析</h2>
            <small class="ai-analysis-subtitle">
              日内每 30 分钟轮询；周维度固定周五收盘复盘；月维度固定月末复盘。每只股票、每个周期各自绑定 DeepSeek 网页会话。
            </small>
          </div>
          <div class="analysis-controls">
            <div class="analysis-period-tabs" role="tablist" aria-label="技术面分析周期">
              <button
                v-for="tab in analysisPeriodTabs"
                :key="tab.value"
                type="button"
                :class="{ active: selectedAnalysisPeriodType === tab.value }"
                @click="switchAnalysisPeriod(tab.value)"
              >
                <span>{{ tab.label }}</span>
                <small>{{ tab.desc }}</small>
              </button>
            </div>
            <button class="plain-button" type="button" @click="selectCurrentAnalysisPeriod">
              {{ selectedAnalysisPeriodType === 'DAY' ? '今天' : (selectedAnalysisPeriodType === 'WEEK' ? '本周' : '本月') }}
            </button>
            <button
              class="primary-button"
              type="button"
              :disabled="technicalAnalysisRunning"
              @click="runTechnicalAnalysisNow"
            >
              <span>↻</span>
              {{ technicalAnalysisRunning ? '分析中' : '立即分析' }}
            </button>
          </div>
        </div>

        <div class="analysis-runtime-grid">
          <article>
            <span>当前周期</span>
            <strong>{{ selectedAnalysisPeriodTab.label }}</strong>
            <small>{{ selectedAnalysisPeriodTab.runtime }}</small>
          </article>
          <article>
            <span>最近执行</span>
            <strong>{{ selectedAnalysisSession.lastRun }}</strong>
            <small>{{ runtimeRetryLabel }}</small>
          </article>
          <article>
            <span>网页会话</span>
            <strong>{{ selectedAnalysisSession.status }}</strong>
            <small>{{ selectedAnalysisSession.sessionId }}</small>
          </article>
        </div>

        <section class="prediction-review">
          <div class="prediction-review-head">
            <div>
              <p class="section-label">prediction audit</p>
              <h3>预测复盘</h3>
              <small>预测和原始验真保持冻结；现行口径复核仅重算方向展示，不改历史概率与 Brier。</small>
            </div>
            <button class="plain-button" type="button" :disabled="predictionLoading" @click="loadPredictionHistory">
              {{ predictionLoading ? '刷新中' : '刷新复盘' }}
            </button>
          </div>
          <div class="prediction-stat-grid">
            <button
              :class="['prediction-stat-card', 'clickable', { active: predictionListMode === 'evaluated' }]"
              type="button"
              @click="showEvaluatedPredictions"
            >
              <span>已验真</span>
              <strong>{{ predictionSummary.evaluated || 0 }}</strong>
              <small>点击查看验真结果 · {{ predictionSummary.pending || 0 }} 条等待到期</small>
            </button>
            <article class="prediction-stat-card">
              <span>现行口径方向复核</span>
              <strong>{{ formatMetricPercent(predictionSummary.currentPolicyDirectionHitRate) }}</strong>
              <small>{{ predictionPolicyCaption }}</small>
              <small>旧 ±1% 对照 {{ formatMetricPercent(predictionSummary.strictDirectionHitRate) }} · {{ predictionSummary.currentPolicyScored || 0 }} 条可复核</small>
            </article>
            <button
              :class="['prediction-stat-card', 'clickable', { active: predictionListMode === 'diverged' }]"
              type="button"
              :aria-pressed="predictionListMode === 'diverged'"
              :disabled="!hasReturnSignDivergence"
              @click="showDivergedPredictions"
            >
              <span>方向背离率 · 收益正负</span>
              <strong>{{ hasReturnSignDivergence ? formatMetricPercent(predictionSummary.directionalDivergenceRate) : '--' }}</strong>
              <template v-if="hasReturnSignDivergence">
                <small>看涨且收益&lt;0 / 看跌且收益&gt;0 才背离；0 不背离</small>
                <small>不背离命中 {{ formatMetricPercent(predictionSummary.directionalNonDivergenceHitRate) }} · {{ predictionSummary.directionalDivergenceCount }} / {{ predictionSummary.directionalForecastScored }} 条背离，点击查看</small>
                <small>仅看涨/看跌已验真 · 最近 {{ predictionRecords.length }} 条预测</small>
              </template>
              <small v-else>等待收益正负口径接口</small>
            </button>
            <article class="prediction-stat-card">
              <span>区间收盘命中</span>
              <strong>{{ formatMetricPercent(predictionSummary.rangeCloseHitRate) }}</strong>
              <small>另保留盘中区间触达指标</small>
            </article>
            <article class="prediction-stat-card">
              <span>Brier 分数</span>
              <strong>{{ formatPredictionNumber(predictionSummary.averageBrierScore, 3) }}</strong>
              <small>{{ predictionSummary.brierPolicyMixed ? '生成口径混合，不展示跨口径均值' : '原始生成口径；越低越好' }}</small>
            </article>
          </div>
          <small v-if="predictionRecords.length > predictionSummary.total" class="prediction-summary-scope">
            上方统计按最近 {{ predictionSummary.total }} 条计算；下方列表已加载 {{ predictionRecords.length }} 条历史预测。
          </small>
          <p v-if="predictionError" class="analysis-inline-error">{{ predictionError }}</p>
          <template v-if="predictionRecords.length">
            <div class="prediction-list-head">
              <div>
                <strong>{{ predictionListTitle }}</strong>
                <small>
                  <template v-if="predictionGroupByDay">已显示 {{ visiblePredictionGroups.length }} / {{ predictionGroups.length }} 天 · 匹配 {{ filteredPredictionRecords.length }} 条</template>
                  <template v-else>{{ visiblePredictionCount }} / {{ filteredPredictionRecords.length }} 条</template>
                  <template v-if="filteredPredictionRecords.length !== predictionRecords.length">
                    · 全部 {{ predictionRecords.length }} 条
                  </template>
                  <template v-if="predictionGroupByDay && hiddenPredictionGroupCount > 0"> · 还有 {{ hiddenPredictionGroupCount }} 天</template>
                  <template v-else-if="hiddenPredictionCount > 0"> · 已折叠 {{ hiddenPredictionCount }} 条</template>
                </small>
              </div>
              <div class="prediction-list-actions">
                <label class="prediction-filter">
                  方向
                  <select v-model="predictionDirectionFilter" aria-label="筛选预测方向" @change="predictionHorizonFilter = 'all'">
                    <option value="all">全部</option>
                    <option value="UP">看涨</option>
                    <option value="DOWN">看跌</option>
                    <option value="SIDEWAYS">震荡</option>
                  </select>
                </label>
                <label v-if="predictionDirectionFilter === 'UP' || predictionDirectionFilter === 'DOWN'" class="prediction-filter">
                  期限
                  <select v-model="predictionHorizonFilter" aria-label="筛选预测期限">
                    <option value="all">全部</option>
                    <option value="T1">T+1</option>
                    <option value="T5">T+5</option>
                  </select>
                </label>
                <button
                  class="plain-button compact"
                  :class="{ active: predictionGroupByDay }"
                  type="button"
                  :aria-pressed="predictionGroupByDay"
                  @click="togglePredictionGroupByDay"
                >
                  {{ predictionGroupByDay ? '逐条查看' : '按天聚合' }}
                </button>
                <button
                  v-if="hasPredictionOverflow"
                  class="plain-button compact"
                  type="button"
                  @click="togglePredictionListExpanded"
                >
                  {{ predictionListExpanded ? '收起' : `显示剩余 ${predictionGroupByDay ? hiddenPredictionGroupCount + ' 天' : hiddenPredictionCount + ' 条'}` }}
                </button>
                <button
                  v-if="predictionListMode !== 'all'"
                  class="plain-button compact"
                  type="button"
                  @click="showAllPredictions"
                >
                  查看全部
                </button>
              </div>
            </div>
            <div v-if="filteredPredictionRecords.length" class="prediction-list" :class="{ 'by-day': predictionGroupByDay }">
              <div
                v-for="group in visiblePredictionGroups"
                :key="group.key"
                :class="predictionGroupByDay ? 'prediction-day-card' : 'prediction-single-group'"
              >
                <button
                  v-if="predictionGroupByDay"
                  class="prediction-day-toggle"
                  type="button"
                  :aria-expanded="expandedPredictionDates.includes(group.date)"
                  :aria-controls="expandedPredictionDates.includes(group.date) ? `prediction-day-${group.date}` : undefined"
                  @click="togglePredictionDay(group.date)"
                >
                  <span class="prediction-day-heading">
                    <strong>{{ group.date }}</strong>
                    <small>{{ group.predictions.length }} 条预测 · {{ predictionDaySummary(group.predictions) }}</small>
                  </span>
                  <span class="prediction-day-expand">{{ expandedPredictionDates.includes(group.date) ? '收起' : '展开' }} <span aria-hidden="true">{{ expandedPredictionDates.includes(group.date) ? '⌃' : '⌄' }}</span></span>
                </button>
                <div
                  v-if="!predictionGroupByDay || expandedPredictionDates.includes(group.date)"
                  :id="predictionGroupByDay ? `prediction-day-${group.date}` : undefined"
                  :class="predictionGroupByDay ? 'prediction-day-details' : 'prediction-flat-records'"
                >
                  <article v-for="prediction in group.predictions" :key="prediction.id" class="prediction-card">
                    <div class="prediction-card-top">
                      <span :class="['prediction-direction', predictionDirectionTone(prediction.direction)]">
                        {{ prediction.horizon }} · {{ predictionDirectionLabel(prediction.direction) }}
                      </span>
                      <span :class="['prediction-status', prediction.status === 'EVALUATED' && prediction.currentPolicyDirectionHit != null ? (prediction.currentPolicyDirectionHit ? 'hit' : 'miss') : 'pending']">
                        {{ predictionListMode === 'diverged' ? '方向背离' : predictionStatusLabel(prediction) }}
                      </span>
                    </div>
                    <strong>{{ prediction.targetLow }} – {{ prediction.targetHigh }}</strong>
                    <p>起点 {{ prediction.sourcePrice }} · 概率 {{ formatMetricPercent((prediction.probability || 0) * 100) }} · 失效线 {{ prediction.invalidationPrice || '--' }}</p>
                    <small>生成时间 {{ prediction.generatedAt || `${prediction.forecastDate} --:--` }}</small>
                    <small v-if="prediction.status === 'EVALUATED'">
                      {{ prediction.evaluatedTradeDate }} 收盘 {{ prediction.actualClose }}，实际收益 {{ formatSignedPercent(finiteOrNull(prediction.actualReturnPct) ?? prediction.directionalReviewReturnPct) }}
                      <template v-if="finiteOrNull(prediction.actualReturnPct) == null && prediction.directionalReviewReturnPct != null">（据收盘价计算）</template>
                      <template v-if="prediction.evaluatedAt"> · 验真时间 {{ prediction.evaluatedAt }}</template>
                    </small>
                    <small v-if="prediction.status === 'EVALUATED' && prediction.directionHit != null && prediction.currentPolicyDirectionHit != null && prediction.currentPolicyDirectionHit !== prediction.directionHit">
                      原始验真{{ prediction.directionHit ? '命中' : '未中' }}（生成时口径）；上方为现行口径复核
                    </small>
                    <small v-if="prediction.directionDiverged != null">
                      收益正负口径：{{ prediction.directionDiverged ? '方向背离' : '不背离（命中）' }}
                    </small>
                    <small v-if="prediction.status !== 'EVALUATED'">等待第 {{ prediction.tradingDays }} 个后续交易日</small>
                  </article>
                </div>
              </div>
            </div>
            <article v-else class="prediction-empty">{{ predictionListEmptyText }}</article>
          </template>
          <article v-else-if="!predictionLoading" class="prediction-empty">完成一次新版分析后，这里会出现 T+1 / T+5 或周月预测。</article>
        </section>
        <p v-if="technicalAnalysisError" class="analysis-inline-error">{{ technicalAnalysisError }}</p>
        <p v-else-if="technicalAnalysisLoading" class="analysis-inline-note">正在加载历史结论...</p>

        <div class="analysis-workspace">
          <aside class="analysis-calendar" aria-label="技术面分析周期列表">
            <div class="analysis-calendar-head">
              <div>
                <strong>{{ selectedAnalysisPeriodTab.windowLabel }}</strong>
                <small>{{ selectedAnalysisPeriodKey }} · {{ selectedAnalysisRecords.length }} 条结论</small>
              </div>
            </div>
            <button
              v-for="period in analysisPeriods"
              :key="period.key"
              :class="['analysis-day-button', { active: selectedAnalysisPeriodKey === period.key, empty: period.count === 0 }]"
              type="button"
              @click="selectAnalysisPeriod(period)"
            >
              <span>{{ period.label }}</span>
              <small>{{ period.sublabel }}</small>
              <em>{{ period.count ? `${period.count} 条` : '无' }}</em>
            </button>
          </aside>

          <main class="analysis-result-stack">
            <article
              v-for="record in selectedAnalysisRecords"
              :key="record.id"
              class="analysis-record-card"
            >
              <div class="analysis-record-top">
                <span :class="['analysis-stance', record.tone]">{{ record.stance }}</span>
                <small>{{ record.time }} · {{ record.provider }} · 置信度 {{ record.confidence }}</small>
              </div>
              <strong>{{ record.title }}</strong>
              <p>{{ record.conclusion }}</p>
              <M30ForecastLine :forecast="record.intradayForecast" />
              <div v-if="record.mainForceIntent" class="analysis-main-force">
                <div>
                  <strong>{{ record.mainForceIntent.provenanceLabel }}</strong>
                  <span>{{ record.mainForceIntent.intentLabel }} · {{ record.mainForceIntent.timeWindowLabel }} · 置信度 {{ record.mainForceIntent.confidence }}</span>
                </div>
                <p v-if="record.mainForceIntent.risk">{{ record.mainForceIntent.risk }}</p>
                <div v-if="record.mainForceIntent.watchPoints.length" class="analysis-main-force-points">
                  <span v-for="item in record.mainForceIntent.watchPoints" :key="item">{{ item }}</span>
                </div>
              </div>
              <div v-if="record.riskPatternSummary" class="analysis-risk-pattern">
                <div>
                  <strong>{{ record.riskPatternSummary.title }}</strong>
                  <span>{{ record.riskPatternSummary.statusLabel }}</span>
                </div>
                <p v-if="record.riskPatternSummary.conclusionText" class="analysis-risk-pattern-conclusion">
                  {{ record.riskPatternSummary.conclusionText }}
                </p>
                <p v-if="record.riskPatternSummary.valueText">
                  {{ record.riskPatternSummary.valueText }}
                </p>
                <div v-if="record.riskPatternSummary.visiblePatterns.length" class="analysis-risk-pattern-tags">
                  <div v-for="item in record.riskPatternSummary.visiblePatterns" :key="item.key">
                    <span>{{ item.label }}</span>
                    <small v-if="item.description">{{ item.description }}</small>
                    <small v-if="item.invalidatesWhen">观察：{{ item.invalidatesWhen }}</small>
                  </div>
                </div>
                <p v-if="record.riskPatternSummary.nextWatch" class="analysis-risk-pattern-note">
                  下一步观察：{{ record.riskPatternSummary.nextWatch }}
                </p>
                <p v-if="record.riskPatternSummary.dataLimitText" class="analysis-risk-pattern-note">
                  数据限制：{{ record.riskPatternSummary.dataLimitText }}
                </p>
                <p class="analysis-risk-pattern-boundary">{{ record.riskPatternSummary.semanticBoundary }}</p>
              </div>
              <div class="analysis-evidence">
                <span v-for="item in record.evidence" :key="item">{{ item }}</span>
              </div>
              <div v-if="record.missingData.length" class="analysis-missing-data">
                <strong>可选数据缺口</strong>
                <span v-for="item in record.missingData" :key="item">{{ item }}</span>
              </div>
              <footer class="analysis-record-footer">
                <small>{{ record.promptHash }}</small>
                <button class="plain-button" type="button" @click="openDeepseekSession(record)">
                  {{ record.traceable || selectedAnalysisSession.traceable ? '查看原会话' : '会话待接入' }}
                </button>
              </footer>
            </article>

            <article v-if="selectedAnalysisRecords.length === 0" class="analysis-empty">
              <strong>这个周期暂无技术面分析</strong>
              <span>完成一次调度或点击“立即分析”后，这里会按日/周/月展示 DeepSeek 网页会话产出的历史结论。</span>
            </article>
          </main>
        </div>
      </section>

      <section class="panel stock-config-panel">
        <div class="panel-bar config-head">
          <div>
            <p class="section-label">single stock config</p>
            <h2>{{ selectedStock.name }} 单股配置中心</h2>
            <small class="config-subtitle">
              行情抓取、LLM 分析、FC卡、触发条件、邮件推送都按当前股票单独配置；邮件推送是最后的通知出口。
            </small>
          </div>
          <div class="config-actions">
            <button
              v-if="activeConfigSupportsPersistence"
              class="plain-button"
              type="button"
              :disabled="activeConfigLoading"
              @click="refreshActiveConfig"
            >
              {{ activeConfigRefreshLabel }}
            </button>
            <button
              v-if="activeConfigSupportsPersistence"
              class="primary-button"
              type="button"
              :disabled="activeConfigSaving"
              @click="saveActiveConfig"
            >
              {{ activeConfigSaveLabel }}
            </button>
          </div>
        </div>

        <div class="config-tabbar">
          <button
            v-for="tab in configTabs"
            :key="tab.value"
            type="button"
            :class="{ active: activeConfigTab === tab.value }"
            @click="activeConfigTab = tab.value"
          >
            <span>{{ tab.kicker }}</span>
            <strong>{{ tab.label }}</strong>
            <small>{{ tab.desc }}</small>
          </button>
        </div>

        <div v-if="activeConfigTab === 'mail' && deliveryError" class="config-alert error">{{ deliveryError }}</div>
        <div v-else-if="activeConfigTab === 'mail' && deliveryNotice" class="config-alert success">{{ deliveryNotice }}</div>
        <div v-if="activeConfigTab === 'llm' && analysisPolicyError" class="config-alert error">{{ analysisPolicyError }}</div>
        <div v-else-if="activeConfigTab === 'llm' && analysisPolicyNotice" class="config-alert success">{{ analysisPolicyNotice }}</div>

        <div v-if="activeConfigTab === 'quote'" class="config-section">
          <div class="config-summary-grid">
            <article>
              <span>行情源</span>
              <strong>东方财富</strong>
              <small>{{ selectedStock.refresh.endpoint }} · {{ selectedStock.refresh.last }} 更新</small>
            </article>
            <article>
              <span>刷新节奏</span>
              <strong>10 分钟</strong>
              <small>交易时段自动刷新，非交易时段保留最后快照</small>
            </article>
            <article>
              <span>字段校验</span>
              <strong>{{ selectedStock.sources.filter((item) => item.ok).length }}/{{ selectedStock.sources.length }}</strong>
              <small>失败源会保留 RETRY，不阻塞邮件配置</small>
            </article>
          </div>
        </div>

        <div v-else-if="activeConfigTab === 'external'" class="config-section external-evidence-section">
          <div class="external-evidence-toolbar">
            <div>
              <strong>TradingView 外部研究证据</strong>
              <small>手动交叉校验当前股票，不进入异动触发、调度任务或邮件发送链路。</small>
            </div>
            <button class="primary-button" type="button" :disabled="externalEvidenceLoading" @click="loadExternalEvidence(true)">
              {{ externalEvidenceLoading ? '刷新中' : '刷新外部证据' }}
            </button>
          </div>

          <div v-if="externalEvidenceError" class="config-alert error">{{ externalEvidenceError }}</div>
          <div v-else-if="externalEvidenceNotice" class="config-alert success">{{ externalEvidenceNotice }}</div>

          <article v-if="externalEvidenceLoading && !externalEvidence" class="external-evidence-empty">
            正在拉取 TradingView 与 Eastmoney 交叉校验结果...
          </article>
          <article v-else-if="!externalEvidence" class="external-evidence-empty">
            点击“刷新外部证据”后，这里会展示 TradingView 技术指标、Eastmoney 主链行情和单位归一后的偏差。
          </article>

          <template v-else>
            <div class="config-summary-grid external-summary-grid">
              <article>
                <span>交叉校验</span>
                <strong :class="externalEvidence.status === 'MATCH' ? 'green' : 'amber'">
                  {{ externalEvidence.status || '--' }}
                </strong>
                <small>{{ externalEvidence.comparison?.summary || '暂无对比结论' }}</small>
              </article>
              <article>
                <span>TradingView</span>
                <strong>{{ externalEvidence.tradingView?.ok ? '可用' : '不可用' }}</strong>
                <small>{{ externalEvidence.tradingView?.tvSymbol || externalEvidence.tradingView?.symbol || '--' }}</small>
              </article>
              <article>
                <span>Eastmoney</span>
                <strong>{{ externalEvidence.eastmoney?.ok ? '可用' : '不可用' }}</strong>
                <small>{{ externalEvidence.eastmoney?.updateTime || externalEvidence.eastmoney?.reason || '--' }}</small>
              </article>
            </div>

            <div class="external-compare-grid">
              <article v-for="row in externalCompareRows" :key="row.label" class="external-compare-card">
                <span>{{ row.label }}</span>
                <div>
                  <strong>{{ row.tradingView }}</strong>
                  <small>TradingView</small>
                </div>
                <div>
                  <strong>{{ row.eastmoney }}</strong>
                  <small>Eastmoney</small>
                </div>
                <em :class="row.matched ? 'ok' : 'warn'">{{ row.diff }}</em>
              </article>
            </div>

            <div class="external-field-grid">
              <article v-for="field in externalTradingViewFields" :key="field.label">
                <span>{{ field.label }}</span>
                <strong>{{ field.value }}</strong>
                <small>{{ field.note }}</small>
              </article>
            </div>

            <section class="external-boundary-panel">
              <div>
                <strong>接入边界</strong>
                <small>{{ externalEvidence.checkedAt || '--' }}</small>
              </div>
              <span v-for="item in externalEvidence.boundary || []" :key="item">{{ item }}</span>
              <span v-if="externalEvidence.comparison?.volumeUnitNote">{{ externalEvidence.comparison.volumeUnitNote }}</span>
              <span v-for="warning in externalEvidence.warnings || []" :key="warning" class="warn">{{ warning }}</span>
            </section>
          </template>
        </div>

        <div v-else-if="activeConfigTab === 'llm'" class="config-section llm-policy-section">
          <div class="config-summary-grid">
            <article>
              <span>分析节奏</span>
              <strong>{{ selectedAnalysisCadenceLabel }}</strong>
              <small>{{ selectedAnalysisPolicySubtitle }}</small>
            </article>
            <article>
              <span>异动升级</span>
              <strong>{{ selectedAnomalyTriggerLabel }}</strong>
              <small>命中后由统一分析需求决定是否调用 DeepSeek</small>
            </article>
            <article>
              <span>下次计划</span>
              <strong>{{ formatPolicyTime(selectedAnalysisSchedule.nextDueAt) }}</strong>
              <small>{{ selectedAnalysisSchedule.reason || selectedAnalysisSchedule.requestedCadence }}</small>
            </article>
          </div>

          <section class="analysis-policy-editor">
            <div class="analysis-policy-head">
              <div>
                <p class="section-label">analysis policy</p>
                <h3>单股 DeepSeek 分析策略</h3>
                <small>技术面分析与结构化异动解读共用同一条分析需求；邮件只是最后通知出口，不再决定是否分析。</small>
              </div>
              <span class="policy-version-chip" :class="{ draft: !selectedAnalysisPolicyPersisted }">
                {{ analysisPolicyVersionLabel }}
              </span>
            </div>

            <div v-if="!selectedAnalysisPolicyPersisted" class="policy-boundary-note policy-draft-note">
              <span>未启用自动分析</span>
              <p>
                当前股票还没有后端分析策略记录。下面只是待保存草案；只有点击“加入并保存策略”后，才会创建后端监控项并进入自动调度候选。
              </p>
            </div>

            <div class="policy-mode-grid">
              <button
                v-for="option in ANALYSIS_MODE_OPTIONS"
                :key="option.value"
                type="button"
                :class="{ active: analysisPolicyForm.mode === option.value }"
                @click="setAnalysisMode(option.value)"
              >
                <strong>{{ option.label }}</strong>
                <small>{{ option.desc }}</small>
              </button>
            </div>

            <div class="policy-form-grid">
              <label class="policy-field">
                <span>自定义间隔（分钟）</span>
                <input
                  v-model.number="analysisPolicyForm.intervalMinutes"
                  type="number"
                  min="10"
                  max="1440"
                  step="10"
                  :disabled="analysisPolicyForm.mode !== 'INTERVAL'"
                  @change="clampAnalysisInterval"
                />
                <small>支持 10 到 1440 分钟。非 INTERVAL 模式下服务端会忽略该值。</small>
              </label>

              <div class="interval-preset-row" :class="{ disabled: analysisPolicyForm.mode !== 'INTERVAL' }">
                <button
                  v-for="minutes in ANALYSIS_INTERVAL_PRESETS"
                  :key="minutes"
                  type="button"
                  :disabled="analysisPolicyForm.mode !== 'INTERVAL'"
                  :class="{ active: analysisPolicyForm.intervalMinutes === minutes }"
                  @click="setAnalysisInterval(minutes)"
                >
                  {{ intervalPresetLabel(minutes) }}
                </button>
              </div>

              <label class="policy-field policy-field-wide">
                <span>异动命中后的处理</span>
                <select v-model="analysisPolicyForm.anomalyTriggerMode" :disabled="analysisPolicyForm.mode === 'PAUSED'">
                  <option
                    v-for="option in ANALYSIS_ANOMALY_TRIGGER_OPTIONS"
                    :key="option.value"
                    :value="option.value"
                  >
                    {{ option.label }}
                  </option>
                </select>
                <small>{{ anomalyTriggerDescription(analysisPolicyForm.anomalyTriggerMode) }}</small>
              </label>

              <label :class="['policy-check-card', { disabled: !analysisReviewTogglesEnabled }]">
                <input
                  v-model="analysisPolicyForm.weeklyReviewEnabled"
                  type="checkbox"
                  :disabled="!analysisReviewTogglesEnabled"
                />
                <span>
                  <strong>保留周复盘</strong>
                  <small>周度复盘仍可作为低频上下文补充，不受日内间隔影响。</small>
                </span>
              </label>

              <label :class="['policy-check-card', { disabled: !analysisReviewTogglesEnabled }]">
                <input
                  v-model="analysisPolicyForm.monthlyReviewEnabled"
                  type="checkbox"
                  :disabled="!analysisReviewTogglesEnabled"
                />
                <span>
                  <strong>保留月复盘</strong>
                  <small>月末统一复盘长期趋势；暂停/仅异动模式下关闭。</small>
                </span>
              </label>
            </div>

            <div class="policy-boundary-note">
              <span>运行边界</span>
              <p>
                保存后只影响自动需求创建；已经提交到 DeepSeek 的任务不回滚。若服务端策略版本已变化，保存会被 409 拒绝，需刷新后重试。
              </p>
            </div>
          </section>

          <div class="config-summary-grid analysis-session-grid">
            <article>
              <span>会话状态</span>
              <strong>{{ selectedAnalysisSession.status }}</strong>
              <small>{{ selectedAnalysisSession.sessionId }}</small>
            </article>
            <article>
              <span>上下文轮换</span>
              <strong>{{ selectedAnalysisSession.contextUsage }}%</strong>
              <small>{{ selectedAnalysisSession.rotatePolicy }}</small>
            </article>
            <article>
              <span>容量状态</span>
              <strong>{{ selectedAnalysisSchedule.capacityStatus }}</strong>
              <small>{{ selectedAnalysisSchedule.currentSlotKey || '当前无固定 slot' }}</small>
            </article>
          </div>

          <section class="observation-inline-panel">
            <div class="observation-inline-head">
              <div>
                <p class="section-label">timeline</p>
                <h3>{{ selectedStock.name }} 观察更新</h3>
                <small>只在 LLM 分析页签展示，避免和 FC / 邮件 / 行情配置混在一起。</small>
              </div>
              <button class="primary-button" type="button" @click="openObservationDialog">
                <span>+</span>
                添加观察
              </button>
            </div>
            <article v-if="selectedEvents.length === 0" class="event-card observation-empty-card">
              暂无观察记录。点击“添加观察”记录人工判断或 LLM 复盘结论。
            </article>
            <article v-for="event in selectedEvents" :key="event.eventId" class="event-card">
              <div class="event-card-top">
                <span :class="['event-badge', event.severity]">{{ event.severity }}</span>
                <div class="event-card-actions">
                  <small>{{ event.time }} · {{ event.source }}</small>
                  <button
                    class="icon-button event-delete-button"
                    type="button"
                    :aria-label="`删除 ${event.title}`"
                    title="删除"
                    @click="deleteEvent(event)"
                  >
                    <el-icon><Delete /></el-icon>
                  </button>
                </div>
              </div>
              <strong>{{ event.title }}</strong>
              <p>{{ event.desc }}</p>
              <small class="mono">{{ event.eventId }}</small>
            </article>
          </section>
        </div>

        <div v-else-if="activeConfigTab === 'fc'" class="config-section fc-section">
          <div class="fc-toolbar">
            <div>
              <strong>{{ selectedStock.name }} FC 论点证伪卡</strong>
              <small>每张卡归属于当前股票。导入 Markdown 时，其他股票的卡会被忽略，不会混入本详情页。</small>
            </div>
            <div class="fc-toolbar-actions">
              <button class="plain-button" type="button" :disabled="fcCardsLoading" @click="loadFcCards">
                {{ fcCardsLoading ? '刷新中' : '刷新FC卡' }}
              </button>
              <button class="plain-button" type="button" @click="openFcManualDialog">
                手动新增
              </button>
              <button class="primary-button" type="button" @click="openFcImportDialog">
                导入Markdown
              </button>
            </div>
          </div>

          <div class="fc-stat-grid">
            <article>
              <span>FC卡总数</span>
              <strong>{{ fcCardStats.total }}</strong>
              <small>当前股票独立配置</small>
            </article>
            <article>
              <span>已激活</span>
              <strong>{{ fcCardStats.active }}</strong>
              <small>满足七字段、基线和采集器闸门</small>
            </article>
            <article>
              <span>草稿/阻塞</span>
              <strong>{{ fcCardStats.draft }} / {{ fcCardStats.blocked }}</strong>
              <small>未完成基线或执行验证</small>
            </article>
            <article>
              <span>人工卡</span>
              <strong>{{ fcCardStats.manual }}</strong>
              <small>不伪装自动，进入提醒队列</small>
            </article>
          </div>

          <div v-if="fcCardsError" class="config-alert error">{{ fcCardsError }}</div>
          <div v-else-if="fcCardsNotice" class="config-alert success">{{ fcCardsNotice }}</div>

          <article v-if="fcCardsLoading" class="fc-empty-card">
            正在加载当前股票FC卡...
          </article>

          <article v-else-if="selectedFcCards.length === 0" class="fc-empty-card">
            <strong>当前股票暂无FC卡</strong>
            <span>可以“手动新增”一张草稿卡，也可以“导入Markdown”批量解析。基线未采前不会激活。</span>
          </article>

          <div v-else class="fc-workspace">
            <aside class="fc-card-list">
              <button
                v-for="card in selectedFcCards"
                :key="card.id"
                type="button"
                :class="['fc-card-button', { active: selectedFcCard?.id === card.id }]"
                @click="selectFcCard(card)"
              >
                <div class="fc-card-button-top">
                  <strong>{{ card.cardKey }}</strong>
                  <span :class="['fc-pill', fcStatusTone(card.activationStatus)]">{{ fcStatusLabel(card.activationStatus) }}</span>
                </div>
                <h3>{{ card.cardTitle }}</h3>
                <p>{{ card.triggerLogic || '触发逻辑待填写' }}</p>
                <div class="fc-card-tags">
                  <span :class="['fc-pill', card.dataSourceTier ? 'violet' : 'gray']">{{ card.dataSourceTier || '未分层' }}</span>
                  <span :class="['fc-pill', fcCollectionTone(card.collectionMethod)]">{{ fcCollectionLabel(card.collectionMethod) }}</span>
                  <span class="fc-pill gray">{{ card.frequency || '未设频率' }}</span>
                </div>
              </button>
            </aside>

            <main v-if="selectedFcCard" class="fc-detail">
              <div class="fc-detail-hero">
                <div>
                  <span class="section-label">fc thesis card</span>
                  <h3>{{ selectedFcCard.cardTitle }}</h3>
                  <p>{{ selectedFcCard.thesis || '论点待填写' }}</p>
                </div>
                <article>
                  <span>激活状态</span>
                  <strong>{{ fcStatusLabel(selectedFcCard.activationStatus) }}</strong>
                  <small>{{ selectedFcCard.activationBlockers.length ? selectedFcCard.activationBlockers.join(' / ') : '已进入监控队列' }}</small>
                </article>
              </div>

              <div class="fc-field-grid">
                <article v-for="field in selectedFcCardFields" :key="field.no" class="fc-field-card">
                  <div>
                    <span>{{ field.no }} {{ field.label }}</span>
                    <em>{{ field.status }}</em>
                  </div>
                  <p>{{ field.value || '待填写' }}</p>
                </article>
              </div>

              <section class="fc-gate">
                <div class="fc-gate-head">
                  <div>
                    <strong>激活闸门</strong>
                    <small>填满字段不等于可执行；基线和采集器通过后才进入监控。</small>
                  </div>
                  <span :class="['fc-pill', fcStatusTone(selectedFcCard.activationStatus)]">
                    {{ fcStatusLabel(selectedFcCard.activationStatus) }}
                  </span>
                </div>
                <div class="fc-gate-grid">
                  <article>
                    <span>七字段</span>
                    <strong>{{ selectedFcCardFields.filter((field) => field.value).length }}/7</strong>
                  </article>
                  <article>
                    <span>来源层级</span>
                    <strong>{{ selectedFcCard.dataSourceTier || '未分层' }}</strong>
                  </article>
                  <article>
                    <span>阻塞项</span>
                    <strong>{{ selectedFcCard.activationBlockers.length }}</strong>
                  </article>
                </div>
                <ul v-if="selectedFcCard.activationBlockers.length" class="fc-blockers">
                  <li v-for="blocker in selectedFcCard.activationBlockers" :key="blocker">{{ blocker }}</li>
                </ul>
              </section>

              <section class="fc-baseline-editor">
                <div>
                  <strong>人工基线 / 失效监控</strong>
                  <small>T3 或半自动卡必须如实录入；采不到基线就保持草稿。</small>
                </div>
                <label>
                  <span>基线读数</span>
                  <textarea v-model.trim="fcEditForm.baselineReading" rows="3" placeholder="例如：当前已覆盖区域 + 最近降幅；或最近一期国内收入同比" />
                </label>
                <label>
                  <span>失效监控</span>
                  <textarea v-model.trim="fcEditForm.failureMonitor" rows="3" placeholder="例如：披露日后3个交易日未录入 → 提醒" />
                </label>
                <div class="fc-baseline-row">
                  <label>
                    <span>采集状态</span>
                    <select v-model="fcEditForm.lastCollectStatus">
                      <option value="PENDING">待验证</option>
                      <option value="SUCCESS">已验收</option>
                      <option value="FAILED">失败</option>
                    </select>
                  </label>
                  <label>
                    <span>采集备注</span>
                    <input v-model.trim="fcEditForm.lastCollectNote" placeholder="接口字段已核对 / 页面路径已确认" />
                  </label>
                </div>
                <button class="primary-button" type="button" :disabled="fcCardsSaving" @click="saveFcCardBaseline">
                  {{ fcCardsSaving ? '保存中' : '保存基线并重算闸门' }}
                </button>
              </section>
            </main>
          </div>
        </div>

        <div v-else-if="activeConfigTab === 'mail'" class="config-section delivery-grid">
          <div class="delivery-main">
            <div class="stock-form config-form">
              <label class="span-2">
                <span>收件邮箱 <em>必填，多个邮箱用逗号/空格/换行分隔</em></span>
                <textarea v-model="deliveryForm.recipientsText" placeholder="1450561799@qq.com&#10;research@example.com" />
              </label>

              <label class="check-field">
                <input v-model="deliveryForm.enabled" type="checkbox" />
                <span>启用当前股票邮件推送</span>
              </label>

              <label>
                <span>发送频率</span>
                <select v-model="deliveryForm.frequencyType">
                  <option value="MANUAL">仅手动发送</option>
                  <option value="IMMEDIATE">新结论立即发送</option>
                  <option value="INTERVAL">自定义间隔发送</option>
                  <option value="DAILY_CLOSE">每日收盘后发送</option>
                </select>
              </label>

              <label v-if="deliveryForm.frequencyType === 'INTERVAL'" class="span-2">
                <span>间隔分钟 <em>2 小时 = 120 分钟</em></span>
                <input v-model.number="deliveryForm.intervalMinutes" type="number" min="5" max="1440" step="5" />
                <div class="quick-intervals">
                  <button type="button" @click="setDeliveryInterval(30)">30 分钟</button>
                  <button type="button" @click="setDeliveryInterval(60)">1 小时</button>
                  <button type="button" @click="setDeliveryInterval(120)">2 小时</button>
                  <button type="button" @click="setDeliveryInterval(240)">4 小时</button>
                </div>
              </label>

              <label>
                <span>最小置信度</span>
                <input v-model.number="deliveryForm.minConfidence" type="number" min="0" max="1" step="0.05" />
              </label>

              <label>
                <span>关键词触发 <em>可为空</em></span>
                <input v-model="deliveryForm.keywordsText" placeholder="突破, 跌破, 支撑失守" />
              </label>

              <div class="span-2 tone-selector">
                <span>结论类型过滤 <em>不选则发送所有新结论</em></span>
                <button
                  v-for="tone in deliveryToneOptions"
                  :key="tone.value"
                  type="button"
                  :class="[{ active: deliveryForm.tones.includes(tone.value) }, tone.value]"
                  @click="toggleDeliveryTone(tone.value)"
                >
                  {{ tone.label }}
                </button>
              </div>
            </div>

            <div class="delivery-buttons">
              <button class="plain-button" type="button" :disabled="deliveryPreviewLoading" @click="previewDeliveryConfig(true)">
                {{ deliveryPreviewLoading ? '预览中' : '预览未发送结论' }}
              </button>
              <button class="plain-button" type="button" :disabled="deliveryTesting" @click="testDeliveryEmail">
                {{ deliveryTesting ? '发送中' : '测试邮件' }}
              </button>
              <button class="primary-button" type="button" :disabled="deliveryDispatching" @click="dispatchDeliveryNow">
                {{ deliveryDispatching ? '发送中' : '立即发送未发送结论' }}
              </button>
            </div>
          </div>

          <aside class="delivery-side">
            <article>
              <span>当前频率</span>
              <strong>{{ deliveryFrequencyLabel }}</strong>
              <small>调度器会按该频率扫描，但只发送未成功发送过的分析结论。</small>
            </article>
            <article>
              <span>收件人</span>
              <strong>{{ deliveryRecipients.length }}</strong>
              <small>{{ deliveryRecipients.length ? deliveryRecipients.join(' / ') : '尚未填写邮箱' }}</small>
            </article>
            <article>
              <span>发送日志</span>
              <strong>{{ deliveryStats.SENT || 0 }} SENT</strong>
              <small>{{ deliveryStats.PENDING || 0 }} pending · {{ deliveryStats.FAILED || 0 }} failed</small>
            </article>
          </aside>

          <div class="delivery-preview span-2">
            <div class="delivery-preview-head">
              <div>
                <strong>未发送结论预览</strong>
                <small>按 analysis_id + recipient_hash 去重；服务重启也不会重复发送已成功投递的结论。</small>
              </div>
              <span>{{ deliveryPreview.length }} 条</span>
            </div>
            <article v-if="deliveryPreview.length === 0" class="delivery-empty">
              暂无符合条件且未发送过的分析结论。
            </article>
            <article v-for="record in deliveryPreview" :key="record.id" class="delivery-preview-row">
              <div>
                <strong>{{ record.title }}</strong>
                <small>#{{ record.id }} · {{ record.date }} {{ record.time }} · 待发送收件人 {{ record.pendingRecipients }}</small>
              </div>
              <small v-if="record.mainForceIntent" class="delivery-preview-intent">
                {{ record.mainForceIntent.provenanceLabel }}：{{ record.mainForceIntent.intentLabel }} · {{ record.mainForceIntent.timeWindowLabel }}
              </small>
              <p>{{ record.conclusion }}</p>
            </article>
          </div>
        </div>

        <div v-else class="config-section">
          <div class="config-summary-grid">
            <article>
              <span>价格规则</span>
              <strong>{{ selectedStock.rules.length }}</strong>
              <small>{{ selectedStock.rules.map((rule) => rule.type).join(' / ') || '暂无' }}</small>
            </article>
            <article>
              <span>AI观察条件</span>
              <strong>{{ selectedAiRules.length }}</strong>
              <small>命中后仍由邮件配置决定是否发送</small>
            </article>
            <article>
              <span>邮件触发</span>
              <strong>{{ deliveryTriggerSummary }}</strong>
              <small>可按 tone、关键词、置信度过滤</small>
            </article>
          </div>
        </div>
      </section>

      <aside class="side-stack">
        <section class="panel">
          <div class="panel-bar compact">
            <div>
              <p class="section-label">signal matrix</p>
              <h2>技术信号矩阵</h2>
            </div>
          </div>
          <article v-for="group in signalMatrix" :key="group.name" class="signal-row">
            <div>
              <strong>{{ group.name }}</strong>
              <small>{{ group.desc }}</small>
            </div>
            <span :class="['signal-grade', group.tone]">{{ group.grade }}</span>
          </article>
        </section>

        <section class="panel deepseek-session-panel">
          <div class="panel-bar compact">
            <div>
              <p class="section-label">web session</p>
              <h2>DeepSeek 会话溯源</h2>
            </div>
          </div>
          <article class="session-card">
            <div class="session-card-top">
              <strong>{{ selectedAnalysisSession.title }}</strong>
              <span>{{ selectedAnalysisSession.status }}</span>
            </div>
            <small>每只股票独立会话，便于回到网页版核对原始上下文。</small>
            <p v-if="!selectedAnalysisSession.traceable" class="session-notice">
              当前会话还没有可定位 URL；完成一次真实分析后，若 DeepSeek 网页返回了会话地址，这里会变成可打开链接。
            </p>
            <dl>
              <div>
                <dt>会话 ID</dt>
                <dd>{{ selectedAnalysisSession.sessionId }}</dd>
              </div>
              <div>
                <dt>上下文占用</dt>
                <dd>{{ selectedAnalysisSession.contextUsage }}%</dd>
              </div>
              <div>
                <dt>换会话策略</dt>
                <dd>{{ selectedAnalysisSession.rotatePolicy }}</dd>
              </div>
            </dl>
            <div class="session-meter" aria-label="DeepSeek 网页会话上下文占用">
              <i :style="{ width: `${selectedAnalysisSession.contextUsage}%` }" />
            </div>
            <button class="plain-button" type="button" @click="openDeepseekSession">
              {{ selectedAnalysisSession.traceable ? '打开网页版' : '会话待接入' }}
            </button>
            <small v-if="deepseekSessionNotice" class="session-click-notice">{{ deepseekSessionNotice }}</small>
          </article>
        </section>

        <section class="panel source-panel">
          <div class="panel-bar compact">
            <div>
              <p class="section-label">data source</p>
              <h2>数据源状态</h2>
              <small class="source-hint">只表示抓取是否成功、数据是否新鲜、字段是否匹配；不代表股票基本面健康。</small>
            </div>
          </div>
          <article v-for="source in selectedStock.sources" :key="source.name" class="source-row">
            <div>
              <strong>{{ source.name }}</strong>
              <span>{{ source.desc }}</span>
            </div>
            <em :class="source.ok ? 'ok' : 'warn'">{{ source.ok ? 'OK' : 'RETRY' }}</em>
          </article>
        </section>

        <section class="panel">
          <div class="panel-bar compact">
            <div>
              <p class="section-label">rule status</p>
              <h2>规则状态</h2>
            </div>
          </div>
          <article v-for="rule in selectedStock.rules" :key="rule.type" class="rule-card">
            <span>{{ rule.type }}</span>
            <strong>{{ rule.desc }}</strong>
            <small>{{ rule.contract }}</small>
          </article>
          <article v-if="selectedStock.rules.length === 0" class="rule-empty-state">
            暂无价格触发条件，不会产生价格规则预警邮件。
          </article>
          <div class="analysis-gate">
            <div>
              <span>analysis auto</span>
              <strong>false</strong>
            </div>
            <button>启动四 agent</button>
          </div>
        </section>

        <section class="panel ai-rule-panel">
          <div class="panel-bar compact">
            <div>
              <p class="section-label">deepseek rules</p>
              <h2>AI观察条件</h2>
            </div>
            <button class="plain-button" type="button" @click="openAiRuleDialog()">
              新增
            </button>
          </div>
          <article v-if="selectedAiRules.length === 0" class="ai-rule-empty">
            暂无AI观察条件
          </article>
          <article v-for="rule in selectedAiRules" :key="rule.id" class="ai-rule-card">
            <div class="ai-rule-card-top">
              <div>
                <strong>{{ rule.name || selectedStock.name }}</strong>
                <small>{{ rule.enabled ? '启用' : '停用' }} · {{ rule.notify ? '通知' : '静默' }}</small>
              </div>
              <span :class="['signal-grade', rule.enabled ? 'green' : 'blue']">
                {{ Math.round((rule.minConfidence || 0.75) * 100) }}%
              </span>
            </div>
            <p>{{ rule.conditionText }}</p>
            <div class="ai-rule-actions">
              <small>冷却 {{ rule.cooldownDays || 1 }} 天 · {{ rule.model || 'deepseek-chat' }}</small>
              <div>
                <button class="plain-button" type="button" @click="openAiRuleDialog(rule)">编辑</button>
                <button class="plain-button danger" type="button" @click="deleteAiRule(rule)">删除</button>
              </div>
            </div>
          </article>
        </section>
      </aside>

      <section class="panel contract-panel">
        <div class="panel-bar compact">
          <div>
            <p class="section-label">TriggerEvent</p>
            <h2>事件契约</h2>
          </div>
        </div>
        <pre>{{ triggerPreview }}</pre>
      </section>
    </section>

    <div v-if="showAddStockDialog" class="modal-backdrop" @click.self="closeAddDialog">
      <section class="modal-panel" role="dialog" aria-modal="true" aria-label="添加股票">
        <div class="modal-head">
          <div>
            <p class="section-label">watchlist item</p>
            <h2>添加股票</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeAddDialog">×</button>
        </div>

        <div class="stock-form">
          <label>
            <span>股票编码</span>
            <input v-model.trim="addStockForm.code" type="text" placeholder="688017.SH 或 H09988" />
          </label>
          <label>
            <span>股票名称 <em>可选，行情刷新后自动补全</em></span>
            <input v-model.trim="addStockForm.name" type="text" placeholder="可留空，例如：绿的谐波" />
          </label>
          <label>
            <span>组别</span>
            <select v-model="addStockForm.groupKey">
              <option
                v-for="group in editableStockGroups"
                :key="group.value"
                :value="group.value"
              >
                {{ group.label }}
              </option>
            </select>
          </label>
          <label>
            <span>图片 URL</span>
            <input v-model.trim="addStockForm.image" type="url" placeholder="https://..." />
          </label>
          <label class="file-field">
            <span>上传图片</span>
            <input type="file" accept="image/*" @change="handleImageUpload" />
            <strong>{{ addStockForm.imageFileName || '选择图片' }}</strong>
          </label>
        </div>

        <div class="image-preview">
          <img v-if="addStockForm.image" :src="addStockForm.image" alt="股票图片预览" />
          <span v-else>{{ addStockPreviewText }}</span>
        </div>

        <p v-if="addStockError" class="form-error">{{ addStockError }}</p>

        <div class="modal-actions">
          <button class="plain-button" type="button" @click="closeAddDialog">取消</button>
            <button class="primary-button" type="button" :disabled="addStockSaving" @click="confirmAddStock">
              {{ addStockSaving ? '保存中' : '添加到监控' }}
            </button>
        </div>
      </section>
    </div>

    <div v-if="showObservationDialog" class="modal-backdrop" @click.self="closeObservationDialog">
      <section class="modal-panel" role="dialog" aria-modal="true" aria-label="添加观察">
        <div class="modal-head">
          <div>
            <p class="section-label">watch update</p>
            <h2>{{ selectedStock.name }} 观察更新</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeObservationDialog">×</button>
        </div>

        <div class="stock-form observation-form">
          <label>
            <span>类型</span>
            <select v-model="observationForm.severity">
              <option value="info">观察</option>
              <option value="opportunity">机会</option>
              <option value="risk">风险</option>
              <option value="warning">提醒</option>
            </select>
          </label>
          <label>
            <span>标题</span>
            <input v-model.trim="observationForm.title" type="text" placeholder="价格刷新后的判断" />
          </label>
          <label class="span-2">
            <span>观察内容</span>
            <textarea v-model.trim="observationForm.desc" rows="5" placeholder="记录当前价格、触发条件、验证点或你自己的判断。" />
          </label>
          <label class="span-2">
            <span>下一步动作</span>
            <input v-model.trim="observationForm.nextAction" type="text" placeholder="例如：跌破 380 复核风险；站回 400 再观察量能" />
          </label>
        </div>

        <p v-if="observationError" class="form-error">{{ observationError }}</p>

        <div class="modal-actions">
          <button class="plain-button" type="button" @click="closeObservationDialog">取消</button>
          <button class="primary-button" type="button" @click="confirmObservation">保存观察</button>
        </div>
      </section>
    </div>

    <div v-if="showAiRuleDialog" class="modal-backdrop" @click.self="closeAiRuleDialog">
      <section class="modal-panel" role="dialog" aria-modal="true" aria-label="AI观察条件">
        <div class="modal-head">
          <div>
            <p class="section-label">deepseek rule</p>
            <h2>{{ aiRuleEditingId ? '编辑AI观察条件' : '新增AI观察条件' }}</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeAiRuleDialog">×</button>
        </div>

        <div class="stock-form ai-rule-form">
          <label>
            <span>名称</span>
            <input v-model.trim="aiRuleForm.name" type="text" placeholder="绿的谐波风险提醒" />
          </label>
          <label>
            <span>风险级别</span>
            <select v-model="aiRuleForm.severity">
              <option value="risk">风险</option>
              <option value="opportunity">机会</option>
              <option value="warning">提醒</option>
              <option value="info">观察</option>
            </select>
          </label>
          <label class="span-2">
            <span>观察条件</span>
            <textarea
              v-model.trim="aiRuleForm.conditionText"
              rows="6"
              placeholder="例如：如果跌破287支撑，同时量比放大且主力资金没有承接，就触发风险提醒。"
            />
          </label>
          <label>
            <span>最低置信度</span>
            <input v-model.number="aiRuleForm.minConfidence" type="number" min="0.1" max="1" step="0.05" />
          </label>
          <label>
            <span>冷却天数</span>
            <input v-model.number="aiRuleForm.cooldownDays" type="number" min="1" max="30" step="1" />
          </label>
          <label class="check-field">
            <input v-model="aiRuleForm.enabled" type="checkbox" />
            <span>启用</span>
          </label>
          <label class="check-field">
            <input v-model="aiRuleForm.notify" type="checkbox" />
            <span>命中后邮件通知</span>
          </label>
        </div>

        <p v-if="aiRuleError" class="form-error">{{ aiRuleError }}</p>

        <div class="modal-actions">
          <button class="plain-button" type="button" @click="closeAiRuleDialog">取消</button>
          <button class="primary-button" type="button" :disabled="aiRuleSaving" @click="saveAiRule">
            {{ aiRuleSaving ? '保存中' : '保存条件' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="showFcManualDialog" class="modal-backdrop" @click.self="closeFcManualDialog">
      <section class="modal-panel fc-manual-modal" role="dialog" aria-modal="true" aria-label="手动新增FC卡">
        <div class="modal-head">
          <div>
            <p class="section-label">fc manual draft</p>
            <h2>手动新增 {{ selectedStock.name }} FC卡</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeFcManualDialog">×</button>
        </div>

        <div class="fc-import-help">
          手动新增会先保存为草稿；只有七字段、基线读数和采集器验证都通过后，现有闸门才会自动激活。
        </div>

        <div class="stock-form fc-manual-form">
          <label>
            <span>卡号 <em>必填</em></span>
            <input v-model.trim="fcManualForm.cardKey" type="text" placeholder="FC-迈瑞-4" />
          </label>
          <label>
            <span>标题 <em>必填</em></span>
            <input v-model.trim="fcManualForm.cardTitle" type="text" placeholder="国内收入 / BIOSECURE法案" />
          </label>
          <label>
            <span>类型</span>
            <input v-model.trim="fcManualForm.cardType" type="text" placeholder="业绩型 / 事件型 / 价格型" />
          </label>
          <label>
            <span>数据源层级</span>
            <select v-model="fcManualForm.dataSourceTier">
              <option value="T1">T1 结构化API</option>
              <option value="T2">T2 网页可爬</option>
              <option value="T3">T3 人工读取</option>
              <option value="T4">T4 不可执行/付费源</option>
            </select>
          </label>
          <label class="span-2">
            <span>论点</span>
            <textarea v-model.trim="fcManualForm.thesis" rows="3" placeholder="这张卡证伪的是什么。" />
          </label>
          <label class="span-2">
            <span>触发逻辑</span>
            <textarea v-model.trim="fcManualForm.triggerLogic" rows="3" placeholder="精确到数值的条件；阈值未校准要如实标注。" />
          </label>
          <label class="span-2">
            <span>数据源</span>
            <textarea v-model.trim="fcManualForm.dataSources" rows="3" placeholder="来源地址、公告页、接口或人工路径。" />
          </label>
          <label>
            <span>采集方式</span>
            <select v-model="fcManualForm.collectionMethod">
              <option value="自动">自动</option>
              <option value="半自动">半自动</option>
              <option value="人工">人工</option>
            </select>
          </label>
          <label>
            <span>频率</span>
            <input v-model.trim="fcManualForm.frequency" type="text" placeholder="日 / 周 / 月 / 季报披露日" />
          </label>
          <label class="span-2">
            <span>解析方法</span>
            <textarea v-model.trim="fcManualForm.parserSpec" rows="3" placeholder="接口名+字段名，或人工读取哪一节哪个数。" />
          </label>
          <label class="span-2">
            <span>基线读数</span>
            <textarea v-model.trim="fcManualForm.baselineReading" rows="3" placeholder="未采到请保留“待采”，系统会保持草稿。" />
          </label>
          <label class="span-2">
            <span>失效监控</span>
            <textarea v-model.trim="fcManualForm.failureMonitor" rows="3" placeholder="例如：披露日后3个交易日未录入 → 提醒。" />
          </label>
          <label class="span-2">
            <span>自动代理</span>
            <textarea v-model.trim="fcManualForm.autoProxy" rows="3" placeholder="可选：如果主指标需人工，是否存在可自动采集的代理指标。" />
          </label>
        </div>

        <p v-if="fcCardsError" class="form-error">{{ fcCardsError }}</p>

        <div class="modal-actions">
          <button class="plain-button" type="button" @click="closeFcManualDialog">取消</button>
          <button class="primary-button" type="button" :disabled="fcCardsManualSaving" @click="saveManualFcCard">
            {{ fcCardsManualSaving ? '保存中' : '保存为草稿' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="showFcImportDialog" class="modal-backdrop" @click.self="closeFcImportDialog">
      <section class="modal-panel fc-import-modal" role="dialog" aria-modal="true" aria-label="导入FC卡Markdown">
        <div class="modal-head">
          <div>
            <p class="section-label">fc markdown import</p>
            <h2>导入 {{ selectedStock.name }} FC卡</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeFcImportDialog">×</button>
        </div>

        <div class="fc-import-help">
          粘贴完整 FC 卡 Markdown 即可。后端会根据当前股票代码、股票名称或所属小节过滤，只保存属于
          <strong>{{ selectedStock.symbol }} · {{ selectedStock.name }}</strong>
          的卡；其他股票卡不会进入本详情页。
        </div>

        <textarea
          v-model="fcImportForm.markdown"
          class="fc-import-textarea"
          rows="18"
          placeholder="粘贴 FC卡 v2 Markdown..."
        />

        <p v-if="fcCardsError" class="form-error">{{ fcCardsError }}</p>

        <div class="modal-actions">
          <button class="plain-button" type="button" @click="closeFcImportDialog">取消</button>
          <button class="primary-button" type="button" :disabled="fcCardsImporting" @click="importFcCards">
            {{ fcCardsImporting ? '导入中' : '解析并导入当前股票' }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { createHttp } from '@/api/http'
import IntradayBarChart from '@/modules/stock-watch/components/IntradayBarChart.vue'
import BarUnavailableState from '@/modules/stock-watch/components/BarUnavailableState.vue'
import MetricCard from '@/modules/stock-watch/components/MetricCard.vue'
import M30ForecastLine from '@/modules/stock-watch/components/M30ForecastLine.vue'
import { loadDemoState } from '@/modules/stock-watch/runtime/demoMode'
import { loadAllPredictionPages, selectVisiblePredictionGroups } from '@/modules/stock-watch/runtime/predictionHistory.mjs'
import { useStockWatchRuntimeStatus } from '@/modules/stock-watch/composables/useStockWatchRuntimeStatus'

const CUSTOM_WATCHLIST_KEY = 'stock-watch.custom-watchlist.v1'
const CUSTOM_OBSERVATIONS_KEY = 'stock-watch.custom-observations.v1'
const CUSTOM_DELETED_EVENTS_KEY = 'stock-watch.deleted-events.v1'
const QUOTE_REFRESH_INTERVAL_MS = 10 * 60 * 1000
const ANALYSIS_CONTEXT_ROTATE_THRESHOLD = 85
const PREDICTION_COLLAPSED_LIMIT = 6
const ANALYSIS_PERIOD_TABS = [
  { label: '日内', value: 'DAY', desc: '30m', runtime: '交易时段每 30 分钟生成', windowLabel: '最近 30 天' },
  { label: '周度', value: 'WEEK', desc: '周五', runtime: '固定周五收盘后生成周复盘', windowLabel: '最近 12 周' },
  { label: '月度', value: 'MONTH', desc: '月末', runtime: '固定月末最后交易日生成月复盘', windowLabel: '最近 12 月' }
]
const ANALYSIS_INTERVAL_PRESETS = [10, 30, 60, 120, 1440]
const ANALYSIS_MODE_OPTIONS = [
  {
    label: '自定义间隔',
    value: 'INTERVAL',
    desc: '按分钟级 slot 生成统一 DeepSeek 分析需求'
  },
  {
    label: '每日收盘',
    value: 'DAILY_CLOSE',
    desc: '收盘后只分析一次，适合非重点监控'
  },
  {
    label: '仅异动触发',
    value: 'ON_ANOMALY',
    desc: '平时不跑定时分析，命中异动再分析'
  },
  {
    label: '暂停',
    value: 'PAUSED',
    desc: '不自动分析，仅保留手动入口'
  }
]
const ANALYSIS_ANOMALY_TRIGGER_OPTIONS = [
  {
    label: '立即统一分析',
    value: 'IMMEDIATE_ANALYSIS',
    desc: '异动命中后立即排队，技术面和异动含义一起交给 DeepSeek'
  },
  {
    label: '延后到下一计划',
    value: 'DEFER_TO_NEXT_SCHEDULE',
    desc: '异动先记账，复用下一次计划分析窗口'
  },
  {
    label: '只提醒不分析',
    value: 'ALERT_ONLY',
    desc: '只保留异动提醒，不额外启动 DeepSeek'
  }
]
const stockWatchHttp = createHttp(20000)
const technicalAnalysisHttp = createHttp(360000)
const { status: runtimeStatus, error: runtimeStatusError, refresh: refreshRuntimeStatus } = useStockWatchRuntimeStatus()

const viewMode = ref('list')
const selectedSymbol = ref('')
const activeFilter = ref('all')
const activeGroupFilter = ref('all')
const selectedAnalysisDate = ref(analysisDateKey(new Date()))
const selectedAnalysisPeriodType = ref('DAY')
const selectedAnalysisPeriodKey = ref(analysisDateKey(new Date()))
const deepseekSessionNotice = ref('')
const technicalAnalysisLoading = ref(false)
const technicalAnalysisRunning = ref(false)
const technicalAnalysisError = ref('')
const analysisPeriods = ref(buildAnalysisPeriods('DAY', {}))
const selectedAnalysisRecords = ref([])
const selectedAnalysisSession = ref(defaultAnalysisSession(null))
const predictionLoading = ref(false)
const predictionError = ref('')
const predictionRecords = ref([])
let predictionHistoryLoadId = 0
function emptyPredictionSummary() {
  return { total: 0, evaluated: 0, pending: 0, directionHitRate: null, currentPolicyDirectionHitRate: null, strictDirectionHitRate: null, currentPolicyScored: 0, currentPolicyLimitsPct: null, directionalDivergenceBasis: null, directionalForecastScored: 0, directionalDivergenceCount: 0, directionalDivergenceRate: null, directionalNonDivergenceHitRate: null, rangeCloseHitRate: null, averageBrierScore: null, brierPolicyMixed: false }
}
const predictionSummary = ref(emptyPredictionSummary())
const hasReturnSignDivergence = computed(() => predictionSummary.value.directionalDivergenceBasis === 'RETURN_SIGN')
const predictionPolicyCaption = computed(() => {
  const limits = predictionSummary.value.currentPolicyLimitsPct
  if (!limits) return '按期限复核震荡范围'
  return `T1 ±${limits.T1}% · T5/周 ±${limits.T5}% · 月 ±${limits.M1}%`
})
const predictionListMode = ref('all')
const predictionListExpanded = ref(false)
const predictionGroupByDay = ref(false)
const predictionDirectionFilter = ref('all')
const predictionHorizonFilter = ref('all')
const expandedPredictionDates = ref([])
const showAddStockDialog = ref(false)
const showObservationDialog = ref(false)
const showAiRuleDialog = ref(false)
const showFcManualDialog = ref(false)
const showFcImportDialog = ref(false)
const addStockError = ref('')
const addStockSaving = ref(false)
const observationError = ref('')
const aiRuleError = ref('')
const aiRuleSaving = ref(false)
const aiRuleEditingId = ref('')
const activeConfigTab = ref('quote')
const deliveryLoading = ref(false)
const deliverySaving = ref(false)
const deliveryTesting = ref(false)
const deliveryDispatching = ref(false)
const deliveryPreviewLoading = ref(false)
const deliveryError = ref('')
const deliveryNotice = ref('')
const deliveryPreview = ref([])
const deliveryStats = ref({ PENDING: 0, SENT: 0, FAILED: 0 })
const analysisPolicyLoading = ref(false)
const analysisPolicySaving = ref(false)
const analysisPolicyError = ref('')
const analysisPolicyNotice = ref('')
const quoteRefreshing = ref(false)
const quoteError = ref('')
const quoteLastRefresh = ref('')
const quoteRefreshMode = ref('manual')
const loadingBars = ref(false)
const barError = ref('')
const demoModeActive = ref(false)
const autoRefreshTimer = ref(null)
const aiRules = ref([])
const fcCards = ref([])
const fcCardsLoading = ref(false)
const fcCardsSaving = ref(false)
const fcCardsManualSaving = ref(false)
const fcCardsImporting = ref(false)
const fcCardsError = ref('')
const fcCardsNotice = ref('')
const selectedFcCardId = ref(null)
const externalEvidenceLoading = ref(false)
const externalEvidenceError = ref('')
const externalEvidenceNotice = ref('')
const externalEvidence = ref(null)

const addStockForm = reactive({
  code: '',
  name: '',
  groupKey: 'watch',
  image: '',
  imageFileName: ''
})

const observationForm = reactive({
  severity: 'info',
  title: '',
  desc: '',
  nextAction: ''
})

const aiRuleForm = reactive({
  name: '',
  conditionText: '',
  severity: 'risk',
  minConfidence: 0.75,
  cooldownDays: 1,
  enabled: true,
  notify: true
})

const fcImportForm = reactive({
  markdown: ''
})

const fcManualForm = reactive({
  cardKey: '',
  cardTitle: '',
  cardType: '',
  thesis: '',
  triggerLogic: '',
  dataSourceTier: 'T3',
  dataSources: '',
  collectionMethod: '人工',
  frequency: '',
  parserSpec: '',
  baselineReading: '待采',
  failureMonitor: '',
  autoProxy: ''
})

const fcEditForm = reactive({
  baselineReading: '',
  failureMonitor: '',
  lastCollectStatus: 'PENDING',
  lastCollectNote: ''
})

const deliveryForm = reactive({
  id: null,
  enabled: false,
  recipientsText: '',
  frequencyType: 'MANUAL',
  intervalMinutes: 120,
  minConfidence: 0,
  keywordsText: '',
  tones: [],
  lastDispatchStatus: '',
  lastDispatchMessage: '',
  lastDispatchAt: '',
  activeFrom: ''
})

const analysisPolicyForm = reactive({
  mode: 'INTERVAL',
  intervalMinutes: 30,
  anomalyTriggerMode: 'IMMEDIATE_ANALYSIS',
  weeklyReviewEnabled: true,
  monthlyReviewEnabled: true,
  version: 0,
  modifiedAt: ''
})

const deletedEventIds = new Set()

const severityTabs = [
  { label: '全部', value: 'all' },
  { label: '机会', value: 'opportunity' },
  { label: '风险', value: 'risk' },
  { label: '等待', value: 'info' }
]

const stockGroupTabs = [
  { label: '全部组别', value: 'all' },
  { label: '持仓股', value: 'holding' },
  { label: '观察股', value: 'watch' },
  { label: '备选股', value: 'candidate' },
  { label: '归档', value: 'archived' }
]

const editableStockGroups = stockGroupTabs.filter((group) => group.value !== 'all')

const configTabs = [
  { label: '行情抓取', value: 'quote', kicker: 'quote', desc: '10分钟快照' },
  { label: '外部证据', value: 'external', kicker: 'external', desc: 'TradingView对照' },
  { label: 'LLM 分析', value: 'llm', kicker: 'llm', desc: 'DeepSeek会话' },
  { label: 'FC卡', value: 'fc', kicker: 'fc', desc: '论点证伪/基线' },
  { label: '触发条件', value: 'rules', kicker: 'rules', desc: '规则过滤' },
  { label: '邮件推送', value: 'mail', kicker: 'mail', desc: '邮箱/频率/去重' }
]

const deliveryToneOptions = [
  { label: '机会', value: 'green' },
  { label: '中性', value: 'blue' },
  { label: '等待', value: 'amber' },
  { label: '风险', value: 'red' }
]

const watchlist = reactive([])
const events = reactive([])
const plugins = reactive([])
const emptyStock = createEmptyStock()

onMounted(async () => {
  const demoState = await loadDemoState()
  if (demoState) {
    demoModeActive.value = true
    applyPageState(demoState)
  } else {
    await loadRealStockWatchState()
  }
  await refreshRuntimeForSelectedStock()
})

onUnmounted(() => {
  stopAutoQuoteRefresh()
})

watch(
  () => [viewMode.value, selectedSymbol.value, selectedAnalysisPeriodType.value, selectedAnalysisPeriodKey.value],
  () => {
    if (viewMode.value === 'detail') {
      loadTechnicalAnalysisHistory()
    }
  }
)

watch(
  () => [viewMode.value, selectedSymbol.value],
  () => {
    predictionListMode.value = 'all'
    predictionListExpanded.value = false
    predictionDirectionFilter.value = 'all'
    predictionHorizonFilter.value = 'all'
    expandedPredictionDates.value = []
    externalEvidence.value = null
    externalEvidenceError.value = ''
    externalEvidenceNotice.value = ''
    analysisPolicyError.value = ''
    analysisPolicyNotice.value = ''
    syncAnalysisPolicyForm()
    if (viewMode.value === 'detail') {
      loadIntradayBars()
      refreshRuntimeForSelectedStock()
      loadDeliveryConfig()
      loadFcCards()
      loadPredictionHistory()
      if (activeConfigTab.value === 'external') {
        loadExternalEvidence(false)
      }
      if (activeConfigTab.value === 'llm') {
        loadAnalysisPolicy(false)
      }
    }
  }
)

watch(
  () => activeConfigTab.value,
  (tab) => {
    if (viewMode.value === 'detail' && tab === 'external' && !externalEvidence.value) {
      loadExternalEvidence(false)
    }
    if (viewMode.value === 'detail' && tab === 'llm') {
      loadAnalysisPolicy(false)
    }
  }
)

const isDetail = computed(() => viewMode.value === 'detail')
const selectedStock = computed(() => watchlist.find((item) => item.symbol === selectedSymbol.value) || watchlist[0] || emptyStock)
const selectedAnalysisPolicy = computed(() => normalizeAnalysisPolicyPayload(selectedStock.value?.analysisPolicy))
const selectedAnalysisSchedule = computed(() => normalizeAnalysisSchedulePayload(selectedStock.value?.analysisSchedule))
const selectedAnalysisPolicyPersisted = computed(() => hasPersistedAnalysisPolicy(selectedStock.value))
const selectedAnalysisCadenceLabel = computed(() => stockAnalysisCadenceLabel(selectedStock.value))
const selectedAnomalyTriggerLabel = computed(() => stockAnomalyTriggerLabel(selectedStock.value))
const selectedAnalysisPolicySubtitle = computed(() => analysisPolicySubtitle(selectedAnalysisPolicy.value, selectedAnalysisPolicyPersisted.value))
const analysisPolicyVersionLabel = computed(() => (
  selectedAnalysisPolicyPersisted.value
    ? `v${analysisPolicyForm.version} · ${formatPolicyTime(analysisPolicyForm.modifiedAt)}`
    : '未保存 · 草案'
))
const analysisReviewTogglesEnabled = computed(() => (
  analysisPolicyForm.mode === 'INTERVAL' || analysisPolicyForm.mode === 'DAILY_CLOSE'
))
const analysisPeriodTabs = ANALYSIS_PERIOD_TABS
const selectedAnalysisPeriodTab = computed(() => (
  analysisPeriodTabs.find((tab) => tab.value === selectedAnalysisPeriodType.value) || analysisPeriodTabs[0]
))
const selectedEvents = computed(() => (
  events
    .filter((event) => event.symbol && event.symbol === selectedStock.value.symbol)
    .slice()
    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
))
const filteredPredictionRecords = computed(() => {
  let records = predictionRecords.value
  if (predictionListMode.value === 'evaluated') {
    records = records.filter((prediction) => prediction.status === 'EVALUATED')
  }
  if (predictionListMode.value === 'diverged') {
    records = records.filter((prediction) => prediction.directionDiverged === true)
  }
  if (predictionDirectionFilter.value !== 'all') {
    records = records.filter((prediction) => prediction.direction === predictionDirectionFilter.value)
  }
  if (predictionDirectionFilter.value !== 'all' && predictionHorizonFilter.value !== 'all') {
    records = records.filter((prediction) => prediction.horizon === predictionHorizonFilter.value)
  }
  return records
})
const predictionGroups = computed(() => {
  if (!predictionGroupByDay.value) {
    return filteredPredictionRecords.value.map((prediction) => ({
      key: `prediction-${prediction.id}`,
      predictions: [prediction]
    }))
  }
  const groups = new Map()
  filteredPredictionRecords.value.forEach((prediction) => {
    const date = prediction.forecastDate || prediction.generatedAt?.slice(0, 10) || '日期未知'
    if (!groups.has(date)) groups.set(date, { key: date, date, predictions: [] })
    groups.get(date).predictions.push(prediction)
  })
  return [...groups.values()].sort((left, right) => right.date.localeCompare(left.date))
})
const hasPredictionOverflow = computed(() => !predictionGroupByDay.value && predictionGroups.value.length > PREDICTION_COLLAPSED_LIMIT)
const visiblePredictionGroups = computed(() => selectVisiblePredictionGroups(
  predictionGroups.value, predictionGroupByDay.value, predictionListExpanded.value, PREDICTION_COLLAPSED_LIMIT
))
const visiblePredictionCount = computed(() => visiblePredictionGroups.value.reduce((count, group) => count + group.predictions.length, 0))
const hiddenPredictionCount = computed(() => (
  Math.max(filteredPredictionRecords.value.length - visiblePredictionCount.value, 0)
))
const hiddenPredictionGroupCount = computed(() => Math.max(predictionGroups.value.length - visiblePredictionGroups.value.length, 0))
watch([predictionDirectionFilter, predictionHorizonFilter], () => {
  predictionListExpanded.value = false
  expandedPredictionDates.value = []
})
const predictionListTitle = computed(() => {
  if (predictionListMode.value === 'evaluated') return '验真结果列表'
  if (predictionListMode.value === 'diverged') return '方向背离样本'
  return '预测历史列表'
})
const predictionListEmptyText = computed(() => {
  if (predictionDirectionFilter.value !== 'all') return '当前筛选条件下暂无预测记录。'
  if (predictionListMode.value === 'evaluated') return '暂无已验真的预测结果。'
  if (predictionListMode.value === 'diverged') return '本次加载的预测中暂无方向背离样本。'
  return '暂无预测记录。'
})
const selectedAiRules = computed(() => aiRules.value.filter((rule) => rule.symbol === selectedStock.value.symbol))
const selectedFcCards = computed(() => fcCards.value.filter((card) => card.symbol === selectedStock.value.symbol))
const selectedFcCard = computed(() => (
  selectedFcCards.value.find((card) => String(card.id) === String(selectedFcCardId.value))
  || selectedFcCards.value[0]
  || null
))
const fcCardStats = computed(() => {
  const cards = selectedFcCards.value
  return {
    total: cards.length,
    active: cards.filter((card) => card.activationStatus === 'ACTIVE').length,
    draft: cards.filter((card) => card.activationStatus === 'DRAFT').length,
    manual: cards.filter((card) => String(card.collectionMethod || '').includes('人工')).length,
    blocked: cards.filter((card) => (card.activationBlockers || []).length > 0).length
  }
})
const selectedFcCardFields = computed(() => {
  const card = selectedFcCard.value
  if (!card) return []
  return [
    { no: '01', label: '论点', value: card.thesis, status: card.thesis ? '已填' : '缺失' },
    { no: '02', label: '触发逻辑', value: card.triggerLogic, status: card.triggerLogic ? '已填' : '缺失' },
    { no: '03', label: '数据源', value: card.dataSources, status: card.dataSourceTier || '未分层' },
    { no: '04', label: '采集方式', value: card.collectionMethod, status: fcCollectionLabel(card.collectionMethod) },
    { no: '05', label: '频率', value: card.frequency, status: card.frequency ? '已填' : '缺失' },
    { no: '06', label: '解析方法', value: card.parserSpec, status: card.parserSpec ? '已填' : '缺失' },
    { no: '07', label: '基线读数 + 失效监控', value: `${card.baselineReading || '基线待采'}\n${card.failureMonitor || '失效监控待配置'}`, status: card.activationStatus }
  ]
})
const externalCompareRows = computed(() => {
  const payload = externalEvidence.value || {}
  const tv = payload.tradingView || {}
  const em = payload.eastmoney || {}
  const comparison = payload.comparison || {}
  return [
    {
      label: '最新价',
      tradingView: formatExternalNumber(tv.price, 2),
      eastmoney: formatExternalNumber(em.price, 2),
      diff: formatExternalDiff(comparison.priceDiff, 4),
      matched: Boolean(comparison.priceMatched)
    },
    {
      label: '涨跌幅',
      tradingView: formatExternalPercent(tv.changePercent, 4),
      eastmoney: formatExternalPercent(em.changePercent, 2),
      diff: formatExternalDiff(comparison.changePercentDiff, 4, ' pct'),
      matched: Boolean(comparison.changePercentMatched)
    },
    {
      label: '成交量',
      tradingView: formatExternalVolume(tv.volumeShares),
      eastmoney: formatExternalVolume(em.volumeShares),
      diff: formatExternalDiff(comparison.volumeDiffPercent, 4, '%'),
      matched: Boolean(comparison.volumeMatched)
    }
  ]
})
const externalTradingViewFields = computed(() => {
  const tv = externalEvidence.value?.tradingView || {}
  return [
    { label: 'Recommend.All', value: formatExternalNumber(tv.recommendAll, 3), note: 'TradingView 综合技术评级，-1 到 1 附近波动' },
    { label: 'RSI', value: formatExternalNumber(tv.rsi, 2), note: '14周期动量指标，仅作外部参照' },
    { label: 'MACD', value: formatExternalNumber(tv.macd, 3), note: `Signal ${formatExternalNumber(tv.macdSignal, 3)}` },
    { label: 'SMA20', value: formatExternalNumber(tv.sma20, 2), note: `EMA20 ${formatExternalNumber(tv.ema20, 2)}` },
    { label: '英文名称', value: tv.description || '--', note: tv.exchange || tv.source || 'TradingView China scanner' },
    { label: '市值字段', value: formatExternalMoney(tv.marketCapBasic), note: 'market_cap_basic，按数值字段保留' }
  ]
})

watch(
  () => selectedFcCard.value?.id,
  () => {
    syncFcEditForm()
  }
)

const analysisTodayKey = computed(() => analysisDateKey(new Date()))
const deliveryRecipients = computed(() => parseDeliveryRecipients(deliveryForm.recipientsText))
const deliveryFrequencyLabel = computed(() => {
  if (deliveryForm.frequencyType === 'IMMEDIATE') return '新结论立即发送'
  if (deliveryForm.frequencyType === 'DAILY_CLOSE') return '每日收盘后'
  if (deliveryForm.frequencyType === 'INTERVAL') {
    const minutes = Math.max(5, Number(deliveryForm.intervalMinutes) || 120)
    return minutes >= 60 && minutes % 60 === 0 ? `每 ${minutes / 60} 小时` : `每 ${minutes} 分钟`
  }
  return '仅手动发送'
})
const activeConfigSupportsPersistence = computed(() => ['llm', 'mail'].includes(activeConfigTab.value))
const activeConfigLoading = computed(() => {
  if (activeConfigTab.value === 'llm') return analysisPolicyLoading.value
  if (activeConfigTab.value === 'mail') return deliveryLoading.value
  return false
})
const activeConfigSaving = computed(() => {
  if (activeConfigTab.value === 'llm') return analysisPolicySaving.value
  if (activeConfigTab.value === 'mail') return deliverySaving.value
  return false
})
const activeConfigRefreshLabel = computed(() => {
  if (activeConfigLoading.value) return '刷新中'
  if (activeConfigTab.value === 'llm') return '刷新策略'
  return '刷新配置'
})
const activeConfigSaveLabel = computed(() => {
  if (activeConfigSaving.value) return '保存中'
  if (activeConfigTab.value === 'llm') return selectedAnalysisPolicyPersisted.value ? '保存策略' : '加入并保存策略'
  return '保存配置'
})
const deliveryTriggerSummary = computed(() => {
  const parts = []
  if (deliveryForm.tones.length) parts.push(deliveryForm.tones.join('/'))
  if (Number(deliveryForm.minConfidence) > 0) parts.push(`conf>=${deliveryForm.minConfidence}`)
  if (deliveryForm.keywordsText.trim()) parts.push('关键词')
  return parts.length ? parts.join(' · ') : '所有新结论'
})
const filteredWatchlist = computed(() => watchlist.filter((item) => {
  const severityMatched = activeFilter.value === 'all' || item.severity === activeFilter.value
  const groupMatched = activeGroupFilter.value === 'all' || normalizeStockGroupKey(item.groupKey) === activeGroupFilter.value
  return severityMatched && groupMatched
}))
const addStockPreviewText = computed(() => {
  const normalized = normalizeStockCode(addStockForm.code)
  const name = addStockForm.name.trim()
  if (name) return name.slice(0, 2)
  if (normalized) return normalized.symbol.slice(-2)
  return '图'
})

const autoRefreshStatusLabel = computed(() => (
  isTradingSessionNow() ? '10m 自动刷新' : '非交易时段手动'
))

const runtimeSchedulingLabel = computed(() => {
  if (runtimeStatusError.value) return '运行状态未知'
  if (runtimeStatus.value?.status === 'DEGRADED') return '运行状态降级'
  if (runtimeStatus.value?.analysisRunning) return '分析运行中'
  if (runtimeStatus.value?.schedulingEnabled) return `调度 ${runtimeStatus.value.analysisMode || '已启用'}`
  return '调度未启用'
})
const runtimeSchedulingTone = computed(() => {
  if (runtimeStatusError.value) return 'muted'
  if (runtimeStatus.value?.status === 'DEGRADED') return 'warning'
  if (runtimeStatus.value?.analysisRunning || runtimeStatus.value?.schedulingEnabled) return 'success'
  return 'muted'
})
const runtimeProviderLabel = computed(() => {
  if (demoModeActive.value) return 'Demo 模式'
  if (runtimeStatusError.value) return 'Provider 未知'
  return runtimeStatus.value?.providerVersionState === 'CONFIGURED'
    ? `${runtimeStatus.value.provider || 'Provider'} 已配置`
    : 'Provider 未配置'
})
const runtimeProviderTone = computed(() => (
  runtimeStatus.value?.providerVersionState === 'CONFIGURED' ? 'neutral' : 'muted'
))
const runtimeRetryLabel = computed(() => {
  const policy = runtimeStatus.value?.retryPolicy
  if (!policy) return `下一次 ${selectedAnalysisSession.value.nextRun}`
  if (policy.status === 'UNKNOWN' || policy.retryable == null) return '重试状态不可判定'
  if (!policy.retryable) return `下一次 ${runtimeStatus.value?.nextDueAt ? formatPolicyTime(runtimeStatus.value.nextDueAt) : selectedAnalysisSession.value.nextRun}，当前无待重试任务`
  if (policy.maxAttempts == null) return `已提交 ${policy.currentAttempt || 0} 次；下次重试：${formatPolicyTime(policy.nextRetryAt)}`
  return `第 ${policy.currentAttempt || 0} / ${policy.maxAttempts || 0} 次；下次重试：${formatPolicyTime(policy.nextRetryAt)}`
})
const selectedIntradayBars = computed(() => (
  Array.isArray(selectedStock.value.intradayBars) ? selectedStock.value.intradayBars : []
))
const chartState = computed(() => {
  if (loadingBars.value) return 'LOADING'
  if (barError.value) return 'ERROR'
  const sourceStatus = String(selectedStock.value.intradayBarStatus?.status || '').toUpperCase()
  if (sourceStatus === 'SOURCE_ERROR') return 'ERROR'
  if (sourceStatus === 'EMPTY') return 'EMPTY'
  if (selectedIntradayBars.value.length === 0) return 'UNAVAILABLE'
  if (sourceStatus === 'DEGRADED') return 'DEGRADED'
  if (sourceStatus === 'INVALID') return 'INVALID'
  if (selectedIntradayBars.value.some((bar) => bar.qualityStatus === 'INVALID')) return 'INVALID'
  return 'VALID'
})
const barUnavailableMessage = computed(() => {
  if (barError.value) return barError.value
  const status = selectedStock.value.intradayBarStatus || {}
  const summary = status.qualitySummary || {}
  if (status.reason) return status.reason
  if (chartState.value === 'INVALID' || chartState.value === 'DEGRADED') {
    return `行情质量未通过校验：有效 ${summary.validBars || 0} / 总计 ${summary.totalBars || 0}`
  }
  return '尚未获取真实分钟行情'
})
const chartSourceLabel = computed(() => {
  if (demoModeActive.value) return '演示 OHLC'
  if (chartState.value === 'VALID') return '东方财富分钟行情'
  const status = selectedStock.value.intradayBarStatus?.status
  return status ? `真实 bars ${status}` : '真实 bars 待获取'
})

const metrics = computed(() => [
  {
    label: '今日事件',
    value: String(events.length),
    note: events.length ? '来自事件流' : '暂无真实事件',
    tone: events.length ? 'green' : 'ink'
  },
  { label: '监控标的', value: String(watchlist.length), note: stockGroupSummaryText.value, tone: 'ink' },
  {
    label: '行情刷新',
    value: quoteRefreshing.value ? '...' : (quoteLastRefresh.value || '待刷新'),
    note: quoteError.value || `${quoteRefreshMode.value} · 东方财富 quote`,
    tone: quoteError.value ? 'red' : 'blue'
  },
  {
    label: 'AI 状态',
    value: runtimeStatus.value?.analysisRunning ? 'RUNNING' : (runtimeStatus.value?.analysisMode || 'UNKNOWN'),
    note: runtimeStatusError.value || runtimeStatus.value?.warnings?.[0] || runtimeStatus.value?.lastRunStatus || '等待运行状态',
    tone: runtimeStatus.value?.status === 'DEGRADED' ? 'amber' : (runtimeStatus.value?.analysisRunning ? 'purple' : 'ink')
  }
])

const stockGroupSummaryText = computed(() => {
  const holding = countStockGroup('holding')
  const watch = countStockGroup('watch')
  const candidate = countStockGroup('candidate')
  return `持仓 ${holding} / 观察 ${watch} / 备选 ${candidate}`
})

const technicalMetrics = computed(() => {
  const t = selectedStock.value.technical
  return [
    metricValue('ohlc', '开高低 / 成交额', [t.open, t.high, t.low], { reason: `成交额 ${t.volume}`, tone: 'ink' }),
    metricValue('ma-bias', '均线 / 乖离', [t.ma5, t.ma10, t.ma20], { reason: `MA20 乖离 ${t.bias20}`, tone: marketToneFromSignedValue(t.bias20, 'blue') }),
    metricValue('vwap-volume', 'VWAP / 量比', [t.vwap, t.volumeRatio], { reason: `VWAP 偏离 ${t.vwapDeviation} · 换手 ${t.turnover}`, tone: marketToneFromSignedValue(t.vwapDeviation, 'blue') }),
    metricValue('volatility-boll', '波动 / 布林带', [t.atr, t.bollWidth], { reason: 'ATR 与 BOLL 带宽', tone: 'amber' }),
    metricValue('rsi-macd', 'RSI / MACD', [t.rsi, t.macd], { reason: '动量只做辅助确认', tone: marketToneFromSignedValue(t.macd, 'blue') }),
    metricValue('flow-obv', '资金 / OBV', [t.mainInflow, t.obvTrend], { reason: '主力净额与量价趋势', tone: marketToneFromSignedValue(t.mainInflow, 'ink') })
  ]
})

const signalMatrix = computed(() => {
  const t = selectedStock.value.technical
  return [
    {
      name: '趋势位置',
      desc: `MA20 乖离 ${t.bias20}，VWAP 偏离 ${t.vwapDeviation}`,
      grade: t.bias20.startsWith('-') ? '回踩' : '偏强',
      tone: marketToneFromSignedValue(t.bias20, 'blue')
    },
    {
      name: '量能确认',
      desc: `量比 ${t.volumeRatio}，换手 ${t.turnover}，OBV ${t.obvTrend}`,
      grade: Number.parseFloat(t.volumeRatio) >= 1 ? '有效' : '偏弱',
      tone: Number.parseFloat(t.volumeRatio) >= 1 ? 'green' : 'amber'
    },
    {
      name: '波动风险',
      desc: `ATR ${t.atr}，BOLL 带宽 ${t.bollWidth}`,
      grade: Number.parseFloat(t.atr) > 4 ? '高波动' : '正常',
      tone: Number.parseFloat(t.atr) > 4 ? 'amber' : 'blue'
    },
    {
      name: '相对强弱',
      desc: `板块 ${t.sectorStrength}，RS ${t.rsRank}`,
      grade: Number.parseInt(t.rsRank, 10) >= 60 ? '强于盘面' : '中性',
      tone: Number.parseInt(t.rsRank, 10) >= 60 ? 'green' : 'blue'
    }
  ]
})

const triggerPreview = computed(() => {
  const stock = selectedStock.value
  const rule = stock.rules[0]
  if (!rule) {
    return JSON.stringify({
      symbol: stock.symbol,
      name: stock.name,
      status: 'NO_TRIGGER_RULE',
      message: '当前股票没有已配置的价格触发条件，不会生成价格规则事件。'
    }, null, 2)
  }
  return JSON.stringify({
    event_id: `${stock.symbol}_20260606_${rule.type}`,
    timestamp: '2026-06-06T13:30:00',
    symbol: stock.symbol,
    name: stock.name,
    trigger_type: rule.type,
    trigger_desc: rule.desc,
    raw_data: stock.rawData,
    severity: stock.severity
  }, null, 2)
})

function buildAnalysisDays(countMap) {
  return buildAnalysisPeriods('DAY', countMap)
}

function buildAnalysisPeriods(periodType = 'DAY', countMap = {}) {
  const type = normalizeAnalysisPeriodType(periodType)
  const length = type === 'DAY' ? 30 : 12
  const today = parseLocalDate(analysisDateKey(new Date()))
  const periods = []
  let cursor = today
  for (let index = 0; index < length; index += 1) {
    const window = analysisPeriodWindow(type, cursor)
    periods.push({
      key: window.key,
      date: window.endDate,
      startDate: window.startDate,
      endDate: window.endDate,
      label: analysisPeriodLabel(window, index),
      sublabel: analysisPeriodSublabel(window),
      count: countMap[window.key] || 0
    })
    cursor = shiftLocalDate(parseLocalDate(window.startDate), -1)
  }
  return periods
}

function normalizeAnalysisPeriodType(periodType) {
  const value = String(periodType || 'DAY').toUpperCase()
  return ['DAY', 'WEEK', 'MONTH'].includes(value) ? value : 'DAY'
}

function analysisPeriodWindow(periodType, date) {
  const dateKey = analysisDateKey(date)
  if (periodType === 'WEEK') {
    const anchor = weekendToFriday(date)
    const day = getChinaWeekday(anchor)
    const monday = shiftLocalDate(anchor, -(day - 1))
    const friday = shiftLocalDate(monday, 4)
    const iso = getIsoWeek(monday)
    return {
      type: periodType,
      key: `${iso.year}-W${String(iso.week).padStart(2, '0')}`,
      startDate: analysisDateKey(monday),
      endDate: analysisDateKey(friday)
    }
  }
  if (periodType === 'MONTH') {
    const parts = dateKey.split('-')
    const first = `${parts[0]}-${parts[1]}-01`
    const last = analysisDateKey(new Date(Number(parts[0]), Number(parts[1]), 0))
    return {
      type: periodType,
      key: `${parts[0]}-${parts[1]}`,
      startDate: first,
      endDate: last
    }
  }
  return { type: 'DAY', key: dateKey, startDate: dateKey, endDate: dateKey }
}

function analysisPeriodLabel(window, index) {
  if (window.type === 'DAY') {
    if (index === 0) return '今天'
    if (index === 1) return '昨天'
    return window.key.slice(5).replace('-', '/')
  }
  if (window.type === 'WEEK') {
    return window.key.replace('-W', ' 第') + '周'
  }
  return window.key
}

function analysisPeriodSublabel(window) {
  if (window.type === 'DAY') return '日内30m'
  return `${window.startDate.slice(5).replace('-', '/')} - ${window.endDate.slice(5).replace('-', '/')}`
}

function parseLocalDate(dateKey) {
  const [year, month, day] = String(dateKey).split('-').map((item) => Number(item))
  return new Date(year, month - 1, day)
}

function shiftLocalDate(date, offsetDays) {
  const shifted = new Date(date)
  shifted.setDate(shifted.getDate() + offsetDays)
  return shifted
}

function getChinaWeekday(date) {
  const day = date.getDay()
  return day === 0 ? 7 : day
}

function weekendToFriday(date) {
  const day = getChinaWeekday(date)
  if (day === 6) return shiftLocalDate(date, -1)
  if (day === 7) return shiftLocalDate(date, -2)
  return date
}

function getIsoWeek(date) {
  const target = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()))
  const dayNumber = target.getUTCDay() || 7
  target.setUTCDate(target.getUTCDate() + 4 - dayNumber)
  const yearStart = new Date(Date.UTC(target.getUTCFullYear(), 0, 1))
  const week = Math.ceil((((target - yearStart) / 86400000) + 1) / 7)
  return { year: target.getUTCFullYear(), week }
}

function defaultAnalysisSession(stock = selectedStock.value) {
  const schedule = getAnalysisRunSchedule(new Date())
  return {
    title: stock?.name && stock?.symbol ? `${stock.name} · ${stock.symbol}` : '待选择股票',
    sessionId: stock?.symbol ? `计划绑定 stock-watch-${stock.market || inferMarket(stock.symbol)}-${stock.symbol}` : '待创建',
    status: '待创建',
    contextUsage: 0,
    lastRun: '--',
    nextRun: schedule.nextRun,
    rotatePolicy: `超过 ${ANALYSIS_CONTEXT_ROTATE_THRESHOLD}% 自动新开`,
    traceable: false,
    url: ''
  }
}

async function loadFcCards() {
  const stock = selectedStock.value
  if (!stock?.symbol) return
  fcCardsLoading.value = true
  fcCardsError.value = ''
  try {
    const response = await stockWatchHttp.get('/stock-watch/fc-cards', {
      params: {
        symbol: stock.symbol,
        market: stock.market
      },
      suppressErrorMessage: true
    })
    const cards = Array.isArray(response?.data?.cards) ? response.data.cards.map(normalizeFcCard) : []
    fcCards.value = cards
    if (!cards.some((card) => String(card.id) === String(selectedFcCardId.value))) {
      selectedFcCardId.value = cards[0]?.id || null
    }
    syncFcEditForm()
  } catch (error) {
    fcCardsError.value = resolveErrorMessage(error, 'FC卡加载失败')
    fcCards.value = []
    selectedFcCardId.value = null
  } finally {
    fcCardsLoading.value = false
  }
}

function normalizeFcCard(card) {
  return {
    id: card?.id || '',
    symbol: card?.symbol || '',
    market: card?.market || '',
    stockName: card?.stockName || '',
    cardKey: card?.cardKey || '',
    cardTitle: card?.cardTitle || '未命名FC卡',
    cardType: card?.cardType || '',
    thesis: card?.thesis || '',
    triggerLogic: card?.triggerLogic || '',
    dataSourceTier: card?.dataSourceTier || '',
    dataSources: card?.dataSources || '',
    collectionMethod: card?.collectionMethod || '',
    frequency: card?.frequency || '',
    parserSpec: card?.parserSpec || '',
    baselineReading: card?.baselineReading || '',
    failureMonitor: card?.failureMonitor || '',
    autoProxy: card?.autoProxy || '',
    activationStatus: card?.activationStatus || 'DRAFT',
    activationBlockers: Array.isArray(card?.activationBlockers) ? card.activationBlockers : [],
    enabled: Boolean(card?.enabled),
    notifyEnabled: card?.notifyEnabled !== false,
    lastCollectStatus: card?.lastCollectStatus || 'PENDING',
    lastCollectNote: card?.lastCollectNote || '',
    lastCollectedAt: card?.lastCollectedAt || '',
    rawMarkdown: card?.rawMarkdown || '',
    gmtModify: card?.gmtModify || ''
  }
}

function selectFcCard(card) {
  selectedFcCardId.value = card?.id || null
}

function resetFcManualForm() {
  const stock = selectedStock.value
  const nextNo = String(selectedFcCards.value.length + 1)
  fcManualForm.cardKey = stock?.symbol ? `FC-${stock.symbol}-${nextNo}` : ''
  fcManualForm.cardTitle = ''
  fcManualForm.cardType = ''
  fcManualForm.thesis = ''
  fcManualForm.triggerLogic = ''
  fcManualForm.dataSourceTier = 'T3'
  fcManualForm.dataSources = ''
  fcManualForm.collectionMethod = '人工'
  fcManualForm.frequency = ''
  fcManualForm.parserSpec = ''
  fcManualForm.baselineReading = '待采'
  fcManualForm.failureMonitor = ''
  fcManualForm.autoProxy = ''
}

function openFcManualDialog() {
  resetFcManualForm()
  fcCardsError.value = ''
  fcCardsNotice.value = ''
  showFcManualDialog.value = true
}

function closeFcManualDialog() {
  showFcManualDialog.value = false
  fcCardsManualSaving.value = false
}

async function saveManualFcCard() {
  const stock = selectedStock.value
  const cardKey = fcManualForm.cardKey.trim()
  const cardTitle = fcManualForm.cardTitle.trim()
  if (!cardKey) {
    fcCardsError.value = '请输入FC卡号'
    return
  }
  if (!cardTitle) {
    fcCardsError.value = '请输入FC卡标题'
    return
  }
  fcCardsManualSaving.value = true
  fcCardsError.value = ''
  fcCardsNotice.value = ''
  try {
    const response = await stockWatchHttp.post('/stock-watch/fc-cards', {
      symbol: stock.symbol,
      market: stock.market,
      stockName: stock.name,
      cardKey,
      cardTitle,
      cardType: fcManualForm.cardType,
      thesis: fcManualForm.thesis,
      triggerLogic: fcManualForm.triggerLogic,
      dataSourceTier: fcManualForm.dataSourceTier,
      dataSources: fcManualForm.dataSources,
      collectionMethod: fcManualForm.collectionMethod,
      frequency: fcManualForm.frequency,
      parserSpec: fcManualForm.parserSpec,
      baselineReading: fcManualForm.baselineReading,
      failureMonitor: fcManualForm.failureMonitor,
      autoProxy: fcManualForm.autoProxy,
      notifyEnabled: true,
      lastCollectStatus: 'PENDING'
    }, { suppressErrorMessage: true })
    const payload = response?.data || {}
    closeFcManualDialog()
    fcCardsNotice.value = 'FC卡已保存为草稿'
    await loadFcCards()
    selectedFcCardId.value = payload.id || selectedFcCardId.value
    activeConfigTab.value = 'fc'
  } catch (error) {
    fcCardsError.value = resolveErrorMessage(error, 'FC卡保存失败')
  } finally {
    fcCardsManualSaving.value = false
  }
}

function openFcImportDialog() {
  fcImportForm.markdown = ''
  fcCardsError.value = ''
  fcCardsNotice.value = ''
  showFcImportDialog.value = true
}

function closeFcImportDialog() {
  showFcImportDialog.value = false
  fcCardsImporting.value = false
}

async function importFcCards() {
  const markdown = fcImportForm.markdown.trim()
  if (!markdown) {
    fcCardsError.value = '请先粘贴FC卡Markdown'
    return
  }
  const stock = selectedStock.value
  fcCardsImporting.value = true
  fcCardsError.value = ''
  fcCardsNotice.value = ''
  try {
    const response = await stockWatchHttp.post('/stock-watch/fc-cards/import', {
      symbol: stock.symbol,
      market: stock.market,
      stockName: stock.name,
      markdown
    }, { suppressErrorMessage: true })
    const payload = response?.data || {}
    fcCardsNotice.value = payload.message || `已导入 ${payload.imported || 0} 张FC卡`
    closeFcImportDialog()
    await loadFcCards()
    activeConfigTab.value = 'fc'
  } catch (error) {
    fcCardsError.value = resolveErrorMessage(error, 'FC卡导入失败')
  } finally {
    fcCardsImporting.value = false
  }
}

function syncFcEditForm() {
  const card = selectedFcCard.value
  fcEditForm.baselineReading = card?.baselineReading || ''
  fcEditForm.failureMonitor = card?.failureMonitor || ''
  fcEditForm.lastCollectStatus = card?.lastCollectStatus || 'PENDING'
  fcEditForm.lastCollectNote = card?.lastCollectNote || ''
}

async function saveFcCardBaseline() {
  const card = selectedFcCard.value
  if (!card?.id || fcCardsSaving.value) return
  fcCardsSaving.value = true
  fcCardsError.value = ''
  fcCardsNotice.value = ''
  try {
    await stockWatchHttp.put(`/stock-watch/fc-cards/${card.id}`, {
      baselineReading: fcEditForm.baselineReading,
      failureMonitor: fcEditForm.failureMonitor,
      lastCollectStatus: fcEditForm.lastCollectStatus,
      lastCollectNote: fcEditForm.lastCollectNote
    }, { suppressErrorMessage: true })
    fcCardsNotice.value = 'FC卡基线与失效监控已保存'
    await loadFcCards()
  } catch (error) {
    fcCardsError.value = resolveErrorMessage(error, 'FC卡保存失败')
  } finally {
    fcCardsSaving.value = false
  }
}

function fcStatusLabel(status) {
  const value = String(status || '').toUpperCase()
  if (value === 'ACTIVE') return '已激活'
  if (value === 'INVALID') return '不可执行'
  if (value === 'SUSPENDED') return '暂停'
  return '草稿'
}

function fcStatusTone(status) {
  const value = String(status || '').toUpperCase()
  if (value === 'ACTIVE') return 'green'
  if (value === 'INVALID') return 'red'
  if (value === 'SUSPENDED') return 'amber'
  return 'amber'
}

function fcCollectionLabel(method) {
  const value = String(method || '')
  if (value.includes('半自动')) return '半自动'
  if (value.includes('人工')) return '人工'
  if (value.includes('自动')) return '自动'
  return value || '未标注'
}

function fcCollectionTone(method) {
  const value = String(method || '')
  if (value.includes('人工')) return 'amber'
  if (value.includes('半自动')) return 'violet'
  if (value.includes('自动')) return 'green'
  return 'gray'
}

async function loadTechnicalAnalysisHistory() {
  const stock = selectedStock.value
  if (!stock?.symbol) return
  technicalAnalysisLoading.value = true
  technicalAnalysisError.value = ''
  deepseekSessionNotice.value = ''
  try {
    const response = await stockWatchHttp.get('/stock-watch/technical-analysis', {
      params: {
        symbol: stock.symbol,
        market: stock.market,
        date: selectedAnalysisDate.value,
        periodType: selectedAnalysisPeriodType.value,
        periodKey: selectedAnalysisPeriodKey.value,
        days: selectedAnalysisPeriodType.value === 'DAY' ? 30 : 12
      },
      suppressErrorMessage: true
    })
    const payload = response?.data || {}
    const periods = Array.isArray(payload.periods)
      ? payload.periods
      : (Array.isArray(payload.days) ? payload.days : buildAnalysisPeriods(selectedAnalysisPeriodType.value, {}))
    const records = Array.isArray(payload.records) ? payload.records : []
    analysisPeriods.value = periods.map(normalizeAnalysisPeriod)
    if (payload.selectedPeriodKey && payload.selectedPeriodKey !== selectedAnalysisPeriodKey.value) {
      selectedAnalysisPeriodKey.value = payload.selectedPeriodKey
    }
    selectedAnalysisRecords.value = records.map(normalizeAnalysisRecord)
    selectedAnalysisSession.value = normalizeAnalysisSession(payload.session, stock)
  } catch (error) {
    technicalAnalysisError.value = resolveErrorMessage(error, '技术面分析历史加载失败')
    selectedAnalysisRecords.value = []
    selectedAnalysisSession.value = defaultAnalysisSession(stock)
  } finally {
    technicalAnalysisLoading.value = false
  }
}

async function runTechnicalAnalysisNow() {
  const stock = selectedStock.value
  if (!stock?.symbol || technicalAnalysisRunning.value) return
  technicalAnalysisRunning.value = true
  technicalAnalysisError.value = ''
  deepseekSessionNotice.value = ''
  try {
    const response = await technicalAnalysisHttp.post('/stock-watch/technical-analysis/run', {
      symbol: stock.symbol,
      market: stock.market,
      name: stock.name,
      periodType: selectedAnalysisPeriodType.value,
      forceNewSession: false,
      ignoreTradingHours: true
    }, { suppressErrorMessage: true })
    const record = response?.data?.record
    if (record?.periodKey && record.periodKey !== selectedAnalysisPeriodKey.value) {
      selectedAnalysisPeriodKey.value = record.periodKey
    }
    if (record?.date && record.date !== selectedAnalysisDate.value) {
      selectedAnalysisDate.value = record.date
    }
    await loadTechnicalAnalysisHistory()
    await loadPredictionHistory()
  } catch (error) {
    technicalAnalysisError.value = resolveErrorMessage(error, '立即分析失败，请确认 DeepSeek bridge 已启动且网页版可用')
  } finally {
    technicalAnalysisRunning.value = false
  }
}

async function loadPredictionHistory() {
  const stock = selectedStock.value
  if (!stock?.symbol) return
  const loadId = ++predictionHistoryLoadId
  predictionLoading.value = true
  predictionError.value = ''
  try {
    const payload = await loadAllPredictionPages(async (params) => {
      const response = await stockWatchHttp.get('/stock-watch/technical-analysis/predictions', {
        params: { symbol: stock.symbol, market: stock.market, ...params },
        suppressErrorMessage: true
      })
      return response?.data || {}
    }, 200)
    if (loadId !== predictionHistoryLoadId) return
    predictionSummary.value = {
      total: Number(payload.summary?.total || 0),
      evaluated: Number(payload.summary?.evaluated || 0),
      pending: Number(payload.summary?.pending || 0),
      directionHitRate: finiteOrNull(payload.summary?.directionHitRate),
      currentPolicyDirectionHitRate: finiteOrNull(payload.summary?.currentPolicyDirectionHitRate),
      strictDirectionHitRate: finiteOrNull(payload.summary?.strictDirectionHitRate),
      currentPolicyScored: Number(payload.summary?.currentPolicyScored || 0),
      currentPolicyLimitsPct: payload.summary?.currentPolicyLimitsPct || null,
      directionalDivergenceBasis: payload.summary?.directionalDivergenceBasis || null,
      directionalForecastScored: Number(payload.summary?.directionalForecastScored || 0),
      directionalDivergenceCount: Number(payload.summary?.directionalDivergenceCount || 0),
      directionalDivergenceRate: finiteOrNull(payload.summary?.directionalDivergenceRate),
      directionalNonDivergenceHitRate: finiteOrNull(payload.summary?.directionalNonDivergenceHitRate),
      rangeCloseHitRate: finiteOrNull(payload.summary?.rangeCloseHitRate),
      averageBrierScore: finiteOrNull(payload.summary?.averageBrierScore),
      brierPolicyMixed: Boolean(payload.summary?.brierPolicyMixed)
    }
    predictionRecords.value = Array.isArray(payload.records) ? payload.records : []
  } catch (error) {
    if (loadId !== predictionHistoryLoadId) return
    predictionError.value = resolveErrorMessage(error, '预测复盘加载失败')
    predictionRecords.value = []
    predictionSummary.value = emptyPredictionSummary()
  } finally {
    if (loadId === predictionHistoryLoadId) predictionLoading.value = false
  }
}

function finiteOrNull(value) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function formatMetricPercent(value) {
  if (value === null || value === undefined || value === '') return '--'
  const number = Number(value)
  return Number.isFinite(number) ? `${number.toFixed(1)}%` : '--'
}

function formatPredictionNumber(value, digits = 2) {
  if (value === null || value === undefined || value === '') return '--'
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(digits) : '--'
}

function formatSignedPercent(value) {
  if (value === null || value === undefined || value === '') return '--'
  const number = Number(value)
  if (!Number.isFinite(number)) return '--'
  return `${number > 0 ? '+' : ''}${number.toFixed(2)}%`
}

function predictionDirectionLabel(direction) {
  if (direction === 'UP') return '看涨'
  if (direction === 'DOWN') return '看跌'
  return '震荡'
}

function predictionDirectionTone(direction) {
  if (direction === 'UP') return 'up'
  if (direction === 'DOWN') return 'down'
  return 'sideways'
}

function predictionStatusLabel(prediction) {
  if (prediction.status !== 'EVALUATED') return '待验真'
  if (prediction.currentPolicyDirectionHit == null) return '复核数据不足'
  return prediction.currentPolicyDirectionHit ? '复核命中' : '复核未中'
}

function predictionDaySummary(predictions) {
  const counts = new Map()
  predictions.forEach((prediction) => {
    const horizon = prediction.horizon === 'T1' ? 'T+1' : prediction.horizon === 'T5' ? 'T+5' : (prediction.horizon || '未知期限')
    const label = `${horizon} ${predictionDirectionLabel(prediction.direction)}`
    counts.set(label, (counts.get(label) || 0) + 1)
  })
  return [...counts].map(([label, count]) => `${label} ${count}`).join(' · ')
}

function togglePredictionGroupByDay() {
  predictionGroupByDay.value = !predictionGroupByDay.value
  predictionListExpanded.value = false
  expandedPredictionDates.value = []
}

function togglePredictionDay(date) {
  expandedPredictionDates.value = expandedPredictionDates.value.includes(date)
    ? expandedPredictionDates.value.filter((item) => item !== date)
    : [...expandedPredictionDates.value, date]
}

function showEvaluatedPredictions() {
  predictionListMode.value = 'evaluated'
  predictionListExpanded.value = false
  expandedPredictionDates.value = []
}

function showDivergedPredictions() {
  if (!hasReturnSignDivergence.value) return
  predictionListMode.value = 'diverged'
  predictionListExpanded.value = false
  expandedPredictionDates.value = []
}

function showAllPredictions() {
  predictionListMode.value = 'all'
  predictionListExpanded.value = false
  expandedPredictionDates.value = []
}

function togglePredictionListExpanded() {
  predictionListExpanded.value = !predictionListExpanded.value
}

async function loadExternalEvidence(showNotice = true) {
  const stock = selectedStock.value
  if (!stock?.symbol || externalEvidenceLoading.value) return
  externalEvidenceLoading.value = true
  externalEvidenceError.value = ''
  if (showNotice) {
    externalEvidenceNotice.value = ''
  }
  try {
    const response = await stockWatchHttp.get('/stock-watch/external-research/evidence', {
      params: {
        symbol: stock.symbol,
        market: stock.market
      },
      suppressErrorMessage: true
    })
    const payload = response?.data || {}
    externalEvidence.value = payload
    if (showNotice) {
      if (payload.status === 'MATCH') {
        externalEvidenceNotice.value = '外部证据已刷新：TradingView 与 Eastmoney 主字段匹配'
      } else if (payload.ok) {
        externalEvidenceNotice.value = '外部证据已刷新，请关注偏差项'
      } else {
        externalEvidenceNotice.value = '外部证据已返回，但至少一个数据源不可用'
      }
    }
  } catch (error) {
    externalEvidenceError.value = resolveErrorMessage(error, '外部证据刷新失败')
  } finally {
    externalEvidenceLoading.value = false
  }
}

function refreshActiveConfig() {
  if (activeConfigTab.value === 'llm') {
    return loadAnalysisPolicy(true)
  }
  if (activeConfigTab.value === 'mail') {
    return loadDeliveryConfig()
  }
  return Promise.resolve()
}

function saveActiveConfig() {
  if (activeConfigTab.value === 'llm') {
    return saveAnalysisPolicy()
  }
  if (activeConfigTab.value === 'mail') {
    return saveDeliveryConfig()
  }
  return Promise.resolve()
}

async function loadAnalysisPolicy(showNotice = false) {
  const stock = selectedStock.value
  if (!stock?.symbol) return
  analysisPolicyLoading.value = true
  analysisPolicyError.value = ''
  if (showNotice) analysisPolicyNotice.value = ''
  try {
    const response = await stockWatchHttp.get(
      `/stock-watch/watchlist/${encodeURIComponent(stock.market)}/${encodeURIComponent(stock.symbol)}/analysis-policy`,
      { suppressErrorMessage: true }
    )
    applyAnalysisPolicyResponse(response?.data || {}, { syncForm: true })
    if (showNotice) {
      analysisPolicyNotice.value = '分析策略已刷新'
    }
  } catch (error) {
    if (isMissingWatchStockError(error)) {
      markAnalysisPolicyMissing(stock)
      if (showNotice) {
        analysisPolicyNotice.value = '该股票尚未加入后端监控；点击“加入并保存策略”后才会启用自动分析'
      }
      return
    }
    analysisPolicyError.value = resolveErrorMessage(error, '分析策略加载失败')
  } finally {
    analysisPolicyLoading.value = false
  }
}

async function saveAnalysisPolicy() {
  const stock = selectedStock.value
  if (!stock?.symbol || analysisPolicySaving.value) return
  clampAnalysisInterval()
  const request = {
    symbol: stock.symbol,
    market: stock.market,
    payload: buildAnalysisPolicyPayload({
      mode: analysisPolicyForm.mode,
      intervalMinutes: analysisPolicyForm.intervalMinutes,
      anomalyTriggerMode: analysisPolicyForm.anomalyTriggerMode,
      weeklyReviewEnabled: analysisPolicyForm.weeklyReviewEnabled,
      monthlyReviewEnabled: analysisPolicyForm.monthlyReviewEnabled,
      version: analysisPolicyForm.version
    })
  }
  analysisPolicySaving.value = true
  analysisPolicyError.value = ''
  analysisPolicyNotice.value = ''
  try {
    await ensureBackendWatchStockForPolicy(stock, { syncForm: false })
    const response = await stockWatchHttp.put(
      `/stock-watch/watchlist/${encodeURIComponent(request.market)}/${encodeURIComponent(request.symbol)}/analysis-policy`,
      request.payload,
      { suppressErrorMessage: true }
    )
    applyAnalysisPolicyResponse(response?.data || {}, { syncForm: true })
    analysisPolicyNotice.value = '单股分析策略已保存'
  } catch (error) {
    analysisPolicyError.value = resolveErrorMessage(error, '分析策略保存失败')
  } finally {
    analysisPolicySaving.value = false
  }
}

async function ensureBackendWatchStockForPolicy(stock, options = {}) {
  if (!stock?.symbol) return false
  if (stock.analysisPolicy?.capability === 'stock.unified-analysis.v1' && Number.isFinite(Number(stock.analysisPolicy?.version))) {
    return false
  }
  const saved = await saveBackendWatchStock(stock, { includeGroupKey: Boolean(stock.groupKeyExplicit) })
  const merged = mergeWatchStock(saved || stock)
  if (options.syncForm !== false && merged.symbol === selectedStock.value?.symbol && merged.market === selectedStock.value?.market) {
    syncAnalysisPolicyForm(merged.analysisPolicy)
  }
  return true
}

function markAnalysisPolicyMissing(stock) {
  if (!stock?.symbol) return
  const target = watchlist.find((item) => item.symbol === stock.symbol && item.market === stock.market)
  if (target) {
    target.analysisPolicy = null
    target.analysisSchedule = null
  }
  if (stock.symbol === selectedStock.value?.symbol && stock.market === selectedStock.value?.market) {
    syncAnalysisPolicyForm(null)
  }
}

function buildAnalysisPolicyPayload(source = analysisPolicyForm) {
  const mode = normalizeAnalysisMode(source.mode)
  const reviewEnabled = supportsAnalysisReview(mode)
  return {
    mode,
    intervalMinutes: clampNumber(source.intervalMinutes, 10, 1440, 30),
    anomalyTriggerMode: normalizeAnomalyTriggerMode(source.anomalyTriggerMode),
    weeklyReviewEnabled: Boolean(source.weeklyReviewEnabled && reviewEnabled),
    monthlyReviewEnabled: Boolean(source.monthlyReviewEnabled && reviewEnabled),
    expectedVersion: Number(source.version) || 0
  }
}

function applyAnalysisPolicyResponse(payload, options = {}) {
  const stock = resolveAnalysisPolicyStock(payload)
  if (!stock) return
  stock.analysisPolicy = normalizeAnalysisPolicyPayload(payload.analysisPolicy)
  stock.analysisSchedule = normalizeAnalysisSchedulePayload(payload.analysisSchedule)
  if (options.syncForm !== false && stock.symbol === selectedStock.value?.symbol && stock.market === selectedStock.value?.market) {
    syncAnalysisPolicyForm(stock.analysisPolicy)
  }
}

function resolveAnalysisPolicyStock(payload) {
  const fallback = selectedStock.value
  const symbol = String(payload?.symbol || fallback?.symbol || '')
  const market = String(payload?.market || fallback?.market || '')
  return watchlist.find((item) => item.symbol === symbol && item.market === market) || fallback || null
}

function isMissingWatchStockError(error) {
  return resolveErrorMessage(error, '').includes('监控股票不存在')
}

async function loadDeliveryConfig() {
  const stock = selectedStock.value
  if (!stock?.symbol) return
  deliveryLoading.value = true
  deliveryError.value = ''
  try {
    const response = await stockWatchHttp.get('/stock-watch/delivery-config', {
      params: {
        symbol: stock.symbol,
        market: stock.market
      },
      suppressErrorMessage: true
    })
    applyDeliveryConfig(response?.data || {})
    await previewDeliveryConfig(false)
  } catch (error) {
    deliveryError.value = resolveErrorMessage(error, '邮件配置加载失败')
  } finally {
    deliveryLoading.value = false
  }
}

async function saveDeliveryConfig() {
  const recipients = parseDeliveryRecipients(deliveryForm.recipientsText)
  if (recipients.length === 0) {
    deliveryError.value = '收件邮箱为必填项，请至少填写一个合法邮箱'
    deliveryNotice.value = ''
    return
  }
  deliverySaving.value = true
  deliveryError.value = ''
  deliveryNotice.value = ''
  try {
    const response = await stockWatchHttp.put('/stock-watch/delivery-config', buildDeliveryPayload(), {
      suppressErrorMessage: true
    })
    applyDeliveryConfig(response?.data || {})
    deliveryNotice.value = '单股邮件配置已保存'
    await previewDeliveryConfig(false)
  } catch (error) {
    deliveryError.value = resolveErrorMessage(error, '邮件配置保存失败')
  } finally {
    deliverySaving.value = false
  }
}

async function previewDeliveryConfig(showNotice = true) {
  const stock = selectedStock.value
  if (!stock?.symbol) return
  deliveryPreviewLoading.value = true
  if (showNotice) {
    deliveryError.value = ''
    deliveryNotice.value = ''
  }
  try {
    const response = await stockWatchHttp.get('/stock-watch/delivery-config/preview', {
      params: {
        symbol: stock.symbol,
        market: stock.market
      },
      suppressErrorMessage: true
    })
    const payload = response?.data || {}
    deliveryPreview.value = Array.isArray(payload.preview) ? payload.preview.map(normalizeDeliveryPreviewRecord) : []
    deliveryStats.value = normalizeDeliveryStats(payload.stats)
    if (showNotice) {
      deliveryNotice.value = `找到 ${deliveryPreview.value.length} 条符合条件且未发送过的结论`
    }
  } catch (error) {
    if (showNotice) {
      deliveryError.value = resolveErrorMessage(error, '未发送结论预览失败')
    }
  } finally {
    deliveryPreviewLoading.value = false
  }
}

async function testDeliveryEmail() {
  const recipients = parseDeliveryRecipients(deliveryForm.recipientsText)
  if (recipients.length === 0) {
    deliveryError.value = '收件邮箱为必填项，请至少填写一个合法邮箱'
    deliveryNotice.value = ''
    return
  }
  deliveryTesting.value = true
  deliveryError.value = ''
  deliveryNotice.value = ''
  try {
    await stockWatchHttp.post('/stock-watch/delivery-config/test-email', {
      symbol: selectedStock.value.symbol,
      market: selectedStock.value.market,
      recipients
    }, { suppressErrorMessage: true })
    deliveryNotice.value = '测试邮件已发送'
  } catch (error) {
    deliveryError.value = resolveErrorMessage(error, '测试邮件发送失败，请检查 SMTP 配置')
  } finally {
    deliveryTesting.value = false
  }
}

async function dispatchDeliveryNow() {
  if (!deliveryForm.id) {
    deliveryError.value = '请先保存当前股票的邮件配置'
    deliveryNotice.value = ''
    return
  }
  deliveryDispatching.value = true
  deliveryError.value = ''
  deliveryNotice.value = ''
  try {
    const response = await stockWatchHttp.post('/stock-watch/delivery-config/dispatch-now', {
      symbol: selectedStock.value.symbol,
      market: selectedStock.value.market
    }, { suppressErrorMessage: true })
    const payload = response?.data || {}
    deliveryNotice.value = payload.message || '发送任务已执行'
    await loadDeliveryConfig()
  } catch (error) {
    deliveryError.value = resolveErrorMessage(error, '立即发送失败')
  } finally {
    deliveryDispatching.value = false
  }
}

function applyDeliveryConfig(payload) {
  deliveryForm.id = payload.id || null
  deliveryForm.enabled = Boolean(payload.enabled)
  deliveryForm.recipientsText = Array.isArray(payload.recipients) ? payload.recipients.join('\n') : ''
  deliveryForm.frequencyType = normalizeDeliveryFrequency(payload.frequencyType)
  deliveryForm.intervalMinutes = Math.max(5, Number(payload.intervalMinutes || 120))
  const policy = payload.triggerPolicy || {}
  deliveryForm.minConfidence = Number(policy.minConfidence || 0)
  deliveryForm.keywordsText = Array.isArray(policy.keywords) ? policy.keywords.join(', ') : ''
  deliveryForm.tones = Array.isArray(policy.tones) ? policy.tones.map(String) : []
  deliveryForm.lastDispatchStatus = payload.lastDispatchStatus || ''
  deliveryForm.lastDispatchMessage = payload.lastDispatchMessage || ''
  deliveryForm.lastDispatchAt = payload.lastDispatchAt || ''
  deliveryForm.activeFrom = payload.activeFrom || ''
  deliveryStats.value = normalizeDeliveryStats(payload.stats)
}

function buildDeliveryPayload() {
  return {
    symbol: selectedStock.value.symbol,
    market: selectedStock.value.market,
    stockName: selectedStock.value.name,
    enabled: Boolean(deliveryForm.enabled),
    recipients: parseDeliveryRecipients(deliveryForm.recipientsText),
    frequencyType: normalizeDeliveryFrequency(deliveryForm.frequencyType),
    intervalMinutes: Math.max(5, Math.min(1440, Number(deliveryForm.intervalMinutes) || 120)),
    triggerPolicy: {
      sendOnNewAnalysis: true,
      tones: deliveryForm.tones,
      minConfidence: Math.max(0, Math.min(1, Number(deliveryForm.minConfidence) || 0)),
      keywords: parseDeliveryKeywords(deliveryForm.keywordsText)
    }
  }
}

function normalizeDeliveryFrequency(value) {
  const normalized = String(value || 'MANUAL').toUpperCase()
  if (normalized === 'EVERY_30_MINUTES') return 'INTERVAL'
  return ['IMMEDIATE', 'INTERVAL', 'DAILY_CLOSE', 'MANUAL'].includes(normalized) ? normalized : 'MANUAL'
}

function normalizeDeliveryStats(stats) {
  return {
    PENDING: Number(stats?.PENDING || stats?.pending || 0),
    SENT: Number(stats?.SENT || stats?.sent || 0),
    FAILED: Number(stats?.FAILED || stats?.failed || 0)
  }
}

function normalizeDeliveryPreviewRecord(record) {
  return {
    id: record?.id || '',
    date: record?.date || '',
    time: record?.time || '--',
    title: record?.title || '技术面分析结论',
    conclusion: truncateText(record?.conclusion || '', 180),
    mainForceIntent: normalizeMainForceIntent(record?.mainForceIntent, record?.mainForceIntentStatus, record?.evidenceQuality),
    pendingRecipients: Number(record?.pendingRecipients || 0)
  }
}

function setDeliveryInterval(minutes) {
  deliveryForm.frequencyType = 'INTERVAL'
  deliveryForm.intervalMinutes = minutes
}

function toggleDeliveryTone(tone) {
  const index = deliveryForm.tones.indexOf(tone)
  if (index >= 0) {
    deliveryForm.tones.splice(index, 1)
  } else {
    deliveryForm.tones.push(tone)
  }
}

function parseDeliveryRecipients(raw) {
  return String(raw || '')
    .split(/[,;\s\n]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .filter((item, index, array) => array.indexOf(item) === index)
    .filter((item) => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(item))
}

function parseDeliveryKeywords(raw) {
  return String(raw || '')
    .split(/[,;，、\n]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .filter((item, index, array) => array.indexOf(item) === index)
}

function truncateText(text, maxLength) {
  const value = String(text || '')
  return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value
}

function normalizeAnalysisPeriod(period) {
  const key = String(period?.key || period?.periodKey || period?.date || '')
  return {
    key,
    date: String(period?.date || period?.endDate || key),
    startDate: String(period?.startDate || period?.date || key),
    endDate: String(period?.endDate || period?.date || key),
    label: String(period?.label || key),
    sublabel: String(period?.sublabel || period?.subtitle || ''),
    count: Number(period?.count || 0)
  }
}

function normalizeAnalysisRecord(record) {
  return {
    id: record?.id || `${record?.periodKey || record?.date || selectedAnalysisPeriodKey.value}-${record?.time || Date.now()}`,
    date: record?.date || selectedAnalysisDate.value,
    periodType: record?.periodType || selectedAnalysisPeriodType.value,
    periodKey: record?.periodKey || selectedAnalysisPeriodKey.value,
    time: record?.time || '--',
    provider: record?.provider || 'DeepSeek 网页',
    stance: record?.stance || '待确认',
    tone: normalizeAnalysisTone(record?.tone),
    confidence: formatConfidence(record?.confidence),
    title: record?.title || '技术面分析结论',
    conclusion: record?.conclusion || record?.rawResponse || '本轮未返回可展示结论。',
    intradayForecast: record?.intradayForecast || null,
    evidence: normalizeEvidence(record?.evidence),
    missingData: normalizeEvidence(record?.missingData),
    mainForceIntent: normalizeMainForceIntent(record?.mainForceIntent, record?.mainForceIntentStatus, record?.evidenceQuality),
    mainForceIntentStatus: record?.mainForceIntentStatus || '',
    mainForceIntentWarnings: normalizeEvidence(record?.mainForceIntentWarnings),
    riskPatternSummary: normalizeRiskPatternSummary(record?.riskPatternSummary),
    sourceRecordIds: normalizeEvidence(record?.sourceRecordIds),
    promptHash: record?.promptHash || record?.status || '',
    conversationUrl: record?.conversationUrl || '',
    traceable: isTraceableDeepseekUrl(record?.conversationUrl)
  }
}

function normalizeRiskPatternSummary(raw) {
  if (!raw || typeof raw !== 'object') return null
  const patterns = Array.isArray(raw.patterns) ? raw.patterns : []
  const visiblePatterns = patterns
    .filter((item) => ['triggered', 'watch'].includes(String(item?.status || '').toLowerCase()))
    .map((item) => ({
      key: `${item.patternId || ''}-${item.status || ''}`,
      label: `${item.patternId || '--'} ${item.riskStructureName || '--'} · ${item.statusLabel || item.status || '--'}`,
      description: item.description || '',
      invalidatesWhen: item.invalidatesWhen || ''
    }))
  const skippedReasons = normalizeEvidence(raw.skippedReasons).slice(0, 2)
  if (!visiblePatterns.length && skippedReasons.length) {
    visiblePatterns.push({
      key: 'skipped',
      label: `跳过：${skippedReasons.join('；')}`,
      description: '',
      invalidatesWhen: ''
    })
  }
  return {
    title: raw.title || '价量风险结构识别',
    status: raw.status || '',
    statusLabel: raw.statusLabel || '--',
    semanticBoundary: raw.semanticBoundary || 'L0 只识别可观察价量结构，不能证明操纵或真实主力身份。',
    conclusionText: raw.conclusionText || '',
    valueText: raw.valueText || '',
    nextWatch: raw.nextWatch || '',
    dataLimitText: raw.dataLimitText || '',
    visiblePatterns
  }
}

function normalizeAnalysisSession(session, stock = selectedStock.value) {
  const fallback = defaultAnalysisSession(stock)
  if (!session) return fallback
  const contextUsage = Number(session.contextUsage)
  return {
    title: session.title || fallback.title,
    sessionId: session.sessionId || session.sessionKey || fallback.sessionId,
    status: session.status || fallback.status,
    contextUsage: Number.isFinite(contextUsage) ? Math.max(0, Math.min(100, Math.round(contextUsage))) : fallback.contextUsage,
    lastRun: session.lastRun || fallback.lastRun,
    nextRun: session.nextRun || fallback.nextRun,
    rotatePolicy: session.rotatePolicy || fallback.rotatePolicy,
    traceable: Boolean(session.traceable && session.url),
    url: session.url || ''
  }
}

function normalizeMainForceIntent(raw, status = 'VALID', fallbackEvidenceQuality = '') {
  const normalizedStatus = String(status || '').toUpperCase()
  if (normalizedStatus && !['VALID', 'VALID_WITH_WARNINGS'].includes(normalizedStatus)) return null
  let value = raw
  if (typeof raw === 'string') {
    try {
      value = JSON.parse(raw)
    } catch (error) {
      value = null
    }
  }
  if (!value || typeof value !== 'object') return null
  const confidence = Number(value.confidence)
  const intent = String(value.intent || 'UNKNOWN').toUpperCase()
  const timeWindow = String(value.timeWindow || 'INTRADAY').toUpperCase()
  const evidenceQuality = String(value.evidenceQuality || fallbackEvidenceQuality || '').toUpperCase()
  return {
    intent,
    intentLabel: mainForceIntentLabel(intent),
    timeWindow,
    timeWindowLabel: mainForceWindowLabel(timeWindow),
    evidenceQuality,
    provenanceLabel: mainForceProvenanceLabel(evidenceQuality),
    confidence: Number.isFinite(confidence) ? `${Math.round(Math.max(0, Math.min(1, confidence)) * 100)}%` : '--',
    risk: String(value.risk || '').trim(),
    watchPoints: normalizeEvidence(value.watchPoints).slice(0, 4),
    evidence: normalizeEvidence(value.evidence).slice(0, 4),
    counterEvidence: normalizeEvidence(value.counterEvidence).slice(0, 4)
  }
}

function mainForceProvenanceLabel(evidenceQuality) {
  if (evidenceQuality === 'SERVER_PROFILE_V1') {
    return '主力证据画像（服务器确定性分类，非行动信号）'
  }
  if (['LEGACY_PATH_MEMBERSHIP_ONLY', 'LEGACY_UNVERIFIED'].includes(evidenceQuality)) {
    return '主力意图（模型推断）'
  }
  return '主力意图（来源未验证）'
}

function mainForceIntentLabel(intent) {
  const labels = {
    ACCUMULATE: '吸筹/承接',
    DISTRIBUTE: '派发/减仓',
    WASH: '洗盘',
    PULL_UP: '拉升',
    SUPPORT: '护盘',
    SUPPRESS: '压盘',
    ROTATE: '轮动换手',
    NEUTRAL: '中性',
    UNKNOWN: '未知'
  }
  return labels[intent] || intent || '未知'
}

function mainForceWindowLabel(timeWindow) {
  const labels = {
    INTRADAY: '日内',
    THREE_DAY: '近3日',
    FIVE_DAY: '近5日',
    TWENTY_DAY: '近20日'
  }
  return labels[timeWindow] || timeWindow || '--'
}

function normalizeEvidence(evidence) {
  if (Array.isArray(evidence)) {
    return evidence.map((item) => String(item || '').trim()).filter(Boolean).slice(0, 8)
  }
  if (typeof evidence === 'string') {
    try {
      const parsed = JSON.parse(evidence)
      return normalizeEvidence(parsed)
    } catch (error) {
      return evidence.split(/[;\n]/).map((item) => item.trim()).filter(Boolean).slice(0, 8)
    }
  }
  return []
}

function normalizeAnalysisTone(tone) {
  const value = String(tone || '').toLowerCase()
  if (['green', 'blue', 'amber', 'red'].includes(value)) return value
  if (value.includes('risk') || value.includes('bear') || value.includes('空') || value.includes('风险')) return 'red'
  if (value.includes('bull') || value.includes('多') || value.includes('强')) return 'green'
  if (value.includes('wait') || value.includes('neutral') || value.includes('等待')) return 'amber'
  return 'blue'
}

function marketToneFromSignedValue(value, neutralTone = 'ink') {
  const text = String(value ?? '').trim()
  if (!text || text === '--') return neutralTone
  const match = text.replace(/,/g, '').match(/[+-]?\d+(?:\.\d+)?/)
  if (!match) return neutralTone
  return marketToneFromNumber(Number.parseFloat(match[0]), neutralTone)
}

function marketToneFromNumber(value, neutralTone = 'ink') {
  if (!Number.isFinite(value) || value === 0) return neutralTone
  return value > 0 ? 'red' : 'green'
}

function metricValue(key, label, values, options = {}) {
  const normalizedValues = values.map((value) => String(value ?? '').trim())
  const invalid = normalizedValues.some((value) => /^(nan|null|undefined)$/i.test(value))
  const presentValues = normalizedValues.filter(hasMetricValue)
  const status = invalid ? 'INVALID' : (presentValues.length ? 'VALID' : 'UNAVAILABLE')
  return {
    key,
    label,
    status,
    value: presentValues.length ? presentValues.join(' / ') : '--',
    reason: status === 'VALID' ? options.reason : (options.unavailableReason || '真实数据源未返回该指标'),
    tone: options.tone || 'ink',
    source: options.source || 'runtime'
  }
}

function hasMetricValue(value) {
  const text = String(value ?? '').trim()
  return Boolean(text)
    && text !== '--'
    && !['待抓取', '待计算', '未计算', 'N/A'].includes(text.toUpperCase())
}

function selectAnalysisPeriod(period) {
  if (!period?.key) return
  selectedAnalysisPeriodKey.value = period.key
  if (period.date) {
    selectedAnalysisDate.value = period.date
  }
}

function selectCurrentAnalysisPeriod() {
  const [current] = buildAnalysisPeriods(selectedAnalysisPeriodType.value, {})
  selectAnalysisPeriod(current)
}

function switchAnalysisPeriod(periodType) {
  const nextType = normalizeAnalysisPeriodType(periodType)
  selectedAnalysisPeriodType.value = nextType
  const [current] = buildAnalysisPeriods(nextType, {})
  if (current?.key) {
    selectedAnalysisPeriodKey.value = current.key
    selectedAnalysisDate.value = current.date
  }
}

function formatConfidence(value) {
  if (value == null || value === '') return '--'
  if (typeof value === 'string' && value.includes('%')) return value
  const number = Number(value)
  if (!Number.isFinite(number)) return String(value)
  return number <= 1 ? `${Math.round(number * 100)}%` : `${Math.round(number)}%`
}

function resolveErrorMessage(error, fallback) {
  return error?.response?.data?.message
    || error?.response?.data?.msg
    || error?.msg
    || error?.message
    || fallback
}

function isTraceableDeepseekUrl(url) {
  const value = String(url || '')
  return value.startsWith('https://chat.deepseek.com') && !['https://chat.deepseek.com', 'https://chat.deepseek.com/'].includes(value)
}

function openDeepseekSession(record = null) {
  const url = record?.conversationUrl || selectedAnalysisSession.value.url
  if (!isTraceableDeepseekUrl(url)) {
    deepseekSessionNotice.value = '还没有真实会话 URL；请先完成一次真实分析，或检查 bridge 是否返回 finalUrl。'
    return
  }
  window.open(url, '_blank', 'noopener,noreferrer')
}

function openDetail(symbol) {
  selectedSymbol.value = symbol
  viewMode.value = 'detail'
  activeConfigTab.value = 'quote'
  fcCardsError.value = ''
  fcCardsNotice.value = ''
  deliveryError.value = ''
  deliveryNotice.value = ''
  nextTick(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
}

function backToList() {
  viewMode.value = 'list'
  nextTick(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
}

function startAutoQuoteRefresh() {
  stopAutoQuoteRefresh()
  autoRefreshTimer.value = window.setInterval(() => {
    if (isTradingSessionNow()) {
      refreshQuotes('自动10m')
    }
  }, QUOTE_REFRESH_INTERVAL_MS)
}

function stopAutoQuoteRefresh() {
  if (autoRefreshTimer.value) {
    window.clearInterval(autoRefreshTimer.value)
    autoRefreshTimer.value = null
  }
}

function isTradingSessionNow(now = new Date()) {
  const chinaParts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).formatToParts(now)
  const values = Object.fromEntries(chinaParts.map((part) => [part.type, part.value]))
  const weekday = values.weekday || ''
  if (weekday.includes('六') || weekday.includes('日') || /sat|sun/i.test(weekday)) {
    return false
  }
  const minutes = Number(values.hour) * 60 + Number(values.minute)
  return (
    (minutes >= 9 * 60 + 30 && minutes <= 11 * 60 + 30)
    || (minutes >= 13 * 60 && minutes <= 15 * 60)
  )
}

async function refreshQuotes(mode = '手动') {
  if (demoModeActive.value) {
    quoteLastRefresh.value = currentTimeLabel()
    quoteRefreshMode.value = '演示'
    return
  }
  if (quoteRefreshing.value || watchlist.length === 0) {
    return
  }
  quoteRefreshing.value = true
  quoteError.value = ''
  quoteRefreshMode.value = mode
  try {
    const symbols = watchlist.map((stock) => `${stock.symbol}.${stock.market}`).join(',')
    const response = await stockWatchHttp.get('/stock-watch/quotes', { params: { symbols } })
    const quotes = Array.isArray(response.data) ? response.data : []
    let successCount = 0
    quotes.forEach((quote) => {
      if (quote?.ok) {
        const updated = applyQuoteToStock(quote)
        if (updated) successCount += 1
      } else if (quote?.symbol) {
        markQuoteSourceFailed(quote)
      }
    })
    quoteLastRefresh.value = currentTimeLabel()
    if (successCount === 0) {
      quoteError.value = '行情源未返回有效报价'
    }
  } catch (error) {
    quoteError.value = '行情刷新失败'
    console.warn('Failed to refresh stock quotes', error)
  } finally {
    quoteRefreshing.value = false
  }
}

function applyQuoteToStock(quote) {
  const stock = watchlist.find((item) => item.symbol === quote.symbol)
  if (!stock || quote.price == null) {
    return false
  }

  const previousName = stock.name
  stock.name = quote.name || stock.name
  stock.market = quote.market || stock.market
  stock.price = formatPrice(quote.price)
  stock.change = Number(quote.changePercent ?? 0)
  stock.refresh.source = '东方财富'
  stock.refresh.endpoint = 'quote snapshot'
  stock.refresh.last = quote.lastTime || '--'
  stock.refresh.next = '交易时段10m'
  if (stock.manual) {
    stock.status = '行情已刷新'
  }

  stock.technical.open = formatPrice(quote.open)
  stock.technical.high = formatPrice(quote.high)
  stock.technical.low = formatPrice(quote.low)
  stock.technical.volume = formatMoney(quote.amount)
  stock.technical.volumeRatio = formatNumberValue(quote.volumeRatio)
  stock.technical.turnover = formatPercentValue(quote.turnover)
  stock.technical.mainInflow = formatSignedMoney(quote.mainInflow)
  stock.technical.rsRank = stock.technical.rsRank === '--' ? '待计算' : stock.technical.rsRank

  stock.rawData = {
    ...stock.rawData,
    price: quote.price,
    change_pct: quote.changePercent,
    change_amount: quote.changeAmount,
    open: quote.open,
    high: quote.high,
    low: quote.low,
    previous_close: quote.previousClose,
    amount: quote.amount,
    turnover: quote.turnover,
    volume_ratio: quote.volumeRatio,
    main_inflow: quote.mainInflow,
    timestamp: quote.timestamp,
    source: quote.source
  }

  upsertQuoteSource(stock, true, `最新价 / 涨跌幅 / 成交额，${quote.updateTime || '刚刚'} 刷新`)
  if (stock.manual && quote.name && quote.name !== previousName) {
    persistCustomStocks()
  }
  return true
}

function markQuoteSourceFailed(quote) {
  const stock = watchlist.find((item) => item.symbol === quote.symbol)
  if (!stock) return
  upsertQuoteSource(stock, false, `行情刷新失败：${quote.reason || 'unknown'}`)
}

function upsertQuoteSource(stock, ok, desc) {
  const source = stock.sources.find((item) => item.name === 'Eastmoney quote' || item.name === 'Quote source')
  if (source) {
    source.name = 'Eastmoney quote'
    source.desc = desc
    source.ok = ok
    return
  }
  stock.sources.unshift({ name: 'Eastmoney quote', desc, ok })
}

function openAddDialog() {
  resetAddForm()
  showAddStockDialog.value = true
}

function closeAddDialog() {
  showAddStockDialog.value = false
  addStockError.value = ''
}

function openObservationDialog() {
  resetObservationForm(true)
  showObservationDialog.value = true
}

function closeObservationDialog() {
  showObservationDialog.value = false
  observationError.value = ''
}

async function loadAiRules() {
  try {
    const response = await stockWatchHttp.get('/stock-watch/ai-rules')
    aiRules.value = Array.isArray(response.data) ? response.data : []
  } catch (error) {
    console.warn('Failed to load AI watch rules', error)
  }
}

function openAiRuleDialog(rule = null) {
  resetAiRuleForm(rule)
  showAiRuleDialog.value = true
}

function closeAiRuleDialog() {
  showAiRuleDialog.value = false
  aiRuleError.value = ''
  aiRuleSaving.value = false
}

async function saveAiRule() {
  if (!aiRuleForm.conditionText.trim()) {
    aiRuleError.value = '请输入观察条件'
    return
  }
  aiRuleSaving.value = true
  aiRuleError.value = ''
  const payload = {
    symbol: selectedStock.value.symbol,
    market: selectedStock.value.market,
    name: aiRuleForm.name.trim() || `${selectedStock.value.name} AI观察`,
    conditionText: aiRuleForm.conditionText.trim(),
    severity: aiRuleForm.severity,
    minConfidence: clampNumber(aiRuleForm.minConfidence, 0.1, 1, 0.75),
    cooldownDays: Math.max(1, Number.parseInt(aiRuleForm.cooldownDays, 10) || 1),
    enabled: Boolean(aiRuleForm.enabled),
    notify: Boolean(aiRuleForm.notify),
    model: 'deepseek-chat'
  }
  try {
    if (aiRuleEditingId.value) {
      await stockWatchHttp.put(`/stock-watch/ai-rules/${aiRuleEditingId.value}`, payload)
    } else {
      await stockWatchHttp.post('/stock-watch/ai-rules', payload)
    }
    await loadAiRules()
    closeAiRuleDialog()
  } catch (error) {
    aiRuleError.value = '保存失败，请检查规则内容'
  } finally {
    aiRuleSaving.value = false
  }
}

async function deleteAiRule(rule) {
  if (!rule?.id) return
  try {
    await stockWatchHttp.delete(`/stock-watch/ai-rules/${rule.id}`)
    await loadAiRules()
  } catch (error) {
    console.warn('Failed to delete AI watch rule', error)
  }
}

async function confirmAddStock() {
  const normalized = normalizeStockCode(addStockForm.code)
  if (!normalized) {
    addStockError.value = '请输入 A 股 6 位编码，或港股 H/HK 前缀、5 位编码、HK 后缀'
    return
  }

  const exists = watchlist.some((stock) => stock.symbol === normalized.symbol && stock.market === normalized.market)
  if (exists) {
    addStockError.value = '该股票已在监控列表'
    return
  }

  const name = resolveStockDisplayName(normalized, addStockForm.name)
  const stock = createManualStock({
    symbol: normalized.symbol,
    market: normalized.market,
    name,
    groupKey: addStockForm.groupKey,
    image: addStockForm.image.trim()
  })
  addStockSaving.value = true
  addStockError.value = ''
  try {
    const saved = await saveBackendWatchStock(stock)
    const merged = mergeWatchStock(saved || stock)
    refreshQuotes('新增股票')
    events.unshift({
      eventId: `manual_${merged.symbol}_${Date.now()}`,
      symbol: merged.symbol,
      stockName: merged.name,
      time: currentTimeLabel(),
      source: 'watchlist.json',
      severity: 'info',
      title: '手动加入监控',
      desc: `${merged.name} 已加入后端监控列表，后续会纳入定时技术面分析。`
    })
    activeFilter.value = 'all'
    activeGroupFilter.value = normalizeStockGroupKey(merged.groupKey)
    selectedSymbol.value = merged.symbol
    persistCustomStocks()
    closeAddDialog()
  } catch (error) {
    addStockError.value = resolveErrorMessage(error, '保存到后端监控列表失败')
  } finally {
    addStockSaving.value = false
  }
}

function confirmObservation() {
  const title = observationForm.title.trim()
  const desc = observationForm.desc.trim()
  const nextAction = observationForm.nextAction.trim()

  if (!title) {
    observationError.value = '请输入观察标题'
    return
  }
  if (!desc) {
    observationError.value = '请输入观察内容'
    return
  }

  const event = createObservationEvent({
    stock: selectedStock.value,
    severity: observationForm.severity,
    title,
    desc,
    nextAction
  })
  events.unshift(event)
  persistCustomObservations()
  closeObservationDialog()
}

function deleteEvent(event) {
  if (!event?.eventId) return

  const index = events.findIndex((item) => item.eventId === event.eventId)
  if (index >= 0) {
    events.splice(index, 1)
  }

  if (event.manualObservation) {
    persistCustomObservations()
    return
  }

  deletedEventIds.add(event.eventId)
  persistDeletedEvents()
}

function handleImageUpload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (!file.type.startsWith('image/')) {
    addStockError.value = '请选择图片文件'
    event.target.value = ''
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    addStockError.value = '图片不能超过 2MB'
    event.target.value = ''
    return
  }

  const reader = new FileReader()
  reader.onload = () => {
    addStockForm.image = String(reader.result || '')
    addStockForm.imageFileName = file.name
    addStockError.value = ''
  }
  reader.onerror = () => {
    addStockError.value = '图片读取失败'
  }
  reader.readAsDataURL(file)
  event.target.value = ''
}

function normalizeStockCode(input) {
  const raw = input.trim().toLowerCase().replace(/\s+/g, '')
  if (!raw) return null

  const hkPrefixed = raw.match(/^(?:h|hk|hkg|hkex)[._:-]?(\d{1,5})$/)
  if (hkPrefixed) {
    return { market: 'hk', symbol: hkPrefixed[1].padStart(5, '0') }
  }

  const hkSuffixed = raw.match(/^(\d{1,5})[._:-]?(?:h|hk|hkg|hkex)$/)
  if (hkSuffixed) {
    return { market: 'hk', symbol: hkSuffixed[1].padStart(5, '0') }
  }

  if (/^\d{1,5}$/.test(raw)) {
    return { market: 'hk', symbol: raw.padStart(5, '0') }
  }

  const prefixed = raw.match(/^(sh|sz|bj|sse|szse)[._:-]?(\d{6})$/)
  if (prefixed) {
    const market = prefixed[1] === 'sse' ? 'sh' : (prefixed[1] === 'szse' ? 'sz' : prefixed[1])
    return { market, symbol: prefixed[2] }
  }

  const suffixed = raw.match(/^(\d{6})(?:[._:-]?(sh|sz|bj|sse|szse))?$/)
  if (!suffixed) return null
  const symbol = suffixed[1]
  const explicitMarket = suffixed[2] === 'sse' ? 'sh' : (suffixed[2] === 'szse' ? 'sz' : suffixed[2])
  return { symbol, market: explicitMarket || inferMarket(symbol) }
}

function inferMarket(symbol) {
  if (/^\d{5}$/.test(symbol)) return 'hk'
  if (/^(6|9)/.test(symbol)) return 'sh'
  if (/^5/.test(symbol)) return 'sh'
  if (/^(0|2|3)/.test(symbol)) return 'sz'
  if (/^(4|8)/.test(symbol)) return 'bj'
  return 'sh'
}

function resolveStockDisplayName(normalized, preferredName = '') {
  const name = String(preferredName || '').trim()
  if (name) return name
  if (!normalized?.symbol) return '未命名标的'
  return `${normalized.symbol}.${String(normalized.market || inferMarket(normalized.symbol)).toUpperCase()}`
}

function normalizeStockGroupKey(value) {
  const raw = String(value || '').trim().toLowerCase()
  if (['holding', 'position', 'portfolio', 'hold', '持仓', '持仓股'].includes(raw)) return 'holding'
  if (['candidate', 'candidate_stock', 'pool', '备选', '备选股'].includes(raw)) return 'candidate'
  if (['archived', 'archive', 'paused', '归档', '暂停'].includes(raw)) return 'archived'
  return 'watch'
}

function stockGroupLabel(value) {
  const groupKey = normalizeStockGroupKey(value)
  return stockGroupTabs.find((group) => group.value === groupKey)?.label || '观察股'
}

function stockGroupTone(value) {
  const groupKey = normalizeStockGroupKey(value)
  if (groupKey === 'holding') return 'holding'
  if (groupKey === 'candidate') return 'candidate'
  if (groupKey === 'archived') return 'archived'
  return 'watch'
}

function countStockGroup(groupKey) {
  return watchlist.filter((stock) => normalizeStockGroupKey(stock.groupKey) === groupKey).length
}

function createEmptyTechnical() {
  return {
    open: '--',
    high: '--',
    low: '--',
    volume: '--',
    ma5: '--',
    ma10: '--',
    ma20: '--',
    rsi: '--',
    macd: '--',
    vwap: '--',
    vwapDeviation: '--',
    volumeRatio: '--',
    turnover: '--',
    atr: '--',
    bollWidth: '--',
    bias20: '--',
    obvTrend: '--',
    rsRank: '--',
    sectorStrength: '--',
    mainInflow: '--',
    levels: []
  }
}

function createEmptyIntradayBarStatus(reason = '分钟行情未加载') {
  return {
    status: 'UNAVAILABLE',
    reason,
    source: '',
    provider: '',
    fetchedAt: '',
    asOfTime: '',
    qualitySummary: {
      totalBars: 0,
      validBars: 0,
      invalidBars: 0
    }
  }
}

function createEmptyStock() {
  return {
    symbol: '',
    name: '暂无标的',
    market: '--',
    image: '',
    groupKey: 'watch',
    groupKeyExplicit: false,
    manual: false,
    createdAt: '',
    price: '--',
    change: 0,
    status: '未加载',
    severity: 'info',
    refresh: {
      source: '--',
      endpoint: '真实 watchlist 未加载',
      last: '--',
      next: '--'
    },
    intradayBars: [],
    intradayBarStatus: createEmptyIntradayBarStatus(),
    technical: createEmptyTechnical(),
    rules: [],
    sources: [],
    analysisPolicy: null,
    analysisSchedule: null,
    rawData: {}
  }
}

function createManualStock({
  symbol,
  market,
  name,
  image = '',
  groupKey = 'watch',
  groupKeyExplicit = true,
  createdAt = new Date().toISOString(),
  analysisPolicy = null,
  analysisSchedule = null
}) {
  return {
    symbol,
    name,
    market,
    image,
    groupKey: normalizeStockGroupKey(groupKey),
    groupKeyExplicit,
    manual: true,
    createdAt,
    price: '待抓取',
    change: 0,
    status: '手动添加',
    severity: 'info',
    refresh: {
      source: '待配置',
      endpoint: 'quote snapshot',
      last: '--',
      next: '--'
    },
    intradayBars: [],
    intradayBarStatus: createEmptyIntradayBarStatus(),
    technical: createEmptyTechnical(),
    rules: [],
    sources: [
      { name: 'Manual watchlist', desc: '用户手动添加', ok: true },
      { name: 'Quote source', desc: '待接入行情抓取', ok: false }
    ],
    analysisPolicy: analysisPolicy ? normalizeAnalysisPolicyPayload(analysisPolicy) : null,
    analysisSchedule: analysisSchedule ? normalizeAnalysisSchedulePayload(analysisSchedule) : null,
    rawData: {
      source: 'manual',
      created_at: createdAt
    }
  }
}

async function loadRealStockWatchState() {
  const localStocks = loadSavedStocks()
  await syncLocalStocksToBackend(localStocks)
  const backendLoaded = await loadBackendWatchlist()
  if (!backendLoaded) {
    replaceReactiveArray(watchlist, [])
    selectedSymbol.value = ''
    quoteError.value = '后端 watchlist 加载失败；未使用本地缓存冒充真实监控列表'
    return
  }

  loadDeletedEvents()
  loadSavedObservations()
  await loadAiRules()
  ensureSelectedSymbol()
  await refreshQuotes('页面打开')
  startAutoQuoteRefresh()
  persistCustomStocks()
}

function replaceReactiveArray(target, items) {
  target.splice(0, target.length, ...items)
}

function ensureSelectedSymbol() {
  if (watchlist.some((stock) => stock.symbol === selectedSymbol.value)) {
    return
  }
  selectedSymbol.value = watchlist[0]?.symbol || ''
}

function applyPageState(state) {
  const pageState = state || {}
  replaceReactiveArray(watchlist, (pageState.watchlist || []).map(normalizePageStock).filter((stock) => stock.symbol))
  replaceReactiveArray(events, Array.isArray(pageState.events) ? pageState.events : [])
  replaceReactiveArray(plugins, Array.isArray(pageState.plugins) ? pageState.plugins : [])
  selectedSymbol.value = pageState.selectedSymbol || watchlist[0]?.symbol || ''
  quoteLastRefresh.value = currentTimeLabel()
  quoteRefreshMode.value = pageState.demo ? '演示' : '初始化'
}

function normalizePageStock(item = {}) {
  const normalized = normalizeStockCode(`${item.symbol || ''}.${item.market || ''}`)
  if (!normalized) return createEmptyStock()
  const stock = createManualStock({
    symbol: normalized.symbol,
    market: normalized.market,
    name: resolveStockDisplayName(normalized, item.name || item.stockName),
    image: String(item.image || item.imageUrl || ''),
    groupKey: item.groupKey,
    groupKeyExplicit: Boolean(String(item.groupKey || '').trim()),
    createdAt: item.createdAt || new Date().toISOString(),
    analysisPolicy: item.analysisPolicy,
    analysisSchedule: item.analysisSchedule
  })
  const envelope = normalizeIntradayEnvelope(item.intradayBarStatus || {
    status: Array.isArray(item.intradayBars) && item.intradayBars.length ? 'VALID' : 'UNAVAILABLE',
    reason: Array.isArray(item.intradayBars) && item.intradayBars.length ? '' : '分钟行情未加载',
    bars: item.intradayBars || []
  })
  const { bars, ...barStatus } = envelope
  return {
    ...stock,
    ...item,
    symbol: stock.symbol,
    market: stock.market,
    name: item.name || item.stockName || stock.name,
    groupKey: normalizeStockGroupKey(item.groupKey || stock.groupKey),
    refresh: {
      ...stock.refresh,
      ...(item.refresh || {})
    },
    technical: {
      ...createEmptyTechnical(),
      ...(item.technical || {}),
      levels: Array.isArray(item.technical?.levels) ? item.technical.levels : []
    },
    rules: Array.isArray(item.rules) ? item.rules : [],
    sources: Array.isArray(item.sources) ? item.sources : stock.sources,
    intradayBars: bars,
    intradayBarStatus: barStatus,
    rawData: item.rawData || stock.rawData
  }
}

async function loadIntradayBars(stock = selectedStock.value) {
  if (demoModeActive.value) {
    return
  }
  if (!stock?.symbol || !stock?.market) {
    barError.value = ''
    return
  }
  loadingBars.value = true
  barError.value = ''
  try {
    const response = await stockWatchHttp.get('/stock-watch/intraday-bars', {
      params: {
        symbol: stock.symbol,
        market: stock.market
      },
      suppressErrorMessage: true
    })
    applyIntradayEnvelopeToStock(stock, normalizeIntradayEnvelope(response?.data))
  } catch (error) {
    const message = resolveErrorMessage(error, '分钟行情加载失败')
    stock.intradayBars = []
    stock.intradayBarStatus = createEmptyIntradayBarStatus(message)
    stock.intradayBarStatus.status = 'SOURCE_ERROR'
    barError.value = message
  } finally {
    loadingBars.value = false
  }
}

function applyIntradayEnvelopeToStock(stock, envelope) {
  stock.intradayBars = envelope.bars
  stock.intradayBarStatus = {
    status: envelope.status,
    reason: envelope.reason,
    source: envelope.source,
    provider: envelope.provider,
    fetchedAt: envelope.fetchedAt,
    asOfTime: envelope.asOfTime,
    qualitySummary: envelope.qualitySummary
  }
}

function normalizeIntradayEnvelope(payload) {
  if (Array.isArray(payload)) {
    const bars = normalizeIntradayBars(payload)
    return {
      ...createEmptyIntradayBarStatus(bars.length ? '' : '分钟行情为空'),
      status: bars.length ? summarizeBarStatus(bars) : 'EMPTY',
      bars
    }
  }
  const source = payload || {}
  const bars = normalizeIntradayBars(source.bars)
  const summary = source.qualitySummary || {}
  const status = String(source.status || (bars.length ? summarizeBarStatus(bars) : 'EMPTY')).toUpperCase()
  return {
    status,
    reason: String(source.reason || ''),
    source: String(source.source || ''),
    provider: String(source.provider || ''),
    fetchedAt: String(source.fetchedAt || ''),
    asOfTime: String(source.asOfTime || ''),
    qualitySummary: {
      totalBars: Number(summary.totalBars ?? bars.length) || 0,
      validBars: Number(summary.validBars ?? bars.filter((bar) => bar.qualityStatus === 'VALID').length) || 0,
      invalidBars: Number(summary.invalidBars ?? bars.filter((bar) => bar.qualityStatus === 'INVALID').length) || 0
    },
    bars
  }
}

function normalizeIntradayBars(rawBars) {
  if (!Array.isArray(rawBars)) return []
  return rawBars.map(normalizeIntradayBar).filter(Boolean)
}

function normalizeIntradayBar(raw, index) {
  if (!raw || typeof raw !== 'object') return null
  const bar = {
    startTime: String(raw.startTime || raw.time || ''),
    endTime: String(raw.endTime || ''),
    open: numberOrNull(raw.open),
    high: numberOrNull(raw.high),
    low: numberOrNull(raw.low),
    close: numberOrNull(raw.close),
    volume: numberOrNull(raw.volume),
    amount: numberOrNull(raw.amount)
  }
  bar.qualityStatus = normalizeBarQuality(raw.qualityStatus, bar)
  bar.key = `${bar.startTime || index}-${index}`
  return bar
}

function normalizeBarQuality(status, bar) {
  const explicit = String(status || '').toUpperCase()
  if (explicit === 'INVALID') return 'INVALID'
  const values = [bar.open, bar.high, bar.low, bar.close]
  if (values.some((value) => value == null)) return 'INVALID'
  if (bar.high < Math.max(bar.open, bar.close, bar.low)) return 'INVALID'
  if (bar.low > Math.min(bar.open, bar.close, bar.high)) return 'INVALID'
  return 'VALID'
}

function summarizeBarStatus(bars) {
  if (!bars.length) return 'EMPTY'
  const invalidCount = bars.filter((bar) => bar.qualityStatus === 'INVALID').length
  if (invalidCount === 0) return 'VALID'
  return invalidCount === bars.length ? 'INVALID' : 'DEGRADED'
}

function numberOrNull(value) {
  if (value == null || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

async function refreshRuntimeForSelectedStock() {
  if (demoModeActive.value) {
    runtimeStatus.value = demoRuntimeStatus()
    runtimeStatusError.value = ''
    return
  }
  await refreshRuntimeStatus(selectedStock.value)
}

function demoRuntimeStatus() {
  return {
    status: 'DEMO',
    schedulingEnabled: false,
    analysisRunning: false,
    analysisMode: 'DEMO',
    provider: 'Demo',
    providerVersionState: 'DEMO',
    lastRunAt: null,
    lastRunStatus: '演示数据',
    nextDueAt: null,
    pendingJobs: null,
    retryJobs: null,
    retryPolicy: {
      maxAttempts: 0,
      currentAttempt: 0,
      backoffStrategy: 'NONE',
      nextRetryAt: null,
      retryable: false,
      lastErrorCode: null
    },
    warnings: ['演示模式开启，未读取真实运行状态']
  }
}

function loadSavedStocks() {
  const localStocks = []
  try {
    const saved = JSON.parse(window.localStorage.getItem(CUSTOM_WATCHLIST_KEY) || '[]')
    if (Array.isArray(saved)) {
      saved.forEach((item) => {
        const normalized = normalizeStockCode(`${item.symbol || ''}.${item.market || ''}`)
        if (!normalized) return
        const stock = createManualStock({
          symbol: normalized.symbol,
          market: normalized.market,
          name: resolveStockDisplayName(normalized, item.name),
          image: String(item.image || ''),
          groupKey: item.groupKey,
          groupKeyExplicit: Boolean(String(item.groupKey || '').trim()),
          createdAt: item.createdAt || new Date().toISOString()
        })
        localStocks.push(stock)
      })
    }
  } catch (error) {
    console.warn('Failed to load custom watchlist', error)
  }
  return localStocks
}

async function loadBackendWatchlist() {
  try {
    const response = await stockWatchHttp.get('/stock-watch/watchlist', { suppressErrorMessage: true })
    const items = Array.isArray(response?.data) ? response.data : []
    const backendStocks = []
    items.forEach((item) => {
      const normalized = normalizeStockCode(`${item.symbol || ''}.${item.market || ''}`)
      if (!normalized) return
      backendStocks.push(createManualStock({
        symbol: normalized.symbol,
        market: normalized.market,
        name: resolveStockDisplayName(normalized, item.name || item.stockName),
        image: String(item.image || item.imageUrl || ''),
        groupKey: item.groupKey,
        groupKeyExplicit: Boolean(String(item.groupKey || '').trim()),
        createdAt: item.createdAt || new Date().toISOString(),
        analysisPolicy: item.analysisPolicy,
        analysisSchedule: item.analysisSchedule
      }))
    })
    replaceReactiveArray(watchlist, backendStocks)
    ensureSelectedSymbol()
    return true
  } catch (error) {
    console.warn('Failed to load backend watchlist', error)
    return false
  }
}

async function syncLocalStocksToBackend(stocks) {
  if (!Array.isArray(stocks) || stocks.length === 0) return
  for (const stock of stocks) {
    try {
      await saveBackendWatchStock(stock, { includeGroupKey: Boolean(stock.groupKeyExplicit) })
    } catch (error) {
      console.warn('Failed to sync local watch stock', stock.symbol, error)
    }
  }
}

async function saveBackendWatchStock(stock, options = {}) {
  const payload = {
    symbol: stock.symbol,
    market: stock.market,
    name: stock.name,
    imageUrl: stock.image && !stock.image.startsWith('data:') ? stock.image : '',
    enabled: true,
    source: 'manual'
  }
  if (options.includeGroupKey !== false) {
    payload.groupKey = normalizeStockGroupKey(stock.groupKey)
  }
  const response = await stockWatchHttp.post('/stock-watch/watchlist', payload, { suppressErrorMessage: true })
  const saved = response?.data || {}
  return createManualStock({
    symbol: saved.symbol || stock.symbol,
    market: saved.market || stock.market,
    name: saved.name || saved.stockName || stock.name,
    image: saved.image || saved.imageUrl || stock.image,
    groupKey: saved.groupKey || stock.groupKey,
    groupKeyExplicit: true,
    createdAt: stock.createdAt || new Date().toISOString(),
    analysisPolicy: saved.analysisPolicy || stock.analysisPolicy,
    analysisSchedule: saved.analysisSchedule || stock.analysisSchedule
  })
}

function mergeWatchStock(stock) {
  const index = watchlist.findIndex((item) => item.symbol === stock.symbol && item.market === stock.market)
  if (index >= 0) {
    const existing = watchlist[index]
    existing.name = stock.name || existing.name
    existing.image = stock.image || existing.image
    existing.groupKey = normalizeStockGroupKey(stock.groupKey || existing.groupKey)
    existing.groupKeyExplicit = existing.groupKeyExplicit || stock.groupKeyExplicit
    existing.manual = existing.manual || stock.manual
    existing.createdAt = existing.createdAt || stock.createdAt
    if (stock.analysisPolicy) {
      existing.analysisPolicy = normalizeAnalysisPolicyPayload(stock.analysisPolicy)
    }
    if (stock.analysisSchedule) {
      existing.analysisSchedule = normalizeAnalysisSchedulePayload(stock.analysisSchedule)
    }
    return existing
  }
  watchlist.push(stock)
  return stock
}

function persistCustomStocks() {
  const customStocks = watchlist
    .filter((stock) => stock.manual)
    .map((stock) => ({
      symbol: stock.symbol,
      market: stock.market,
      name: stock.name,
      image: stock.image,
      groupKey: normalizeStockGroupKey(stock.groupKey),
      createdAt: stock.createdAt
    }))
  window.localStorage.setItem(CUSTOM_WATCHLIST_KEY, JSON.stringify(customStocks))
}

function loadSavedObservations() {
  try {
    const saved = JSON.parse(window.localStorage.getItem(CUSTOM_OBSERVATIONS_KEY) || '[]')
    if (!Array.isArray(saved)) return

    saved
      .filter((item) => item?.symbol && item?.title && item?.desc)
      .sort((a, b) => (Number(a.createdAt) || 0) - (Number(b.createdAt) || 0))
      .forEach((item) => {
        const eventId = String(item.eventId || '')
        if (eventId && deletedEventIds.has(eventId)) return
        const exists = events.some((event) => event.eventId === eventId)
        if (exists) return
        events.unshift(createObservationEvent({
          eventId,
          symbol: String(item.symbol),
          stockName: String(item.stockName || ''),
          time: String(item.time || ''),
          source: String(item.source || '手动观察'),
          severity: String(item.severity || 'info'),
          title: String(item.title),
          desc: String(item.desc),
          createdAt: Number(item.createdAt) || Date.now()
        }))
      })
  } catch (error) {
    console.warn('Failed to load custom observations', error)
  }
}

function persistCustomObservations() {
  const customObservations = events
    .filter((event) => event.manualObservation)
    .map((event) => ({
      eventId: event.eventId,
      symbol: event.symbol,
      stockName: event.stockName,
      time: event.time,
      source: event.source,
      severity: event.severity,
      title: event.title,
      desc: event.desc,
      createdAt: event.createdAt
    }))
  window.localStorage.setItem(CUSTOM_OBSERVATIONS_KEY, JSON.stringify(customObservations))
}

function loadDeletedEvents() {
  try {
    const saved = JSON.parse(window.localStorage.getItem(CUSTOM_DELETED_EVENTS_KEY) || '[]')
    if (!Array.isArray(saved)) return

    saved
      .map((item) => String(item || '').trim())
      .filter(Boolean)
      .forEach((eventId) => {
        deletedEventIds.add(eventId)
        removeEventById(eventId)
      })
  } catch (error) {
    console.warn('Failed to load deleted stock-watch events', error)
  }
}

function persistDeletedEvents() {
  window.localStorage.setItem(CUSTOM_DELETED_EVENTS_KEY, JSON.stringify([...deletedEventIds]))
}

function removeEventById(eventId) {
  const index = events.findIndex((event) => event.eventId === eventId)
  if (index >= 0) {
    events.splice(index, 1)
  }
}

function createObservationEvent({
  stock,
  eventId = '',
  symbol = '',
  stockName = '',
  time = '',
  source = '手动观察',
  severity = 'info',
  title,
  desc,
  nextAction = '',
  createdAt = Date.now()
}) {
  const targetSymbol = symbol || stock?.symbol || ''
  const targetName = stockName || stock?.name || targetSymbol
  const details = nextAction ? `${desc}\n下一步：${nextAction}` : desc
  return {
    eventId: eventId || `obs_${targetSymbol}_${createdAt}`,
    symbol: targetSymbol,
    stockName: targetName,
    time: time || currentTimeLabel(new Date(createdAt)),
    source,
    severity: normalizeSeverity(severity),
    title,
    desc: details,
    createdAt,
    manualObservation: true
  }
}

function normalizeSeverity(severity) {
  return ['info', 'opportunity', 'risk', 'warning'].includes(severity) ? severity : 'info'
}

function resetAddForm() {
  addStockForm.code = ''
  addStockForm.name = ''
  addStockForm.groupKey = activeGroupFilter.value === 'all' ? 'watch' : normalizeStockGroupKey(activeGroupFilter.value)
  addStockForm.image = ''
  addStockForm.imageFileName = ''
  addStockError.value = ''
}

function resetObservationForm(withSnapshot = false) {
  const stock = selectedStock.value
  observationForm.severity = 'info'
  observationForm.title = withSnapshot ? `${stock.name} 行情观察` : ''
  observationForm.desc = withSnapshot ? buildObservationSnapshot(stock) : ''
  observationForm.nextAction = ''
  observationError.value = ''
}

function resetAiRuleForm(rule = null) {
  aiRuleEditingId.value = rule?.id || ''
  aiRuleForm.name = rule?.name || `${selectedStock.value.name} AI观察`
  aiRuleForm.conditionText = rule?.conditionText || ''
  aiRuleForm.severity = rule?.severity || 'risk'
  aiRuleForm.minConfidence = rule?.minConfidence ?? 0.75
  aiRuleForm.cooldownDays = rule?.cooldownDays || 1
  aiRuleForm.enabled = rule?.enabled ?? true
  aiRuleForm.notify = rule?.notify ?? true
  aiRuleError.value = ''
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.min(max, Math.max(min, number))
}

function normalizeAnalysisMode(raw) {
  const value = String(raw || 'INTERVAL').trim().toUpperCase()
  return ANALYSIS_MODE_OPTIONS.some((option) => option.value === value) ? value : 'INTERVAL'
}

function normalizeAnomalyTriggerMode(raw) {
  const value = String(raw || 'IMMEDIATE_ANALYSIS').trim().toUpperCase()
  return ANALYSIS_ANOMALY_TRIGGER_OPTIONS.some((option) => option.value === value) ? value : 'IMMEDIATE_ANALYSIS'
}

function normalizeAnalysisPolicyPayload(raw) {
  const source = raw?.analysisPolicy && !raw.mode ? raw.analysisPolicy : (raw || {})
  const mode = normalizeAnalysisMode(source.mode)
  const intervalMinutes = clampNumber(source.intervalMinutes, 10, 1440, 30)
  return {
    capability: source.capability || 'stock.unified-analysis.v1',
    mode,
    intervalMinutes,
    anomalyTriggerMode: normalizeAnomalyTriggerMode(source.anomalyTriggerMode),
    weeklyReviewEnabled: source.weeklyReviewEnabled == null ? true : Boolean(source.weeklyReviewEnabled),
    monthlyReviewEnabled: source.monthlyReviewEnabled == null ? true : Boolean(source.monthlyReviewEnabled),
    version: Number(source.version || 0),
    modifiedAt: source.modifiedAt || ''
  }
}

function normalizeAnalysisSchedulePayload(raw) {
  const source = raw || {}
  return {
    due: Boolean(source.due),
    reason: String(source.reason || '待加载'),
    currentSlotKey: String(source.currentSlotKey || ''),
    nextDueAt: source.nextDueAt || '',
    requestedCadence: String(source.requestedCadence || 'INTERVAL'),
    capacityStatus: String(source.capacityStatus || 'UNBOUNDED')
  }
}

function syncAnalysisPolicyForm(policy = selectedAnalysisPolicy.value) {
  const normalized = normalizeAnalysisPolicyPayload(policy)
  analysisPolicyForm.mode = normalized.mode
  analysisPolicyForm.intervalMinutes = normalized.intervalMinutes
  analysisPolicyForm.anomalyTriggerMode = normalized.anomalyTriggerMode
  analysisPolicyForm.weeklyReviewEnabled = normalized.weeklyReviewEnabled && supportsAnalysisReview(normalized.mode)
  analysisPolicyForm.monthlyReviewEnabled = normalized.monthlyReviewEnabled && supportsAnalysisReview(normalized.mode)
  analysisPolicyForm.version = normalized.version
  analysisPolicyForm.modifiedAt = normalized.modifiedAt
}

function hasPersistedAnalysisPolicy(stock) {
  return stock?.analysisPolicy?.capability === 'stock.unified-analysis.v1'
    && Number.isFinite(Number(stock.analysisPolicy?.version))
}

function supportsAnalysisReview(mode) {
  return mode === 'INTERVAL' || mode === 'DAILY_CLOSE'
}

function setAnalysisMode(mode) {
  const normalized = normalizeAnalysisMode(mode)
  analysisPolicyForm.mode = normalized
  if (normalized === 'INTERVAL') {
    analysisPolicyForm.intervalMinutes = clampNumber(analysisPolicyForm.intervalMinutes, 10, 1440, 30)
  }
  if (normalized === 'ON_ANOMALY') {
    analysisPolicyForm.anomalyTriggerMode = 'IMMEDIATE_ANALYSIS'
    analysisPolicyForm.weeklyReviewEnabled = false
    analysisPolicyForm.monthlyReviewEnabled = false
  }
  if (normalized === 'PAUSED') {
    analysisPolicyForm.anomalyTriggerMode = 'ALERT_ONLY'
    analysisPolicyForm.weeklyReviewEnabled = false
    analysisPolicyForm.monthlyReviewEnabled = false
  }
}

function setAnalysisInterval(minutes) {
  analysisPolicyForm.intervalMinutes = clampNumber(minutes, 10, 1440, 30)
}

function clampAnalysisInterval() {
  analysisPolicyForm.intervalMinutes = clampNumber(analysisPolicyForm.intervalMinutes, 10, 1440, 30)
}

function stockAnalysisCadenceLabel(stock) {
  if (!hasPersistedAnalysisPolicy(stock)) return '未保存策略'
  const policy = normalizeAnalysisPolicyPayload(stock?.analysisPolicy)
  if (policy.mode === 'DAILY_CLOSE') return '每日收盘一次'
  if (policy.mode === 'ON_ANOMALY') return '仅异动触发'
  if (policy.mode === 'PAUSED') return '暂停自动分析'
  return `每 ${intervalPresetLabel(policy.intervalMinutes)}`
}

function stockAnomalyTriggerLabel(stock) {
  if (!hasPersistedAnalysisPolicy(stock)) return '待保存后启用'
  const policy = normalizeAnalysisPolicyPayload(stock?.analysisPolicy)
  const option = ANALYSIS_ANOMALY_TRIGGER_OPTIONS.find((item) => item.value === policy.anomalyTriggerMode)
  return option?.label || '立即统一分析'
}

function analysisPolicySubtitle(policy, persisted = true) {
  if (!persisted) return '后端未创建策略记录；当前显示为待保存草案'
  const normalized = normalizeAnalysisPolicyPayload(policy)
  if (normalized.mode === 'DAILY_CLOSE') return '收盘窗口创建一次统一分析需求'
  if (normalized.mode === 'ON_ANOMALY') return '无异动时不占用 DeepSeek provider'
  if (normalized.mode === 'PAUSED') return '自动调度暂停，手动分析入口仍保留'
  return 'DeepSeek 网页 provider；每只股票独立会话'
}

function intervalPresetLabel(minutes) {
  const value = clampNumber(minutes, 10, 1440, 30)
  if (value >= 60 && value % 60 === 0) {
    return `${value / 60} 小时`
  }
  return `${value} 分钟`
}

function anomalyTriggerDescription(value) {
  const option = ANALYSIS_ANOMALY_TRIGGER_OPTIONS.find((item) => item.value === normalizeAnomalyTriggerMode(value))
  return option?.desc || '异动命中后立即排队统一分析'
}

function formatPolicyTime(value) {
  if (!value) return '--'
  const normalized = String(value).replace(/\[[^\]]+\]$/, '')
  const date = new Date(normalized)
  if (!Number.isFinite(date.getTime())) return String(value)
  if (date.getTime() <= 0) return '未修改'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}

function buildObservationSnapshot(stock) {
  const tech = stock.technical
  return [
    `价格 ${stock.price}，涨跌幅 ${stock.change >= 0 ? '+' : ''}${stock.change}%。`,
    `VWAP ${tech.vwap}，量比 ${tech.volumeRatio}，主力净流入 ${tech.mainInflow}。`,
    `当前状态：${stock.status}。`
  ].join('\n')
}

function stockInitials(stock) {
  const source = stock?.name?.trim() || stock?.symbol || '?'
  return Array.from(source).slice(0, 2).join('').toUpperCase()
}

function currentTimeLabel(date = new Date()) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}

function getAnalysisRunSchedule(date = new Date()) {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  const weekday = values.weekday || ''
  const isWeekend = weekday.includes('六') || weekday.includes('日') || /sat|sun/i.test(weekday)
  if (isWeekend) {
    return {
      lastRun: '--',
      previousRun: '--',
      nextRun: '下个交易日 09:30'
    }
  }

  const currentMinutes = Number(values.hour) * 60 + Number(values.minute)
  const slots = [
    9 * 60 + 30,
    10 * 60,
    10 * 60 + 30,
    11 * 60,
    11 * 60 + 30,
    13 * 60,
    13 * 60 + 30,
    14 * 60,
    14 * 60 + 30,
    15 * 60
  ]
  const completedSlots = slots.filter((slot) => slot <= currentMinutes)
  const nextSlot = slots.find((slot) => slot > currentMinutes)

  return {
    lastRun: completedSlots.length ? formatMinuteOfDay(completedSlots[completedSlots.length - 1]) : '--',
    previousRun: completedSlots.length > 1 ? formatMinuteOfDay(completedSlots[completedSlots.length - 2]) : '--',
    nextRun: nextSlot ? formatMinuteOfDay(nextSlot) : '下个交易日 09:30'
  }
}

function formatMinuteOfDay(minuteOfDay) {
  const hour = Math.floor(minuteOfDay / 60)
  const minute = minuteOfDay % 60
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

function analysisDateKey(date = new Date()) {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

function shiftAnalysisDate(date, offsetDays) {
  const shifted = new Date(date)
  shifted.setDate(shifted.getDate() + offsetDays)
  return shifted
}

function formatPrice(value) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  return Number(value).toFixed(2)
}

function formatNumberValue(value) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  return Number(value).toFixed(2)
}

function formatPercentValue(value) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  return `${Number(value).toFixed(2)}%`
}

function formatMoney(value) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  const number = Number(value)
  const abs = Math.abs(number)
  if (abs >= 100000000) {
    return `${(number / 100000000).toFixed(2)}亿`
  }
  if (abs >= 10000) {
    return `${(number / 10000).toFixed(2)}万`
  }
  return number.toFixed(2)
}

function formatSignedMoney(value) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  const formatted = formatMoney(Math.abs(Number(value)))
  return `${Number(value) >= 0 ? '+' : '-'}${formatted}`
}

function formatExternalNumber(value, digits = 2) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  return Number(value).toFixed(digits)
}

function formatExternalPercent(value, digits = 2) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  return `${Number(value).toFixed(digits)}%`
}

function formatExternalDiff(value, digits = 2, suffix = '') {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  const number = Number(value)
  return `${number >= 0 ? '+' : ''}${number.toFixed(digits)}${suffix}`
}

function formatExternalVolume(value) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  const number = Number(value)
  const abs = Math.abs(number)
  if (abs >= 100000000) {
    return `${(number / 100000000).toFixed(2)}亿股`
  }
  if (abs >= 10000) {
    return `${(number / 10000).toFixed(2)}万股`
  }
  return `${number.toFixed(0)}股`
}

function formatExternalMoney(value) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '--'
  }
  return formatMoney(value)
}

</script>

<style scoped>
.stock-watch-page {
  min-height: calc(100vh - 32px);
  display: grid;
  gap: 14px;
  color: #101828;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", sans-serif;
}

button {
  font: inherit;
}

button:disabled {
  cursor: not-allowed;
  opacity: .58;
}

.watch-header,
.panel,
.metric-tile {
  border: 1px solid #dde3ea;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 10px 28px rgba(16, 24, 40, 0.06);
}

.watch-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 20px;
}

.eyebrow,
.section-label {
  margin: 0 0 5px;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
}

.watch-header h1,
.panel h2 {
  margin: 0;
  color: #101828;
  font-weight: 750;
  letter-spacing: 0;
}

.watch-header h1 {
  font-size: 24px;
  line-height: 1.2;
}

.panel h2 {
  font-size: 18px;
}

.header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.plain-button,
.primary-button,
.icon-button,
.segmented button,
.analysis-gate button {
  min-height: 34px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  color: #334155;
  background: #ffffff;
  cursor: pointer;
}

.plain-button {
  padding: 0 12px;
}

.plain-button.danger {
  color: #b42318;
  border-color: #f2c8c5;
}

.primary-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 12px;
  color: #ffffff;
  border-color: #101828;
  background: #101828;
}

.primary-button span {
  font-size: 17px;
  line-height: 1;
}

.icon-button {
  width: 34px;
  padding: 0;
  color: #475467;
  background: #ffffff;
}

.status-chip,
.source-badge,
.signal-grade,
.event-badge,
.source-row em {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 750;
  white-space: nowrap;
}

.status-chip.success { color: #047857; background: #dff8ec; }
.status-chip.neutral { color: #1d4ed8; background: #e8f0ff; }
.status-chip.muted { color: #475569; background: #f1f5f9; }
.status-chip.warning { color: #92400e; background: #fef3c7; }

.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-tile {
  padding: 14px 16px;
}

.metric-tile span,
.metric-tile small,
.technical-tile span,
.technical-tile small,
.plugin-row small,
.stock-name small,
.quote-cell small,
.signal-cell small,
.refresh-cell small,
.quote-panel small,
.rule-card small,
.signal-row small,
.source-row span {
  display: block;
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
}

.metric-tile strong {
  display: block;
  margin: 5px 0 2px;
  font-size: 26px;
  line-height: 1.05;
}

.green { color: #059669; }
.up { color: #d92d20; }
.blue { color: #2563eb; }
.purple { color: #6d5bd0; }
.amber { color: #b7791f; }
.red { color: #d92d20; }
.down { color: #059669; }
.ink { color: #101828; }

.list-layout {
  display: grid;
  grid-template-columns: minmax(760px, 1fr) 360px;
  gap: 14px;
  align-items: start;
}

.detail-layout {
  display: grid;
  grid-template-columns: minmax(720px, 1fr) 360px;
  gap: 14px;
  align-items: start;
}

.quote-panel,
.chart-panel,
.ai-analysis-panel,
.stock-config-panel,
.contract-panel {
  grid-column: 1 / 2;
}

.side-stack {
  display: grid;
  gap: 14px;
}

.detail-layout > .side-stack {
  grid-column: 2 / 3;
  grid-row: 1 / span 5;
}

.event-panel {
  grid-column: 1 / 2;
}

.panel-bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 12px;
  border-bottom: 1px solid #edf1f5;
}

.panel-bar.compact {
  padding-bottom: 10px;
}

.panel-tools {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
}

.segmented {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border: 1px solid #d7dee8;
  border-radius: 8px;
  background: #f8fafc;
}

.segmented button {
  min-height: 30px;
  padding: 0 10px;
  border: 0;
  background: transparent;
}

.segmented button.active {
  color: #ffffff;
  background: #101828;
}

.watch-table {
  padding: 8px 12px 12px;
}

.stock-row {
  display: grid;
  grid-template-columns: 10px 42px 150px 100px 160px minmax(220px, 1fr) 128px;
  align-items: center;
  gap: 14px;
  width: 100%;
  min-height: 76px;
  margin-top: 8px;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  color: inherit;
  background: #ffffff;
  text-align: left;
  cursor: pointer;
  transition: border-color .16s ease, background .16s ease, transform .16s ease;
}

.stock-row:hover {
  transform: translateY(-1px);
  border-color: #b7c7da;
  background: #f8fbff;
}

.row-state,
.event-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
}

.row-state.opportunity,
.event-dot.opportunity { background: #059669; }
.row-state.risk,
.event-dot.risk { background: #d92d20; }
.row-state.info,
.event-dot.info { background: #2563eb; }
.row-state.warning,
.event-dot.warning { background: #b7791f; }

.stock-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  overflow: hidden;
  border: 1px solid #dbe5f0;
  border-radius: 8px;
  color: #1d4ed8;
  background: #eef4ff;
  font-size: 13px;
  font-weight: 800;
  line-height: 1;
}

.stock-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.stock-name strong,
.quote-cell strong,
.signal-cell strong,
.refresh-cell strong,
.plugin-row strong,
.rule-card strong,
.event-card strong,
.source-row strong,
.signal-row strong {
  display: block;
  font-weight: 750;
}

.quote-cell strong {
  font-size: 19px;
}

.tech-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tech-cell span {
  min-height: 26px;
  padding: 5px 8px;
  border-radius: 6px;
  color: #334155;
  background: #f1f5f9;
  font-size: 12px;
  font-weight: 650;
}

.event-row {
  display: grid;
  grid-template-columns: 12px 1fr;
  align-items: center;
  gap: 10px;
  width: calc(100% - 24px);
  min-height: 58px;
  margin: 8px 12px;
  padding: 10px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  color: inherit;
  background: #ffffff;
  text-align: left;
  cursor: pointer;
}

.event-row:disabled {
  cursor: default;
}

.event-row strong {
  display: block;
  font-size: 13px;
}

.event-row small {
  display: block;
  margin-top: 3px;
  color: #667085;
  font-size: 12px;
}

.plugin-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 8px 12px;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
}

.toggle {
  position: relative;
  width: 44px;
  height: 24px;
  border: 0;
  border-radius: 999px;
  background: #cbd5e1;
  cursor: pointer;
}

.toggle i {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #ffffff;
  transition: transform .16s ease;
}

.toggle.on {
  background: #059669;
}

.toggle.on i {
  transform: translateX(20px);
}

.quote-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px;
}

.quote-identity {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.detail-avatar {
  width: 54px;
  height: 54px;
  flex: 0 0 auto;
  font-size: 16px;
}

.quote-numbers {
  text-align: right;
}

.quote-numbers strong {
  display: block;
  font-size: 44px;
  line-height: 1;
  letter-spacing: 0;
}

.quote-numbers span {
  display: inline-flex;
  margin-top: 6px;
  font-size: 15px;
  font-weight: 800;
}

.source-badge {
  color: #1d4ed8;
  background: #e8f0ff;
}

.price-chart {
  margin: 14px 18px 12px;
  border: 1px solid #edf1f5;
  border-radius: 8px;
  background: #f8fafc;
}

svg {
  display: block;
  width: 100%;
  height: 320px;
}

.grid-line {
  stroke: #e3e9f0;
  stroke-width: 1;
}

.axis-line {
  stroke: #cbd5e1;
  stroke-width: 1.2;
}

.area-line {
  fill: url(#watchArea);
  stroke: none;
}

.price-line {
  fill: none;
  stroke: #2563eb;
  stroke-width: 4;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.price-line.red {
  stroke: #d92d20;
}

.price-line.green {
  stroke: #059669;
}

.price-line.blue,
.price-line.ink {
  stroke: #2563eb;
}

.support-line {
  stroke: #d97706;
  stroke-width: 2;
  stroke-dasharray: 7 7;
}

.pressure-line {
  stroke: #d92d20;
  stroke-width: 2;
  stroke-dasharray: 7 7;
}

.axis-text,
.level-text {
  fill: #667085;
  font-size: 12px;
  font-weight: 700;
}

.level-text {
  fill: #344054;
}

.technical-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding: 0 18px 18px;
}

.technical-tile {
  min-height: 108px;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  background: #ffffff;
}

.technical-tile strong {
  display: block;
  min-height: 32px;
  margin: 6px 0;
  font-size: 18px;
  line-height: 1.25;
}

.ai-analysis-panel {
  overflow: hidden;
}

.ai-analysis-head {
  gap: 18px;
  background:
    radial-gradient(circle at 12% 0%, rgba(37, 99, 235, .10), transparent 28%),
    linear-gradient(135deg, #ffffff 0%, #f8fbff 100%);
}

.ai-analysis-subtitle {
  display: block;
  max-width: 680px;
  margin-top: 6px;
  color: #667085;
  font-size: 12px;
  line-height: 1.55;
}

.analysis-controls {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.analysis-period-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid #d8e1ef;
  border-radius: 10px;
  background: #f8fafc;
}

.analysis-period-tabs button {
  display: grid;
  gap: 1px;
  min-width: 58px;
  border: 0;
  border-radius: 8px;
  padding: 6px 10px;
  color: #475467;
  background: transparent;
  cursor: pointer;
}

.analysis-period-tabs button.active {
  color: #ffffff;
  background: #101828;
}

.analysis-period-tabs span,
.analysis-period-tabs small {
  line-height: 1.1;
}

.analysis-period-tabs span {
  font-size: 12px;
  font-weight: 900;
}

.analysis-period-tabs small {
  font-size: 10px;
  opacity: .78;
}

.analysis-runtime-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding: 14px 18px 0;
}

.analysis-runtime-grid article {
  min-height: 84px;
  padding: 12px;
  border: 1px solid #e1e8f5;
  border-radius: 8px;
  background: #ffffff;
}

.analysis-runtime-grid span,
.analysis-runtime-grid small {
  display: block;
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
}

.analysis-runtime-grid strong {
  display: block;
  margin: 5px 0;
  color: #101828;
  font-size: 20px;
  line-height: 1.2;
}

.analysis-inline-error,
.analysis-inline-note {
  margin: 12px 18px 0;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.55;
}

.analysis-inline-error {
  color: #b42318;
  border: 1px solid #fecdca;
  background: #fff4f3;
}

.analysis-inline-note {
  color: #1d4ed8;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
}

.analysis-workspace {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 14px;
  padding: 14px 18px 18px;
}

.analysis-calendar {
  display: grid;
  align-content: start;
  gap: 8px;
  max-height: 540px;
  overflow: auto;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  background: #f8fafc;
}

.analysis-calendar-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 2px;
}

.analysis-calendar-head strong,
.analysis-calendar-head small {
  display: block;
}

.analysis-calendar-head small {
  margin-top: 3px;
  color: #667085;
  font-size: 12px;
}

.analysis-day-button {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  border: 1px solid transparent;
  border-radius: 7px;
  padding: 0 10px;
  color: #344054;
  background: transparent;
  cursor: pointer;
}

.analysis-day-button:hover {
  background: #ffffff;
}

.analysis-day-button.active {
  color: #1d4ed8;
  border-color: #bfdbfe;
  background: #eff6ff;
}

.analysis-day-button.empty {
  color: #98a2b3;
}

.analysis-day-button span {
  font-weight: 750;
}

.analysis-day-button small {
  grid-column: 1;
  color: inherit;
  opacity: .7;
  font-size: 11px;
  line-height: 1.1;
}

.analysis-day-button em {
  grid-row: 1 / span 2;
  grid-column: 2;
  font-size: 12px;
  font-style: normal;
}

.analysis-result-stack {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.analysis-record-card,
.analysis-empty {
  padding: 14px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  background: #ffffff;
}

.analysis-record-card {
  display: grid;
  gap: 10px;
}

.analysis-record-top,
.analysis-record-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.analysis-record-top small,
.analysis-record-footer small,
.analysis-empty span {
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
}

.analysis-stance {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.analysis-stance.green { color: #047857; background: #dff8ec; }
.analysis-stance.blue { color: #1d4ed8; background: #e8f0ff; }
.analysis-stance.amber { color: #92400e; background: #fef3c7; }
.analysis-stance.red { color: #b42318; background: #fee4e2; }

.analysis-record-card > strong,
.analysis-empty strong {
  display: block;
  font-size: 16px;
  line-height: 1.35;
}

.analysis-record-card p {
  margin: 0;
  color: #344054;
  font-size: 13px;
  line-height: 1.7;
}

.analysis-evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.analysis-main-force {
  display: grid;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
}

.analysis-risk-pattern {
  display: grid;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #fffbeb;
}

.analysis-risk-pattern > div:first-child {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  align-items: center;
}

.analysis-risk-pattern strong {
  color: #92400e;
  font-size: 12px;
}

.analysis-risk-pattern span {
  color: #78350f;
  font-size: 12px;
  font-weight: 650;
}

.analysis-risk-pattern p {
  margin: 0;
  color: #475569;
  line-height: 1.55;
}

.analysis-risk-pattern-conclusion {
  color: #111827 !important;
  font-weight: 700;
}

.analysis-risk-pattern-tags {
  display: grid;
  gap: 6px;
}

.analysis-risk-pattern-tags > div {
  display: grid;
  gap: 3px;
  padding: 4px 7px;
  border-radius: 6px;
  background: #fef3c7;
}

.analysis-risk-pattern-tags span {
  color: #854d0e;
}

.analysis-risk-pattern-tags small {
  color: #92400e;
  font-size: 11px;
  line-height: 1.45;
}

.analysis-risk-pattern-note {
  color: #78350f !important;
}

.analysis-risk-pattern-boundary {
  color: #64748b !important;
  font-size: 11px;
}

.analysis-main-force > div:first-child {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  align-items: center;
}

.analysis-main-force strong {
  color: #1d4ed8;
  font-size: 12px;
}

.analysis-main-force span,
.delivery-preview-intent {
  color: #475569;
  font-size: 12px;
  font-weight: 650;
}

.analysis-main-force-points {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.analysis-main-force-points span {
  padding: 4px 7px;
  border-radius: 6px;
  color: #1e3a8a;
  background: #e0f2fe;
  font-size: 12px;
}

.analysis-evidence span {
  min-height: 26px;
  padding: 5px 8px;
  border-radius: 6px;
  color: #334155;
  background: #f1f5f9;
  font-size: 12px;
  font-weight: 650;
}

.analysis-record-footer {
  padding-top: 2px;
}

.analysis-empty {
  display: grid;
  place-items: center;
  min-height: 220px;
  text-align: center;
}

.prediction-review {
  margin: 0 18px 16px;
  padding: 16px;
  border: 1px solid #dfe7ef;
  border-radius: 14px;
  background:
    radial-gradient(circle at 100% 0%, rgba(14, 116, 144, .08), transparent 34%),
    linear-gradient(135deg, #fbfefe 0%, #f7fafc 100%);
}

.prediction-review-head,
.prediction-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.prediction-review-head h3 {
  margin: 3px 0;
  color: #101828;
  font-size: 20px;
}

.prediction-review-head small,
.prediction-card small,
.prediction-card p {
  color: #667085;
  font-size: 12px;
  line-height: 1.55;
}

.prediction-stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 10px;
  margin-top: 14px;
}

.prediction-stat-card {
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 10px;
  background: rgba(255, 255, 255, .86);
  color: inherit;
  font: inherit;
  text-align: left;
}

.prediction-stat-card.clickable {
  cursor: pointer;
  transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease;
}

.prediction-stat-card.clickable:hover,
.prediction-stat-card.clickable.active {
  border-color: #14b8a6;
  box-shadow: 0 10px 24px rgba(20, 184, 166, .12);
  transform: translateY(-1px);
}

.prediction-stat-card.clickable.active {
  background: linear-gradient(135deg, #f0fdfa 0%, #ffffff 100%);
}

.prediction-stat-grid span,
.prediction-stat-grid small {
  display: block;
  color: #667085;
  font-size: 11px;
}

.prediction-stat-grid strong {
  display: block;
  margin: 5px 0;
  color: #0f172a;
  font-size: 24px;
}

.prediction-summary-scope {
  display: block;
  margin-top: 10px;
  color: #667085;
  font-size: 11px;
}

.prediction-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
}

.prediction-list-head strong,
.prediction-list-head small {
  display: block;
}

.prediction-list-head strong {
  color: #101828;
  font-size: 14px;
}

.prediction-list-head small {
  margin-top: 2px;
  color: #667085;
  font-size: 11px;
}

.prediction-list-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.prediction-filter {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #475467;
  font-size: 12px;
}

.prediction-filter select {
  min-height: 30px;
  padding: 0 8px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
  color: #101828;
  font: inherit;
}

.plain-button.compact {
  min-height: 30px;
  padding: 0 10px;
  font-size: 12px;
}

.prediction-list-actions .plain-button.active {
  border-color: #14b8a6;
  color: #0f766e;
  background: #f0fdfa;
}

.prediction-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}

.prediction-list.by-day {
  grid-template-columns: 1fr;
}

.prediction-single-group,
.prediction-flat-records {
  display: contents;
}

.prediction-day-card {
  min-width: 0;
  border: 1px solid #dfe7ef;
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}

.prediction-day-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 14px;
  border: 0;
  background: transparent;
  color: #101828;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.prediction-day-toggle:hover,
.prediction-day-toggle:focus-visible {
  background: #f0fdfa;
}

.prediction-day-heading {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.prediction-day-heading strong {
  font-size: 16px;
}

.prediction-day-heading small {
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.prediction-day-expand {
  flex: none;
  color: #0f766e;
  font-size: 12px;
  font-weight: 700;
}

.prediction-day-details {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding: 12px;
  border-top: 1px solid #e4e9f0;
  background: #f7fafc;
}

.prediction-card {
  display: grid;
  gap: 7px;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 10px;
  background: #fff;
}

.prediction-card > strong {
  color: #101828;
  font-size: 18px;
}

.prediction-card p {
  margin: 0;
}

.prediction-direction,
.prediction-status {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 800;
}

.prediction-direction.up { color: #b42318; background: #fee4e2; }
.prediction-direction.down { color: #047857; background: #dff8ec; }
.prediction-direction.sideways { color: #92400e; background: #fef3c7; }
.prediction-status.hit { color: #047857; background: #ecfdf3; }
.prediction-status.miss { color: #b42318; background: #fff1f0; }
.prediction-status.pending { color: #475467; background: #f2f4f7; }

.prediction-empty {
  margin-top: 12px;
  padding: 24px;
  border: 1px dashed #cbd5e1;
  border-radius: 10px;
  color: #667085;
  text-align: center;
}

.analysis-missing-data {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 9px 10px;
  border-radius: 8px;
  color: #92400e;
  background: #fffbeb;
  font-size: 11px;
}

.analysis-missing-data strong {
  margin-right: 4px;
}

.stock-config-panel {
  overflow: hidden;
}

.config-head {
  gap: 18px;
  background:
    radial-gradient(circle at 6% 0%, rgba(5, 150, 105, .10), transparent 32%),
    linear-gradient(135deg, #ffffff 0%, #fbfefb 100%);
}

.config-subtitle {
  display: block;
  max-width: 760px;
  margin-top: 6px;
  color: #667085;
  font-size: 12px;
  line-height: 1.55;
}

.config-actions {
  display: inline-flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.config-tabbar {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  border-bottom: 1px solid #edf1f5;
}

.config-tabbar button {
  min-height: 84px;
  padding: 14px 16px;
  border: 0;
  border-right: 1px solid #edf1f5;
  color: #334155;
  background: #ffffff;
  text-align: left;
  cursor: pointer;
}

.config-tabbar button:last-child {
  border-right: 0;
}

.config-tabbar button.active {
  color: #ffffff;
  background: #101828;
}

.config-tabbar span,
.config-tabbar small {
  display: block;
  color: #667085;
  font-size: 11px;
  font-weight: 800;
  line-height: 1.35;
  text-transform: uppercase;
}

.config-tabbar button.active span,
.config-tabbar button.active small {
  color: #a7f3d0;
}

.config-tabbar strong {
  display: block;
  margin: 5px 0 3px;
  font-size: 16px;
  line-height: 1.2;
}

.config-section {
  padding: 16px 18px 18px;
}

.config-summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.config-summary-grid article,
.delivery-side article {
  min-height: 96px;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  background: #ffffff;
}

.config-summary-grid span,
.config-summary-grid small,
.delivery-side span,
.delivery-side small,
.delivery-preview-head small,
.delivery-preview-row small {
  display: block;
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
}

.config-summary-grid strong,
.delivery-side strong {
  display: block;
  margin: 6px 0;
  color: #101828;
  font-size: 19px;
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.config-alert {
  margin: 12px 18px 0;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.55;
}

.config-alert.error {
  color: #b42318;
  border: 1px solid #fecdca;
  background: #fff4f3;
}

.config-alert.success {
  color: #047857;
  border: 1px solid #bbf7d0;
  background: #f0fdf4;
}

.delivery-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  gap: 14px;
  align-items: start;
}

.delivery-main {
  min-width: 0;
}

.config-form {
  padding: 0;
}

.config-form .span-2 {
  grid-column: 1 / -1;
}

.config-form textarea {
  min-height: 88px;
}

.quick-intervals {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.quick-intervals button,
.tone-selector button {
  min-height: 30px;
  border: 1px solid #d7dee8;
  border-radius: 999px;
  padding: 0 10px;
  color: #344054;
  background: #ffffff;
  cursor: pointer;
}

.quick-intervals button:hover,
.tone-selector button:hover {
  border-color: #94a3b8;
}

.tone-selector {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 0 0;
}

.tone-selector > span {
  flex: 0 0 100%;
  color: #475467;
  font-size: 12px;
  font-weight: 750;
}

.tone-selector > span em {
  margin-left: 6px;
  color: #98a2b3;
  font-size: 11px;
  font-style: normal;
  font-weight: 600;
}

.tone-selector button.active.green { color: #047857; border-color: #86efac; background: #dcfce7; }
.tone-selector button.active.blue { color: #1d4ed8; border-color: #bfdbfe; background: #eff6ff; }
.tone-selector button.active.amber { color: #92400e; border-color: #fde68a; background: #fffbeb; }
.tone-selector button.active.red { color: #b42318; border-color: #fecaca; background: #fff1f2; }

.delivery-buttons {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.delivery-side {
  display: grid;
  gap: 10px;
}

.delivery-preview {
  grid-column: 1 / -1;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  background: #f8fafc;
}

.delivery-preview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 12px;
  border-bottom: 1px solid #e4e9f0;
}

.delivery-preview-head strong,
.delivery-preview-head span {
  display: block;
  font-weight: 800;
}

.delivery-preview-head span {
  color: #1d4ed8;
}

.delivery-empty,
.delivery-preview-row {
  margin: 10px;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  background: #ffffff;
}

.delivery-empty {
  color: #667085;
  font-size: 13px;
}

.delivery-preview-row {
  display: grid;
  gap: 8px;
}

.delivery-preview-row strong {
  display: block;
  line-height: 1.35;
}

.delivery-preview-row p {
  margin: 0;
  color: #475467;
  font-size: 13px;
  line-height: 1.65;
}

.deepseek-session-panel {
  overflow: hidden;
}

.session-card {
  display: grid;
  gap: 12px;
  margin: 8px 12px 12px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.session-card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.session-card-top strong,
.session-card small {
  display: block;
}

.session-card-top span {
  display: inline-flex;
  min-height: 26px;
  align-items: center;
  padding: 0 9px;
  border-radius: 999px;
  color: #1d4ed8;
  background: #e8f0ff;
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.session-card small,
.session-card dt {
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
}

.session-notice,
.session-click-notice {
  margin: 0;
  padding: 9px 10px;
  border-radius: 7px;
  color: #92400e;
  background: #fffbeb;
  font-size: 12px;
  line-height: 1.55;
}

.session-click-notice {
  color: #1d4ed8;
  background: #eff6ff;
}

.session-card dl {
  display: grid;
  gap: 8px;
  margin: 0;
}

.session-card dl > div {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 8px;
}

.session-card dt,
.session-card dd {
  margin: 0;
}

.session-card dd {
  min-width: 0;
  color: #101828;
  font-size: 12px;
  font-weight: 750;
  overflow-wrap: anywhere;
}

.session-meter {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #e5e7eb;
}

.session-meter i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #2563eb 0%, #14b8a6 100%);
}

.signal-row,
.rule-card,
.source-row,
.event-card {
  position: relative;
  margin: 8px 12px;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  background: #ffffff;
  transition: border-color .16s ease, box-shadow .16s ease;
}

.signal-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: center;
}

.signal-grade.green { color: #047857; background: #dff8ec; }
.signal-grade.blue { color: #1d4ed8; background: #e8f0ff; }
.signal-grade.amber { color: #92400e; background: #fef3c7; }

.rule-card span {
  display: inline-flex;
  margin-bottom: 6px;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 800;
}

.rule-empty-state {
  margin: 8px 12px;
  padding: 14px;
  border: 1px dashed #cbd5e1;
  border-radius: 10px;
  color: #667085;
  font-size: 13px;
  line-height: 1.6;
  background: #f8fafc;
}

.analysis-gate {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: center;
  margin: 8px 12px 12px;
  padding: 12px;
  border-radius: 8px;
  color: #3730a3;
  background: #eef2ff;
}

.analysis-gate span,
.analysis-gate strong {
  display: block;
}

.analysis-gate button {
  padding: 0 12px;
  color: #ffffff;
  border-color: #101828;
  background: #101828;
}

.ai-rule-empty,
.ai-rule-card {
  margin: 8px 12px;
  padding: 12px;
  border: 1px solid #e4e9f0;
  border-radius: 8px;
  background: #ffffff;
}

.ai-rule-empty {
  color: #667085;
  font-size: 13px;
}

.ai-rule-card-top,
.ai-rule-actions {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.ai-rule-card strong,
.ai-rule-card small {
  display: block;
}

.ai-rule-card small,
.ai-rule-actions small {
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
}

.ai-rule-card p {
  margin: 10px 0;
  color: #344054;
  font-size: 13px;
  line-height: 1.55;
}

.ai-rule-actions {
  align-items: center;
}

.ai-rule-actions > div {
  display: inline-flex;
  gap: 8px;
}

.event-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding-right: 34px;
  margin-bottom: 8px;
}

.event-card-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}

.event-delete-button {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 28px;
  height: 28px;
  min-height: 0;
  color: #98a2b3;
  border-color: transparent;
  border-radius: 999px;
  background: transparent;
  opacity: 0;
  transform: scale(.94);
  transition: opacity .16s ease, transform .16s ease, color .16s ease, background .16s ease;
}

.event-card:hover {
  border-color: #d0d7e2;
  box-shadow: 0 10px 26px rgba(15, 23, 42, .05);
}

.event-card:hover .event-delete-button,
.event-delete-button:focus-visible {
  opacity: 1;
  transform: scale(1);
}

.event-delete-button:hover {
  color: #d92d20;
  background: #fff1f0;
}

@media (hover: none) {
  .event-delete-button {
    opacity: .72;
    transform: scale(1);
    background: rgba(248, 250, 252, .88);
  }
}

.event-badge.opportunity { color: #047857; background: #dff8ec; }
.event-badge.risk { color: #b42318; background: #fee4e2; }
.event-badge.info { color: #1d4ed8; background: #e8f0ff; }
.event-badge.warning { color: #92400e; background: #fef3c7; }

.event-card p {
  margin: 8px 0;
  color: #475467;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-line;
}

.mono,
pre {
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
}

.source-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  align-items: center;
}

.source-hint {
  display: block;
  max-width: 300px;
  margin-top: 6px;
  color: #667085;
  font-size: 12px;
  line-height: 1.55;
}

.source-row em {
  font-style: normal;
}

.source-row .ok {
  color: #047857;
  background: #dff8ec;
}

.source-row .warn {
  color: #92400e;
  background: #fef3c7;
}

pre {
  margin: 0;
  padding: 14px 18px 18px;
  overflow: auto;
  color: #344054;
  background: #f8fafc;
  font-size: 12px;
  line-height: 1.55;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 18px;
  background: rgba(15, 23, 42, 0.42);
}

.modal-panel {
  width: min(520px, 100%);
  max-height: calc(100vh - 36px);
  overflow: auto;
  border: 1px solid #d9e2ec;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.24);
}

.modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 18px 12px;
  border-bottom: 1px solid #edf1f5;
}

.modal-head h2 {
  margin: 0;
  font-size: 20px;
  line-height: 1.2;
}

.stock-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 16px 18px 0;
}

.stock-form label {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.stock-form label > span {
  color: #475467;
  font-size: 12px;
  font-weight: 750;
}

.stock-form label > span em {
  margin-left: 6px;
  color: #98a2b3;
  font-size: 11px;
  font-style: normal;
  font-weight: 600;
}

.stock-form input,
.stock-form select,
.stock-form textarea,
.file-field strong {
  width: 100%;
  min-height: 38px;
  box-sizing: border-box;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 0 10px;
  color: #101828;
  background: #ffffff;
  font: inherit;
}

.stock-form textarea {
  min-height: 108px;
  padding: 10px;
  line-height: 1.5;
  resize: vertical;
}

.stock-form input:focus,
.stock-form select:focus,
.stock-form textarea:focus {
  outline: 2px solid rgba(37, 99, 235, 0.18);
  border-color: #2563eb;
}

.observation-form .span-2 {
  grid-column: 1 / -1;
}

.ai-rule-form .span-2 {
  grid-column: 1 / -1;
}

.check-field {
  display: flex;
  grid-template-columns: none;
  align-items: center;
  gap: 8px;
  min-height: 38px;
}

.stock-form .check-field input {
  width: 16px;
  min-height: 16px;
  padding: 0;
}

.check-field span {
  color: #344054;
}

.file-field {
  position: relative;
}

.file-field input {
  position: absolute;
  inset: 24px 0 0;
  opacity: 0;
  cursor: pointer;
}

.file-field strong {
  display: flex;
  align-items: center;
  color: #334155;
  font-weight: 650;
}

.image-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  width: calc(100% - 36px);
  height: 120px;
  margin: 14px 18px 0;
  overflow: hidden;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  color: #1d4ed8;
  background: #f8fafc;
  font-size: 22px;
  font-weight: 800;
}

.image-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.form-error {
  margin: 10px 18px 0;
  color: #b42318;
  font-size: 13px;
  font-weight: 650;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 18px 18px;
}

@media (max-width: 1280px) {
  .list-layout,
  .detail-layout {
    grid-template-columns: 1fr;
  }

  .detail-layout > .side-stack,
  .source-panel,
  .quote-panel,
  .chart-panel,
  .ai-analysis-panel,
  .stock-config-panel,
  .event-panel,
  .contract-panel {
    grid-column: auto;
    grid-row: auto;
  }

  .stock-row {
    grid-template-columns: 10px 42px 1fr 100px 150px;
  }

  .tech-cell,
  .refresh-cell {
    display: none;
  }
}

@media (max-width: 900px) {
  .watch-header,
  .quote-panel {
    align-items: flex-start;
    flex-direction: column;
  }

  .header-actions {
    justify-content: flex-start;
  }

  .metric-strip,
  .technical-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .stock-row {
    grid-template-columns: 10px 42px 1fr;
  }

  .quote-cell,
  .signal-cell {
    grid-column: 3;
  }

  .quote-numbers {
    text-align: left;
  }

  .source-row {
    grid-template-columns: 1fr;
  }

  .analysis-runtime-grid,
  .analysis-workspace,
  .config-summary-grid,
  .delivery-grid {
    grid-template-columns: 1fr;
  }

  .prediction-stat-grid,
  .prediction-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .config-tabbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .analysis-calendar {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    max-height: none;
  }

  .analysis-calendar-head {
    grid-column: 1 / -1;
  }

  .analysis-record-top,
  .analysis-record-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .panel-tools {
    justify-content: flex-start;
  }
}

@media (max-width: 560px) {
  .metric-strip,
  .technical-grid {
    grid-template-columns: 1fr;
  }

  .panel-bar {
    flex-direction: column;
  }

  .stock-form {
    grid-template-columns: 1fr;
  }

  .config-tabbar {
    grid-template-columns: 1fr;
  }

  .analysis-calendar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .modal-actions {
    flex-direction: column-reverse;
  }

  .modal-actions button {
    width: 100%;
  }

  svg {
    height: 260px;
  }
}

/* 2026-06 visual refresh: calmer trading-workbench layout without changing data flow. */
.stock-watch-page {
  position: relative;
  gap: 18px;
  padding: 4px;
  color: #172033;
  font-family: "Avenir Next", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}

.stock-watch-page::before {
  content: "";
  position: fixed;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background:
    radial-gradient(circle at 10% 0%, rgba(16, 185, 129, .14), transparent 34%),
    radial-gradient(circle at 88% 12%, rgba(14, 165, 233, .12), transparent 30%),
    linear-gradient(180deg, #f5f8f7 0%, #eef3f6 46%, #f8fafc 100%);
}

.watch-header,
.panel,
.metric-tile {
  border: 1px solid rgba(148, 163, 184, .22);
  border-radius: 22px;
  background: rgba(255, 255, 255, .86);
  box-shadow: 0 22px 60px rgba(15, 23, 42, .08);
  backdrop-filter: blur(14px);
}

.watch-header {
  position: relative;
  min-height: 112px;
  overflow: hidden;
  padding: 24px 28px;
  border-color: rgba(15, 23, 42, .08);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, .94) 0%, rgba(236, 253, 245, .76) 52%, rgba(239, 246, 255, .82) 100%);
}

.watch-header::after {
  content: "";
  position: absolute;
  right: 22px;
  top: 50%;
  z-index: 0;
  width: 128px;
  height: 128px;
  border: 1px solid rgba(16, 185, 129, .22);
  border-radius: 36px;
  opacity: .42;
  background:
    linear-gradient(135deg, rgba(16, 185, 129, .14), rgba(14, 165, 233, .08)),
    repeating-linear-gradient(135deg, rgba(15, 23, 42, .06) 0 1px, transparent 1px 11px);
  transform: translateY(-50%) rotate(8deg);
}

.header-title-wrap {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 18px;
  min-width: 0;
}

.header-title-wrap.detail {
  align-items: flex-start;
}

.back-button {
  flex: 0 0 auto;
  min-height: 52px;
  padding: 0 24px;
  border-radius: 18px;
  color: #0f172a;
  background: rgba(255, 255, 255, .9);
  font-size: 20px;
  font-weight: 950;
}

.eyebrow,
.section-label {
  color: #64748b;
  font-size: 11px;
  font-weight: 900;
  letter-spacing: .18em;
  text-transform: uppercase;
}

.watch-header h1 {
  font-size: clamp(28px, 3vw, 40px);
  font-weight: 900;
  letter-spacing: -.04em;
}

.panel h2 {
  color: #172033;
  font-size: 20px;
  font-weight: 900;
  letter-spacing: -.025em;
}

.header-actions {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  flex-wrap: wrap;
  margin-left: auto;
}

.header-actions .status-chip {
  min-height: 48px;
  padding: 0 20px;
  font-size: 18px;
  font-weight: 950;
}

.plain-button,
.primary-button,
.icon-button,
.segmented button,
.analysis-gate button {
  min-height: 40px;
  border-radius: 13px;
  font-size: 14px;
  font-weight: 800;
  transition: transform .16s ease, border-color .16s ease, background .16s ease, box-shadow .16s ease;
}

.plain-button {
  border-color: rgba(100, 116, 139, .28);
  color: #334155;
  background: rgba(255, 255, 255, .82);
}

.plain-button:hover,
.icon-button:hover,
.segmented button:hover {
  transform: translateY(-1px);
  border-color: rgba(15, 23, 42, .18);
  box-shadow: 0 12px 28px rgba(15, 23, 42, .08);
}

.primary-button {
  min-height: 42px;
  border-color: #132016;
  color: #ffffff;
  background: linear-gradient(135deg, #111827 0%, #183226 100%);
  box-shadow: 0 16px 34px rgba(15, 23, 42, .18);
}

.primary-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 20px 42px rgba(15, 23, 42, .22);
}

.status-chip,
.source-badge,
.signal-grade,
.event-badge,
.source-row em {
  min-height: 30px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 900;
}

.status-chip.success { color: #047857; background: #dff8ec; }
.status-chip.neutral { color: #075985; background: #e0f2fe; }
.status-chip.muted { color: #475569; background: #e8edf2; }
.status-chip.warning { color: #92400e; background: #fef3c7; }

.metric-strip {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-tile {
  min-height: 112px;
  padding: 18px 20px;
  box-shadow: 0 18px 42px rgba(15, 23, 42, .06);
}

.metric-tile span {
  color: #64748b;
  font-size: 13px;
  font-weight: 800;
}

.metric-tile strong {
  margin: 9px 0 5px;
  font-size: 34px;
  font-weight: 950;
  letter-spacing: -.04em;
}

.metric-tile small {
  color: #64748b;
  font-size: 13px;
}

.list-layout {
  grid-template-columns: minmax(780px, 1fr) 332px;
  gap: 18px;
}

.detail-layout {
  grid-template-columns: minmax(760px, 1fr) 340px;
  gap: 18px;
}

.quote-panel,
.chart-panel,
.ai-analysis-panel,
.stock-config-panel,
.contract-panel {
  grid-column: 1 / 2;
}

.detail-layout > .side-stack {
  grid-column: 2 / 3;
  grid-row: 1 / span 5;
}

.side-stack {
  gap: 18px;
}

.panel {
  overflow: hidden;
}

.panel-bar {
  align-items: center;
  padding: 20px 22px 16px;
  border-bottom-color: rgba(148, 163, 184, .18);
}

.panel-bar.compact {
  padding: 18px 20px 13px;
}

.panel-tools {
  gap: 12px;
}

.segmented {
  gap: 6px;
  padding: 5px;
  border: 1px solid rgba(100, 116, 139, .22);
  border-radius: 16px;
  background: rgba(241, 245, 249, .78);
}

.segmented button {
  min-height: 36px;
  padding: 0 15px;
  border-radius: 12px;
  color: #475569;
}

.segmented button.active {
  color: #ffffff;
  background: #111827;
  box-shadow: 0 8px 18px rgba(15, 23, 42, .16);
}

.group-segmented button {
  padding: 0 12px;
}

.watch-table {
  padding: 4px 16px 18px;
}

.stock-row {
  grid-template-columns: 8px 48px minmax(140px, 1.15fr) minmax(92px, .65fr) minmax(108px, .82fr) minmax(176px, 1.05fr) minmax(118px, .78fr);
  min-height: 84px;
  margin-top: 10px;
  padding: 14px 16px;
  border: 1px solid rgba(148, 163, 184, .20);
  border-radius: 18px;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, .98) 0%, rgba(248, 250, 252, .94) 100%);
  box-shadow: 0 1px 0 rgba(15, 23, 42, .03);
}

.stock-row:hover {
  transform: translateY(-2px);
  border-color: rgba(16, 185, 129, .30);
  background: #ffffff;
  box-shadow: 0 18px 38px rgba(15, 23, 42, .10);
}

.row-state,
.event-dot {
  width: 7px;
  height: 38px;
  border-radius: 999px;
}

.stock-avatar {
  width: 46px;
  height: 46px;
  border: 0;
  border-radius: 16px;
  color: #047857;
  background:
    linear-gradient(135deg, #dcfce7 0%, #eefcf6 100%);
  box-shadow: inset 0 0 0 1px rgba(16, 185, 129, .18);
  font-size: 14px;
  font-weight: 950;
}

.stock-name strong,
.quote-cell strong,
.signal-cell strong,
.refresh-cell strong,
.plugin-row strong,
.rule-card strong,
.event-card strong,
.source-row strong,
.signal-row strong {
  color: #172033;
  font-weight: 900;
}

.stock-name small,
.quote-cell small,
.signal-cell small,
.refresh-cell small,
.quote-panel small,
.rule-card small,
.signal-row small,
.source-row span,
.plugin-row small {
  color: #64748b;
  font-size: 13px;
}

.quote-cell strong {
  font-size: 22px;
  letter-spacing: -.02em;
}

.tech-cell {
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.tech-cell span,
.analysis-evidence span {
  min-height: 28px;
  border-radius: 10px;
  color: #334155;
  background: #edf2f7;
  font-size: 12px;
  font-weight: 900;
}

.stock-name,
.quote-cell,
.signal-cell,
.refresh-cell {
  min-width: 0;
}

.stock-name strong,
.stock-name small,
.signal-cell strong,
.signal-cell small,
.refresh-cell strong,
.refresh-cell small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stock-group-chip {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  min-height: 22px;
  margin-top: 6px;
  padding: 0 8px;
  border-radius: 999px;
  color: #334155;
  background: #eef2f7;
  font-size: 12px;
  font-weight: 900;
}

.stock-group-chip.holding {
  color: #047857;
  background: #dcfce7;
}

.stock-group-chip.watch {
  color: #1d4ed8;
  background: #dbeafe;
}

.stock-group-chip.candidate {
  color: #b45309;
  background: #fef3c7;
}

.stock-group-chip.archived {
  color: #64748b;
  background: #f1f5f9;
}

.refresh-cell {
  justify-self: end;
  text-align: right;
}

.event-row,
.plugin-row,
.signal-row,
.rule-card,
.source-row,
.ai-rule-empty,
.ai-rule-card,
.event-card,
.analysis-record-card,
.analysis-empty,
.delivery-empty,
.delivery-preview-row,
.technical-tile,
.analysis-runtime-grid article,
.config-summary-grid article,
.delivery-side article {
  border-color: rgba(148, 163, 184, .20);
  border-radius: 18px;
  background: rgba(255, 255, 255, .84);
}

.event-row {
  grid-template-columns: 8px 1fr;
  min-height: 64px;
  margin: 10px 14px;
  padding: 12px 14px;
}

.event-row .event-dot {
  height: 32px;
}

.plugin-row {
  margin: 10px 14px;
  padding: 14px 15px;
}

.toggle {
  width: 48px;
  height: 28px;
  background: #cbd5e1;
}

.toggle i {
  width: 22px;
  height: 22px;
}

.toggle.on {
  background: linear-gradient(135deg, #10b981, #059669);
}

.quote-panel {
  min-height: 132px;
  padding: 24px 26px;
  background:
    radial-gradient(circle at 4% 0%, rgba(16, 185, 129, .16), transparent 32%),
    linear-gradient(135deg, rgba(255, 255, 255, .95), rgba(248, 250, 252, .88));
}

.detail-avatar {
  width: 64px;
  height: 64px;
  border-radius: 22px;
  font-size: 18px;
}

.quote-identity {
  gap: 16px;
}

.quote-numbers strong {
  font-size: 54px;
  font-weight: 950;
  letter-spacing: -.06em;
}

.quote-numbers span {
  font-size: 16px;
  font-weight: 950;
}

.source-badge {
  color: #047857;
  background: #dff8ec;
}

.price-chart {
  margin: 16px 22px 14px;
  border-color: rgba(148, 163, 184, .18);
  border-radius: 20px;
  background:
    linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
}

.price-line {
  stroke: #059669;
}

.technical-grid {
  gap: 12px;
  padding: 0 22px 22px;
}

.technical-tile {
  min-height: 116px;
  padding: 16px;
}

.technical-tile strong {
  margin: 8px 0;
  font-size: 21px;
  font-weight: 950;
  letter-spacing: -.025em;
}

.ai-analysis-head,
.config-head {
  background:
    radial-gradient(circle at 0% 0%, rgba(16, 185, 129, .12), transparent 34%),
    linear-gradient(135deg, rgba(255, 255, 255, .94), rgba(248, 250, 252, .88));
}

.ai-analysis-subtitle,
.config-subtitle {
  max-width: 760px;
  color: #64748b;
  font-size: 13px;
}

.analysis-runtime-grid {
  gap: 12px;
  padding: 18px 22px 0;
}

.analysis-runtime-grid article {
  min-height: 98px;
  padding: 16px;
}

.analysis-runtime-grid strong {
  margin: 8px 0;
  font-size: 24px;
  font-weight: 950;
}

.analysis-workspace {
  grid-template-columns: 230px minmax(0, 1fr);
  gap: 16px;
  padding: 16px 22px 22px;
}

.analysis-calendar {
  gap: 9px;
  padding: 14px;
  border-color: rgba(148, 163, 184, .18);
  border-radius: 20px;
  background: #f4f7fa;
}

.analysis-day-button {
  min-height: 40px;
  border-radius: 13px;
  padding: 0 12px;
}

.analysis-day-button:hover {
  background: rgba(255, 255, 255, .78);
}

.analysis-day-button.active {
  color: #047857;
  border-color: rgba(16, 185, 129, .28);
  background: #dcfce7;
}

.analysis-record-card,
.analysis-empty {
  padding: 18px;
}

.analysis-stance {
  min-height: 30px;
  border-radius: 999px;
  font-weight: 950;
}

.analysis-record-card > strong,
.analysis-empty strong {
  font-size: 18px;
  font-weight: 950;
}

.analysis-record-card p {
  color: #334155;
  font-size: 14px;
}

.config-tabbar {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  padding: 14px 18px;
  border-bottom-color: rgba(148, 163, 184, .16);
  background: rgba(248, 250, 252, .72);
}

.config-tabbar button {
  min-height: 72px;
  border: 1px solid rgba(148, 163, 184, .18);
  border-radius: 18px;
  background: #ffffff;
}

.config-tabbar button.active {
  border-color: #111827;
  color: #ffffff;
  background:
    linear-gradient(135deg, #111827 0%, #153426 100%);
  box-shadow: 0 16px 34px rgba(15, 23, 42, .18);
}

.config-tabbar strong {
  font-size: 17px;
  font-weight: 950;
}

.config-section {
  padding: 18px 22px 22px;
}

.config-summary-grid {
  gap: 12px;
}

.config-summary-grid article,
.delivery-side article {
  min-height: 112px;
  padding: 16px;
}

.config-summary-grid strong,
.delivery-side strong {
  margin: 8px 0;
  font-size: 22px;
  font-weight: 950;
}

.external-evidence-section {
  display: grid;
  gap: 16px;
}

.external-evidence-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 18px;
  border: 1px solid rgba(14, 165, 233, .22);
  border-radius: 22px;
  background:
    radial-gradient(circle at 100% 0%, rgba(14, 165, 233, .10), transparent 34%),
    linear-gradient(135deg, #ffffff 0%, #f4fbff 100%);
}

.external-evidence-toolbar strong {
  display: block;
  color: #0f172a;
  font-size: 24px;
  font-weight: 950;
  letter-spacing: -.04em;
}

.external-evidence-toolbar small {
  display: block;
  margin-top: 6px;
  color: #64748b;
  line-height: 1.6;
}

.external-evidence-empty {
  display: grid;
  min-height: 118px;
  place-content: center;
  padding: 22px;
  border: 1px dashed rgba(148, 163, 184, .5);
  border-radius: 22px;
  color: #64748b;
  background: rgba(255, 255, 255, .78);
  text-align: center;
}

.external-summary-grid {
  margin-top: 0;
}

.external-compare-grid,
.external-field-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.external-compare-card,
.external-field-grid article {
  min-width: 0;
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, .22);
  border-radius: 20px;
  background: #ffffff;
}

.external-compare-card {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 10px;
  align-items: end;
}

.external-compare-card > span {
  grid-column: 1 / -1;
  color: #64748b;
  font-size: 12px;
  font-weight: 900;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.external-compare-card strong,
.external-field-grid strong {
  display: block;
  color: #0f172a;
  font-size: 20px;
  font-weight: 950;
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.external-compare-card small,
.external-field-grid span,
.external-field-grid small {
  display: block;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.external-compare-card em {
  align-self: center;
  min-height: 30px;
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-style: normal;
  font-weight: 900;
  white-space: nowrap;
}

.external-compare-card em.ok {
  color: #047857;
  background: #dff8ec;
}

.external-compare-card em.warn {
  color: #92400e;
  background: #fef3c7;
}

.external-field-grid article {
  display: grid;
  gap: 7px;
}

.external-field-grid span {
  font-weight: 900;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.external-boundary-panel {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, .22);
  border-radius: 20px;
  background: #f8fafc;
}

.external-boundary-panel > div {
  flex: 0 0 100%;
}

.external-boundary-panel strong,
.external-boundary-panel small {
  display: block;
}

.external-boundary-panel small {
  margin-top: 4px;
  color: #64748b;
}

.external-boundary-panel span {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 5px 9px;
  border-radius: 999px;
  color: #075985;
  background: #e0f2fe;
  font-size: 12px;
  font-weight: 850;
}

.external-boundary-panel span.warn {
  color: #92400e;
  background: #fef3c7;
}

.observation-inline-panel {
  display: grid;
  gap: 12px;
  margin-top: 16px;
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, .24);
  border-radius: 22px;
  background:
    radial-gradient(circle at 100% 0%, rgba(14, 165, 233, .10), transparent 30%),
    #ffffff;
}

.observation-inline-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 14px;
}

.observation-inline-head h3 {
  margin: 0;
  color: #0f172a;
  font-size: 22px;
  font-weight: 950;
  letter-spacing: -.04em;
}

.observation-inline-head small {
  display: block;
  margin-top: 5px;
  color: #64748b;
}

.observation-empty-card {
  min-height: 92px;
  place-content: center;
  color: #64748b;
  text-align: center;
}

.delivery-grid {
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 16px;
}

.stock-form input,
.stock-form select,
.stock-form textarea,
.file-field strong {
  min-height: 44px;
  border-color: rgba(100, 116, 139, .26);
  border-radius: 14px;
  background: rgba(255, 255, 255, .92);
  font-size: 14px;
}

.stock-form label > span {
  color: #334155;
  font-size: 13px;
  font-weight: 900;
}

.config-form textarea {
  min-height: 104px;
}

.quick-intervals button,
.tone-selector button {
  min-height: 34px;
  border-color: rgba(100, 116, 139, .22);
  border-radius: 999px;
  padding: 0 13px;
  font-weight: 850;
}

.quick-intervals button:hover,
.tone-selector button:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 22px rgba(15, 23, 42, .08);
}

.delivery-buttons {
  justify-content: flex-start;
  margin-top: 16px;
}

.delivery-preview {
  border-color: rgba(148, 163, 184, .20);
  border-radius: 20px;
  background: #f4f7fa;
}

.delivery-preview-head {
  padding: 16px;
}

.delivery-preview-row p {
  font-size: 14px;
}

.session-card {
  gap: 14px;
  margin: 10px 14px 14px;
  padding: 16px;
  border-color: rgba(16, 185, 129, .20);
  border-radius: 20px;
  background:
    linear-gradient(180deg, #ffffff 0%, #f2fbf7 100%);
}

.session-card-top span {
  color: #047857;
  background: #dff8ec;
}

.session-meter i {
  background: linear-gradient(90deg, #10b981 0%, #0ea5e9 100%);
}

.signal-row,
.rule-card,
.source-row,
.event-card,
.ai-rule-empty,
.ai-rule-card {
  margin: 10px 14px;
  padding: 16px;
}

.analysis-gate {
  margin: 10px 14px 14px;
  padding: 16px;
  border-radius: 18px;
  color: #065f46;
  background: #dcfce7;
}

.event-card:hover {
  border-color: rgba(16, 185, 129, .28);
  box-shadow: 0 18px 38px rgba(15, 23, 42, .10);
}

.event-badge.opportunity,
.analysis-stance.green,
.tone-selector button.active.green { color: #047857; background: #dff8ec; }
.event-badge.risk,
.analysis-stance.red,
.tone-selector button.active.red { color: #b42318; background: #fee4e2; }
.event-badge.info,
.analysis-stance.blue,
.tone-selector button.active.blue { color: #075985; background: #e0f2fe; }
.event-badge.warning,
.analysis-stance.amber,
.tone-selector button.active.amber { color: #92400e; background: #fef3c7; }

pre {
  background: #f4f7fa;
}

.modal-panel {
  border-radius: 24px;
  box-shadow: 0 34px 92px rgba(15, 23, 42, .28);
}

.fc-section {
  display: grid;
  gap: 18px;
}

.fc-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: flex-start;
  padding: 18px;
  border: 1px solid rgba(148, 163, 184, .26);
  border-radius: 22px;
  background: linear-gradient(135deg, rgba(236, 253, 245, .88), rgba(255, 255, 255, .9));
}

.fc-toolbar strong {
  display: block;
  color: #0f172a;
  font-size: 24px;
  letter-spacing: -.04em;
}

.fc-toolbar small {
  display: block;
  margin-top: 6px;
  color: #64748b;
  line-height: 1.6;
}

.fc-toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}

.fc-stat-grid,
.fc-gate-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.fc-stat-grid article,
.fc-gate-grid article {
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, .24);
  border-radius: 18px;
  background: #fff;
}

.fc-stat-grid span,
.fc-gate-grid span {
  display: block;
  color: #64748b;
  font-size: 12px;
  font-weight: 900;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.fc-stat-grid strong,
.fc-gate-grid strong {
  display: block;
  margin-top: 7px;
  color: #0f172a;
  font-size: 28px;
  letter-spacing: -.05em;
}

.fc-stat-grid small {
  display: block;
  margin-top: 5px;
  color: #64748b;
  line-height: 1.45;
}

.fc-empty-card {
  display: grid;
  gap: 8px;
  padding: 26px;
  min-height: 150px;
  place-content: center;
  text-align: center;
  border: 1px dashed rgba(148, 163, 184, .5);
  border-radius: 24px;
  background: rgba(255, 255, 255, .72);
  color: #64748b;
}

.fc-empty-card strong {
  color: #0f172a;
  font-size: 20px;
}

.fc-workspace {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.fc-card-list {
  display: grid;
  gap: 12px;
}

.fc-card-button {
  width: 100%;
  text-align: left;
  border: 1px solid rgba(148, 163, 184, .28);
  border-radius: 22px;
  background: #fff;
  padding: 16px;
  cursor: pointer;
  transition: .18s ease;
}

.fc-card-button:hover {
  transform: translateY(-1px);
  border-color: rgba(16, 185, 129, .34);
  box-shadow: 0 14px 32px rgba(15, 23, 42, .08);
}

.fc-card-button.active {
  border-color: #0f172a;
  box-shadow: inset 0 0 0 2px #0f172a, 0 14px 32px rgba(15, 23, 42, .08);
}

.fc-card-button-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.fc-card-button-top strong {
  color: #0f172a;
  font-size: 16px;
}

.fc-card-button h3 {
  margin: 10px 0 7px;
  color: #0f172a;
  font-size: 19px;
  line-height: 1.24;
  letter-spacing: -.03em;
}

.fc-card-button p {
  margin: 0;
  color: #475569;
  line-height: 1.55;
}

.fc-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 12px;
}

.fc-pill {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 5px 9px;
  font-size: 12px;
  font-weight: 900;
  white-space: nowrap;
}

.fc-pill.green { color: #047857; background: #dff8ec; }
.fc-pill.blue { color: #075985; background: #e0f2fe; }
.fc-pill.amber { color: #92400e; background: #fef3c7; }
.fc-pill.red { color: #b42318; background: #fee4e2; }
.fc-pill.violet { color: #5b21b6; background: #ede9fe; }
.fc-pill.gray { color: #475569; background: #eef2f6; }

.fc-detail {
  display: grid;
  gap: 16px;
}

.fc-detail-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 230px;
  gap: 14px;
}

.fc-detail-hero > div,
.fc-detail-hero > article,
.fc-field-card,
.fc-baseline-editor {
  border: 1px solid rgba(148, 163, 184, .24);
  border-radius: 22px;
  background: #fff;
  padding: 18px;
}

.fc-detail-hero > div {
  background:
    radial-gradient(circle at 92% 0%, rgba(16, 185, 129, .12), transparent 34%),
    linear-gradient(135deg, #fff, #f6fbf7);
}

.fc-detail-hero h3 {
  margin: 8px 0 10px;
  color: #0f172a;
  font-size: 30px;
  line-height: 1.08;
  letter-spacing: -.05em;
}

.fc-detail-hero p,
.fc-field-card p {
  margin: 0;
  color: #405169;
  line-height: 1.65;
}

.fc-detail-hero article span {
  display: block;
  color: #64748b;
  font-weight: 900;
}

.fc-detail-hero article strong {
  display: block;
  margin: 10px 0;
  color: #0f172a;
  font-size: 34px;
  letter-spacing: -.05em;
}

.fc-detail-hero article small {
  color: #64748b;
  line-height: 1.45;
}

.fc-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.fc-field-card:last-child {
  grid-column: 1 / -1;
}

.fc-field-card > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.fc-field-card span {
  color: #64748b;
  font-size: 12px;
  font-weight: 900;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.fc-field-card em {
  color: #0f766e;
  font-size: 12px;
  font-style: normal;
  font-weight: 900;
}

.fc-gate {
  overflow: hidden;
  border-radius: 24px;
  background: #101828;
  color: #fff;
}

.fc-gate-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 18px;
  border-bottom: 1px solid rgba(255, 255, 255, .12);
}

.fc-gate-head strong {
  display: block;
  font-size: 22px;
}

.fc-gate-head small {
  display: block;
  margin-top: 5px;
  color: rgba(255, 255, 255, .62);
}

.fc-gate-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding: 14px 18px 18px;
}

.fc-gate-grid article {
  border-color: rgba(255, 255, 255, .12);
  background: rgba(255, 255, 255, .06);
}

.fc-gate-grid span {
  color: rgba(255, 255, 255, .62);
}

.fc-gate-grid strong {
  color: #fff;
}

.fc-blockers {
  margin: 0;
  padding: 0 18px 18px 36px;
  color: #fecaca;
  line-height: 1.7;
}

.fc-baseline-editor {
  display: grid;
  gap: 12px;
}

.fc-baseline-editor > div strong {
  display: block;
  color: #0f172a;
  font-size: 20px;
}

.fc-baseline-editor > div small {
  display: block;
  margin-top: 4px;
  color: #64748b;
}

.fc-baseline-editor label {
  display: grid;
  gap: 7px;
  color: #334155;
  font-weight: 800;
}

.fc-baseline-row {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 12px;
}

.fc-import-modal,
.fc-manual-modal {
  width: min(920px, calc(100vw - 32px));
}

.fc-manual-modal {
  width: min(980px, calc(100vw - 32px));
}

.fc-manual-form {
  padding-top: 0;
}

.fc-manual-form textarea {
  min-height: 92px;
  padding-top: 10px;
}

.fc-import-help {
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid rgba(16, 185, 129, .25);
  border-radius: 18px;
  color: #225443;
  background: #ecfdf5;
  line-height: 1.6;
}

.fc-import-textarea {
  width: 100%;
  min-height: 420px;
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, .38);
  border-radius: 18px;
  font-family: "SFMono-Regular", Menlo, Consolas, monospace;
  line-height: 1.55;
}

.analysis-cadence-line {
  margin-top: 3px;
  color: #2563eb !important;
  font-weight: 900;
}

.llm-policy-section {
  display: grid;
  gap: 16px;
}

.analysis-policy-editor {
  display: grid;
  gap: 16px;
  padding: 18px;
  border: 1px solid rgba(37, 99, 235, .18);
  border-radius: 24px;
  background:
    radial-gradient(circle at 100% 0%, rgba(37, 99, 235, .10), transparent 34%),
    linear-gradient(135deg, #ffffff 0%, #f8fbff 100%);
}

.analysis-policy-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.analysis-policy-head h3 {
  margin: 2px 0 4px;
  color: #0f172a;
  font-size: 22px;
  letter-spacing: -.02em;
}

.analysis-policy-head small,
.policy-field small,
.policy-check-card small,
.policy-boundary-note p {
  color: #64748b;
  line-height: 1.55;
}

.policy-version-chip {
  flex: 0 0 auto;
  padding: 8px 11px;
  border: 1px solid rgba(37, 99, 235, .18);
  border-radius: 999px;
  color: #1d4ed8;
  background: #eff6ff;
  font-size: 12px;
  font-weight: 950;
}

.policy-version-chip.draft {
  border-color: rgba(245, 158, 11, .28);
  color: #92400e;
  background: #fffbeb;
}

.policy-mode-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.policy-mode-grid button {
  min-height: 104px;
  padding: 13px;
  border: 1px solid rgba(148, 163, 184, .28);
  border-radius: 18px;
  color: #334155;
  background: rgba(255, 255, 255, .86);
  text-align: left;
  cursor: pointer;
}

.policy-mode-grid button.active {
  border-color: rgba(37, 99, 235, .42);
  color: #172554;
  background: #eff6ff;
  box-shadow: inset 0 0 0 1px rgba(37, 99, 235, .16);
}

.policy-mode-grid strong,
.policy-mode-grid small {
  display: block;
}

.policy-mode-grid strong {
  margin-bottom: 7px;
  font-size: 15px;
  font-weight: 950;
}

.policy-mode-grid small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.policy-form-grid {
  display: grid;
  grid-template-columns: minmax(220px, .78fr) minmax(280px, 1fr);
  gap: 12px;
}

.policy-field {
  display: grid;
  gap: 8px;
  min-height: 114px;
  padding: 14px;
  border: 1px solid rgba(148, 163, 184, .24);
  border-radius: 18px;
  background: #ffffff;
}

.policy-field-wide {
  grid-column: 1 / -1;
}

.policy-field > span {
  color: #334155;
  font-size: 13px;
  font-weight: 950;
}

.policy-field input,
.policy-field select {
  width: 100%;
  min-height: 42px;
  padding: 0 12px;
  border: 1px solid rgba(148, 163, 184, .36);
  border-radius: 12px;
  color: #0f172a;
  background: #ffffff;
  font-weight: 900;
}

.policy-field input:disabled,
.policy-field select:disabled {
  color: #94a3b8;
  background: #f8fafc;
}

.interval-preset-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  align-content: start;
  padding: 14px;
  border: 1px solid rgba(148, 163, 184, .24);
  border-radius: 18px;
  background: #ffffff;
}

.interval-preset-row button {
  min-height: 42px;
  border: 1px solid rgba(148, 163, 184, .28);
  border-radius: 12px;
  color: #334155;
  background: #f8fafc;
  font-size: 12px;
  font-weight: 950;
  cursor: pointer;
}

.interval-preset-row button.active {
  border-color: rgba(37, 99, 235, .38);
  color: #1d4ed8;
  background: #eff6ff;
}

.interval-preset-row.disabled {
  opacity: .58;
}

.policy-check-card {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  min-height: 92px;
  padding: 14px;
  border: 1px solid rgba(148, 163, 184, .24);
  border-radius: 18px;
  background: #ffffff;
}

.policy-check-card input {
  margin-top: 3px;
}

.policy-check-card strong,
.policy-check-card small {
  display: block;
}

.policy-check-card strong {
  margin-bottom: 4px;
  color: #0f172a;
  font-size: 14px;
  font-weight: 950;
}

.policy-check-card.disabled {
  opacity: .58;
}

.policy-boundary-note {
  padding: 12px 14px;
  border: 1px solid rgba(245, 158, 11, .24);
  border-radius: 18px;
  background: #fffbeb;
}

.policy-boundary-note span {
  display: block;
  margin-bottom: 4px;
  color: #92400e;
  font-size: 12px;
  font-weight: 950;
}

.policy-boundary-note p {
  margin: 0;
}

.analysis-session-grid {
  margin-top: 0;
}

@media (max-width: 1280px) {
  .stock-row {
    grid-template-columns: 8px 48px 1fr 112px 150px;
  }

  .policy-mode-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .list-layout,
  .detail-layout {
    grid-template-columns: 1fr;
  }

  .detail-layout > .side-stack,
  .source-panel,
  .quote-panel,
  .chart-panel,
  .ai-analysis-panel,
  .stock-config-panel,
  .event-panel,
  .contract-panel {
    grid-column: auto;
    grid-row: auto;
  }

  .watch-header::after {
    display: none;
  }

  .metric-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .stock-row {
    grid-template-columns: 8px 46px 1fr;
    align-items: start;
  }

  .quote-cell,
  .signal-cell {
    grid-column: 3;
  }

  .analysis-calendar {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .delivery-grid {
    grid-template-columns: 1fr;
  }

  .external-compare-grid,
  .external-field-grid {
    grid-template-columns: 1fr;
  }

  .config-tabbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .fc-workspace,
  .fc-detail-hero {
    grid-template-columns: 1fr;
  }

  .observation-inline-head {
    flex-direction: column;
  }

  .analysis-policy-head {
    flex-direction: column;
  }

  .policy-form-grid {
    grid-template-columns: 1fr;
  }

  .policy-field-wide {
    grid-column: auto;
  }
}

@media (max-width: 560px) {
  .stock-watch-page {
    padding: 0;
  }

  .watch-header,
  .panel,
  .metric-tile {
    border-radius: 18px;
  }

  .watch-header {
    padding: 20px;
  }

  .metric-strip {
    grid-template-columns: 1fr;
  }

  .config-tabbar {
    grid-template-columns: 1fr;
  }

  .fc-toolbar,
  .fc-gate-head,
  .external-evidence-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .fc-toolbar-actions,
  .fc-stat-grid,
  .fc-field-grid,
  .fc-gate-grid,
  .fc-baseline-row {
    grid-template-columns: 1fr;
  }

  .fc-toolbar-actions {
    display: grid;
  }

  .analysis-calendar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .prediction-review {
    margin: 0 10px 12px;
    padding: 12px;
  }

  .prediction-review-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .prediction-list-head {
    align-items: stretch;
    flex-direction: column;
  }

  .prediction-list-actions {
    justify-content: flex-start;
  }

  .prediction-stat-grid,
  .prediction-list,
  .prediction-day-details {
    grid-template-columns: 1fr;
  }

  .policy-mode-grid,
  .interval-preset-row {
    grid-template-columns: 1fr;
  }
}
</style>
