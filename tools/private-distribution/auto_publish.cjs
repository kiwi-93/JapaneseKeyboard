'use strict';
// Executed only from the fixed, reviewed publisher commit, never from the built source.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const p = require('./r2_protocol.cjs');
const { S3Client, PutObjectCommand, GetObjectCommand } = require('@aws-sdk/client-s3');

async function main() {
  p.assert(process.env.GITHUB_REPOSITORY === 'kiwi-93/JapaneseKeyboard', 'Unexpected repository.');
  p.assert(/^[a-f0-9]{32}$/.test(process.env.CF_ACCOUNT_ID || ''), 'Invalid Cloudflare account.');
  const dir = process.env.BUNDLE_DIR;
  const request = await p.readBundle(dir);
  p.assert(request.record.source_commit === process.env.SOURCE_SHA, 'Bundle commit mismatch.');
  p.assert(request.record.version_code === Number(process.env.EXPECTED_VERSION_CODE), 'Bundle version mismatch.');
  const id = crypto.randomUUID();
  const prefix = p.prefixFor(request, id);
  const s3 = new S3Client({ region: 'auto', endpoint: `https://${process.env.CF_ACCOUNT_ID}.r2.cloudflarestorage.com`,
    forcePathStyle: true, requestChecksumCalculation: 'WHEN_REQUIRED', responseChecksumValidation: 'WHEN_REQUIRED' });
  for (const name of Object.keys(p.TYPES)) await p.assertProtected(`${prefix}/${name}`);
  await p.assertProtected('latest.json');
  let previous;
  try {
    const object = await s3.send(new GetObjectCommand({ Bucket: p.BUCKET, Key: 'latest.json' }));
    previous = { record: JSON.parse(await object.Body.transformToString()), etag: object.ETag };
  } catch (error) { if (error.$metadata?.httpStatusCode !== 404) throw error; }
  const relation = previous ? await p.commitRelation(previous.record.source_commit, request.record.source_commit) : null;
  const condition = p.latestCondition(previous, request.record, relation);
  for (const file of request.files) {
    await s3.send(new PutObjectCommand({ Bucket: p.BUCKET, Key: `${prefix}/${file.name}`,
      Body: fs.createReadStream(path.join(dir, file.name)), ContentLength: file.size,
      ContentType: p.TYPES[file.name], CacheControl: 'private, no-store', IfNoneMatch: '*' }));
    const object = await s3.send(new GetObjectCommand({ Bucket: p.BUCKET, Key: `${prefix}/${file.name}` }));
    p.assert(object.ContentLength === file.size, `Uploaded ${file.name} size mismatch.`);
    const hash = crypto.createHash('sha256');
    for await (const chunk of object.Body) hash.update(chunk);
    p.assert(hash.digest('hex') === file.sha256, `Uploaded ${file.name} checksum mismatch.`);
  }
  for (const name of Object.keys(p.TYPES)) await p.assertProtected(`${prefix}/${name}`);
  await p.assertProtected('latest.json');
  const latest = { ...request.record, upstream_sha: process.env.UPSTREAM_SHA,
    apk_url: `${p.BASE_URL}/${prefix}/sumire.apk`, checksum_url: `${p.BASE_URL}/${prefix}/sumire.apk.sha256`,
    build_info_url: `${p.BASE_URL}/${prefix}/build.json`, uploaded_at: new Date().toISOString(),
    workflow_url: `https://github.com/kiwi-93/JapaneseKeyboard/actions/runs/${process.env.GITHUB_RUN_ID}` };
  // Complete local output I/O before the final, conditional publication operation.
  await fs.promises.appendFile(process.env.GITHUB_OUTPUT, `apk_url=${latest.apk_url}\n`);
  await s3.send(new PutObjectCommand({ Bucket: p.BUCKET, Key: 'latest.json', ...condition,
    Body: JSON.stringify(latest, null, 2) + '\n', ContentType: 'application/json', CacheControl: 'private, no-store' }));
  console.log(`Private APK URL: ${latest.apk_url}`);
}
main().catch(error => {
  console.error(`Private distribution stopped (${error.$metadata?.httpStatusCode || error.name || 'error'}).`);
  if (!error.$metadata && error.name === 'Error') console.error(error.message);
  process.exitCode = 1;
});
