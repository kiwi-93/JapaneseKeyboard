'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const protocol = require('./r2_protocol.cjs');
const runner = path.join(__dirname, 'auto_publish.cjs');
const hash = x => crypto.createHash('sha256').update(x).digest('hex');

function simulate(mode) {
  const tempRoot = fs.realpathSync(os.tmpdir());
  const dir = fs.mkdtempSync(path.join(tempRoot, 'sumire-publisher-test-'));
  assert.equal(path.dirname(fs.realpathSync(dir)), tempRoot);
  try {
    const apk = Buffer.from('test-only APK');
    const record = { application_id: protocol.APP_ID, certificate_sha256: protocol.CERTIFICATE,
      version_code: 1001, version_name: 'test', source_commit: 'a'.repeat(40), apk_sha256: hash(apk), size_bytes: apk.length };
    fs.writeFileSync(path.join(dir, 'sumire.apk'), apk);
    fs.writeFileSync(path.join(dir, 'sumire.apk.sha256'), `${record.apk_sha256}  sumire.apk\n`);
    fs.writeFileSync(path.join(dir, 'build.json'), JSON.stringify(record));
    const harness = `
      const Module = require('module'); const fs = require('fs'); const { Readable } = require('stream');
      const original = Module._load; const mode = process.env.TEST_MODE; const record = JSON.parse(process.env.TEST_RECORD);
      let previous = { ...record, version_code: mode === 'rollback' ? 1002 : 1000 };
      if (mode === 'collision') previous = { ...record, apk_sha256: 'b'.repeat(64) };
      const objects = new Map([['latest.json', Buffer.from(JSON.stringify(previous))]]); const writes=[];
      const body = b => { const stream=Readable.from([b]); stream.transformToString=async()=>b.toString(); return stream; };
      class GetObjectCommand { constructor(input){ this.input=input; } }
      class PutObjectCommand { constructor(input){ this.input=input; } }
      class S3Client { async send(cmd) {
        const key=cmd.input.Key;
        if(cmd instanceof GetObjectCommand) {
          if(!objects.has(key)) { const e=new Error('missing'); e.$metadata={httpStatusCode:404}; throw e; }
          let data=objects.get(key); if(key.endsWith('/sumire.apk') && mode==='corrupt') data=Buffer.alloc(data.length);
          if(key.endsWith('/sumire.apk') && mode==='badsize') data=Buffer.from('wrong');
          return {Body:body(data), ETag:'"old"', ContentLength:data.length};
        }
        writes.push({key, condition:cmd.input.IfMatch || cmd.input.IfNoneMatch});
        if(mode==='race' && key==='latest.json') { const e=new Error('race'); e.$metadata={httpStatusCode:412}; throw e; }
        let data=cmd.input.Body; if(typeof data!=='string') { const chunks=[]; for await (const c of data) chunks.push(c); data=Buffer.concat(chunks); }
        objects.set(key,Buffer.from(data)); return {};
      }}
      Module._load=function(request, ...rest) { if(request==='@aws-sdk/client-s3') return {S3Client,GetObjectCommand,PutObjectCommand}; return original.call(this,request,...rest); };
      global.fetch=async url=>mode==='unprotected' && url.endsWith('/build.json') ? new Response('public',{status:200}) : new Response(null,{status:302,headers:{location:'https://ctrlcv.cloudflareaccess.com/cdn-cgi/access/login/test'}});
      process.on('exit',()=>fs.writeFileSync(process.env.TEST_STATE,JSON.stringify(writes)));
      require(process.env.TEST_RUNNER);
    `;
    const state = path.join(dir, 'state.json');
    const result = spawnSync(process.execPath, ['-e', harness], { encoding: 'utf8', env: { ...process.env,
      TEST_MODE: mode, TEST_RECORD: JSON.stringify(record), TEST_STATE: state, TEST_RUNNER: runner,
      GITHUB_REPOSITORY: 'kiwi-93/JapaneseKeyboard', CF_ACCOUNT_ID: 'c'.repeat(32), BUNDLE_DIR: dir,
      SOURCE_SHA: record.source_commit, UPSTREAM_SHA: 'd'.repeat(40), EXPECTED_VERSION_CODE: String(record.version_code),
      GITHUB_RUN_ID: '1', GITHUB_OUTPUT: mode === 'badoutput' ? path.join(dir, 'missing', 'output.txt') : path.join(dir, 'output.txt') } });
    return { status: result.status, stderr: result.stderr, writes: JSON.parse(fs.readFileSync(state, 'utf8')) };
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
}

test('automatic publishing verifies all files before conditional latest update', () => {
  const result = simulate('success');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.writes.length, 4);
  assert.equal(result.writes.at(-1).key, 'latest.json');
  assert.equal(result.writes.at(-1).condition, '"old"');
  assert(result.writes.slice(0, 3).every(write => write.condition === '*'));
});

test('corruption, rollback, collisions and competing updates fail closed', () => {
  for (const mode of ['corrupt', 'badsize', 'unprotected', 'badoutput', 'rollback', 'collision', 'race']) {
    const result = simulate(mode);
    assert.notEqual(result.status, 0, mode);
    if (mode !== 'race') assert(!result.writes.some(write => write.key === 'latest.json'), mode);
  }
});
