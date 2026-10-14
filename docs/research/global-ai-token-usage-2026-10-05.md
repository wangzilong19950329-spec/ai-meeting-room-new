# 全球 AI Token 用量与增速：公开原始资料核查

核查日期：2026-10-05。问题中的“全球”按全球各提供商、产品和自部署大模型的实际推理 Token 合计理解。公开资料目前**没有统一、可审计的全球实测总量或同比增速**；以下是各主体披露的局部观测，不可直接相加或当作全球市场份额。Token 可能包含输入、输出、缓存命中、推理及多模态编码，各平台计数范围未统一。

## 可以直接引用的观测与同口径增速

| 主体及统计边界 | 原始披露 | 可复算的增速 | 限制 |
| --- | --- | --- | --- |
| **Google，各产品界面与 API 的月处理量** | [Google I/O 2026，2026-05-19](https://blog.google/intl/en-africa/products/explore-get-answers/sundar-pichai-io-2026/)：2024 年 5 月约 **9.7 万亿/月**，2025 年 5 月约 **480 万亿/月**，2026 年 5 月 **超过 3,200 万亿/月**（英文原文 *over 3.2 quadrillion*）。 | 按披露的标称数：2025/2024 约 **49.5 倍**；2026/2025 **超过 6.67 倍**，即增幅超过约 **567%**。Google 将后一增速概述为约 **7 倍同比**。 | 这是 Google 自家模型在其产品及 API 的处理量，不是全世界总量，也不是纯文本或仅付费 API 量。披露为近似值和下限，产品覆盖与计数口径的历史稳定性未独立审计。 |
| **Google 模型 API，客户直接调用的瞬时吞吐** | [Google Cloud Next，2026-04-22](https://blog.google/innovation-and-ai/infrastructure-and-cloud/google-cloud/cloud-next-2026-sundar-pichai/)：当时 **超过 160 亿 Token/分钟**，上季度为 **100 亿/分钟**；[Alphabet Q2 2026 CEO 发言，2026-07-22](https://blog.google/company-news/inside-google/message-ceo/alphabet-earnings-q2-2026/)：约 **220 亿/分钟**，上季度 **160 亿/分钟**。 | 用 Q2 发言自身的比较值计算，**220/160 ≈ 1.375 倍，环比约 +37.5%**。 | 这是 API 调用速率快照，不能无条件乘以分钟数当作已实现的日/月用量；与上行 Google 全产品月处理量有包含关系。 |
| **OpenAI API 平台的瞬时吞吐** | [OpenAI DevDay 2025，2025-10-06](https://openai.com/devday/2025/)：**60 亿 Token/分钟**；[OpenAI，2026-04-08](https://openai.com/index/next-phase-of-enterprise-ai/)：其 API **超过 150 亿 Token/分钟**。 | 按 60 亿的标称基数，2026 年 4 月速率约为 2025 年 10 月的**超过 2.5 倍**；相隔约 6 个月，**不是同比**。 | 只覆盖 OpenAI API，不包括 ChatGPT 消费端的全部流量。两次均是公司披露的速率快照，不能推出精确月量；60 亿可能已四舍五入。 |
| **OpenRouter 路由平台的每周吞吐** | [OpenRouter 融资公告，2026-05-28，页面后于 8 月更新](https://openrouter.ai/blog/announcements/series-b/)：过去约 6 个月周流量从 **5 万亿**到 **25 万亿 Token**；[OpenRouter，2026-08-10](https://openrouter.ai/blog/announcements/introducing-the-new-auto-router/)：用于路由决策的过去 7 日用量**超过 55 万亿 Token**。 | OpenRouter 自身披露的六个月周流量约 **5 倍**。8 月的 55 万亿比 5 月公告中的 25 万亿**超过 2.2 倍**；因 5 月文章曾更新，跨文章的精确时点增速只作参考。 | 这是一个多模型路由商的流量，可能与上游 Google、OpenAI 等供应商处理量重叠；亦不包括直连 API、ChatGPT/Gemini 应用、企业私有部署。其[研究限制](https://openrouter.ai/state-of-ai)明确指出单平台视野无法覆盖企业、私有和内部系统。 |

## 全球数字为什么仍是未知

1. 各提供商没有共同提交输入、输出、缓存、推理和多模态 Token 的统一统计；许多私有化或本地部署没有公开计数。OpenRouter 的[2025 年 100 万亿 Token 研究](https://openrouter.ai/state-of-ai)只研究其平台样本，报告自己明确承认它仅是生态的局部视图，不能改称“全球用了 100 万亿”。
2. 路由平台会把请求转给模型提供商；把 OpenRouter 与上游供应商数字相加可能重复计算。Google 的 API 量也已经包含在其全产品量之内。
3. 对应月份和速率不同：例如 Google 2026 年 5 月“每月处理量”、Google/OpenAI 2026 年 4–7 月“每分钟速率”、OpenRouter 2026 年 8 月“过去 7 天流量”，没有共同观测窗。增长速度只应在**同一主体、同一计数边界、相似时间窗**内解释。
4. [Epoch AI 于 2026-05-25 的原始建模分析](https://epoch.ai/gradient-updates/is-a-compute-crunch-coming)认为全球推理需求可能约 **2 亿至 40 亿 Token/秒**、每年约 **10 倍增长**，但原文也说明它来自 Google 等不完美代理及工程师使用强度外推，估算高度不确定。尤其其下限至上限依赖潜在软件工程师需求，并非全行业账单汇总或已经观测到的全球实际用量；**不能把它当作全球实测结论**。该文的全球推理*产能*估算更不能与实际消费量混用。

## 给本次查询的可用结论

**严格表述：**截至 2026-10-05，能核实的是多个大平台自身用量快速上升；全球合计及其增速仍无统一公开实测值。Google 单独披露 2026 年 5 月已超过 **3,200 万亿 Token/月**，这是全球活动规模的一个组成部分、可作同月全球总量的下界，但无法说明它占全球几成。Google 自身全产品月量较 2025 年 5 月增加超过约 **5.67 倍**（达到超过约 6.67 倍）；OpenRouter 自身六个月周量增至约 **5 倍**。这两者都不是“全球同比增速”。

**核查边界：**已打开上述 Google、OpenAI、OpenRouter 与 Epoch AI 原始网页；没有取得各平台原始计量日志、统一 Token 定义或全球覆盖清单。任何声称精确“全球每日/每年 Token 总量”和单一全球增速的结果，需另附模型、包含范围、时间窗、去重方法及不确定区间，否则应标为模型估算或无法核验。

## n8n 本地执行记录

2026-10-05 在本地 n8n 执行 `GlobalTokenSourceAudit20261005`，执行 ID **110**，状态 `success`。四个 HTTP Request 均返回 200，审核节点从 Google I/O、Alphabet Q2 发言、OpenRouter 融资公告及 Auto Router 公告的页面正文中匹配了数值及其上下文，并计算：Google 全产品月处理量 2026/2025 **超过 6.67 倍**（增幅超过约 **567%**）；Google API 速率季度比较 **+37.5%**；OpenRouter 自身六个月每周流量 **5 倍**。该临时工作流已恢复为未发布状态，执行记录保留在本地 n8n。

另一次 n8n DeepSeek bridge 搜索执行 ID **108** 在 240 秒 HTTP 超时前未返回结果，未进入审核，也没有为本报告贡献已核验事实。执行 110 改由 n8n 直接读取上述一手网页。`NOT_ESTABLISHED` 是基于公开来源不完整、口径不同且可能重复计数所作的研究判断，不是四次网页请求能形式化证明的“全球数据不存在”。
