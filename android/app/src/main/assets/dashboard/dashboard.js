'use strict';

const byId = id => document.getElementById(id);
const dashboard = byId('dashboard');
const port = byId('port');
const search = byId('search');
const launch = byId('launch');
const stop = byId('stop');
const language = byId('language');
const moduleButton = byId('module');
const moduleIcon = moduleButton.querySelector('img');
const statusText = byId('status');
const applyHint = byId('apply-hint');
const description = byId('description');
const favorite = byId('favorite');
const dropdown = byId('dropdown');
const listbox = byId('dropdown-options');
const pickers = new Map(['group', 'profiles', 'quic', 'discord'].map(id => [id, {
    trigger: byId(id), display: byId(id + '-value'), items: [], value: '', label: '', signature: '',
}]));

let channel;
let ready = false;
let languageFlashExpiry;
let openPicker;
let focusedOption = -1;

function send(action, details = {}) {
    if (channel && ready) channel.postMessage(JSON.stringify({action, ...details}));
}

function setText(node, value) {
    if (node.textContent !== value) node.textContent = value;
}

function setImage(node, value) {
    if (node.getAttribute('src') !== value) node.setAttribute('src', value);
}

function options(id, items, selected, disabled = false) {
    const picker = pickers.get(id);
    const signature = items ? JSON.stringify(items) : picker.signature;
    const changed = picker.signature !== signature || picker.value !== selected;
    if (picker.signature !== signature) {
        picker.items = items;
        picker.signature = signature;
    }
    setChoice(picker, selected);
    picker.trigger.disabled = disabled || !picker.items.some(item => !item.disabled);
    if (picker === openPicker) {
        if (picker.trigger.disabled) closeDropdown();
        else if (changed) renderDropdown();
    }
}

function setChoice(picker, value) {
    picker.value = value;
    const item = picker.items.find(choice => choice.value === value);
    const text = item ? item.label : '';
    setText(picker.display, text);
    picker.trigger.setAttribute('aria-label', text ? picker.label + ', ' + text : picker.label);
}

function closeDropdown() {
    if (!openPicker) return;
    openPicker.trigger.setAttribute('aria-expanded', 'false');
    openPicker.trigger.removeAttribute('aria-activedescendant');
    openPicker = undefined;
    focusedOption = -1;
    dropdown.hidden = true;
    send('dropdown', {open: false});
}

function placeDropdown() {
    if (!openPicker) return;
    const trigger = openPicker.trigger;
    const anchor = trigger.getBoundingClientRect();
    const viewport = window.visualViewport;
    const top = viewport ? viewport.offsetTop : 0;
    const left = viewport ? viewport.offsetLeft : 0;
    const bottom = top + (viewport ? viewport.height : document.documentElement.clientHeight);
    const right = left + (viewport ? viewport.width : document.documentElement.clientWidth);
    const x = Math.max(left, anchor.left);
    const width = Math.min(right, anchor.right) - x;
    const below = bottom - anchor.bottom - 5;
    const above = anchor.top - top - 5;
    if (width <= 0 || anchor.bottom <= top || anchor.top >= bottom || Math.max(above, below) < 24) {
        closeDropdown();
        return;
    }
    listbox.style.fontSize = getComputedStyle(trigger).fontSize;
    listbox.style.left = x + 'px';
    listbox.style.width = width + 'px';
    const desired = Math.min(openPicker.items.length, 10) * listbox.firstElementChild.getBoundingClientRect().height + 2;
    const opensDown = below >= desired || below >= above;
    listbox.style.maxHeight = Math.min(desired, opensDown ? below : above) + 'px';
    listbox.style.top = (opensDown ? anchor.bottom + 1 : anchor.top - 1 - listbox.getBoundingClientRect().height) + 'px';
}

