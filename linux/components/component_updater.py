"""Обновление только двух фиксированных upstream-компонентов QNZapret.

Запускается system daemon после Polkit. Никаких pip, shell, post-install scripts
или произвольных URL из GUI. Ошибка сети оставляет последнюю рабочую версию.
"""

import fcntl
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import pwd
import re
import shutil
import signal
import subprocess
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request

BASE = Path("/var/lib/qnzapret/components")
PACKAGED = Path("/usr/lib/qnzapret/runtime")
ADAPTER = Path("/usr/lib/qnzapret/telegram/adapter/qnzapret_telegram.py")
VALIDATOR = "/usr/libexec/qnzapret-runtime"
REPOS = {"zapret2": "bol-van/zapret2", "tg-ws-proxy": "Flowseal/tg-ws-proxy"}
SLOTS = {"zapret2": "runtime", "tg-ws-proxy": "telegram"}
MAX_DOWNLOAD = 32 * 1024 * 1024


class UpdateError(Exception):
    pass


def version(value):
    if not re.fullmatch(r"v?\d+(?:\.\d+){1,3}", value):
        raise UpdateError("Неподдерживаемый номер версии")
    return tuple(int(part) for part in value.lstrip("v").split("."))


class TrustedRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        validate_url(url)
        return super().redirect_request(request, fp, code, message, headers, url)


def validate_url(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.username or parsed.password or
            parsed.port not in (None, 443) or parsed.hostname not in {
                "api.github.com", "github.com", "codeload.github.com",
                "release-assets.githubusercontent.com", "objects.githubusercontent.com"}):
        raise UpdateError("URL вне официального GitHub allowlist")


def fetch(url, limit=MAX_DOWNLOAD):
    validate_url(url)
    request = urllib.request.Request(url, headers={"User-Agent": "QNZapret-components/1",
                                                  "Accept": "application/vnd.github+json"})
    # Не наследуем пользовательский HTTP proxy в привилегированный downloader.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), TrustedRedirect())
    with opener.open(request, timeout=6) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise UpdateError("Загрузка превышает допустимый размер")
    return data


def api(path):
    return json.loads(fetch("https://api.github.com/" + path, 4 * 1024 * 1024))


def git_blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def archive_files(data):
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        total = 0
        for member in archive:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise UpdateError("Небезопасный путь в архиве")
            if not member.isfile():
                continue
            if member.size > MAX_DOWNLOAD:
                raise UpdateError("Файл в архиве слишком большой")
            total += member.size
            if total > 128 * 1024 * 1024:
                raise UpdateError("Распакованный архив слишком большой")
            relative = str(PurePosixPath(*path.parts[1:]))
            if relative in result:
                raise UpdateError("Повторяющийся путь в архиве")
            result[relative] = archive.extractfile(member).read()
    return result


