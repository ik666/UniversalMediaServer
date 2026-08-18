# Profiling-Analyse: Media-Scan (Stand 2026-08-18)

Fork-lokales Dokument. Festgehalten sind Messaufbau, Befunde und der Stand der
Umsetzung, damit die Arbeit auf einem anderen System ohne die ursprüngliche
Sitzung fortgesetzt werden kann.

## Messaufbau

Gemessen mit Java Flight Recorder statt YourKit, weil `jfr print` reinen Text
liefert, der sich automatisiert auswerten lässt. JFR läuft ab JVM-Start, damit
der initiale Scan vollständig erfasst wird.

Relevante JVM-Flags (im Betriebs-Repo unter `/lake/docker/ums`, nicht hier):

```
-XX:FlightRecorderOptions=stackdepth=192
-XX:StartFlightRecording=name=ums,settings=profile,disk=true,maxsize=2g,maxage=12h,dumponexit=true,filename=/jfr/ums-exit.jfr
```

`stackdepth=192` ist nötig — der Default von 64 schneidet die tiefen
Jetty/UMS-Stacks ab, dann sind die Aufrufer nicht mehr identifizierbar.

Dump im laufenden Betrieb, ohne Neustart:

```bash
docker exec -e JAVA_TOOL_OPTIONS= ums bash -lc \
  "source /usr/local/sdkman/bin/sdkman-init.sh; jcmd 1 JFR.dump name=ums filename=/jfr/<name>.jfr"
```

`JAVA_TOOL_OPTIONS` muss geleert werden: `jcmd` startet eine eigene JVM, die
sonst den YourKit-Agent auf dem bereits belegten Port 10012 laden will und mit
rc=20 am Attach scheitert.

Auswertung (das Feld heißt `sampledThread`, nicht `eventThread`; `grep -a`
verwenden, die Dumps enthalten Bytes, die grep als binär einstuft):

```bash
jfr summary ums.jfr
jfr print --events jdk.Deoptimization ums.jfr
jfr print --events jdk.ExecutionSample --stack-depth 60 ums.jfr
```

Basis-Aufnahme: 141 s, deckt Startup plus einen 67 s Media-Scan ab, **gegen eine
bereits befüllte Datenbank**.

## Befund 1 — Config-Getter werfen ~250.000 Exceptions pro Scan

### Messung

```
jdk.Deoptimization                    260.939 Events / 141 s
  PropertyConverter.toBoolean()       246.603   reason=unhandled, instruction=athrow
  Integer.parseInt()                   10.682
  Long.parseLong()                      1.877
  davon auf Thread "Media Scanner"    239.939
```

Betroffene Keys und Getter (aus den `ConversionException`-Events):

| Getter                  | Key                       | Events |
|-------------------------|---------------------------|--------|
| `isArchiveBrowsing()`   | `enable_archive_browsing` | 6.575  |
| `isResumeEnabled()`     | `resume`                  | 5.900  |
| `useCode()`             | `code_enable`             | 3.771  |
| `getATZLimit()`         | `atz_limit`               | 692    |
| `isHideEmptyFolders()`  | `hide_empty_folders`      | 665    |
| `getSearchInFolder()`   | `search_in_folder`        | 625    |

**Anteil an der CPU: 25,4 % aller Samples des Media-Scanner-Threads**
(502 von 1.979) liegen in `org.apache.commons.configuration2`; bezogen auf alle
Threads 17,5 % (556 von 3.186).

Hinweis zur Interpretation: Die `jdk.Deoptimization`-Events sind hier der
belastbare Zähler, weil pro `athrow` aus einem kompilierten Frame genau ein
Event entsteht. Die `jdk.JavaExceptionThrow`-Events (18.383 `ConversionException`)
liegen deutlich darunter, JFR begrenzt diese Event-Rate.

### Ursache

In `UMS.conf` stehen **174 Keys mit leerem Wert** (`resume =`,
`enable_archive_browsing =`, …). commons-configuration2 liefert dafür nicht den
übergebenen Default, sondern wirft `ConversionException`.
`ConfigurationReader.getBoolean` fängt sie und gibt den Default zurück —
funktional korrekt, aber pro Aufruf entsteht eine Exception-Konstruktion **plus**
eine C2-Deoptimierung jedes Frames, durch den die Exception läuft.

Die Aufrufer liegen im heißesten Pfad, einmal oder mehrfach pro Medien-Element:

- `store/StoreContainer.java:108` → `useCode()`
- `store/StoreContainer.java:164` → `isResumeEnabled()`
- `store/MediaStore.java:845-849` und `:921-925` → `isArchiveBrowsing()`, je 3× pro Datei
- `store/container/VirtualFolder.java:302` → `getATZLimit()`
- `store/StoreItem.java:1145`, `store/ResumeObj.java:157` → `isResumeEnabled()`

