package ultimate.fotovideosorter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SorterTest
{
	@TempDir
	Path temp;

	@Test
	void copiesAuditsAndSkipsOnSecondRun() throws Exception
	{
		Fixture f = fixture();
		Path source = f.source.resolve("Photo.JPG");
		Files.write(source, "photo".getBytes(StandardCharsets.UTF_8));
		try (AuditRepository audit = new AuditRepository(temp.resolve("audit.db")))
		{
			Sorter sorter = new Sorter(f.config, f.paths, audit);
			Sorter.Summary first = sorter.run(f.config.profiles, false).get(0);
			assertEquals(1, first.copied);
			assertEquals(1, Files.walk(f.target).filter(Files::isRegularFile).count());
			Sorter.Summary second = sorter.run(f.config.profiles, false).get(0);
			assertEquals(1, second.previouslyProcessed);
			assertEquals(0, second.copied);
			AuditRepository.Query q = new AuditRepository.Query();
			q.profile = "camera";
			assertEquals(1, audit.query(q).size());
		}
	}

	@Test
	void dryRunPlansWithoutCopyOrAudit() throws Exception
	{
		Fixture f = fixture();
		Files.write(f.source.resolve("a.jpg"), new byte[] { 1 });
		try (AuditRepository audit = AuditRepository.memory())
		{
			Sorter.Summary s = new Sorter(f.config, f.paths, audit).run(f.config.profiles, true).get(0);
			assertEquals(1, s.planned);
			assertEquals(0, s.copied);
			assertFalse(Files.exists(f.target));
			assertTrue(audit.query(new AuditRepository.Query()).isEmpty());
		}
	}

	@Test
	void usesConfigurableCollisionSeparator() throws Exception
	{
		Fixture f = fixture();
		f.config.collisionSeparator = " ";
		Files.write(f.source.resolve("a.jpg"), new byte[] { 1 });
		Files.write(f.source.resolve("b.jpg"), new byte[] { 2 });
		f.config.profiles.get(0).filenamePattern = "yyyy";
		try (AuditRepository audit = AuditRepository.memory())
		{
			new Sorter(f.config, f.paths, audit).run(f.config.profiles, false);
			assertEquals(2, Files.walk(f.target).filter(Files::isRegularFile).count());
			assertTrue(Files.walk(f.target).anyMatch(p -> p.getFileName().toString().matches(".* 001\\.jpg")));
		}
	}

	@Test
	void formatsSummariesAsAlignedAsciiTable()
	{
		Sorter.Summary camera = new Sorter.Summary("camera");
		camera.discovered = 6;
		camera.filtered = 1;
		camera.beforeCutoff = 5;
		Sorter.Summary export = new Sorter.Summary("dji-export");
		export.discovered = 83;
		export.filtered = 83;
		Sorter.Summary total = new Sorter.Summary("TOTAL");
		total.add(camera);
		total.add(export);

		String expected = "+------------+------------+----------+---------------+-----------+---------+--------+--------+--------------+----------------+\n"
				+ "| Profile    | Discovered | Filtered | Before cutoff | Processed | Planned | Copied | Failed | Missing date | Missing source |\n"
				+ "+------------+------------+----------+---------------+-----------+---------+--------+--------+--------------+----------------+\n"
				+ "| camera     |          6 |        1 |             5 |         0 |       0 |      0 |      0 |            0 |              0 |\n"
				+ "| dji-export |         83 |       83 |             0 |         0 |       0 |      0 |      0 |            0 |              0 |\n"
				+ "+------------+------------+----------+---------------+-----------+---------+--------+--------+--------------+----------------+\n"
				+ "| TOTAL      |         89 |       84 |             5 |         0 |       0 |      0 |      0 |            0 |              0 |\n"
				+ "+------------+------------+----------+---------------+-----------+---------+--------+--------+--------------+----------------+";
		assertEquals(expected, Sorter.Summary.table(Arrays.asList(camera, export), total));
	}

	@Test
	void dryRunLogContainsEveryFileAndPlannedDestination() throws Exception
	{
		Fixture f = fixture();
		Path included = f.source.resolve("photo.jpg");
		Path filtered = f.source.resolve("notes.txt");
		Files.write(included, new byte[] { 1 });
		Files.write(filtered, new byte[] { 2 });
		Path logFile;
		try (RunLog log = new RunLog(temp.resolve("logs"), true); AuditRepository audit = AuditRepository.memory())
		{
			logFile = log.file();
			new Sorter(f.config, f.paths, audit, log).run(f.config.profiles, true);
		}

		String contents = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
		String planned = Arrays.stream(contents.split("\\R")).filter(line -> line.contains("\tPLANNED\t")).findFirst().get();
		String[] columns = planned.split("\t", -1);
		assertEquals(included.toAbsolutePath().normalize().toString(), columns[2]);
		assertFalse(columns[3].isEmpty());
		assertTrue(contents.contains("\tFILTERED\t" + filtered.toAbsolutePath().normalize()));
	}

	private Fixture fixture() throws Exception
	{
		Fixture f = new Fixture();
		f.config = new Config();
		Config.Environment e = new Config.Environment();
		f.source = temp.resolve("imports/camera");
		f.target = temp.resolve("photos/sorted");
		Files.createDirectories(f.source);
		e.roots.put("imports", temp.resolve("imports").toString());
		e.roots.put("photos", temp.resolve("photos").toString());
		f.config.environments.put("test", e);
		f.config.target = new Config.LogicalPath();
		f.config.target.root = "photos";
		f.config.target.path = "sorted";
		f.config.folderPattern = "yyyy/MM/dd";
		f.config.dateSources = Collections.singletonList(Config.DateSource.MODIFIED);
		f.config.timezone = "UTC";
		f.config.startDate = "2000-01-01T00:00:00Z";
		f.config.lowercaseFilename = true;
		Config.Profile p = new Config.Profile();
		p.name = "camera";
		p.source = new Config.LogicalPath();
		p.source.root = "imports";
		p.source.path = "camera";
		p.include = Collections.singletonList("jpg");
		f.config.profiles.add(p);
		f.config.validate();
		f.paths = new PathResolver(f.config, "test");
		return f;
	}

	static class Fixture
	{
		Config			config;
		PathResolver	paths;
		Path			source, target;
	}
}
