<template>
  <div class="workflow-templates" :class="{ 'designer-page-mode': isDesignerPage }">
    <div class="page-header">
      <div>
        <h2>会议流程模板</h2>
        <p class="subtitle">通过拖动节点、点击连线来搭建分析、复核、终审等流程，而不是手填一组阶段字段。</p>
      </div>
      <div class="page-header-actions">
        <el-button v-if="isDesignerPage" plain @click="exitDesignerPage">
          返回模板列表
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 新建流程
        </el-button>
      </div>
    </div>

    <div v-if="!isDesignerPage" v-loading="loading" class="template-grid">
      <el-empty v-if="!loading && templates.length === 0" description="暂无流程模板" :image-size="80" />

      <el-card v-for="tpl in templates" :key="tpl.id" shadow="hover" class="tpl-card">
        <div class="tpl-top">
          <div class="tpl-avatar" :class="{ builtin: tpl.builtIn === 1 }">
            {{ tpl.name?.charAt(0) || '流' }}
          </div>
          <div class="tpl-meta">
            <div class="tpl-name">
              {{ tpl.name }}
              <el-tag v-if="tpl.builtIn === 1" size="small" type="success" effect="plain" style="margin-left:6px">系统内置</el-tag>
            </div>
            <div class="tpl-key">{{ tpl.templateKey }}</div>
          </div>
          <div class="tpl-actions">
            <el-button link @click="openClone(tpl)">
              复制
            </el-button>
            <el-button v-if="tpl.builtIn !== 1" link type="primary" @click="openEdit(tpl)">
              编辑
            </el-button>
            <el-button v-if="tpl.builtIn !== 1" link type="danger" @click="handleDelete(tpl)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>

        <div v-if="tpl.description" class="tpl-desc">{{ tpl.description }}</div>

        <div class="stage-flow-preview">
          <div v-for="stage in tpl.stages" :key="stage.id || `${tpl.id}-${stage.stageKey}`" class="preview-stage">
            <div class="preview-stage-head">
              <span class="stage-order">#{{ stage.stageOrder }}</span>
              <span class="stage-name">{{ stage.stageName }}</span>
              <el-tag size="small" effect="plain">{{ stage.stageType }}</el-tag>
            </div>
            <div class="preview-stage-meta">
              <span>{{ actorTypeLabel(stage.actorType) }}</span>
              <span v-if="stage.skillKey">Skill: {{ stage.skillKey }}</span>
              <span>{{ searchStrategyLabel(stage.searchStrategy, stage.stageType) }}</span>
              <span v-if="['REVIEW','APPROVAL'].includes(stage.stageType)">
                {{ stage.reviewRequired === 0 ? '审核跳过' : '人工审核' }}
              </span>
            </div>
            <div class="preview-stage-links">
              <span v-if="stage.passTarget">通过 → {{ stage.passTarget }}</span>
              <span v-if="stage.rejectTarget">打回 → {{ stage.rejectTarget }}</span>
              <span v-if="stage.reopenTarget">重开 → {{ stage.reopenTarget }}</span>
            </div>
          </div>
        </div>
      </el-card>
    </div>

    <div v-if="isDesignerPage" class="designer-page">
      <div class="designer-page-topbar">
        <div class="designer-page-title">
          <strong>{{ designerTitle }}</strong>
          <span>{{ designerSubtitle }}</span>
        </div>
        <div class="designer-page-actions">
          <el-button plain @click="zoomOut">缩小</el-button>
          <el-tag effect="plain">{{ Math.round(zoomLevel * 100) }}%</el-tag>
          <el-button plain @click="zoomIn">放大</el-button>
          <el-button plain @click="resetZoom">重置缩放</el-button>
          <el-button plain @click="autoLayout">自动排布</el-button>
          <el-button type="primary" :loading="saving" @click="handleSave">{{ saveButtonLabel }}</el-button>
        </div>
      </div>
      <div class="designer-shell fullpage">
        <div class="designer-sidebar">
          <el-form ref="formRef" :model="form" label-width="82px">
            <el-form-item label="模板名称" prop="name" :rules="[{ required: true, message: '请输入模板名称' }]">
              <el-input v-model="form.name" placeholder="例如：双分析 + 终审" />
            </el-form-item>
            <el-form-item label="模板标识" prop="templateKey" :rules="[{ required: true, message: '请输入模板标识' }]">
              <el-input v-model="form.templateKey" placeholder="例如：dual-analysis-review" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="form.description" type="textarea" :rows="3" placeholder="说明这个流程适用于什么场景" />
            </el-form-item>
          </el-form>

          <div class="sidebar-block">
            <div class="sidebar-title">画布工具</div>
            <div class="tool-actions">
              <el-button size="small" plain @click="addStage">
                <el-icon><Plus /></el-icon> 添加节点
              </el-button>
              <el-button size="small" plain @click="addLane">
                <el-icon><Plus /></el-icon> 添加分组
              </el-button>
              <el-button size="small" plain @click="clearConnectMode" :disabled="!connectMode">退出连线</el-button>
            </div>
            <div class="designer-hint">
              全页模式支持更大画布和缩放，适合放下更多节点。
            </div>
            <div v-if="connectMode" class="connect-banner">
              正在连接：
              <b>{{ connectMode.sourceName }}</b>
              的
              <b>{{ connectLabel(connectMode.field) }}</b>
              指向
            </div>
          </div>

          <div class="sidebar-block">
            <div class="sidebar-title">流程预览</div>
            <div class="flow-preview-overview">
              <div class="flow-preview-stat">
                <span>起始节点</span>
                <strong>{{ flowPreview.startLabel }}</strong>
              </div>
              <div class="flow-preview-stat">
                <span>有效节点</span>
                <strong>{{ flowPreview.stageCount }}</strong>
              </div>
              <div class="flow-preview-stat">
                <span>可达节点</span>
                <strong>{{ flowPreview.reachableCount }}</strong>
              </div>
            </div>
            <div class="flow-preview-tags">
              <el-tag :type="flowPreview.issues.length ? 'warning' : 'success'" effect="plain">
                {{ flowPreview.issues.length ? `发现 ${flowPreview.issues.length} 个风险点` : '结构已连通' }}
              </el-tag>
              <el-tag v-if="flowPreview.hasReviewStage" effect="plain">含审核节点</el-tag>
              <el-tag v-if="flowPreview.manualNodeCount" effect="plain" type="info">手动节点 {{ flowPreview.manualNodeCount }}</el-tag>
            </div>
            <div v-if="flowPreview.issues.length" class="flow-issues">
              <div v-for="issue in flowPreview.issues" :key="issue.code" class="flow-issue-item">
                <strong>{{ issue.title }}</strong>
                <span>{{ issue.detail }}</span>
              </div>
            </div>
            <div v-else class="designer-hint">
              当前模板从起始节点可以覆盖所有有效节点，未发现孤立或断链节点。
            </div>
            <div class="flow-route-list">
              <div v-for="item in flowPreview.routePreview" :key="item.stageKey" class="flow-route-item">
                <div class="flow-route-head">
                  <strong>{{ item.stageName }}</strong>
                  <small>{{ item.stageType }}</small>
                </div>
                <div class="flow-route-links">
                  <span>通过 → {{ item.passLabel }}</span>
                  <span>打回 → {{ item.rejectLabel }}</span>
                  <span>重开 → {{ item.reopenLabel }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="sidebar-block">
            <div class="sidebar-title">执行槽位</div>
            <div class="tool-actions" style="margin-bottom:10px">
              <el-button size="small" plain @click="addActorSlot">
                <el-icon><Plus /></el-icon> 添加槽位
              </el-button>
            </div>
            <div v-if="!form.actorSlots.length" class="designer-hint">
              当前模板还没有执行槽位。圆桌模板可以保持为空；需要按节点分配 AI 成员时再新增槽位。
            </div>
            <div v-else class="node-list">
              <button
                v-for="(slot, idx) in form.actorSlots"
                :key="slot.uid"
                class="node-list-item lane-list-item"
                :class="{ active: selectedActorSlotIndex === idx }"
                @click="selectActorSlot(idx)"
              >
                <span>{{ slot.slotName || `未命名槽位 ${idx + 1}` }}</span>
                <small>{{ slot.slotKey || '未设置标识' }}</small>
              </button>
            </div>
          </div>

          <div v-if="selectedActorSlot" class="sidebar-block">
            <div class="sidebar-title">槽位属性</div>
            <el-form label-width="78px" class="node-form">
              <el-form-item label="标识">
                <el-input v-model="selectedActorSlot.slotKey" placeholder="analyst_a" />
              </el-form-item>
              <el-form-item label="名称">
                <el-input v-model="selectedActorSlot.slotName" placeholder="分析员A" />
              </el-form-item>
              <el-form-item label="说明">
                <el-input v-model="selectedActorSlot.description" type="textarea" :rows="2" placeholder="这个槽位负责什么" />
              </el-form-item>
              <el-form-item label="默认模型">
                <el-input v-model="selectedActorSlot.defaultModelType" placeholder="例如：gpt-4o" />
              </el-form-item>
              <el-form-item label="默认人设">
                <el-input v-model="selectedActorSlot.defaultPersona" type="textarea" :rows="2" placeholder="发起会议时默认带入的人设建议" />
              </el-form-item>
              <el-form-item label="必填">
                <el-switch v-model="selectedActorSlot.required" :active-value="1" :inactive-value="0" />
              </el-form-item>
              <el-form-item label="多人">
                <el-switch v-model="selectedActorSlot.allowMultiple" :active-value="1" :inactive-value="0" />
              </el-form-item>
              <div v-if="selectedActorSlotStageLabels.length" class="designer-hint skill-hint">
                引用节点：{{ selectedActorSlotStageLabels.join('、') }}
              </div>
              <div class="node-actions">
                <el-button size="small" plain @click="removeSelectedActorSlot">删除槽位</el-button>
              </div>
            </el-form>
          </div>

          <div class="sidebar-block">
            <div class="sidebar-title">分组泳道</div>
            <div class="node-list">
              <button
                v-for="(lane, idx) in boardLanes"
                :key="lane.uid"
                class="node-list-item lane-list-item"
                :class="{ active: selectedLaneIndex === idx }"
                @click="selectLane(idx)"
              >
                <span>{{ lane.label || `未命名分组 ${idx + 1}` }}</span>
                <small>{{ laneToneLabel(lane.tone) }} · {{ lane.width }} × {{ lane.height }}</small>
              </button>
            </div>
          </div>

          <div v-if="selectedLane" class="sidebar-block">
            <div class="sidebar-title">分组属性</div>
            <el-form label-width="78px" class="node-form">
              <el-form-item label="名称">
                <el-input v-model="selectedLane.label" placeholder="例如：分析与讨论" />
              </el-form-item>
              <el-form-item label="色调">
                <el-select v-model="selectedLane.tone" style="width:100%">
                  <el-option label="蓝色" value="analysis" />
                  <el-option label="紫色" value="review" />
                  <el-option label="橙色" value="tracking" />
                  <el-option label="灰色" value="generic" />
                </el-select>
              </el-form-item>
              <el-form-item label="位置 X">
                <el-slider v-model="selectedLane.x" :min="0" :max="canvasWidth - 260" :step="10" show-input input-size="small" />
              </el-form-item>
              <el-form-item label="位置 Y">
                <el-slider v-model="selectedLane.y" :min="0" :max="canvasHeight - 180" :step="10" show-input input-size="small" />
              </el-form-item>
              <el-form-item label="宽度">
                <el-slider v-model="selectedLane.width" :min="240" :max="900" :step="10" show-input input-size="small" />
              </el-form-item>
              <el-form-item label="高度">
                <el-slider v-model="selectedLane.height" :min="180" :max="900" :step="10" show-input input-size="small" />
              </el-form-item>
              <div class="node-actions">
                <el-button size="small" plain @click="removeSelectedLane" :disabled="boardLanes.length <= 1">删除分组</el-button>
              </div>
            </el-form>
          </div>

          <div class="sidebar-block">
            <div class="sidebar-title">节点列表</div>
            <div class="node-list">
              <button
                v-for="(stage, idx) in form.stages"
                :key="stage.uid"
                class="node-list-item"
                :class="{ active: selectedStageIndex === idx }"
                @click="selectStage(idx)"
              >
                <span>{{ stage.stageName || `未命名节点 ${idx + 1}` }}</span>
                <small>{{ stage.stageKey || '未设置标识' }}</small>
              </button>
            </div>
          </div>

          <div v-if="selectedStage" class="sidebar-block">
            <div class="sidebar-title">节点属性</div>
            <el-form label-width="78px" class="node-form">
              <el-form-item label="标识">
                <el-input v-model="selectedStage.stageKey" placeholder="analysis-a" />
              </el-form-item>
              <el-form-item label="名称">
                <el-input v-model="selectedStage.stageName" placeholder="分析员A提交" />
              </el-form-item>
              <el-form-item label="类型">
                <el-select v-model="selectedStage.stageType" style="width:100%">
                  <el-option label="DEBATE" value="DEBATE" />
                  <el-option label="ANALYSIS" value="ANALYSIS" />
                  <el-option label="REVIEW" value="REVIEW" />
                  <el-option label="APPROVAL" value="APPROVAL" />
                  <el-option label="TRACKING" value="TRACKING" />
                </el-select>
              </el-form-item>
              <el-form-item label="执行者">
                <el-select v-model="selectedStage.actorType" style="width:100%">
                  <el-option label="ALL_MEMBERS" value="ALL_MEMBERS" />
                  <el-option label="ANALYST" value="ANALYST" />
                  <el-option label="REVIEWER" value="REVIEWER" />
                  <el-option label="APPROVER" value="APPROVER" />
                  <el-option label="HUMAN" value="HUMAN" />
                  <el-option label="SYSTEM" value="SYSTEM" />
                </el-select>
              </el-form-item>
              <el-form-item label="绑定方式">
                <el-select v-model="selectedStage.actorBindingMode" style="width:100%">
                  <el-option label="全体成员" value="ALL_MEMBERS" />
                  <el-option label="指定槽位" value="BY_SLOT" />
                  <el-option label="人工节点" value="HUMAN" />
                  <el-option label="系统节点" value="SYSTEM" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="selectedStage.actorBindingMode === 'BY_SLOT'" label="执行槽位">
                <el-select v-model="selectedStage.actorSlotKey" clearable filterable style="width:100%">
                  <el-option v-for="slot in actorSlotOptions" :key="slot.value" :label="slot.label" :value="slot.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="所属分组">
                <el-select v-model="selectedStage.laneUid" style="width:100%">
                  <el-option v-for="lane in boardLanes" :key="lane.uid" :label="lane.label || lane.uid" :value="lane.uid" />
                </el-select>
              </el-form-item>
              <el-form-item label="完成规则">
                <el-input v-model="selectedStage.completionRule" placeholder="例如：ALL_SUBMITTED" />
              </el-form-item>
              <el-form-item label="Skill(选填)">
                <el-select
                  v-model="selectedStage.skillKey"
                  filterable
                  allow-create
                  clearable
                  default-first-option
                  @change="applySkillPreset(selectedStage)"
                  style="width:100%"
                  placeholder="可留空；也可临时手动输入 skill 标识"
                >
                  <el-option v-for="opt in skillOptionsForStage(selectedStage)" :key="opt.value" :label="`${opt.label} · ${opt.value}`" :value="opt.value" />
                </el-select>
              </el-form-item>
              <div v-if="selectedStage.skillKey && skillMeta(selectedStage.skillKey)" class="designer-hint skill-hint">
                <div><b>{{ skillMeta(selectedStage.skillKey).name }}</b></div>
                <div>{{ skillMeta(selectedStage.skillKey).description }}</div>
                <div>适用阶段：{{ (skillMeta(selectedStage.skillKey).stageTypes || []).join(' / ') }}</div>
              </div>
              <div v-else-if="selectedStage.skillPrompt?.trim()" class="designer-hint skill-hint">
                <div><b>手动节点执行要求</b></div>
                <div>当前没有绑定预设 Skill，这个节点会按你填写的临时执行要求运行。</div>
              </div>
              <div v-if="selectedStage.actorBindingMode === 'BY_SLOT' && !selectedStage.actorSlotKey" class="designer-hint skill-hint">
                <div><b>建议补充执行槽位</b></div>
                <div>这个节点已经切换为“指定槽位”，但还没有选中具体槽位。</div>
              </div>
              <el-form-item label="Skill指令">
                <el-input v-model="selectedStage.skillPrompt" type="textarea" :rows="3" placeholder="选填；如果没有预设 skill，也可以只在这里临时写本节点的执行要求" />
              </el-form-item>
              <el-form-item label="联网搜索">
                <el-select v-model="selectedStage.searchStrategy" style="width:100%">
                  <el-option label="关闭搜索" value="OFF" />
                  <el-option label="按需搜索" value="OPTIONAL" />
                  <el-option label="强制搜索" value="REQUIRED" />
                </el-select>
              </el-form-item>
              <div class="designer-hint skill-hint">
                {{ searchStrategyHint(selectedStage.searchStrategy, selectedStage.stageType) }}
              </div>
              <el-form-item label="Skill文件">
                <el-select v-model="selectedStage.skillFileIds" multiple collapse-tags collapse-tags-tooltip filterable clearable style="width:100%" placeholder="可选择多个文件">
                  <el-option v-for="opt in skillFileOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
              <el-upload drag multiple :show-file-list="false" :http-request="handleSkillFileUpload" class="skill-file-upload">
                <div class="skill-file-upload-inner">
                  <div><b>拖拽上传 Skill 文件</b></div>
                  <div>支持直接拖入多个文件，上传后会自动加入当前节点选择。</div>
                </div>
              </el-upload>
              <div v-if="selectedSkillFiles(selectedStage).length" class="skill-file-list">
                <div v-for="file in selectedSkillFiles(selectedStage)" :key="file.id" class="skill-file-item">
                  <div>
                    <b>{{ file.fileName }}</b>
                    <span>{{ formatFileSize(file.fileSize) }}</span>
                  </div>
                  <div class="skill-file-item-actions">
                    <el-button link @click="downloadSkillFile(file.id)">下载</el-button>
                    <el-button link type="danger" @click="deleteSkillFile(file.id)">删除文件</el-button>
                  </div>
                </div>
              </div>
              <div v-if="selectedStage.skillKey && skillMeta(selectedStage.skillKey)?.defaultPrompt && selectedStage.skillPrompt?.trim() !== skillMeta(selectedStage.skillKey)?.defaultPrompt" class="node-actions">
                <el-button size="small" plain @click="fillSkillDefaultPrompt(selectedStage)">填入默认指令</el-button>
                <el-button size="small" plain @click="selectedStage.skillKey = ''">改为手动节点</el-button>
              </div>
              <el-form-item v-if="needsReviewToggle(selectedStage)" label="审核">
                <el-switch v-model="selectedStage.reviewRequired" :active-value="1" :inactive-value="0" active-text="开启" inactive-text="跳过" />
              </el-form-item>
              <el-form-item v-if="needsReviewToggle(selectedStage)" label="最多打回">
                <el-input-number v-model="selectedStage.maxReviewAttempts" :min="0" :max="10" :step="1" style="width:100%" />
              </el-form-item>
              <div v-if="needsReviewToggle(selectedStage)" class="designer-hint skill-hint">
                0 表示不限制；大于 0 时，允许最多连续打回这么多次，超过后会自动截止。
              </div>
              <el-form-item label="通过后">
                <el-select v-model="selectedStage.passTargetUid" clearable filterable style="width:100%">
                  <el-option v-for="opt in targetOptions(selectedStage.uid)" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="打回到">
                <el-select v-model="selectedStage.rejectTargetUid" clearable filterable style="width:100%">
                  <el-option v-for="opt in targetOptions(selectedStage.uid)" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="重开到">
                <el-select v-model="selectedStage.reopenTargetUid" clearable filterable style="width:100%">
                  <el-option v-for="opt in targetOptions(selectedStage.uid)" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="宽度">
                <el-slider v-model="selectedStage.width" :min="220" :max="420" :step="10" show-input input-size="small" />
              </el-form-item>
              <el-form-item label="高度">
                <el-slider v-model="selectedStage.height" :min="150" :max="320" :step="10" show-input input-size="small" />
              </el-form-item>
            </el-form>
          </div>
        </div>
        <div class="designer-board-wrap fullpage">
          <div class="designer-board-toolbar">
            <span>流程画布</span>
            <el-tag effect="plain">缩放 {{ Math.round(zoomLevel * 100) }}%</el-tag>
          </div>
          <div class="designer-board-viewport">
            <div class="designer-scene-frame" :style="sceneFrameStyle">
              <div class="designer-scene" :style="sceneStyle">
                <div ref="canvasRef" class="designer-board fullpage" :style="boardStyle" @click="handleCanvasClick">
                <div class="board-lanes">
                  <div
                    v-for="(lane, idx) in boardLanes"
                    :key="lane.uid"
                    class="board-lane"
                    :class="[{ active: selectedLaneIndex === idx, dragging: draggingLaneUid === lane.uid, resizing: resizingLaneUid === lane.uid }, `lane-${lane.tone || 'generic'}`]"
                    :style="{ left: `${lane.x}px`, top: `${lane.y}px`, width: `${lane.width}px`, height: `${lane.height}px`, zIndex: laneZIndex(idx, lane) }"
                    @click.stop="selectLane(idx)"
                  >
                    <div class="board-lane-label" @mousedown.stop.prevent="startLaneDrag($event, idx)">
                      {{ lane.label }}
                    </div>
                    <div class="board-lane-resize-x" @mousedown.stop.prevent="startLaneResize($event, idx, 'x')"></div>
                    <div class="board-lane-resize-y" @mousedown.stop.prevent="startLaneResize($event, idx, 'y')"></div>
                    <div class="board-lane-resize-corner" @mousedown.stop.prevent="startLaneResize($event, idx, 'xy')"></div>
                  </div>
                </div>
                <svg class="designer-lines" :viewBox="`0 0 ${canvasWidth} ${canvasHeight}`" preserveAspectRatio="none">
                  <defs>
                    <marker id="arrow-pass-page" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                      <path d="M 0 0 L 10 5 L 0 10 z" fill="#409eff" />
                    </marker>
                    <marker id="arrow-reject-page" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                      <path d="M 0 0 L 10 5 L 0 10 z" fill="#f56c6c" />
                    </marker>
                    <marker id="arrow-reopen-page" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                      <path d="M 0 0 L 10 5 L 0 10 z" fill="#e6a23c" />
                    </marker>
                  </defs>
                  <g v-for="line in flowLines" :key="`${line.id}-page`">
                    <path :d="line.path" :class="['flow-line', `flow-line-${line.kind}`]" :marker-end="`url(#arrow-${line.kind}-page)`" />
                    <text :x="line.labelX" :y="line.labelY" class="flow-line-label">{{ connectLabel(line.field) }}</text>
                  </g>
                </svg>

                <div
                  v-for="(stage, idx) in form.stages"
                  :key="`${stage.uid}-page`"
                  class="stage-node"
                  :class="{
                    active: selectedStageIndex === idx,
                    linking: connectMode?.sourceUid === stage.uid,
                    'connect-target': connectMode && connectMode.sourceUid !== stage.uid,
                    dragging: draggingStageUid === stage.uid,
                    [stageToneClass(stage)]: true
                  }"
                  :style="{ left: `${stage.x}px`, top: `${stage.y}px`, width: `${stage.width}px`, minHeight: `${stage.height}px` }"
                  @dragstart.prevent
                  @click.stop="handleStageClick(idx)"
                >
                  <div class="stage-node-head">
                    <div class="stage-node-handle" @mousedown.stop.prevent="startDrag($event, idx)">拖动</div>
                    <div>
                      <div class="stage-node-title">{{ stage.stageName || `未命名节点 ${idx + 1}` }}</div>
                      <div class="stage-node-key">{{ stage.stageKey || '未设置标识' }}</div>
                    </div>
                    <el-tag size="small" effect="plain">{{ stage.stageType }}</el-tag>
                  </div>
                  <div class="stage-node-body">
                    <div class="stage-node-meta">{{ actorTypeLabel(stage.actorType) }}</div>
                    <div v-if="stage.actorBindingMode === 'BY_SLOT' && stage.actorSlotKey" class="stage-node-meta stage-node-skill">
                      槽位 · {{ stage.actorSlotKey }}
                    </div>
                    <div v-if="stage.skillKey" class="stage-node-meta stage-node-skill">Skill · {{ stage.skillKey }}</div>
                    <div v-else-if="stage.skillPrompt" class="stage-node-meta stage-node-skill">手动节点</div>
                    <div class="stage-node-meta">搜索 · {{ searchStrategyLabel(stage.searchStrategy, stage.stageType) }}</div>
                    <div v-if="stage.skillFileIds?.length" class="stage-node-meta">文件 · {{ stage.skillFileIds.length }} 个</div>
                    <div v-if="needsReviewToggle(stage)" class="stage-node-meta">
                      {{ stage.reviewRequired === 0 ? '审核跳过' : '人工审核' }}
                    </div>
                  </div>
                  <div class="stage-node-links">
                    <button class="link-chip pass" @mousedown.stop @click.stop="startConnect(stage, 'passTarget')">
                      通过
                      <span>{{ targetLabel(stage.passTargetUid) }}</span>
                    </button>
                    <button class="link-chip reject" @mousedown.stop @click.stop="startConnect(stage, 'rejectTarget')">
                      打回
                      <span>{{ targetLabel(stage.rejectTargetUid) }}</span>
                    </button>
                    <button class="link-chip reopen" @mousedown.stop @click.stop="startConnect(stage, 'reopenTarget')">
                      重开
                      <span>{{ targetLabel(stage.reopenTargetUid) }}</span>
                    </button>
                  </div>
                  <div class="stage-node-resize" @mousedown.stop.prevent="startResize($event, idx)"></div>
                </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" title="可视化流程设计器" width="1200px" :close-on-click-modal="false" top="4vh" :show-close="!isDesignerPage">
      <div class="designer-shell">
        <div class="designer-sidebar">
          <el-form ref="formRef" :model="form" label-width="82px">
            <el-form-item label="模板名称" prop="name" :rules="[{ required: true, message: '请输入模板名称' }]">
              <el-input v-model="form.name" placeholder="例如：双分析 + 终审" />
            </el-form-item>
            <el-form-item label="模板标识" prop="templateKey" :rules="[{ required: true, message: '请输入模板标识' }]">
              <el-input v-model="form.templateKey" placeholder="例如：dual-analysis-review" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="form.description" type="textarea" :rows="3" placeholder="说明这个流程适用于什么场景" />
            </el-form-item>
          </el-form>

          <div class="sidebar-block">
            <div class="sidebar-title">画布工具</div>
            <div class="tool-actions">
              <el-button size="small" plain @click="addStage">
                <el-icon><Plus /></el-icon> 添加节点
              </el-button>
              <el-button size="small" plain @click="addLane">
                <el-icon><Plus /></el-icon> 添加分组
              </el-button>
              <el-button size="small" plain @click="autoLayout">自动排布</el-button>
              <el-button size="small" plain @click="clearConnectMode" :disabled="!connectMode">退出连线</el-button>
            </div>
            <div class="designer-hint">
              1. 拖动节点调整位置
              <br>
              2. 点击节点上的“通过 / 打回 / 重开”进入连线模式
              <br>
              3. 再点击目标节点，完成连接
            </div>
            <div v-if="connectMode" class="connect-banner">
              正在连接：
              <b>{{ connectMode.sourceName }}</b>
              的
              <b>{{ connectLabel(connectMode.field) }}</b>
              指向
            </div>
          </div>

          <div class="sidebar-block">
            <div class="sidebar-title">流程预览</div>
            <div class="flow-preview-overview compact">
              <div class="flow-preview-stat">
                <span>起始</span>
                <strong>{{ flowPreview.startLabel }}</strong>
              </div>
              <div class="flow-preview-stat">
                <span>有效节点</span>
                <strong>{{ flowPreview.stageCount }}</strong>
              </div>
            </div>
            <div class="flow-preview-tags">
              <el-tag :type="flowPreview.issues.length ? 'warning' : 'success'" effect="plain">
                {{ flowPreview.issues.length ? `风险 ${flowPreview.issues.length}` : '结构正常' }}
              </el-tag>
            </div>
            <div v-if="flowPreview.issues.length" class="flow-issues compact">
              <div v-for="issue in flowPreview.issues" :key="issue.code" class="flow-issue-item">
                <strong>{{ issue.title }}</strong>
                <span>{{ issue.detail }}</span>
              </div>
            </div>
            <div v-else class="designer-hint">
              当前模板没有发现明显断链。
            </div>
          </div>

          <div class="sidebar-block">
            <div class="sidebar-title">执行槽位</div>
            <div class="tool-actions" style="margin-bottom:10px">
              <el-button size="small" plain @click="addActorSlot">
                <el-icon><Plus /></el-icon> 添加槽位
              </el-button>
            </div>
            <div v-if="!form.actorSlots.length" class="designer-hint">
              当前模板还没有执行槽位。圆桌模板可以保持为空；需要按节点分配 AI 成员时再新增槽位。
            </div>
            <div v-else class="node-list">
              <button
                v-for="(slot, idx) in form.actorSlots"
                :key="slot.uid"
                class="node-list-item lane-list-item"
                :class="{ active: selectedActorSlotIndex === idx }"
                @click="selectActorSlot(idx)"
              >
                <span>{{ slot.slotName || `未命名槽位 ${idx + 1}` }}</span>
                <small>{{ slot.slotKey || '未设置标识' }}</small>
              </button>
            </div>
          </div>

          <div class="sidebar-block">
            <div class="sidebar-title">节点列表</div>
            <div class="node-list">
              <button
                v-for="(stage, idx) in form.stages"
                :key="stage.uid"
                class="node-list-item"
                :class="{ active: selectedStageIndex === idx }"
                @click="selectStage(idx)"
              >
                <span>{{ stage.stageName || `未命名节点 ${idx + 1}` }}</span>
                <small>{{ stage.stageKey || '未设置标识' }}</small>
              </button>
            </div>
          </div>

          <div class="sidebar-block">
            <div class="sidebar-title">分组泳道</div>
            <div class="node-list">
              <button
                v-for="(lane, idx) in boardLanes"
                :key="lane.uid"
                class="node-list-item lane-list-item"
                :class="{ active: selectedLaneIndex === idx }"
                @click="selectLane(idx)"
              >
                <span>{{ lane.label || `未命名分组 ${idx + 1}` }}</span>
                <small>{{ laneToneLabel(lane.tone) }} · {{ lane.width }} × {{ lane.height }}</small>
              </button>
            </div>
          </div>

          <div v-if="selectedLane" class="sidebar-block">
            <div class="sidebar-title">分组属性</div>
            <el-form label-width="78px" class="node-form">
              <el-form-item label="名称">
                <el-input v-model="selectedLane.label" placeholder="例如：分析与讨论" />
              </el-form-item>
              <el-form-item label="色调">
                <el-select v-model="selectedLane.tone" style="width:100%">
                  <el-option label="蓝色" value="analysis" />
                  <el-option label="紫色" value="review" />
                  <el-option label="橙色" value="tracking" />
                  <el-option label="灰色" value="generic" />
                </el-select>
              </el-form-item>
              <el-form-item label="位置 X">
                <el-slider v-model="selectedLane.x" :min="0" :max="canvasWidth - 260" :step="10" show-input input-size="small" />
              </el-form-item>
              <el-form-item label="位置 Y">
                <el-slider v-model="selectedLane.y" :min="0" :max="canvasHeight - 180" :step="10" show-input input-size="small" />
              </el-form-item>
              <el-form-item label="宽度">
                <el-slider v-model="selectedLane.width" :min="240" :max="900" :step="10" show-input input-size="small" />
              </el-form-item>
              <el-form-item label="高度">
                <el-slider v-model="selectedLane.height" :min="180" :max="900" :step="10" show-input input-size="small" />
              </el-form-item>
              <div class="node-actions">
                <el-button size="small" plain @click="removeSelectedLane" :disabled="boardLanes.length <= 1">删除分组</el-button>
              </div>
            </el-form>
          </div>

          <div v-if="selectedActorSlot" class="sidebar-block">
            <div class="sidebar-title">槽位属性</div>
            <el-form label-width="78px" class="node-form">
              <el-form-item label="标识">
                <el-input v-model="selectedActorSlot.slotKey" placeholder="analyst_a" />
              </el-form-item>
              <el-form-item label="名称">
                <el-input v-model="selectedActorSlot.slotName" placeholder="分析员A" />
              </el-form-item>
              <el-form-item label="说明">
                <el-input v-model="selectedActorSlot.description" type="textarea" :rows="2" placeholder="这个槽位负责什么" />
              </el-form-item>
              <el-form-item label="默认模型">
                <el-input v-model="selectedActorSlot.defaultModelType" placeholder="例如：gpt-4o" />
              </el-form-item>
              <el-form-item label="默认人设">
                <el-input v-model="selectedActorSlot.defaultPersona" type="textarea" :rows="2" placeholder="发起会议时默认带入的人设建议" />
              </el-form-item>
              <el-form-item label="必填">
                <el-switch v-model="selectedActorSlot.required" :active-value="1" :inactive-value="0" />
              </el-form-item>
              <el-form-item label="多人">
                <el-switch v-model="selectedActorSlot.allowMultiple" :active-value="1" :inactive-value="0" />
              </el-form-item>
              <div v-if="selectedActorSlotStageLabels.length" class="designer-hint skill-hint">
                引用节点：{{ selectedActorSlotStageLabels.join('、') }}
              </div>
              <div class="node-actions">
                <el-button size="small" plain @click="removeSelectedActorSlot">删除槽位</el-button>
              </div>
            </el-form>
          </div>

          <div v-if="selectedStage" class="sidebar-block">
            <div class="sidebar-title">节点属性</div>
            <el-form label-width="78px" class="node-form">
              <el-form-item label="标识">
                <el-input v-model="selectedStage.stageKey" placeholder="analysis-a" />
              </el-form-item>
              <el-form-item label="名称">
                <el-input v-model="selectedStage.stageName" placeholder="分析员A提交" />
              </el-form-item>
              <el-form-item label="类型">
                <el-select v-model="selectedStage.stageType" style="width:100%">
                  <el-option label="DEBATE" value="DEBATE" />
                  <el-option label="ANALYSIS" value="ANALYSIS" />
                  <el-option label="REVIEW" value="REVIEW" />
                  <el-option label="APPROVAL" value="APPROVAL" />
                  <el-option label="TRACKING" value="TRACKING" />
                </el-select>
              </el-form-item>
              <el-form-item label="执行者">
                <el-select v-model="selectedStage.actorType" style="width:100%">
                  <el-option label="ALL_MEMBERS" value="ALL_MEMBERS" />
                  <el-option label="ANALYST" value="ANALYST" />
                  <el-option label="REVIEWER" value="REVIEWER" />
                  <el-option label="APPROVER" value="APPROVER" />
                  <el-option label="HUMAN" value="HUMAN" />
                  <el-option label="SYSTEM" value="SYSTEM" />
                </el-select>
              </el-form-item>
              <el-form-item label="绑定方式">
                <el-select v-model="selectedStage.actorBindingMode" style="width:100%">
                  <el-option label="全体成员" value="ALL_MEMBERS" />
                  <el-option label="指定槽位" value="BY_SLOT" />
                  <el-option label="人工节点" value="HUMAN" />
                  <el-option label="系统节点" value="SYSTEM" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="selectedStage.actorBindingMode === 'BY_SLOT'" label="执行槽位">
                <el-select v-model="selectedStage.actorSlotKey" clearable filterable style="width:100%">
                  <el-option v-for="slot in actorSlotOptions" :key="slot.value" :label="slot.label" :value="slot.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="所属分组">
                <el-select v-model="selectedStage.laneUid" style="width:100%">
                  <el-option v-for="lane in boardLanes" :key="lane.uid" :label="lane.label || lane.uid" :value="lane.uid" />
                </el-select>
              </el-form-item>
              <el-form-item label="完成规则">
                <el-input v-model="selectedStage.completionRule" placeholder="例如：ALL_SUBMITTED" />
              </el-form-item>
              <el-form-item label="Skill(选填)">
                <el-select
                  v-model="selectedStage.skillKey"
                  filterable
                  allow-create
                  clearable
                  default-first-option
                  @change="applySkillPreset(selectedStage)"
                  style="width:100%"
                  placeholder="可留空；也可临时手动输入 skill 标识"
                >
                  <el-option v-for="opt in skillOptionsForStage(selectedStage)" :key="opt.value" :label="`${opt.label} · ${opt.value}`" :value="opt.value" />
                </el-select>
              </el-form-item>
              <div v-if="selectedStage.skillKey && skillMeta(selectedStage.skillKey)" class="designer-hint skill-hint">
                <div><b>{{ skillMeta(selectedStage.skillKey).name }}</b></div>
                <div>{{ skillMeta(selectedStage.skillKey).description }}</div>
                <div>适用阶段：{{ (skillMeta(selectedStage.skillKey).stageTypes || []).join(' / ') }}</div>
              </div>
              <div v-else-if="selectedStage.skillPrompt?.trim()" class="designer-hint skill-hint">
                <div><b>手动节点执行要求</b></div>
                <div>当前没有绑定预设 Skill，这个节点会按你填写的临时执行要求运行。</div>
              </div>
              <div v-if="selectedStage.actorBindingMode === 'BY_SLOT' && !selectedStage.actorSlotKey" class="designer-hint skill-hint">
                <div><b>建议补充执行槽位</b></div>
                <div>这个节点已经切换为“指定槽位”，但还没有选中具体槽位。</div>
              </div>
              <el-form-item label="Skill指令">
                <el-input v-model="selectedStage.skillPrompt" type="textarea" :rows="3" placeholder="选填；如果没有预设 skill，也可以只在这里临时写本节点的执行要求" />
              </el-form-item>
              <el-form-item label="联网搜索">
                <el-select v-model="selectedStage.searchStrategy" style="width:100%">
                  <el-option label="关闭搜索" value="OFF" />
                  <el-option label="按需搜索" value="OPTIONAL" />
                  <el-option label="强制搜索" value="REQUIRED" />
                </el-select>
              </el-form-item>
              <div class="designer-hint skill-hint">
                {{ searchStrategyHint(selectedStage.searchStrategy, selectedStage.stageType) }}
              </div>
              <el-form-item label="Skill文件">
                <el-select v-model="selectedStage.skillFileIds" multiple collapse-tags collapse-tags-tooltip filterable clearable style="width:100%" placeholder="可选择多个文件">
                  <el-option v-for="opt in skillFileOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
              <el-upload drag multiple :show-file-list="false" :http-request="handleSkillFileUpload" class="skill-file-upload">
                <div class="skill-file-upload-inner">
                  <div><b>拖拽上传 Skill 文件</b></div>
                  <div>支持直接拖入多个文件，上传后会自动加入当前节点选择。</div>
                </div>
              </el-upload>
              <div v-if="selectedSkillFiles(selectedStage).length" class="skill-file-list">
                <div v-for="file in selectedSkillFiles(selectedStage)" :key="file.id" class="skill-file-item">
                  <div>
                    <b>{{ file.fileName }}</b>
                    <span>{{ formatFileSize(file.fileSize) }}</span>
                  </div>
                  <div class="skill-file-item-actions">
                    <el-button link @click="downloadSkillFile(file.id)">下载</el-button>
                    <el-button link type="danger" @click="deleteSkillFile(file.id)">删除文件</el-button>
                  </div>
                </div>
              </div>
              <div v-if="selectedStage.skillKey && skillMeta(selectedStage.skillKey)?.defaultPrompt && selectedStage.skillPrompt?.trim() !== skillMeta(selectedStage.skillKey)?.defaultPrompt" class="node-actions">
                <el-button size="small" plain @click="fillSkillDefaultPrompt(selectedStage)">填入默认指令</el-button>
                <el-button size="small" plain @click="selectedStage.skillKey = ''">改为手动节点</el-button>
              </div>
              <el-form-item v-if="needsReviewToggle(selectedStage)" label="审核">
                <el-switch
                  v-model="selectedStage.reviewRequired"
                  :active-value="1"
                  :inactive-value="0"
                  active-text="开启"
                  inactive-text="跳过"
                />
              </el-form-item>
              <el-form-item v-if="needsReviewToggle(selectedStage)" label="最多打回">
                <el-input-number v-model="selectedStage.maxReviewAttempts" :min="0" :max="10" :step="1" style="width:100%" />
              </el-form-item>
              <div v-if="needsReviewToggle(selectedStage)" class="designer-hint skill-hint">
                0 表示不限制；大于 0 时，允许最多连续打回这么多次，超过后会自动截止。
              </div>
              <el-form-item label="通过后">
                <el-select v-model="selectedStage.passTargetUid" clearable filterable style="width:100%">
                  <el-option v-for="opt in targetOptions(selectedStage.uid)" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="打回到">
                <el-select v-model="selectedStage.rejectTargetUid" clearable filterable style="width:100%">
                  <el-option v-for="opt in targetOptions(selectedStage.uid)" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="重开到">
                <el-select v-model="selectedStage.reopenTargetUid" clearable filterable style="width:100%">
                  <el-option v-for="opt in targetOptions(selectedStage.uid)" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="宽度">
                <el-slider v-model="selectedStage.width" :min="220" :max="420" :step="10" show-input input-size="small" />
              </el-form-item>
              <el-form-item label="高度">
                <el-slider v-model="selectedStage.height" :min="150" :max="320" :step="10" show-input input-size="small" />
              </el-form-item>
              <div class="node-actions">
                <el-button size="small" plain @click="duplicateStage(selectedStage.uid)">复制节点</el-button>
                <el-button size="small" type="danger" plain @click="removeSelectedStage" :disabled="form.stages.length <= 1">删除节点</el-button>
              </div>
            </el-form>
          </div>
        </div>

        <div class="designer-board-wrap">
          <div class="designer-board-toolbar">
            <span>流程画布</span>
            <el-tag v-if="selectedStage" effect="plain">
              当前选中：{{ selectedStage.stageName || selectedStage.stageKey || '未命名节点' }}
            </el-tag>
          </div>
          <div ref="canvasRef" class="designer-board" @click="handleCanvasClick">
            <div class="board-lanes">
              <div
                v-for="(lane, idx) in boardLanes"
                :key="lane.uid"
                class="board-lane"
                :class="[{ active: selectedLaneIndex === idx, dragging: draggingLaneUid === lane.uid, resizing: resizingLaneUid === lane.uid }, `lane-${lane.tone || 'generic'}`]"
                :style="{ left: `${lane.x}px`, top: `${lane.y}px`, width: `${lane.width}px`, height: `${lane.height}px`, zIndex: laneZIndex(idx, lane) }"
                @click.stop="selectLane(idx)"
              >
                <div class="board-lane-label" @mousedown.stop.prevent="startLaneDrag($event, idx)">{{ lane.label }}</div>
                <div class="board-lane-resize-x" @mousedown.stop.prevent="startLaneResize($event, idx, 'x')"></div>
                <div class="board-lane-resize-y" @mousedown.stop.prevent="startLaneResize($event, idx, 'y')"></div>
                <div class="board-lane-resize-corner" @mousedown.stop.prevent="startLaneResize($event, idx, 'xy')"></div>
              </div>
            </div>
            <svg class="designer-lines" :viewBox="`0 0 ${canvasWidth} ${canvasHeight}`" preserveAspectRatio="none">
              <defs>
                <marker id="arrow-pass" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#409eff" />
                </marker>
                <marker id="arrow-reject" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#f56c6c" />
                </marker>
                <marker id="arrow-reopen" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#e6a23c" />
                </marker>
              </defs>
              <g v-for="line in flowLines" :key="line.id">
                <path
                  :d="line.path"
                  :class="['flow-line', `flow-line-${line.kind}`]"
                  :marker-end="`url(#arrow-${line.kind})`"
                />
                <text :x="line.labelX" :y="line.labelY" class="flow-line-label">{{ connectLabel(line.field) }}</text>
              </g>
            </svg>

                <div
                  v-for="(stage, idx) in form.stages"
                  :key="stage.uid"
                  class="stage-node"
              :class="{
                active: selectedStageIndex === idx,
                linking: connectMode?.sourceUid === stage.uid,
                'connect-target': connectMode && connectMode.sourceUid !== stage.uid,
                dragging: draggingStageUid === stage.uid,
                [stageToneClass(stage)]: true
                  }"
                  :style="{ left: `${stage.x}px`, top: `${stage.y}px`, width: `${stage.width}px`, minHeight: `${stage.height}px` }"
                  @dragstart.prevent
                  @click.stop="handleStageClick(idx)"
                >
              <div class="stage-node-head">
                <div class="stage-node-handle" @mousedown.stop.prevent="startDrag($event, idx)">拖动</div>
                <div>
                  <div class="stage-node-title">{{ stage.stageName || `未命名节点 ${idx + 1}` }}</div>
                  <div class="stage-node-key">{{ stage.stageKey || '未设置标识' }}</div>
                </div>
                <el-tag size="small" effect="plain">{{ stage.stageType }}</el-tag>
              </div>
              <div class="stage-node-body">
                <div class="stage-node-meta">{{ actorTypeLabel(stage.actorType) }}</div>
                <div v-if="stage.actorBindingMode === 'BY_SLOT' && stage.actorSlotKey" class="stage-node-meta stage-node-skill">
                  槽位 · {{ stage.actorSlotKey }}
                </div>
                <div v-if="stage.skillKey" class="stage-node-meta stage-node-skill">Skill · {{ stage.skillKey }}</div>
                <div v-else-if="stage.skillPrompt" class="stage-node-meta stage-node-skill">手动节点</div>
                <div class="stage-node-meta">搜索 · {{ searchStrategyLabel(stage.searchStrategy, stage.stageType) }}</div>
                <div v-if="stage.skillFileIds?.length" class="stage-node-meta">文件 · {{ stage.skillFileIds.length }} 个</div>
                <div v-if="needsReviewToggle(stage)" class="stage-node-meta">
                  {{ stage.reviewRequired === 0 ? '审核跳过' : '人工审核' }}
                </div>
              </div>
              <div class="stage-node-links">
                    <button class="link-chip pass" @mousedown.stop @click.stop="startConnect(stage, 'passTarget')">
                  通过
                  <span>{{ targetLabel(stage.passTargetUid) }}</span>
                </button>
                    <button class="link-chip reject" @mousedown.stop @click.stop="startConnect(stage, 'rejectTarget')">
                  打回
                  <span>{{ targetLabel(stage.rejectTargetUid) }}</span>
                </button>
                    <button class="link-chip reopen" @mousedown.stop @click.stop="startConnect(stage, 'reopenTarget')">
                  重开
                  <span>{{ targetLabel(stage.reopenTargetUid) }}</span>
                </button>
              </div>
              <div class="stage-node-resize" @mousedown.stop.prevent="startResize($event, idx)"></div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">{{ saveButtonLabel }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { meetingApi } from '@/api/meeting'
