"""Shared Linux CLI presentation. Copyright Igor Krauch (github.com/igareck)."""
from __future__ import annotations
import os
import platform
import shutil
import sys
import textwrap
import unicodedata

VERSION = 'CLI Version 1.0'
SEPARATOR = '----------------------'


def columns():
    try:return max(40,os.get_terminal_size(sys.stdout.fileno()).columns)
    except (OSError,AttributeError):return max(40,shutil.get_terminal_size((100,24)).columns)


def cell_width(text):
    return sum(0 if unicodedata.combining(c) else 2 if unicodedata.east_asian_width(c) in ('W','F') else 1 for c in str(text))


def wrap(text,width):
    parts=[]
    for line in str(text).splitlines() or ['']:
        parts.extend(textwrap.wrap(line,width=max(1,width),break_long_words=True,break_on_hyphens=False) or [''])
    return parts


def table(headers, rows, widths):
    border='+'+'+'.join('-'*(w+2) for w in widths)+'+'
    print(border)
    def emit(values):
        cells=[wrap(value,w) for value,w in zip(values,widths)]
        for i in range(max(map(len,cells))):
            line='|'
            for parts,w in zip(cells,widths):
                item=parts[i] if i<len(parts) else ''
                line+=' '+item+' '*(w-cell_width(item)+1)+'|'
            print(line)
    emit(headers);print(border)
    for row in rows:
        emit(row);print(border)


def paragraphs(lines):
    # A newline here is a paragraph boundary, not a screen-width boundary.
    # Let the terminal create soft wraps that it can reflow on window resize.
    for line in lines:
        print(str(line))


def show_header(root):
    machine=platform.machine().lower()
    architecture='x64' if machine in ('x86_64','amd64') else 'ARM64' if machine in ('aarch64','arm64') else machine
    rule='='*min(79,columns())
    lines=[rule,'  Igareck AntiDPI Strategies + Bol-Van Zapret2',
           '  '+VERSION+' | Linux '+architecture,rule,
           '  ПРОГРАММА ГОТОВЫХ ANTI-DPI СТРАТЕГИЙ ОТ IGARECK НА БАЗЕ ZAPRET2 (BOL-VAN)',
           '  В УДОБНОЙ CLI ОБОЛОЧКЕ ДЛЯ РАБОТЫ ЧЕРНЫХ СПИСКОВ VPN/TOR + YOUTUBE',rule,
           '  AntiDPI от Igareck из репозитория:',
           '  https://github.com/igareck/anti-dpi-for-russia',
           '  VPN/Tor Черные Списки:',
           '  https://github.com/igareck/vpn-configs-for-russia',
           '  Telegram: https://t.me/igareq',rule,
           '  Zapret2 от Bol-Van, сборка для Linux из репозитория:',
           '  https://github.com/bol-van/zapret2',
           '  Скачивается напрямую или через прокси из оригинального репозитория',rule]
    paragraphs(lines)


def pause(message):
    if sys.stdin.isatty() and sys.stdout.isatty():
        input('\n'+SEPARATOR+'\n\n'+message+'\n')


def show_intro(root, prefix=None, installing=False):
    show_header(root)
    destination='папка, которую вы выберете далее' if installing else str(prefix or root)
    engine_path='<выбранная папка>/engine/nfqws2' if installing else str((prefix or root)/'engine/nfqws2')
    paragraphs(['','ВНИМАНИЕ!',
      '1. Если используется антивирус, добавьте папку установленной программы, файл nfqws2 и процесс nfqws2 в его исключения.',
      '   Папка программы: '+destination,
      '   Движок: '+engine_path,
      '2. Добавьте в исключения антивируса процессы используемых VPN/Tor-клиентов и их ядер.',
      '3. Если антивирус продолжает мешать соединениям, временно отключите его для проверки.',
      '   Эти инструкции относятся к установленному антивирусу; UFW и системный брандмауэр программа не отключает.'])
    pause('Нажмите Enter, чтобы продолжить и прочитать ЗАМЕТКИ')
    paragraphs(['','ЗАМЕТКИ:',
      '1. Программа с готовыми AntiDPI стратегиями разработана специально для восстановления работоспособности VPN-конфигураций Черных Списков, Tor и YouTube.',
      '2. Для работы с VPN Черных Списков или Tor включайте каждую стратегию поочередно, затем пингуйте подписки/мосты в вашем клиенте!',
      '   На одних стратегиях будет больше живых VPN-конфигураций, на других меньше.',
      '3. Для работы с YouTube следуйте инструкциям ниже и запустите автоматическую проверку стратегий на доступ к YouTube.',
      '   VPN для доступа к YouTube не требуется. YouTube работает напрямую из вашей сети вместе с AntiDPI стратегиями.',
      '4. Успешный тест на доступ к YouTube не всегда означает, что стратегия идеально работает с VPN-конфигурациями.',
      '   В свою очередь — отрицательный тест на YouTube абсолютно не значит, что стратегия не работает с VPN, часто наоборот.',
      '   Бывает, что стратегия не дает прямой доступ на YouTube, но при этом пингует самое максимальное число VPN-конфигураций.',
      '5. Стратегии Golden для VPN/Tor/YouTube могут работать лучше, чем Silver.',
      '6. Набор стратегий протестирован на российских кабельных/мобильных сетях.'])
    pause('Нажмите Enter, чтобы продолжить установку' if installing else 'Нажмите Enter, чтобы продолжить работу')


def catalogue(data):
    print('\n######################\n')
    paragraphs(['КАТАЛОГ (Октябрь 2026): Golden+Silver; 484 уникальных AntiDPI стратегии, подобранные специально для российских сетей.',
                'Golden: 1–45 (45). Silver: 46–484 (439).'])
    print('\n'+SEPARATOR+'\n\nВводите номер стратегии из колонки «Номера».')
    family_width=max(10,min(65,columns()-40))
    rows=[]
    for f in data['FamilyRows']:
        group,name=f['Name'].split(' / ',1)
        numbers=str(f['First']) if f['First']==f['Last'] else f'{f["First"]}–{f["Last"]}'
        rows.append([f['Number'],group,name,f['Count'],numbers])
    table(['#','Набор','Семейство','Кол.','Номера'],rows,[2,6,family_width,4,7])


def selection_prompt():
    print('\nВыберите один из двух вариантов.')
    paragraphs(['1. Либо введите номер AntiDPI-стратегии из таблицы (0 — выход из программы)',
      '2. Либо, чтобы проверить диапазон стратегий на доступ к YouTube, наберите слово "Youtube" (без учета регистра) и далее следуйте указаниям'])
    print(SEPARATOR)
    return input('Ваш выбор: ').strip()
