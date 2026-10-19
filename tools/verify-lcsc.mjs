#!/usr/bin/env node
/**
 * 立创数据链路契约验证
 *
 * 作用：确认「输入型号 → 拿到立创号 → 打开商品页」这条链路**当前仍然可用**。
 * 接口是立创 EDA 前端自用的，不属于对外承诺的开放平台，字段和路径有可能变。
 * 因此把它当契约测试，定期跑一遍比读文档可靠。
 *
 * 用法：
 *   node tools/verify-lcsc.mjs
 *   node tools/verify-lcsc.mjs C8734 SS34 1N4148
 *
 * 退出码：0 全部通过；1 有失败（可用于 CI 告警）。
 *
 * 注意：本脚本只验证接口字段与链接有效性，**不复制 App 的排序逻辑**——
 * 排序规则由 app/src/test/.../LcscMatcherTest.kt 覆盖，避免两套实现漂移。
 */

const API = 'https://pro.lceda.cn/api/eda/product/search'
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'

/**
 * 用例： [关键词, 期望型号(用于确认落到的确是同一个器件), 硬性要求的立创号]
 *
 * 立创号不硬编码具体值 —— 同型号常有多个品牌在售（NE555 首条在 TI 与国睿之间变过），
 * 首位结果随库存浮动。硬编码会造成误报。唯一必须精确的是「用立创号反查」那一条。
 */
const DEFAULT_CASES = [
  ['STM32F103C8T6', 'STM32F103C8T6', null],
  ['SS34', 'SS34', null],
  ['NE555', 'NE555', null],
  ['AMS1117-3.3', 'AMS1117-3.3', null],
  ['C8734', 'STM32F103C8T6', 'C8734'], // 立创号反查：必须落到同一个器件
  ['10k 0603', null, null], // 中文+数值混合关键词
  ['肖特基二极管', null, null], // 纯中文分类词
]

const timeout = 15000

async function search(keyword) {
  const url = `${API}?keyword=${encodeURIComponent(keyword)}&currentPage=1&pageSize=20`
  const r = await fetch(url, {
    signal: AbortSignal.timeout(timeout),
    headers: { 'User-Agent': UA, Accept: 'application/json, text/plain, */*', Referer: 'https://pro.lceda.cn/editor' },
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  const j = await r.json()
  if (j.code !== 200 || !j.ok) throw new Error(`接口返回 code=${j.code} msg=${j.msg}`)
  return (j.result && j.result.productList) || []
}

/** 软 404：立创 404 页的 HTTP 状态码是 200，只能看正文那句话（不能搜 404.html，正常页也引用它） */
function isSoft404(html) {
  return html.includes('您要访问的页面没有找到')
}

async function checkLink(url, expectModel) {
  const r = await fetch(url, { signal: AbortSignal.timeout(timeout), headers: { 'User-Agent': UA } })
  const html = await r.text()
  const title = (html.match(/<title[^>]*>([^<]*)<\/title>/i) || [])[1] || ''
  return {
    status: r.status,
    ok: r.status === 200 && !isSoft404(html) && (!expectModel || html.includes(expectModel)),
    title: title.slice(0, 60),
    soft404: isSoft404(html),
  }
}

const cases = process.argv.slice(2).length
  ? process.argv.slice(2).map((k) => [k, null, null])
  : DEFAULT_CASES

let pass = 0
let fail = 0
const rows = []

console.log(`\n立创数据链路验证 · ${new Date().toISOString()}\n`)

for (const [keyword, expectModel, mustCode] of cases) {
  try {
    const t0 = Date.now()
    const list = await search(keyword)
    const ms = Date.now() - t0
    if (!list.length) throw new Error('无结果')

    // 与 App 一致：C 编号做精确过滤，型号按归一化相等优先
    const norm = (s) => (s || '').toUpperCase().replace(/[\s\-_/]+/g, '')
    let picked
    if (/^[Cc]\d{3,9}$/.test(keyword.trim())) {
      picked = list.find((p) => p.code.toUpperCase() === keyword.trim().toUpperCase()) || list[0]
    } else {
      picked = list.find((p) => norm(p.model) === norm(keyword)) || list[0]
    }

    const url = `https://item.szlcsc.com/${picked.id}.html`
    const link = await checkLink(url, expectModel || picked.model)

    // 立创号必须是合法形态；立创号反查的用例还要求完全一致
    const codeOk = /^C\d{3,9}$/.test(picked.code) && (!mustCode || picked.code.toUpperCase() === mustCode.toUpperCase())
    // 型号必须对得上，否则就是张冠李戴
    const modelOk = !expectModel || (picked.model || '').toUpperCase().includes(expectModel.toUpperCase())
    const ok = codeOk && modelOk && link.ok
    ok ? pass++ : fail++
    rows.push({
      关键词: keyword,
      立创号: picked.code,
      型号: picked.model,
      封装: picked.standard || '-',
      链接: url,
      耗时: `${ms}ms`,
      结果: ok ? '✅' : '❌',
    })
    if (!ok) {
      console.log(`  失败详情 [${keyword}]: 实际码=${picked.code} 型号=${picked.model} status=${link.status} soft404=${link.soft404} title="${link.title}"`)
    }
  } catch (e) {
    fail++
    rows.push({ 关键词: keyword, 立创号: '-', 型号: '-', 封装: '-', 链接: '-', 耗时: '-', 结果: `❌ ${e.message}` })
  }
}

// 输出对齐表格
const cols = ['关键词', '立创号', '型号', '封装', '耗时', '结果']
const w = (s, n) => {
  // 中文按 2 个宽度算
  let len = 0
  for (const ch of s) len += /[\u4e00-\u9fa5\uff00-\uffef]/.test(ch) ? 2 : 1
  return s + ' '.repeat(Math.max(0, n - len))
}
const width = Object.fromEntries(cols.map((c) => [c, Math.max(c.length + 2, ...rows.map((r) => {
  let len = 0
  for (const ch of String(r[c] ?? '')) len += /[\u4e00-\u9fa5\uff00-\uffef]/.test(ch) ? 2 : 1
  return len + 2
}))]))
console.log(cols.map((c) => w(c, width[c])).join(''))
console.log(cols.map((c) => '-'.repeat(width[c] - 1)).join(' '))
for (const r of rows) console.log(cols.map((c) => w(String(r[c] ?? ''), width[c])).join(''))

console.log(`\n通过 ${pass} / 失败 ${fail}`)
console.log(fail === 0 ? '✅ 链路正常：型号 → 立创号 → 商品页 全部可解析且可跳转' : '❌ 有失败项，接口或链接格式可能已变更')
process.exit(fail === 0 ? 0 : 1)
