/* 前端页面级自检（仅开发用，不进发布包）
 *
 * 用 jsdom 把 index.html + app.js 真跑一遍，连到 preview/mock-server.js，
 * 断言"日期控件 / 按天联动 / 事件分页 / 全部事件弹窗"这些改过的地方
 * 真的渲染出了东西 —— 光看语法过不过没法知道界面是不是空的。
 *
 * 用法：
 *   node preview/mock-server.js 8711      # 另开一个窗口
 *   node preview/ui-check.js              # 默认连 8711
 *
 * 依赖 jsdom（只装在本机 node 隔离目录里，不进项目、不进发布包）：
 *   cd %USERPROFILE%\.workbuddy\binaries\node\workspace && npm i jsdom
 */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const BASE = `http://127.0.0.1:${process.argv[2] || 8711}/`;
// 第 3 个参数是视口宽度，用来顺便验证手机布局那条分支
const VIEWPORT = Number(process.argv[3] || 1280);

const html = fs.readFileSync(path.join(ROOT, 'static', 'index.html'), 'utf8');
const appjs = fs.readFileSync(path.join(ROOT, 'static', 'app.js'), 'utf8');

let pass = 0;
let fail = 0;
const errors = [];

function ok(cond, name, extra) {
  if (cond) {
    pass++;
    console.log(`  \u2713 ${name}`);
  } else {
    fail++;
    console.log(`  \u2717 ${name}${extra ? '  ← ' + extra : ''}`);
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(fn, ms = 4000, step = 50) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    try {
      if (fn()) return true;
    } catch (e) {
      /* 渲染中间态，继续等 */
    }
    await sleep(step);
  }
  return false;
}