function focusOption(index, keyboard = false) {
    const row = listbox.children[index];
    if (!row || !openPicker) return;
    if (focusedOption >= 0) listbox.children[focusedOption].classList.remove('focused');
    if (keyboard) row.classList.add('focused');
    focusedOption = index;
    openPicker.trigger.setAttribute('aria-activedescendant', row.id);
    if (row.offsetTop < listbox.scrollTop) listbox.scrollTop = row.offsetTop;
    else if (row.offsetTop + row.offsetHeight > listbox.scrollTop + listbox.clientHeight) {
        listbox.scrollTop = row.offsetTop + row.offsetHeight - listbox.clientHeight;
    }
}

function renderDropdown() {
    const picker = openPicker;
    const rows = document.createDocumentFragment();
    const selected = picker.items.findIndex(item => item.value === picker.value && !item.disabled);
    picker.items.forEach((item, index) => {
        const row = document.createElement('div');
        row.id = 'dropdown-option-' + index;
        row.className = 'dropdown-option';
        row.dataset.index = index;
        row.setAttribute('role', 'option');
        row.setAttribute('aria-selected', String(index === selected));
        if (item.disabled) row.setAttribute('aria-disabled', 'true');
        row.textContent = item.label;
        rows.appendChild(row);
    });
    focusedOption = -1;
    listbox.textContent = '';
    listbox.appendChild(rows);
    listbox.setAttribute('aria-label', picker.label);
    placeDropdown();
    const initial = selected >= 0 ? selected : picker.items.findIndex(item => !item.disabled);
    listbox.scrollTop = listbox.children[initial].offsetTop;
    focusOption(initial);
}

function openDropdown(picker) {
    if (!ready || picker.trigger.disabled) return;
    closeDropdown();
    openPicker = picker;
    picker.trigger.focus({preventScroll: true});
    picker.trigger.setAttribute('aria-expanded', 'true');
    dropdown.hidden = false;
    renderDropdown();
    if (openPicker) send('dropdown', {open: true});
}

function chooseOption(index) {
    if (!openPicker) return;
    const picker = openPicker;
    const item = picker.items[index];
    if (!item || item.disabled) return;
    closeDropdown();
    picker.trigger.focus({preventScroll: true});
    if (item.value === picker.value) return;
    setChoice(picker, item.value);
    send('edit', {field: picker.trigger.id, value: item.value});
}

function moveOption(start, step) {
    const items = openPicker.items;
    for (let index = start; index >= 0 && index < items.length; index += step) {
        if (!items[index].disabled) {
            focusOption(index, true);
            break;
        }
    }
}

for (const picker of pickers.values()) {
    picker.trigger.addEventListener('click', () => {
        if (openPicker === picker) closeDropdown();
        else openDropdown(picker);
    });
    picker.trigger.addEventListener('keydown', event => {
        const key = event.key;
        if (openPicker === picker && (key === 'Escape' || key === 'Tab')) {
            if (key === 'Escape') event.preventDefault();
            closeDropdown();
        } else if (openPicker === picker && (key === 'Enter' || key === ' ')) {
            event.preventDefault();
            chooseOption(focusedOption);
        } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(key)) {
            event.preventDefault();
            const wasOpen = openPicker === picker;
            if (!wasOpen) openDropdown(picker);
            if (!openPicker) return;
            if (key === 'Home') moveOption(0, 1);
            else if (key === 'End') moveOption(picker.items.length - 1, -1);
            else if (wasOpen) {
                const step = key === 'ArrowDown' ? 1 : -1;
                moveOption(focusedOption + step, step);
            }
        }
    });
}
dropdown.addEventListener('click', event => {
    if (event.target === dropdown) closeDropdown();
});
listbox.addEventListener('click', event => {
    const row = event.target.closest('.dropdown-option');
    if (row) chooseOption(Number(row.dataset.index));
});
window.addEventListener('resize', placeDropdown);
window.addEventListener('scroll', placeDropdown, {passive: true});
if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', placeDropdown);
    window.visualViewport.addEventListener('scroll', placeDropdown);
}

