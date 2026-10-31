#!/usr/bin/env node
/**
 * 网络体检：切换网络后跑一下，看 GitHub 通没通。
 *   node netcheck.js
 */
const net = require('net');

const TARGETS = [
  ['github.com', '主站（注册 / 登录 / 生成 token 要用）', true],
  ['api.github.com', 'API（上传代码 / 下载 APK 要用）', true],
  ['objects.githubusercontent.com', '产物下载', false],
  ['gitlab.com', 'GitLab（备选平台）', false],
  ['gitee.com', 'Gitee（备选平台）', false],
];

function probe(host, timeout = 5000) {
  return new Promise((resolve) => {
    const t = Date.now();
    const s = net.connect({ host, port: 443, timeout });
    const done = (r) => { s.destroy(); resolve({ ...r, ms: Date.now() - t }); };
    s.on('connect', () => {
      // TCP 通了再验一次 TLS 层：发个 HEAD 看能不能拿到响应
      done({ ok: true });
    });
    s.on('timeout', () => done({ ok: false, err: '超时' }));
    s.on('error', (e) => done({ ok: false, err: e.code }));
  });
}

async function httpsOK(url) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(6000), redirect: 'manual' });
    return { ok: true, status: r.status };
  } catch (e) {
    return { ok: false, err: e.name };
  }
}

(async () => {
  console.log('网络体检\n');
  let mainOK = false;

  for (const [host, desc, critical] of TARGETS) {
    const tcp = await probe(host);
    let tls = null;
    if (tcp.ok) tls = await httpsOK('https://' + host + '/');

    const ok = tcp.ok && tls && tls.ok;
    const mark = ok ? '✅' : '❌';
    const detail = !tcp.ok
      ? `TCP ${tcp.err}`
      : (!tls.ok ? `TLS ${tls.err}` : `HTTP ${tls.status}`);

    console.log(`  ${mark} ${host.padEnd(34)} ${detail.padEnd(16)} ${desc}`);
    if (critical && host === 'github.com') mainOK = ok;
  }

  console.log('');
  if (mainOK) {
    console.log('  结论：GitHub 主站可以打开，去注册 / 生成 token 吧。');
  } else {
    console.log('  结论：GitHub 主站还是打不开。');
    console.log('  试试：① 切换 WiFi ↔ 移动流量   ② 开飞行模式 10 秒再关');
    console.log('        ③ 有加速器/VPN 的话打开它    ④ 换个时间段再试');
  }
})();
