package ultimate.fotovideosorter;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class ConsoleOutput
{
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

	private ConsoleOutput()
	{
	}

	static void info(String message)
	{
		System.out.println(prefix() + message);
	}

	static void error(String message)
	{
		System.err.println(prefix() + message);
	}

	private static String prefix()
	{
		return "[" + TIMESTAMP.format(ZonedDateTime.now()) + "] ";
	}
}