(async () => {
  const dom = new JSDOM(html, {
    url: BASE,
    runScripts: 'dangerously',
    pretendToBeVisual: true,
  });
  const w = dom.window;
  const d = w.document;

  // 页面里的 fetch 用 node 的，相对路径按 BASE 解析
  w.fetch = (u, opt) => fetch(new URL(u, BASE).href, opt);
  w.addEventListener('error', (e) => errors.push(e.message || String(e.error)));
  w.addEventListener('unhandledrejection', (e) => errors.push('unhandled: ' + (e.reason && e.reason.message)));

  // jsdom 缺这两个浏览器 API：补桩，不代表业务代码有问题
  // （matchMedia 需要按查询串给出 matches，否则时间线会选错布局分支）
  // VIEWPORT 用文件顶部那个（见上）
  w.matchMedia = (q) => {
    const m = /max-width:\s*(\d+)px/.exec(q);
    const n = /min-width:\s*(\d+)px/.exec(q);
    let matches = false;
    if (m) matches = VIEWPORT <= Number(m[1]);
    else if (n) matches = VIEWPORT >= Number(n[1]);
    return {
      matches, media: q,
      addListener() {}, removeListener() {},
      addEventListener() {}, removeEventListener() {},
      dispatchEvent() { return false; },
    };
  };
  w.scrollTo = () => {};

  // 预置登录态，跳过登录页
  w.localStorage.setItem('guardian_admin_token', 'preview-token');
  w.localStorage.setItem('guardian_admin_user', JSON.stringify({ id: 'u-admin', username: 'admin', is_admin: true }));

  w.eval(appjs);

  console.log('\n[1] 首页');
  const loaded = await waitFor(() => d.querySelectorAll('#devices .device').length > 0);
  ok(loaded, '设备卡片渲染出来了');
  ok(d.querySelectorAll('#devices .device').length === 3, '设备数量 = 3');

  const evHtml = d.getElementById('events').innerHTML;
  ok(!d.getElementById('events').querySelector('.empty'), '首页事件不是空态');
  const stamps = [...d.querySelectorAll('#events .event .time')].map((n) => n.textContent.trim());
  ok(stamps.length > 0, `首页事件带时间戳（${stamps.length} 条）`);
  const stampOk = stamps.every((s) => /^(今天|昨天|\d{2}-\d{2}|\d{4}-\d{2}-\d{2}) \d{2}:\d{2}$/.test(s));
  ok(stampOk, '时间戳格式 = 日期 + 时分', stamps.slice(0, 3).join(' | '));
  ok(/48/.test(d.getElementById('evWindow').textContent), '首页标注"最近 48 小时"');

  const metrics = d.querySelector('#devices .device .device-metrics').textContent;
  ok(/解锁/.test(metrics), '首页设备卡显示"今日解锁"');
  ok(/亮屏/.test(metrics), '首页设备卡显示"今日亮屏"');
  ok(/违规 3 次/.test(metrics), '设备卡标出违规次数（惩罚会放大休息时长）');
  ok(/×3/.test(metrics), '设备卡标出惩罚倍数 ×3');

  console.log('\n[2] 设备页：日期控件');
  d.querySelector('#devices .device').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  // 注意等待条件：dayInput 是在 loadTimeline 里同步赋值的，而事件列表要等
  // /events 回来才填。只等 dayInput 会读到"事件还没渲染"的中间态。
  const devReady = await waitFor(() => !d.getElementById('devicePage').classList.contains('hidden')
    && d.getElementById('dayInput').value
    && /共 \d+ 条/.test(d.getElementById('dpEvHint').textContent));
  ok(devReady, '进入设备页');

  const dayInput = d.getElementById('dayInput');
  const today = dayInput.value;
  ok(/^\d{4}-\d{2}-\d{2}$/.test(today), `日期控件默认 = 今天（${today}）`);
  ok(dayInput.max === today, '日期控件 max = 今天（禁止翻未来）');
  ok(d.getElementById('btnNextDay').disabled, '今天的"下一天"按钮置灰');
  ok(d.getElementById('btnToday').disabled, '今天的"今天"按钮置灰');
  ok(/共 \d+ 条/.test(d.getElementById('dpEvHint').textContent), `事件计数提示：${d.getElementById('dpEvHint').textContent}`);

  const evCount = d.querySelectorAll('#dpEvents .cmd').length;
  ok(evCount > 0, `当天事件有内容（${evCount} 条）`);

  console.log('\n[2.5] 新增统计：亮屏 / 解锁 / 开关机');
  const laneNames = [...d.querySelectorAll('#tlCanvas .tl-name')].map((n) => n.textContent.trim());
  ok(laneNames.includes('屏幕'), `时间轴有"屏幕"行（${laneNames.join('/')}）`);
  const scrLane = d.querySelectorAll('#tlCanvas .tl-scr').length;
  ok(scrLane > 0, `屏幕轨道画出了绿线（${scrLane} 段）`);

  const cards = [...d.querySelectorAll('#dpStats .stat')].map((s) => ({
    label: s.querySelector('.label').textContent,
    value: s.querySelector('.value').textContent,
    foot: s.querySelector('.foot').textContent,
  }));
  const cardOf = (k) => cards.find((c) => c.label.includes(k));
  ok(!!cardOf('亮屏'), '有"亮屏"统计卡');
  ok(!!cardOf('解锁'), '有"解锁"统计卡');
  ok(!!cardOf('开关机'), '有"开关机"统计卡');
  ok(cardOf('亮屏') && cardOf('亮屏').value !== '—', `亮屏数值：${cardOf('亮屏') && cardOf('亮屏').value}`);
  ok(cardOf('解锁') && /\d/.test(cardOf('解锁').value), `解锁数值：${cardOf('解锁') && cardOf('解锁').value}`);
  ok(cardOf('开关机') && /\d/.test(cardOf('开关机').value), `开机数值：${cardOf('开关机') && cardOf('开关机').value} / ${cardOf('开关机') && cardOf('开关机').foot}`);
  ok(/系统口径|一致/.test(cardOf('前台') ? cardOf('前台').foot : ''), `前台卡标注口径对照：${cardOf('前台') && cardOf('前台').foot}`);

  // 第一张卡必须是"本轮"口径。以前标题写"今天使用"、数字取当天累计，
  // 而进度条和脚注都是本轮 —— 三个信息互相打架，家长第一反应就是"名字不对"。
  ok(cards[0].label === '本轮使用', `第一张卡叫"本轮使用"（实际：${cards[0].label}）`);
  ok(/今天累计/.test(cards[0].foot), `脚注里仍保留当天累计（${cards[0].foot}）`);
  ok(/上限/.test(cards[0].foot), '脚注带使用上限');

  console.log('\n[2.6] 锁定按钮下发的分钟数（v2.3.1：1 分钟不再变成 5 分钟）');
  const lockBtn = d.querySelector('#dpActions button[data-act="lock"]');
  ok(!!lockBtn, '设备页有锁定按钮');
  ok(lockBtn && lockBtn.dataset.min === '5', `按钮带着设备配置的分钟数（${lockBtn && lockBtn.dataset.min}）`);
  ok(lockBtn && /锁定 5 分钟/.test(lockBtn.textContent), `按钮文案与下发值一致（${lockBtn && lockBtn.textContent.trim()}）`);
  ok(!!d.querySelector('#dpActions button[data-act="reset_tamper"]'), '设备页有「清零违规计数」按钮');

  const cfgText = d.getElementById('dpConfig').textContent.replace(/\s+/g, ' ');
  ok(/违规次数/.test(cfgText), '配置卡说明惩罚会放大休息时长');
  ok(/5×3 = 15 分钟/.test(cfgText), `配置卡算出了 5×3=15：${(cfgText.match(/当前已记[^。]*/) || [''])[0]}`);

  // 真点一次：确认发给后端的是 {minutes: 设备配置值}，而不是空 params
  let cmdBody = null;
  const realFetch = w.fetch;
  w.fetch = (u, opt) => {
    try {
      if (opt && opt.body && String(u).includes('/command')) cmdBody = JSON.parse(opt.body);
    } catch (e) { /* 不是 JSON 就忽略 */ }
    return realFetch(u, opt);
  };
  w.confirm = () => true;
  lockBtn.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await waitFor(() => cmdBody !== null, 3000);
  w.fetch = realFetch;
  ok(cmdBody && cmdBody.type === 'lock', '点按钮发出了 lock 指令');
  ok(cmdBody && cmdBody.params && cmdBody.params.minutes === 5,
    `下发的 minutes = ${cmdBody && cmdBody.params && cmdBody.params.minutes}`);

  console.log('\n[2.7] 停用时段面板（v2.4）');
  const quietNow = d.getElementById('quietNow');
  ok(quietNow.classList.contains('on'), `状态条显示"正在停用中"（${quietNow.textContent.replace(/\s+/g, ' ').trim()}）`);
  ok(/还会锁/.test(quietNow.textContent), '状态条带剩余时长');

  const rows = [...d.querySelectorAll('#quietList .quiet-row')];
  ok(rows.length === 2, `规则行数 = 2（实际 ${rows.length}）`);
  const row0 = rows[0];
  ok(row0.querySelector('[data-q="from"]').value === '22:00', `第一行开始时间 ${row0 && row0.querySelector('[data-q="from"]').value}`);
  ok(row0.querySelector('[data-q="to"]').value === '06:00', `第一行结束时间 ${row0 && row0.querySelector('[data-q="to"]').value}`);
  ok(row0.querySelector('[data-q="label"]').value === '睡觉', '第一行备注保留');
  ok(!row0.classList.contains('off'), '第一行处于启用状态');
  ok(rows[1] && rows[1].classList.contains('off'), '未启用的规则行被标灰');

  const dayBtns = [...row0.querySelectorAll('.qi-day')];
  ok(dayBtns.length === 7, '每行有 7 个星期按钮');
  ok(dayBtns.every((b) => b.classList.contains('on')), 'days=0 时 7 天全亮（表示每天）');

  const hint = d.getElementById('quietHint').textContent;
  ok(/1 条生效中/.test(hint), `汇总提示：${hint}`);
  ok(/22:00/.test(hint) && /06:00/.test(hint), '汇总里带具体时段');

  // 真点一次保存：确认发出去的是完整 rules（服务端整份替换）
  let quietBody = null;
  const realFetch2 = w.fetch;
  w.fetch = (u, opt) => {
    try {
      if (opt && opt.body && String(u).includes('/command')) quietBody = JSON.parse(opt.body);
    } catch (e) { /* 忽略 */ }
    return realFetch2(u, opt);
  };
  d.getElementById('btnQuietSave').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await waitFor(() => quietBody !== null, 3000);
  w.fetch = realFetch2;
  ok(quietBody && quietBody.type === 'quiet', '点保存发出了 quiet 指令');
  ok(quietBody && quietBody.params && Array.isArray(quietBody.params.rules)
    && quietBody.params.rules.length === 2, `下发了 2 条规则（${quietBody && quietBody.params && quietBody.params.rules && quietBody.params.rules.length}）`);
  const sent0 = quietBody && quietBody.params && quietBody.params.rules && quietBody.params.rules[0];
  ok(sent0 && sent0.from === 1320 && sent0.to === 360, `时间按"0 点起算的分钟数"下发（${sent0 && sent0.from}→${sent0 && sent0.to}）`);
  ok(sent0 && sent0.id === 'q_night', '规则 id 原样带上（服务端据此保持稳定）');

  console.log('\n[2.85] 锁定原因 / 本机规则条数（v2.6.1）');
  // 家长最难自己查清的一件事就是"孩子为什么被锁"。设备把原因报上来之后，
  // 设备卡上必须真的显示出来，而不是只存在接口里。
  const cardText = d.getElementById('devices').textContent;
  ok(/使用时长达到上限/.test(cardText),
    `设备卡显示本次锁定原因（${cardText.replace(/\s+/g, ' ').slice(0, 80)}…）`);
  // 本机规则的内容不在服务端，只报条数；不提示的话家长会以为设置没生效
  ok(/本机另有 1 条/.test(d.getElementById('quietHint').textContent),
    `停用面板提示本机另有几条（${d.getElementById('quietHint').textContent.slice(0, 60)}…）`);

  console.log('\n[2.8] 应用 ↔ 时间线联动（点一行只看它，点空白恢复）');
  // 每次都要重新查：renderDpApps 会重建 #dpApps 的 innerHTML，
  // 之前拿到的行已经是脱离文档的孤儿节点，再点它不会冒泡到容器。
  const rowEl = () => d.querySelector('#dpApps .app-row');
  const segCount = () => d.querySelectorAll('#tlCanvas .tl-app').length;
  const clickRow = async () => {
    rowEl().dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
    await sleep(150);
  };

  const allSegs = segCount();
  ok(!!rowEl(), '应用排行有可点的行');
  ok(!!rowEl().dataset.pkg, '行上带包名（过滤靠它）');
  ok(allSegs > 1, `过滤前应用轨道有 ${allSegs} 段`);

  await clickRow();
  ok(rowEl().classList.contains('on'), '被点的应用行高亮');
  ok(/只看/.test(d.getElementById('tlSub').textContent),
    `时间轴标题说明只看哪个（${d.getElementById('tlSub').textContent}）`);
  const pickedSegs = segCount();
  ok(pickedSegs > 0 && pickedSegs < allSegs, `只看时色块变少（${allSegs} → ${pickedSegs}）`);
  ok(!!d.querySelector('#tlLegend [data-clear-filter]'), '图例上有"只看 X ✕"，一眼能看出在过滤');

  // 再点同一行 = 取消（比强迫家长去找按钮顺手）
  await clickRow();
  ok(segCount() === allSegs, '再点同一行恢复全部');
  ok(!d.querySelector('#tlLegend [data-clear-filter]'), '过滤标识消失');

  // 点时间线空白处也能恢复 —— 这是用户明确要的那个手势
  await clickRow();
  ok(segCount() < allSegs, '再次过滤生效');
  d.getElementById('tlCanvas').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await sleep(150);
  ok(segCount() === allSegs, '点时间线空白处恢复全部应用');

  console.log('\n[2.9] 答题解锁面板（v2.6.0）');
  const quizReady = await waitFor(
    () => d.querySelector('#quizCfg #btnSaveQuiz'), 4000,
  );
  ok(quizReady, '答题面板渲染出来了（不是一直停在"加载题库…"）');
  ok(d.getElementById('qEnabled').checked, '设备 A 的开关是开着的');
  ok(d.getElementById('qInterval').value === '10',
    `间隔回显 ${d.getElementById('qInterval').value} 分钟`);
  ok(d.getElementById('qPenalty').value === '2',
    `罚时回显 ${d.getElementById('qPenalty').value} 分钟`);
  const picks = [...d.querySelectorAll('#quizCfg .qBank')];
  ok(picks.length === 3, `题库勾选项 = 3（实际 ${picks.length}）`);
  const checkedIds = picks.filter((el) => el.checked).map((el) => Number(el.value));
  ok(checkedIds.length === 2 && checkedIds.includes(1) && checkedIds.includes(3),
    `设备已勾选的题库回显正确（${checkedIds.join(',')}）`);

  // 真点一次保存：要发两条指令（config + quiz_banks），漏了任意一条都不生效
  let quizBodies = [];
  const realFetchQuiz = w.fetch;
  w.fetch = (u, opt) => {
    try {
      if (opt && opt.body && String(u).includes('/command')) quizBodies.push(JSON.parse(opt.body));
    } catch (e) { /* 忽略 */ }
    return realFetchQuiz(u, opt);
  };
  d.getElementById('btnSaveQuiz').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await waitFor(() => quizBodies.length >= 2, 4000);
  w.fetch = realFetchQuiz;
  ok(quizBodies.length === 2, `保存发出 2 条指令（实际 ${quizBodies.length}）`);
  const cfgCmd = quizBodies.find((b) => b.type === 'config');
  const bankCmd = quizBodies.find((b) => b.type === 'quiz_banks');
  ok(!!cfgCmd && cfgCmd.params.quiz_enabled === true, 'config 里带 quiz_enabled=true');
  ok(!!cfgCmd && cfgCmd.params.quiz_interval_minutes === 10, 'config 里带间隔 10');
  ok(!!cfgCmd && cfgCmd.params.quiz_penalty_minutes === 2, 'config 里带罚时 2');
  ok(!!bankCmd && Array.isArray(bankCmd.params.ids) && bankCmd.params.ids.length === 2,
    `quiz_banks 里带 2 个题库 id（${bankCmd && bankCmd.params && bankCmd.params.ids}）`);

  console.log('\n[2.95] 题库弹窗：列表 → 题目 → 新增 / 导入表单');
  d.getElementById('btnBanks').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  const banksOk = await waitFor(
    () => d.querySelectorAll('#banksBody .bank-row').length === 3, 5000,
  );
  ok(banksOk, `题库列表 3 行（实际 ${d.querySelectorAll('#banksBody .bank-row').length}）`);
  ok(!d.getElementById('banksModal').classList.contains('hidden'), '题库弹窗打开了');
  const bankRow0 = d.querySelector('#banksBody .bank-row');
  ok(/语文/.test(bankRow0.textContent), `第一行是语文题库（${bankRow0.querySelector('.bank-title').textContent.trim()}）`);
  ok(!!bankRow0.querySelector('[data-open]'), '每行有「查看题目」入口');
  ok(!!d.getElementById('btnCreateBank'), '有新建题库按钮');
  ok(!!d.getElementById('nbSubject'), '新建时有科目下拉');

  d.querySelector('#banksBody .bank-row [data-open]').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  const detailOk = await waitFor(
    () => d.querySelectorAll('#banksBody .q-row').length >= 3, 5000,
  );
  ok(detailOk, `进到题目列表，题目行 ${d.querySelectorAll('#banksBody .q-row').length} 条`);
  ok(/语文/.test(d.getElementById('banksTitle').textContent),
    `标题换成题库名（${d.getElementById('banksTitle').textContent}）`);
  ok(!!d.getElementById('qfStem') && !!d.getElementById('qfOptions') && !!d.getElementById('qfAnswer'),
    '新增题表单齐全（题干 / 选项 / 答案）');
  ok(!!d.getElementById('qfTpl'), '有补全单词用的模板输入框');
  ok(!!d.getElementById('qfImport'), '有批量导入文本框');
  ok(/题干\|选项/.test(d.querySelector('#banksBody .import-help').textContent),
    '导入说明写了格式，家长不用猜');
  ok(!!d.querySelector('#banksBody [data-edit]') && !!d.querySelector('#banksBody [data-del]'),
    '每题都有编辑 / 删除');

  // 点「编辑」应当回填表单，而不是只弹个提示
  d.querySelector('#banksBody [data-edit]').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  const editOk = await waitFor(() => d.getElementById('qfStem').value.length > 0, 4000);
  ok(editOk, `点编辑回填了题干（${d.getElementById('qfStem').value}）`);
  ok(/保存修改/.test(d.getElementById('btnQfSave').textContent), '按钮变成「保存修改」');
  d.getElementById('btnQfCancel').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await waitFor(() => /添加/.test(d.getElementById('btnQfSave').textContent), 3000);
  ok(/添加/.test(d.getElementById('btnQfSave').textContent), '取消后回到新增态');

  d.getElementById('btnBankBack').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  const banksBackOk = await waitFor(
    () => d.querySelectorAll('#banksBody .bank-row').length === 3, 4000,
  );
  ok(banksBackOk, '「返回题库列表」能回去');
  d.getElementById('banksModal').classList.add('hidden');

  console.log('\n[3] 设备页：翻到前一天，时间轴和事件一起换');
  const evBefore = d.getElementById('dpEvents').innerHTML;
  const subBefore = d.getElementById('tlSub').textContent;
  const canvasBefore = d.getElementById('tlCanvas').innerHTML;
  d.getElementById('btnPrevDay').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  const moved = await waitFor(() => dayInput.value !== today
    && d.getElementById('dpEvents').innerHTML !== evBefore
    && d.getElementById('tlSub').textContent !== subBefore);
  ok(moved, `点 ‹ 后日期变成 ${dayInput.value}`);
  ok(new Date(dayInput.value) < new Date(today), '确实往前了一天');
  ok(d.getElementById('dpEvents').innerHTML !== evBefore, '事件列表跟着换了');
  ok(d.getElementById('tlSub').textContent !== subBefore, '时间轴副标题跟着换了（数据时段变了）');
  ok(d.getElementById('tlCanvas').innerHTML !== canvasBefore, '时间轴画面跟着重画了');
  ok(!d.getElementById('btnNextDay').disabled, '"下一天"按钮恢复可点');

  console.log('\n[4] 设备页：直接选日期 + 回到今天');
  dayInput.value = today;
  dayInput.dispatchEvent(new w.Event('change', { bubbles: true }));
  const backOk = await waitFor(() => d.getElementById('dpEvHint').textContent.includes(today));
  ok(backOk, '手选日期后事件跟着刷新');
  ok(d.getElementById('btnToday').disabled, '回到今天后"今天"按钮再次置灰');

  console.log('\n[5] 全部事件弹窗（按天 + 分页）');
  d.getElementById('btnAllEvents').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  const modalOk = await waitFor(() => !d.getElementById('eventsModal').classList.contains('hidden')
    && d.querySelectorAll('#evList .event').length > 0);
  ok(modalOk, '弹窗打开并渲染出事件');
  ok(d.getElementById('evDay').value === today, '弹窗默认查今天');
  ok(d.getElementById('evDevice').options.length === 4, '设备下拉 = 全部 + 3 台');
  ok(/共 \d+ 条/.test(d.getElementById('evModalSub').textContent), d.getElementById('evModalSub').textContent);
  ok(d.querySelectorAll('#evList .dev-tag').length > 0, '跨设备列表带设备标签');
  const pagerTxt = d.getElementById('evPager').textContent;
  ok(/共 \d+ 条/.test(pagerTxt), `单页时只显示总数、不显示翻页按钮：${pagerTxt.replace(/\s+/g, ' ').trim()}`);

  console.log('\n[6] 全部事件：切到"全部时间"（数据量大，可以验证翻页）');
  // 注意：不能拿"列表 HTML 变了"当判据。今天的事件本来就是最新的，
  // 事件数 ≥30 时，「全部时间」第 1 页和「今天」第 1 页是同 30 条 —— 看起来"没变"。
  // 用总数（分页条 + 副标题）判断才可靠。
  const pagerBefore = d.getElementById('evPager').textContent;
  d.getElementById('btnEvAllTime').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  // 桩服务是**按需现造**一年的数据，第一次查"全部时间"要 1~2 秒（真实服务端走
  // SQLite 索引是毫秒级），所以这里给足 10 秒，别把慢当成功能没生效。
  const allOk = await waitFor(() => d.getElementById('evModalSub').textContent.includes('全部时间')
    && d.getElementById('evPager').textContent !== pagerBefore, 10000);
  ok(allOk, '"全部时间"状态生效',
    `副标题=${d.getElementById('evModalSub').textContent} 分页=${d.getElementById('evPager').textContent.replace(/\s+/g, ' ')}`);
  ok(d.getElementById('evDay').value === '', '"全部时间"清空日期框');
  ok(d.getElementById('btnEvAllTime').classList.contains('on'), '"全部时间"按钮高亮');

  const nextBtn = d.querySelector('#evPager button[data-p="2"]');
  ok(!!nextBtn, '数据量够多时出现翻页按钮');
  if (nextBtn && !nextBtn.disabled) {
    const page1 = d.getElementById('evList').innerHTML;
    nextBtn.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
    const p2ok = await waitFor(() => d.getElementById('evList').innerHTML !== page1);
    ok(p2ok, '翻到第 2 页内容变了');
    ok(/第 2 \/ \d+ 页/.test(d.getElementById('evPager').textContent), '页码显示第 2 页');
  }

  console.log('\n[7] 关闭弹窗后重新打开，"全部时间"要记住');
  d.getElementById('eventsModal').classList.add('hidden');
  d.getElementById('btnAllEvents').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await sleep(300);
  ok(d.getElementById('evModalSub').textContent.includes('全部时间'), '重开后仍是"全部时间"（不被重置成今天）');
  d.getElementById('btnEvToday').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await waitFor(() => d.getElementById('evModalSub').textContent.includes(today));
  ok(d.getElementById('evModalSub').textContent.includes(today), '点"今天"能回到今天');

  console.log('\n[7.5] 路由：进设备页留一步历史，浏览器后退回列表（而不是退出整个后台）');
  const devId = decodeURIComponent((w.location.hash || '').replace('#/device/', ''));
  ok(!!devId, `地址栏里带设备 hash（${w.location.hash}）`);
  ok(w.history.state && w.history.state.dev === devId, '历史记录里记着这台设备');
  w.history.back();
  const routeBackOk = await waitFor(
    () => d.getElementById('devicePage').classList.contains('hidden'), 3000,
  );
  ok(routeBackOk, '按后退回到设备列表');
  ok(!d.getElementById('app').classList.contains('hidden'), '首页可见（没有把页面整体关掉）');
  ok(!w.location.hash, `地址栏 hash 已清掉（${w.location.hash || '空'}）`);

  console.log('\n[8] 未捕获异常');
  ok(errors.length === 0, '没有 JS 异常', errors.join(' / '));

  console.log(`\n结果：通过 ${pass} 项，失败 ${fail} 项`);
  w.close();
  process.exit(fail ? 1 : 0);
})().catch((e) => {
  console.error('自检崩溃：', e);
  process.exit(2);
});
