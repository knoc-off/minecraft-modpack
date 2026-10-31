#!/usr/bin/env python3
"""Find recipes that compete for the same input: same recipe type, inputs
that can be satisfied by the same items, different outputs. In game that is
"this does two things" (seeds -> seed_oil or plant_oil) or a crafting grid
that silently picks one mod's result.

Static: reads recipe + item-tag json straight out of the jars, evaluates the
common NeoForge load conditions, applies datapacks last (they override).
Recipes added from code (no json) are invisible to it.

Usage: recipe_conflicts.py <jar or dir of jars>... [--datapack DIR]...
"""
import json
import re
import sys
import zipfile
from collections import defaultdict
from itertools import combinations
from pathlib import Path

# One input -> many outputs by design: a menu, not a conflict.
MENU_TYPES = re.compile(r"stonecutting|chipped:|woodcutting|sawmill|cutting$|create:(item_application|deploying|sandpaper_polishing|sequenced_assembly)|smithing")
RECIPE_RE = re.compile(r"^data/([^/]+)/recipes?/(.+)\.json$")
TAG_RE = re.compile(r"^data/([^/]+)/tags/items?/(.+)\.json$")
CONDITION_KEYS = ("neoforge:conditions", "conditions", "fabric:load_conditions")


def load(args):
    jars, packs = [], []
    it = iter(args)
    for a in it:
        if a == "--datapack":
            packs.append(Path(next(it)))
        else:
            p = Path(a)
            jars += sorted(p.glob("*.jar")) if p.is_dir() else [p]

    recipes, tags, mods = {}, defaultdict(list), set()

    def add(name, read):
        if m := RECIPE_RE.match(name):
            try:
                recipes[f"{m[1]}:{m[2]}"] = json.loads(read())
            except Exception:
                pass
        elif m := TAG_RE.match(name):
            try:
                tags[f"{m[1]}:{m[2]}"].append(json.loads(read()))
            except Exception:
                pass

    for j in jars:
        with zipfile.ZipFile(j) as z:
            for n in z.namelist():
                if n == "META-INF/neoforge.mods.toml":
                    # only [[mods]] blocks: [[dependencies.x]] blocks name modIds too
                    for block in re.split(r"^\s*\[\[", z.read(n).decode(errors="replace"), flags=re.M):
                        if block.startswith("mods]]"):
                            mods.update(re.findall(r'modId\s*=\s*"([^"]+)"', block))
                add(n, lambda n=n: z.read(n))
    for d in packs:
        for f in d.rglob("*.json"):
            add(f.relative_to(d).as_posix(), f.read_bytes)
    mods |= {"minecraft", "neoforge", "c", "forge"}
    return recipes, tags, mods


def resolve_tags(raw):
    out = {}

    def get(t, seen=()):
        if t in out:
            return out[t]
        items = set()
        for doc in raw.get(t, []):
            if doc.get("replace"):
                items = set()
            for v in doc.get("values", []):
                v = v["id"] if isinstance(v, dict) else v
                if v.startswith("#"):
                    if v[1:] not in seen:
                        items |= get(v[1:], seen + (t,))
                else:
                    items.add(v)
        out[t] = frozenset(items)
        return out[t]

    for t in raw:
        get(t)
    return get


def condition_ok(c, mods, tag):
    t = c.get("type", c.get("condition", ""))
    if t.endswith(":false"):
        return False
    if t.endswith(":true"):
        return True
    if t.endswith("mod_loaded") or t.endswith("all_mods_loaded"):
        want = c.get("modid") or c.get("values") or []
        return all(m in mods for m in ([want] if isinstance(want, str) else want))
    if t.endswith(":not"):
        return not condition_ok(c["value"], mods, tag)
    if t.endswith(":and"):
        return all(condition_ok(x, mods, tag) for x in c["values"])
    if t.endswith(":or"):
        return any(condition_ok(x, mods, tag) for x in c["values"])
    if t.endswith("item_exists"):
        return c.get("item", "").split(":")[0] in mods
    if t.endswith("tag_empty"):
        return not tag(c["tag"])
    return True  # unknown condition: assume it loads


def ingredient(x, tag):
    """-> frozenset of item ids (or 'fluid:...' ids), None if unrecognised."""
    if x is None or x == {} or x == []:
        return None
    if isinstance(x, list):
        s = set()
        for y in x:
            s |= ingredient(y, tag) or set()
        return frozenset(s)
    if isinstance(x, str):
        return tag(x[1:]) if x.startswith("#") else frozenset([x])
    if isinstance(x, dict) and ("components" in x or "nbt" in x):
        # a potion/fluid with data: only equal data overlaps
        base = {k: v for k, v in x.items() if k not in ("amount", "count")}
        return frozenset(["data:" + json.dumps(base, sort_keys=True)])
    if isinstance(x, dict):
        if "ingredient" in x and isinstance(x["ingredient"], (dict, list)):
            return ingredient(x["ingredient"], tag)
        for k in ("fluid_tag", "fluidTag"):
            if k in x:
                return frozenset(["fluid#" + x[k]])
        if "fluid" in x and isinstance(x["fluid"], str):
            return frozenset(["fluid:" + x["fluid"]])
        if x.get("type") == "neoforge:tag" and "amount" in x:
            return frozenset(["fluid#" + x["tag"]])
        if x.get("type") in ("neoforge:single", "neoforge:fluid") or ("id" in x and "amount" in x and "count" not in x and x.get("type", "").startswith("neoforge")):
            return frozenset(["fluid:" + x.get("fluid", x.get("id"))])
        if "tag" in x:
            return tag(x["tag"]) or frozenset(["#" + x["tag"]])
        if "item" in x:
            return frozenset([x["item"]])
        if "items" in x:
            return ingredient(x["items"], tag)
        if "children" in x:
            return ingredient(x["children"], tag)
        if "base" in x:
            return ingredient(x["base"], tag)
    return None


