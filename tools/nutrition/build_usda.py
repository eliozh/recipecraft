#!/usr/bin/env python3
"""Builds app/src/main/assets/nutrition/usda.db from USDA FoodData Central and aliases.csv.

Run from the repository root (standard library only):

    python3 tools/nutrition/build_usda.py --cache /tmp/usda

The script downloads the FDC Foundation Foods and SR Legacy CSV releases into --cache (skipped when
a file with the pinned SHA-256 is already there), reads food.csv and food_nutrient.csv straight from
the zips, and writes only the rows curated in aliases.csv: name, aliases, kcal per 100 g and FDC id.
The raw FDC files are never committed.

Energy choice: nutrient 1008 ("Energy", KCAL) when the food has it, which is the case for every SR
Legacy food. Many Foundation foods only carry the Atwater energies, so the fallback is 2048 (Atwater
Specific Factors, the method SR Legacy used for most foods) and then 2047 (Atwater General Factors).

The table DDL, the room_master_table identity hash and user_version come from Room's exported schema
for UsdaDatabase, so Room's prepackaged-database check accepts the file. After changing
UsdaFoodEntity, build the app once to refresh app/schemas/ and run this script again.

Output is deterministic for the same inputs and SQLite version: rows are inserted in name order,
energy is rounded to one decimal, and the file is VACUUMed.
"""

import argparse
import csv
import hashlib
import io
import json
import os
import sqlite3
import sys
import time
import unicodedata
import urllib.request
import zipfile

BASE_URL = "https://fdc.nal.usda.gov/fdc-datasets/"

# (release file, the data_type its food.csv uses for the dataset's own foods, pinned SHA-256)
RELEASES = [
    (
        "FoodData_Central_foundation_food_csv_2026-04-30.zip",
        "foundation_food",
        "70457ee9d9342f43bda2010318c85f04210c689fdeb9cd2da4c513b0e8dbc655",
    ),
    (
        "FoodData_Central_sr_legacy_food_csv_2018-04.zip",
        "sr_legacy_food",
        "b80817294b8850530aaedf2e515c02593b1824f763a0ff356e5c2081643e6fd0",
    ),
]

ENERGY_NUTRIENTS = ["1008", "2048", "2047"]  # in order of preference, all KCAL

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_ALIASES = os.path.join(REPO, "tools", "nutrition", "aliases.csv")
DEFAULT_SCHEMA = os.path.join(REPO, "app", "schemas", "com.elio.recipecraft.data.nutrition.UsdaDatabase", "1.json")
DEFAULT_OUT = os.path.join(REPO, "app", "src", "main", "assets", "nutrition", "usda.db")
MAX_BYTES = 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(name, expected, cache):
    """Downloads one release into the cache, resuming a partial file, and checks its SHA-256."""
    path = os.path.join(cache, name)
    if os.path.exists(path) and sha256(path) == expected:
        return path
    for attempt in range(1, 9):
        have = os.path.getsize(path) if os.path.exists(path) else 0
        request = urllib.request.Request(BASE_URL + name, headers={"Range": f"bytes={have}-"} if have else {})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                mode = "ab" if have and response.status == 206 else "wb"
                with open(path, mode) as f:
                    while chunk := response.read(1 << 16):
                        f.write(chunk)
            break
        except OSError as e:  # URLError, timeouts and resets are all OSErrors
            print(f"  {name}: attempt {attempt} failed ({e}); retrying", file=sys.stderr)
            time.sleep(min(30, 2 * attempt))
    else:
        sys.exit(f"could not download {name}")
    actual = sha256(path)
    if actual != expected:
        sys.exit(f"{name}: SHA-256 {actual} does not match the pinned {expected}; delete it or update RELEASES")
    return path


def read_csv(archive, member_suffix):
    member = next(n for n in archive.namelist() if n.endswith("/" + member_suffix))
    with archive.open(member) as raw:
        yield from csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8"))


