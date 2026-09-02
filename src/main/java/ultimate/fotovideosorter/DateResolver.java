package ultimate.fotovideosorter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.GpsDirectory;

final class DateResolver
{
	Result resolve(Path file, List<Config.DateSource> order, ZoneId zone, Object cameraOffset)
	{
		Metadata metadata = null;
		boolean metadataRead = false;
		for(Config.DateSource source : order)
		{
			Instant instant = null;
			try
			{
				if(source == Config.DateSource.GPS || source == Config.DateSource.CAPTURE)
				{
					if(!metadataRead)
					{
						metadataRead = true;
						metadata = ImageMetadataReader.readMetadata(file.toFile());
					}
					if(source == Config.DateSource.GPS)
						instant = gps(metadata);
					else
					{
						instant = capture(metadata, zone);
						if(instant != null)
							instant = applyCameraOffset(instant, zone, cameraOffset);
					}
				}
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
				return new Result(instant, source.name());
		}
		return null;
	}

	private Instant gps(Metadata metadata)
	{
		GpsDirectory directory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
		Date date = directory == null ? null : directory.getGpsDate();
		return date == null ? null : date.toInstant();
	}

	private Instant capture(Metadata metadata, ZoneId zone) throws Exception
	{
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

	static void validateCameraOffset(Object value)
	{
		if(value instanceof String)
		{
			Duration.parse((String) value);
			return;
		}
		if(!(value instanceof Map))
			throw new IllegalArgumentException("dateTimeOffset must be an ISO-8601 duration or a map with y, M, d, h, m and s");
		Map<?, ?> values = (Map<?, ?>) value;
		for(Map.Entry<?, ?> entry : values.entrySet())
		{
			String key = String.valueOf(entry.getKey());
			if(!("y".equals(key) || "M".equals(key) || "d".equals(key) || "h".equals(key) || "m".equals(key) || "s".equals(key) || "S".equals(key)))
				throw new IllegalArgumentException("Unknown dateTimeOffset field: " + key);
			if(!(entry.getValue() instanceof Number))
				throw new IllegalArgumentException("dateTimeOffset." + key + " must be a number");
		}
		if(values.containsKey("s") && values.containsKey("S"))
			throw new IllegalArgumentException("Use either dateTimeOffset.s or dateTimeOffset.S, not both");
	}

	static Instant applyCameraOffset(Instant instant, ZoneId zone, Object value)
	{
		if(value instanceof String)
			return instant.plus(Duration.parse((String) value));
		Map<?, ?> values = (Map<?, ?>) value;
		ZonedDateTime adjusted = instant.atZone(zone).plusYears(number(values, "y")).plusMonths(number(values, "M")).plusDays(number(values, "d")).plusHours(number(values, "h"))
				.plusMinutes(number(values, "m")).plusSeconds(values.containsKey("s") ? number(values, "s") : number(values, "S"));
		return adjusted.toInstant();
	}

	private static long number(Map<?, ?> values, String key)
	{
		Object value = values.get(key);
		return value == null ? 0L : ((Number) value).longValue();
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
