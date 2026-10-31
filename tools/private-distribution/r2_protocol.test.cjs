'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const p = require('./r2_protocol.cjs');
const id = 'c611c747-4f85-4652-8d86-a218c183bff3';
function request() {
  return { record: { application_id: p.APP_ID, certificate_sha256: p.CERTIFICATE,
    version_code: 835, version_name: '1.7.139-fork.2', source_commit: 'a'.repeat(40),
    apk_sha256: 'b'.repeat(64), size_bytes: 123 },
    files: Object.keys(p.TYPES).map((name, index) => ({ name, size: index === 0 ? 123 : 10,
      sha256: index === 0 ? 'b'.repeat(64) : 'c'.repeat(64) })) };
}
function grant() {
  const req = request();
  const prefix = p.prefixFor(req, id);
  return { id, prefix, apk_sha256: req.record.apk_sha256, expires_at: new Date(Date.now() + 890000).toISOString(),
    urls: Object.fromEntries(Object.keys(p.TYPES).map(name => [name,
      `https://${'a'.repeat(32)}.r2.cloudflarestorage.com/${p.BUCKET}/${prefix}/${name}?signature=test`])) };
}

test('only the existing fork identity and the three expected files are allowed', () => {
  p.validateRequest(request());
  const wrongApp = request(); wrongApp.record.application_id = 'another.app';
  assert.throws(() => p.validateRequest(wrongApp));
  const privateFile = request(); privateFile.files[1].name = 'private-key.pem';
  assert.throws(() => p.validateRequest(privateFile));
  const mismatch = request(); mismatch.files[0].sha256 = 'd'.repeat(64);
  assert.throws(() => p.validateRequest(mismatch));
});

test('an encrypted grant is readable only with the requesting computer key', () => {
  const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 3072 });
  const publicKey = keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  const value = grant();
  const encrypted = p.seal(publicKey, value);
  assert(!JSON.stringify(encrypted).includes('cloudflarestorage.com'));
  assert.deepEqual(p.unseal(keys.privateKey, encrypted), value);
  const other = crypto.generateKeyPairSync('rsa', { modulusLength: 3072 });
  assert.throws(() => p.unseal(other.privateKey, encrypted));
  encrypted.tag = crypto.randomBytes(16).toString('base64');
  assert.throws(() => p.unseal(keys.privateKey, encrypted));
});

test('grant expiry, request binding, endpoint and object path are checked', () => {
  p.validateGrant(grant(), request(), id);
  const expired = grant(); expired.expires_at = new Date(Date.now() - 1000).toISOString();
  assert.throws(() => p.validateGrant(expired, request(), id));
  const other = grant(); other.id = crypto.randomUUID();
  assert.throws(() => p.validateGrant(other, request(), id));
  const publicUpload = grant(); publicUpload.urls['sumire.apk'] = 'https://example.invalid/sumire.apk';
  assert.throws(() => p.validateGrant(publicUpload, request(), id));
  const wrongObject = grant(); wrongObject.urls['sumire.apk'] =
    `https://${'a'.repeat(32)}.r2.cloudflarestorage.com/${p.BUCKET}/unexpected.apk`;
  assert.throws(() => p.validateGrant(wrongObject, request(), id));
});

test('an unprotected download path is rejected and the existing Access redirect is accepted', async () => {
  const original = global.fetch;
  try {
    global.fetch = async () => new Response('public', { status: 200 });
    await assert.rejects(p.assertProtected('example.apk'));
    global.fetch = async () => new Response(null, { status: 302,
      headers: { location: 'https://ctrlcv.cloudflareaccess.com/cdn-cgi/access/login/apk.ctrlcv.dev' } });
    await p.assertProtected('example.apk');
    global.fetch = async () => new Response(null, { status: 302,
      headers: { location: 'https://unrelated.cloudflareaccess.com/cdn-cgi/access/login/' } });
    await assert.rejects(p.assertProtected('example.apk'));
  } finally { global.fetch = original; }
});