def put(path, data, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
    path.write_bytes(data)
    path.chmod(0o755 if executable else 0o644)


def write_manifest(root):
    paths = sorted(path for path in root.rglob("*") if path.is_file()
                   and path.name != "manifest.sha256")
    put(root / "manifest.sha256", "".join(
        hashlib.sha256(path.read_bytes()).hexdigest() + "  " + str(path.relative_to(root)) + "\n"
        for path in paths).encode())


def verify_manifest(root):
    entries = (root / "manifest.sha256").read_text().splitlines()
    if len(entries) < 7:
        raise UpdateError("Неполный manifest")
    for line in entries:
        digest, relative = line.split("  ", 1)
        path = PurePosixPath(relative)
        if path.is_absolute() or ".." in path.parts:
            raise UpdateError("Небезопасный manifest")
        if hashlib.sha256((root / relative).read_bytes()).hexdigest() != digest:
            raise UpdateError("Не совпала контрольная сумма " + relative)


def unprivileged():
    if os.geteuid() == 0:
        account = pwd.getpwnam("qnzapret-runtime")
        os.setgroups([])
        os.setgid(account.pw_gid)
        os.setuid(account.pw_uid)


def validate(root, component):
    environment = {"PATH": "/usr/bin:/bin", "HOME": "/tmp", "PYTHONDONTWRITEBYTECODE": "1"}
    if component == "zapret2":
        command = [VALIDATOR, "--validate-component", str(root)]
    else:
        environment["PYTHONPATH"] = str(root)
        command = ["/usr/bin/python3", "-B", str(ADAPTER), "--self-test"]
        for path in root.glob("proxy/*.py"):
            compile(path.read_bytes(), str(path), "exec")
    result = subprocess.run(command, env=environment, preexec_fn=unprivileged,
                            capture_output=True, timeout=8, check=False)
    if result.returncode:
        # Содержимое сторонних stdout/stderr не печатается привилегированным downloader.
        raise UpdateError("Проверка совместимости не пройдена: " + component)


def installed_version(component):
    bundled = next(item["version"] for item in json.loads(
        (PACKAGED / "provenance.json").read_text())["components"] if item["name"] == component)
    link = BASE / SLOTS[component]
    if not link.exists():
        return bundled
    try:
        verify_manifest(link)
        cached = json.loads((link / "component.json").read_text())["version"]
        if version(cached) >= version(bundled):
            return cached
    except (OSError, ValueError, UpdateError):
        pass
    link.unlink(missing_ok=True)
    return bundled


def prepare_zapret(release, destination):
    tag = release["tag_name"]
    name = "zapret2-" + tag + ".tar.gz"
    asset = next(item for item in release["assets"] if item["name"] == name)
    expected_url = "https://github.com/bol-van/zapret2/releases/download/" + tag + "/" + name
    if asset["browser_download_url"] != expected_url:
        raise UpdateError("Неожиданный URL zapret2")
    digest = asset.get("digest", "")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise UpdateError("Upstream не опубликовал SHA256")
    data = fetch(expected_url)
    if "sha256:" + hashlib.sha256(data).hexdigest() != digest:
        raise UpdateError("Не совпал SHA256 архива zapret2")
    files = archive_files(data)
    shutil.copytree(PACKAGED / "lists", destination / "lists")
    shutil.copytree(PACKAGED / "payloads", destination / "payloads")
    for directory in (destination / "lists", destination / "payloads"):
        directory.chmod(0o755)
        for path in directory.rglob("*"):
            path.chmod(0o755 if path.is_dir() else 0o644)
    put(destination / "bin/nfqws2", files["binaries/linux-x86_64/nfqws2"], True)
    for path, contents in files.items():
        if re.fullmatch(r"lua/[a-zA-Z0-9_-]+\.lua", path):
            put(destination / path, contents)
    put(destination / "LICENSE.txt", files["docs/LICENSE.txt"])
    return {"archiveSha256": digest[7:]}


def prepare_telegram(release, destination):
    repo = REPOS["tg-ws-proxy"]
    tag = release["tag_name"]
    commit = api("repos/" + repo + "/commits/" + urllib.parse.quote(tag))["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise UpdateError("Некорректный upstream commit")
    tree = api("repos/" + repo + "/git/trees/" + commit + "?recursive=1")
    if tree.get("truncated"):
        raise UpdateError("Неполное дерево исходников")
    hashes = {item["path"]: item["sha"] for item in tree["tree"]
              if item["type"] == "blob" and item["mode"] in ("100644", "100755")}
    data = fetch("https://codeload.github.com/" + repo + "/tar.gz/" + commit)
    files = archive_files(data)
    selected = [path for path in hashes if re.fullmatch(r"proxy/[A-Za-z0-9_]+\.py", path)] + ["LICENSE"]
    if "proxy/tg_ws_proxy.py" not in selected:
        raise UpdateError("В upstream отсутствует headless core")
    for path in selected:
        contents = files[path]
        if git_blob(contents) != hashes[path]:
            raise UpdateError("Не совпал Git blob: " + path)
        put(destination / path, contents)
    return {"commit": commit, "archiveSha256": hashlib.sha256(data).hexdigest()}


def atomic_link(slot, target):
    temporary = BASE / (slot + ".new")
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(target)
    os.replace(temporary, BASE / slot)


def update(component):
    current = installed_version(component)
    release = api("repos/" + REPOS[component] + "/releases/latest")
    tag = release["tag_name"]
    if release["draft"] or release["prerelease"] or version(tag) <= version(current):
        print(component + ": актуальная версия " + current, flush=True)
        return
    with tempfile.TemporaryDirectory(prefix="stage-", dir=BASE) as temporary:
        stage = Path(temporary)
        stage.chmod(0o755)
        metadata = (prepare_zapret if component == "zapret2" else prepare_telegram)(release, stage)
        metadata.update(component=component, version=tag, upstream=REPOS[component],
                        updatedAt=int(time.time()))
        put(stage / "component.json", json.dumps(metadata, indent=2).encode())
        write_manifest(stage)
        verify_manifest(stage)
        validate(stage, component)
        destination = BASE / (component + "-" + tag + "-" + metadata["archiveSha256"][:12])
        if destination.exists():
            verify_manifest(destination)
        else:
            os.replace(stage, destination)
        slot = SLOTS[component]
        active = BASE / slot
        if active.is_symlink():
            atomic_link(slot + ".previous", os.readlink(active))
        atomic_link(slot, destination.name)
        print(component + ": установлена " + tag, flush=True)


def deadline(_signum, _frame):
    raise TimeoutError("Истекло время проверки обновлений")


def main():
    if os.geteuid() != 0:
        raise SystemExit("Updater запускается только system daemon после Polkit")
    os.umask(0o022)
    BASE.mkdir(parents=True, exist_ok=True, mode=0o755)
    BASE.chmod(0o755)
    with (BASE / "update.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Обновление уже выполняется; используем рабочие компоненты", flush=True)
            return
        signal.signal(signal.SIGALRM, deadline)
        # Отдельный бюджет каждому upstream; сбой одного не пропускает второй.
        for component in REPOS:
            signal.alarm(20)
            try:
                update(component)
            except Exception as error:
                print(component + ": обновление пропущено (" + type(error).__name__ +
                      "); сохранена локальная версия", flush=True)
            finally:
                signal.alarm(0)


if __name__ == "__main__":
    main()