import { getWorkflowSkillMeta, workflowSkillCatalog } from '@/config/workflowSkills'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const canvasRef = ref()
const templates = ref([])
const skillFiles = ref([])
const editingTemplateId = ref(null)
const selectedStageIndex = ref(0)
const selectedLaneIndex = ref(0)
const selectedActorSlotIndex = ref(0)
const connectMode = ref(null)
const draggingStageUid = ref(null)
const resizingStageUid = ref(null)
const draggingLaneUid = ref(null)
const resizingLaneUid = ref(null)
const zoomLevel = ref(1)
const MIN_ZOOM = 0.2
const MAX_ZOOM = 1.8
const ZOOM_STEP = 0.1
let uidSeed = 1
let dragState = null
const savedDesignerSnapshot = ref('')

const commonSkillOptions = workflowSkillCatalog.map(skill => ({
  value: skill.key,
  label: skill.name,
  description: skill.description,
  stageTypes: skill.stageTypes
}))

const isDesignerPage = computed(() => route.query.designer === '1')
const canvasWidth = computed(() => isDesignerPage.value ? 1800 : 1100)
const canvasHeight = computed(() => isDesignerPage.value ? 1120 : 720)
const selectedLane = computed(() => form.value.lanes?.[selectedLaneIndex.value] || null)
const sceneFrameStyle = computed(() => ({
  width: `${canvasWidth.value * zoomLevel.value}px`,
  height: `${canvasHeight.value * zoomLevel.value}px`
}))
const sceneStyle = computed(() => ({
  width: `${canvasWidth.value}px`,
  height: `${canvasHeight.value}px`
}))
const boardStyle = computed(() => ({
  transform: `scale(${zoomLevel.value})`,
  transformOrigin: 'top left'
}))
const designerMode = computed(() => route.query.mode || (editingTemplateId.value ? 'edit' : 'new'))
const designerTitle = computed(() => {
  if (designerMode.value === 'edit') return '编辑流程模板'
  if (designerMode.value === 'clone') return '复制流程模板'
  return '新建流程模板'
})
const designerSubtitle = computed(() => {
  if (designerMode.value === 'edit') return '已加载现有模板布局和节点配置，你可以直接在画布上修改。'
  if (designerMode.value === 'clone') return '基于现有模板快速复制，适合在旧流程基础上派生新版本。'
  return '更大画布，更适合多节点流程编排。'
})
const saveButtonLabel = computed(() => (designerMode.value === 'edit' ? '保存修改' : '保存模板'))
const designSnapshot = computed(() => JSON.stringify(buildTemplatePayload()))
const hasUnsavedChanges = computed(() => isDesignerPage.value && designSnapshot.value !== savedDesignerSnapshot.value)
const flowPreview = computed(() => buildFlowPreview())

