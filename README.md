# foto-video-sorter

## Requirements
- tool in java
- compatible with java 8 (to be able to run under java version 1.8.0_151)
- runnable from the command line without ui and via cron
- configurable via file
- should scan folders defined in config for newly added files and copy them according to the folder config
- should keep record of files processed to not process them on the next run again
- ignore not existing folders
- handle collisions by suffixing 001, 002, etc.
- support dry run for testing


## Config
What we need...
Global
- folder name
- target folder path (root)
- folder structure (e.g. "yyyy/yyyy.MM.dd")
- convert to lower case (true/false)
- global include filter (multiple values, e.g. "jpg", "mpg")
- global exclude filter (multiple values, e.g. "txt", "srt")

For each folder
- source folder path
- date source (e.g "capture date" or "modified date")
- suffix (e.g. " Panorama-Teil" or "")
- file name pattern (e.g. "yyyy.MM.dd hh.mm.SS" or "*" (keep original name))
- optional script to execute
- date time offset
- include filter (multiple values, e.g. "jpg", "mpg")
- exclude filter (multiple values, e.g. "txt", "srt")
