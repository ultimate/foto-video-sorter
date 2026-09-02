# foto-video-sorter

## Requirements
- tool in java
- compatible with java 8 (to be able to run under java version 1.8.0_151)
- runnable from the command line without ui and via cron
- configurable via file
- should scan folders defined in config for newly added files and copy them according to the folder config
- should keep audit log of files processed to not process them on the next run again, even if target folder was renamed (e.g. if I add a description to the folder name)
- ignore not existing folders
- handle collisions by suffixing 001, 002, etc.
- support dry run for testing
- use config by parameter
- allow choosing profile(s) (all or by name)
- support to set a start date via config and ignore all older files
- output a summary to the console by profile (e.g. number of new files found, number copied, number ignored, etc.)

## Config
What we need...
Global
- folder name
- target folder path (root)
- folder structure (e.g. "yyyy/yyyy.MM.dd")
- convert to lower case (true/false)
- global include filter (multiple values, e.g. "jpg", "mpg")
- global exclude filter (multiple values, e.g. "txt", "srt")
- global date filter (file date)

For each folder / profile
- Profile name
- source folder path
- date source (e.g "capture date" or "modified date")
- suffix (e.g. " Panorama-Teil" or "")
- file name pattern (e.g. "yyyy.MM.dd hh.mm.SS" or "*" (keep original name))
- optional script to execute
- date time offset
- include filter (multiple values, e.g. "jpg", "mpg")
- exclude filter (multiple values, e.g. "txt", "srt")
- include subfolders (true/false)
- include by default when all profiles are run (true/false)

## TBD
- use efficient copying when file is copied from one folder of the same network device to another (e.g. run copy command on the remote machine instead of transferring the file content forth and back to the machine running the program)
- image processing for spherical panorama
- gps position analysis and location (city, mountain, etc.) lookup for putting a locations.txt to the folder