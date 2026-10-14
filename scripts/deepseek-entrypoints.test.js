'use strict';

const test = require('node:test');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

test('DeepSeek service entrypoints pass the Node syntax gate', () => {
  for (const file of ['deepseek-web-bridge.js', 'deepseek-bridge-proxy.js']) {
    execFileSync(process.execPath, ['--check', path.join(__dirname, file)], {
      stdio: 'pipe'
    });
  }
});
