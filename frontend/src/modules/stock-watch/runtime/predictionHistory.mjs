export async function loadAllPredictionPages(fetchPage, pageSize = 200) {
  if (!Number.isInteger(pageSize) || pageSize <= 0) throw new Error('预测历史分页大小无效')
  const records = []
  const seenIds = new Set()
  let firstPayload = null
  let params = { limit: pageSize }

  while (true) {
    const payload = await fetchPage(params)
    if (firstPayload === null) firstPayload = payload
    const page = payload?.records
    if (!Array.isArray(page) || page.length > pageSize) throw new Error('预测历史分页响应无效')
    for (const prediction of page) {
      if (prediction.id == null || seenIds.has(String(prediction.id))) {
        throw new Error('预测历史分页重复或缺少记录ID')
      }
      seenIds.add(String(prediction.id))
      records.push(prediction)
    }
    if (page.length < pageSize) return { ...firstPayload, records }

    const last = page[page.length - 1]
    if (!last.forecastDate) throw new Error('预测历史分页缺少日期')
    params = { limit: pageSize, beforeDate: last.forecastDate, beforeId: last.id }
  }
}

export function selectVisiblePredictionGroups(groups, groupByDay, expanded, limit = 6) {
  return groupByDay || expanded ? groups : groups.slice(0, limit)
}
