#!/usr/bin/env python3
"""
Офлайн-база мест для определения города по координатам фото.

Источник — GeoNames (https://www.geonames.org, лицензия CC BY 4.0):
cities5000.zip, alternateNamesV2.zip, countryInfo.txt из https://download.geonames.org/export/dump/
(копия — в зеркале /mnt/mirror/ai/geonames).

Результат (app/src/main/assets/places/):
  cities.tsv       — id, широта, долгота, название (по-русски, если есть), код страны, население
  countries.tsv    — код страны, название по-русски

Запуск: python3 scripts/build_places.py [каталог_с_исходниками]
"""
import csv
import io
import os
import sys
import zipfile

SRC = sys.argv[1] if len(sys.argv) > 1 else "/mnt/mirror/ai/geonames"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "places")
csv.field_size_limit(10**9)


def read_cities():
    with zipfile.ZipFile(os.path.join(SRC, "cities5000.zip")) as z:
        with z.open("cities5000.txt") as f:
            for line in io.TextIOWrapper(f, encoding="utf-8"):
                c = line.rstrip("\n").split("\t")
                # 0 id, 1 name, 4 lat, 5 lon, 7 feature code, 8 country, 14 population
                yield int(c[0]), c[1], float(c[4]), float(c[5]), c[8], int(c[14] or 0)


def read_countries():
    with open(os.path.join(SRC, "countryInfo.txt"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            c = line.rstrip("\n").split("\t")
            # 0 ISO, 4 name, 16 geonameid
            if len(c) > 16 and c[16]:
                yield c[0], c[4], int(c[16])


def russian_names(ids):
    """Русское название для каждого id: предпочтительное, иначе первое не разговорное и не историческое."""
    best = {}
    with zipfile.ZipFile(os.path.join(SRC, "alternateNamesV2.zip")) as z:
        with z.open("alternateNamesV2.txt") as f:
            for line in io.TextIOWrapper(f, encoding="utf-8"):
                c = line.rstrip("\n").split("\t")
                if len(c) < 8 or c[2] != "ru":
                    continue
                gid = int(c[1])
                if gid not in ids or c[6] == "1" or c[7] == "1":  # разговорное / историческое
                    continue
                rank = 0 if c[4] == "1" else (2 if c[5] == "1" else 1)  # предпочтительное < обычное < краткое
                if gid not in best or rank < best[gid][0]:
                    best[gid] = (rank, c[3])
    return {gid: name for gid, (_, name) in best.items()}


def main():
    cities = list(read_cities())
    countries = list(read_countries())
    ids = {c[0] for c in cities} | {c[2] for c in countries}
    ru = russian_names(ids)
    os.makedirs(OUT, exist_ok=True)
    # Без сжатия: AGP распаковывает ассеты .gz при сборке и убирает расширение, а APK
    # и так хранит ассеты сжатыми.
    with open(os.path.join(OUT, "cities.tsv"), "w", encoding="utf-8") as f:
        for gid, name, lat, lon, cc, pop in cities:
            f.write(f"{gid}\t{lat:.4f}\t{lon:.4f}\t{ru.get(gid, name)}\t{cc}\t{pop}\n")
    with open(os.path.join(OUT, "countries.tsv"), "w", encoding="utf-8") as f:
        for code, name, gid in countries:
            f.write(f"{code}\t{ru.get(gid, name)}\n")
    translated = sum(1 for c in cities if c[0] in ru)
    print(f"городов {len(cities)}, с русским названием {translated}; стран {len(countries)}")


if __name__ == "__main__":
    main()
