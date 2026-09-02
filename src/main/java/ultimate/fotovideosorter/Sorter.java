package ultimate.fotovideosorter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class Sorter
{
	private static final int	PROGRESS_ITEMS		= 1000;
	private static final long	PROGRESS_MILLIS	= 5000L;
	private final Config			config;
	private final PathResolver		paths;
	private final AuditRepository	audit;
	private final RunLog			log;
	private final DateResolver		dates	= new DateResolver();

	Sorter(Config config, PathResolver paths, AuditRepository audit)
	{
		this(config, paths, audit, null);
	}

	Sorter(Config config, PathResolver paths, AuditRepository audit, RunLog log)
	{
		this.config = config;
		this.paths = paths;
		this.audit = audit;
		this.log = log;
	}

	List<Summary> run(List<Config.Profile> profiles, boolean dryRun) throws Exception
	{
		List<Summary> result = new ArrayList<Summary>();
		Set<Path> reserved = new HashSet<Path>();
		for(Config.Profile profile : profiles)
			result.add(runProfile(profile, dryRun, reserved));
		return result;
	}

	private Summary runProfile(Config.Profile profile, boolean dryRun, Set<Path> reserved) throws Exception
	{
		Summary s = new Summary(profile.name);
		Path sourceRoot = paths.resolve(profile.source);
		ConsoleOutput.info("[" + profile.name + "] Scanning " + sourceRoot.toAbsolutePath().normalize());
		if(!Files.isDirectory(sourceRoot))
		{
			s.missingSource = 1;
			record(profile.name, "MISSING_SOURCE", sourceRoot, null, "Source directory does not exist");
			ConsoleOutput.info("[" + profile.name + "] Source directory is missing; skipping profile");
			return s;
		}
		final int depth = profile.recursive ? Integer.MAX_VALUE : 1;
		FilterRules includes = new FilterRules(profile.include.isEmpty() ? config.include : profile.include);
		FilterRules excludes = new FilterRules(profile.exclude.isEmpty() ? config.exclude : profile.exclude);
		List<Path> files = new ArrayList<Path>();
		Progress scanProgress = new Progress(profile.name, "Scanning candidates", -1);
		try (Stream<Path> stream = Files.walk(sourceRoot, depth))
		{
			Iterator<Path> iterator = stream.filter(Files::isRegularFile).iterator();
			while(iterator.hasNext())
			{
				Path file = iterator.next();
				s.discovered++;
				String relative = Config.normalizeRelative(sourceRoot.relativize(file).toString());
				String filename = file.getFileName().toString();
				String extension = extension(filename).toLowerCase(Locale.ROOT);
				String logicalSource = paths.logical(profile.source.root, join(profile.source.path, relative));
				if((!includes.isEmpty() && !includes.matches(extension, filename, relative)) || excludes.matches(extension, filename, relative))
				{
					s.filtered++;
					record(profile.name, "FILTERED", file, null, logicalSource);
				}
				else
					files.add(file);
				scanProgress.update(files.size());
			}
		}
		Collections.sort(files);
		ConsoleOutput.info("[" + profile.name + "] Scan complete: " + s.discovered + " file(s) scanned, " + files.size() + " candidate(s) after filters");
		ZoneId zone = ZoneId.of(profile.timezone == null ? config.timezone : profile.timezone);
		Duration offset = Duration.parse(profile.dateTimeOffset == null ? "PT0S" : profile.dateTimeOffset);
		Instant cutoff = config.startDate == null ? null : Instant.parse(config.startDate);
		ConsoleOutput.info("[" + profile.name + "] Processing " + files.size() + " file(s)");
		Progress processingProgress = new Progress(profile.name, "Processing", files.size());
		for(int fileIndex = 0; fileIndex < files.size(); fileIndex++)
		{
			Path source = files.get(fileIndex);
			processingProgress.update(fileIndex + 1);
			String relative = Config.normalizeRelative(sourceRoot.relativize(source).toString());
			String logicalSource = paths.logical(profile.source.root, join(profile.source.path, relative));
			BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
			if(cutoff != null && latestFilesystemDate(attrs).isBefore(cutoff))
			{
				s.beforeCutoff++;
				record(profile.name, "BEFORE_CUTOFF", source, null,
						"created=" + attrs.creationTime().toInstant() + " modified=" + attrs.lastModifiedTime().toInstant());
				continue;
			}
			if(audit.contains(profile.name, logicalSource, attrs.size(), attrs.lastModifiedTime().toMillis()))
			{
				s.previouslyProcessed++;
				record(profile.name, "PREVIOUSLY_PROCESSED", source, null, logicalSource);
				continue;
			}
			DateResolver.Result date = dates.resolve(source, config.dateSources, zone, offset);
			if(date == null)
			{
				s.missingDate++;
				record(profile.name, "MISSING_DATE", source, null, logicalSource);
				continue;
			}
			Path destination = destination(profile, source, date.instant, zone, reserved);
			s.planned++;
			if(dryRun)
			{
				record(profile.name, "PLANNED", source, destination, date.source + " " + date.instant);
				continue;
			}
			try
			{
				copyAtomically(source, destination);
				AuditRepository.Record record = new AuditRepository.Record();
				record.profile = profile.name;
				record.logicalSource = logicalSource;
				record.physicalSource = source.toAbsolutePath().normalize().toString();
				record.filename = destination.getFileName().toString();
				Path targetBase = paths.resolve(config.target);
				String destRelative = Config.normalizeRelative(targetBase.relativize(destination).toString());
				record.logicalDestination = paths.logical(config.target.root, join(config.target.path, destRelative));
				record.physicalDestination = destination.toAbsolutePath().normalize().toString();
				record.size = attrs.size();
				record.modifiedMillis = attrs.lastModifiedTime().toMillis();
				record.resolvedDate = date.instant;
				record.dateSource = date.source;
				audit.insert(record);
				s.copied++;
				record(profile.name, "COPIED", source, destination, date.source + " " + date.instant);
			}
			catch(java.sql.SQLException e)
			{
				record(profile.name, "COPIED_AUDIT_FAILED", source, destination, e.getMessage());
				throw e;
			}
			catch(Exception e)
			{
				s.failed++;
				record(profile.name, "FAILED", source, destination, e.getMessage());
				ConsoleOutput.error("Copy failed: " + source + " -> " + destination + ": " + e.getMessage());
			}
		}
		ConsoleOutput.info("[" + profile.name + "] Processing complete: " + files.size() + " candidate(s)");
		return s;
	}

	private static Instant latestFilesystemDate(BasicFileAttributes attrs)
	{
		Instant created = attrs.creationTime().toInstant();
		Instant modified = attrs.lastModifiedTime().toInstant();
		return created.isAfter(modified) ? created : modified;
	}

	private void record(String profile, String status, Path source, Path destination, String detail) throws IOException
	{
		if(log != null)
			log.record(profile, status, source, destination, detail);
	}

	private static final class Progress
	{
		private final String	profile;
		private final String	phase;
		private final int	total;
		private int			lastCount;
		private long		lastTime = System.currentTimeMillis();

		Progress(String profile, String phase, int total)
		{
			this.profile = profile;
			this.phase = phase;
			this.total = total;
		}

		void update(int count)
		{
			long now = System.currentTimeMillis();
			if(count - lastCount < PROGRESS_ITEMS && now - lastTime < PROGRESS_MILLIS)
				return;
			if(total < 0)
				ConsoleOutput.info("[" + profile + "] " + phase + ": " + count + " file(s) found...");
			else
			{
				double percent = total == 0 ? 100.0 : count * 100.0 / total;
				ConsoleOutput.info(String.format(Locale.ROOT, "[%s] %s: %d/%d (%.1f%%)...", profile, phase, count, total, percent));
			}
			lastCount = count;
			lastTime = now;
		}
	}

	private Path destination(Config.Profile profile, Path source, Instant instant, ZoneId zone, Set<Path> reserved) throws IOException
	{
		ZonedDateTime time = instant.atZone(zone);
		String folder = DateTimeFormatter.ofPattern(config.folderPattern).format(time);
		Path base = paths.resolve(config.target);
		for(String part : Config.normalizeRelative(folder).split("/"))
			if(!part.isEmpty())
				base = base.resolve(part);
		String original = source.getFileName().toString(), ext = extensionWithDot(original), stem = stem(original);
		String name = ("*".equals(profile.filenamePattern) ? stem : DateTimeFormatter.ofPattern(profile.filenamePattern).format(time)) + (profile.suffix == null ? "" : profile.suffix) + ext;
		if(config.lowercaseFilename)
			name = name.toLowerCase(Locale.ROOT);
		Path candidate = base.resolve(name), normalized = candidate.toAbsolutePath().normalize();
		int counter = 1;
		while(Files.exists(candidate) || reserved.contains(normalized))
		{
			String suffix = String.format(Locale.ROOT, "%03d", counter++);
			candidate = base.resolve(stem(name) + (config.collisionSeparator == null ? "" : config.collisionSeparator) + suffix + extensionWithDot(name));
			normalized = candidate.toAbsolutePath().normalize();
		}
		reserved.add(normalized);
		return candidate;
	}

	private static void copyAtomically(Path source, Path destination) throws IOException
	{
		Files.createDirectories(destination.getParent());
		Path temp = Files.createTempFile(destination.getParent(), ".fvs-", ".tmp");
		try
		{
			Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			try
			{
				Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
			}
			catch(AtomicMoveNotSupportedException e)
			{
				Files.move(temp, destination);
			}
		}
		finally
		{
			Files.deleteIfExists(temp);
		}
	}

	private static final class FilterRules
	{
		private final Set<String>	extensions = new HashSet<String>();
		private final List<Pattern>	globs = new ArrayList<Pattern>();

		FilterRules(List<String> values)
		{
			if(values == null)
				return;
			for(String value : values)
			{
				if(value == null || value.isEmpty())
					continue;
				if(isGlob(value))
					globs.add(Pattern.compile(globRegex(value.replace('\\', '/')), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
				else
				{
					String extension = value.toLowerCase(Locale.ROOT);
					while(extension.startsWith("."))
						extension = extension.substring(1);
					if(!extension.isEmpty())
						extensions.add(extension);
				}
			}
		}

		boolean isEmpty()
		{
			return extensions.isEmpty() && globs.isEmpty();
		}

		boolean matches(String extension, String filename, String relative)
		{
			if(extensions.contains(extension))
				return true;
			for(Pattern glob : globs)
				if(glob.matcher(filename).matches() || glob.matcher(relative).matches())
					return true;
			return false;
		}

		private static boolean isGlob(String value)
		{
			return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
		}

		private static String globRegex(String glob)
		{
			StringBuilder regex = new StringBuilder("^");
			for(int i = 0; i < glob.length(); i++)
			{
				char c = glob.charAt(i);
				if(c == '*')
				{
					if(i + 1 < glob.length() && glob.charAt(i + 1) == '*')
					{
						regex.append(".*");
						i++;
					}
					else
						regex.append("[^/]*");
				}
				else if(c == '?')
					regex.append("[^/]");
				else
				{
					if("\\.[]{}()+-^$|".indexOf(c) >= 0)
						regex.append('\\');
					regex.append(c);
				}
			}
			return regex.append('$').toString();
		}
	}

	private static String extension(String n)
	{
		int i = n.lastIndexOf('.');
		return i > 0 && i < n.length() - 1 ? n.substring(i + 1) : "";
	}

	private static String extensionWithDot(String n)
	{
		String e = extension(n);
		return e.isEmpty() ? "" : "." + e;
	}

	private static String stem(String n)
	{
		String e = extensionWithDot(n);
		return e.isEmpty() ? n : n.substring(0, n.length() - e.length());
	}

	private static String join(String a, String b)
	{
		if(a == null || a.isEmpty())
			return Config.normalizeRelative(b);
		if(b == null || b.isEmpty())
			return Config.normalizeRelative(a);
		return Config.normalizeRelative(a + "/" + b);
	}

	static final class Summary
	{
		final String	profile;
		int				discovered, filtered, beforeCutoff, previouslyProcessed, planned, copied, failed, missingDate, missingSource;

		Summary(String p)
		{
			profile = p;
		}

		static String table(List<Summary> summaries, Summary total)
		{
			String[] headers = { "Profile", "Discovered", "Filtered", "Before cutoff", "Processed", "Planned", "Copied", "Failed", "Missing date", "Missing source" };
			List<Summary> rows = new ArrayList<Summary>(summaries);
			rows.add(total);
			int[] widths = new int[headers.length];
			for(int i = 0; i < headers.length; i++)
				widths[i] = headers[i].length();
			for(Summary row : rows)
			{
				String[] values = row.values();
				for(int i = 0; i < values.length; i++)
					widths[i] = Math.max(widths[i], values[i].length());
			}

			String divider = divider(widths);
			StringBuilder out = new StringBuilder();
			out.append(divider).append('\n');
			appendRow(out, headers, widths).append('\n').append(divider).append('\n');
			for(Summary summary : summaries)
				appendRow(out, summary.values(), widths).append('\n');
			out.append(divider).append('\n');
			appendRow(out, total.values(), widths).append('\n').append(divider);
			return out.toString();
		}

		private String[] values()
		{
			return new String[] { profile, Integer.toString(discovered), Integer.toString(filtered), Integer.toString(beforeCutoff), Integer.toString(previouslyProcessed), Integer.toString(planned),
					Integer.toString(copied), Integer.toString(failed), Integer.toString(missingDate), Integer.toString(missingSource) };
		}

		private static String divider(int[] widths)
		{
			StringBuilder line = new StringBuilder("+");
			for(int width : widths)
			{
				for(int i = 0; i < width + 2; i++)
					line.append('-');
				line.append('+');
			}
			return line.toString();
		}

		private static StringBuilder appendRow(StringBuilder out, String[] values, int[] widths)
		{
			out.append('|');
			for(int i = 0; i < values.length; i++)
			{
				out.append(' ');
				if(i == 0)
					out.append(String.format(Locale.ROOT, "%-" + widths[i] + "s", values[i]));
				else
					out.append(String.format(Locale.ROOT, "%" + widths[i] + "s", values[i]));
				out.append(" |");
			}
			return out;
		}

		void add(Summary o)
		{
			discovered += o.discovered;
			filtered += o.filtered;
			beforeCutoff += o.beforeCutoff;
			previouslyProcessed += o.previouslyProcessed;
			planned += o.planned;
			copied += o.copied;
			failed += o.failed;
			missingDate += o.missingDate;
			missingSource += o.missingSource;
		}
	}
}
