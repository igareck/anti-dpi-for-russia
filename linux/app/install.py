#!/usr/bin/env python3
"""CLI installer for Ubuntu/Debian (apt) and Fedora/RHEL (dnf). Copyright Igor Krauch (github.com/igareck)."""
from __future__ import annotations
import argparse
from contextlib import ExitStack
from hashlib import sha256
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid
import zipfile

SOURCE = Path(__file__).resolve().parent
PACKAGES_DEBIAN = ['python3', 'ca-certificates', 'curl', 'iproute2', 'nftables', 'kmod',
                   'build-essential', 'pkg-config', 'libnetfilter-queue-dev', 'libnfnetlink-dev',
                   'libmnl-dev', 'zlib1g-dev', 'libcap-dev', 'libluajit-5.1-dev']
PACKAGES_FEDORA = ['python3', 'ca-certificates', 'curl', 'iproute', 'nftables', 'kmod',
                   'gcc', 'make', 'pkg-config', 'libnetfilter_queue-devel', 'libnfnetlink-devel',
                   'libmnl-devel', 'zlib-devel', 'libcap-devel', 'luajit-devel']
PACKAGES = PACKAGES_DEBIAN
from app_paths import FILE_LAYOUT, resource_path

FILES = tuple(FILE_LAYOUT)