const boardLanes = computed(() => form.value.lanes || [])
const selectedActorSlot = computed(() => form.value.actorSlots?.[selectedActorSlotIndex.value] || null)
const skillFileOptions = computed(() => (skillFiles.value || []).map(file => ({
  value: file.id,
  label: `${file.fileName} (${formatFileSize(file.fileSize)})`
})))
const actorSlotOptions = computed(() => (form.value.actorSlots || []).map(slot => ({
  value: slot.slotKey,
  label: `${slot.slotName || slot.slotKey || '未命名槽位'} (${slot.slotKey || '未设置标识'})`
})))
const selectedActorSlotStageLabels = computed(() => {
  const slotKey = selectedActorSlot.value?.slotKey
  if (!slotKey) return []
  return (form.value.stages || [])
    .filter(stage => stage.actorBindingMode === 'BY_SLOT' && stage.actorSlotKey === slotKey)
    .map(stage => stage.stageName || stage.stageKey || '未命名节点')
})

const defaultLanes = () => ([
  newLane({ label: '分析与讨论', tone: 'analysis', x: 24, width: 540 }),
  newLane({ label: '复核与审批', tone: 'review', x: 600, width: 540 }),
  newLane({ label: '跟踪与收口', tone: 'tracking', x: 1176, width: 540 })
])

