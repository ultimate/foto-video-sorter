package ultimate.fotovideosorter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest
{
	@TempDir
	Path temp;

	@Test
	void resolvesSameLogicalPathAcrossEnvironments()
	{
		Config c = base();
		Config.Environment second = new Config.Environment();
		second.roots.put("imports", temp.resolve("other-imports").toString());
		second.roots.put("photos", temp.resolve("other-photos").toString());
		c.environments.put("other", second);
		c.validate();
		Path a = new PathResolver(c, "test").resolve(c.profiles.get(0).source);
		Path b = new PathResolver(c, "other").resolve(c.profiles.get(0).source);
		assertNotEquals(a, b);
		assertEquals("imports:camera", new PathResolver(c, "test").logical("imports", "camera"));
	}

	@Test
	void rejectsTraversal()
	{
		Config c = base();
		c.profiles.get(0).source.path = "../secret";
		assertThrows(IllegalArgumentException.class, c::validate);
	}

	@Test
	void convertsPhysicalPathToLogical()
	{
		Config c = base();
		c.validate();
		PathResolver r = new PathResolver(c, "test");
		assertEquals("imports:camera/a.jpg", r.physicalToLogical(temp.resolve("imports/camera/a.jpg").toString()));
	}

	@Test
	void optionalProfileValuesMayBeOmittedOrNull() throws Exception
	{
		Path yaml = temp.resolve("config.yaml");
		String root = temp.toString().replace('\\', '/');
		String content = "environments:\n"
				+ "  test:\n"
				+ "    roots: { imports: \"" + root + "/imports\", photos: \"" + root + "/photos\" }\n"
				+ "target: { root: photos }\n"
				+ "include: [jpg]\n"
				+ "profiles:\n"
				+ "  - name: camera\n"
				+ "    source: { root: imports }\n"
				+ "    include:\n"
				+ "    exclude: null\n"
				+ "    timezone:\n"
				+ "    suffix: null\n"
				+ "    dateTimeOffset: null\n"
				+ "    includeByDefault: null\n";
		Files.write(yaml, content.getBytes(StandardCharsets.UTF_8));

		Config.Profile profile = Config.load(yaml).profiles.get(0);
		assertEquals(Collections.emptyList(), profile.include);
		assertEquals(Collections.emptyList(), profile.exclude);
		assertEquals(null, profile.timezone);
		assertEquals("", profile.suffix);
		assertEquals("PT0S", profile.dateTimeOffset);
		assertEquals(true, profile.includeByDefault);
	}

	Config base()
	{
		Config c = new Config();
		
		Config.Environment e = new Config.Environment();
		e.roots = new LinkedHashMap<String, String>();
		e.roots.put("imports", temp.resolve("imports").toString());
		e.roots.put("photos", temp.resolve("photos").toString());
		
		Config.Profile p = new Config.Profile();
		p.name = "camera";
		p.source = new Config.LogicalPath();
		p.source.root = "imports";
		p.source.path = "camera";
		
		c.environments.put("test", e);
		c.target = new Config.LogicalPath();
		c.target.root = "photos";
		c.target.path = "sorted";
		c.profiles.add(p);
		return c;
	}
	
	@Test
	void acceptsLinuxAndUncMappingsOnAnyHost()
	{
		Config c = new Config();
		
		Config.Environment e = new Config.Environment();
		e.roots.put("photos", "/etc/share/photos");
		e.roots.put("imports", "//192.168.0.10/imports");
		
		Config.Profile p = new Config.Profile();
		p.name = "camera";
		p.source = new Config.LogicalPath();
		p.source.root = "imports";
		
		c.environments.put("nas", e);
		c.target = new Config.LogicalPath();
		c.target.root = "photos";
		c.profiles = Collections.singletonList(p);
		
		assertDoesNotThrow(c::validate);
	}
}
