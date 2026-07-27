import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(new URL('..', import.meta.url).pathname)
const stockWatchVue = read('src/views/StockWatch.vue')
const demoMode = read('src/modules/stock-watch/runtime/demoMode.js')

const checks = [
  {
    name: 'StockWatch.vue does not inline the legacy demo watchlist',
    pass: !stockWatchVue.includes("const watchlist = reactive([\n  {\n    symbol: '688017'")
  },
  {
    name: 'StockWatch.vue no longer fabricates quote snapshot series',
    pass: !stockWatchVue.includes('function buildQuoteSeries') && !stockWatchVue.includes('quote.previousClose,\n    quote.open,\n    quote.low,\n    quote.price')
  },
  {
    name: 'StockWatch.vue does not label a synthetic line as kline',
    pass: !stockWatchVue.includes('10-minute kline') && !stockWatchVue.includes('10 分钟 K 线，用于价格技术图')
  },
  {
    name: 'Demo data is dynamically gated by VITE_STOCK_WATCH_DEMO_MODE',
    pass: demoMode.includes("VITE_STOCK_WATCH_DEMO_MODE === 'true'") && demoMode.includes('await import(')
  }
]

const failed = checks.filter((check) => !check.pass)
checks.forEach((check) => {
  console.log(`${check.pass ? 'PASS' : 'FAIL'} ${check.name}`)
})

if (failed.length) {
  process.exitCode = 1
}

function read(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8')
}