function translate(english) {
    const lang = english ? 'en' : 'ru';
    if (document.documentElement.lang === lang && ready) return;
    document.documentElement.lang = lang;
    for (const node of document.querySelectorAll('[data-en]')) setText(node, node.dataset[lang]);
    for (const node of document.querySelectorAll('.usage[lang]')) node.hidden = node.lang !== lang;
    const labels = {
        launch: english ? 'Start or apply strategy' : 'Запустить или применить стратегию',
        stop: english ? 'Stop strategies' : 'Остановить стратегии',
        language: english ? 'Поменять язык на Русский' : 'Tap to switch to English',
        search: english ? 'Search this group by number or keyword' : 'Поиск в группе по номеру или слову',
        group: english ? 'TCP strategy groups' : 'TCP-стратегии по группам',
        profiles: english ? 'TCP strategies' : 'Список TCP-стратегий',
        favorite: english ? 'Add or remove favorite' : 'Добавить или убрать из избранного',
        quic: english ? 'QUIC strategy' : 'Стратегия QUIC',
        discord: english ? 'Discord/STUN strategy' : 'Стратегия Discord/STUN',
    };
    for (const [id, label] of Object.entries(labels)) {
        const picker = pickers.get(id);
        if (picker) picker.label = label;
        else byId(id).setAttribute('aria-label', label);
    }
    search.placeholder = labels.search;
    setImage(byId('launch-text'), english ? 'start-en.png' : 'start-ru.png');
    setImage(byId('language-text'), english ? 'language-ru.png' : 'language-en.png');
    window.clearTimeout(languageFlashExpiry);
    language.classList.remove('flashing');
}

function receive(event) {
    let state;
    try {
        state = JSON.parse(event.data);
    } catch (_) {
        return;
    }
    if (state.action === 'dismiss-dropdown') {
        closeDropdown();
        return;
    }
    const theme = state.dark ? 'dark' : 'light';
    if (document.documentElement.dataset.theme !== theme) document.documentElement.dataset.theme = theme;
    translate(state.english);
    if (!ready) {
        port.value = state.port;
        search.value = state.query;
        ready = true;
    }
    dashboard.dataset.status = state.status;
    setText(statusText, state.statusText);
    setText(applyHint, state.hint);
    applyHint.hidden = !state.hint;
    applyHint.title = state.hint;
    setText(description, state.description);
    setImage(moduleIcon, state.module ? 'module-on.png' : 'module-off.png');
    moduleButton.setAttribute('aria-description', state.moduleStatus);

    options('group', state.groups, state.group);
    options('profiles', state.profiles, state.profile, !state.profileAvailable);
    options('quic', state.quics, state.quic, !state.udp);
    options('discord', state.discords, state.discord, !state.udp || !state.discordEnabled);
    byId('udp').checked = state.udp;
    const voice = byId('discordEnabled');
    voice.checked = state.discordEnabled;
    voice.disabled = !state.udp;

    setText(favorite, state.favorite ? '★' : '☆');
    favorite.setAttribute('aria-pressed', String(state.favorite));
    favorite.disabled = !state.profileAvailable;
    launch.disabled = !state.canStart;
    stop.disabled = !state.canStop;
    placeDropdown();
}

window.addEventListener('message', event => {
    if ((event.origin !== '' && event.origin !== 'null' && event.origin !== location.origin) || event.data !== 'dashboard' || !event.ports.length || channel) return;
    channel = event.ports[0];
    channel.onmessage = receive;
    channel.postMessage('{"action":"ready"}');
});

for (const button of document.querySelectorAll('[data-action]')) {
    button.addEventListener('click', () => send(button.dataset.action));
}

let activePress;
function clearPress(event) {
    if (!activePress || (event && event.pointerId !== activePress.pointerId)) return;
    activePress.control.classList.remove('press-active');
    activePress = undefined;
}

dashboard.addEventListener('pointerdown', event => {
    if (!event.isPrimary || event.pointerType === 'mouse' || event.button !== 0) return;
    const target = event.target.closest('.pages button, .module, .select-trigger, .option label');
    if (!target) return;
    const control = target.tagName === 'LABEL' ? target.querySelector('input') : target;
    if (control.disabled) return;
    clearPress();
    activePress = {control, pointerId: event.pointerId};
    control.classList.add('press-active');
});
for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) {
    window.addEventListener(event, clearPress);
}
window.addEventListener('blur', () => clearPress());

