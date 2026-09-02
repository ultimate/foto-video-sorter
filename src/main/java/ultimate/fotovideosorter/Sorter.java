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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

final class Sorter
{
	private final Config			config;
	private final PathResolver		paths;
	private final AuditRepository	audit;
	private final DateResolver		dates	= new DateResolver();

	Sorter(Config config, PathResolver paths, AuditRepository audit)
	{
		this.config = config;
		this.paths = paths;
		this.audit = audit;
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
		if(!Files.isDirectory(sourceRoot))
		{
			s.missingSource = 1;
			return s;
		}
		final int depth = profile.recursive ? Integer.MAX_VALUE : 1;
		List<Path> files = new ArrayList<Path>();
		try (Stream<Path> stream = Files.walk(sourceRoot, depth))
		{
			stream.filter(Files::isRegularFile).forEach(files::add);
		}
		Collections.sort(files);
		Set<String> includes = extensions(profile.include.isEmpty() ? config.include : profile.include);
		Set<String> excludes = extensions(profile.exclude.isEmpty() ? config.exclude : profile.exclude);
		ZoneId zone = ZoneId.of(profile.timezone == null ? config.timezone : profile.timezone);
		Duration offset = Duration.parse(profile.dateTimeOffset == null ? "PT0S" : profile.dateTimeOffset);
		Instant cutoff = config.startDate == null ? null : Instant.parse(config.startDate);
		for(Path source : files)
		{
			s.discovered++;
			String extension = extension(source.getFileName().toString()).toLowerCase(Locale.ROOT);
			if((!includes.isEmpty() && !includes.contains(extension)) || excludes.contains(extension))
			{
				s.filtered++;
				continue;
			}
			BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
			String relative = Config.normalizeRelative(sourceRoot.relativize(source).toString());
			String logicalSource = paths.logical(profile.source.root, join(profile.source.path, relative));
			if(audit.contains(profile.name, logicalSource, attrs.size(), attrs.lastModifiedTime().toMillis()))
			{
				s.previouslyProcessed++;
				continue;
			}
			DateResolver.Result date = dates.resolve(source, config.dateSources, zone, offset);
			if(date == null)
			{
				s.missingDate++;
				continue;
			}
			if(cutoff != null && date.instant.isBefore(cutoff))
			{
				s.beforeCutoff++;
				continue;
			}
			Path destination = destination(profile, source, date.instant, zone, reserved);
			s.planned++;
			if(dryRun)
				continue;
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
			}
			catch(java.sql.SQLException e)
			{
				throw e;
			}
			catch(Exception e)
			{
				s.failed++;
				System.err.println("Copy failed: " + source + " -> " + destination + ": " + e.getMessage());
			}
		}
		return s;
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

	private static Set<String> extensions(List<String> values)
	{
		Set<String> r = new HashSet<String>();
		if(values != null)
			for(String v : values)
			{
				String n = v.toLowerCase(Locale.ROOT);
				while(n.startsWith("."))
					n = n.substring(1);
				if(!n.isEmpty())
					r.add(n);
			}
		return r;
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

		String line()
		{
			return String.format(Locale.ROOT, "%-20s discovered=%d filtered=%d before-cutoff=%d processed=%d planned=%d copied=%d failed=%d missing-date=%d missing-source=%d", profile, discovered,
					filtered, beforeCutoff, previouslyProcessed, planned, copied, failed, missingDate, missingSource);
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
