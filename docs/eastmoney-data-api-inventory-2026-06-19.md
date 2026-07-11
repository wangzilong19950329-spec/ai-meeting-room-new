# 东方财富数据接口调研与预测输入方案

调研日期：2026-06-19  
验证标的：中船特气 `688146.SH`

> 这些是东方财富官方网页正在使用的第一方 JSON/JSONP 接口，但不是带稳定性承诺的开放 API。实现时必须保留字段校验、超时、重试、降级和原始响应审计。Choice 量化接口才是东方财富正式商业 API。

## 结论

当前预测所需的主要数据已经能够从东方财富补齐：

- 实时行情和资金流
- 历史日 K、周 K、月 K
- 最近 5 个交易日分钟线和均价
- 指数与行业板块 K 线
- 当前及历史 PE/PB/PEG/PS/PCF
- 行业估值对比
- 季度财务指标
- 公司公告列表、完整公告正文和 PDF
- 东方财富站内个股新闻

MA、EMA、MACD、RSI、ATR、BOLL、OBV、历史波动率、支撑阻力和相对强弱不需要额外 API，应由系统基于原始 K 线确定性计算。

## 已验证接口

### 1. 批量实时行情和资金流

状态：已在 `StockQuoteService` 使用，验证通过。

```text
https://push2.eastmoney.com/api/qt/ulist.np/get
```

核心参数：

```text
fltt=2
secids=1.688146,0.300750
fields=f2,f3,f4,f5,f6,f8,f10,f12,f13,f14,f15,f16,f17,f18,f62,f66,f69,f72,f75,f78,f81,f84,f87,f124,f152,f184
```

字段映射：

| 字段 | 含义 |
|---|---|
| f2/f3/f4 | 最新价/涨跌幅/涨跌额 |
| f5/f6 | 成交量/成交额 |
| f8/f10 | 换手率/量比 |
| f15/f16/f17/f18 | 高/低/开/昨收 |
| f62/f184 | 主力净额/主力净占比 |
| f66/f69 | 超大单净额/占比 |
| f72/f75 | 大单净额/占比 |
| f78/f81 | 中单净额/占比 |
| f84/f87 | 小单净额/占比 |
| f124 | 行情时间戳 |

验证值：中船特气最新快照 364.00 元，主力净流入 59,822,752 元，主力净占比 1.12%。

### 2. 个股详情、快速估值和行业

状态：验证通过。

```text
https://push2.eastmoney.com/api/qt/stock/get
```

核心参数：

```text
secid=1.688146
fltt=2
invt=2
fields=f43,f44,f45,f46,f47,f48,f50,f57,f58,f60,f84,f85,f116,f117,f127,f162,f167,f168,f169,f170,f171,f173
```

可取得当前价、OHLC、量比、总/流通股本、总/流通市值、所属行业、动态 PE、PB、换手率、振幅和 ROE。

注意：`stock/get` 的 `f84` 是总股本，而 `ulist.np/get` 资金流字段组中的 `f84` 是小单净额。字段号必须按接口分别映射，禁止建立全局通用 `fXX` 字典。

### 3. 历史 K 线：个股、指数和行业板块共用

状态：验证通过。

```text
https://push2his.eastmoney.com/api/qt/stock/kline/get
```

核心参数：

```text
secid=1.688146
fields1=f1,f2,f3,f4,f5,f6
fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61
klt=101
fqt=1
beg=20250101
end=20500101
lmt=250
```

`klt`：101 日线、102 周线、103 月线。  
`fqt`：0 不复权、1 前复权、2 后复权。

`fields2` 顺序：日期、开、收、高、低、成交量、成交额、振幅、涨跌幅、涨跌额、换手率。

同一接口支持：

- 上证指数：`secid=1.000001`
- 科创 50：`secid=1.000688`
- 半导体板块：`secid=90.BK1036`

验证结果：个股返回 764 条历史记录；科创 50 和半导体板块日 K 均成功返回。

### 4. 最近 5 日分钟线和 VWAP

状态：验证通过。

```text
https://push2his.eastmoney.com/api/qt/stock/trends2/get
```

核心参数：

```text
secid=1.688146
ndays=5
fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13
fields2=f51,f52,f53,f54,f55,f56,f57,f58
```

`fields2` 顺序：时间、开、收、高、低、成交量、成交额、累计均价。可直接用于盘中结构、跳空、尾盘强弱和 VWAP 偏离分析。

### 5. 盘中资金流序列

状态：验证通过。

```text
https://push2.eastmoney.com/api/qt/stock/fflow/kline/get
```

核心参数：

```text
secid=1.688146
lmt=0
klt=1
fields1=f1,f2,f3,f7
fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65
ut=b2884a393a59ad64002292a3e90d46a5
```

响应的主要值依次为时间、主力净额、超大单净额、大单净额、中单净额、小单净额。

### 6. 历史估值与个股估值分位

状态：验证通过。

```text
https://datacenter-web.eastmoney.com/api/data/v1/get
```

参数：

