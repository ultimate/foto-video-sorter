package ultimate.fotovideosorter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;

final class DateResolver
{
	Result resolve(Path file, List<Config.DateSource> order, ZoneId zone, Duration offset)
	{
		for(Config.DateSource source : order)
		{
			Instant instant = null;
			try
			{
				if(source == Config.DateSource.CAPTURE)
					instant = capture(file, zone);
				else
				{
					BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
					if(source == Config.DateSource.CREATED && a.creationTime() != null)
						instant = a.creationTime().toInstant();
					if(source == Config.DateSource.MODIFIED && a.lastModifiedTime() != null)
						instant = a.lastModifiedTime().toInstant();
				}
			}
			catch(Exception ignored)
			{
			}
			if(instant != null)
				return new Result(instant.plus(offset), source.name());
		}
		return null;
	}

	private Instant capture(Path file, ZoneId zone) throws Exception
	{
		Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
		for(Directory directory : metadata.getDirectories())
			for(Tag tag : directory.getTags())
			{
				String name = tag.getTagName().toLowerCase(Locale.ROOT);
				if(name.contains("date/time original") || name.contains("creation date") || name.contains("creation time") || name.contains("date created"))
				{
					Date date = directory.getDate(tag.getTagType(), java.util.TimeZone.getTimeZone(zone));
					if(date != null)
						return date.toInstant();
				}
			}
		return null;
	}

	static final class Result
	{
		final Instant	instant;
		final String	source;

		Result(Instant i, String s)
		{
			instant = i;
			source = s;
		}
	}
}
