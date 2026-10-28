"""Operate only the authorized experimental repository; never print/store credentials."""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import urllib.parse
import urllib.request
import zipfile

REPO = 'jhonni005/FirmarPDF-Prueba'
BRANCH = 'codex/embedpdf-arm64-20261026'
WORKFLOW = 'embedpdf-android-arm64.yml'
PROJECT = Path(__file__).resolve().parents[2]


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected and urllib.parse.urlsplit(req.full_url).hostname != urllib.parse.urlsplit(newurl).hostname:
            redirected.remove_header('Authorization')
        return redirected


def api(path, method='GET', data=None):
    env = os.environ.copy()
    env.update(GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never')
    r = subprocess.run(['git', 'credential', 'fill'], input=f'url=https://github.com/{REPO}\n\n'.encode(),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=30)
    values = dict(line.split('=', 1) for line in r.stdout.decode().splitlines() if '=' in line)
    token = values.get('password') or env.get('GH_TOKEN') or env.get('GITHUB_TOKEN')
    if not token:
        raise RuntimeError('GitHub authentication required; no interactive login attempted')
    request = urllib.request.Request('https://api.github.com/repos/' + REPO + path,
        headers={'Authorization': 'Bearer ' + token, 'User-Agent': 'FirmarPDF-ARM64-CI',
                 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'},
        method=method, data=json.dumps(data).encode() if data is not None else None)
    with urllib.request.build_opener(SafeRedirect()).open(request, timeout=60) as response:
        payload = response.read()
        return json.loads(payload) if response.headers.get_content_type() == 'application/json' else payload


def extract(data, target):
    if os.name == 'nt':
        target = Path('\\\\?\\' + str(target.resolve()))
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for name in z.namelist():
            if not (target / name).resolve().is_relative_to(target.resolve()):
                raise RuntimeError('Unsafe archive entry')
        z.extractall(target)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('command', choices=('status', 'dispatch', 'logs', 'download'))
    p.add_argument('--run', type=int)
    p.add_argument('--writer-only', action='store_true', help='Dispatch only the disposable writer harness')
    p.add_argument('--writer-library-run', type=int, help='Reuse the already linked Linux harness from this run')
    a = p.parse_args()
    if a.command == 'dispatch':
        api('/actions/workflows/' + WORKFLOW + '/dispatches', 'POST',
            {'ref': BRANCH, 'inputs': {'writer_only': a.writer_only,
                                     'writer_library_run': str(a.writer_library_run or '')}})
        print('Manual workflow dispatched on authorized branch')
        return
    runs = api('/actions/runs?' + urllib.parse.urlencode({'branch': BRANCH, 'per_page': 5}))['workflow_runs']
    runs = [r for r in runs if r['name'] == 'EmbedPDF Android ARM64']
    for r in runs:
        print(json.dumps({k: r[k] for k in ('id', 'status', 'conclusion', 'html_url', 'head_sha')}))
    run_id = a.run or runs[0]['id']
    if a.command == 'status':
        jobs = api(f'/actions/runs/{run_id}/jobs')['jobs']
        for j in jobs:
            print(json.dumps({'job': j['name'], 'status': j['status'], 'conclusion': j['conclusion'],
                              'steps': [{k: s.get(k) for k in ('name', 'status', 'conclusion', 'started_at', 'completed_at')} for s in j['steps']]}))
    elif a.command == 'logs':
        dest = PROJECT / '.gradle/embedpdf-android-audit' / str(run_id) / 'logs'
        extract(api(f'/actions/runs/{run_id}/logs'), dest)
        print('Logs saved under .gradle for run', run_id)
        for path in dest.rglob('*.txt'):
            lines = path.read_text(encoding='utf-8-sig', errors='replace').splitlines()
            if any('##[error]' in line or 'FAILED' in line or 'ERROR' in line for line in lines):
                print(path.name)
                print('\n'.join(lines[-70:]))
    else:
        for artifact in api(f'/actions/runs/{run_id}/artifacts')['artifacts']:
            if artifact['expired']:
                continue
            dest = PROJECT / '.gradle/embedpdf-android-artifacts' / str(run_id) / artifact['name']
            if not dest.resolve().is_relative_to((PROJECT / '.gradle/embedpdf-android-artifacts' / str(run_id)).resolve()):
                raise RuntimeError('Unsafe artifact name')
            extract(api('/actions/artifacts/' + str(artifact['id']) + '/zip'), dest)
            print('Downloaded', artifact['name'], 'bytes', artifact['size_in_bytes'])


if __name__ == '__main__':
    main()
