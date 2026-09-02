# foto-video-sorter

A Java 8 command-line tool that copies photos and videos into date-based folders. It is designed for unattended NAS cron jobs and on-demand runs from another computer using the same YAML configuration and SQLite audit history.

## Build

Java 8 or newer and Maven 3.9+ are required.

```shell
mvn clean package
java -jar target/foto-video-sorter.jar --help
```

The build produces one executable JAR containing all dependencies.

## Quick start

Keep the JAR, YAML, and audit database in the same NAS directory and change to it before running:

```shell
java -jar foto-video-sorter.jar run --config config.yaml --environment nas --profiles all --dry-run
java -jar foto-video-sorter.jar run --config config.yaml --environment nas --profiles all
```

From Windows, navigate to the same NAS directory and select the network mappings:

```powershell
Set-Location \\192.168.0.10\tools\foto-video-sorter
java -jar foto-video-sorter.jar run --config config.yaml --environment network --profiles camera,phone
```

See [`config.example.yaml`](config.example.yaml) for a complete configuration.

## Paths and environments

`environments` maps stable logical root names to platform-specific absolute paths:

```yaml
environments:
  nas:
    roots:
      photos: /etc/share/photos
      imports: /etc/share/imports
  network:
    roots:
      photos: //192.168.0.10/photos
      imports: //192.168.0.10/imports

target: { root: photos, path: sorted }
```

Every environment must define every referenced root. Logical paths are relative and use `/` on every platform. Absolute logical paths, unknown roots, and `..` traversal are rejected. Environment mappings accept Linux absolute paths, Windows drive paths, and UNC paths.

The destination is `<target root>/<target path>/<folderPattern>/<filename>`.

## Configuration reference

Global settings:

| Setting | Meaning |
| --- | --- |
| `folderPattern` | Java date/time pattern, e.g. `yyyy/yyyy.MM.dd` |
| `lowercaseFilename` | Lowercase the generated/preserved base filename and extension before adding the profile suffix |
| `include`, `exclude` | Case-insensitive extension or glob lists; dots are optional for extensions |
| `dateSources` | Ordered fallbacks: `GPS`, `CAPTURE`, `CREATED`, `MODIFIED` |
| `timezone` | IANA zone for formatting and zone-less metadata |
| `startDate` | Optional inclusive ISO-8601 filesystem cutoff; files are eligible when created or modified on/after it |
| `collisionSeparator` | Text before a three-digit collision counter |
| `database` | Working-directory-relative SQLite filename |

Profile settings:

| Setting | Meaning |
| --- | --- |
| `name` | Unique, stable profile/audit identity |
| `source` | Logical `root` and relative `path` |
| `filenamePattern` | Java date/time pattern, or `*` to preserve the stem |
| `suffix` | Text inserted before the extension |
| `timezone` | Optional global-zone override |
| `dateTimeOffset` | Camera-clock correction with `y`, `M`, `d`, `h`, `m`, `s`; legacy ISO-8601 durations remain accepted |
| `include`, `exclude` | Non-empty extension/glob lists override corresponding global lists |
| `recursive` | Scan nested source folders |
| `includeByDefault` | Select with `--profiles all` |

Optional profile settings may be omitted. An omitted or YAML `null` value keeps the profile default; `include`, `exclude`, and `timezone` then use the corresponding global behavior.

Profile suffixes preserve their configured capitalization. When `lowercaseFilename` is enabled, lowercasing is applied to the base filename and extension before the suffix is inserted.

Camera-clock corrections use calendar arithmetic in the profile/global timezone and apply only when `CAPTURE` supplies the destination date:

```yaml
dateTimeOffset: { y: 0, M: 0, d: -1, h: -2, m: -15, s: 0 }
```

`GPS` reads the UTC GPS timestamp embedded by supported devices. It is the first default source because satellite-derived time is normally more reliable than the camera clock, but it may be missing or contain bad device metadata; explicit `dateSources` ordering remains authoritative. GPS and filesystem dates are never changed by `dateTimeOffset`.

Plain filter entries such as `jpg` or `.mp4` match file extensions. Entries containing glob syntax match filenames and profile-relative paths, case-insensitively. `*` matches within one path segment, `**` crosses folders, and `?` matches one character. For example, `exclude: [".trashed-*", "cache/**"]` excludes trashed filenames anywhere and every file below the relative `cache` folder. Exclude rules take precedence over include rules.

Existing names are never overwritten. With separator `_`, the first collision for `photo.jpg` is `photo_001.jpg`.

## Processing and auditing

The cutoff is checked first using only filesystem creation and modification timestamps; embedded capture metadata is not read for files rejected by it. A file is eligible when the newer of its creation and modification times is on or after `startDate`. For eligible files, destination date lookup then follows the configured `dateSources` order. Files without any available destination date or rejected by filters are reported but not audited. Files are audited only after a successful copy. Audit identity is profile plus logical source path, size, and last-modified time, so Linux and UNC views share identity while changed sources can be processed again.

Copies are staged beside the destination and finalized atomically when supported. A lock beside the database prevents overlapping runs. SQLite over SMB depends on correct NAS/SMB file locking; do not run cron and interactive invocations concurrently.

Dry run performs scanning, metadata resolution, audit checks, naming, and collision planning, but does not copy or add audit records. With no existing database it creates none.

Every run creates a timestamped `foto-video-sorter-*.log` file beside the audit database. The tab-separated log contains every discovered source, its outcome, and its destination when copied or planned. Dry-run mappings use the `PLANNED` status, making them directly reviewable before a real run.

Each run reports timestamped profile scan and processing phases, with progress updates every 1,000 eligible candidates or approximately five seconds during active work. Timestamps use local time and include the UTC offset. Filtering happens during the scan; scan completion shows both the total files encountered and the candidates retained, while processing progress counts candidates only. The run then prints per-profile and total counters. Missing source directories are reported rather than treated as fatal.

## Audit queries

Criteria are exact and combined with AND. Range endpoints are inclusive ISO-8601 instants.

```shell
java -jar foto-video-sorter.jar audit query --config config.yaml --environment nas --filename IMG_0001.JPG
java -jar foto-video-sorter.jar audit query --config config.yaml --environment network --source //192.168.0.10/imports/camera/DCIM/IMG_0001.JPG
java -jar foto-video-sorter.jar audit query --config config.yaml --environment nas --profile camera --processed-from 2026-01-01T00:00:00Z
```

Paths may be physical or logical values such as `imports:camera/DCIM/IMG_0001.JPG`. Results show logical and observed physical paths, fingerprint, resolved date/source, and processing time.

## Cron

```cron
15 2 * * * cd /etc/share/tools/foto-video-sorter && /usr/bin/java -jar foto-video-sorter.jar run --config config.yaml --environment nas --profiles all >> sorter-cron.log 2>&1
```

Remote-host copy optimization, scripts, panorama processing, and GPS/location analysis are deferred beyond v1.

Licensed under MIT.