def load_release(path, data_type, wanted):
    """Returns {fdc_id: (description, kcal)} for the wanted ids that are this release's own foods."""
    with zipfile.ZipFile(path) as archive:
        foods = {
            row["fdc_id"]: row["description"]
            for row in read_csv(archive, "food.csv")
            if row["data_type"] == data_type and row["fdc_id"] in wanted
        }
        energy = {}
        for row in read_csv(archive, "food_nutrient.csv"):
            if row["fdc_id"] in foods and row["nutrient_id"] in ENERGY_NUTRIENTS:
                energy.setdefault(row["fdc_id"], {})[row["nutrient_id"]] = float(row["amount"])
    result = {}
    for fdc_id, description in foods.items():
        values = energy.get(fdc_id, {})
        kcal = next((values[n] for n in ENERGY_NUTRIENTS if n in values), None)
        result[fdc_id] = (description, kcal)
    return result


def normalize(key):
    """Mirrors IngredientMatcher.normalize: NFKC, lowercase, trimmed, single spaces."""
    return " ".join(unicodedata.normalize("NFKC", key).lower().split())


def read_aliases(path):
    with open(path, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    seen = {}
    for row in rows:
        keys = [row["name"]] + [a for a in row["aliases"].split("|") if a.strip()]
        for key in keys:
            n = normalize(key)
            if n != key.strip():
                sys.exit(f"aliases.csv: key {key!r} of {row['name']!r} is not normalized")
            if n in seen:
                sys.exit(f"aliases.csv: key {n!r} appears in both {seen[n]!r} and {row['name']!r}")
            seen[n] = row["name"]
    return rows


def write_db(rows, schema_path, out):
    with open(schema_path, encoding="utf-8") as f:
        database = json.load(f)["database"]
    (entity,) = database["entities"]
    tmp = out + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    con = sqlite3.connect(tmp)
    con.execute("PRAGMA page_size = 4096")
    con.execute("PRAGMA journal_mode = DELETE")
    con.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for query in database["setupQueries"]:
        con.execute(query)
    con.executemany(
        f"INSERT INTO `{entity['tableName']}` (name, aliases, kcalPer100g, fdcId) VALUES (?, ?, ?, ?)",
        rows,
    )
    con.execute(f"PRAGMA user_version = {int(database['version'])}")
    con.commit()
    con.execute("VACUUM")
    con.close()
    os.replace(tmp, out)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cache", required=True, help="directory for the downloaded FDC zips")
    parser.add_argument("--aliases", default=DEFAULT_ALIASES)
    parser.add_argument("--schema", default=DEFAULT_SCHEMA)
    parser.add_argument("--out", default=DEFAULT_OUT)
    args = parser.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)

    curated = read_aliases(args.aliases)
    wanted = {row["fdc_id"] for row in curated}
    found = {}
    for name, data_type, expected in RELEASES:
        print(f"release {name}")
        found.update(load_release(download(name, expected, args.cache), data_type, wanted))

    rows, missing, problems = [], [], []
    for row in sorted(curated, key=lambda r: r["name"]):
        hit = found.get(row["fdc_id"])
        if hit is None:
            missing.append(f"{row['name']} (fdc {row['fdc_id']})")
            continue
        description, kcal = hit
        if description != row["fdc_description"]:
            problems.append(f"{row['name']}: fdc {row['fdc_id']} is {description!r}, aliases.csv says {row['fdc_description']!r}")
        elif kcal is None:
            problems.append(f"{row['name']}: fdc {row['fdc_id']} has no energy value")
        else:
            aliases = "|".join(a.strip() for a in row["aliases"].split("|") if a.strip())
            rows.append((row["name"], aliases, round(kcal, 1), int(row["fdc_id"])))

    for line in problems:
        print("ERROR " + line, file=sys.stderr)
    for line in missing:
        print("MISSING " + line, file=sys.stderr)
    if problems or missing:
        sys.exit(f"{len(problems)} mismatched and {len(missing)} missing fdc ids; fix aliases.csv")

    write_db(rows, args.schema, args.out)
    size = os.path.getsize(args.out)
    print(f"rows written: {len(rows)}")
    print(f"aliases: {sum(len(r[1].split('|')) for r in rows if r[1])}")
    print(f"missing fdc ids: {len(missing)}")
    print(f"wrote {os.path.relpath(args.out, REPO)}: {size} bytes, sha256 {sha256(args.out)}")
    if size >= MAX_BYTES:
        sys.exit(f"{args.out} is {size} bytes; the asset must stay under 1 MB")


if __name__ == "__main__":
    main()
