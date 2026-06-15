export const workflowSkillCatalog = [
  {
    key: 'debate.analyze',
    name: '分析论证',
    description: '适合分析节点，输出结构化判断、依据和风险。',
    stageTypes: ['DEBATE', 'ANALYSIS'],
    defaultPrompt: '请围绕当前议题输出结论摘要、关键依据、主要风险和建议下一步。'
  },
  {
    key: 'debate.challenge',
    name: '交叉质疑',
    description: '适合对前序结论进行反驳、补证和交叉核验。',
    stageTypes: ['DEBATE', 'ANALYSIS'],
    defaultPrompt: '请重点指出前序方案的漏洞、遗漏假设和需要补强的证据。'
  },
  {
    key: 'review.check',
    name: '复核检查',
    description: '适合复核节点，检查输出是否完整、可靠、可流转。',
    stageTypes: ['REVIEW'],
    defaultPrompt: '请逐项检查结论、证据、风险和遗漏点，并给出通过或打回建议。'
  },
  {
    key: 'approval.final',
    name: '终审决策',
    description: '适合审批节点，决定通过、驳回或要求返工。',
    stageTypes: ['APPROVAL'],
    defaultPrompt: '请从整体质量、风险可控性和可执行性角度做最终审批判断。'
  },
  {
    key: 'tracking.followup',
    name: '追踪推进',
    description: '适合追踪节点，汇总补证进展和是否满足重开条件。',
    stageTypes: ['TRACKING'],
    defaultPrompt: '请说明进展结论、新增证据、是否满足继续讨论条件，以及下一步责任人。'
  },
  {
    key: 'evidence.verify',
    name: '证据核验',
    description: '适合证据核验节点，校验引用材料的可信度和缺口。',
    stageTypes: ['ANALYSIS', 'REVIEW'],
    defaultPrompt: '请核验关键信息来源，指出已确认事实、存疑点和缺失证据。'
  },
  {
    key: 'summary.conclude',
    name: '结论归档',
    description: '适合总结节点，沉淀最终结论和决策卡片。',
    stageTypes: ['CONCLUDE', 'TRACKING'],
    defaultPrompt: '请生成结构化结论，包括最终判断、依据、待解问题和下一步动作。'
  }
]

// skill key 的前缀即所属分类，用于二级联动选择。
export const workflowSkillGroupLabels = {
  debate: '辩论 / 分析',
  review: '复核校验',
  approval: '审批决策',
  tracking: '追踪推进',
  evidence: '证据核验',
  summary: '总结归档'
}

export const workflowSkillMap = Object.fromEntries(
  workflowSkillCatalog.map(skill => [skill.key, skill])
)

export function getWorkflowSkillMeta(skillKey) {
  return workflowSkillMap[skillKey] || null
}
