#!/usr/bin/env python3
"""
Generate org-roam style test vaults for measuring Bloom's Roam features
(Backlinks bar: linked + unlinked references, full-text search, and the
link suggestion strip) at different scales on a real device.

    scripts/gen-roam-perf-vault.py                      # 500, 2k, 5k and 10k vaults
    scripts/gen-roam-perf-vault.py --scale 2k           # one preset
    scripts/gen-roam-perf-vault.py --count 7500         # any size
    scripts/gen-roam-perf-vault.py --scale 5k --push    # also copy to the device

Each vault is a folder named BloomPerf-<N> under --out (default
internal/perf-vaults/, gitignored), next to a BloomPerf-<N>.manifest.md that
lists the probe notes and the exact counts to expect. The manifest sits
outside the vault on purpose: inside it, its own text would show up as
unlinked mentions of the probes.

Vault shape, matching what org-roam produces:
  * Notes in the vault root, named %Y%m%d%H%M%S-slug.org, each with a
    file-level :ID: drawer and #+title:. Some headings carry their own :ID:.
  * Daily notes in daily/YYYY-MM-DD.org (about 10% of the files), ending today.
  * Links are [[id:UUID][Title]], to file IDs and some heading IDs.
  * Link targets follow a power law: most notes have a handful of backlinks,
    a few hubs have hundreds. Notes also mention other titles in plain text,
    which feeds Unlinked references.
  * Fixed probe notes ("Probe Hub Large", ...) get an exact number of linked
    and unlinked references, so the same note can be timed at every scale.

The same --seed and scale always give the same notes; only the daily note
dates move with the current date.

--push copies a vault to /sdcard/Download/BloomPerf-<N> over adb. It never
writes anywhere else and asks before touching the device.
"""

from __future__ import annotations

import argparse
import os
import random
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import uuid
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

SCALES = {"500": 500, "2k": 2_000, "5k": 5_000, "10k": 10_000}
REMOTE_PARENT = "/sdcard/Download"
VAULT_PREFIX = "BloomPerf-"

# Probe notes: (title, linked references, unlinked references). Each reference
# sits in a different source file, so the Backlinks sheet shows exactly these
# counts. Counts shrink proportionally when a vault is too small to hold them.
# No title is a substring of another, and no word here is in the prose vocabulary.
PROBES = [
    ("Probe Leaf", 0, 0),
    ("Probe Hub Small", 10, 10),
    ("Probe Hub Medium", 100, 100),
    ("Probe Hub Large", 1000, 1000),
    ("Probe Mentions Only", 0, 2000),
]

# Full-text search probes: (word, share of files containing it). 0 means one file.
SEARCH_PROBES = [("zyzzyva", 0), ("marmalade", 0.01), ("lighthouse", 0.25)]

ADJECTIVES = """
active adaptive ambient analog applied atomic balanced basic binary bounded
brief calm canonical careful central classic clean cognitive collective compact
complex concrete constant creative critical cultural daily deep deliberate dense
digital direct distributed dynamic early effective elastic emergent empirical
evergreen explicit external fast flexible formal fragile frugal functional
general gentle global gradual hidden honest implicit incremental informal inner
intentional iterative lateral lean linear literal local loose manual mental
minimal mobile modern modular mutual narrow natural nested open optimal organic
outer parallel partial passive personal physical plain portable practical
private progressive public quiet radical random rapid raw recursive reflective
remote resilient robust rough rural shared silent simple slow social solid
spatial stable static steady strategic strong structured subtle sustainable
tacit temporal thin tidy urban useful visual vital weekly wide
""".split()

NOUNS = """
abstraction agenda algorithm analogy anchor archive argument attention
backlog balance baseline belief bias boundary budget buffer cadence calendar
canvas capture catalog chapter checklist chunk clarity cluster commitment
compass concept constraint context contract craft curiosity cycle dashboard
debt decision default delegation design dialogue diary discipline draft
energy entropy environment essay estimate ethic evidence experiment feedback
fiction filter focus folder friction garden goal grammar graph habit harvest
heuristic hierarchy history horizon hypothesis idea identity incentive index
inbox insight interface interval intuition inventory journal judgment kernel
keyboard language lecture lens library ledger leverage limit literature logic
loop manuscript map margin memory mentor metaphor method metric mindset model
momentum motivation narrative network notebook novel outline paradigm paragraph
pattern philosophy pipeline planning poetry practice principle priority
problem process productivity project prompt protocol quality question reading
recall recipe reference reflection region repetition research resource review
rhythm ritual roadmap routine rubric schedule scope script season sequence
signal sketch skill sprint stack story strategy structure summary surface
synthesis system tension theory thread threshold timeline tool topic trade
tradition transition trust vocabulary weekend workflow writing
""".split()

