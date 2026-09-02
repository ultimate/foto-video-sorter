package ultimate.fotovideosorter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class Config
{
	public Map<String, Environment>	environments		= new LinkedHashMap<String, Environment>();
	public LogicalPath				target;
	public String					folderPattern		= "yyyy/yyyy.MM.dd";
	public boolean					lowercaseFilename;
	public List<String>				include				= new ArrayList<String>();
	public List<String>				exclude				= new ArrayList<String>();
	public List<DateSource>			dateSources			= Arrays.asList(DateSource.CAPTURE, DateSource.CREATED, DateSource.MODIFIED);
	public String					timezone			= "UTC";
	public String					startDate;
	public String					collisionSeparator	= "_";
	public String					database			= "foto-video-sorter.db";
	public List<Profile>			profiles			= new ArrayList<Profile>();

	public static Config load(Path file) throws IOException
	{
		ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
		// A blank optional profile value in YAML should behave like an omitted value:
		// retain the profile default, which in turn falls back to the global setting.
		mapper.configOverride(Profile.class).setSetterInfo(JsonSetter.Value.forValueNulls(Nulls.SKIP));
		Config config = mapper.readValue(Files.newInputStream(file), Config.class);
		config.validate();
		return config;
	}

	public void validate()
	{
		if(environments == null || environments.isEmpty())
			fail("At least one environment is required");
		if(target == null)
			fail("target is required");
		target.validate("target");
		if(profiles == null || profiles.isEmpty())
			fail("At least one profile is required");
		if(dateSources == null || dateSources.isEmpty())
			fail("dateSources must not be empty");
		ZoneId.of(timezone);
		if(startDate != null)
			Instant.parse(startDate);
		validatePattern(folderPattern, "folderPattern");
		Set<String> names = new HashSet<String>();
		for(Map.Entry<String, Environment> env : environments.entrySet())
		{
			if(env.getKey().trim().isEmpty() || env.getValue() == null)
				fail("Invalid environment");
			env.getValue().validate(env.getKey());
		}
		for(Profile p : profiles)
		{
			if(p == null)
				fail("Profiles must not be null");
			if(p.name == null || p.name.trim().isEmpty() || !names.add(p.name))
				fail("Profile names must be non-empty and unique");
			if(p.source == null)
				fail("source is required for profile " + p.name);
			p.source.validate("profile " + p.name + " source");
			if(p.filenamePattern == null)
				p.filenamePattern = "*";
			if(p.suffix == null)
				p.suffix = "";
			if(p.dateTimeOffset == null)
				p.dateTimeOffset = "PT0S";
			if(p.include == null)
				p.include = new ArrayList<String>();
			if(p.exclude == null)
				p.exclude = new ArrayList<String>();
			if(p.recursive == null)
				p.recursive = false;
			if(p.includeByDefault == null)
				p.includeByDefault = true;
			if(!"*".equals(p.filenamePattern))
				validatePattern(p.filenamePattern, "filenamePattern for " + p.name);
			if(p.timezone != null)
				ZoneId.of(p.timezone);
			Duration.parse(p.dateTimeOffset);
		}
		for(String environment : environments.keySet())
			validateRoots(environment);
		if(database == null || database.trim().isEmpty() || Paths.get(database).isAbsolute())
			fail("database must be a non-empty working-directory-relative path");
	}

	private void validateRoots(String envName)
	{
		Set<String> roots = environments.get(envName).roots.keySet();
		if(!roots.contains(target.root))
			fail("Environment " + envName + " does not define target root " + target.root);
		for(Profile p : profiles)
			if(!roots.contains(p.source.root))
				fail("Environment " + envName + " does not define source root " + p.source.root);
	}

	private static void validatePattern(String value, String name)
	{
		try
		{
			DateTimeFormatter.ofPattern(value);
		}
		catch(IllegalArgumentException e)
		{
			fail("Invalid " + name + ": " + e.getMessage());
		}
	}

	private static void fail(String message)
	{
		throw new IllegalArgumentException(message);
	}

	public enum DateSource
	{
		CAPTURE, CREATED, MODIFIED
	}

	public static final class Environment
	{
		public Map<String, String> roots = new LinkedHashMap<String, String>();

		void validate(String name)
		{
			if(roots == null || roots.isEmpty())
				fail("Environment " + name + " has no roots");
			for(Map.Entry<String, String> root : roots.entrySet())
			{
				if(root.getKey().trim().isEmpty() || root.getValue() == null || !isPlatformAbsolute(root.getValue()))
					fail("Root " + root.getKey() + " in environment " + name + " must be absolute");
			}
		}

		private static boolean isPlatformAbsolute(String value)
		{
			return Paths.get(value).isAbsolute() || value.startsWith("/") || value.matches("^[A-Za-z]:[\\\\/].*") || value.startsWith("\\\\");
		}
	}

	public static final class LogicalPath
	{
		public String	root;
		public String	path	= "";

		void validate(String label)
		{
			if(root == null || root.trim().isEmpty())
				fail(label + ".root is required");
			String p = path == null ? "" : path.replace('\\', '/');
			if(p.startsWith("/") || p.matches("^[A-Za-z]:.*"))
				fail(label + ".path must be relative");
			for(String part : p.split("/"))
				if("..".equals(part))
					fail(label + ".path may not contain '..'");
			path = normalizeRelative(p);
		}
	}

	public static final class Profile
	{
		public String		name;
		public LogicalPath	source;
		public String		filenamePattern		= "*";
		public String		suffix				= "";
		public String		timezone;
		public String		dateTimeOffset		= "PT0S";
		public List<String>	include				= new ArrayList<String>();
		public List<String>	exclude				= new ArrayList<String>();
		public Boolean		recursive			= false;
		public Boolean		includeByDefault	= true;
	}

	static String normalizeRelative(String value)
	{
		String[] parts = value.replace('\\', '/').split("/");
		List<String> clean = new ArrayList<String>();
		for(String part : parts)
			if(!part.isEmpty() && !".".equals(part))
				clean.add(part);
		return String.join("/", clean);
	}
}
