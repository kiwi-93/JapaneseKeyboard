'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const p = require('./r2_protocol.cjs');
const REPOSITORY = 'kiwi-93/JapaneseKeyboard';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function gh(...args) {
  return execFileSync('gh', args, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], maxBuffer: 2000000 });
}
function api(endpoint) { return JSON.parse(gh('api', endpoint)); }

async function upload(directory) {
  const request = await p.readBundle(directory);
  const id = crypto.randomUUID();
  const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 3072 });
  const recipient = keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  gh('workflow', 'run', 'r2-upload.yml', '--repo', REPOSITORY, '--ref', 'custom',
    '-f', `request_id=${id}`, '-f', `recipient_key=${recipient}`, '-f', `manifest=${JSON.stringify(request)}`);
  console.log('GitHubで、既存APKのアップロードを準備しています。');
  let run;
  for (let attempt = 0; attempt < 120; attempt++) {
    const runs = api(`repos/${REPOSITORY}/actions/workflows/r2-upload.yml/runs?event=workflow_dispatch&per_page=30`).workflow_runs;
    run = runs.find(item => item.display_title === `R2 upload ${id}`);
    if (run) break;
    await sleep(5000);
  }
  p.assert(run, 'The upload workflow did not appear.');
  console.log(`実行ログ: ${run.html_url}`);
  let artifact;
  for (let attempt = 0; attempt < 120; attempt++) {
    const state = api(`repos/${REPOSITORY}/actions/runs/${run.id}`);
    artifact = api(`repos/${REPOSITORY}/actions/runs/${run.id}/artifacts`).artifacts
      .find(item => item.name === `sealed-r2-upload-${id}` && !item.expired);
    if (artifact) break;
    p.assert(state.status !== 'completed', 'The workflow stopped before preparing the upload. Check the run log.');
    await sleep(5000);
  }
  p.assert(artifact, 'The encrypted upload grant did not appear.');
  const temporary = path.join(directory, '.upload-requests', id);
  await fs.promises.mkdir(temporary, { recursive: true });
  gh('run', 'download', String(run.id), '--repo', REPOSITORY, '--name', artifact.name, '--dir', temporary);
  const encrypted = JSON.parse(await fs.promises.readFile(path.join(temporary, 'envelope.json'), 'utf8'));
  const grant = p.validateGrant(p.unseal(keys.privateKey, encrypted), request, id);
  for (const file of request.files) {
    // Confirm that the file still matches the verified bundle immediately before sending it.
    p.assert(await p.fileDigest(path.join(directory, file.name)) === file.sha256, 'A bundle file changed after preparing the request.');
    console.log(`送信中: ${file.name}`);
    const response = await fetch(grant.urls[file.name], { method: 'PUT', redirect: 'error',
      headers: { 'Content-Type': p.TYPES[file.name], 'Content-Length': String(file.size), 'Cache-Control': 'private, no-store' },
      body: fs.createReadStream(path.join(directory, file.name)), duplex: 'half', signal: AbortSignal.timeout(300000) });
    await response.body?.cancel();
    p.assert(response.ok, `R2 rejected ${file.name} (HTTP ${response.status}).`);
  }
  console.log('R2側のファイル検証を待っています。');
  for (let attempt = 0; attempt < 150; attempt++) {
    const state = api(`repos/${REPOSITORY}/actions/runs/${run.id}`);
    if (state.status === 'completed') {
      p.assert(state.conclusion === 'success', 'The workflow could not verify the upload. latest.json was not updated.');
      const result = { version: request.record.version_name, version_code: request.record.version_code,
        apk_url: `${p.BASE_URL}/${grant.prefix}/sumire.apk`, latest_url: `${p.BASE_URL}/latest.json`,
        sha256: request.record.apk_sha256, workflow_url: run.html_url };
      await fs.promises.writeFile(path.join(directory, 'upload-result.json'), JSON.stringify(result, null, 2) + '\n');
      console.log(`配布先: ${result.apk_url}`);
      console.log('完了しました。ダウンロードには既存のGitHubログインが必要です。');
      return result;
    }
    await sleep(5000);
  }
  throw new Error('Verification is still running. Check the workflow run before retrying.');
}

if (require.main === module) {
  const argument = process.argv.indexOf('--bundle');
  if (argument < 0 || !process.argv[argument + 1]) {
    console.error('Usage: node upload_existing.cjs --bundle <prepared-bundle-directory>');
    process.exitCode = 1;
  } else {
    upload(path.resolve(process.argv[argument + 1])).catch(error => {
      // Do not print fetch error causes, decrypted grants or signed upload URLs.
      console.error(error.message);
      process.exitCode = 1;
    });
  }
}
module.exports = { upload };
