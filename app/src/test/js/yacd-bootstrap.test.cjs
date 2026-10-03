// @author 雾晚
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const template = fs.readFileSync(path.join(__dirname, '../../main/assets/yacd-bootstrap.js'), 'utf8');
const endpoint = 'http://127.0.0.1:9090';
const key = 'yacd.metacubex.one';

function execute(hash, state, query = '', blocked = false) {
  let href = `${endpoint}/ui/${query}${hash}`;
  const storage = {[key]: typeof state === 'string' ? state : JSON.stringify(state), unrelated: 'keep'};
  let replacements = 0;
  const location = {get href() {return href;}};
  const context = {
    window: {location}, URL,
    history: {state: {keep: true}, replaceState(state, title, url) { href = url; replacements++; }},
    localStorage: {
      getItem(k) {return storage[k] || null;},
      setItem(k, value) {if (blocked) throw Error('blocked'); storage[k] = value;}
    },
    document: {addEventListener() {}},
    fetch() {throw Error('bootstrap must not send credentials');}
  };
  const config = {endpoint, secret: 'local-secret-"\\</script>', storageError: 'reload'};
  vm.runInNewContext(template.replace('__WANBOX_YACD_CONFIG__', JSON.stringify(config)), context);
  return {url: new URL(href), state: JSON.parse(storage[key]), storage, replacements, config};
}

for (const hash of ['', '#', '#/backend', '#/backend/', '#/backend?x=1', '#/setup']) {
  test(`healthy native bootstrap repairs ${hash || 'empty'} to bundled overview`, () => {
    const before = {theme: 'dark', hideUnavailableProxies: true, selectedClashAPIConfigIndex: 0,
      clashAPIConfigs: [{baseURL: 'https://custom.example', secret: 'user-secret'}]};
    const result = execute(hash, before);
    assert.equal(result.url.hash, '#/');
    assert.equal(result.state.selectedClashAPIConfigIndex, 1);
    assert.deepEqual(result.state.clashAPIConfigs[0], before.clashAPIConfigs[0]);
    assert.equal(result.state.clashAPIConfigs[1].baseURL, endpoint);
    assert.equal(result.state.clashAPIConfigs[1].secret, result.config.secret);
    assert.equal(result.state.theme, before.theme);
    assert.equal(result.state.hideUnavailableProxies, true);
    assert.equal(result.storage.unrelated, 'keep');
  });
}

for (const hash of ['#/', '#/proxies', '#/rules', '#/connections', '#/configs', '#/logs']) {
  test(`explicit ${hash} and refresh preserve route and user controller selection`, () => {
    const state = {theme: 'light', selectedClashAPIConfigIndex: 0,
      clashAPIConfigs: [{baseURL: 'https://custom.example', secret: 'user-secret'},
        {baseURL: `${endpoint}/`, secret: 'outdated', addedAt: 42}]};
    const first = execute(hash, state);
    const reloaded = execute(hash, first.state);
    assert.equal(first.url.hash, hash);
    assert.equal(first.replacements, 0);
    assert.equal(reloaded.replacements, 0);
    assert.equal(first.state.selectedClashAPIConfigIndex, 0);
    assert.equal(first.state.clashAPIConfigs.length, 2);
    assert.equal(first.state.clashAPIConfigs[1].secret, first.config.secret);
    assert.equal(first.state.clashAPIConfigs[1].addedAt, 42);
    assert.equal(first.state.clashAPIConfigs[0].secret, 'user-secret');
  });
}

test('initial storage and corrupt JSON recover only the YACD key', () => {
  for (const state of [{}, '{bad', null, []]) {
    const result = execute('', state);
    assert.equal(result.state.selectedClashAPIConfigIndex, 0);
    assert.equal(result.url.hash, '#/');
    assert.equal(result.storage.unrelated, 'keep');
  }
});

test('invalid selected index is repaired without losing explicit route', () => {
  const result = execute('#/rules', {selectedClashAPIConfigIndex: 8});
  assert.equal(result.url.hash, '#/rules');
  assert.equal(result.state.selectedClashAPIConfigIndex, 0);
});

test('selected duplicate local controller receives current secret without deleting controllers', () => {
  const result = execute('#/connections', {selectedClashAPIConfigIndex: 1,
    clashAPIConfigs: [{baseURL: endpoint, secret: 'old'}, {baseURL: `${endpoint}/`, secret: 'other-old'}]});
  assert.equal(result.state.selectedClashAPIConfigIndex, 1);
  assert.equal(result.state.clashAPIConfigs.length, 2);
  assert.equal(result.url.hash, '#/connections');
  for (const controller of result.state.clashAPIConfigs) assert.equal(controller.secret, result.config.secret);
});

test('query overrides cannot move the local credential to a remote controller', () => {
  const query = '?hostname=https%3A%2F%2Fevil.example&port=443&secret=override&theme=dark';
  for (const blocked of [false, true]) {
    const result = execute('#/proxies', {}, query, blocked);
    for (const key of ['hostname', 'port', 'secret']) assert.equal(result.url.searchParams.has(key), false);
    assert.equal(result.url.searchParams.get('theme'), 'dark');
    assert.equal(result.url.hash, '#/proxies');
  }
});

test('failed storage save does not falsely route to overview', () => {
  const result = execute('#/backend', {theme: 'dark'}, '', true);
  assert.equal(result.url.hash, '#/backend');
  assert.equal(result.state.theme, 'dark');
  assert.equal(result.state.clashAPIConfigs, undefined);
});
