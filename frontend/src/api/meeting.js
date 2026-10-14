import { createHttp } from '@/api/http'

const http = createHttp(30000)

export const meetingApi = {
  create: (data) => http.post('/meetings', data),
  start: (id) => http.post(`/meetings/${id}/start`),
  getDetail: (id) => http.get(`/meetings/${id}`),
  list: (creatorId) => http.get('/meetings', { params: { creatorId } }),
  listWorkflowTemplates: () => http.get('/workflow-templates'),
  listWorkflowTemplateDetails: () => http.get('/workflow-templates/detail'),
  createWorkflowTemplate: (data) => http.post('/workflow-templates', data),
  updateWorkflowTemplate: (id, data) => http.put(`/workflow-templates/${id}`, data),
  deleteWorkflowTemplate: (id) => http.delete(`/workflow-templates/${id}`),
  listSkillFiles: () => http.get('/skill-files'),
  uploadSkillFile: (formData) => http.post('/skill-files/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }),
  deleteSkillFile: (id) => http.delete(`/skill-files/${id}`),
  cancel: (id, config = {}) => http.post(`/meetings/${id}/cancel`, null, config),
  verify: (id, valid) => http.post(`/meetings/${id}/verify`, null, { params: { valid } }),
  getContributions: (id) => http.get(`/meetings/${id}/contributions`),
  getMemberScores: (memberId) => http.get(`/members/${memberId}/scores`),
  sendHumanMessage: (id, content, mode = 'message', targetMemberId = null) =>
    http.post(`/meetings/${id}/human-message`, { content, mode, targetMemberId },
      { suppressErrorMessage: true }),
  getTrackingOverview: () => http.get('/tracking-items/overview'),
  createCustomTrackingItem: (data) => http.post('/tracking-items/custom', data),
  refreshTrackingItem: (id) => http.post(`/tracking-items/${id}/refresh`),
  reopenMeetingFromTracking: (id) => http.post(`/tracking-items/${id}/reopen-meeting`),
  updateTrackingStatus: (id, status, note = '', evidenceRef = '') =>
    http.post(`/tracking-items/${id}/status`, { status, note, evidenceRef }),
  submitWorkflowStage: (id, content, actorRef = '') =>
    http.post(`/meetings/${id}/workflow/submit`, { content, actorRef }),
  reviewWorkflowStage: (id, approved, comment = '', actorRef = '') =>
    http.post(`/meetings/${id}/workflow/review`, { approved, comment, actorRef }),
  retryWorkflowAutoStage: (id) => http.post(`/meetings/${id}/workflow/retry-auto`),
  clone: (id, title) => http.post(`/meetings/${id}/clone`, { title }),
  terminate: (id) => http.post(`/meetings/${id}/terminate`),
  restart: (id) => http.post(`/meetings/${id}/restart`),
  remove: (id) => http.delete(`/meetings/${id}`),
  batchRemove: (ids, config = {}) => http.post('/meetings/batch-delete', { ids }, config),
}

export const templateApi = {
  list: () => http.get('/member-templates'),
  create: (data) => http.post('/member-templates', data),
  update: (id, data) => http.put(`/member-templates/${id}`, data),
  remove: (id) => http.delete(`/member-templates/${id}`),
}
