#!/usr/bin/env python3
"""Builds app/src/main/assets/titles.tsv and assets/posters/*.jpg from JustWatch (India, free/flat-rate offers).

Edit TITLES, run `python3 tools/build_titles.py`, rebuild the app. A title is kept only if it streams on Netflix,
Prime Video or Hotstar; the link is that app's own deep link. Posters are for your own TV: they are not committed.
"""
import json, os, re, sys, time, urllib.error, urllib.request

# display name | search term | MOVIE or SHOW | release year
TITLES = """
Breaking Bad|Breaking Bad|SHOW|2008
Better Call Saul|Better Call Saul|SHOW|2015
Money Heist|Money Heist|SHOW|2017
Dark|Dark|SHOW|2017
Lupin|Lupin|SHOW|2021
Narcos|Narcos|SHOW|2015
Squid Game|Squid Game|SHOW|2021
Sacred Games|Sacred Games|SHOW|2018
Wednesday|Wednesday|SHOW|2022
The Umbrella Academy|The Umbrella Academy|SHOW|2019
Stranger Things|Stranger Things|SHOW|2016
The Witcher|The Witcher|SHOW|2019
Ozark|Ozark|SHOW|2017
Peaky Blinders|Peaky Blinders|SHOW|2013
Mindhunter|Mindhunter|SHOW|2017
Black Mirror|Black Mirror|SHOW|2011
Drive to Survive|Formula 1: Drive to Survive|SHOW|2019
The Boys|The Boys|SHOW|2019
Panchayat|Panchayat|SHOW|2020
Mirzapur|Mirzapur|SHOW|2018
The Family Man|The Family Man|SHOW|2019
Paatal Lok|Paatal Lok|SHOW|2020
Invincible|Invincible|SHOW|2021
Reacher|Reacher|SHOW|2022
Fallout|Fallout|SHOW|2024
Loki|Loki|SHOW|2021
Shogun|Shōgun|SHOW|2024
The Mandalorian|The Mandalorian|SHOW|2019
Andor|Andor|SHOW|2022
Game of Thrones|Game of Thrones|SHOW|2011
Master|Master|MOVIE|2021
Bigil|Bigil|MOVIE|2019
Mersal|Mersal|MOVIE|2017
Sarkar|Sarkar|MOVIE|2018
Varisu|Varisu|MOVIE|2023
Theri|Theri|MOVIE|2016
Thuppakki|Thuppakki|MOVIE|2012
Leo|Leo|MOVIE|2023
Beast|Beast|MOVIE|2022
GOAT|The Greatest of All Time|MOVIE|2024
Viswasam|Viswasam|MOVIE|2019
Veeram|Veeram|MOVIE|2014
Vivegam|Vivegam|MOVIE|2017
Yennai Arindhaal|Yennai Arindhaal|MOVIE|2015
Thunivu|Thunivu|MOVIE|2023
Avengers: Endgame|Avengers: Endgame|MOVIE|2019
Avengers: Infinity War|Avengers: Infinity War|MOVIE|2018
Guardians of the Galaxy|Guardians of the Galaxy|MOVIE|2014
Black Panther|Black Panther|MOVIE|2018
Thor: Ragnarok|Thor: Ragnarok|MOVIE|2017
Doctor Strange|Doctor Strange|MOVIE|2016
Deadpool & Wolverine|Deadpool & Wolverine|MOVIE|2024
Iron Man|Iron Man|MOVIE|2008
"""
# JustWatch package -> Android package, in order of preference
APPS = [("nfx", "com.netflix.ninja"), ("prv", "com.amazon.amazonvideo.livingroom"), ("jhs", "in.startv.hotstar")]
Q = """query($t:String!,$k:[ObjectType!]){popularTitles(country:IN,first:6,filter:{searchQuery:$t,objectTypes:$k}){edges{node{
content(country:IN,language:en){title originalReleaseYear posterUrl}
offers(country:IN,platform:WEB){monetizationType package{shortName} standardWebURL}}}}}"""
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")


def post(variables):
    req = urllib.request.Request("https://apis.justwatch.com/graphql", json.dumps({"query": Q, "variables": variables}).encode(),
                                 {"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    for i in range(4):
        try:
            return json.load(urllib.request.urlopen(req, timeout=30))
        except urllib.error.HTTPError as e:
            if e.code != 429: raise
            time.sleep(3 * (i + 1))


def norm(s): return re.sub(r"[^a-z0-9]+", "", s.lower())


os.makedirs(os.path.join(OUT, "posters"), exist_ok=True)
rows = []
for line in TITLES.strip().split("\n"):
    name, term, kind, year = line.split("|")
    node = next((e["node"] for e in post({"t": term, "k": [kind]})["data"]["popularTitles"]["edges"]
                 if norm(e["node"]["content"]["title"]) == norm(term)
                 and abs((e["node"]["content"]["originalReleaseYear"] or 0) - int(year)) <= 1), None)
    if not node:
        print("not found:", name); continue
    links = {}
    for o in node["offers"]:
        if o["monetizationType"] in ("FLATRATE", "FREE", "ADS"): links.setdefault(o["package"]["shortName"], o["standardWebURL"])
    pick = next(((pkg, links[short]) for short, pkg in APPS if short in links), None)
    poster = node["content"]["posterUrl"]
    if not pick or not poster:
        print("not on your apps:", name); continue
    fn = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_") + ".jpg"
    dest = os.path.join(OUT, "posters", fn)
    if not os.path.exists(dest):
        url = "https://images.justwatch.com" + poster.replace("{profile}", "s276").replace("{format}", "jpg")
        open(dest, "wb").write(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=30).read())
        time.sleep(.3)
    rows.append("\t".join([name, pick[0], pick[1], fn]))
    print("ok", name, pick[0].split(".")[-1] if "." in pick[0] else pick[0])
open(os.path.join(OUT, "titles.tsv"), "w").write("\n".join(rows) + "\n")
print(len(rows), "titles")
