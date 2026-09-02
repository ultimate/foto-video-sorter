package ultimate.fotovideosorter;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class AuditRepository implements AutoCloseable
{
	private final Connection connection;

	AuditRepository(Path database) throws SQLException
	{
		this("jdbc:sqlite:" + database.toAbsolutePath());
	}

	static AuditRepository memory() throws SQLException
	{
		return new AuditRepository("jdbc:sqlite::memory:");
	}

	private AuditRepository(String url) throws SQLException
	{
		connection = DriverManager.getConnection(url);
		try (Statement s = connection.createStatement())
		{
			s.execute("PRAGMA busy_timeout=5000");
			s.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)");
			s.execute("INSERT INTO schema_version(version) SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM schema_version)");
			s.execute("CREATE TABLE IF NOT EXISTS audit (" + "id INTEGER PRIMARY KEY AUTOINCREMENT, profile TEXT NOT NULL, logical_source TEXT NOT NULL,"
					+ "physical_source TEXT NOT NULL, logical_destination TEXT NOT NULL, physical_destination TEXT NOT NULL,"
					+ "filename TEXT NOT NULL, size INTEGER NOT NULL, modified_millis INTEGER NOT NULL," + "resolved_date TEXT NOT NULL, date_source TEXT NOT NULL, processed_date TEXT NOT NULL,"
					+ "UNIQUE(profile, logical_source, size, modified_millis))");
			s.execute("CREATE INDEX IF NOT EXISTS idx_audit_source ON audit(logical_source)");
			s.execute("CREATE INDEX IF NOT EXISTS idx_audit_psource ON audit(physical_source)");
			s.execute("CREATE INDEX IF NOT EXISTS idx_audit_destination ON audit(logical_destination)");
			s.execute("CREATE INDEX IF NOT EXISTS idx_audit_pdestination ON audit(physical_destination)");
			s.execute("CREATE INDEX IF NOT EXISTS idx_audit_filename ON audit(filename)");
			s.execute("CREATE INDEX IF NOT EXISTS idx_audit_profile ON audit(profile)");
			s.execute("CREATE INDEX IF NOT EXISTS idx_audit_resolved ON audit(resolved_date)");
			s.execute("CREATE INDEX IF NOT EXISTS idx_audit_processed ON audit(processed_date)");
		}
	}

	boolean contains(String profile, String logicalSource, long size, long modified) throws SQLException
	{
		try (PreparedStatement p = connection.prepareStatement("SELECT 1 FROM audit WHERE profile=? AND logical_source=? AND size=? AND modified_millis=?"))
		{
			p.setString(1, profile);
			p.setString(2, logicalSource);
			p.setLong(3, size);
			p.setLong(4, modified);
			try (ResultSet r = p.executeQuery())
			{
				return r.next();
			}
		}
	}

	void insert(Record r) throws SQLException
	{
		String sql = "INSERT INTO audit(profile,logical_source,physical_source,logical_destination,physical_destination,filename,size,modified_millis,resolved_date,date_source,processed_date) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
		try (PreparedStatement p = connection.prepareStatement(sql))
		{
			p.setString(1, r.profile);
			p.setString(2, r.logicalSource);
			p.setString(3, r.physicalSource);
			p.setString(4, r.logicalDestination);
			p.setString(5, r.physicalDestination);
			p.setString(6, r.filename);
			p.setLong(7, r.size);
			p.setLong(8, r.modifiedMillis);
			p.setString(9, r.resolvedDate.toString());
			p.setString(10, r.dateSource);
			p.setString(11, Instant.now().toString());
			p.executeUpdate();
		}
	}

	List<Record> query(Query q) throws SQLException
	{
		StringBuilder sql = new StringBuilder(
				"SELECT profile,logical_source,physical_source,logical_destination,physical_destination,filename,size,modified_millis,resolved_date,date_source,processed_date FROM audit WHERE 1=1");
		List<Object> args = new ArrayList<Object>();
		add(sql, args, " AND (logical_source=? OR physical_source=?)", q.source, q.source);
		add(sql, args, " AND (logical_destination=? OR physical_destination=?)", q.destination, q.destination);
		add(sql, args, " AND filename=?", q.filename);
		add(sql, args, " AND profile=?", q.profile);
		add(sql, args, " AND processed_date>=?", q.processedFrom);
		add(sql, args, " AND processed_date<=?", q.processedTo);
		add(sql, args, " AND resolved_date>=?", q.resolvedFrom);
		add(sql, args, " AND resolved_date<=?", q.resolvedTo);
		sql.append(" ORDER BY processed_date, id");
		try (PreparedStatement p = connection.prepareStatement(sql.toString()))
		{
			for(int i = 0; i < args.size(); i++)
				p.setObject(i + 1, args.get(i));
			List<Record> result = new ArrayList<Record>();
			try (ResultSet rs = p.executeQuery())
			{
				while(rs.next())
				{
					Record r = new Record();
					r.profile = rs.getString(1);
					r.logicalSource = rs.getString(2);
					r.physicalSource = rs.getString(3);
					r.logicalDestination = rs.getString(4);
					r.physicalDestination = rs.getString(5);
					r.filename = rs.getString(6);
					r.size = rs.getLong(7);
					r.modifiedMillis = rs.getLong(8);
					r.resolvedDate = Instant.parse(rs.getString(9));
					r.dateSource = rs.getString(10);
					r.processedDate = Instant.parse(rs.getString(11));
					result.add(r);
				}
			}
			return result;
		}
	}

	private static void add(StringBuilder sql, List<Object> args, String clause, Object... values)
	{
		if(values.length > 0 && values[0] != null)
		{
			sql.append(clause);
			for(Object value : values)
				args.add(value);
		}
	}

	@Override
	public void close() throws SQLException
	{
		connection.close();
	}

	static final class Record
	{
		String	profile, logicalSource, physicalSource, logicalDestination, physicalDestination, filename, dateSource;
		long	size, modifiedMillis;
		Instant	resolvedDate, processedDate;
	}

	static final class Query
	{
		String source, destination, filename, profile, processedFrom, processedTo, resolvedFrom, resolvedTo;
	}
}
