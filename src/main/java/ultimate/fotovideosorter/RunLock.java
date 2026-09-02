package ultimate.fotovideosorter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class RunLock implements AutoCloseable
{
	private final FileChannel	channel;
	private final FileLock		lock;

	RunLock(Path file) throws IOException
	{
		channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		FileLock acquired;
		try
		{
			acquired = channel.tryLock();
		}
		catch(Exception e)
		{
			acquired = null;
		}
		if(acquired == null)
		{
			channel.close();
			throw new IllegalStateException("Another sorter run holds " + file);
		}
		lock = acquired;
	}

	@Override
	public void close() throws IOException
	{
		try
		{
			lock.release();
		}
		finally
		{
			// Closing the channel also releases its locks. Always do it even if an
			// explicit release fails, otherwise this JVM could retain the lock.
			channel.close();
		}
	}
}
