#!/usr/bin/env python3
"""Refuse a push that would publish somebody's mail address or name.

    scripts/check-personal-data.py            # the files git tracks
    scripts/check-personal-data.py --install  # run it before every push

Two rules, on every tracked text file:

1. A mail address must be on an example domain (`.example`, `.test`,
   `.invalid`, `example.com`…) or listed in `.personal-data-allow` — one
   address per line, each a made-up one or a service's public address. An
   example that needs a real provider's domain (`alex.example@icloud.com`) is added
   there by hand, which is the moment to ask whether it is somebody's.
2. No word or pair of words from the private list
   `~/.maurice/ops/personal-names.txt` (one name per line, never in a repo):
   the people and households the operator knows. Without that file this rule
   is skipped — which is the case in CI, where only rule 1 runs.

Exit status 1 when something is found; the lines are printed, the private
names are not.
"""
import os
import re
import subprocess
import sys
import unicodedata

ROOT = subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True, check=True).stdout.strip()
ALLOW_FILE = os.path.join(ROOT, '.personal-data-allow')
NAMES_FILE = os.path.expanduser(os.environ.get('MAURICE_PERSONAL_NAMES', '~/.maurice/ops/personal-names.txt'))
EMAIL = re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+')
EXAMPLE = re.compile(r'(\.example|\.test|\.invalid|\.local|\.localhost|@example\.(com|org|net))$')
# Not addresses: package@version, image@2x, systemd unit@instance.
NOT_MAIL = re.compile(r'@(\d|[0-9.]+$)|\.(png|jpg|svg|service)$')
BINARY = ('.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.icns', '.pdf', '.ttf', '.otf', '.woff', '.woff2', '.epub', '.zip', '.db')


def fold(s):
    s = unicodedata.normalize('NFD', s)
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn').lower()


def install():
    hooks = os.path.join(ROOT, '.githooks')
    os.makedirs(hooks, exist_ok=True)
    hook = os.path.join(hooks, 'pre-push')
    with open(hook, 'w') as f:
        f.write('#!/bin/sh\n# Installed by scripts/check-personal-data.py --install\nexec python3 "$(git rev-parse --show-toplevel)/scripts/check-personal-data.py"\n')
    os.chmod(hook, 0o755)
    subprocess.run(['git', 'config', 'core.hooksPath', '.githooks'], check=True, cwd=ROOT)
    print('pre-push hook installed (.githooks/pre-push)')


def main():
    if '--install' in sys.argv:
        return install()
    allow = set()
    if os.path.exists(ALLOW_FILE):
        allow = {l.strip().lower() for l in open(ALLOW_FILE, encoding='utf-8') if l.strip() and not l.startswith('#')}
    names = []
    if os.path.exists(NAMES_FILE):
        names = [fold(l.strip()) for l in open(NAMES_FILE, encoding='utf-8') if l.strip() and not l.startswith('#')]
    name_re = None
    if names:
        alt = '|'.join(re.escape(n).replace(r'\ ', r'[\s._-]+') for n in sorted(set(names), key=len, reverse=True))
        name_re = re.compile(r'(?<![a-z])(' + alt + r')(?![a-z])')
    files = subprocess.run(['git', 'ls-files', '-z'], capture_output=True, text=True, check=True, cwd=ROOT).stdout.split('\0')
    found = 0
    for rel in files:
        if not rel or rel.lower().endswith(BINARY) or rel == '.personal-data-allow':
            continue
        try:
            text = open(os.path.join(ROOT, rel), encoding='utf-8').read()
        except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
            continue
        for i, line in enumerate(text.split('\n'), 1):
            for m in EMAIL.finditer(line):
                a = m.group(0).lower().lstrip('-')
                if EXAMPLE.search(a) or NOT_MAIL.search(a) or a in allow:
                    continue
                found += 1
                print(f'{rel}:{i}: mail address outside the example domains: {m.group(0)}')
            if name_re and name_re.search(fold(line)):
                found += 1
                print(f'{rel}:{i}: a name of the private list')
    if found:
        print(f'\n✗ {found} line(s). Replace them with made-up people on .example domains;'
              ' a made-up address that needs a real provider goes in .personal-data-allow.', file=sys.stderr)
        return 1
    print(f'✓ no personal data found ({len(files)} files; private names list: {"read" if names else "absent, rule skipped"})')
    return 0


if __name__ == '__main__':
    sys.exit(main() or 0)