def detect_distro():
    os_release = {}
    if Path('/etc/os-release').is_file():
        try:
            for line in Path('/etc/os-release').read_text(encoding='utf-8', errors='replace').splitlines():
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    k, v = line.split('=', 1)
                    os_release[k.strip()] = v.strip().strip('"').strip("'")
        except OSError:
            pass

    distro_id = os_release.get('ID', '').lower()
    distro_like = os_release.get('ID_LIKE', '').lower().split()

    if (distro_id in ('fedora', 'rhel', 'centos', 'almalinux', 'rocky') or
            any(d in distro_like for d in ('fedora', 'rhel')) or
            Path('/etc/fedora-release').exists() or Path('/etc/redhat-release').exists()):
        return 'fedora'
    if (distro_id in ('debian', 'ubuntu', 'linuxmint', 'pop') or
            any(d in distro_like for d in ('debian', 'ubuntu')) or
            Path('/etc/debian_version').exists()):
        return 'debian'
    if shutil.which('dnf'):
        return 'fedora'
    if shutil.which('apt-get'):
        return 'debian'
    return None


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def extract_source(archive, destination, archive_root):
    """Validate the official archive, then omit only its outer GitHub directory."""
    if not archive_root or '/' in archive_root or '\\' in archive_root or archive_root in ('.', '..'):
        raise RuntimeError('Некорректный корень официального архива.')
    destination = Path(destination)
    records = []
    seen = set()
    total = 0

    def target(name, size, directory):
        nonlocal total
        path = Path(name)
        if (path.is_absolute() or '..' in path.parts or '\\' in name or
                not path.parts or path.parts[0] != archive_root):
            raise RuntimeError('Недопустимый элемент исходного архива: '+name)
        relative = Path(*path.parts[1:])
        if len(path.parts) == 1:
            if not directory:
                raise RuntimeError('Корень исходного архива должен быть папкой.')
            return None
        key = relative.as_posix()
        if key in seen:
            raise RuntimeError('Повторяющийся элемент исходного архива: '+name)
        seen.add(key)
        total += size
        if total > 100*1024*1024 or len(seen) > 20000:
            raise RuntimeError('Непредвиденный размер исходников.')
        return destination/relative

    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as stream:
            for member in stream.infolist():
                mode = member.external_attr >> 16
                if stat.S_ISLNK(mode) or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                    raise RuntimeError('Недопустимый элемент исходного ZIP: '+member.filename)
                path = target(member.filename, member.file_size, member.is_dir())
                if path is not None:
                    records.append((member, path))
            for member, path in records:
                if member.is_dir():
                    path.mkdir(parents=True, exist_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with stream.open(member) as source, path.open('xb') as output:
                        shutil.copyfileobj(source, output)
        return
    with tarfile.open(archive) as stream:
        for member in stream.getmembers():
            if not (member.isfile() or member.isdir()):
                raise RuntimeError('Недопустимый элемент исходного архива: '+member.name)
            path = target(member.name, member.size, member.isdir())
            if path is not None:
                records.append((member, path))
        for member, path in records:
            if member.isdir():
                path.mkdir(parents=True, exist_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                with stream.extractfile(member) as source, path.open('xb') as output:
                    shutil.copyfileobj(source, output)
                path.chmod(member.mode & 0o777)


def get_archive(meta, temporary):
    from source_download import download
    with (temporary/'source-download.log').open('w', encoding='utf-8') as log:
        return download(meta,temporary/'s.arc',log=log)


def choose_engine(value=None):
    from cli_ui import SEPARATOR
    if value:
        return value
    if not sys.stdin.isatty():
        return 'compatible'
    print(SEPARATOR)
    print('Выберите одно из двух.')
    print('1. Для установки максимально совместимой версии Zapret2 (рекомендуется) - нажмите 1')
    print('2. Для установки самой последней версии Zapret2 - нажмите 2')
    while True:
        answer = input('Ваш выбор [Enter = 1]: ').strip()
        if answer in ('', '1'):
            return 'compatible'
        if answer == '2':
            return 'latest'
        print('Введите 1 или 2. Enter выбирает 1.')


def choose_shortcuts(user, value=None):
    if user is None:
        return False
    if value is not None:
        return value == 'yes'
    if not sys.stdin.isatty():
        return False
    from cli_ui import SEPARATOR
    print(SEPARATOR)
    print('Создать папку с ярлыками Запуска и Удаления (Рабочий Стол и Главное Меню) + с подробным Описанием и Инструкциями?')
    while True:
        answer = input('Введите Да/Yes или Нет/No: ').strip().casefold()
        if answer in ('да', 'yes'):
            return True
        if answer in ('нет', 'no'):
            return False
        print('Введите Да/Yes или Нет/No.')


def installation_summary(prefix, shortcuts, global_command=None):
    print()
    if shortcuts:
        print('Создана папка с удобными Ярлыками Запуска и Удаления на Рабочем Столе:')
        print(Path(shortcuts['folder']).name)
        print('В папку также добавлено подробное Описание Стратегий и Инструкции в формате txt.')
        print('Добавлен ярлык запуска в Главное Меню программ.')
    print('Установлено в:')
    print(str(prefix))
    print('Исходные файлы запуска и удаления находятся в этой корневой папке.')
    if shortcuts and not shortcuts.get('icons_set', True):
        print('Не удалось назначить значки в файловом менеджере; файлы запуска доступны.')
    print()
    print('Запуск: sudo bash '+shlex.quote(str(prefix/'START.sh')))
    if global_command:
        print('Запуск из любой папки: igareck-antidpi')
    print('Стандартное удаление (не удаляет логи и измененные файлы):'+
          ' sudo bash '+shlex.quote(str(prefix/'DELETE.sh')))
    print('Полное удаление (вместе с корневой папкой и логами):'+
          ' sudo bash '+shlex.quote(str(prefix/'DELETE.sh'))+' --purge')
    print()


def choose_prefix(value):
    while True:
        answer = value or (input('Папка установки [/opt/igareck-antidpi]: ').strip() if sys.stdin.isatty() else '')
        path = Path(answer or '/opt/igareck-antidpi').expanduser().resolve()
        if path == Path('/') or (path.exists() and (not path.is_dir() or any(path.iterdir()))):
            print('Папка уже занята, выберите другую папку или удалите текущую!')
            if value or not sys.stdin.isatty(): raise RuntimeError('Укажите новую пустую папку через --prefix.')
            continue
        return path


def install(args):
    if sys.version_info < (3, 9):
        raise RuntimeError('Нужен Python 3.9 или новее.')
    distro = detect_distro()
    if distro == 'debian' and shutil.which('apt-get'):
        package_manager = 'apt-get'
        packages = PACKAGES_DEBIAN
        update_cmd = ['apt-get', 'update']
        install_cmd = ['apt-get', 'install', '-y', '--no-install-recommends']
    elif distro == 'fedora' and shutil.which('dnf'):
        package_manager = 'dnf'
        packages = PACKAGES_FEDORA
        update_cmd = ['dnf', 'makecache']
        install_cmd = ['dnf', 'install', '-y', '--setopt=install_weak_deps=False']
    elif distro == 'debian':
        raise RuntimeError('Обнаружен Debian/Ubuntu, но утилита apt-get не найдена.')
    elif distro == 'fedora':
        raise RuntimeError('Обнаружен Fedora/RHEL, но утилита dnf не найдена.')
    else:
        raise RuntimeError('Автоустановка этой версии поддерживает Ubuntu/Debian и Fedora/RHEL.')
    from cli_ui import show_intro
    from desktop_integration import graphical_user, create_shortcuts, remove_shortcuts
    from source_download import select_source
    from global_command import prepare, register, remove as remove_global_command
    with ExitStack() as rollback:
        install_id=uuid.uuid4().hex
        show_intro(SOURCE, installing=True)
        print('Нажмите Enter для установки в /opt/igareck-antidpi по умолчанию или укажите новую пустую папку.')
        prefix = choose_prefix(args.prefix)
        selection = choose_engine(args.engine)
        desktop_user = graphical_user()
        create_desktop = choose_shortcuts(desktop_user, args.shortcuts)
        command_plan = prepare(args.global_command)
        print('----------------------')
        print('\nУстановка: '+str(prefix)+'\n484 стратегии: 45 Golden + 439 Silver. IPv4 TCP/TLS.')
        if not args.skip_deps:
            subprocess.run(update_cmd, check=True)
            subprocess.run(install_cmd+packages, check=True)
        compiler = 'cc' if shutil.which('cc') else 'gcc'
        for name in (compiler, 'make', 'pkg-config', 'nft', 'ip', 'curl'):
            if not shutil.which(name): raise RuntimeError('Не установлена зависимость: '+name)
        meta = select_source(json.loads(resource_path(SOURCE, 'upstream.json').read_text()), selection)
        prefix.parent.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix='.ig-', dir=prefix.parent))
        # Lua is opened again after nfqws2 drops UID: all program ancestors must be traversable.
        stage.chmod(0o755)
        complete = False
        try:
            for name in FILES:
                target = stage/name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(resource_path(SOURCE, name), target)
            archive = get_archive(meta, stage)
            unpacked = stage/'s'; unpacked.mkdir(mode=0o700)
            extract_source(archive, unpacked, meta['archive_root'])
            upstream = unpacked
            if not (upstream/'nfq2/Makefile').is_file() or not (upstream/'lua/zapret-lib.lua').is_file():
                raise RuntimeError('В официальных исходниках не найдены nfqws2 и Lua-библиотека.')
            (stage/'upstream-selected.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
            print('Сборка nfqws2 под текущий Linux/CPU. Подробности: '+str(stage/'build.log'), flush=True)
            (stage/'build.log').touch(mode=0o600)
            with (stage/'build.log').open('w') as log:
                result = subprocess.run(['make','-C',str(upstream/'nfq2'),'-j'+str(min(os.cpu_count() or 2, 8))], stdout=log, stderr=subprocess.STDOUT)
            if result.returncode:
                print((stage/'build.log').read_text()[-8000:])
                raise RuntimeError('Ошибка компиляции nfqws2, см. сообщения выше.')
            engine = stage/'engine'; engine.mkdir()
            shutil.copy2(upstream/'nfq2/nfqws2', engine/'nfqws2')
            shutil.copytree(upstream/'lua', engine/'lua')
            license_file = upstream/'docs/LICENSE.txt'
            shutil.copy2(license_file, engine/'LICENSE_Bol-Van.txt')
            from igareck import load_catalogue, engine_arguments
            for path in stage.rglob('*'):
                if path == unpacked or unpacked in path.parents: continue
                if path.is_dir(): path.chmod(0o755)
                elif path.is_file() and path.name not in ('build.log',): path.chmod(0o755 if path.name=='nfqws2' or path.suffix=='.sh' else 0o644)
            data = load_catalogue(stage)
            print('Проверяю параметры всех 484 стратегий...', flush=True)
            with (stage/'parameters-check.log').open('w') as log:
                for row in data['ProfileRows']:
                    checked = subprocess.run(engine_arguments(stage, row)+['--dry-run'], cwd=engine, stdout=log, stderr=subprocess.STDOUT)
                    if checked.returncode:
                        log.flush()
                        print((stage/'parameters-check.log').read_text()[-8000:])
                        hint = ' Повторите установку с вариантом 1 (совместимая версия).' if selection == 'latest' else ''
                        raise RuntimeError(f'Стратегия {row["Number"]} несовместима с движком.'+hint)
            shutil.rmtree(unpacked)
            archive.unlink()  # The source is fetched again on the next installation.
            for path in stage.rglob('*'):
                if path.is_file(): path.chmod(0o755 if path.suffix=='.sh' or path.name=='nfqws2' else 0o644)
            (stage/'_state').mkdir(mode=0o700); (stage/'logs').mkdir(mode=0o700)
            for name in ('build.log','parameters-check.log','source-download.log'):
                shutil.move(stage/name, stage/'logs'/name)
            identity = {'schema':'igareck.zapret2.linux.install.v1','version':'1.0','prefix':str(prefix),
                        'id':install_id,'upstream_commit':meta['commit'],'upstream_selection':selection,'shortcuts':None,'global_command':None,
                        'files':{p.relative_to(stage).as_posix():digest(p) for p in stage.rglob('*') if p.is_file() and p.relative_to(stage).parts[0] not in ('logs','_state','__pycache__')}}
            (stage/'installation.json').write_text(json.dumps(identity,ensure_ascii=False,indent=2)+'\n')
            if prefix.exists(): prefix.rmdir()  # Only the empty directory accepted above.
            stage.chmod(0o755)
            os.replace(stage, prefix); complete = True
            rollback.pop_all()
            if create_desktop:
                shortcuts = None
                try:
                    shortcuts = create_shortcuts(desktop_user, prefix, install_id)
                    identity['shortcuts'] = shortcuts
                    temporary_marker = prefix/'installation.json.tmp'
                    temporary_marker.write_text(json.dumps(identity, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
                    os.replace(temporary_marker, prefix/'installation.json')
                except Exception as error:
                    identity['shortcuts'] = None
                    if shortcuts:
                        remove_shortcuts(shortcuts)
                    print('Программа установлена, но создать ярлыки не удалось: '+str(error))
            command_record = None
            try:
                command_record = register(prefix, install_id, command_plan)
                identity['global_command'] = command_record
                temporary_marker = prefix/'installation.json.tmp'
                temporary_marker.write_text(json.dumps(identity, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
                os.replace(temporary_marker, prefix/'installation.json')
            except Exception as error:
                identity['global_command'] = None
                if command_record:
                    remove_global_command(command_record)
                print('Программа установлена, но создать глобальную команду не удалось: '+str(error))
            installation_summary(prefix, identity['shortcuts'], identity['global_command'])
        finally:
            if not complete:
                saved = Path('/var/log/igareck-zapret2')/('install_failed_'+time.strftime('%Y%m%d_%H%M%S')+'_'+uuid.uuid4().hex[:8])
                try:
                    saved.mkdir(parents=True)
                    for name in ('build.log','parameters-check.log','source-download.log'):
                        if (stage/name).exists(): shutil.copy2(stage/name, saved/name)
                    print('Логи неудачной установки: '+str(saved))
                finally:
                    shutil.rmtree(stage, ignore_errors=True)
        if not args.no_launch and sys.stdin.isatty():
            subprocess.run([sys.executable,str(prefix/'igareck.py'),'--skip-intro'], check=False)


def uninstall(args):
    from remove_installation import uninstall as remove
    remove(SOURCE, purge=args.purge, yes=args.yes)


if __name__ == '__main__':
    def terminate(_signal, _frame):
        # Closing the terminal can make sudo forward both HUP and TERM.
        # Let the first cancellation unwind cleanup without a second signal.
        for item in (signal.SIGHUP, signal.SIGTERM, signal.SIGINT):
            signal.signal(item, signal.SIG_IGN)
        raise KeyboardInterrupt
    signal.signal(signal.SIGHUP, terminate)
    signal.signal(signal.SIGTERM, terminate)
    parser=argparse.ArgumentParser(description='Установка/удаление Igareck CLI Linux 1.0')
    parser.add_argument('action', nargs='?', choices=('install','uninstall'),default='install')
    parser.add_argument('--prefix')
    parser.add_argument('--engine', choices=('compatible','latest'), help='Версия официального Zapret2; без аргумента предлагается выбор')
    parser.add_argument('--shortcuts', choices=('yes','no'), help='Ярлыки для пользователя графической сессии; в терминальной среде пропускаются')
    parser.add_argument('--global-command', choices=('yes','no'), help='Создать глобальную команду запуска; при конфликте без аргумента предлагается выбор')
    parser.add_argument('--skip-deps', action='store_true',help='Зависимости уже установлены через apt/dnf')
    parser.add_argument('--no-launch',action='store_true')
    parser.add_argument('--yes',action='store_true')
    parser.add_argument('--purge',action='store_true',help='При удалении полностью удалить папку этой установки, включая логи и изменённые файлы')
    args=parser.parse_args()
    if os.geteuid()!=0: parser.error('Запустите через sudo bash INSTALL.sh / DELETE.sh.')
    exit_code = 0
    try:
        install(args) if args.action=='install' else uninstall(args)
    except KeyboardInterrupt:
        print('\nУстановка остановлена.'); exit_code = 130
    except Exception as error:
        print('ОШИБКА: '+str(error),file=sys.stderr); exit_code = 1
    raise SystemExit(exit_code)
