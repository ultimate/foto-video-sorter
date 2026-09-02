package ultimate.fotovideosorter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "foto-video-sorter", mixinStandardHelpOptions = true, version = "foto-video-sorter 1.0", description = "Sort photos and videos into date-based folders.", subcommands = { App.Run.class,
		App.Audit.class })
public final class App implements Runnable
{
	public static void main(String[] args)
	{
		CommandLine cli = new CommandLine(new App()).setCaseInsensitiveEnumValuesAllowed(true);
		cli.setExecutionExceptionHandler((error, commandLine, parseResult) -> {
			commandLine.getErr().println("Error: " + error.getMessage());
			return commandLine.getCommandSpec().exitCodeOnExecutionException();
		});
		System.exit(cli.execute(args));
	}

	@Override
	public void run()
	{
		CommandLine.usage(this, System.out);
	}

	abstract static class Configured implements Callable<Integer>
	{
		@Option(names = "--config", required = true, paramLabel = "<yaml>", description = "YAML configuration file")
		Path	configFile;
		@Option(names = "--environment", required = true, paramLabel = "<name>", description = "Path environment name")
		String	environment;
		Config	config;

		Path working(Path path)
		{
			return path.isAbsolute() ? path : Paths.get("").toAbsolutePath().resolve(path).normalize();
		}

		void load() throws Exception
		{
			config = Config.load(working(configFile));
			if(!config.environments.containsKey(environment))
				throw new IllegalArgumentException("Unknown environment: " + environment);
		}

		Path database()
		{
			return working(Paths.get(config.database));
		}
	}

	@Command(name = "run", mixinStandardHelpOptions = true, description = "Scan and copy files.")
	static final class Run extends Configured
	{
		@Option(names = "--profiles", defaultValue = "all", paramLabel = "all|name1,name2")
		String	profileSelection;
		@Option(names = "--dry-run", description = "Plan without copying or changing audit data")
		boolean	dryRun;

		@Override
		public Integer call() throws Exception
		{
			load();
			List<Config.Profile> selected = select(config, profileSelection);
			Path db = database(), lock = db.resolveSibling(db.getFileName().toString() + ".lock");
			Path logDirectory = db.toAbsolutePath().normalize().getParent();
			try (RunLock ignored = new RunLock(lock); RunLog runLog = new RunLog(logDirectory, dryRun);
					AuditRepository repository = dryRun && !java.nio.file.Files.exists(db) ? AuditRepository.memory() : new AuditRepository(db))
			{
				System.out.println("Run log: " + runLog.file());
				List<Sorter.Summary> summaries = new Sorter(config, new PathResolver(config, environment), repository, runLog).run(selected, dryRun);
				Sorter.Summary total = new Sorter.Summary("TOTAL");
				for(Sorter.Summary summary : summaries)
					total.add(summary);
				System.out.println(Sorter.Summary.table(summaries, total));
				return total.failed == 0 ? 0 : 2;
			}
		}

		private static List<Config.Profile> select(Config c, String value)
		{
			List<Config.Profile> selected = new ArrayList<Config.Profile>();
			if("all".equalsIgnoreCase(value))
			{
				for(Config.Profile p : c.profiles)
					if(p.includeByDefault)
						selected.add(p);
				return selected;
			}
			Set<String> requested = new LinkedHashSet<String>(Arrays.asList(value.split(",")));
			for(Config.Profile p : c.profiles)
				if(requested.remove(p.name))
					selected.add(p);
			if(!requested.isEmpty())
				throw new IllegalArgumentException("Unknown profile(s): " + requested);
			if(selected.isEmpty())
				throw new IllegalArgumentException("No profiles selected");
			return selected;
		}
	}

	@Command(name = "audit", description = "Inspect processing history.", subcommands = { AuditQuery.class })
	static final class Audit implements Runnable
	{
		@Override
		public void run()
		{
			CommandLine.usage(this, System.out);
		}
	}

	@Command(name = "query", mixinStandardHelpOptions = true, description = "Query processing history; criteria are combined with AND.")
	static final class AuditQuery extends Configured
	{
		@Option(names = "--source")
		String	source;
		@Option(names = "--destination")
		String	destination;
		@Option(names = "--filename")
		String	filename;
		@Option(names = "--profile")
		String	profile;
		@Option(names = "--processed-from")
		String	processedFrom;
		@Option(names = "--processed-to")
		String	processedTo;
		@Option(names = "--resolved-from")
		String	resolvedFrom;
		@Option(names = "--resolved-to")
		String	resolvedTo;

		@Override
		public Integer call() throws Exception
		{
			load();
			validateInstant(processedFrom);
			validateInstant(processedTo);
			validateInstant(resolvedFrom);
			validateInstant(resolvedTo);
			PathResolver resolver = new PathResolver(config, environment);
			AuditRepository.Query query = new AuditRepository.Query();
			query.source = pathCriterion(source, resolver);
			query.destination = pathCriterion(destination, resolver);
			query.filename = filename;
			query.profile = profile;
			query.processedFrom = instant(processedFrom);
			query.processedTo = instant(processedTo);
			query.resolvedFrom = instant(resolvedFrom);
			query.resolvedTo = instant(resolvedTo);
			if(!java.nio.file.Files.isRegularFile(database()))
				throw new IllegalArgumentException("Audit database does not exist: " + database());
			try (AuditRepository repository = new AuditRepository(database()))
			{
				List<AuditRepository.Record> rows = repository.query(query);
				for(AuditRepository.Record r : rows)
					print(r);
				System.out.println("Records: " + rows.size());
			}
			return 0;
		}

		private String pathCriterion(String value, PathResolver resolver)
		{
			if(value == null)
				return null;
			int colon = value.indexOf(':');
			if(colon > 0 && config.environments.get(environment).roots.containsKey(value.substring(0, colon)))
				return value.replace('\\', '/');
			return resolver.physicalToLogical(value);
		}

		private static void validateInstant(String value)
		{
			if(value != null)
				Instant.parse(value);
		}

		private static String instant(String value)
		{
			return value == null ? null : Instant.parse(value).toString();
		}

		private static void print(AuditRepository.Record r)
		{
			System.out.println("---");
			System.out.println("profile: " + r.profile);
			System.out.println("source: " + r.logicalSource);
			System.out.println("physicalSource: " + r.physicalSource);
			System.out.println("destination: " + r.logicalDestination);
			System.out.println("physicalDestination: " + r.physicalDestination);
			System.out.println("filename: " + r.filename);
			System.out.println("size: " + r.size);
			System.out.println("modifiedMillis: " + r.modifiedMillis);
			System.out.println("resolvedDate: " + r.resolvedDate);
			System.out.println("dateSource: " + r.dateSource);
			System.out.println("processedDate: " + r.processedDate);
		}
	}
}
