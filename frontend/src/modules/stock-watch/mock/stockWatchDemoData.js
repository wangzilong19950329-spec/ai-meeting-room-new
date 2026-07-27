const demoBars = [
  { startTime: '2026-06-04 09:31', endTime: '2026-06-04 09:32', open: 251.4, high: 253.2, low: 250.8, close: 252.8, volume: 1820000, amount: 459200000, qualityStatus: 'VALID' },
  { startTime: '2026-06-04 09:32', endTime: '2026-06-04 09:33', open: 252.8, high: 255.6, low: 252.1, close: 255.2, volume: 2110000, amount: 537800000, qualityStatus: 'VALID' },
  { startTime: '2026-06-04 09:33', endTime: '2026-06-04 09:34', open: 255.2, high: 256.0, low: 253.6, close: 254.1, volume: 1450000, amount: 369400000, qualityStatus: 'VALID' },
  { startTime: '2026-06-04 09:34', endTime: '2026-06-04 09:35', open: 254.1, high: 258.1, low: 253.8, close: 257.6, volume: 2310000, amount: 594900000, qualityStatus: 'VALID' },
  { startTime: '2026-06-04 09:35', endTime: '2026-06-04 09:36', open: 257.6, high: 260.4, low: 257.0, close: 259.2, volume: 2690000, amount: 697200000, qualityStatus: 'VALID' },
  { startTime: '2026-06-04 09:36', endTime: '2026-06-04 09:37', open: 259.2, high: 260.1, low: 257.5, close: 258.0, volume: 1740000, amount: 450100000, qualityStatus: 'VALID' }
]

export const stockWatchDemoData = {
  demo: true,
  selectedSymbol: '688017',
  watchlist: [
    {
      symbol: '688017',
      name: '演示-绿的谐波',
      market: 'sh',
      image: '',
      price: '258.00',
      change: 1.84,
      status: '演示数据',
      severity: 'opportunity',
      refresh: {
        source: '演示数据',
        endpoint: 'demo quote + demo minute bars',
        last: '13:30',
        next: '演示模式不轮询'
      },
      intradayBars: demoBars,
      technical: {
        open: '254.20',
        high: '261.30',
        low: '250.80',
        volume: '8.42亿',
        ma5: '256.4',
        ma10: '259.8',
        ma20: '272.1',
        rsi: '54.6',
        macd: '-0.18',
        vwap: '257.2',
        vwapDeviation: '+0.31%',
        volumeRatio: '1.28',
        turnover: '2.6%',
        atr: '4.1%',
        bollWidth: '9.8%',
        bias20: '-5.2%',
        obvTrend: '转强',
        rsRank: '68/100',
        sectorStrength: '+1.6%',
        mainInflow: '+1.1亿',
        levels: [
          { label: '压力', value: 287, type: 'pressure' },
          { label: '支撑', value: 250, type: 'support' }
        ]
      },
      rules: [],
      sources: [
        { name: 'Demo quote', desc: '演示行情，不代表真实报价', ok: true },
        { name: 'Demo minute bars', desc: '演示 OHLC bars，用于组件预览', ok: true }
      ],
      rawData: {
        demo: true,
        watermark: '演示数据，不用于真实盯盘'
      }
    },
    {
      symbol: '300760',
      name: '演示-迈瑞医疗',
      market: 'sz',
      image: '',
      price: '158.42',
      change: -0.36,
      status: '演示数据',
      severity: 'info',
      refresh: {
        source: '演示数据',
        endpoint: 'demo quote',
        last: '13:20',
        next: '演示模式不轮询'
      },
      intradayBars: [],
      technical: {
        open: '159.10',
        high: '160.80',
        low: '157.90',
        volume: '4.18亿',
        ma5: '158.9',
        ma10: '161.2',
        ma20: '166.8',
        rsi: '42.3',
        macd: '-0.42',
        vwap: '158.8',
        vwapDeviation: '-0.24%',
        volumeRatio: '0.86',
        turnover: '0.8%',
        atr: '2.3%',
        bollWidth: '6.2%',
        bias20: '-5.0%',
        obvTrend: '走弱',
        rsRank: '42/100',
        sectorStrength: '-0.4%',
        mainInflow: '-0.3亿',
        levels: [
          { label: '压力', value: 170, type: 'pressure' },
          { label: '支撑', value: 155, type: 'support' }
        ]
      },
      rules: [],
      sources: [
        { name: 'Demo quote', desc: '演示行情，不代表真实报价', ok: true }
      ],
      rawData: {
        demo: true,
        watermark: '演示数据，不用于真实盯盘'
      }
    }
  ],
  events: [
    {
      eventId: 'demo_lvde_20260604_A',
      symbol: '688017',
      stockName: '演示-绿的谐波',
      time: '13:30',
      source: 'demo',
      severity: 'opportunity',
      title: '演示：价格回踩 + 主力净流入转正',
      desc: '演示事件，仅用于界面预览。'
    },
    {
      eventId: 'demo_system_watermark',
      symbol: null,
      stockName: '',
      time: '13:31',
      source: 'demo',
      severity: 'info',
      title: '演示模式已开启',
      desc: '当前页面展示的是演示数据，不代表真实运行状态。'
    }
  ],
  plugins: [
    { name: 'demo-logger', desc: '演示插件闸门，不会影响后端', enabled: true },
    { name: 'demo-notify', desc: '演示通知开关，不会发送邮件', enabled: false },
    { name: 'demo-analysis', desc: '演示分析开关，不会调用 DeepSeek', enabled: false }
  ]
}
