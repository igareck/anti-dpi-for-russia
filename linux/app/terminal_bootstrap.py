"""Bootstrap launcher for Igareck Linux scripts.

START.sh / INSTALL.sh / DELETE.sh run in the calling terminal. Desktop
shortcuts open a terminal emulator when needed. Only the installer/engine
command requests sudo.
Copyright Igor Krauch (github.com/igareck).
"""
from __future__ import annotations
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def program_command(role, arguments):
    if role == 'install':
        return [sys.executable, str(ROOT/'install.py'), 'install']+arguments
    if role == 'run':
        return [sys.executable, str(ROOT/'igareck.py')]+arguments
    if role == 'uninstall':
        return [sys.executable, str(ROOT/'remove_installation.py')]+arguments
    raise RuntimeError('Неизвестный режим запуска.')


def stop_owned(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def sudo_command(role, arguments):
    command = program_command(role, arguments)
    if os.geteuid() != 0:
        command = ['sudo', '--']+command
    return command


def open_terminal(role, arguments):
    """Desktop shortcuts: open a terminal and run the program inside it."""
    command = sudo_command(role, arguments)
    cwd = os.environ.get('IGARECK_TERMINAL_CWD', str(ROOT))
    candidates = (
        ('xdg-terminal-exec', []),
        ('gnome-terminal', ['--']),
        ('kgx', ['-e']),
        ('konsole', ['-e']),
        ('xfce4-terminal', ['-x']),
        ('mate-terminal', ['-e']),
        ('kitty', []),
        ('alacritty', ['-e']),
        ('foot', []),
        ('xterm', ['-e']),
    )
    for binary, prefix in candidates:
        path = shutil.which(binary)
        if not path:
            continue
        process = subprocess.Popen([path]+prefix+command, cwd=cwd)
        try:
            return process.wait()
        except KeyboardInterrupt:
            stop_owned(process)
            return 130
    raise RuntimeError('Не найден терминал для ярлыка. Запустите START.sh или DELETE.sh вручную.')


def launch(role, arguments):
    # Pure information commands retain their original non-root CLI behavior.
    if ('--help' in arguments or '-h' in arguments or
            (role == 'run' and arguments and arguments[0] in ('list','show','check-engine'))):
        os.execv(sys.executable, program_command(role, arguments))
    command = sudo_command(role, arguments)
    os.execvp(command[0], command)


def main():
    arguments = sys.argv[1:]
    if not arguments:
        raise RuntimeError('Не указан режим запуска.')
    if arguments[0] == '--session':
        # Kept for native desktop launchers compiled against older helpers.
        return open_terminal(arguments[1], arguments[2:])
    return launch(arguments[0], arguments[1:])


if __name__ == '__main__':
    def terminate(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGHUP, terminate)
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as error:
        print('ОШИБКА: '+str(error), file=sys.stderr)
        raise SystemExit(1)