function newLane(overrides = {}) {
  return {
    uid: `lane-${uidSeed++}`,
    label: '新分组',
    tone: 'generic',
    x: 40,
    y: 20,
    width: 360,
    height: 640,
    ...overrides
  }
}

const newStage = (overrides = {}) => ({
  uid: `stage-${uidSeed++}`,
  stageKey: '',
  stageName: '',
  stageType: 'ANALYSIS',
  actorType: 'ANALYST',
  actorBindingMode: 'ALL_MEMBERS',
  actorSlotKey: '',
  completionRule: '',
  skillKey: '',
  skillPrompt: '',
  skillFileIds: [],
  searchStrategy: 'OPTIONAL',
  passTarget: '',
  rejectTarget: '',
  reopenTarget: '',
  passTargetUid: '',
  rejectTargetUid: '',
  reopenTargetUid: '',
  laneUid: '',
  reviewRequired: 0,
  maxReviewAttempts: 0,
  x: 80,
  y: 80,
  width: 260,
  height: 170,
  ...overrides
})

const newActorSlot = (overrides = {}) => ({
  uid: `slot-${uidSeed++}`,
  slotKey: '',
  slotName: '',
  description: '',
  defaultModelType: '',
  defaultPersona: '',
  required: 1,
  allowMultiple: 0,
  ...overrides
})

const emptyForm = () => {
  const lanes = defaultLanes()
  return {
    name: '',
    templateKey: '',
    description: '',
    actorSlots: [],
    lanes,
    stages: [
      newStage({ stageKey: 'analysis', stageName: '分析节点', laneUid: lanes[0]?.uid || '', x: 80, y: 120 })
    ]
  }
}