FILLER = """
the a an of to in on for with about from into over under between because
while when after before during around and but or so yet then also often
rarely usually still again maybe really quite very more less most least
this that these those it its we our they their i my you your
is are was were be been being has have had do does did can could should would
will might must think notice remember connect revisit compare explain
question sketch write read reread link note collect move keep drop try
""".split()

PROSE = ADJECTIVES + NOUNS + FILLER * 3
TAGS = """
reading writing project idea person book article meeting draft evergreen
reference howto inbox research someday
""".split()

# Guard the exactness of the probe counts.
_PROBE_WORDS = {w.lower() for t, _, _ in PROBES for w in t.split()}
assert not _PROBE_WORDS & set(PROSE), "probe words must not appear in prose"
assert not {w for w, _ in SEARCH_PROBES} & set(PROSE), "search probes must not appear in prose"


@dataclass
class Heading:
    title: str
    org_id: str | None


@dataclass
class Note:
    title: str
    org_id: str
    path: str  # relative to the vault root
    kind: str  # "note" | "daily" | "probe"
    headings: list[Heading] = field(default_factory=list)
    tags: list[str] = field(default_factory=list)
    # sections[0] is the intro (text before the first heading), then one per heading.
    # Each holds sentences; a sentence is a list of tokens (words or raw org markup).
    sections: list[list[list[str]]] = field(default_factory=list)


def make_id(rng: random.Random) -> str:
    return str(uuid.UUID(int=rng.getrandbits(128), version=4))


