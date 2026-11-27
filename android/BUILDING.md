# Сборка Igareck AntiDPI для Android v1.0

Для сборки нужны Linux x64, Bash, Python 3, JDK 17 или 21, Android SDK Platform
35 и Android SDK Build Tools 35.0.0. Для компиляции нативных компонентов
установите Android NDK r28c (`28.2.13676358`).

| Переменная | Значение |
| --- | --- |
| `IGARECK_JAVA_HOME` | Каталог JDK |
| `IGARECK_ANDROID_JAR` | Путь к `android.jar` из Android SDK Platform 35 |
| `IGARECK_BUILD_TOOLS` | Каталог Android SDK Build Tools 35.0.0 |
| `IGARECK_NDK` | Каталог Android NDK r28c |

Задайте эти переменные окружения. Запустите сборку из каталога `android/`
в репозитории или из корня распакованного архива исходников Android.

```bash
IGARECK_UNSIGNED_ONLY=1 bash build.sh
```

Результат сборки сохраняется в
`dist/Igareck_AntiDPI_v1.0_Android_11+_Universal.unsigned.apk`.
Чтобы собрать APK с подписью, задайте `IGARECK_KEYSTORE` (путь к хранилищу ключей),
`IGARECK_KEY_ALIAS` (псевдоним ключа) и `IGARECK_STORE_PASS` (пароль) и запустите
`bash build.sh`. Храните ключ и пароль вне репозитория. Для обновления
установленного приложения `com.igareck.antidpi` нужны тот же подписывающий
ключ и совместимый `versionCode`.

Сборка с NDK компилирует сетевой движок и SPAKE2 JNI для `arm64-v8a`,
`armeabi-v7a` и `x86_64`. Готовые зависимости из `deps/` проверяются по хешам
в [DEPENDENCIES.json](DEPENDENCIES.json).

Если нужны те же нативные библиотеки, что и в опубликованном APK v1.0,
вместо `IGARECK_NDK` укажите путь к подписанному APK в `IGARECK_NATIVE_FROM_APK`.
Перед сборкой скрипт проверит все девять библиотек по
[NATIVE_LIBRARIES_SHA256.json](NATIVE_LIBRARIES_SHA256.json).

Хеш релизного APK и отпечаток сертификата приведены в
[BUILD_PROVENANCE.json](BUILD_PROVENANCE.json). Проверки на компьютере
описаны в [tests/README.md](tests/README.md). Лицензии компонентов перечислены
в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Сборка в GitHub Actions

В репозитории откройте **Actions → Build release files → Run workflow**
и выберите `android` в поле `platform`. Вариант `all` запускает сборку для
трёх платформ. По окончании работы задание Android добавит APK без подписи
в **Artifacts**. Подписанный APK для установки доступен в
[GitHub Releases](https://github.com/igareck/anti-dpi-for-russia/releases/tag/v1.2).

## Сборка с изменённой библиотекой SPAKE2

Внесите изменения в отдельной копии исходников. При изменении JNI-файлов в
`vendor/spake2-java/android/src/main/cpp/` используйте сборку с NDK.

Для замены Java-части отредактируйте файлы из
`vendor/spake2-java/android/src/main/java/`. Соберите JAR, укажите его в
`DEPENDENCIES.json` вместо исходного AAR и запустите сборку с NDK.

```bash
spake_classes=$(mktemp -d)
"$IGARECK_JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$IGARECK_ANDROID_JAR:$IGARECK_BUILD_TOOLS/core-lambda-stubs.jar" \
    -classpath deps/annotation-1.3.0.jar -d "$spake_classes" \
    vendor/spake2-java/android/src/main/java/io/github/muntashirakon/crypto/spake2/*.java
"$IGARECK_JAVA_HOME/bin/jar" --create --file deps/spake2-local.jar -C "$spake_classes" .

python3 - <<'PYTHON'
import hashlib
import json
from pathlib import Path

manifest = Path("DEPENDENCIES.json")
dependencies = json.loads(manifest.read_text())
jar = Path("deps/spake2-local.jar")
for index, dependency in enumerate(dependencies):
    if dependency["file"] == "spake2-android-2.2.1.aar":
        dependencies[index] = {
            "artifact": "spake2-local",
            "version": "2.2.1-local",
            "file": jar.name,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        }
        break
else:
    raise SystemExit("В DEPENDENCIES.json отсутствует запись для SPAKE2 AAR")
manifest.write_text(json.dumps(dependencies, indent=2) + "\n")
PYTHON

env -u IGARECK_NATIVE_FROM_APK IGARECK_UNSIGNED_ONLY=1 bash build.sh
```

Для установки этого APK подпишите его своим ключом и используйте чистое
устройство или эмулятор.