const form = ref(emptyForm())

const selectedStage = computed(() => form.value.stages[selectedStageIndex.value] || null)

const flowLines = computed(() => {
  const lines = []
  const stageMap = new Map(form.value.stages.map(stage => [stage.uid, stage]))
  for (const stage of form.value.stages) {
    for (const pair of [
      { field: 'passTarget', uidField: 'passTargetUid' },
      { field: 'rejectTarget', uidField: 'rejectTargetUid' },
      { field: 'reopenTarget', uidField: 'reopenTargetUid' }
    ]) {
      if (!stage[pair.uidField]) continue
      const target = stageMap.get(stage[pair.uidField])
      if (!target) continue
      const startX = stage.x + stage.width
      const startY = stage.y + stage.height / 2
      const endX = target.x
      const endY = target.y + target.height / 2
      const midX = (startX + endX) / 2
      lines.push({
        id: `${stage.uid}-${pair.field}-${target.uid}`,
        field: pair.field,
        kind: fieldKind(pair.field),
        path: `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${endY}, ${endX} ${endY}`,
        labelX: midX,
        labelY: (startY + endY) / 2 - 8
      })
    }
  }
  return lines
})

function fieldKind(field) {
  return field === 'rejectTarget' ? 'reject' : field === 'reopenTarget' ? 'reopen' : 'pass'
}

function actorTypeLabel(type) {
  return ({
    ALL_MEMBERS: '全体议员',
    ANALYST: '分析员',
    REVIEWER: '复核员',
    APPROVER: '审批人',
    HUMAN: '人工',
    SYSTEM: '系统'
  })[type] || type
}

function defaultSearchStrategy(stageType) {
  return ['ANALYSIS', 'DEBATE', 'TRACKING'].includes(stageType) ? 'OPTIONAL' : 'OFF'
}

function normalizeSearchStrategy(strategy, stageType) {
  const upper = String(strategy || '').trim().toUpperCase()
  if (['OFF', 'OPTIONAL', 'REQUIRED'].includes(upper)) return upper
  return defaultSearchStrategy(stageType)
}

function searchStrategyLabel(strategy, stageType) {
  return ({
    OFF: '关闭搜索',
    OPTIONAL: '按需搜索',
    REQUIRED: '强制搜索'
  })[normalizeSearchStrategy(strategy, stageType)] || '按需搜索'
}

function searchStrategyHint(strategy, stageType) {
  const normalized = normalizeSearchStrategy(strategy, stageType)
  if (normalized === 'REQUIRED') {
    return '当前节点每次执行前都会先走外部搜索，搜索失败会在节点时间线里留下诊断。'
  }
  if (normalized === 'OFF') {
    return '当前节点不会主动联网，只消费模板指令、Skill 文件和上游交付物。'
  }
  return '当前节点会优先结合任务内容决定是否联网搜索；适合大多数分析与跟踪节点。'
}

function laneToneLabel(tone) {
  return ({
    analysis: '蓝色分组',
    review: '紫色分组',
    tracking: '橙色分组',
    generic: '灰色分组'
  })[tone] || tone
}

function actorBindingModeLabel(mode) {
  return ({
    ALL_MEMBERS: '全体成员',
    BY_SLOT: '指定槽位',
    HUMAN: '人工节点',
    SYSTEM: '系统节点'
  })[mode] || mode
}

function skillMeta(skillKey) {
  return getWorkflowSkillMeta(skillKey)
}

function applySkillPreset(stage) {
  if (!stage?.skillKey || stage.skillPrompt?.trim()) return
  const meta = skillMeta(stage.skillKey)
  if (meta?.defaultPrompt) {
    stage.skillPrompt = meta.defaultPrompt
  }
}

function fillSkillDefaultPrompt(stage) {
  if (!stage?.skillKey) return
  const meta = skillMeta(stage.skillKey)
  if (meta?.defaultPrompt) {
    stage.skillPrompt = meta.defaultPrompt
  }
}

function selectedSkillFiles(stage) {
  const ids = stage?.skillFileIds || []
  const fileMap = new Map((skillFiles.value || []).map(file => [file.id, file]))
  return ids.map(id => fileMap.get(id)).filter(Boolean)
}