def slugify(title: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")


def sentence(rng: random.Random, lo: int = 6, hi: int = 14) -> list[str]:
    words = [rng.choice(PROSE) for _ in range(rng.randint(lo, hi))]
    words[0] = words[0].capitalize()
    return words


def insert_token(rng: random.Random, note: Note, section: int, token: str) -> None:
    """Puts [token] at a random spot in a random sentence of [section]."""
    sentences = note.sections[section]
    if not sentences:
        sentences.append(sentence(rng))
    s = rng.choice(sentences)
    s.insert(rng.randint(1, len(s)), token)


def unique_titles(rng: random.Random, count: int) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    while len(out) < count:
        r = rng.random()
        if r < 0.03:  # single-word titles: the worst case for unlinked-mention matching
            t = rng.choice(NOUNS).capitalize()
        elif r < 0.60:
            t = f"{rng.choice(ADJECTIVES)} {rng.choice(NOUNS)}".capitalize()
        elif r < 0.85:
            t = f"{rng.choice(NOUNS)} {rng.choice(NOUNS)}".capitalize()
        else:
            t = f"{rng.choice(ADJECTIVES)} {rng.choice(NOUNS)} {rng.choice(NOUNS)}".capitalize()
        if t.lower() not in seen:
            seen.add(t.lower())
            out.append(t)
    return out


def build_vault(n: int, seed: int, today: date):
    rng = random.Random(seed * 1_000_003 + n)
    n_daily = max(30, n // 10)
    n_regular = n - n_daily - len(PROBES)
    if n_regular < 50:
        sys.exit(f"error: --count {n} is too small (need at least {n_daily + len(PROBES) + 50})")

    # 1. Notes, IDs, file names and headings.
    notes: list[Note] = []
    ts = datetime(2021, 3, 1, 9, 0, 0)
    for title in unique_titles(rng, n_regular):
        ts += timedelta(seconds=rng.randint(60, 40_000))
        notes.append(Note(title, make_id(rng), f"{ts:%Y%m%d%H%M%S}-{slugify(title)}.org", "note"))
    probes: list[Note] = []
    for title, _, _ in PROBES:
        ts += timedelta(seconds=rng.randint(60, 40_000))
        probes.append(Note(title, make_id(rng), f"{ts:%Y%m%d%H%M%S}-{slugify(title)}.org", "probe"))
    dailies: list[Note] = []
    for i in range(n_daily):
        d = today - timedelta(days=n_daily - 1 - i)
        dailies.append(Note(d.isoformat(), make_id(rng), f"daily/{d.isoformat()}.org", "daily"))

    for note in notes + probes:
        k = rng.choices([0, 1, 2, 3, 4, 6], weights=[25, 25, 20, 15, 10, 5])[0]
        for _ in range(k):
            words = [rng.choice(ADJECTIVES + NOUNS) for _ in range(rng.randint(2, 4))]
            hid = make_id(rng) if rng.random() < 0.25 else None
            note.headings.append(Heading(" ".join(words).capitalize(), hid))
        if rng.random() < 0.5:
            note.tags = rng.sample(TAGS, rng.randint(1, 3))
        note.sections = [[sentence(rng) for _ in range(rng.randint(1, 3))]]
        note.sections += [[sentence(rng) for _ in range(rng.randint(1, 4))] for _ in note.headings]
    for note in dailies:
        note.headings = [Heading("Log", None)]
        note.sections = [[sentence(rng, 4, 8)], [sentence(rng, 3, 9) for _ in range(rng.randint(2, 8))]]

    # 2. Power-law links. Weight by shuffled rank, so hubs are spread through time.
    # The rank offset caps the top hub at roughly 2% of all links (about 1k at 10k
    # files); without it the top note collects half the vault.
    targets = notes[:]
    rng.shuffle(targets)
    cum, acc = [], 0.0
    for rank in range(len(targets)):
        acc += 1.0 / (rank + 6)
        cum.append(acc)

    sources = notes + probes + dailies
    # Distinct (source file, section) rows that link to each file ID: what the Backlinks sheet counts.
    linked_rows: dict[str, set] = {}
    total_links = 0
    for src in sources:
        if src.kind == "daily":
            out_degree = rng.randint(3, 12)
        else:
            out_degree = rng.choices([0, 1, 2, 3, 4, 5, 6, 8, 10, 15], weights=[6, 10, 14, 16, 14, 12, 10, 8, 6, 4])[0]
        for tgt in rng.choices(targets, cum_weights=cum, k=out_degree):
            if tgt is src:
                continue
            section = rng.randrange(len(src.sections))
            heading_ids = [h for h in tgt.headings if h.org_id]
            if heading_ids and rng.random() < 0.2:
                h = rng.choice(heading_ids)
                insert_token(rng, src, section, f"[[id:{h.org_id}][{h.title}]]")
            else:
                insert_token(rng, src, section, f"[[id:{tgt.org_id}][{tgt.title}]]")
                linked_rows.setdefault(tgt.org_id, set()).add((src.path, section))
            total_links += 1
        # Plain-text mentions of other titles, which feed Unlinked references.
        for _ in range(rng.choices([0, 1, 2, 3], weights=[30, 40, 20, 10])[0]):
            other = rng.choice(notes)
            if other is not src:
                insert_token(rng, src, rng.randrange(len(src.sections)), other.title.lower())

    # 3. Probe references, each in its own file's intro.
    pool = notes + dailies
    probe_rows = []
    for probe, (_, want_linked, want_unlinked) in zip(probes, PROBES):
        cap = int(len(pool) * 0.9)
        scale = min(1.0, cap / max(1, want_linked + want_unlinked))
        n_linked, n_unlinked = int(want_linked * scale), int(want_unlinked * scale)
        picked = rng.sample(pool, n_linked + n_unlinked)
        for src in picked[:n_linked]:
            insert_token(rng, src, 0, f"[[id:{probe.org_id}][{probe.title}]]")
        for src in picked[n_linked:]:
            insert_token(rng, src, 0, probe.title)
        total_links += n_linked
        probe_rows.append((probe, n_linked, n_unlinked))

    # 4. Search probes, as an extra intro sentence.
    all_notes = notes + probes + dailies
    search_rows = []
    for word, share in SEARCH_PROBES:
        k = 1 if share == 0 else max(1, int(len(all_notes) * share))
        for src in rng.sample(all_notes, k):
            s = sentence(rng, 5, 9)
            s.insert(rng.randint(1, len(s)), word)
            src.sections[0].append(s)
        search_rows.append((word, k))

    stats = {
        "files": len(all_notes),
        "regular": len(notes),
        "dailies": len(dailies),
        "probes": len(probes),
        "links": total_links,
        "heading_ids": sum(1 for x in notes + probes for h in x.headings if h.org_id),
        "hubs": sorted(((len(linked_rows.get(x.org_id, ())), x) for x in notes), key=lambda p: -p[0])[:5],
    }
    return all_notes, probe_rows, search_rows, stats


def render(note: Note) -> str:
    lines = [":PROPERTIES:", f":ID:       {note.org_id}", ":END:", f"#+title: {note.title}"]
    if note.tags:
        lines.append(f"#+filetags: :{':'.join(note.tags)}:")
    lines.append("")
    lines += [" ".join(s) + "." for s in note.sections[0]]
    for heading, body in zip(note.headings, note.sections[1:]):
        lines += ["", f"* {heading.title}"]
        if heading.org_id:
            lines += [":PROPERTIES:", f":ID:       {heading.org_id}", ":END:"]
        bullets = note.kind == "daily"
        lines += [("- " if bullets else "") + " ".join(s) + "." for s in body]
    return "\n".join(lines) + "\n"


def suggestion_probes(notes: list[Note]) -> list[tuple[str, int]]:
    """3-letter words to type and how many suggestion-index entries each matches
    (titles of files plus headings that have an :ID:), from most to fewest."""
    entries = [x.title.lower() for x in notes]
    entries += [h.title.lower() for x in notes for h in x.headings if h.org_id]
    grams: Counter = Counter()
    for e in entries:
        grams.update({e[i:i + 3] for i in range(len(e) - 2) if e[i:i + 3].isalpha()})
    ranked = grams.most_common()
    picks = ranked[:3]
    for target in (100, 20, 3):
        near = min(ranked, key=lambda g: abs(g[1] - target))
        if near not in picks:
            picks.append(near)
    return picks


def write_vault(root: Path, n: int, seed: int, today: date, force: bool) -> Path:
    vault = root / f"{VAULT_PREFIX}{n}"
    if vault.exists():
        if not force:
            sys.exit(f"error: {vault} exists; pass --force to regenerate it")
        shutil.rmtree(vault)
    notes, probe_rows, search_rows, stats = build_vault(n, seed, today)
    (vault / "daily").mkdir(parents=True)
    size = 0
    for note in notes:
        text = render(note)
        size += len(text.encode())
        (vault / note.path).write_text(text, encoding="utf-8")

    m = [
        f"# {vault.name} manifest",
        "",
        "- [Summary](#summary)",
        "- [Backlinks probes](#backlinks-probes)",
        "- [Busiest organic hubs](#busiest-organic-hubs)",
        "- [Search probes](#search-probes)",
        "- [Suggestion strip probes](#suggestion-strip-probes)",
        "- [Device setup](#device-setup)",
        "",
        "## Summary",
        "",
        f"Generated {datetime.now():%Y-%m-%d %H:%M} with seed {seed}.",
        "",
        "| Files | Notes | Dailies | Probes | id: links | Heading IDs | Size |",
        "|---|---|---|---|---|---|---|",
        f"| {stats['files']} | {stats['regular']} | {stats['dailies']} | {stats['probes']} "
        f"| {stats['links']} | {stats['heading_ids']} | {size / 1024 / 1024:.1f} MB |",
        "",
        "## Backlinks probes",
        "",
        "Open each probe note and check the Backlinks bar shows these counts. Each reference is in a",
        "different file, so these are exact.",
        "",
        "| Note | File | Linked | Unlinked |",
        "|---|---|---|---|",
    ]
    m += [f"| {p.title} | `{p.path}` | {nl} | {nu} |" for p, nl, nu in probe_rows]
    m += [
        "",
        "## Busiest organic hubs",
        "",
        "Generated notes with the most linked references (file-ID links only). Unlinked counts are",
        "not computed; single-word titles can collect many.",
        "",
        "| Note | File | Linked |",
        "|---|---|---|",
    ]
    m += [f"| {x.title} | `{x.path}` | {c} |" for c, x in stats["hubs"]]
    m += [
        "",
        "## Search probes",
        "",
        "| Query | Files containing it |",
        "|---|---|",
    ]
    m += [f"| `{w}` | {k} |" for w, k in search_rows]
    m += [
        "",
        "## Suggestion strip probes",
        "",
        "Type these as a word in the editor; the count is how many index entries contain them.",
        "",
        "| Type | Matching entries |",
        "|---|---|",
    ]
    m += [f"| `{g}` | {c} |" for g, c in suggestion_probes(notes)]
    m += [
        "",
        "## Device setup",
        "",
        f"1. Copy the vault to the device: `scripts/gen-roam-perf-vault.py --count {n} --push`",
        "   (or push it yourself).",
        f"2. In the Performance build (com.rrajath.grove.perf), pick `{REMOTE_PARENT}/{vault.name}` as the vault.",
        "3. Settings › Roam Features: turn on Roam Features, Backlinks and Suggestions.",
        "4. Set Dailies location to `daily`.",
        "5. Wait for the first sync and index to finish before timing anything.",
        "",
    ]
    (root / f"{vault.name}.manifest.md").write_text("\n".join(m), encoding="utf-8")
    print(f"{vault}: {stats['files']} files, {stats['links']} links, {size / 1024 / 1024:.1f} MB")
    return vault


def find_adb() -> str:
    candidates = [shutil.which("adb")]
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            candidates.append(os.path.join(os.environ[env], "platform-tools", "adb"))
    candidates.append(os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
    for c in candidates:
        if c and os.path.isfile(c):
            return c
    sys.exit("error: adb not found (set ANDROID_HOME or put adb on PATH)")


def push(vault: Path, serial: str | None, force: bool, assume_yes: bool) -> None:
    adb = find_adb()
    out = subprocess.run([adb, "devices"], capture_output=True, text=True, check=True).stdout
    devices = [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]
    if serial is None:
        if len(devices) != 1:
            sys.exit(f"error: {len(devices)} devices connected; pass --serial (have: {', '.join(devices) or 'none'})")
        serial = devices[0]
    elif serial not in devices:
        sys.exit(f"error: device {serial} is not connected")

    def sh(cmd: str, check: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run([adb, "-s", serial, "shell", cmd], capture_output=True, text=True, check=check)

    model = sh("getprop ro.product.model").stdout.strip()
    remote = f"{REMOTE_PARENT}/{vault.name}"
    exists = sh(f"test -e '{remote}' && echo yes", check=False).stdout.strip() == "yes"
    if exists and not force:
        sys.exit(f"error: {remote} already exists on {model}; pass --force to replace it")

    action = "Replace" if exists else "Create"
    print(f"{action} {remote} on {model} ({serial}) with {sum(1 for _ in vault.rglob('*.org'))} files.")
    if not assume_yes and input("Continue? [y/N] ").strip().lower() != "y":
        sys.exit("aborted")

    # One tar is much faster than adb-pushing thousands of small files.
    device_tar = f"/data/local/tmp/{vault.name}.tar"
    with tempfile.TemporaryDirectory() as tmp:
        tar_path = Path(tmp) / f"{vault.name}.tar"
        with tarfile.open(tar_path, "w", format=tarfile.USTAR_FORMAT) as tar:
            tar.add(vault, arcname=vault.name)
        subprocess.run([adb, "-s", serial, "push", str(tar_path), device_tar], check=True, capture_output=True)
    if exists:
        sh(f"rm -rf '{remote}'")
    sh(f"tar -xf '{device_tar}' -C '{REMOTE_PARENT}' && rm '{device_tar}'")
    count = sh(f"find '{remote}' -name '*.org' | wc -l").stdout.strip()
    print(f"Pushed {count} files to {remote}")


def main() -> None:
    repo = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    size = ap.add_mutually_exclusive_group()
    size.add_argument("--scale", choices=[*SCALES, "all"], default="all", help="preset size (default: all)")
    size.add_argument("--count", type=int, help="exact number of files instead of a preset")
    ap.add_argument("--out", type=Path, default=repo / "internal" / "perf-vaults", help="output folder")
    ap.add_argument("--seed", type=int, default=42, help="random seed (default: 42)")
    ap.add_argument("--force", action="store_true", help="replace an existing vault, locally and on the device")
    ap.add_argument("--push", action="store_true", help=f"copy each vault to {REMOTE_PARENT}/{VAULT_PREFIX}<N> over adb")
    ap.add_argument("--serial", help="adb device serial (needed when several devices are connected)")
    ap.add_argument("--yes", action="store_true", help="don't ask before pushing")
    args = ap.parse_args()

    counts = [args.count] if args.count else (list(SCALES.values()) if args.scale == "all" else [SCALES[args.scale]])
    args.out.mkdir(parents=True, exist_ok=True)
    today = date.today()
    for n in counts:
        vault = write_vault(args.out, n, args.seed, today, args.force)
        if args.push:
            push(vault, args.serial, args.force, args.yes)


if __name__ == "__main__":
    main()
