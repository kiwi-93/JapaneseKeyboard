'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const p = require('./r2_protocol.cjs');
const { S3Client, ListObjectsV2Command, PutObjectCommand, HeadObjectCommand, GetObjectCommand } = require('@aws-sdk/client-s3');
const { getSignedUrl } = require('@aws-sdk/s3-request-presigner');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function main() {
  p.assert(process.env.GITHUB_REPOSITORY === 'kiwi-93/JapaneseKeyboard' && process.env.GITHUB_REF === 'refs/heads/custom', 'This workflow only runs on the fork custom branch.');
  p.assert(/^[a-f0-9]{32}$/.test(process.env.CF_ACCOUNT_ID || ''), 'CF_ACCOUNT_ID must be the 32-character account ID.');
  p.assert(process.env.AWS_ACCESS_KEY_ID && process.env.AWS_SECRET_ACCESS_KEY, 'R2 credentials are missing.');
  const request = p.validateRequest(JSON.parse(process.env.UPLOAD_MANIFEST));
  const id = process.env.UPLOAD_REQUEST_ID;
  const prefix = p.prefixFor(request, id);
  const s3 = new S3Client({ region: 'auto', endpoint: `https://${process.env.CF_ACCOUNT_ID}.r2.cloudflarestorage.com`,
    forcePathStyle: true, requestChecksumCalculation: 'WHEN_REQUIRED', responseChecksumValidation: 'WHEN_REQUIRED' });
  await p.assertProtected(`${prefix}/sumire.apk`);
  await p.assertProtected('latest.json');
  if (process.argv[2] === 'grant') {
    // Validate the existing bucket and supplied object credentials without logging object names.
    await s3.send(new ListObjectsV2Command({ Bucket: p.BUCKET, MaxKeys: 1 }));
    const urls = {};
    for (const file of request.files) {
      urls[file.name] = await getSignedUrl(s3, new PutObjectCommand({ Bucket: p.BUCKET,
        Key: `${prefix}/${file.name}`, ContentType: p.TYPES[file.name], ContentLength: file.size,
        CacheControl: 'private, no-store' }), { expiresIn: 900 });
    }
    const grant = { id, prefix, apk_sha256: request.record.apk_sha256,
      expires_at: new Date(Date.now() + 900000).toISOString(), urls };
    const envelope = p.seal(process.env.UPLOAD_RECIPIENT_KEY, grant);
    const destination = path.join(process.env.RUNNER_TEMP, 'sealed-r2-upload');
    await fs.promises.mkdir(destination, { recursive: true });
    await fs.promises.writeFile(path.join(destination, 'envelope.json'), JSON.stringify(envelope), { mode: 0o600 });
    console.log('The temporary upload grant is encrypted for the requesting computer.');
    return;
  }
  p.assert(process.argv[2] === 'verify', 'Unknown upload phase.');
  let ready = false;
  for (let attempt = 0; attempt < 144; attempt++) {
    const results = await Promise.all(request.files.map(async file => {
      try {
        const info = await s3.send(new HeadObjectCommand({ Bucket: p.BUCKET, Key: `${prefix}/${file.name}` }));
        return info.ContentLength === file.size;
      } catch (error) {
        if (error.$metadata?.httpStatusCode === 404) return false;
        throw error;
      }
    }));
    if (results.every(Boolean)) { ready = true; break; }
    if (attempt % 6 === 0) console.log('Waiting for the existing APK and metadata to be uploaded.');
    await sleep(5000);
  }
  p.assert(ready, 'The upload did not complete in time; latest.json was not changed.');
  for (const file of request.files) {
    const object = await s3.send(new GetObjectCommand({ Bucket: p.BUCKET, Key: `${prefix}/${file.name}` }));
    const hash = crypto.createHash('sha256');
    for await (const chunk of object.Body) hash.update(chunk);
    p.assert(hash.digest('hex') === file.sha256, `The uploaded ${file.name} checksum does not match.`);
    console.log(`Verified ${file.name}.`);
  }
  try {
    const previous = await s3.send(new GetObjectCommand({ Bucket: p.BUCKET, Key: 'latest.json' }));
    const record = JSON.parse(await previous.Body.transformToString());
    p.assert(!Number.isSafeInteger(record.version_code) || record.version_code <= request.record.version_code,
      'An older APK cannot replace the latest version pointer.');
  } catch (error) { if (error.$metadata?.httpStatusCode !== 404) throw error; }
  await p.assertProtected(`${prefix}/sumire.apk`);
  await p.assertProtected('latest.json');
  const latest = { ...request.record, apk_url: `${p.BASE_URL}/${prefix}/sumire.apk`,
    checksum_url: `${p.BASE_URL}/${prefix}/sumire.apk.sha256`, build_info_url: `${p.BASE_URL}/${prefix}/build.json`,
    uploaded_at: new Date().toISOString(), workflow_url: `https://github.com/kiwi-93/JapaneseKeyboard/actions/runs/${process.env.GITHUB_RUN_ID}` };
  await s3.send(new PutObjectCommand({ Bucket: p.BUCKET, Key: 'latest.json',
    Body: JSON.stringify(latest, null, 2) + '\n', ContentType: 'application/json', CacheControl: 'private, no-store' }));
  await fs.promises.appendFile(process.env.GITHUB_STEP_SUMMARY,
    `## Existing APK published privately\n\nVersion: ${request.record.version_code}\n\n[Download APK](${latest.apk_url})\n\nSHA-256: \`${request.record.apk_sha256}\`\n\nNo APK build or GitHub Release was performed.\n`);
  console.log(`Private APK URL: ${latest.apk_url}`);
}

main().catch(error => {
  // AWS errors may contain request URLs. Do not dump errors, request headers or presigned URLs.
  console.error(`Private R2 upload stopped (${error.$metadata?.httpStatusCode || error.name || 'error'}).`);
  if (!error.$metadata && error.name === 'Error') console.error(error.message);
  process.exitCode = 1;
});
