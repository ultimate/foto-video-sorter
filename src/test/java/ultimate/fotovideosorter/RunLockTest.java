package ultimate.fotovideosorter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLockTest
{
	@TempDir
	Path temp;

	@Test
	void preventsOverlapAndRecoversAfterClose() throws Exception
	{
		Path lock = temp.resolve("audit.db.lock");
		try (RunLock first = new RunLock(lock))
		{
			assertThrows(IllegalStateException.class, () -> new RunLock(lock));
		}
		try (RunLock recovered = new RunLock(lock))
		{
			assertNotNull(recovered);
		}
	}

	@Test
	void releasesLockWhenRunThrows() throws Exception
	{
		Path lock = temp.resolve("audit.db.lock");
		assertThrows(IllegalArgumentException.class, () -> {
			try (RunLock ignored = new RunLock(lock))
			{
				throw new IllegalArgumentException("simulated run failure");
			}
		});

		try (RunLock recovered = new RunLock(lock))
		{
			assertNotNull(recovered);
		}
	}
}
