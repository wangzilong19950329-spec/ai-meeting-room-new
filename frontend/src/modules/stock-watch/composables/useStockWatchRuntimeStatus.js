import { shallowRef } from 'vue'
import { createHttp } from '@/api/http'

const runtimeHttp = createHttp(20000)

export function useStockWatchRuntimeStatus() {
  const status = shallowRef(null)
  const error = shallowRef('')

  async function refresh(stock = null) {
    const params = {}
    if (stock?.symbol) params.symbol = stock.symbol
    if (stock?.market) params.market = stock.market
    try {
      const response = await runtimeHttp.get('/stock-watch/runtime-status', {
        params,
        suppressErrorMessage: true
      })
      status.value = response?.data || null
      error.value = ''
    } catch (e) {
      status.value = null
      error.value = normalizeError(e)
    }
  }

  return { status, error, refresh }
}

function normalizeError(error) {
  return error?.response?.data?.msg
    || error?.response?.data?.message
    || error?.message
    || '运行状态读取失败'
}