### Umsetzung in diesem Branch

`ConfigurationReader` behandelt einen leeren Wert jetzt als „nicht gesetzt",
bevor commons-configuration2 überhaupt gefragt wird — für `getInt`, `getLong`,
`getDouble` und `getBoolean`. Das greift für alle 174 Keys auf einmal und
entspricht dem Verhalten, das `getNonBlankConfigurationString` für String-Keys
schon immer hatte.

Das Ergebnis ist unverändert: leerer Wert → Default, vorher über die Exception,
jetzt direkt. Der `try`/`catch` bleibt als Absicherung für echte Fehlwerte
(z.B. `atz_limit = abc`) erhalten.

Trade-off, bewusst so gewählt: für Keys mit gültigem Wert kostet es einen
zusätzlichen `getProperty`-Lookup. Das ist eine Map-Abfrage gegen eine
eingesparte Exception samt Deoptimierung — in den Messdaten dominiert der
Blank-Fall deutlich.

Verworfene Alternative: leere Keys beim Laden der Konfiguration verwerfen. Das
wäre noch billiger, ändert aber das Rückschreibverhalten von `UMS.conf` beim
Speichern und damit mehr als nötig.

## Befund 2 — `FileUtil.isUrl` kompiliert pro Aufruf ein Pattern (offen)

`util/FileUtil.java:275` nutzt `filename.matches("\\S+://.*")`. `String.matches`
kompiliert das Pattern bei **jedem** Aufruf neu.

Aufrufkette: `formats/FormatFactory.java:134` (`getAssociatedFormat`) iteriert
über ~66 registrierte Formate, jedes `Format.match` (`formats/Format.java:225`)
ruft `FileUtil.getProtocol` → `isUrl`. Pro Datei also bis zu 66
Pattern-Kompilierungen, dazu 66× `toLowerCase` auf denselben Dateinamen.

**Anteil an der CPU: 9,5 % aller Samples** (304 von 3.186) liegen in
`java.util.regex`. `java.util.regex.Pattern` steht zusätzlich in den Top-12 der
allokierten Typen.

Vorschlag:

1. In `FileUtil` ein statisches, vorkompiliertes `Pattern` verwenden — oder ganz
   ohne Regex über `indexOf("://")` plus Whitespace-Prüfung.
2. `toLowerCase` aus der Schleife in `getAssociatedFormat` herausziehen, statt es
   in jedem `Format.match` erneut zu berechnen.
3. Gleiches `matches("\\S+://.+")` nochmal in
   `store/container/UnattachedFolder.java:111`.

Weitere `String.matches`-Aufrufe in `FileUtil` (Zeilen 1052–1272) betreffen die
Titel-Erkennung und tauchen im Scan-Profil nicht auf — niedrigere Priorität.

## Befund 3 — Threads sind unauffällig

10 `jdk.JavaMonitorEnter`-Events oberhalb der 10-ms-Schwelle in 141 s
(ConcurrentHashMap-Reservation, TaskQueue, Klassen-Initialisierung). Keine
Lock-Contention, keine blockierten Pools. Hier besteht kein Handlungsbedarf.

Zur Einordnung: 42,6 % der Samples berühren `org.h2` (`MediaTableFiles.getMediaInfo`
pro Datei). Das ist die eigentliche Scan-Arbeit, kein Defekt.

## Gegenmessung

Noch offen — die Zahlen oben sind die Vorher-Messung.

1. Build mit diesem Branch, Container mit denselben JFR-Flags starten.
2. Nach Abschluss des Scans dumpen und vergleichen:
   - `jdk.Deoptimization` mit `method = ...PropertyConverter.toBoolean` sollte auf 0 fallen
   - Anteil `commons.configuration2` an den `Media Scanner`-Samples
   - „Media scan completed in N seconds" im Log

**Voraussetzung:** Der Effekt tritt nur mit einer `UMS.conf` auf, die die leeren
Keys enthält. Eine frisch generierte Config kann das Problem verbergen — dann
fehlen die Keys, statt dass der Fix greift. Für einen belastbaren Vergleich die
Original-`UMS.conf` verwenden (Volume `ums_profile`).

Zweiter Punkt: gegen eine befüllte Datenbank messen, so wie die Basis-Aufnahme.
Ein Erstscan hat ein völlig anderes Profil, weil dann das Parsen der Medien
dominiert.

## Warnung zum Build

`build.sh` und `build_dev.sh` im Betriebs-Repo führen `git reset --hard` und
`git checkout <branch>` aus. Beide würden diesen Branch bzw. nicht committete
Änderungen verwerfen. Für Tests entweder die Skripte anpassen oder
`docker build` direkt aufrufen.