```text
reportName=RPT_VALUEANALYSIS_DET
columns=SECURITY_CODE,SECURITY_NAME_ABBR,TRADE_DATE,CLOSE_PRICE,CHANGE_RATE,PE_TTM,PE_LAR,PB_MRQ,PEG_CAR,PS_TTM,PCF_OCF_TTM,TOTAL_MARKET_CAP,BOARD_CODE,BOARD_NAME
filter=(SECURITY_CODE="688146")
sortColumns=TRADE_DATE
sortTypes=-1
```

验证值（2026-06-18）：PE(TTM) 535.10、PB 32.84、所属半导体行业，返回 764 个交易日历史估值。

`stock/get.f162` 是快速动态估值，不应冒充精确定义的 PE(TTM)。正式分析使用 `RPT_VALUEANALYSIS_DET.PE_TTM`。

### 7. 行业历史估值

状态：验证通过。

```text
reportName=RPT_VALUEINDUSTRY_DET
filter=(BOARD_CODE="016077")
sortColumns=TRADE_DATE
sortTypes=-1
```

返回行业 PE(TTM)、静态 PE、PB、PEG、PS、PCF、总市值和公司数量，并提供 `ORIGINALCODE=1036`，可转换为行情板块代码 `BK1036`。

验证值（2026-06-18）：半导体行业 PE(TTM) 177.12、PB 21.63。

### 8. 财务指标

状态：验证通过。

```text
reportName=RPT_F10_FINANCE_MAINFINADATA
filter=(SECUCODE="688146.SH")
sortColumns=REPORT_DATE
sortTypes=-1
```

返回季度/年度 EPS、营收、归母净利润、扣非净利润、同比/环比、ROE、毛利率、净利率、经营现金流、资产负债率、应收和存货周转等字段。

### 9. 公告列表

状态：验证通过。

```text
https://np-anotice-stock.eastmoney.com/api/security/ann
```

参数：

```text
stock_list=688146
ann_type=A
page_index=1
page_size=20
client_source=web
```

返回公告代码、标题、分类、披露时间和证券信息。

### 10. 公告完整正文和 PDF

状态：验证通过。

```text
https://np-cnotice-stock.eastmoney.com/api/content/ann
```

参数：

```text
art_code=AN202606111823463477
client_source=web
page_index=1
```

返回 `notice_content` 完整文本、公告标题以及 `attach_url` PDF。验证中成功取得中船特气股票交易异常波动公告全文。

### 11. 个股新闻搜索

状态：验证通过，JSONP。

```text
https://search-api-web.eastmoney.com/search/jsonp
```

`param` 内容：

```json
{
  "uid": "",
  "keyword": "中船特气",
  "type": ["cmsArticleWebOld"],
  "client": "web",
  "clientType": "web",
  "clientVersion": "curr",
  "param": {
    "cmsArticleWebOld": {
      "searchScope": "default",
      "sort": "default",
      "pageIndex": 1,
      "pageSize": 10,
      "preTag": "<em>",
      "postTag": "</em>"
    }
  }
}
```

返回新闻标题、摘要、媒体名称、发布时间和原文链接。结果顺序不保证严格按时间，入库前需按 `date` 再排序并去重。

## 当前不稳定接口

### 历史日资金流

东方财富官方资金流页面脚本仍调用：

```text
https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get
```

但本次从后端环境调用连续返回 `Empty reply from server`，不能直接标记为稳定可用。

替代方案：每个交易日从已验证的 `ulist.np/get` 保存收盘前最后一份 `f62/f66/f72/f78/f84` 快照，自建日资金流历史；盘中曲线使用已验证的 `fflow/kline/get`。

## 预测输入分层

### T+1 / T+5 必需数据

- 最新行情及时间戳
- 最近 60 个交易日日 K
- 最近 5 日分钟线和 VWAP
- 盘中资金流最终状态
- 对应市场指数和行业板块最近 60 日日 K
- 最近公告和新闻

系统本地计算：MA5/10/20/60、EMA、MACD、RSI14、ATR14、BOLL20、OBV、历史波动率、量价背离、相对指数/行业强弱、支撑阻力。

### 周度预测

在上述数据上增加：

- 最近 250 日日 K
- 近一年历史估值和行业估值
- 最近 8 个季度财务指标
- 最近 30 日公告和新闻

### 月度预测

在上述数据上增加：

- 周 K、月 K
- 3 年历史估值分位
- 最近 12 个季度财务指标
- 最近 90 日公告、新闻和重大风险事件

## 接入建议

1. 新建 `EastmoneyMarketDataService`，不要继续把所有字段塞进 `StockQuoteService`。
2. 每个接口独立 DTO 和字段字典，严禁跨接口复用 `fXX` 含义。
3. 原始响应、抓取时间、源时间戳、接口版本和字段校验结果一并保存。
4. 行情时间戳不是当前交易日时标记 `STALE`，不允许作为“实时预测”输入。
5. 预测前构建 `requiredData` 和 `optionalData`；必需字段缺失则不生成正式预测，可选字段缺失只降低置信度。
6. 公告正文优先于新闻；新闻用于事件发现，不能覆盖公司正式公告。
7. 预测 Prompt 只传计算后的紧凑特征和必要原文摘要，避免把数千条分钟数据直接塞进网页会话。