language.addEventListener('click', () => {
    if (!ready || language.classList.contains('flashing')) return;
    language.classList.add('flashing');
    window.setTimeout(() => {
        send('language');
        // Clear the feedback even if the native bridge cannot reply.
        languageFlashExpiry = window.setTimeout(() => language.classList.remove('flashing'), 650);
    }, 150);
});
for (const id of ['udp', 'discordEnabled']) {
    byId(id).addEventListener('change', event => {
        const control = event.currentTarget;
        send('edit', {field: id, value: control.checked});
    });
}
for (const control of [port, search]) {
    control.addEventListener('input', () => send('edit', {field: control.id, value: control.value}));
}

if ('ResizeObserver' in window) {
    const title = document.querySelector('.masthead h1');
    const repositories = [
        [document.querySelector('[data-action="github"]'), document.querySelector('.masthead .version')],
        [document.querySelector('[data-action="vpn-configs"]'), document.querySelector('.masthead .telegram')],
    ];
    const metrics = document.createElement('canvas').getContext('2d');
    const actions = document.querySelector('.actions');
    const controls = actions.querySelector('.strategy-controls');
    const controlsAndCaptions = controls.querySelectorAll('button, .launch-note img, .stop-note img');
    const launchNote = controls.querySelector('.launch-note');
    const discordField = byId('discord');
    actions.classList.add('positioned');

    function alignRepositoryLinks() {
        if (!metrics) return;
        const titleStyle = getComputedStyle(title);
        const fontSize = parseFloat(titleStyle.fontSize);
        const width = title.getBoundingClientRect().width;
        for (const [link, pairedText] of repositories) {
            const style = getComputedStyle(link);
            metrics.font = `${style.fontWeight} ${titleStyle.fontSize} ${style.fontFamily}`;
            const unscaledWidth = metrics.measureText(link.textContent).width;
            link.style.fontSize = (fontSize * width / unscaledWidth).toFixed(2) + 'px';
            pairedText.style.fontSize = link.style.fontSize;
        }
    }

    function placeActions() {
        const fieldTop = actions.getBoundingClientRect().top + window.scrollY;
        const freeHeight = document.documentElement.clientHeight - fieldTop;
        const inset = Math.max(discordField.getBoundingClientRect().height,
            freeHeight / 3 - launch.getBoundingClientRect().height / 2);
        const label = language.getBoundingClientRect();
        const groupTop = controls.getBoundingClientRect().top;
        const compact = !launchNote.getClientRects().length;
        let firstClearCenter = label.height / 2;
        // Extend the scrollable field if the shared hit area would cover another control.
        for (const element of controlsAndCaptions) {
            const box = element.getBoundingClientRect();
            if (box.height > 0 && (compact || (box.right > label.left && box.left < label.right))) {
                firstClearCenter = Math.max(firstClearCenter, inset + box.bottom - groupTop + 8 + label.height / 2);
            }
        }
        // The space after the label's center is one sixth of the field.
        const fieldHeight = Math.max(freeHeight, firstClearCenter * 6 / 5, label.height * 3);
        actions.style.setProperty('--controls-inset', inset + 'px');
        actions.style.setProperty('--field-height', fieldHeight + 'px');
    }

    let layoutFrame;
    function schedulePlacement() {
        if (layoutFrame !== undefined) return;
        layoutFrame = window.requestAnimationFrame(() => {
            layoutFrame = undefined;
            alignRepositoryLinks();
            placeActions();
        });
    }

    const resize = new ResizeObserver(schedulePlacement);
    for (const element of document.querySelectorAll('.masthead, .status, .pages, .settings, .strategy-controls, .language')) {
        resize.observe(element);
    }
    window.addEventListener('resize', schedulePlacement);
    alignRepositoryLinks();
    placeActions();
}