def outputs(r):
    res = r.get("result", r.get("results", r.get("output", r.get("outputs"))))
    out = set()
    for x in res if isinstance(res, list) else [res]:
        if isinstance(x, str):
            out.add(x)
        elif isinstance(x, dict):
            x = x.get("item", x) if isinstance(x.get("item"), dict) else x
            i = x.get("id") or x.get("item") or x.get("fluid")
            if i and float(x.get("chance", 1)) >= 1:
                out.add(i)
    return frozenset(out)


def signature(r, tag):
    """-> ('shaped', grid) | ('list', [sets]) | None"""
    if "pattern" in r and "key" in r:
        key = {k: ingredient(v, tag) for k, v in r["key"].items()}
        rows = r["pattern"]
        # trim so a 2x2 in the corner of a 3x3 pattern compares equal
        grid = tuple(tuple(key.get(c) if c != " " else None for c in row) for row in rows)
        return ("shaped", grid)
    ings = r.get("ingredients", r.get("ingredient", r.get("input", r.get("inputs"))))
    if ings is None:
        return None
    if isinstance(ings, dict) or isinstance(ings, str):
        ings = [ings]
    sets = [ingredient(i, tag) for i in ings]
    if any(s is None for s in sets):
        return None
    return ("list", sorted(sets, key=lambda s: sorted(s)))


def overlap(a, b):
    """Can one set of inputs satisfy both recipes?"""
    if a[0] != b[0]:
        return False
    if a[0] == "shaped":
        ga, gb = a[1], b[1]
        if len(ga) != len(gb) or any(len(x) != len(y) for x, y in zip(ga, gb)):
            return False
        return all((x is None) == (y is None) and (x is None or x & y) for ra, rb in zip(ga, gb) for x, y in zip(ra, rb))
    la, lb = a[1], b[1]
    if len(la) != len(lb):
        return False
    match = [-1] * len(lb)  # bipartite matching, ingredient i of a <-> j of b

    def aug(i, seen):
        for j, s in enumerate(lb):
            if j not in seen and la[i] & s:
                seen.add(j)
                if match[j] < 0 or aug(match[j], seen):
                    match[j] = i
                    return True
        return False

    return all(aug(i, set()) for i in range(len(la)))


# Fields that don't decide which recipe a machine picks. Everything else
# (heat, mold, base fluid, tower size...) goes into the family key, so only
# recipes run under identical machine settings are compared.
IGNORED_FIELDS = {
    "type", "result", "results", "output", "outputs", "ingredient", "ingredients",
    "input", "inputs", "pattern", "key", "category", "group", "experience",
    "cookingtime", "cooking_time", "processingTime", "processing_time", "time",
    "duration", "fermenting_time", "burn_time", "unit", "show_notification",
    "energy", "fabric:load_conditions", "neoforge:conditions", "conditions",
}


def family(r):
    extra = {k: v for k, v in r.items() if k not in IGNORED_FIELDS}
    return f"{r.get('type', '?')} {json.dumps(extra, sort_keys=True) if extra else ''}".strip()


def main():
    recipes, raw_tags, mods = load(sys.argv[1:])
    tag = resolve_tags(raw_tags)
    by_family = defaultdict(list)
    skipped = 0
    for rid, r in recipes.items():
        if not isinstance(r, dict) or MENU_TYPES.search(r.get("type", "")):
            continue
        conds = next((r[k] for k in CONDITION_KEYS if k in r), [])
        if not all(condition_ok(c, mods, tag) for c in (conds if isinstance(conds, list) else [conds])):
            continue
        sig, out = signature(r, tag), outputs(r)
        if sig is None or not out:
            skipped += 1
            continue
        if sig[0] == "shaped" or r.get("type", "").endswith("shapeless"):
            fam = "crafting"
        else:
            fam = family(r)
        by_family[fam].append((rid, sig, out))

    found = []
    for fam, rs in by_family.items():
        for (ia, sa, oa), (ib, sb, ob) in combinations(rs, 2):
            if oa != ob and overlap(sa, sb):
                found.append((fam, ia, ib, oa, ob, sa == sb))
    found.sort(key=lambda f: (not f[5], f[0], f[1]))
    for fam, ia, ib, oa, ob, same in found:
        tag_ = "SAME INPUT" if same else "overlap"
        print(f"[{fam}] {tag_}\n  {ia} -> {', '.join(sorted(oa))}\n  {ib} -> {', '.join(sorted(ob))}")
    print(f"\n{len(found)} conflicts in {sum(map(len, by_family.values()))} recipes ({skipped} unparsed, skipped)", file=sys.stderr)


if __name__ == "__main__":
    main()
