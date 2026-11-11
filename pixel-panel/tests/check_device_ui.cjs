// Silent headless browser checks. All HTTP is intercepted; no printer is contacted.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.VORON_PLAYWRIGHT || path.join(process.env.LOCALAPPDATA, 'VoronPanel/browser-tools/node_modules/playwright'));
const html = fs.readFileSync(path.join(__dirname, '../app/assets/device.html'), 'utf8');

(async () => {
  const browser = await chromium.launch({executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 800}});
    const errors = [], requests = [];
    page.on('pageerror', error => errors.push(error.message));
    let delay = 0;
    const state = {
      webhooks: {state: 'ready'}, print_stats: {state: 'standby', filename: '', print_duration: 0},
      virtual_sdcard: {progress: .27}, extruder: {temperature: 239.6, target: 240},
      heater_bed: {temperature: 99.8, target: 100}, toolhead: {homed_axes: 'xyz'},
      gcode_move: {homing_origin: [0, 0, -.025, 0]}
    };
    await page.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      assert.equal(url.hostname, 'voron-test', 'Unexpected external request');
      if (url.pathname === '/') return route.fulfill({contentType: 'text/html', body: html});
      if (url.pathname === '/health') return route.fulfill({json: {camera: 'Monitoring stopped', frame_age_ms: -1, root_required: false}});
      if (request.method() === 'GET') return route.fulfill({json: {result: {status: structuredClone(state)}}});
      requests.push({path: url.pathname, body: request.postDataJSON()});
      if (url.pathname.endsWith('/emergency_stop')) state.webhooks.state = 'shutdown';
      else {
        if (url.pathname.endsWith('/pause')) state.print_stats.state = 'paused';
        if (url.pathname.endsWith('/resume')) state.print_stats.state = 'printing';
        if (url.pathname.endsWith('/cancel')) state.print_stats.state = 'cancelled';
        if (delay) await new Promise(resolve => setTimeout(resolve, delay));
      }
      await route.fulfill({json: {result: 'ok'}});
    });
    await page.goto('http://voron-test/?token=fixture-only');
    await page.locator('#connection-text').filter({hasText: 'Printer connected'}).waitFor();
    assert.equal(await page.locator('#job-actions').isVisible(), false);
    assert.equal(await page.locator('#z-up').isDisabled(), true);
    state.print_stats = {state: 'printing', filename: 'HeatCore_4_toolhead_front_ABS_0.2mm.gcode', print_duration: 3760};
    await page.evaluate(() => refresh());
    await page.locator('#pause').click();
    await page.locator('#resume').waitFor({state: 'visible'});
    assert.equal(requests.at(-1).path, '/printer/print/pause');
    await page.locator('#resume').click();
    state.print_stats.filename = 'another-job.gcode';
    await page.locator('#confirm-action').click();
    await page.locator('#action-message').filter({hasText: 'Nothing sent'}).waitFor();
    assert.equal(requests.length, 1, 'Changed job was resumed');
    await page.locator('#resume').click();
    await page.locator('#confirm-action').click();
    await page.locator('#pause').waitFor({state: 'visible'});
    assert.equal(requests.at(-1).path, '/printer/print/resume');
    state.toolhead.homed_axes = '';
    await page.locator('#z-up').click();
    await page.locator('#action-message').filter({hasText: 'Nothing sent'}).waitFor();
    assert.equal(requests.length, 2, 'Stale homing allowed Z command');
    state.toolhead.homed_axes = 'xyz';
    await page.evaluate(() => refresh());
    const output = path.join(__dirname, '../.build/design/device');
    fs.mkdirSync(output, {recursive: true});
    await page.screenshot({path: path.join(output, 'desktop.png'), fullPage: true});
    await page.setViewportSize({width: 390, height: 844});
    const small = await page.locator('button:visible').evaluateAll(nodes => nodes.filter(node => {
      const r = node.getBoundingClientRect(); return r.height < 48 || r.width < 48;
    }).map(node => node.textContent));
    assert.deepEqual(small, [], 'Undersized browser touch targets');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({path: path.join(output, 'mobile.png'), fullPage: true});
    delay = 5000;
    await page.locator('#z-up').click();
    await page.waitForFunction(() => document.querySelector('#z-up').disabled);
    await page.locator('#emergency').click();
    await page.locator('#action-message').filter({hasText: 'Emergency stop sent'}).waitFor();
    await page.waitForTimeout(5500);
    assert.match(await page.locator('#action-message').innerText(), /Emergency stop sent/);
    assert.equal(requests.at(-1).path, '/printer/emergency_stop');
    assert.deepEqual(errors, []);
    console.log('PASS: browser idle/printing/pause/resume, changed-job and homing checks, 48px mobile targets, no overflow, independent sticky E-stop');
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode = 1;});
