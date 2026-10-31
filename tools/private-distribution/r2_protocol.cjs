'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const APP_ID = 'dev.ctrlcv.markdownhelperkeyboard';
const CERTIFICATE = '2179af3733bbfbe959a5d53196210880c6fb4a666bef5b3113e0f6cee1cf1c4e';
const BUCKET = 'apk-private-store';
const BASE_URL = 'https://apk.ctrlcv.dev';
const TYPES = { 'sumire.apk': 'application/vnd.android.package-archive',
  'sumire.apk.sha256': 'text/plain', 'build.json': 'application/json' };

function assert(condition, message) { if (!condition) throw new Error(message); }
function validateRequest(request) {
  const record = request.record;
  assert(record?.application_id === APP_ID && record.certificate_sha256 === CERTIFICATE, 'Unexpected APK identity.');
  assert(Number.isSafeInteger(record.version_code) && record.version_code > 0, 'Invalid version code.');
  assert(typeof record.version_name === 'string' && record.version_name.length < 100, 'Invalid version name.');
  assert(/^[a-f0-9]{40}$/.test(record.source_commit), 'Invalid source commit.');
  assert(/^[a-f0-9]{64}$/.test(record.apk_sha256), 'Invalid APK checksum.');
  assert(Number.isSafeInteger(record.size_bytes) && record.size_bytes > 0 && record.size_bytes < 500 * 1024 * 1024, 'Invalid APK size.');
  assert(Array.isArray(request.files) && request.files.length === 3, 'Exactly three upload files are required.');
  assert(Object.keys(TYPES).every(name => request.files.filter(file => file.name === name).length === 1), 'Unexpected upload file names.');
  for (const file of request.files) {
    assert(/^[a-f0-9]{64}$/.test(file.sha256) && Number.isSafeInteger(file.size) && file.size > 0, 'Invalid file checksum or size.');
    assert(file.name === 'sumire.apk' || file.size < 100000, 'Metadata is too large.');
  }
  const apk = request.files.find(file => file.name === 'sumire.apk');
  assert(apk.sha256 === record.apk_sha256 && apk.size === record.size_bytes, 'APK metadata differs from the upload request.');
  return request;
}

function prefixFor(request, id) {
  assert(/^[a-f0-9-]{36}$/.test(id), 'Invalid request ID.');
  return `builds/${request.record.version_code}-${request.record.source_commit.slice(0, 12)}-${id}`;
}

async function fileDigest(file) {
  const hash = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}

async function readBundle(directory) {
  const record = JSON.parse(await fs.promises.readFile(path.join(directory, 'build.json'), 'utf8'));
  const files = [];
  for (const name of Object.keys(TYPES)) {
    const file = path.join(directory, name);
    const info = await fs.promises.lstat(file);
    assert(info.isFile() && !info.isSymbolicLink(), 'Bundle files must be regular files.');
    files.push({ name, size: info.size, sha256: await fileDigest(file) });
  }
  const request = validateRequest({ record, files });
  const checksum = (await fs.promises.readFile(path.join(directory, 'sumire.apk.sha256'), 'utf8')).trim();
  assert(checksum === `${record.apk_sha256}  sumire.apk`, 'The checksum file does not match the APK.');
  return request;
}

function seal(publicKeyBase64, value) {
  const key = crypto.createPublicKey({ key: Buffer.from(publicKeyBase64, 'base64'), type: 'spki', format: 'der' });
  assert(key.asymmetricKeyType === 'rsa' && key.asymmetricKeyDetails.modulusLength >= 3072, 'A 3072-bit or larger RSA recipient key is required.');
  const secret = crypto.randomBytes(32);
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', secret, nonce);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  return { version: 1, key: crypto.publicEncrypt({ key, oaepHash: 'sha256' }, secret).toString('base64'),
    nonce: nonce.toString('base64'), tag: cipher.getAuthTag().toString('base64'), ciphertext: ciphertext.toString('base64') };
}

function unseal(privateKey, envelope) {
  assert(envelope.version === 1, 'Unknown upload grant format.');
  const secret = crypto.privateDecrypt({ key: privateKey, oaepHash: 'sha256' }, Buffer.from(envelope.key, 'base64'));
  const decipher = crypto.createDecipheriv('aes-256-gcm', secret, Buffer.from(envelope.nonce, 'base64'));
  decipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
  return JSON.parse(Buffer.concat([decipher.update(Buffer.from(envelope.ciphertext, 'base64')), decipher.final()]).toString('utf8'));
}

function validateGrant(grant, request, id) {
  const prefix = prefixFor(request, id);
  assert(grant.id === id && grant.prefix === prefix && grant.apk_sha256 === request.record.apk_sha256, 'The upload grant belongs to another request.');
  const remaining = Date.parse(grant.expires_at) - Date.now();
  assert(remaining > 0 && remaining <= 16 * 60 * 1000, 'The upload grant is expired or has an invalid lifetime.');
  for (const name of Object.keys(TYPES)) {
    const url = new URL(grant.urls[name]);
    assert(url.protocol === 'https:' && /^[a-f0-9]{32}\.r2\.cloudflarestorage\.com$/.test(url.hostname), 'Unexpected R2 upload endpoint.');
    assert(url.pathname === `/${BUCKET}/${prefix}/${name}` && !url.username && !url.password && !url.hash, 'Unexpected R2 object path.');
  }
  return grant;
}

function latestCondition(previous, next, relation) {
  if (!previous) return { IfNoneMatch: '*' };
  const record = previous.record;
  assert(typeof previous.etag === 'string' && previous.etag.length > 0, 'Missing latest ETag.');
  assert(Number.isSafeInteger(record.version_code) && record.version_code > 0 &&
    /^[a-f0-9]{40}$/.test(record.source_commit || '') && /^[a-f0-9]{64}$/.test(record.apk_sha256 || ''),
    'Existing latest metadata is invalid.');
  assert(record.version_code <= next.version_code, 'An older APK cannot replace the latest version pointer.');
  assert(record.version_code !== next.version_code || record.apk_sha256 === next.apk_sha256,
    'The same version code cannot identify a different APK.');
  assert(record.source_commit === next.source_commit || relation === 'ahead',
    'The new source commit must descend from the last published commit.');
  return { IfMatch: previous.etag };
}

async function commitRelation(base, head) {
  if (base === head) return 'identical';
  assert(process.env.GITHUB_TOKEN, 'A read-only GitHub token is required for ancestry verification.');
  const response = await fetch(`https://api.github.com/repos/kiwi-93/JapaneseKeyboard/compare/${base}...${head}`,
    { headers: { Authorization: `Bearer ${process.env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json',
      'User-Agent': 'sumire-private-distribution' }, signal: AbortSignal.timeout(20000) });
  assert(response.ok, 'Could not verify published commit ancestry.');
  return (await response.json()).status;
}

async function assertProtected(objectKey) {
  const response = await fetch(`${BASE_URL}/${objectKey}`, { redirect: 'manual', signal: AbortSignal.timeout(20000) });
  await response.body?.cancel();
  const location = new URL(response.headers.get('location') || '/', BASE_URL);
  assert([302, 303, 307].includes(response.status) && location.protocol === 'https:' &&
    location.hostname === 'ctrlcv.cloudflareaccess.com' && location.pathname.startsWith('/cdn-cgi/access/login/'),
  'The download path is not protected by the existing Cloudflare Access login.');
}

module.exports = { APP_ID, CERTIFICATE, BUCKET, BASE_URL, TYPES, assert, validateRequest,
  prefixFor, fileDigest, readBundle, seal, unseal, validateGrant, assertProtected, latestCondition, commitRelation };
