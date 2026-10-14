'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const test = require('node:test');

function executable(file, body) {
  fs.writeFileSync(file, body, { mode: 0o755 });
}

test('migration health gate authenticates when facade key is configured', () => {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-migration-auth-'));
  const capturePath = path.join(temporaryDirectory, 'curl-args.txt');
  executable(path.join(temporaryDirectory, 'lsof'), '#!/bin/sh\nexit 1\n');
  executable(path.join(temporaryDirectory, 'launchctl'), '#!/bin/sh\nexit 1\n');
  executable(path.join(temporaryDirectory, 'mvn'), '#!/bin/sh\nexit 0\n');
  executable(path.join(temporaryDirectory, 'curl'), [
    '#!/bin/sh',
    'printf "%s\\n" "$@" > "$CURL_CAPTURE_PATH"',
    'printf \'{"ok":true,"busy":false,"activeRequest":null}\'',
    ''
  ].join('\n'));

  try {
    execFileSync('/bin/bash', [path.join(__dirname, 'run-stock-watch-v6-migration.sh'), 'migrate'], {
      cwd: path.join(__dirname, '..'),
      env: {
        ...process.env,
        PATH: `${temporaryDirectory}:${process.env.PATH}`,
        AI_MEETING_ENV_FILE: path.join(temporaryDirectory, 'missing.env'),
        DEEPSEEK_OPENAI_FACADE_API_KEY: 'migration-secret',
        CURL_CAPTURE_PATH: capturePath
      },
      stdio: 'pipe'
    });

    const args = fs.readFileSync(capturePath, 'utf8').split('\n').filter(Boolean);
    assert.ok(args.includes('-H'));
    assert.ok(args.includes('Authorization: Bearer migration-secret'));
    assert.ok(args.some(value => value.endsWith('/health')));
  } finally {
    fs.rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});
