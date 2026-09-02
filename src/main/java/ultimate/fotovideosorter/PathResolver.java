package ultimate.fotovideosorter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

final class PathResolver
{
	private final Config.Environment environment;

	PathResolver(Config config, String name)
	{
		this.environment = config.environments.get(name);
		if(environment == null)
			throw new IllegalArgumentException("Unknown environment: " + name);
	}

	Path resolve(Config.LogicalPath logical)
	{
		String base = environment.roots.get(logical.root);
		if(base == null)
			throw new IllegalArgumentException("Unknown root: " + logical.root);
		Path result = Paths.get(base);
		for(String part : Config.normalizeRelative(logical.path).split("/"))
			if(!part.isEmpty())
				result = result.resolve(part);
		return result.normalize();
	}

	String logical(String root, String relative)
	{
		return root + ":" + Config.normalizeRelative(relative);
	}

	String physicalToLogical(String input)
	{
		Path candidate = Paths.get(input).toAbsolutePath().normalize();
		for(Map.Entry<String, String> entry : environment.roots.entrySet())
		{
			Path root = Paths.get(entry.getValue()).toAbsolutePath().normalize();
			if(candidate.startsWith(root))
				return logical(entry.getKey(), root.relativize(candidate).toString());
		}
		return input.replace('\\', '/');
	}
}
