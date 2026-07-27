export function isStockWatchDemoMode(env = import.meta.env) {
  return env?.VITE_STOCK_WATCH_DEMO_MODE === 'true'
}

export async function loadDemoState(env = import.meta.env) {
  if (!isStockWatchDemoMode(env)) {
    return null
  }
  const module = await import('../mock/stockWatchDemoData.js')
  return clonePageState(module.stockWatchDemoData)
}

export function clonePageState(state) {
  if (typeof structuredClone === 'function') {
    return structuredClone(state)
  }
  return JSON.parse(JSON.stringify(state))
}
