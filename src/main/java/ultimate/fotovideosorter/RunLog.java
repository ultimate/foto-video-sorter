package ultimate.fotovideosorter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class RunLog implements AutoCloseable
{
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
	private final Path			file;
	private final BufferedWriter	writer;

	RunLog(Path directory, boolean dryRun) throws IOException
	{
		Files.createDirectories(directory);
		String base = "foto-video-sorter-" + FILE_TIME.format(Instant.now());
		Path candidate = directory.resolve(base + ".log");
		int suffix = 1;
		while(Files.exists(candidate))
			candidate = directory.resolve(base + "-" + suffix++ + ".log");
		file = candidate;
		writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		writer.write("# started=" + Instant.now() + " mode=" + (dryRun ? "dry-run" : "copy"));
		writer.newLine();
		writer.write("profile\tstatus\tsource\tdestination\tdetail");
		writer.newLine();
		writer.flush();
	}

	Path file()
	{
		return file;
	}

	void record(String profile, String status, Path source, Path destination, String detail) throws IOException
	{
		writer.write(clean(profile));
		writer.write('\t');
		writer.write(clean(status));
		writer.write('\t');
		writer.write(clean(source == null ? "" : source.toAbsolutePath().normalize().toString()));
		writer.write('\t');
		writer.write(clean(destination == null ? "" : destination.toAbsolutePath().normalize().toString()));
		writer.write('\t');
		writer.write(clean(detail));
		writer.newLine();
		writer.flush();
	}

	private static String clean(String value)
	{
		return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
	}

	@Override
	public void close() throws IOException
	{
		writer.close();
	}
}
