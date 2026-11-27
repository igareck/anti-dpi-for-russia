/* Production dashboard.js with a local document and message port fixture. */
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../app/src/main/assets/dashboard');
const markup = readFileSync(path.join(assets, 'index.html'), 'utf8');
const nodes = new Map();
const actions = [];
const localized = [];
const usage = [];

function element(tag, attributes) {
    const classes = new Set((attributes.class ?? '').split(' '));
    return {
        tag, ...attributes, dataset: {}, children: [],
        classList: {add: name => classes.add(name), remove: name => classes.delete(name),
            contains: name => classes.has(name)},
        disabled: Object.hasOwn(attributes, 'disabled'),
        hidden: Object.hasOwn(attributes, 'hidden'),
        listeners: new Map(),
        addEventListener(name, callback) { this.listeners.set(name, callback); },
        getAttribute(name) { return this[name] ?? null; },
        setAttribute(name, value) { this[name] = String(value); },
        querySelector(name) { return this.children.find(child => child.tag === name); },
        add(option) { this.children.push(option); },
    };
}

for (const [, tag, attrs] of markup.matchAll(/<(\w+)([^>]*)>/g)) {
    const attributes = Object.fromEntries([...attrs.matchAll(/([\w-]+)(?:="([^"]*)")?/g)]
        .map(([, name, value]) => [name, value ?? '']));
    const node = element(tag, attributes);
    if (node.id) nodes.set(node.id, node);
    if (attributes['data-action']) {
        node.dataset.action = attributes['data-action'];
        actions.push(node);
    }
    if (attributes['data-en']) {
        node.dataset.en = attributes['data-en'];
        node.dataset.ru = attributes['data-ru'];
        localized.push(node);
    }
    if (node.class === 'usage') usage.push(node);
}
nodes.get('module').children.push(element('img', {src: 'module-off.png'}));
const window = element('window', {});
const timers = new Map();
let timerId = 0;
window.setTimeout = (callback, delay) => {
    timers.set(++timerId, {callback, delay});
    return timerId;
};
window.clearTimeout = id => timers.delete(id);
function runTimer(delay) {
    const entry = [...timers].find(([, timer]) => timer.delay === delay);
    assert.ok(entry, `No timeout scheduled for ${delay} ms`);
    timers.delete(entry[0]);
    entry[1].callback();
}
const origin = 'https://appassets.androidplatform.net';
const document = {
    documentElement: {lang: 'ru', dataset: {}},
    getElementById: id => nodes.get(id),
    querySelectorAll(selector) {
        return {'[data-en]': localized, '.usage[lang]': usage, '[data-action]': actions}[selector];
    },
};
vm.runInNewContext(readFileSync(path.join(assets, 'dashboard.js'), 'utf8'),
    {document, window, location: {origin}});

const emitted = [];
const channel = {postMessage(message) { emitted.push(JSON.parse(message)); }};
const connect = window.listeners.get('message');
connect({origin: 'https://other.invalid', data: 'dashboard', ports: [channel]});
assert.equal(emitted.length, 0);
connect({origin: '', data: 'dashboard', ports: [channel]});
assert.deepEqual(emitted.pop(), {action: 'ready'});

const state = {
    english: false, port: '1080', query: '', group: '0', profile: 'T33',
    profileAvailable: true, favorite: false, description: 'Описание TCP33',
    udp: false, quic: 'Q1', discord: 'D8', discordEnabled: false,
    status: 'stopped', statusText: 'Остановлен', hint: '',
    module: false, moduleStatus: 'Модуль не активирован', canStart: true, canStop: false,
    groups: [{value: '0', label: 'Golden · 45'}],
    profiles: [{value: 'T33', label: 'TCP33'}],
    quics: [{value: 'Q1', label: 'QUIC · Q1'}],
    discords: [{value: 'D8', label: 'D8'}],
};
const render = fields => channel.onmessage({data: JSON.stringify({...state, ...fields})});
const visibleControls = () => {
    for (const id of ['language', 'launch', 'stop']) assert.equal(nodes.get(id).hidden, false, id);
};
render({});
visibleControls();
assert.equal(document.documentElement.dataset.theme, 'light');
render({dark: true});
assert.equal(document.documentElement.dataset.theme, 'dark');
render({});
assert.equal(document.documentElement.dataset.theme, 'light');
for (const name of ['github', 'vpn-configs', 'telegram']) {
    actions.find(node => node.dataset.action === name).listeners.get('click')();
}
assert.deepEqual(emitted.splice(0), [{action: 'github'}, {action: 'vpn-configs'}, {action: 'telegram'}]);
assert.equal(nodes.get('stop').disabled, true);
assert.equal(nodes.get('language-text').src, 'language-en.png');
assert.equal(nodes.get('module').children[0].src, 'module-off.png');
assert.equal(nodes.get('group-value').textContent, 'Golden · 45');
assert.equal(nodes.get('profiles-value').textContent, 'TCP33');
assert.equal(nodes.get('dropdown').hidden, true);

const down = nodes.get('dashboard').listeners.get('pointerdown');
const press = (control, pointerId = 1) => down({isPrimary: true, pointerType: 'touch',
    button: 0, pointerId, target: {closest: () => control}});
const label = input => ({tagName: 'LABEL', querySelector: () => input});
const udp = nodes.get('udp');
press(label(udp));
assert.equal(udp.classList.contains('press-active'), true);
assert.equal(udp.checked, false);
window.listeners.get('pointerup')({pointerId: 2});
assert.equal(udp.classList.contains('press-active'), true);
window.listeners.get('pointercancel')({pointerId: 1});
assert.equal(udp.classList.contains('press-active'), false);
const voice = nodes.get('discordEnabled');
press(label(voice));
assert.equal(voice.classList.contains('press-active'), false);
const moduleButton = nodes.get('module');
press(moduleButton);
assert.equal(moduleButton.classList.contains('press-active'), true);
window.listeners.get('pointerup')({pointerId: 1});
assert.equal(moduleButton.classList.contains('press-active'), false);

render({status: 'running', statusText: 'Работает • TCP33 • 127.0.0.1:1080', module: true, canStop: true});
visibleControls();
assert.equal(nodes.get('stop').disabled, false);
assert.equal(nodes.get('module').children[0].src, 'module-on.png');
assert.equal(nodes.get('apply-hint').hidden, true);

render({status: 'running', udp: true, hint: 'Выбор изменён. Нажмите «Применить».', module: true, canStop: true});
visibleControls();
assert.equal(nodes.get('udp').checked, true);
assert.equal(nodes.get('quic').disabled, false);
assert.equal(nodes.get('discordEnabled').disabled, false);
assert.equal(nodes.get('apply-hint').hidden, false);

render({status: 'running', udp: true, discordEnabled: true, module: true, canStop: true});
visibleControls();
assert.equal(nodes.get('apply-hint').hidden, true);
assert.equal(nodes.get('discord').disabled, false);
for (const id of ['launch', 'stop']) nodes.get(id).listeners.get('click')();
assert.deepEqual(emitted.splice(0), [{action: 'start'}, {action: 'stop'}]);

const language = nodes.get('language');
language.listeners.get('click')();
language.listeners.get('click')();
assert.equal(timers.size, 1);
assert.equal(language.classList.contains('flashing'), true);
assert.equal(language['aria-label'], 'Tap to switch to English');
assert.equal(emitted.length, 0);
runTimer(150);
assert.deepEqual(emitted.splice(0), [{action: 'language'}]);

render({english: true, module: true});
visibleControls();
assert.equal(document.documentElement.lang, 'en');
assert.equal(nodes.get('launch-text').src, 'start-en.png');
assert.equal(nodes.get('language-text').src, 'language-ru.png');
assert.equal(language['aria-label'], 'Поменять язык на Русский');
assert.equal(language.classList.contains('flashing'), false);
assert.equal(timers.size, 0);
assert.equal(nodes.get('module').children[0].src, 'module-on.png');
assert.equal(nodes.get('quic').disabled, true);
assert.equal(nodes.get('discordEnabled').disabled, true);
assert.equal(nodes.get('discord').disabled, true);
assert.equal(nodes.get('profiles')['aria-label'], 'TCP strategies, TCP33');

language.listeners.get('click')();
runTimer(150);
assert.deepEqual(emitted.splice(0), [{action: 'language'}]);
runTimer(650);
assert.equal(language.classList.contains('flashing'), false);
render({});
visibleControls();
assert.equal(document.documentElement.lang, 'ru');
assert.equal(nodes.get('launch-text').src, 'start-ru.png');
assert.equal(nodes.get('language-text').src, 'language-en.png');
assert.equal(nodes.get('module').children[0].src, 'module-off.png');
console.log(JSON.stringify({passed: true, states: 6, engine: 'node DOM fixture'}));