function formatFileSize(size) {
  if (size == null || Number.isNaN(Number(size))) return '--'
  const bytes = Number(size)
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function downloadSkillFile(fileId) {
  if (!fileId) return
  window.open(`/api/skill-files/${fileId}/download`, '_blank')
}

async function handleSkillFileUpload(option) {
  const formData = new FormData()
  formData.append('file', option.file)
  try {
    const res = await meetingApi.uploadSkillFile(formData)
    const uploaded = res.data
    if (uploaded) {
      skillFiles.value = [uploaded, ...skillFiles.value.filter(item => item.id !== uploaded.id)]
      if (selectedStage.value && !selectedStage.value.skillFileIds.includes(uploaded.id)) {
        selectedStage.value.skillFileIds = [...selectedStage.value.skillFileIds, uploaded.id]
      }
    }
    option.onSuccess?.(res.data)
    ElMessage.success(`已上传 ${option.file.name}`)
  } catch (error) {
    option.onError?.(error)
    ElMessage.error(error?.message || '上传 skill 文件失败')
  }
}

async function deleteSkillFile(fileId) {
  try {
    await ElMessageBox.confirm('确认删除这个 skill 文件？已绑定该文件的节点也会失去这个引用。', '删除文件', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await meetingApi.deleteSkillFile(fileId)
  skillFiles.value = skillFiles.value.filter(item => item.id !== fileId)
  form.value.stages.forEach(stage => {
    stage.skillFileIds = (stage.skillFileIds || []).filter(id => id !== fileId)
  })
  ElMessage.success('文件已删除')
}

function skillOptionsForStage(stage) {
  const stageType = stage?.stageType
  if (!stageType) return commonSkillOptions
  const matched = commonSkillOptions.filter(opt => !opt.stageTypes?.length || opt.stageTypes.includes(stageType))
  return matched.length ? matched : commonSkillOptions
}

function selectActorSlot(idx) {
  selectedActorSlotIndex.value = idx
}

function addActorSlot() {
  form.value.actorSlots.push(newActorSlot({
    slotKey: `slot_${form.value.actorSlots.length + 1}`,
    slotName: `执行槽位 ${form.value.actorSlots.length + 1}`
  }))
  selectedActorSlotIndex.value = form.value.actorSlots.length - 1
}

function removeSelectedActorSlot() {
  const slot = selectedActorSlot.value
  if (!slot) return
  const referencedStages = form.value.stages.filter(stage => stage.actorBindingMode === 'BY_SLOT' && stage.actorSlotKey === slot.slotKey)
  if (referencedStages.length) {
    ElMessage.warning(`请先解除 ${referencedStages.map(stage => stage.stageName || stage.stageKey || '未命名节点').join('、')} 对该槽位的引用`)
    return
  }
  form.value.actorSlots.splice(selectedActorSlotIndex.value, 1)
  selectedActorSlotIndex.value = Math.max(0, selectedActorSlotIndex.value - 1)
}

function laneZIndex(idx, lane) {
  if (draggingLaneUid.value === lane.uid || resizingLaneUid.value === lane.uid) return 80
  if (selectedLaneIndex.value === idx) return 70
  return 10 + idx
}

function connectLabel(field) {
  return ({
    passTarget: '通过',
    rejectTarget: '打回',
    reopenTarget: '重开'
  })[field] || field
}

function stageToneClass(stage) {
  const type = stage?.stageType
  if (['DEBATE', 'ANALYSIS'].includes(type)) return 'tone-analysis'
  if (['REVIEW', 'APPROVAL'].includes(type)) return 'tone-review'
  if (type === 'TRACKING') return 'tone-tracking'
  return 'tone-generic'
}

function preferredLaneTone(stage) {
  const type = stage?.stageType
  if (['DEBATE', 'ANALYSIS'].includes(type)) return 'analysis'
  if (['REVIEW', 'APPROVAL'].includes(type)) return 'review'
  if (type === 'TRACKING') return 'tracking'
  return null
}

function stageCenter(stage) {
  return {
    x: stage.x + stage.width / 2,
    y: stage.y + stage.height / 2
  }
}

function isPointInLane(point, lane) {
  return point.x >= lane.x
    && point.x <= lane.x + lane.width
    && point.y >= lane.y
    && point.y <= lane.y + lane.height
}

function resolveLaneForStage(stage, options = {}) {
  const { preferAssigned = false } = options
  const lanes = boardLanes.value || []
  if (!lanes.length) return null
  const current = lanes.find(lane => lane.uid === stage.laneUid)
  if (preferAssigned && current) return current
  const point = stageCenter(stage)
  const containing = lanes.find(lane => isPointInLane(point, lane))
  if (containing) return containing
  const tone = preferredLaneTone(stage)
  if (tone) {
    const toneMatch = lanes.find(lane => lane.tone === tone)
    if (toneMatch) return toneMatch
  }
  if (current) return current
  return lanes.slice().sort((a, b) => Math.abs((a.x + a.width / 2) - point.x) - Math.abs((b.x + b.width / 2) - point.x))[0]
}

function needsReviewToggle(stage) {
  return ['REVIEW', 'APPROVAL'].includes(stage?.stageType)
}

function targetLabel(stageKey) {
  if (!stageKey) return '未连接'
  const target = form.value.stages.find(item => item.uid === stageKey)
  return target?.stageName || target?.stageKey || '未命名节点'
}

function targetOptions(excludeUid) {
  return form.value.stages
    .filter(stage => stage.uid !== excludeUid)
    .map(stage => ({ value: stage.uid, label: `${stage.stageName || stage.stageKey || '未命名节点'} (${stage.stageKey || stage.uid})` }))
}

async function load() {
  loading.value = true
  try {
    const [templateRes, skillFileRes] = await Promise.all([
      meetingApi.listWorkflowTemplateDetails(),
      meetingApi.listSkillFiles()
    ])
    templates.value = templateRes.data || []
    skillFiles.value = skillFileRes.data || []
    const templateId = Number(route.query.templateId)
    if (templateId && isDesignerPage.value) {
      const tpl = templates.value.find(item => item.id === templateId)
      if (tpl && editingTemplateId.value !== templateId) {
        hydrateFormFromTemplate(tpl)
      }
    }
  } finally {
    loading.value = false
  }
}

function resetDesignerForm() {
  form.value = emptyForm()
  editingTemplateId.value = null
  selectedStageIndex.value = 0
  selectedLaneIndex.value = 0
  selectedActorSlotIndex.value = 0
  connectMode.value = null
  zoomLevel.value = 1
  savedDesignerSnapshot.value = JSON.stringify(buildTemplatePayload())
}

async function openCreate() {
  if (!(await confirmDiscardIfNeeded())) return
  resetDesignerForm()
  if (isDesignerPage.value) return
  router.push({ name: 'WorkflowTemplates', query: { designer: '1', mode: 'new' } })
}

async function openEdit(tpl) {
  if (!(await confirmDiscardIfNeeded())) return
  hydrateFormFromTemplate(tpl)
  if (isDesignerPage.value) return
  router.push({ name: 'WorkflowTemplates', query: { designer: '1', mode: 'edit', templateId: String(tpl.id) } })
}

async function openClone(tpl) {
  if (!(await confirmDiscardIfNeeded())) return
  hydrateFormFromTemplate(tpl)
  editingTemplateId.value = null
  form.value.name = `${tpl.name || '流程模板'} 副本`
  form.value.templateKey = `${tpl.templateKey || 'workflow'}-copy`
  savedDesignerSnapshot.value = JSON.stringify(buildTemplatePayload())
  if (isDesignerPage.value) return
  router.push({ name: 'WorkflowTemplates', query: { designer: '1', mode: 'clone' } })
}

function hydrateFormFromTemplate(tpl) {
  const layout = parseLayoutConfig(tpl.layoutConfig)
  const lanes = Array.isArray(layout?.lanes) && layout.lanes.length
    ? layout.lanes.map((lane, idx) => newLane({
        label: lane.label || `分组 ${idx + 1}`,
        tone: lane.tone || 'generic',
        x: Number.isFinite(lane.x) ? lane.x : 40 + idx * 320,
        y: Number.isFinite(lane.y) ? lane.y : 20,
        width: Number.isFinite(lane.width) ? lane.width : 320,
        height: Number.isFinite(lane.height) ? lane.height : 640
      }))
    : defaultLanes()
  const laneUidMap = new Map((layout?.lanes || []).map((lane, idx) => [lane.uid || lane.key || `lane-${idx}`, lanes[idx]?.uid]))
  const stageLayouts = new Map((layout?.stageLayouts || []).map(item => [item.stageKey, item]))
  const stages = (tpl.stages || []).map((stage, idx) => {
    const stageLayout = stageLayouts.get(stage.stageKey) || {}
    return newStage({
      stageKey: stage.stageKey,
      stageName: stage.stageName,
      stageType: stage.stageType,
      actorType: stage.actorType,
      actorBindingMode: stage.actorBindingMode || 'ALL_MEMBERS',
      actorSlotKey: stage.actorSlotKey || '',
      completionRule: stage.completionRule || '',
      skillKey: stage.skillKey || '',
      skillPrompt: stage.skillPrompt || '',
      skillFileIds: Array.isArray(stage.skillFileIds) ? [...stage.skillFileIds] : [],
      searchStrategy: normalizeSearchStrategy(stage.searchStrategy, stage.stageType),
      passTarget: stage.passTarget || '',
      rejectTarget: stage.rejectTarget || '',
      reopenTarget: stage.reopenTarget || '',
      reviewRequired: Number(stage.reviewRequired ?? 0),
      maxReviewAttempts: Number(stage.maxReviewAttempts ?? 0),
      x: Number.isFinite(stageLayout.x) ? stageLayout.x : 80 + (idx % 3) * 280,
      y: Number.isFinite(stageLayout.y) ? stageLayout.y : 100 + Math.floor(idx / 3) * 210,
      width: Number.isFinite(stageLayout.width) ? stageLayout.width : 260,
      height: Number.isFinite(stageLayout.height) ? stageLayout.height : 170,
      laneUid: laneUidMap.get(stageLayout.laneUid) || lanes[0]?.uid || ''
    })
  })
  const stageUidByKey = new Map(stages.map(stage => [stage.stageKey, stage.uid]))
  stages.forEach(stage => {
    const source = (tpl.stages || []).find(item => item.stageKey === stage.stageKey)
    stage.passTargetUid = source?.passTarget ? (stageUidByKey.get(source.passTarget) || '') : ''
    stage.rejectTargetUid = source?.rejectTarget ? (stageUidByKey.get(source.rejectTarget) || '') : ''
    stage.reopenTargetUid = source?.reopenTarget ? (stageUidByKey.get(source.reopenTarget) || '') : ''
  })

  form.value = {
    name: tpl.name || '',
    templateKey: tpl.templateKey || '',
    description: tpl.description || '',
    actorSlots: (tpl.actorSlots || []).map(slot => newActorSlot({
      slotKey: slot.slotKey || '',
      slotName: slot.slotName || '',
      description: slot.description || '',
      defaultModelType: slot.defaultModelType || '',
      defaultPersona: slot.defaultPersona || '',
      required: Number(slot.required ?? 1),
      allowMultiple: Number(slot.allowMultiple ?? 0)
    })),
    lanes,
    stages: stages.length ? stages : [newStage({ laneUid: lanes[0]?.uid || '' })]
  }
  editingTemplateId.value = tpl.id
  selectedStageIndex.value = 0
  selectedLaneIndex.value = 0
  selectedActorSlotIndex.value = 0
  connectMode.value = null
  zoomLevel.value = 1
  savedDesignerSnapshot.value = JSON.stringify(buildTemplatePayload())
}

function parseLayoutConfig(raw) {
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

function openDesignerPage() {
  resetDesignerForm()
  router.push({ name: 'WorkflowTemplates', query: { designer: '1' } })
}

async function exitDesignerPage() {
  if (!(await confirmDiscardIfNeeded())) return
  router.push({ name: 'WorkflowTemplates' })
}

function addStage() {
  const index = form.value.stages.length
  const lane = selectedLane.value || boardLanes.value[0]
  form.value.stages.push(newStage({
    stageKey: `stage-${index + 1}`,
    stageName: `节点 ${index + 1}`,
    laneUid: lane?.uid || '',
    x: lane ? lane.x + 36 : 80 + (index % 3) * 290,
    y: lane ? lane.y + 56 : 100 + Math.floor(index / 3) * 190
  }))
  selectedStageIndex.value = form.value.stages.length - 1
}

function duplicateStage(uid) {
  const source = form.value.stages.find(stage => stage.uid === uid)
  if (!source) return
  form.value.stages.push(newStage({
    stageKey: `${source.stageKey || 'stage'}-copy`,
    stageName: `${source.stageName || '节点'} 副本`,
    stageType: source.stageType,
    actorType: source.actorType,
    actorBindingMode: source.actorBindingMode,
    actorSlotKey: source.actorSlotKey,
    completionRule: source.completionRule,
    skillKey: source.skillKey,
    skillPrompt: source.skillPrompt,
    skillFileIds: Array.isArray(source.skillFileIds) ? [...source.skillFileIds] : [],
    searchStrategy: normalizeSearchStrategy(source.searchStrategy, source.stageType),
    laneUid: source.laneUid,
    reviewRequired: source.reviewRequired,
    maxReviewAttempts: source.maxReviewAttempts,
    x: Math.min(source.x + 40, canvasWidth.value - source.width - 20),
    y: Math.min(source.y + 40, canvasHeight.value - source.height - 20),
    width: source.width,
    height: source.height
  }))
  selectedStageIndex.value = form.value.stages.length - 1
}

function removeSelectedStage() {
  if (form.value.stages.length <= 1) return
  const current = selectedStage.value
  if (!current) return
  const removedUid = current.uid
  form.value.stages.splice(selectedStageIndex.value, 1)
  for (const stage of form.value.stages) {
    if (stage.passTargetUid === removedUid) stage.passTargetUid = ''
    if (stage.rejectTargetUid === removedUid) stage.rejectTargetUid = ''
    if (stage.reopenTargetUid === removedUid) stage.reopenTargetUid = ''
  }
  selectedStageIndex.value = Math.max(0, selectedStageIndex.value - 1)
  clearConnectMode()
}

function addLane() {
  const idx = boardLanes.value.length
  const lastLane = boardLanes.value.at(-1)
  form.value.lanes.push(newLane({
    label: `分组 ${idx + 1}`,
    tone: idx % 3 === 0 ? 'analysis' : idx % 3 === 1 ? 'review' : 'tracking',
    x: lastLane ? Math.min(lastLane.x + lastLane.width + 36, canvasWidth.value - 280) : 40,
    y: lastLane?.y ?? 20,
    width: 320
  }))
  selectedLaneIndex.value = form.value.lanes.length - 1
}

function selectLane(idx) {
  selectedLaneIndex.value = idx
}

function removeSelectedLane() {
  if (form.value.lanes.length <= 1) return
  const lane = selectedLane.value
  if (!lane) return
  const fallbackLane = form.value.lanes.find(item => item.uid !== lane.uid)
  form.value.stages.forEach(stage => {
    if (stage.laneUid === lane.uid) {
      stage.laneUid = fallbackLane?.uid || ''
    }
  })
  form.value.lanes.splice(selectedLaneIndex.value, 1)
  selectedLaneIndex.value = Math.max(0, selectedLaneIndex.value - 1)
}

function selectStage(idx) {
  selectedStageIndex.value = idx
}

function handleStageClick(idx) {
  if (connectMode.value) {
    const target = form.value.stages[idx]
    if (!target || target.uid === connectMode.value.sourceUid) return
    const source = form.value.stages.find(stage => stage.uid === connectMode.value.sourceUid)
    if (source) {
      source[`${connectMode.value.field}Uid`] = target.uid
    }
    clearConnectMode()
    return
  }
  selectStage(idx)
}

function startConnect(stage, field) {
  selectedStageIndex.value = form.value.stages.findIndex(item => item.uid === stage.uid)
  connectMode.value = {
    sourceUid: stage.uid,
    sourceName: stage.stageName || stage.stageKey || '未命名节点',
    field
  }
}

function clearConnectMode() {
  connectMode.value = null
}

function handleCanvasClick() {
  clearConnectMode()
}

function isPrimaryMouse(event) {
  if (!event) return false
  return event.button == null || event.button === 0
}

function startLaneDrag(event, idx) {
  if (!isPrimaryMouse(event)) return
  const lane = boardLanes.value[idx]
  if (!lane) return
  selectLane(idx)
  draggingLaneUid.value = lane.uid
  dragState = {
    mode: 'lane-move',
    uid: lane.uid,
    startX: event.clientX,
    startY: event.clientY,
    originalX: lane.x,
    originalY: lane.y
  }
}

function startLaneResize(event, idx, axis = 'x') {
  if (!isPrimaryMouse(event)) return
  const lane = boardLanes.value[idx]
  if (!lane) return
  selectLane(idx)
  resizingLaneUid.value = lane.uid
  dragState = {
    mode: axis === 'y' ? 'lane-resize-y' : axis === 'xy' ? 'lane-resize-xy' : 'lane-resize-x',
    uid: lane.uid,
    startX: event.clientX,
    startY: event.clientY,
    originalWidth: lane.width,
    originalHeight: lane.height
  }
}

function startDrag(event, idx) {
  if (!isPrimaryMouse(event)) return
  const stage = form.value.stages[idx]
  if (!stage) return
  selectStage(idx)
  draggingStageUid.value = stage.uid
  dragState = {
    mode: 'move',
    uid: stage.uid,
    startX: event.clientX,
    startY: event.clientY,
    originalX: stage.x,
    originalY: stage.y
  }
}

function startResize(event, idx) {
  if (!isPrimaryMouse(event)) return
  const stage = form.value.stages[idx]
  if (!stage) return
  selectStage(idx)
  resizingStageUid.value = stage.uid
  dragState = {
    mode: 'resize',
    uid: stage.uid,
    startX: event.clientX,
    startY: event.clientY,
    originalWidth: stage.width,
    originalHeight: stage.height
  }
}

function onPointerMove(event) {
  if (!dragState) return
  if (dragState.mode === 'lane-move' || dragState.mode === 'lane-resize-x' || dragState.mode === 'lane-resize-y' || dragState.mode === 'lane-resize-xy') {
    const lane = form.value.lanes.find(item => item.uid === dragState.uid)
    if (!lane) return
    if (dragState.mode === 'lane-resize-x' || dragState.mode === 'lane-resize-xy') {
      lane.width = Math.max(240, Math.min(900, dragState.originalWidth + ((event.clientX - dragState.startX) / zoomLevel.value)))
      lane.x = Math.max(0, Math.min(canvasWidth.value - lane.width - 20, lane.x))
    }
    if (dragState.mode === 'lane-resize-y' || dragState.mode === 'lane-resize-xy') {
      lane.height = Math.max(180, Math.min(900, dragState.originalHeight + ((event.clientY - dragState.startY) / zoomLevel.value)))
      lane.y = Math.max(0, Math.min(canvasHeight.value - lane.height - 20, lane.y))
    }
    if (dragState.mode === 'lane-move') {
      lane.x = Math.max(0, Math.min(canvasWidth.value - lane.width - 20, dragState.originalX + ((event.clientX - dragState.startX) / zoomLevel.value)))
      lane.y = Math.max(0, Math.min(canvasHeight.value - lane.height - 20, dragState.originalY + ((event.clientY - dragState.startY) / zoomLevel.value)))
    }
    return
  }
  const stage = form.value.stages.find(item => item.uid === dragState.uid)
  if (!stage) return
  const stageLane = boardLanes.value.find(lane => lane.uid === stage.laneUid) || null
  if (dragState.mode === 'resize') {
    const maxWidth = stageLane ? Math.max(220, stageLane.width - 48) : 460
    const maxHeight = stageLane ? Math.max(150, stageLane.height - 72) : 340
    stage.width = Math.max(220, Math.min(maxWidth, dragState.originalWidth + ((event.clientX - dragState.startX) / zoomLevel.value)))
    stage.height = Math.max(150, Math.min(maxHeight, dragState.originalHeight + ((event.clientY - dragState.startY) / zoomLevel.value)))
    if (stageLane) {
      stage.x = Math.max(stageLane.x + 20, Math.min(stageLane.x + stageLane.width - stage.width - 20, stage.x))
      stage.y = Math.max(stageLane.y + 44, Math.min(stageLane.y + stageLane.height - stage.height - 20, stage.y))
    } else {
      stage.x = Math.max(20, Math.min(canvasWidth.value - stage.width - 20, stage.x))
      stage.y = Math.max(20, Math.min(canvasHeight.value - stage.height - 20, stage.y))
    }
    return
  }
  const nextX = dragState.originalX + ((event.clientX - dragState.startX) / zoomLevel.value)
  const nextY = dragState.originalY + ((event.clientY - dragState.startY) / zoomLevel.value)
  if (stageLane) {
    stage.x = Math.max(stageLane.x + 20, Math.min(stageLane.x + stageLane.width - stage.width - 20, nextX))
    stage.y = Math.max(stageLane.y + 44, Math.min(stageLane.y + stageLane.height - stage.height - 20, nextY))
  } else {
    stage.x = Math.max(20, Math.min(canvasWidth.value - stage.width - 20, nextX))
    stage.y = Math.max(20, Math.min(canvasHeight.value - stage.height - 20, nextY))
  }
}

function onPointerUp() {
  dragState = null
  draggingStageUid.value = null
  resizingStageUid.value = null
  draggingLaneUid.value = null
  resizingLaneUid.value = null
}

function autoLayout() {
  const groups = new Map()
  boardLanes.value.forEach(lane => groups.set(lane.uid, []))
  form.value.stages.forEach(stage => {
    const lane = resolveLaneForStage(stage, { preferAssigned: true })
    if (lane) {
      stage.laneUid = lane.uid
    }
    const key = lane?.uid || (stage.laneUid && groups.has(stage.laneUid) ? stage.laneUid : boardLanes.value[0]?.uid)
    if (key) groups.get(key).push(stage)
  })
  boardLanes.value.forEach(lane => {
    const stages = (groups.get(lane.uid) || []).slice().sort((a, b) => {
      const ao = Number(a.stageOrder ?? 999)
      const bo = Number(b.stageOrder ?? 999)
      if (ao !== bo) return ao - bo
      return String(a.stageName || a.stageKey).localeCompare(String(b.stageName || b.stageKey))
    })
    const laneInnerLeft = lane.x + 24
    const laneInnerTop = lane.y + 56
    const laneInnerWidth = Math.max(220, lane.width - 48)
    let cursorX = laneInnerLeft
    let cursorY = laneInnerTop
    let rowHeight = 0
    stages.forEach((stage) => {
      const stageWidth = Math.min(stage.width, laneInnerWidth)
      const stageHeight = stage.height
      if (cursorX > laneInnerLeft && cursorX + stageWidth > laneInnerLeft + laneInnerWidth) {
        cursorX = laneInnerLeft
        cursorY += rowHeight + 36
        rowHeight = 0
      }
      stage.width = stageWidth
      stage.x = Math.max(20, Math.min(canvasWidth.value - stage.width - 20, cursorX))
      stage.y = Math.max(20, Math.min(canvasHeight.value - stage.height - 20, cursorY))
      cursorX += stage.width + 24
      rowHeight = Math.max(rowHeight, stageHeight)
    })
    const requiredHeight = Math.max(180, (cursorY - laneInnerTop) + rowHeight + 72)
    lane.height = Math.max(lane.height, requiredHeight)
  })
}

function zoomIn() {
  zoomLevel.value = Math.min(MAX_ZOOM, Number((zoomLevel.value + ZOOM_STEP).toFixed(2)))
}

function zoomOut() {
  zoomLevel.value = Math.max(MIN_ZOOM, Number((zoomLevel.value - ZOOM_STEP).toFixed(2)))
}

function resetZoom() {
  zoomLevel.value = 1
}

function normalizeStageKey(stage, idx) {
  const raw = (stage.stageKey || `stage-${idx + 1}`).trim().toLowerCase()
  return raw.replace(/\s+/g, '-')
}

function stagePreviewLabel(stage) {
  return stage?.stageName?.trim() || stage?.stageKey || '未命名节点'
}

function buildFlowPreview() {
  const stages = (form.value.stages || []).filter(stage => stage.stageName?.trim())
  if (!stages.length) {
    return {
      stageCount: 0,
      reachableCount: 0,
      startLabel: '未设置',
      hasReviewStage: false,
      manualNodeCount: 0,
      issues: [{ code: 'empty', title: '暂无有效节点', detail: '至少保留一个已命名节点，模板才能形成流程。' }],
      routePreview: []
    }
  }

  const normalizedKeys = stages.map((stage, idx) => normalizeStageKey(stage, idx))
  const keyCount = new Map()
  normalizedKeys.forEach(key => keyCount.set(key, (keyCount.get(key) || 0) + 1))
  const duplicateKeys = normalizedKeys.filter((key, idx) => keyCount.get(key) > 1 && normalizedKeys.indexOf(key) === idx)
  const stageMap = new Map(stages.map((stage, idx) => [normalizedKeys[idx], { ...stage, normalizedKey: normalizedKeys[idx] }]))
  const startKey = normalizedKeys[0]
  const incoming = new Map(normalizedKeys.map(key => [key, 0]))
  const adjacency = new Map(normalizedKeys.map(key => [key, []]))
  const routePreview = []
  const issues = []

  stages.forEach((stage, idx) => {
    const currentKey = normalizedKeys[idx]
    const current = stageMap.get(currentKey)
    const edgeDefs = [
      { field: 'passTargetUid', label: '通过' },
      { field: 'rejectTargetUid', label: '打回' },
      { field: 'reopenTargetUid', label: '重开' }
    ]
    const linkLabels = {}
    edgeDefs.forEach(edge => {
      const targetUid = stage[edge.field]
      const targetStage = stages.find(item => item.uid === targetUid)
      const targetKey = targetStage ? normalizedKeys[stages.findIndex(item => item.uid === targetUid)] : ''
      if (targetKey) {
        adjacency.get(currentKey).push(targetKey)
        incoming.set(targetKey, (incoming.get(targetKey) || 0) + 1)
        linkLabels[edge.field] = stagePreviewLabel(stageMap.get(targetKey))
      } else if (edge.field === 'passTargetUid' && idx < stages.length - 1) {
        const fallbackKey = normalizedKeys[idx + 1]
        adjacency.get(currentKey).push(fallbackKey)
        incoming.set(fallbackKey, (incoming.get(fallbackKey) || 0) + 1)
        linkLabels[edge.field] = stagePreviewLabel(stageMap.get(fallbackKey))
      } else {
        linkLabels[edge.field] = '未连接'
      }
    })
    routePreview.push({
      stageKey: currentKey,
      stageName: stagePreviewLabel(current),
      stageType: current.stageType,
      passLabel: linkLabels.passTargetUid,
      rejectLabel: linkLabels.rejectTargetUid,
      reopenLabel: linkLabels.reopenTargetUid
    })
  })

  const visited = new Set()
  const queue = [startKey]
  while (queue.length) {
    const key = queue.shift()
    if (!key || visited.has(key)) continue
    visited.add(key)
    ;(adjacency.get(key) || []).forEach(next => {
      if (!visited.has(next)) queue.push(next)
    })
  }

  if (duplicateKeys.length) {
    issues.push({
      code: `duplicate-${duplicateKeys.join('-')}`,
      title: '存在重复标识',
      detail: `这些节点归一化后会重复：${duplicateKeys.join('、')}。保存前需要去重。`
    })
  }

  const orphanStages = normalizedKeys.filter(key => key !== startKey && (incoming.get(key) || 0) === 0)
  if (orphanStages.length) {
    issues.push({
      code: `orphan-${orphanStages.join('-')}`,
      title: '存在孤立节点',
      detail: orphanStages.map(key => stagePreviewLabel(stageMap.get(key))).join('、')
    })
  }

  const unreachableStages = normalizedKeys.filter(key => !visited.has(key))
  if (unreachableStages.length) {
    issues.push({
      code: `unreachable-${unreachableStages.join('-')}`,
      title: '存在不可达节点',
      detail: unreachableStages.map(key => stagePreviewLabel(stageMap.get(key))).join('、')
    })
  }

  const deadEndStages = normalizedKeys.filter((key, idx) => {
    const stage = stages[idx]
    const passCount = adjacency.get(key)?.length || 0
    return passCount === 0 && idx !== stages.length - 1 && !['TRACKING'].includes(stage.stageType)
  })
  if (deadEndStages.length) {
    issues.push({
      code: `dead-end-${deadEndStages.join('-')}`,
      title: '存在无出口节点',
      detail: deadEndStages.map(key => stagePreviewLabel(stageMap.get(key))).join('、')
    })
  }

  return {
    stageCount: stages.length,
    reachableCount: visited.size,
    startLabel: stagePreviewLabel(stageMap.get(startKey)),
    hasReviewStage: stages.some(stage => ['REVIEW', 'APPROVAL'].includes(stage.stageType)),
    manualNodeCount: stages.filter(stage => !stage.skillKey?.trim() && stage.skillPrompt?.trim()).length,
    issues,
    routePreview
  }
}

function buildTemplatePayload() {
  const validStages = (form.value.stages || []).filter(stage => stage.stageName?.trim())
  const stageKeys = validStages.map((stage, idx) => normalizeStageKey(stage, idx))
  const normalizedKeyByUid = new Map(validStages.map((stage, idx) => [stage.uid, stageKeys[idx]]))
  const normalizedStages = validStages.map((stage, idx) => {
    const stageKey = stageKeys[idx]
    const cleaned = {
      stageKey,
      stageName: stage.stageName?.trim() || '',
      stageType: stage.stageType,
      actorType: stage.actorType,
      actorBindingMode: stage.actorBindingMode || 'ALL_MEMBERS',
      actorSlotKey: stage.actorBindingMode === 'BY_SLOT' ? (stage.actorSlotKey?.trim().toLowerCase() || '') : '',
      completionRule: stage.completionRule?.trim() || '',
      skillKey: stage.skillKey?.trim() || '',
      skillPrompt: stage.skillPrompt?.trim() || '',
      skillFileIds: Array.isArray(stage.skillFileIds) ? stage.skillFileIds.filter(id => Number.isFinite(Number(id))).map(id => Number(id)) : [],
      searchStrategy: normalizeSearchStrategy(stage.searchStrategy, stage.stageType),
      passTarget: stage.passTargetUid ? (normalizedKeyByUid.get(stage.passTargetUid) || '') : '',
      rejectTarget: stage.rejectTargetUid ? (normalizedKeyByUid.get(stage.rejectTargetUid) || '') : '',
      reopenTarget: stage.reopenTargetUid ? (normalizedKeyByUid.get(stage.reopenTargetUid) || '') : (stageKeys[0] || ''),
      autoStart: idx === 0 ? 1 : 0,
      reviewRequired: needsReviewToggle(stage) ? Number(stage.reviewRequired ?? 1) : 0,
      maxReviewAttempts: needsReviewToggle(stage) ? Number(stage.maxReviewAttempts ?? 0) : 0
    }
    if (!cleaned.passTarget && idx < validStages.length - 1) {
      cleaned.passTarget = stageKeys[idx + 1]
    }
    return cleaned
  })
  return {
    name: form.value.name?.trim() || '',
    templateKey: form.value.templateKey?.trim().toLowerCase() || '',
    description: form.value.description?.trim() || '',
    actorSlots: (form.value.actorSlots || [])
      .filter(slot => slot.slotName?.trim())
      .map(slot => ({
        slotKey: (slot.slotKey?.trim() || slot.slotName?.trim() || 'slot').toLowerCase().replace(/\s+/g, '_'),
        slotName: slot.slotName?.trim() || '',
        description: slot.description?.trim() || '',
        defaultModelType: slot.defaultModelType?.trim() || '',
        defaultPersona: slot.defaultPersona?.trim() || '',
        required: Number(slot.required ?? 1),
        allowMultiple: Number(slot.allowMultiple ?? 0)
      })),
    layoutConfig: JSON.stringify({
      lanes: boardLanes.value.map(lane => ({
        uid: lane.uid,
        label: lane.label,
        tone: lane.tone,
        x: lane.x,
        y: lane.y,
        width: lane.width,
        height: lane.height
      })),
      stageLayouts: validStages.map((stage, idx) => ({
        stageKey: stageKeys[idx],
        laneUid: stage.laneUid,
        x: stage.x,
        y: stage.y,
        width: stage.width,
        height: stage.height
      }))
    }),
    stages: normalizedStages
  }
}

async function confirmDiscardIfNeeded() {
  if (!hasUnsavedChanges.value) return true
  try {
    await ElMessageBox.confirm('当前画布有未保存修改，确认放弃这些更改？', '未保存变更', {
      type: 'warning',
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑'
    })
    return true
  } catch {
    return false
  }
}

function handleBeforeUnload(event) {
  if (!hasUnsavedChanges.value) return
  event.preventDefault()
  event.returnValue = ''
}

async function handleSave() {
  await formRef.value.validate()
  const validStages = form.value.stages.filter(stage => stage.stageName?.trim())
  if (validStages.length === 0) {
    ElMessage.error('至少需要一个有效节点')
    return
  }

  const stageKeys = validStages.map((stage, idx) => normalizeStageKey(stage, idx))
  if (new Set(stageKeys).size !== stageKeys.length) {
    ElMessage.error('阶段标识不能重复')
    return
  }

  saving.value = true
  try {
    const payload = buildTemplatePayload()

    if (editingTemplateId.value) {
      await meetingApi.updateWorkflowTemplate(editingTemplateId.value, payload)
      ElMessage.success('流程模板已更新')
    } else {
      await meetingApi.createWorkflowTemplate(payload)
      ElMessage.success('流程模板已创建')
    }
    savedDesignerSnapshot.value = JSON.stringify(payload)
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function handleDelete(tpl) {
  await ElMessageBox.confirm(`确认删除流程模板「${tpl.name}」？`, '删除流程模板', { type: 'warning' })
  await meetingApi.deleteWorkflowTemplate(tpl.id)
  ElMessage.success('流程模板已删除')
  load()
}

onMounted(() => {
  load()
  window.addEventListener('mousemove', onPointerMove)
  window.addEventListener('mouseup', onPointerUp)
  document.addEventListener('mousemove', onPointerMove)
  document.addEventListener('mouseup', onPointerUp)
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onUnmounted(() => {
  window.removeEventListener('mousemove', onPointerMove)
  window.removeEventListener('mouseup', onPointerUp)
  document.removeEventListener('mousemove', onPointerMove)
  document.removeEventListener('mouseup', onPointerUp)
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<style scoped>
.workflow-templates { display: flex; flex-direction: column; gap: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; }
.page-header-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.page-header h2 { font-size: 20px; font-weight: 700; color: #1a1f2e; }
.subtitle { color: #909399; font-size: 13px; margin-top: 2px; }
.designer-page { display: flex; flex-direction: column; gap: 12px; }
.designer-page-topbar {
  display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap;
  padding: 14px 16px; border: 1px solid #e5e7eb; border-radius: 14px;
  background: linear-gradient(180deg, #ffffff, #f8fbff);
}
.designer-page-title { display: flex; flex-direction: column; gap: 4px; }
.designer-page-title strong { font-size: 16px; color: #0f172a; }
.designer-page-title span { font-size: 12px; color: #64748b; }
.designer-page-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }

.template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; }
.tpl-card { border-radius: 10px; }
.tpl-top { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 10px; }
.tpl-avatar {
  width: 42px; height: 42px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: #409eff; color: #fff; font-weight: 700; font-size: 16px;
}
.tpl-avatar.builtin { background: #67c23a; }
.tpl-meta { flex: 1; min-width: 0; }
.tpl-name { font-weight: 600; font-size: 15px; color: #1f2937; }
.tpl-key { font-size: 12px; color: #909399; margin-top: 4px; }
.tpl-desc { font-size: 12px; color: #606266; line-height: 1.6; margin-bottom: 10px; }
.stage-flow-preview { display: flex; flex-direction: column; gap: 8px; }
.preview-stage { border: 1px solid #ebeef5; border-radius: 8px; padding: 10px; background: #fafbfd; }
.preview-stage-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.preview-stage-meta, .preview-stage-links { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 6px; font-size: 12px; color: #909399; }
.stage-order { font-size: 11px; color: #909399; }
.stage-name { font-size: 13px; font-weight: 600; color: #303133; }

.designer-shell { display: grid; grid-template-columns: 340px 1fr; gap: 16px; min-height: 720px; }
.designer-sidebar {
  border: 1px solid #ebeef5; border-radius: 12px; background: linear-gradient(180deg, #fbfdff 0%, #f7faff 100%);
  padding: 14px; display: flex; flex-direction: column; gap: 14px; overflow: auto;
}
.sidebar-block { border-top: 1px dashed #dbe3ee; padding-top: 12px; }
.sidebar-block:first-of-type { border-top: 0; padding-top: 0; }
.sidebar-title { font-size: 13px; font-weight: 700; color: #1f2937; margin-bottom: 10px; }
.tool-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.designer-hint { margin-top: 10px; font-size: 12px; line-height: 1.7; color: #64748b; }
.connect-banner {
  margin-top: 10px; padding: 10px 12px; border-radius: 8px; background: #eff6ff; color: #1d4ed8;
  border: 1px solid #bfdbfe; font-size: 12px; line-height: 1.6;
}
.node-list { display: flex; flex-direction: column; gap: 8px; }
.node-list-item {
  border: 1px solid #e5e7eb; border-radius: 10px; background: #fff; text-align: left;
  padding: 10px 12px; cursor: pointer; transition: all .2s ease;
}
.node-list-item:hover, .node-list-item.active { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64, 158, 255, .12); }
.node-list-item span { display: block; font-size: 13px; font-weight: 600; color: #1f2937; }
.node-list-item small { display: block; margin-top: 4px; color: #64748b; }
.lane-list-item small { color: #475569; }
.node-actions { display: flex; gap: 8px; }
.skill-file-upload { margin-top: 10px; }
.skill-file-upload-inner {
  font-size: 12px;
  line-height: 1.7;
  color: #475569;
}
.skill-file-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}
.skill-file-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
}
.skill-file-item b {
  display: block;
  font-size: 12px;
  color: #1f2937;
}
.skill-file-item span {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: #64748b;
}
.skill-file-item-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.flow-preview-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.flow-preview-overview.compact {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.flow-preview-stat {
  padding: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.flow-preview-stat span {
  font-size: 11px;
  color: #64748b;
}
.flow-preview-stat strong {
  font-size: 13px;
  color: #0f172a;
  line-height: 1.4;
}
.flow-preview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.flow-issues {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.flow-issues.compact {
  gap: 6px;
}
.flow-issue-item {
  padding: 10px;
  border-radius: 10px;
  border: 1px solid #fde68a;
  background: #fffbeb;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.flow-issue-item strong {
  font-size: 12px;
  color: #92400e;
}
.flow-issue-item span {
  font-size: 12px;
  line-height: 1.6;
  color: #a16207;
}
.flow-route-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.flow-route-item {
  padding: 10px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  background: #fff;
}
.flow-route-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}
.flow-route-head strong {
  font-size: 13px;
  color: #0f172a;
}
.flow-route-head small {
  font-size: 11px;
  color: #64748b;
}
.flow-route-links {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #475569;
}

.designer-board-wrap {
  border: 1px solid #ebeef5; border-radius: 14px; overflow: hidden;
  background:
    radial-gradient(circle at top left, rgba(64, 158, 255, .08), transparent 28%),
    linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}
.designer-board-wrap.fullpage { min-height: 860px; }
.designer-board-toolbar {
  height: 48px; display: flex; align-items: center; justify-content: space-between;
  padding: 0 14px; border-bottom: 1px solid #eef2f7; background: rgba(255, 255, 255, .86);
}
.designer-board-viewport {
  height: calc(100vh - 240px);
  min-height: 720px;
  overflow: auto;
  background: linear-gradient(180deg, #fcfdff, #f6faff);
}
.designer-scene-frame {
  position: relative;
}
.designer-scene {
  position: relative;
}
.designer-board {
  position: relative; width: 100%; height: 672px; overflow: hidden;
  background-image:
    linear-gradient(rgba(148, 163, 184, .12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, .12) 1px, transparent 1px);
  background-size: 28px 28px;
}
.designer-board.fullpage { height: 100%; }
.board-lanes { position: absolute; inset: 0; pointer-events: none; }
.board-lane {
  position: absolute; border-radius: 24px;
  border: 1px dashed rgba(148, 163, 184, .28);
  transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease;
  pointer-events: none;
}
.board-lane.active { border-color: rgba(37, 99, 235, .5); box-shadow: inset 0 0 0 1px rgba(37, 99, 235, .18), 0 0 0 3px rgba(37, 99, 235, .08); }
.board-lane.dragging, .board-lane.resizing { transform: translateY(-1px); box-shadow: inset 0 0 0 1px rgba(37, 99, 235, .2), 0 10px 24px rgba(15, 23, 42, .08); }
.board-lane.lane-analysis { background: linear-gradient(180deg, rgba(59, 130, 246, .07), rgba(59, 130, 246, .02)); }
.board-lane.lane-review { background: linear-gradient(180deg, rgba(168, 85, 247, .07), rgba(168, 85, 247, .02)); }
.board-lane.lane-tracking { background: linear-gradient(180deg, rgba(245, 158, 11, .08), rgba(245, 158, 11, .02)); }
.board-lane.lane-generic { background: linear-gradient(180deg, rgba(100, 116, 139, .09), rgba(100, 116, 139, .02)); }
.board-lane-label {
  position: absolute; left: 16px; top: 14px; font-size: 12px; font-weight: 700;
  color: #475569; letter-spacing: .04em; cursor: grab; user-select: none;
  padding: 4px 10px; border-radius: 999px; background: rgba(255, 255, 255, .72);
  border: 1px solid rgba(255, 255, 255, .75); backdrop-filter: blur(4px);
  z-index: 3; touch-action: none; pointer-events: auto;
}
.board-lane-resize-x {
  position: absolute; top: 50%; right: 10px; transform: translateY(-50%);
  width: 14px; height: 74px; border-radius: 999px; cursor: ew-resize;
  background: linear-gradient(180deg, rgba(148, 163, 184, .18), rgba(148, 163, 184, .35));
  border: 1px solid rgba(100, 116, 139, .18);
  z-index: 3; touch-action: none; pointer-events: auto;
}
.board-lane-resize-x::before {
  content: ''; position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  width: 4px; height: 34px; border-radius: 999px; background: rgba(71, 85, 105, .35);
}
.board-lane-resize-y {
  position: absolute; left: 50%; bottom: 10px; transform: translateX(-50%);
  width: 74px; height: 14px; border-radius: 999px; cursor: ns-resize;
  background: linear-gradient(90deg, rgba(148, 163, 184, .18), rgba(148, 163, 184, .35));
  border: 1px solid rgba(100, 116, 139, .18);
  z-index: 3; touch-action: none; pointer-events: auto;
}
.board-lane-resize-y::before {
  content: ''; position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  width: 34px; height: 4px; border-radius: 999px; background: rgba(71, 85, 105, .35);
}
.board-lane-resize-corner {
  position: absolute; right: 10px; bottom: 10px; width: 18px; height: 18px; cursor: nwse-resize;
  border-right: 3px solid rgba(71, 85, 105, .4); border-bottom: 3px solid rgba(71, 85, 105, .4);
  border-bottom-right-radius: 5px;
  z-index: 3; touch-action: none; pointer-events: auto;
}
.designer-lines { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.flow-line { fill: none; stroke-width: 2.5; }
.flow-line-pass { stroke: #409eff; }
.flow-line-reject { stroke: #f56c6c; stroke-dasharray: 6 4; }
.flow-line-reopen { stroke: #e6a23c; stroke-dasharray: 3 3; }
.flow-line-label { font-size: 12px; fill: #334155; font-weight: 700; }

.stage-node {
  position: absolute; width: 240px; min-height: 150px; border-radius: 14px;
  border: 1px solid #dbe3ee; background: rgba(255, 255, 255, .98);
  box-shadow: 0 12px 24px rgba(15, 23, 42, .08); cursor: pointer;
  transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease;
  backdrop-filter: blur(6px);
  touch-action: none;
  user-select: none;
  -webkit-user-drag: none;
  z-index: 120;
}
.stage-node:hover { transform: translateY(-1px); }
.stage-node.active { border-color: #409eff; box-shadow: 0 0 0 3px rgba(64, 158, 255, .14), 0 18px 30px rgba(15, 23, 42, .12); z-index: 130; }
.stage-node.linking { border-color: #1d4ed8; box-shadow: 0 0 0 3px rgba(29, 78, 216, .14); }
.stage-node.connect-target { border-style: dashed; }
.stage-node.dragging { transform: scale(1.02); box-shadow: 0 18px 34px rgba(15, 23, 42, .18); z-index: 140; }
.stage-node.dragging .stage-node-head,
.stage-node.dragging .stage-node-handle { cursor: grabbing; }
.stage-node.tone-analysis { border-top: 4px solid #3b82f6; }
.stage-node.tone-review { border-top: 4px solid #a855f7; }
.stage-node.tone-tracking { border-top: 4px solid #f59e0b; }
.stage-node.tone-generic { border-top: 4px solid #64748b; }
.stage-node-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 8px;
  padding: 12px 12px 8px; cursor: grab; user-select: none;
  background: linear-gradient(180deg, rgba(248, 250, 252, .92), rgba(248, 250, 252, .55));
  border-radius: 14px 14px 0 0; touch-action: none; -webkit-user-drag: none;
  position: relative; z-index: 2; pointer-events: auto;
}
.stage-node-handle {
  flex-shrink: 0;
  padding: 4px 8px;
  border-radius: 999px;
  border: 1px solid rgba(148, 163, 184, .4);
  background: rgba(255, 255, 255, .88);
  color: #475569;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  cursor: grab;
  user-select: none;
  touch-action: none;
  pointer-events: auto;
  box-shadow: 0 4px 10px rgba(15, 23, 42, .06);
}
.stage-node-handle:hover { border-color: rgba(59, 130, 246, .45); color: #1d4ed8; }
.stage-node-title { font-size: 14px; font-weight: 700; color: #1f2937; line-height: 1.4; }
.stage-node-key { margin-top: 4px; font-size: 11px; color: #64748b; }
.stage-node-body { padding: 0 12px 10px; display: flex; flex-direction: column; gap: 6px; cursor: default; user-select: none; touch-action: none; -webkit-user-drag: none; position: relative; z-index: 1; pointer-events: auto; }
.stage-node-meta { font-size: 12px; color: #475569; }
.stage-node-skill { color: #2563eb; font-weight: 600; }
.stage-node-links {
  padding: 10px 12px 12px; border-top: 1px dashed #e2e8f0;
  display: flex; flex-direction: column; gap: 8px;
  position: relative; z-index: 3;
}
.link-chip {
  border: 1px solid #dbe3ee; border-radius: 10px; background: #f8fafc;
  padding: 8px 10px; display: flex; align-items: center; justify-content: space-between;
  gap: 10px; font-size: 12px; cursor: pointer; transition: all .18s ease;
}
.link-chip span { color: #64748b; }
.link-chip:hover { transform: translateX(2px); box-shadow: 0 6px 12px rgba(15, 23, 42, .06); }
.link-chip.pass { color: #2563eb; }
.link-chip.reject { color: #dc2626; }
.link-chip.reopen { color: #d97706; }
.stage-node-resize {
  position: absolute; right: 10px; bottom: 10px; width: 16px; height: 16px; cursor: nwse-resize;
  border-right: 2px solid rgba(100, 116, 139, .55); border-bottom: 2px solid rgba(100, 116, 139, .55);
  border-bottom-right-radius: 4px; touch-action: none; z-index: 4;
}
.stage-node-resize::before,
.stage-node-resize::after {
  content: ''; position: absolute; right: 3px; bottom: 3px; border-right: 2px solid rgba(100, 116, 139, .35); border-bottom: 2px solid rgba(100, 116, 139, .35);
}
.stage-node-resize::before { width: 10px; height: 10px; }
.stage-node-resize::after { width: 5px; height: 5px; }

@media (max-width: 1080px) {
  .designer-shell { grid-template-columns: 1fr; }
  .designer-board { height: 560px; }
  .designer-board-viewport { height: 70vh; min-height: 560px; }
}
</style>
